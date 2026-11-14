//! JSON-RPC 2.0 Model Context Protocol (MCP) client for OffconAI Desktop.
//! Supports Streamable HTTP (`application/json` and `text/event-stream` responses),
//! `Mcp-Session-Id` session tracking, and host-safe bearer authentication.

use crate::contracts::ToolDefinition;
use reqwest::blocking::Client;
use reqwest::Url;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct McpConnectionInfo {
    pub server_name: String,
    pub tools: Vec<ToolDefinition>,
    pub latency_ms: u128,
}

#[derive(Clone, Debug)]
pub struct McpCallOutcome {
    pub is_success: bool,
    pub output: String,
}

#[derive(Default)]
struct McpState {
    endpoint: Option<String>,
    session_id: Option<String>,
    bearer_token: Option<String>,
    server_name: String,
    tools: Vec<ToolDefinition>,
}

pub struct McpClient {
    http: Client,
    seq: AtomicU64,
    state: Mutex<McpState>,
}

impl Default for McpClient {
    fn default() -> Self {
        Self::new()
    }
}

impl McpClient {
    pub fn new() -> Self {
        Self {
            http: Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .timeout(Duration::from_secs(30))
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .expect("valid MCP HTTP client"),
            seq: AtomicU64::new(0),
            state: Mutex::new(McpState::default()),
        }
    }

    pub fn disconnect(&self) {
        *self.state.lock().expect("mcp state lock") = McpState::default();
    }

    pub fn current_tools(&self) -> Vec<ToolDefinition> {
        self.state.lock().expect("mcp state lock").tools.clone()
    }

    pub fn is_connected(&self) -> bool {
        self.state.lock().expect("mcp state lock").endpoint.is_some()
    }

    pub fn connect(&self, raw_url: &str, bearer_token: Option<&str>) -> Result<McpConnectionInfo, String> {
        self.disconnect();
        let clean_url = raw_url.trim();
        if clean_url.is_empty() {
            return Err("Enter an MCP server URL.".into());
        }
        let parsed = Url::parse(clean_url).map_err(|_| "Invalid http(s) MCP URL.".to_string())?;
        if parsed.scheme() != "http" && parsed.scheme() != "https" {
            return Err("MCP URL must start with http:// or https://.".into());
        }
        let token = bearer_token
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .map(str::to_string);
        if token.is_some() && !is_token_safe_url(&parsed) {
            return Err(
                "Refusing to send a bearer token over plain HTTP to a public host. Use https://."
                    .into(),
            );
        }

        let started = Instant::now();
        let init_id = self.next_id();
        let init_payload = json!({
            "jsonrpc": "2.0",
            "id": init_id,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "OffconAI-Desktop",
                    "version": env!("CARGO_PKG_VERSION"),
                }
            }
        })
        .to_string();

        let (session_id, init_body) =
            self.post_rpc(clean_url, &init_payload, None, token.as_deref(), Some(init_id))?;
        let server_name = parse_server_name(&init_body)
            .unwrap_or_else(|| parsed.host_str().unwrap_or("MCP").to_string());

        let initialized_notif = json!({
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {}
        })
        .to_string();
        let _ = self.post_rpc(
            clean_url,
            &initialized_notif,
            session_id.as_deref(),
            token.as_deref(),
            None,
        );

        let list_id = self.next_id();
        let list_payload = json!({
            "jsonrpc": "2.0",
            "id": list_id,
            "method": "tools/list",
            "params": {}
        })
        .to_string();
        let (_, list_body) = self.post_rpc(
            clean_url,
            &list_payload,
            session_id.as_deref(),
            token.as_deref(),
            Some(list_id),
        )?;

        if let Some(err) = list_body.get("error").filter(|v| !v.is_null()) {
            let msg = err
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("MCP tools/list returned an error");
            return Err(msg.to_string());
        }

        let tools = parse_tools(&list_body);
        let latency_ms = started.elapsed().as_millis();

        let mut guard = self.state.lock().expect("mcp state lock");
        guard.endpoint = Some(clean_url.to_string());
        guard.session_id = session_id;
        guard.bearer_token = token;
        guard.server_name = server_name.clone();
        guard.tools = tools.clone();

        Ok(McpConnectionInfo {
            server_name,
            tools,
            latency_ms,
        })
    }

    pub fn call_tool(&self, name: &str, arguments_json: &str) -> McpCallOutcome {
        let (endpoint, session_id, token) = {
            let guard = self.state.lock().expect("mcp state lock");
            let Some(ep) = guard.endpoint.clone() else {
                return McpCallOutcome {
                    is_success: false,
                    output: "MCP server is not connected.".into(),
                };
            };
            (ep, guard.session_id.clone(), guard.bearer_token.clone())
        };

        let raw_args = if arguments_json.trim().is_empty() {
            "{}"
        } else {
            arguments_json.trim()
        };
        let args_val: Value = match serde_json::from_str(raw_args) {
            Ok(Value::Object(map)) => Value::Object(map),
            _ => {
                return McpCallOutcome {
                    is_success: false,
                    output: "Tool arguments are not a valid JSON object.".into(),
                };
            }
        };

        let call_id = self.next_id();
        let payload = json!({
            "jsonrpc": "2.0",
            "id": call_id,
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": args_val,
            }
        })
        .to_string();

        let (_, body) = match self.post_rpc(
            &endpoint,
            &payload,
            session_id.as_deref(),
            token.as_deref(),
            Some(call_id),
        ) {
            Ok(res) => res,
            Err(err) => {
                return McpCallOutcome {
                    is_success: false,
                    output: format!("MCP request failed: {err}"),
                };
            }
        };

        if let Some(err) = body.get("error").filter(|v| !v.is_null()) {
            let msg = err
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("Unknown MCP error");
            return McpCallOutcome {
                is_success: false,
                output: format!("MCP tool error: {msg}"),
            };
        }

        let result = body.get("result");
        let failed = result
            .and_then(|r| r.get("isError"))
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let mut texts = Vec::new();
        if let Some(items) = result
            .and_then(|r| r.get("content"))
            .and_then(Value::as_array)
        {
            for item in items {
                let kind = item.get("type").and_then(Value::as_str).unwrap_or("");
                if kind == "text" {
                    if let Some(t) = item.get("text").and_then(Value::as_str) {
                        texts.push(t.to_string());
                    }
                } else if !kind.is_empty() {
                    texts.push(format!("[{kind} content omitted: only text is supported]"));
                }
            }
        }

        McpCallOutcome {
            is_success: !failed,
            output: texts.join("\n"),
        }
    }

    fn next_id(&self) -> u64 {
        self.seq.fetch_add(1, Ordering::Relaxed) + 1
    }

    fn post_rpc(
        &self,
        url: &str,
        payload: &str,
        session_id: Option<&str>,
        token: Option<&str>,
        expected_id: Option<u64>,
    ) -> Result<(Option<String>, Value), String> {
        let mut req = self
            .http
            .post(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .body(payload.to_string());
        if let Some(sid) = session_id.filter(|s| !s.is_empty()) {
            req = req.header("Mcp-Session-Id", sid);
        }
        if let Some(tok) = token {
            req = req.header("Authorization", format!("Bearer {tok}"));
        }

        let resp = req
            .send()
            .map_err(|e| format!("connection failed: {e}"))?;
        let status = resp.status();
        let new_sid = resp
            .headers()
            .get("Mcp-Session-Id")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let raw = resp.text().unwrap_or_default();
        if !status.is_success() {
            return Err(format!("HTTP {status}: {raw}"));
        }
        if expected_id.is_none() {
            return Ok((new_sid, Value::Null));
        }
        let body = extract_json_rpc_message(&raw, expected_id)
            .ok_or_else(|| "MCP response was not valid JSON-RPC.".to_string())?;
        Ok((new_sid, body))
    }
}

fn parse_server_name(envelope: &Value) -> Option<String> {
    let info = envelope.get("result")?.get("serverInfo")?;
    let name = info.get("name")?.as_str()?.trim();
    if name.is_empty() {
        return None;
    }
    let version = info.get("version").and_then(Value::as_str).unwrap_or("").trim();
    if version.is_empty() {
        Some(name.to_string())
    } else {
        Some(format!("{name} {version}"))
    }
}

fn parse_tools(envelope: &Value) -> Vec<ToolDefinition> {
    let Some(arr) = envelope
        .get("result")
        .and_then(|r| r.get("tools"))
        .and_then(Value::as_array)
    else {
        return Vec::new();
    };
    arr.iter()
        .filter_map(|item| {
            let name = item.get("name")?.as_str()?.trim();
            if name.is_empty() {
                return None;
            }
            let description = item
                .get("description")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let parameters = item
                .get("inputSchema")
                .cloned()
                .unwrap_or_else(|| json!({"type": "object"}));
            Some(ToolDefinition {
                name: name.to_string(),
                description,
                parameters,
            })
        })
        .collect()
}

pub(crate) fn extract_json_rpc_message(raw: &str, expected_id: Option<u64>) -> Option<Value> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.starts_with('{') {
        let obj: Value = serde_json::from_str(trimmed).ok()?;
        return matches_id(&obj, expected_id).then_some(obj);
    }
    for line in trimmed.lines() {
        let t = line.trim();
        let Some(payload) = t.strip_prefix("data:") else {
            continue;
        };
        let payload = payload.trim();
        if payload.is_empty() || payload == "[DONE]" {
            continue;
        }
        if let Ok(obj) = serde_json::from_str::<Value>(payload) {
            if matches_id(&obj, expected_id) && (obj.get("result").is_some() || obj.get("error").is_some()) {
                return Some(obj);
            }
        }
    }
    None
}

fn matches_id(obj: &Value, expected_id: Option<u64>) -> bool {
    let Some(expected) = expected_id else {
        return true;
    };
    match obj.get("id") {
        Some(Value::Number(n)) => n.as_u64() == Some(expected),
        Some(Value::String(s)) => s == &expected.to_string(),
        _ => false,
    }
}

pub(crate) fn is_token_safe_url(url: &Url) -> bool {
    if url.scheme() == "https" {
        return true;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    let lower = host.to_lowercase();
    if lower == "localhost" || lower == "::1" || lower == "[::1]" {
        return true;
    }
    let parts: Vec<u8> = lower.split('.').filter_map(|p| p.parse::<u8>().ok()).collect();
    if parts.len() == 4 {
        let (a, b) = (parts[0], parts[1]);
        return a == 127
            || a == 10
            || (a == 192 && b == 168)
            || (a == 172 && (16..=31).contains(&b))
            || (a == 169 && b == 254);
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn refuses_bearer_token_over_cleartext_public_http_but_allows_loopback() {
        assert!(!is_token_safe_url(&Url::parse("http://mcp.example.com/mcp").unwrap()));
        assert!(is_token_safe_url(&Url::parse("https://mcp.example.com/mcp").unwrap()));
        assert!(is_token_safe_url(&Url::parse("http://127.0.0.1:3000/mcp").unwrap()));
        assert!(is_token_safe_url(&Url::parse("http://192.168.1.25:8080/mcp").unwrap()));
    }

    #[test]
    fn connects_lists_tools_and_executes_tool_call_over_json_rpc() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind mock MCP");
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            for _ in 0..4 {
                let Ok((mut stream, _)) = listener.accept() else { break };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut len = 0usize;
                let mut line = String::new();
                let _ = reader.read_line(&mut line);
                loop {
                    line.clear();
                    if reader.read_line(&mut line).unwrap_or(0) == 0 || line.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = line
                        .trim()
                        .strip_prefix("Content-Length:")
                        .or_else(|| line.trim().strip_prefix("content-length:"))
                    {
                        len = v.trim().parse().unwrap_or(0);
                    }
                }
                let mut buf = vec![0u8; len];
                if len > 0 {
                    let _ = reader.read_exact(&mut buf);
                }
                let req_json: Value = serde_json::from_slice(&buf).unwrap_or(Value::Null);
                let method = req_json.get("method").and_then(Value::as_str).unwrap_or("");
                let id = req_json.get("id").cloned().unwrap_or(json!(1));
                let resp_body = match method {
                    "initialize" => json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "result": {
                            "protocolVersion": "2024-11-05",
                            "serverInfo": {"name": "MockMCP", "version": "1.0"}
                        }
                    })
                    .to_string(),
                    "notifications/initialized" => "{}".to_string(),
                    "tools/list" => json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "result": {
                            "tools": [{
                                "name": "get_weather",
                                "description": "Weather lookup",
                                "inputSchema": {"type": "object", "properties": {"city": {"type": "string"}}}
                            }]
                        }
                    })
                    .to_string(),
                    "tools/call" => json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "result": {
                            "content": [{"type": "text", "text": "Seoul: 18C Clear"}]
                        }
                    })
                    .to_string(),
                    _ => "{}".to_string(),
                };
                let http_resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nMcp-Session-Id: sess-42\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    resp_body.len(),
                    resp_body
                );
                let _ = stream.write_all(http_resp.as_bytes());
            }
        });

        let client = McpClient::new();
        let info = client
            .connect(&format!("http://{addr}/mcp"), Some("local-secret"))
            .expect("MCP handshake succeeds");
        assert_eq!(info.server_name, "MockMCP 1.0");
        assert_eq!(info.tools.len(), 1);
        assert_eq!(info.tools[0].name, "get_weather");

        let outcome = client.call_tool("get_weather", r#"{"city":"Seoul"}"#);
        assert!(outcome.is_success);
        assert_eq!(outcome.output, "Seoul: 18C Clear");
    }
}
