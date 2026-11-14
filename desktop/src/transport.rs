//! OpenAI-compatible HTTP transport shared by local server and gateway backends.
//! Serializes model/messages/tools with `serde_json` and parses SSE frames,
//! assembling streamed tool-call deltas into structured `ToolCallRecord`s.

use crate::contracts::{ChatRequest, StreamEvent, ToolCallRecord};
use reqwest::blocking::{Client, Response};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

pub struct OpenAiTransport {
    client: Client,
}

impl Default for OpenAiTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl OpenAiTransport {
    pub fn new() -> Self {
        Self {
            client: Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .redirect(reqwest::redirect::Policy::limited(10))
                .build()
                .expect("the HTTP client configuration is valid"),
        }
    }

    pub fn chat(
        &self,
        base_url: &str,
        api_key: Option<&str>,
        request: &ChatRequest,
        cancel: &AtomicBool,
        listener: &mut dyn FnMut(StreamEvent),
    ) {
        let body = request_body(request);
        let mut builder = self
            .client
            .post(chat_url(base_url))
            .timeout(Duration::from_secs(600))
            .header("Content-Type", "application/json")
            .body(body);
        if let Some(key) = clean_key(api_key) {
            builder = builder.header("Authorization", format!("Bearer {key}"));
        }
        let response = match builder.send() {
            Ok(response) => response,
            Err(error) => {
                listener(StreamEvent::Error(format!("request failed: {error}")));
                return;
            }
        };
        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().unwrap_or_default();
            listener(StreamEvent::Error(format!("HTTP {status}: {body}")));
            return;
        }
        if request.stream {
            read_stream(response, cancel, listener);
        } else {
            let body = response.text().unwrap_or_default();
            let mut assembler = ToolCallAssembler::default();
            let errored = parse_payload(&body, &mut assembler, listener);
            assembler.flush(listener);
            if !errored {
                listener(StreamEvent::Done);
            }
        }
    }

    pub fn models_available(&self, base_url: &str, api_key: Option<&str>) -> bool {
        let mut builder = self
            .client
            .get(models_url(base_url))
            .timeout(Duration::from_secs(8));
        if let Some(key) = clean_key(api_key) {
            builder = builder.header("Authorization", format!("Bearer {key}"));
        }
        builder
            .send()
            .map(|response| response.status().is_success())
            .unwrap_or(false)
    }

    pub fn health_available(&self, base_url: &str) -> bool {
        self.client
            .get(health_url(base_url))
            .timeout(Duration::from_secs(3))
            .send()
            .map(|response| response.status().is_success())
            .unwrap_or(false)
    }
}

fn clean_key(api_key: Option<&str>) -> Option<&str> {
    api_key.map(str::trim).filter(|key| !key.is_empty())
}

pub fn request_body(request: &ChatRequest) -> String {
    let mut body = json!({
        "messages": request.messages,
        "temperature": request.temperature,
        "max_tokens": request.max_tokens,
        "stream": request.stream,
    });
    if let Some(model) = request
        .model
        .as_deref()
        .map(str::trim)
        .filter(|model| !model.is_empty())
    {
        body["model"] = Value::String(model.to_string());
    }
    if !request.tools.is_empty() {
        let tools: Vec<Value> = request
            .tools
            .iter()
            .map(|tool| {
                json!({
                    "type": "function",
                    "function": {
                        "name": tool.name,
                        "description": tool.description,
                        "parameters": tool.parameters,
                    }
                })
            })
            .collect();
        body["tools"] = Value::Array(tools);
    }
    body.to_string()
}

pub fn chat_url(base_url: &str) -> String {
    let base = strip_slash(base_url);
    if base.ends_with("/chat/completions") {
        base
    } else if base.ends_with("/v1") {
        format!("{base}/chat/completions")
    } else {
        format!("{base}/v1/chat/completions")
    }
}

pub fn models_url(base_url: &str) -> String {
    let base = strip_slash(base_url);
    let base = base
        .strip_suffix("/chat/completions")
        .unwrap_or(&base)
        .to_string();
    if base.ends_with("/v1") {
        format!("{base}/models")
    } else {
        format!("{base}/v1/models")
    }
}

pub fn health_url(base_url: &str) -> String {
    format!("{}/health", strip_slash(base_url))
}

fn strip_slash(value: &str) -> String {
    value.trim().trim_end_matches('/').to_string()
}

#[derive(Default)]
struct PartialToolCall {
    id: Option<String>,
    name: String,
    arguments: String,
}

/// Collects streamed OpenAI `delta.tool_calls` fragments indexed by call slot
/// and flushes complete structured `ToolCallRecord`s once the turn completes.
#[derive(Default)]
struct ToolCallAssembler {
    slots: BTreeMap<usize, PartialToolCall>,
}

impl ToolCallAssembler {
    fn ingest(&mut self, calls: &[Value]) {
        for (fallback_idx, item) in calls.iter().enumerate() {
            let index = item
                .get("index")
                .and_then(Value::as_u64)
                .map(|idx| idx as usize)
                .unwrap_or(fallback_idx);
            let slot = self.slots.entry(index).or_default();
            if let Some(id) = item
                .get("id")
                .and_then(Value::as_str)
                .filter(|id| !id.is_empty())
            {
                slot.id = Some(id.to_string());
            }
            if let Some(function) = item.get("function") {
                if let Some(name) = function
                    .get("name")
                    .and_then(Value::as_str)
                    .filter(|name| !name.is_empty())
                {
                    slot.name.push_str(name);
                }
                if let Some(args) = function
                    .get("arguments")
                    .and_then(Value::as_str)
                    .filter(|args| !args.is_empty())
                {
                    slot.arguments.push_str(args);
                }
            }
        }
    }

    fn flush(&mut self, listener: &mut dyn FnMut(StreamEvent)) {
        for (_, partial) in std::mem::take(&mut self.slots) {
            let name = if partial.name.trim().is_empty() {
                "tool".to_string()
            } else {
                partial.name
            };
            listener(StreamEvent::ToolCall(ToolCallRecord {
                id: partial.id,
                name,
                arguments: partial.arguments,
            }));
        }
    }
}

fn read_stream(response: Response, cancel: &AtomicBool, listener: &mut dyn FnMut(StreamEvent)) {
    let mut reader = BufReader::new(response);
    let mut assembler = ToolCallAssembler::default();
    let mut line = String::new();
    loop {
        if cancel.load(Ordering::SeqCst) {
            break;
        }
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {}
            Err(error) => {
                if !cancel.load(Ordering::Relaxed) {
                    listener(StreamEvent::Error(format!(
                        "stream read failed: {error}"
                    )));
                }
                return;
            }
        }
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with(':') {
            continue;
        }
        let payload = trimmed.strip_prefix("data:").unwrap_or(trimmed).trim();
        if payload == "[DONE]" {
            assembler.flush(listener);
            listener(StreamEvent::Done);
            return;
        }
        if parse_payload(payload, &mut assembler, listener) {
            return;
        }
    }
    assembler.flush(listener);
    listener(StreamEvent::Done);
}

/// Returns true when a terminal error event was emitted.
fn parse_payload(
    payload: &str,
    assembler: &mut ToolCallAssembler,
    listener: &mut dyn FnMut(StreamEvent),
) -> bool {
    if payload.trim().is_empty() {
        return false;
    }
    let Ok(value) = serde_json::from_str::<Value>(payload) else {
        return false;
    };
    if let Some(error) = value.get("error").and_then(error_text) {
        listener(StreamEvent::Error(error));
        return true;
    }
    let delta = value
        .get("choices")
        .and_then(Value::as_array)
        .and_then(|choices| choices.first())
        .map(|choice| {
            choice
                .get("delta")
                .or_else(|| choice.get("message"))
                .unwrap_or(choice)
        });

    if let Some(tool_calls) = delta
        .and_then(|d| d.get("tool_calls"))
        .and_then(Value::as_array)
    {
        assembler.ingest(tool_calls);
    }

    let text = delta
        .and_then(|delta| delta.get("content"))
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .map(str::to_string);
    let reasoning = delta
        .and_then(|delta| {
            delta
                .get("reasoning_content")
                .or_else(|| delta.get("reasoning"))
        })
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .map(str::to_string);
    if text.is_some() || reasoning.is_some() {
        listener(StreamEvent::Delta { text, reasoning });
    }
    false
}

fn error_text(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Object(map) => map
            .get("message")
            .and_then(Value::as_str)
            .map(str::to_string)
            .or_else(|| Some(value.to_string())),
        _ => Some(value.to_string()),
    }
}

/// Minimal single-response HTTP server used by unit tests.
#[cfg(test)]
pub(crate) mod test_support {
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};
    use std::thread;

    pub struct Captured {
        pub base_url: String,
        pub request: Arc<Mutex<String>>,
    }

    pub fn serve_once(body: &'static str, content_type: &'static str) -> Captured {
        let listener = TcpListener::bind("127.0.0.1:0").expect("test server binds");
        let address = listener.local_addr().expect("test server address");
        let request = Arc::new(Mutex::new(String::new()));
        let captured = Arc::clone(&request);
        thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("test server accepts");
            let mut reader = BufReader::new(stream.try_clone().expect("test stream clones"));
            let mut content_length = 0usize;
            let mut line = String::new();
            reader.read_line(&mut line).expect("request line");
            loop {
                line.clear();
                if reader.read_line(&mut line).expect("header line") == 0 {
                    break;
                }
                let trimmed = line.trim_end();
                if trimmed.is_empty() {
                    break;
                }
                if let Some(value) = trimmed
                    .strip_prefix("Content-Length:")
                    .or_else(|| trimmed.strip_prefix("content-length:"))
                {
                    content_length = value.trim().parse().unwrap_or(0);
                }
            }
            let mut request_body = vec![0u8; content_length];
            if content_length > 0 {
                reader
                    .read_exact(&mut request_body)
                    .expect("request body");
            }
            *captured.lock().expect("capture lock") =
                String::from_utf8_lossy(&request_body).into_owned();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            stream
                .write_all(response.as_bytes())
                .expect("test response");
        });
        Captured {
            base_url: format!("http://{address}"),
            request,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contracts::{ChatMessage, ToolDefinition};

    #[test]
    fn urls_accept_root_v1_and_full_chat_paths() {
        assert_eq!(
            chat_url("http://127.0.0.1:8000"),
            "http://127.0.0.1:8000/v1/chat/completions"
        );
        assert_eq!(
            chat_url("http://127.0.0.1:8000/v1/"),
            "http://127.0.0.1:8000/v1/chat/completions"
        );
        assert_eq!(
            chat_url("http://127.0.0.1:8000/v1/chat/completions"),
            "http://127.0.0.1:8000/v1/chat/completions"
        );
        assert_eq!(
            models_url("http://127.0.0.1:8000/v1/chat/completions"),
            "http://127.0.0.1:8000/v1/models"
        );
        assert_eq!(
            health_url("http://127.0.0.1:8080/"),
            "http://127.0.0.1:8080/health"
        );
    }

    #[test]
    fn streaming_response_preserves_text_model_id_and_tool_schema() {
        let server = test_support::serve_once(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n\
             data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n\
             data: {\"choices\":[{\"delta\":{\"content\":\"local\"}}]}\n\n\
             data: [DONE]\n\n",
            "text/event-stream",
        );
        let transport = OpenAiTransport::new();
        let cancel = AtomicBool::new(false);
        let mut text = String::new();
        let mut reasoning = String::new();
        let mut finished = false;
        let mut request = ChatRequest::text(vec![ChatMessage::new("user", "hi")]);
        request.model = Some("qwen3.5-4b".into());
        request.tools = vec![ToolDefinition {
            name: "lookup".into(),
            description: "look up".into(),
            parameters: json!({"type": "object"}),
        }];
        request.temperature = 0.2;
        request.max_tokens = 32;
        transport.chat(
            &server.base_url,
            None,
            &request,
            &cancel,
            &mut |event| match event {
                StreamEvent::Delta {
                    text: t,
                    reasoning: r,
                } => {
                    if let Some(chunk) = t {
                        text.push_str(&chunk);
                    }
                    if let Some(chunk) = r {
                        reasoning.push_str(&chunk);
                    }
                }
                StreamEvent::Done => finished = true,
                _ => {}
            },
        );
        assert_eq!(reasoning, "think ");
        assert_eq!(text, "Hello local");
        assert!(finished);
        let captured = server.request.lock().expect("request body");
        assert!(captured.contains("\"model\":\"qwen3.5-4b\""));
        assert!(captured.contains("\"type\":\"function\""));
        assert!(captured.contains("\"name\":\"lookup\""));
        assert!(captured.contains("\"max_tokens\":32"));
    }

    #[test]
    fn streaming_tool_call_deltas_assemble_into_single_structured_record() {
        let server = test_support::serve_once(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}\n\n\
             data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"rust\\\"}\"}}]}}]}\n\n\
             data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n\
             data: [DONE]\n\n",
            "text/event-stream",
        );
        let transport = OpenAiTransport::new();
        let cancel = AtomicBool::new(false);
        let mut tools = Vec::new();
        let request = ChatRequest::text(vec![ChatMessage::new("user", "search rust")]);
        transport.chat(&server.base_url, None, &request, &cancel, &mut |event| {
            if let StreamEvent::ToolCall(call) = event {
                tools.push(call);
            }
        });
        assert_eq!(
            tools,
            vec![ToolCallRecord {
                id: Some("call_1".into()),
                name: "search".into(),
                arguments: "{\"q\":\"rust\"}".into(),
            }]
        );
    }
}
