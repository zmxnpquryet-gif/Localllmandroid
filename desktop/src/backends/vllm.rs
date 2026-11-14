//! vLLM OpenAI-compatible server adapter (external endpoint or managed subprocess).

use crate::contracts::{
    BackendCapabilities, BackendKind, ChatRequest, LlmBackend, ModelSpec, ResourceSnapshot,
    StreamEvent,
};
use crate::process::{log_path, log_tail, spawn_with_log, wait_for, ProcessSlot};
use crate::transport::OpenAiTransport;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct VllmConfig {
    pub base_url: String,
    pub api_key: Option<String>,
    pub model_id: String,
    pub python_executable: String,
    pub model_path: String,
    pub auto_start: bool,
    pub port: u16,
    pub gpu_memory_utilization: f64,
    pub tensor_parallel: u32,
    pub dtype: String,
    pub extra_args: Vec<String>,
}

impl Default for VllmConfig {
    fn default() -> Self {
        Self {
            base_url: "http://127.0.0.1:8000".into(),
            api_key: None,
            model_id: String::new(),
            python_executable: std::env::var("PYTHON").unwrap_or_else(|_| "python".into()),
            model_path: String::new(),
            auto_start: true,
            port: 8000,
            gpu_memory_utilization: 0.85,
            tensor_parallel: 1,
            dtype: "auto".into(),
            extra_args: Vec::new(),
        }
    }
}

pub struct Vllm {
    config: VllmConfig,
    transport: OpenAiTransport,
    slot: ProcessSlot,
}

impl Vllm {
    pub fn new(config: VllmConfig) -> Self {
        Self {
            config,
            transport: OpenAiTransport::new(),
            slot: ProcessSlot::new(),
        }
    }
}

impl LlmBackend for Vllm {
    fn kind(&self) -> BackendKind {
        BackendKind::Vllm
    }

    fn capabilities(&self) -> BackendCapabilities {
        if self
            .transport
            .models_available(&self.config.base_url, self.config.api_key.as_deref())
        {
            BackendCapabilities::ready("vLLM OpenAI API is reachable")
        } else {
            BackendCapabilities::unavailable(
                "vLLM endpoint is not reachable; enable Spawn vLLM or start vLLM separately",
            )
        }
    }

    fn load(&self, model: &ModelSpec) -> Result<(), String> {
        let served = {
            let explicit = model.effective_served_id();
            if explicit != "local-model" || self.config.model_path.trim().is_empty() {
                explicit.to_string()
            } else {
                self.config.model_path.trim().to_string()
            }
        };

        if self.config.auto_start {
            self.unload();
            let python = if self.config.python_executable.trim().is_empty() {
                "python"
            } else {
                self.config.python_executable.trim()
            };
            let model_path = model
                .local_path
                .as_deref()
                .map(str::trim)
                .filter(|path| !path.is_empty())
                .or_else(|| {
                    let configured = self.config.model_path.trim();
                    (!configured.is_empty()).then_some(configured)
                })
                .unwrap_or(&served);
            if model_path.is_empty() {
                return Err("vLLM managed start requires a model path or Hugging Face id".into());
            }
            let mut command = Command::new(python);
            command
                .args(["-m", "vllm.entrypoints.openai.api_server", "--model"])
                .arg(model_path)
                .args(["--served-model-name", &served])
                .args(["--host", "127.0.0.1", "--port"])
                .arg(self.config.port.to_string())
                .arg("--gpu-memory-utilization")
                .arg(self.config.gpu_memory_utilization.to_string())
                .arg("--tensor-parallel-size")
                .arg(self.config.tensor_parallel.to_string());
            if !self.config.dtype.trim().is_empty() {
                command.arg("--dtype").arg(self.config.dtype.trim());
            }
            command.args(&self.config.extra_args);
            let log = log_path("vllm.log");
            let child = spawn_with_log(command, &log)?;
            self.slot.store_child(child, log.clone());
            self.slot.set_loaded(&served);
            if !wait_for(Duration::from_secs(300), || {
                self.slot.alive()
                    && self
                        .transport
                        .models_available(&self.config.base_url, self.config.api_key.as_deref())
            }) {
                let tail = log_tail(Some(&log), 20);
                self.slot.clear();
                return Err(format!("vLLM did not become ready. {tail}"));
            }
        } else {
            if !self
                .transport
                .models_available(&self.config.base_url, self.config.api_key.as_deref())
            {
                return Err(format!(
                    "External vLLM endpoint is not reachable: {}",
                    self.config.base_url
                ));
            }
            self.slot.mark_external_connected(&served);
        }
        Ok(())
    }

    fn unload(&self) {
        self.slot.clear();
    }

    fn stream(
        &self,
        request: &ChatRequest,
        cancel: &AtomicBool,
        listener: &mut dyn FnMut(StreamEvent),
    ) {
        let loaded = self.slot.loaded();
        let fallback_model = loaded.as_deref().or_else(|| {
            let configured = self.config.model_id.trim();
            (!configured.is_empty()).then_some(configured)
        });
        let effective = request.with_default_model(fallback_model);
        self.slot.active_requests.fetch_add(1, Ordering::Relaxed);
        self.transport.chat(
            &self.config.base_url,
            self.config.api_key.as_deref(),
            &effective,
            cancel,
            listener,
        );
        self.slot.active_requests.fetch_sub(1, Ordering::Relaxed);
    }

    fn resources(&self) -> ResourceSnapshot {
        let detail = self
            .slot
            .log_path()
            .map(|path| format!("vLLM log: {}", path.display()))
            .unwrap_or_else(|| {
                self.slot
                    .loaded()
                    .map(|model| format!("model: {model}"))
                    .unwrap_or_else(|| "external/unloaded".into())
            });
        self.slot.snapshot(detail)
    }

    fn configuration(&self) -> Vec<(String, String)> {
        vec![
            ("endpoint".into(), self.config.base_url.clone()),
            ("managed".into(), self.config.auto_start.to_string()),
            (
                "gpu_memory_utilization".into(),
                self.config.gpu_memory_utilization.to_string(),
            ),
            (
                "tensor_parallel".into(),
                self.config.tensor_parallel.to_string(),
            ),
            ("dtype".into(), self.config.dtype.clone()),
        ]
    }
}

impl Drop for Vllm {
    fn drop(&mut self) {
        self.unload();
    }
}
