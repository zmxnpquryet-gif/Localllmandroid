//! Typed contracts shared by the desktop UI controller and every backend adapter.
//! Hardware arguments stay backend-specific; transport events stay uniform.

use serde::Serialize;
use serde_json::Value;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BackendKind {
    LlamaCpp,
    Vllm,
    Strata,
}

impl BackendKind {
    pub fn from_index(index: i32) -> Self {
        match index {
            1 => Self::Vllm,
            2 => Self::Strata,
            _ => Self::LlamaCpp,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::LlamaCpp => "llama.cpp",
            Self::Vllm => "vLLM",
            Self::Strata => "Strata",
        }
    }

    pub fn is_local_process(self) -> bool {
        self == Self::LlamaCpp
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DesktopEdition {
    Llm,
    AllInOne,
}

impl DesktopEdition {
    pub fn from_index(index: i32) -> Self {
        if index == 1 {
            Self::AllInOne
        } else {
            Self::Llm
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Llm => "LLM",
            Self::AllInOne => "All-in-One",
        }
    }
}

#[derive(Clone, Debug)]
pub struct ModelSpec {
    pub id: String,
    pub local_path: Option<String>,
    pub served_model_id: Option<String>,
}

impl ModelSpec {
    pub fn new(id: impl Into<String>, local_path: Option<String>) -> Self {
        let id = id.into();
        Self {
            served_model_id: Some(id.clone()),
            id,
            local_path,
        }
    }

    pub fn effective_served_id(&self) -> &str {
        self.served_model_id
            .as_deref()
            .map(str::trim)
            .filter(|id| !id.is_empty())
            .unwrap_or(&self.id)
    }
}

#[derive(Clone, Debug, Default)]
pub struct BackendCapabilities {
    pub available: bool,
    pub streaming: bool,
    pub tool_calling: bool,
    pub image_generation: bool,
    pub native_audio: bool,
    pub reason: String,
}

impl BackendCapabilities {
    pub fn unavailable(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
            ..Self::default()
        }
    }

    pub fn ready(reason: impl Into<String>) -> Self {
        Self {
            available: true,
            streaming: true,
            tool_calling: true,
            reason: reason.into(),
            ..Self::default()
        }
    }
}

#[derive(Clone, Debug)]
pub struct ResourceSnapshot {
    /// `None` for external endpoints that own no child process.
    pub process_id: Option<u32>,
    pub cpu_percent: Option<f32>,
    pub resident_bytes: Option<u64>,
    pub active_requests: u32,
    pub uptime: Duration,
    pub detail: String,
}

impl Default for ResourceSnapshot {
    fn default() -> Self {
        Self {
            process_id: None,
            cpu_percent: None,
            resident_bytes: None,
            active_requests: 0,
            uptime: Duration::ZERO,
            detail: "external/unloaded".into(),
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct ToolDefinition {
    pub name: String,
    pub description: String,
    /// A JSON Schema object embedded directly in the OpenAI function specification.
    pub parameters: Value,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolCallRecord {
    pub id: Option<String>,
    pub name: String,
    pub arguments: String,
}

impl ToolCallRecord {
    pub fn display_text(&self) -> String {
        let args = self.arguments.trim();
        let args = if args.is_empty() { "{}" } else { args };
        match self.id.as_deref().filter(|id| !id.is_empty()) {
            Some(id) => format!("{}({args})  [{id}]", self.name),
            None => format!("{}({args})", self.name),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OpenAiFunctionCall {
    pub name: String,
    pub arguments: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OpenAiToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: OpenAiFunctionCall,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<OpenAiToolCall>>,
}

impl ChatMessage {
    pub fn new(role: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            role: role.into(),
            content: content.into(),
            name: None,
            tool_call_id: None,
            tool_calls: None,
        }
    }

    pub fn assistant_with_tool_calls(content: impl Into<String>, calls: &[ToolCallRecord]) -> Self {
        let mapped = calls
            .iter()
            .enumerate()
            .map(|(idx, call)| OpenAiToolCall {
                id: call
                    .id
                    .clone()
                    .filter(|id| !id.is_empty())
                    .unwrap_or_else(|| format!("call_{idx}")),
                kind: "function".into(),
                function: OpenAiFunctionCall {
                    name: call.name.clone(),
                    arguments: if call.arguments.trim().is_empty() {
                        "{}".into()
                    } else {
                        call.arguments.clone()
                    },
                },
            })
            .collect();
        Self {
            role: "assistant".into(),
            content: content.into(),
            name: None,
            tool_call_id: None,
            tool_calls: Some(mapped),
        }
    }

    pub fn tool_result(
        tool_call_id: impl Into<String>,
        name: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        Self {
            role: "tool".into(),
            content: content.into(),
            name: Some(name.into()),
            tool_call_id: Some(tool_call_id.into()),
            tool_calls: None,
        }
    }
}

#[derive(Clone, Debug)]
pub struct ChatRequest {
    pub model: Option<String>,
    pub messages: Vec<ChatMessage>,
    pub tools: Vec<ToolDefinition>,
    pub temperature: f64,
    pub max_tokens: u32,
    pub stream: bool,
}

impl ChatRequest {
    pub fn text(messages: Vec<ChatMessage>) -> Self {
        Self {
            model: None,
            messages,
            tools: Vec::new(),
            temperature: 0.7,
            max_tokens: 1024,
            stream: true,
        }
    }

    pub fn with_default_model(&self, fallback_model: Option<&str>) -> Self {
        if self.model.as_deref().is_some_and(|m| !m.trim().is_empty()) {
            return self.clone();
        }
        let mut copy = self.clone();
        copy.model = fallback_model
            .map(str::trim)
            .filter(|m| !m.is_empty())
            .map(str::to_string);
        copy
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum StreamEvent {
    Delta {
        text: Option<String>,
        reasoning: Option<String>,
    },
    ToolCall(ToolCallRecord),
    Error(String),
    Done,
}

pub trait LlmBackend: Send + Sync {
    fn kind(&self) -> BackendKind;
    fn capabilities(&self) -> BackendCapabilities;
    fn load(&self, model: &ModelSpec) -> Result<(), String>;
    fn unload(&self);
    fn stream(
        &self,
        request: &ChatRequest,
        cancel: &AtomicBool,
        listener: &mut dyn FnMut(StreamEvent),
    );
    fn resources(&self) -> ResourceSnapshot;
    fn configuration(&self) -> Vec<(String, String)>;
}

pub trait ImageGenerationBackend {
    fn available(&self) -> bool;
    fn generate(&self, request: &ImageRequest) -> Result<ImageResult, String>;
}

#[derive(Clone, Debug)]
pub struct ImageRequest {
    pub model: String,
    pub prompt: String,
    pub size: String,
    pub output_format: String,
}

#[derive(Clone, Debug)]
pub struct ImageResult {
    pub bytes: Vec<u8>,
    pub media_type: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backend_and_edition_indices_are_total() {
        assert_eq!(BackendKind::from_index(-4), BackendKind::LlamaCpp);
        assert_eq!(BackendKind::from_index(0), BackendKind::LlamaCpp);
        assert_eq!(BackendKind::from_index(1), BackendKind::Vllm);
        assert_eq!(BackendKind::from_index(2), BackendKind::Strata);
        assert_eq!(BackendKind::from_index(99), BackendKind::LlamaCpp);
        assert_eq!(DesktopEdition::from_index(1), DesktopEdition::AllInOne);
        assert_eq!(DesktopEdition::from_index(0), DesktopEdition::Llm);
    }

    #[test]
    fn model_spec_defaults_served_id_to_the_label() {
        let model = ModelSpec::new("local-model", None);
        assert_eq!(model.id, "local-model");
        assert_eq!(model.effective_served_id(), "local-model");
    }

    #[test]
    fn request_applies_default_model_only_when_missing() {
        let req = ChatRequest::text(vec![ChatMessage::new("user", "hi")]);
        let filled = req.with_default_model(Some("qwen3.5-4b"));
        assert_eq!(filled.model.as_deref(), Some("qwen3.5-4b"));

        let explicit = ChatRequest {
            model: Some("custom-id".into()),
            ..req
        };
        assert_eq!(
            explicit.with_default_model(Some("qwen3.5-4b")).model.as_deref(),
            Some("custom-id")
        );
    }
}
