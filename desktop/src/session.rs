//! UI-independent conversation state machine.
//! Keeps turn rendering records and OpenAI request history synchronized and unit-testable.

use crate::contracts::{ChatMessage, ChatRequest, ToolCallRecord};

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
        self.had_error = false;
    }

    /// Starts a user turn and builds a `ChatRequest` covering the conversation history.
    pub fn begin_user_turn(
        &mut self,
        prompt: &str,
        temperature: f64,
        max_tokens: u32,
    ) -> Option<ChatRequest> {
        let clean = prompt.trim();
        if clean.is_empty() {
            return None;
        }
        self.had_error = false;
        self.active_reasoning_idx = None;
        self.active_assistant_idx = None;
        self.active_assistant_text.clear();

        self.turns.push(TurnEntry {
            kind: KIND_USER,
            role: "You".into(),
            body: clean.to_string(),
        });
        self.messages.push(ChatMessage::new("user", clean));

        let mut req = ChatRequest::text(self.messages.clone());
        req.temperature = temperature;
        req.max_tokens = max_tokens;
        Some(req)
    }

    /// Appends a reasoning chunk, returning `(row_index, updated_entry, inserted_new)`.
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

    /// Appends an assistant prose chunk, returning `(row_index, updated_entry, inserted_new)`.
    pub fn append_assistant(&mut self, chunk: &str) -> Option<(usize, TurnEntry, bool)> {
        if chunk.is_empty() {
            return None;
        }
        self.active_assistant_text.push_str(chunk);
        match self.active_assistant_idx {
            Some(idx) => {
                self.turns[idx].body.push_str(chunk);
                Some((idx, self.turns[idx].clone(), false))
            }
            None => {
                let idx = self.turns.len();
                let entry = TurnEntry {
                    kind: KIND_ASSISTANT,
                    role: "OffconAI".into(),
                    body: chunk.to_string(),
                };
                self.turns.push(entry.clone());
                self.active_assistant_idx = Some(idx);
                Some((idx, entry, true))
            }
        }
    }

    pub fn push_tool_call(&mut self, call: &ToolCallRecord) -> TurnEntry {
        let entry = TurnEntry {
            kind: KIND_TOOL,
            role: "Tool call".into(),
            body: call.display_text(),
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

    pub fn finish_turn(&mut self) {
        if !self.active_assistant_text.is_empty() {
            self.messages.push(ChatMessage::new(
                "assistant",
                std::mem::take(&mut self.active_assistant_text),
            ));
        }
        self.active_reasoning_idx = None;
        self.active_assistant_idx = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_accumulates_reasoning_and_assistant_then_commits_history() {
        let mut session = ConversationSession::new();
        let req = session
            .begin_user_turn("  Explain KV quantization  ", 0.4, 512)
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
        session.finish_turn();

        assert_eq!(session.turns().len(), 3);
        assert_eq!(session.messages().len(), 2);
        assert_eq!(session.messages()[1].role, "assistant");
        assert_eq!(session.messages()[1].content, "KV cache saves RAM.");

        session.clear();
        assert!(session.turns().is_empty());
        assert!(session.messages().is_empty());
    }
}
