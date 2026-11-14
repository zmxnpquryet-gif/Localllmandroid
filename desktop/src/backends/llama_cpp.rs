//! Local `llama-server` subprocess adapter.

use crate::contracts::{
    BackendCapabilities, BackendKind, ChatRequest, LlmBackend, ModelSpec, ResourceSnapshot,
    StreamEvent,
};
use crate::process::{
    default_threads, log_path, log_tail, resolve_executable, spawn_with_log, wait_for, ProcessSlot,
};
use crate::transport::OpenAiTransport;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct LlamaCppConfig {
    pub executable: String,
    pub default_model_path: String,
    pub host: String,
    pub port: u16,
    pub context_size: u32,
    pub threads: usize,
    pub gpu_layers: u32,
    pub extra_args: Vec<String>,
}

impl Default for LlamaCppConfig {
    fn default() -> Self {
        Self {
            executable: "llama-server".into(),
            default_model_path: String::new(),
            host: "127.0.0.1".into(),
            port: 8080,
            context_size: 4096,
            threads: default_threads(),
            gpu_layers: 0,
            extra_args: Vec::new(),
        }
    }
}

pub struct LlamaCpp {
    config: LlamaCppConfig,
    transport: OpenAiTransport,
    slot: ProcessSlot,
}

impl LlamaCpp {
    pub fn new(config: LlamaCppConfig) -> Self {
        Self {
            config,
            transport: OpenAiTransport::new(),
            slot: ProcessSlot::new(),
        }
    }

    fn base_url(&self) -> String {
        format!("http://{}:{}", self.config.host, self.config.port)
    }
}

impl LlmBackend for LlamaCpp {
    fn kind(&self) -> BackendKind {
        BackendKind::LlamaCpp
    }

    fn capabilities(&self) -> BackendCapabilities {
        let server_up = self.transport.health_available(&self.base_url());
        let executable = resolve_executable(&self.config.executable).is_some();
        if !server_up && !executable {
            BackendCapabilities::unavailable("llama-server executable was not found")
        } else {
            BackendCapabilities::ready(if server_up {
                "llama-server is healthy and ready"
            } else {
                "llama-server can be launched; model file and backend are verified on load"
            })
        }
    }

    fn load(&self, model: &ModelSpec) -> Result<(), String> {
        self.unload();
        let model_path = model
            .local_path
            .as_deref()
            .map(str::trim)
            .filter(|path| !path.is_empty())
            .unwrap_or(&self.config.default_model_path);
        if !Path::new(model_path).is_file() {
            return Err("llama.cpp requires an existing local GGUF model path".into());
        }
        let executable = resolve_executable(&self.config.executable).ok_or_else(|| {
            format!(
                "llama-server executable was not found: {}",
                self.config.executable
            )
        })?;
        let mut command = Command::new(&executable);
        command
            .arg("--model")
            .arg(model_path)
            .arg("--host")
            .arg(&self.config.host)
            .arg("--port")
            .arg(self.config.port.to_string())
            .arg("--ctx-size")
            .arg(self.config.context_size.to_string())
            .arg("--threads")
            .arg(self.config.threads.to_string())
            .arg("--n-gpu-layers")
            .arg(self.config.gpu_layers.to_string())
            .arg("--jinja");
        command.args(&self.config.extra_args);
        let log = log_path("llama-server.log");
        let child = spawn_with_log(command, &log)?;
        self.slot.store_child(child, log.clone());
        self.slot.set_loaded(model.effective_served_id());
        if !wait_for(Duration::from_secs(90), || {
            self.slot.alive() && self.transport.health_available(&self.base_url())
        }) {
            let tail = log_tail(Some(&log), 20);
            self.slot.clear();
            return Err(format!("llama-server did not become healthy. {tail}"));
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
        if !self.transport.health_available(&self.base_url()) {
            listener(StreamEvent::Error(format!(
                "llama-server is not loaded or reachable at {}",
                self.base_url()
            )));
            return;
        }
        let effective = request.with_default_model(self.slot.loaded().as_deref());
        self.slot.active_requests.fetch_add(1, Ordering::Relaxed);
        self.transport
            .chat(&self.base_url(), None, &effective, cancel, listener);
        self.slot.active_requests.fetch_sub(1, Ordering::Relaxed);
    }

    fn resources(&self) -> ResourceSnapshot {
        let detail = self
            .slot
            .log_path()
            .map(|path| format!("llama-server log: {}", path.display()))
            .unwrap_or_else(|| "unloaded".into());
        self.slot.snapshot(detail)
    }

    fn configuration(&self) -> Vec<(String, String)> {
        vec![
            ("host".into(), self.config.host.clone()),
            ("port".into(), self.config.port.to_string()),
            ("context".into(), self.config.context_size.to_string()),
            ("threads".into(), self.config.threads.to_string()),
            ("gpu_layers".into(), self.config.gpu_layers.to_string()),
            (
                "procurement".into(),
                "GGUF subprocess; effective GPU backend is reported by llama-server logs".into(),
            ),
        ]
    }
}

impl Drop for LlamaCpp {
    fn drop(&mut self) {
        self.unload();
    }
}
