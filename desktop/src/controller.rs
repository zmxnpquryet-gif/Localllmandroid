//! Slint UI orchestration controller.
//! All blocking HTTP/process/MCP I/O executes on worker threads; the Slint event loop
//! drains typed `UiEvent`s and coordinates multi-round MCP tool execution with user approval.

use crate::backends::{LlamaCpp, LlamaCppConfig, Strata, StrataConfig, Vllm, VllmConfig};
use crate::contracts::{
    BackendKind, ChatRequest, ImageGenerationBackend, ImageRequest, LlmBackend, ModelSpec,
    StreamEvent, ToolCallRecord,
};
use crate::image_backend::OpenAiImageBackend;
use crate::mcp::{McpCallOutcome, McpClient, McpConnectionInfo};
use crate::process::{
    format_resources, parse_command, parse_f64_clamped, parse_port, parse_u32, parse_u32_clamped,
};
use crate::session::{ConversationSession, TurnEntry};
use crate::{ChatTurn, MainWindow};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::cell::RefCell;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

const MAX_TOOL_ROUNDS: u32 = 3;
const MAX_CALLS_PER_TURN: usize = 4;

enum BackendChoice {
    Llama(LlamaCppConfig),
    Vllm(VllmConfig),
    Strata(StrataConfig),
}

impl BackendChoice {
    fn build(self) -> Arc<dyn LlmBackend> {
        match self {
            Self::Llama(config) => Arc::new(LlamaCpp::new(config)),
            Self::Vllm(config) => Arc::new(Vllm::new(config)),
            Self::Strata(config) => Arc::new(Strata::new(config)),
        }
    }
}

enum UiEvent {
    Loaded {
        backend: Arc<dyn LlmBackend>,
        model_label: String,
        status: String,
    },
    LoadFailed(String),
    Unloaded,
    McpConnected(McpConnectionInfo),
    McpFailed(String),
    Delta {
        text: Option<String>,
        reasoning: Option<String>,
    },
    ToolCall(ToolCallRecord),
    ToolExecuted {
        call: ToolCallRecord,
        index: usize,
        outcome: McpCallOutcome,
    },
    Error(String),
    Done {
        cancelled: bool,
    },
    ImageWritten {
        path: PathBuf,
        message: String,
    },
    ImageFailed(String),
}

fn to_chat_turn(entry: TurnEntry) -> ChatTurn {
    ChatTurn {
        kind: entry.kind,
        role: entry.role.into(),
        body: entry.body.into(),
    }
}

pub struct DesktopController {
    ui: slint::Weak<MainWindow>,
    sender: Sender<UiEvent>,
    receiver: Receiver<UiEvent>,
    backend: Option<Arc<dyn LlmBackend>>,
    mcp: Arc<McpClient>,
    turns_model: Rc<VecModel<ChatTurn>>,
    session: ConversationSession,
    generation: Option<Arc<AtomicBool>>,
    pending_calls: VecDeque<(usize, ToolCallRecord)>,
    current_tool_round: u32,
    busy: bool,
    resource_ticks: u32,
}

impl DesktopController {
    fn new(ui: slint::Weak<MainWindow>) -> Self {
        let (sender, receiver) = channel();
        Self {
            ui,
            sender,
            receiver,
            backend: None,
            mcp: Arc::new(McpClient::new()),
            turns_model: Rc::new(VecModel::from(Vec::<ChatTurn>::new())),
            session: ConversationSession::new(),
            generation: None,
            pending_calls: VecDeque::new(),
            current_tool_round: 0,
            busy: false,
            resource_ticks: 0,
        }
    }

    fn install(&self) {
        if let Some(ui) = self.ui.upgrade() {
            ui.set_turns(ModelRc::new(self.turns_model.clone()));
        }
    }

    fn tick(&mut self) {
        while let Ok(event) = self.receiver.try_recv() {
            self.apply(event);
        }
        self.resource_ticks += 1;
        if self.resource_ticks >= 20 {
            self.resource_ticks = 0;
            self.refresh_resources();
        }
    }

    fn apply(&mut self, event: UiEvent) {
        let Some(ui) = self.ui.upgrade() else { return };
        match event {
            UiEvent::Loaded {
                backend,
                model_label,
                status,
            } => {
                let kind = backend.kind();
                self.backend = Some(backend);
                self.set_busy(&ui, false);
                ui.set_model_loaded(true);
                ui.set_model_indicator(model_label.clone().into());
                ui.set_empty_engine(format!("{} \u{00B7} {}", model_label, kind.label()).into());
                ui.set_status(status.into());
                ui.set_status_error(false);
                self.refresh_resources();
            }
            UiEvent::LoadFailed(message) => {
                self.backend = None;
                self.set_busy(&ui, false);
                ui.set_model_loaded(false);
                ui.set_status(format!("Load failed: {message}").into());
                ui.set_status_error(true);
            }
            UiEvent::Unloaded => {
                self.backend = None;
                self.set_busy(&ui, false);
                ui.set_model_loaded(false);
                ui.set_model_indicator("No model".into());
                let kind = BackendKind::from_index(ui.get_backend_index());
                ui.set_empty_engine(idle_engine_label(kind).into());
                ui.set_resources("No runtime resources".into());
                ui.set_status("Runtime unloaded.".into());
                ui.set_status_error(false);
            }
            UiEvent::McpConnected(info) => {
                self.set_busy(&ui, false);
                ui.set_mcp_connected(true);
                let summary = format!(
                    "{} \u{00B7} {} tools ({} ms)",
                    info.server_name,
                    info.tools.len(),
                    info.latency_ms
                );
                ui.set_mcp_status(summary.clone().into());
                ui.set_status(format!("MCP connected: {summary}").into());
                ui.set_status_error(false);
            }
            UiEvent::McpFailed(err) => {
                self.set_busy(&ui, false);
                ui.set_mcp_connected(false);
                ui.set_mcp_status("Disconnected".into());
                ui.set_status(format!("MCP failed: {err}").into());
                ui.set_status_error(true);
            }
            UiEvent::Delta { text, reasoning } => {
                if let Some(reasoning) = reasoning {
                    if let Some((idx, entry, inserted)) = self.session.append_reasoning(&reasoning)
                    {
                        self.sync_turn(&ui, idx, entry, inserted);
                    }
                }
                if let Some(text) = text {
                    if let Some((idx, entry, inserted)) = self.session.append_assistant(&text) {
                        self.sync_turn(&ui, idx, entry, inserted);
                    }
                }
            }
            UiEvent::ToolCall(call) => {
                let entry = self.session.push_tool_call(call);
                self.turns_model.push(to_chat_turn(entry));
                self.touch_transcript(&ui);
            }
            UiEvent::ToolExecuted {
                call,
                index,
                outcome,
            } => {
                let entry =
                    self.session
                        .record_tool_result(&call, index, &outcome.output, outcome.is_success);
                self.turns_model.push(to_chat_turn(entry));
                self.touch_transcript(&ui);
                self.prompt_next_tool_or_continue(&ui);
            }
            UiEvent::Error(message) => {
                let entry = self.session.push_error(&message);
                self.turns_model.push(to_chat_turn(entry));
                self.touch_transcript(&ui);
                ui.set_status(format!("Generation failed: {message}").into());
                ui.set_status_error(true);
            }
            UiEvent::Done { cancelled } => {
                let had_error = self.session.had_error;
                let (calls, from_inline) = self.session.finish_turn();
                if from_inline {
                    for call in &calls {
                        self.turns_model.push(ChatTurn {
                            kind: crate::session::KIND_TOOL,
                            role: "Tool call".into(),
                            body: call.display_text().into(),
                        });
                    }
                    self.touch_transcript(&ui);
                }
                self.generation = None;

                if cancelled || had_error {
                    self.pending_calls.clear();
                    self.clear_tool_approval_ui(&ui);
                    self.set_busy(&ui, false);
                    if cancelled {
                        ui.set_status("Stopped.".into());
                        ui.set_status_error(false);
                    }
                    self.refresh_resources();
                    return;
                }

                if !calls.is_empty()
                    && self.mcp.is_connected()
                    && self.current_tool_round < MAX_TOOL_ROUNDS
                {
                    self.current_tool_round += 1;
                    // Ensure inline-parsed `<tool_call>` calls also appear in the transcript.
                    for (idx, call) in calls.into_iter().take(MAX_CALLS_PER_TURN).enumerate() {
                        self.pending_calls.push_back((idx, call));
                    }
                    self.prompt_next_tool_or_continue(&ui);
                    return;
                }

                self.set_busy(&ui, false);
                ui.set_status("Ready".into());
                ui.set_status_error(false);
                self.refresh_resources();
            }
            UiEvent::ImageWritten { path, message } => {
                self.set_busy(&ui, false);
                match slint::Image::load_from_path(&path) {
                    Ok(image) => {
                        ui.set_image_preview(image);
                        ui.set_image_ready(true);
                        ui.set_status(message.into());
                        ui.set_status_error(false);
                    }
                    Err(error) => {
                        ui.set_status(format!("Image saved, preview failed: {error}").into());
                        ui.set_status_error(true);
                    }
                }
            }
            UiEvent::ImageFailed(message) => {
                self.set_busy(&ui, false);
                ui.set_status(format!("Image generation failed: {message}").into());
                ui.set_status_error(true);
            }
        }
    }

    fn prompt_next_tool_or_continue(&mut self, ui: &MainWindow) {
        if let Some((_, next_call)) = self.pending_calls.front() {
            ui.set_awaiting_tool_approval(true);
            ui.set_pending_tool_title(format!("Approve MCP tool \"{}\"?", next_call.name).into());
            ui.set_pending_tool_args(next_call.arguments.clone().into());
            ui.set_status(format!("Awaiting approval for tool \"{}\"...", next_call.name).into());
            ui.set_status_error(false);
            return;
        }

        self.clear_tool_approval_ui(ui);
        let Some(backend) = self.backend.clone() else {
            self.set_busy(ui, false);
            return;
        };
        self.session.prepare_continuation_round();
        let temperature = parse_f64_clamped(&ui.get_temperature_text(), 0.7, 0.0, 2.0);
        let max_tokens = parse_u32_clamped(&ui.get_max_tokens_text(), 1024, 1, 32_768);
        let request = self
            .session
            .build_request(self.mcp.current_tools(), temperature, max_tokens);
        self.spawn_stream(ui, backend, request);
    }

    fn approve_pending_tool(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        let Some((index, call)) = self.pending_calls.pop_front() else {
            return;
        };
        self.clear_tool_approval_ui(&ui);
        ui.set_status(format!("Running MCP tool \"{}\"...", call.name).into());
        ui.set_status_error(false);
        let mcp = self.mcp.clone();
        let sender = self.sender.clone();
        thread::spawn(move || {
            let outcome = mcp.call_tool(&call.name, &call.arguments);
            let _ = sender.send(UiEvent::ToolExecuted {
                call,
                index,
                outcome,
            });
        });
    }

    fn decline_pending_tool(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        let Some((index, call)) = self.pending_calls.pop_front() else {
            return;
        };
        self.pending_calls.clear();
        self.clear_tool_approval_ui(&ui);
        let entry =
            self.session
                .record_tool_result(&call, index, "The user declined to run this tool.", false);
        self.turns_model.push(to_chat_turn(entry));
        self.touch_transcript(&ui);
        self.set_busy(&ui, false);
        ui.set_status("Tool call declined.".into());
        ui.set_status_error(false);
    }

    fn clear_tool_approval_ui(&self, ui: &MainWindow) {
        ui.set_awaiting_tool_approval(false);
        ui.set_pending_tool_title("".into());
        ui.set_pending_tool_args("".into());
    }

    fn connect_mcp(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        let url = ui.get_mcp_url().to_string();
        let token = ui.get_mcp_token().to_string();
        let mcp = self.mcp.clone();
        let sender = self.sender.clone();
        self.set_busy(&ui, true);
        ui.set_mcp_status("Connecting...".into());
        ui.set_status("Connecting to MCP server...".into());
        ui.set_status_error(false);
        thread::spawn(move || {
            let token_opt = (!token.trim().is_empty()).then_some(token.as_str());
            match mcp.connect(&url, token_opt) {
                Ok(info) => {
                    let _ = sender.send(UiEvent::McpConnected(info));
                }
                Err(err) => {
                    let _ = sender.send(UiEvent::McpFailed(err));
                }
            }
        });
    }

    fn disconnect_mcp(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        self.mcp.disconnect();
        ui.set_mcp_connected(false);
        ui.set_mcp_status("Disconnected".into());
        ui.set_status("MCP server disconnected.".into());
        ui.set_status_error(false);
    }

    fn sync_turn(&self, ui: &MainWindow, idx: usize, entry: TurnEntry, inserted: bool) {
        let turn = to_chat_turn(entry);
        if inserted {
            self.turns_model.push(turn);
        } else {
            use slint::Model;
            self.turns_model.set_row_data(idx, turn);
        }
        self.touch_transcript(ui);
    }

    fn touch_transcript(&self, ui: &MainWindow) {
        ui.set_scroll_tick(ui.get_scroll_tick().wrapping_add(1));
    }

    fn set_busy(&mut self, ui: &MainWindow, busy: bool) {
        self.busy = busy;
        ui.set_busy(busy);
    }

    fn new_chat(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        self.pending_calls.clear();
        self.clear_tool_approval_ui(&ui);
        self.session.clear();
        self.turns_model.set_vec(Vec::new());
        ui.set_status("Started a new conversation.".into());
        ui.set_status_error(false);
    }

    fn load_model(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        let choice = self.backend_choice(&ui);
        let raw_label = ui.get_model_label().to_string();
        let model_label = if raw_label.trim().is_empty() {
            "local-model".to_string()
        } else {
            raw_label.trim().to_string()
        };
        let local_path = match BackendKind::from_index(ui.get_backend_index()) {
            BackendKind::LlamaCpp => Some(ui.get_llama_model_path().to_string()),
            BackendKind::Vllm => Some(ui.get_vllm_model_path().to_string()),
            BackendKind::Strata => None,
        };
        let model = ModelSpec::new(model_label.clone(), local_path);
        let previous = self.backend.take();
        let sender = self.sender.clone();
        self.set_busy(&ui, true);
        ui.set_status("Loading backend...".into());
        ui.set_status_error(false);
        thread::spawn(move || {
            if let Some(previous) = previous {
                previous.unload();
            }
            let backend = choice.build();
            match backend.load(&model) {
                Ok(()) => {
                    let capabilities = backend.capabilities();
                    let status = if capabilities.available {
                        capabilities.reason
                    } else {
                        format!("Unavailable: {}", capabilities.reason)
                    };
                    let _ = sender.send(UiEvent::Loaded {
                        backend,
                        model_label,
                        status,
                    });
                }
                Err(error) => {
                    backend.unload();
                    let _ = sender.send(UiEvent::LoadFailed(error));
                }
            }
        });
    }

    fn unload_model(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        let previous = self.backend.take();
        let sender = self.sender.clone();
        self.set_busy(&ui, true);
        ui.set_status("Unloading runtime...".into());
        ui.set_status_error(false);
        thread::spawn(move || {
            if let Some(previous) = previous {
                previous.unload();
            }
            let _ = sender.send(UiEvent::Unloaded);
        });
    }

    fn backend_changed(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        let kind = BackendKind::from_index(ui.get_backend_index());
        if self.backend.is_none() {
            ui.set_empty_engine(idle_engine_label(kind).into());
        }
        ui.set_status(
            format!(
                "{} selected. Configure settings and load or connect.",
                kind.label()
            )
            .into(),
        );
        ui.set_status_error(false);
    }

    fn backend_choice(&self, ui: &MainWindow) -> BackendChoice {
        match BackendKind::from_index(ui.get_backend_index()) {
            BackendKind::LlamaCpp => BackendChoice::Llama(LlamaCppConfig {
                executable: ui.get_llama_executable().to_string(),
                default_model_path: ui.get_llama_model_path().to_string(),
                port: parse_port(&ui.get_llama_port(), 8080),
                context_size: parse_u32_clamped(&ui.get_llama_context_size(), 4096, 512, 131_072),
                gpu_layers: parse_u32(&ui.get_llama_gpu_layers(), 0),
                ..LlamaCppConfig::default()
            }),
            BackendKind::Vllm => BackendChoice::Vllm(VllmConfig {
                base_url: ui.get_vllm_endpoint().to_string(),
                api_key: std::env::var("VLLM_API_KEY").ok(),
                model_id: ui.get_model_label().to_string(),
                model_path: ui.get_vllm_model_path().to_string(),
                auto_start: ui.get_vllm_managed(),
                port: parse_port(&ui.get_vllm_port(), 8000),
                ..VllmConfig::default()
            }),
            BackendKind::Strata => BackendChoice::Strata(StrataConfig {
                base_url: ui.get_strata_endpoint().to_string(),
                api_key: std::env::var("STRATA_API_KEY").ok(),
                model_id: ui.get_model_label().to_string(),
                launch_command: parse_command(&ui.get_strata_launch()),
                working_directory: Some(PathBuf::from(".")),
                ..StrataConfig::default()
            }),
        }
    }

    fn send_message(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        let Some(backend) = self.backend.clone() else {
            ui.set_status("Load or connect a backend first.".into());
            ui.set_status_error(true);
            return;
        };
        let raw_prompt = ui.get_input_text().to_string();
        let temperature = parse_f64_clamped(&ui.get_temperature_text(), 0.7, 0.0, 2.0);
        let max_tokens = parse_u32_clamped(&ui.get_max_tokens_text(), 1024, 1, 32_768);
        let tools = self.mcp.current_tools();
        let Some(request) = self
            .session
            .begin_user_turn(&raw_prompt, tools, temperature, max_tokens)
        else {
            return;
        };
        ui.set_input_text("".into());
        self.pending_calls.clear();
        self.current_tool_round = 0;
        if let Some(last_user) = self.session.turns().last().cloned() {
            self.turns_model.push(to_chat_turn(last_user));
            self.touch_transcript(&ui);
        }
        self.spawn_stream(&ui, backend, request);
    }

    fn spawn_stream(
        &mut self,
        ui: &MainWindow,
        backend: Arc<dyn LlmBackend>,
        request: ChatRequest,
    ) {
        let cancel = Arc::new(AtomicBool::new(false));
        self.generation = Some(cancel.clone());
        let sender = self.sender.clone();
        self.set_busy(ui, true);
        ui.set_status("Generating...".into());
        ui.set_status_error(false);
        thread::spawn(move || {
            backend.stream(&request, &cancel, &mut |event| match event {
                StreamEvent::Delta { text, reasoning } => {
                    let _ = sender.send(UiEvent::Delta { text, reasoning });
                }
                StreamEvent::ToolCall(call) => {
                    let _ = sender.send(UiEvent::ToolCall(call));
                }
                StreamEvent::Error(error) => {
                    let _ = sender.send(UiEvent::Error(error));
                }
                StreamEvent::Done => {}
            });
            let cancelled = cancel.load(Ordering::SeqCst);
            let _ = sender.send(UiEvent::Done { cancelled });
        });
    }

    fn stop_generation(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if !self.pending_calls.is_empty() {
            self.pending_calls.clear();
            self.clear_tool_approval_ui(&ui);
            self.set_busy(&ui, false);
            ui.set_status("Stopped.".into());
            ui.set_status_error(false);
            return;
        }
        if let Some(cancel) = &self.generation {
            cancel.store(true, Ordering::SeqCst);
            ui.set_status("Stopping...".into());
            ui.set_status_error(false);
        }
    }

    fn generate_image(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.busy {
            return;
        }
        let prompt = ui.get_image_prompt().trim().to_string();
        if prompt.is_empty() {
            ui.set_status("Image prompt is required.".into());
            ui.set_status_error(true);
            return;
        }
        let backend = OpenAiImageBackend::new(
            ui.get_image_endpoint().to_string(),
            std::env::var("IMAGE_API_KEY").ok(),
        );
        let request = ImageRequest {
            model: ui.get_image_model().to_string(),
            prompt,
            size: ui.get_image_size().to_string(),
            output_format: "png".into(),
        };
        let output = PathBuf::from(ui.get_image_output().to_string());
        let sender = self.sender.clone();
        self.set_busy(&ui, true);
        ui.set_status("Generating image...".into());
        ui.set_status_error(false);
        thread::spawn(move || match backend.generate(&request) {
            Ok(result) => {
                let write = (|| -> Result<(), String> {
                    if let Some(parent) = output.parent() {
                        if !parent.as_os_str().is_empty() {
                            std::fs::create_dir_all(parent).map_err(|error| {
                                format!("could not create {}: {error}", parent.display())
                            })?;
                        }
                    }
                    std::fs::write(&output, &result.bytes)
                        .map_err(|error| format!("could not write {}: {error}", output.display()))
                })();
                match write {
                    Ok(()) => {
                        let display_path = output.canonicalize().unwrap_or_else(|_| output.clone());
                        let _ = sender.send(UiEvent::ImageWritten {
                            path: output,
                            message: format!("Image written to {}", display_path.display()),
                        });
                    }
                    Err(error) => {
                        let _ = sender.send(UiEvent::ImageFailed(error));
                    }
                }
            }
            Err(error) => {
                let _ = sender.send(UiEvent::ImageFailed(error));
            }
        });
    }

    fn refresh_resources(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        let Some(backend) = self.backend.as_ref() else {
            ui.set_resources("No runtime resources".into());
            return;
        };
        let snapshot = backend.resources();
        ui.set_resources(format_resources(&snapshot).into());
    }

    fn shutdown(&mut self) {
        if let Some(cancel) = self.generation.take() {
            cancel.store(true, Ordering::SeqCst);
        }
        self.mcp.disconnect();
        if let Some(backend) = self.backend.take() {
            backend.unload();
        }
    }
}

fn idle_engine_label(kind: BackendKind) -> String {
    format!(
        "{} \u{00B7} {}",
        kind.label(),
        if kind.is_local_process() {
            "local runtime"
        } else {
            "endpoint"
        }
    )
}

pub fn run() -> Result<(), slint::PlatformError> {
    let ui = MainWindow::new()?;
    let controller = Rc::new(RefCell::new(DesktopController::new(ui.as_weak())));
    controller.borrow().install();

    macro_rules! wire {
        ($method:ident, $action:ident) => {{
            let weak = Rc::downgrade(&controller);
            ui.$method(move || {
                if let Some(ctrl) = weak.upgrade() {
                    ctrl.borrow_mut().$action();
                }
            });
        }};
    }

    wire!(on_load_requested, load_model);
    wire!(on_unload_requested, unload_model);
    wire!(on_new_chat_requested, new_chat);
    wire!(on_send_requested, send_message);
    wire!(on_stop_requested, stop_generation);
    wire!(on_generate_image_requested, generate_image);
    wire!(on_backend_changed, backend_changed);
    wire!(on_mcp_connect_requested, connect_mcp);
    wire!(on_mcp_disconnect_requested, disconnect_mcp);
    wire!(on_approve_tool_requested, approve_pending_tool);
    wire!(on_decline_tool_requested, decline_pending_tool);

    let timer = slint::Timer::default();
    {
        let weak = Rc::downgrade(&controller);
        timer.start(
            slint::TimerMode::Repeated,
            Duration::from_millis(45),
            move || {
                if let Some(ctrl) = weak.upgrade() {
                    ctrl.borrow_mut().tick();
                }
            },
        );
    }

    let result = ui.run();
    drop(timer);
    controller.borrow_mut().shutdown();
    result
}
