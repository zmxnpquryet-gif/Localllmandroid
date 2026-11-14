//! UI-independent conversation state machine.
//! Keeps turn rendering records, OpenAI request history, and multi-round tool calls synchronized.

use crate::contracts::{ChatMessage, ChatRequest, ToolCallRecord, ToolDefinition};
use serde_json::{json, Value};

pub const KIND_USER: i32 = 0;
pub const KIND_ASSISTANT: i32 = 1;
pub const KIND_REASONING: i32 = 2;
pub const KIND_TOOL: i32 = 3;
pub const KIND_ERROR: i32 = 4;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TurnEntry {
    pub kind: i32,
    pub role: String,
    pub body: String,
}

#[derive(Default)]
pub struct ConversationSession {
    turns: Vec<TurnEntry>,
    messages: Vec<ChatMessage>,
    active_reasoning_idx: Option<usize>,
    active_assistant_idx: Option<usize>,
    active_assistant_text: String,
    active_tool_calls: Vec<ToolCallRecord>,
    pub had_error: bool,
}

impl ConversationSession {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn turns(&self) -> &[TurnEntry] {
        &self.turns
    }

    pub fn messages(&self) -> &[ChatMessage] {
        &self.messages
    }

    pub fn clear(&mut self) {
        self.turns.clear();
        self.messages.clear();
        self.active_reasoning_idx = None;
        self.active_assistant_idx = None;
        self.active_assistant_text.clear();
        self.active_tool_calls.clear();
        self.had_error = false;
    }

    pub fn begin_user_turn(
        &mut self,
        prompt: &str,
        tools: Vec<ToolDefinition>,
        temperature: f64,
        max_tokens: u32,
    ) -> Option<ChatRequest> {
        let clean = prompt.trim();
        if clean.is_empty() {
            return None;
        }
        self.reset_active_buffers();

        self.turns.push(TurnEntry {
            kind: KIND_USER,
            role: "You".into(),
            body: clean.to_string(),
        });
        self.messages.push(ChatMessage::new("user", clean));

        Some(self.build_request(tools, temperature, max_tokens))
    }

    pub fn build_request(
        &self,
        tools: Vec<ToolDefinition>,
        temperature: f64,
        max_tokens: u32,
    ) -> ChatRequest {
        let mut req = ChatRequest::text(self.messages.clone());
        req.tools = tools;
        req.temperature = temperature;
        req.max_tokens = max_tokens;
        req
    }

    pub fn prepare_continuation_round(&mut self) {
        self.reset_active_buffers();
    }

    fn reset_active_buffers(&mut self) {
        self.had_error = false;
        self.active_reasoning_idx = None;
        self.active_assistant_idx = None;
        self.active_assistant_text.clear();
        self.active_tool_calls.clear();
    }

    pub fn append_reasoning(&mut self, chunk: &str) -> Option<(usize, TurnEntry, bool)> {
        if chunk.is_empty() {
            return None;
        }
        match self.active_reasoning_idx {
            Some(idx) => {
                self.turns[idx].body.push_str(chunk);
                Some((idx, self.turns[idx].clone(), false))
            }
            None => {
                let idx = self.turns.len();
                let entry = TurnEntry {
                    kind: KIND_REASONING,
                    role: "Reasoning".into(),
                    body: chunk.to_string(),
                };
                self.turns.push(entry.clone());
                self.active_reasoning_idx = Some(idx);
                Some((idx, entry, true))
            }
        }
    }

    pub fn append_assistant(&mut self, chunk: &str) -> Option<(usize, TurnEntry, bool)> {
        if chunk.is_empty() {
            return None;
        }
        self.active_assistant_text.push_str(chunk);
        let visible = strip_inline_tool_blocks(&self.active_assistant_text);
        match self.active_assistant_idx {
            Some(idx) => {
                self.turns[idx].body = visible;
                Some((idx, self.turns[idx].clone(), false))
            }
            None => {
                if visible.is_empty() && self.active_assistant_text.contains("<tool_call>") {
                    return None;
                }
                let idx = self.turns.len();
                let entry = TurnEntry {
                    kind: KIND_ASSISTANT,
                    role: "OffconAI".into(),
                    body: visible,
                };
                self.turns.push(entry.clone());
                self.active_assistant_idx = Some(idx);
                Some((idx, entry, true))
            }
        }
    }

    pub fn push_tool_call(&mut self, call: ToolCallRecord) -> TurnEntry {
        let entry = TurnEntry {
            kind: KIND_TOOL,
            role: "Tool call".into(),
            body: call.display_text(),
        };
        self.active_tool_calls.push(call);
        self.turns.push(entry.clone());
        entry
    }

    pub fn record_tool_result(&mut self, call: &ToolCallRecord, index: usize, output: &str, ok: bool) -> TurnEntry {
        let call_id = call
            .id
            .clone()
            .filter(|id| !id.is_empty())
            .unwrap_or_else(|| format!("call_{index}"));
        let status = if ok { "ok" } else { "error" };
        let truncated: String = output.chars().take(4000).collect();
        self.messages.push(ChatMessage::tool_result(
            call_id,
            call.name.clone(),
            truncated.clone(),
        ));
        let entry = TurnEntry {
            kind: KIND_TOOL,
            role: format!("Tool result ({status}) \u{00B7} {}", call.name),
            body: if truncated.trim().is_empty() {
                "(empty output)".into()
            } else {
                truncated
            },
        };
        self.turns.push(entry.clone());
        entry
    }

    pub fn push_error(&mut self, error: &str) -> TurnEntry {
        self.had_error = true;
        let entry = TurnEntry {
            kind: KIND_ERROR,
            role: "Error".into(),
            body: error.to_string(),
        };
        self.turns.push(entry.clone());
        entry
    }

    /// Commits the current assistant turn and returns `(tool_calls, parsed_from_inline_text)`.
    pub fn finish_turn(&mut self) -> (Vec<ToolCallRecord>, bool) {
        let mut calls = std::mem::take(&mut self.active_tool_calls);
        let mut from_inline = false;
        if calls.is_empty() && self.active_assistant_text.contains("<tool_call>") {
            calls = extract_inline_tool_calls(&self.active_assistant_text);
            from_inline = !calls.is_empty();
            for call in &calls {
                self.turns.push(TurnEntry {
                    kind: KIND_TOOL,
                    role: "Tool call".into(),
                    body: call.display_text(),
                });
            }
        }

        let clean_prose = strip_inline_tool_blocks(&self.active_assistant_text);
        self.active_assistant_text.clear();

        if !calls.is_empty() {
            for (idx, call) in calls.iter_mut().enumerate() {
                if call.id.as_deref().unwrap_or("").is_empty() {
                    call.id = Some(format!("call_{idx}"));
                }
            }
            self.messages
                .push(ChatMessage::assistant_with_tool_calls(clean_prose, &calls));
        } else if !clean_prose.is_empty() {
            self.messages
                .push(ChatMessage::new("assistant", clean_prose));
        }

        self.active_reasoning_idx = None;
        self.active_assistant_idx = None;
        (calls, from_inline)
    }
}

fn strip_inline_tool_blocks(text: &str) -> String {
    let mut out = text.to_string();
    while let Some(open) = out.find("<tool_call>") {
        if let Some(rel_close) = out[open + "<tool_call>".len()..].find("</tool_call>") {
            let end = open + "<tool_call>".len() + rel_close + "</tool_call>".len();
            out.replace_range(open..end, "");
        } else {
            out.truncate(open);
            break;
        }
    }
    out.trim().to_string()
}

/// Parses text-based `<tool_call>...</tool_call>` blocks (both JSON and `<function=...>`)
/// emitted by local models when a server does not convert them into `delta.tool_calls`.
pub fn extract_inline_tool_calls(text: &str) -> Vec<ToolCallRecord> {
    let mut out = Vec::new();
    let mut rest = text;
    while let Some(open) = rest.find("<tool_call>") {
        let after_open = &rest[open + "<tool_call>".len()..];
        let Some(close) = after_open.find("</tool_call>") else {
            break;
        };
        let inner = after_open[..close].trim();
        if let Some(call) = parse_inline_call_body(inner, out.len()) {
            out.push(call);
        }
        rest = &after_open[close + "</tool_call>".len()..];
    }
    out
}

fn parse_inline_call_body(inner: &str, idx: usize) -> Option<ToolCallRecord> {
    if let Some(after_fn) = inner.strip_prefix("<function=") {
        let gt = after_fn.find('>')?;
        let name = after_fn[..gt].trim().to_string();
        if name.is_empty() {
            return None;
        }
        let mut args = serde_json::Map::new();
        let mut body = &after_fn[gt + 1..];
        while let Some(p_open) = body.find("<parameter=") {
            let after_p = &body[p_open + "<parameter=".len()..];
            let Some(p_gt) = after_p.find('>') else { break };
            let key = after_p[..p_gt].trim().to_string();
            let val_part = &after_p[p_gt + 1..];
            let Some(p_close) = val_part.find("</parameter>") else { break };
            let val_str = val_part[..p_close].trim();
            args.insert(key, parse_xml_scalar(val_str));
            body = &val_part[p_close + "</parameter>".len()..];
        }
        return Some(ToolCallRecord {
            id: Some(format!("call_{idx}")),
            name,
            arguments: Value::Object(args).to_string(),
        });
    }

    let val: Value = serde_json::from_str(inner).ok()?;
    let target = val.get("function").unwrap_or(&val);
    let name = target.get("name")?.as_str()?.trim().to_string();
    if name.is_empty() {
        return None;
    }
    let arguments = match target.get("arguments").or_else(|| target.get("parameters")) {
        Some(Value::Object(map)) => Value::Object(map.clone()).to_string(),
        Some(Value::String(s)) => s.clone(),
        _ => json!({}).to_string(),
    };
    Some(ToolCallRecord {
        id: Some(format!("call_{idx}")),
        name,
        arguments,
    })
}

fn parse_xml_scalar(raw: &str) -> Value {
    if raw.eq_ignore_ascii_case("true") {
        return Value::Bool(true);
    }
    if raw.eq_ignore_ascii_case("false") {
        return Value::Bool(false);
    }
    let leading_zero = raw.len() > 1 && raw.starts_with('0') && !raw.starts_with("0.");
    if !leading_zero {
        if let Ok(i) = raw.parse::<i64>() {
            return json!(i);
        }
        if let Ok(f) = raw.parse::<f64>() {
            return json!(f);
        }
    }
    Value::String(raw.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_accumulates_reasoning_and_assistant_then_commits_history() {
        let mut session = ConversationSession::new();
        let req = session
            .begin_user_turn("  Explain KV quantization  ", Vec::new(), 0.4, 512)
            .expect("non-empty prompt");
        assert_eq!(req.messages.len(), 1);
        assert_eq!(req.messages[0].content, "Explain KV quantization");
        assert_eq!(req.temperature, 0.4);
        assert_eq!(req.max_tokens, 512);

        let (_, _, inserted_r) = session.append_reasoning("step 1 ").unwrap();
        assert!(inserted_r);
        let (_, r2, inserted_r2) = session.append_reasoning("step 2").unwrap();
        assert!(!inserted_r2);
        assert_eq!(r2.body, "step 1 step 2");

        session.append_assistant("KV cache ");
        session.append_assistant("saves RAM.");
        let (calls, _) = session.finish_turn();
        assert!(calls.is_empty());

        assert_eq!(session.turns().len(), 3);
        assert_eq!(session.messages().len(), 2);
        assert_eq!(session.messages()[1].role, "assistant");
        assert_eq!(session.messages()[1].content, "KV cache saves RAM.");

        session.clear();
        assert!(session.turns().is_empty());
        assert!(session.messages().is_empty());
    }

    #[test]
    fn extracts_inline_json_and_qwen_xml_tool_calls() {
        let xml = "Checking.<tool_call>\n<function=get_weather>\n<parameter=city>\nSeoul\n</parameter>\n</function>\n</tool_call>";
        let calls = extract_inline_tool_calls(xml);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "get_weather");
        assert_eq!(calls[0].arguments, r#"{"city":"Seoul"}"#);
        assert_eq!(strip_inline_tool_blocks(xml), "Checking.");
    }
}
