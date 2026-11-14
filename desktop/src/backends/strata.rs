//! Explicit Strata OpenAI-compatible gateway adapter.

use crate::contracts::{
    BackendCapabilities, BackendKind, ChatRequest, LlmBackend, ModelSpec, ResourceSnapshot,
    StreamEvent,
};
use crate::process::{log_path, log_tail, spawn_with_log, wait_for, ProcessSlot};
use crate::transport::OpenAiTransport;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct StrataConfig {
    pub base_url: String,
    pub api_key: Option<String>,
    pub model_id: String,
    pub launch_command: Vec<String>,
    pub working_directory: Option<PathBuf>,
    pub extra_args: Vec<String>,
}

impl Default for StrataConfig {
    fn default() -> Self {
        Self {
            base_url: String::new(),
            api_key: std::env::var("STRATA_API_KEY").ok(),
            model_id: String::new(),
            launch_command: Vec::new(),
            working_directory: None,
            extra_args: Vec::new(),
        }
    }
}

pub struct Strata {
    config: StrataConfig,
    transport: OpenAiTransport,
    slot: ProcessSlot,
}

impl Strata {
    pub fn new(config: StrataConfig) -> Self {
        Self {
            config,
            transport: OpenAiTransport::new(),
            slot: ProcessSlot::new(),
        }
    }
}

impl LlmBackend for Strata {
    fn kind(&self) -> BackendKind {
        BackendKind::Strata
    }

    fn capabilities(&self) -> BackendCapabilities {
        if self.config.base_url.trim().is_empty() {
            return BackendCapabilities::unavailable(
                "Strata deployments expose different APIs; configure an OpenAI-compatible gateway endpoint",
            );
        }
        if self
            .transport
            .models_available(&self.config.base_url, self.config.api_key.as_deref())
        {
            BackendCapabilities::ready("Strata-compatible endpoint is reachable")
        } else {
            BackendCapabilities::unavailable(
                "Configured Strata endpoint did not answer /v1/models",
            )
        }
    }

    fn load(&self, model: &ModelSpec) -> Result<(), String> {
        if self.config.base_url.trim().is_empty() {
            return Err(
                "Strata adapter requires a configured OpenAI-compatible gateway endpoint".into(),
            );
        }
        let served = model.effective_served_id().to_string();

        if !self.config.launch_command.is_empty() {
            self.unload();
            let mut command = Command::new(&self.config.launch_command[0]);
            command.args(&self.config.launch_command[1..]);
            command.args(&self.config.extra_args);
            if let Some(directory) = &self.config.working_directory {
                command.current_dir(directory);
            }
            let log = log_path("strata.log");
            let child = spawn_with_log(command, &log)?;
            self.slot.store_child(child, log.clone());
            self.slot.set_loaded(&served);
            if !wait_for(Duration::from_secs(30), || {
                self.slot.alive()
                    && self
                        .transport
                        .models_available(&self.config.base_url, self.config.api_key.as_deref())
            }) {
                let tail = log_tail(Some(&log), 20);
                self.slot.clear();
                return Err(format!(
                    "Launched Strata command did not answer /v1/models. {tail}"
                ));
            }
            return Ok(());
        }

        if !self
            .transport
            .models_available(&self.config.base_url, self.config.api_key.as_deref())
        {
            self.slot.clear();
            return Err(
                "Strata adapter requires a reachable OpenAI-compatible endpoint; API paths differ by Strata release"
                    .into(),
            );
        }
        self.slot.mark_external_connected(&served);
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
        self.slot.snapshot("Strata adapter".into())
    }

    fn configuration(&self) -> Vec<(String, String)> {
        vec![
            ("endpoint".into(), self.config.base_url.clone()),
            ("protocol".into(), "OpenAI-compatible adapter".into()),
            (
                "launch_command".into(),
                self.config.launch_command.join(" "),
            ),
        ]
    }
}

impl Drop for Strata {
    fn drop(&mut self) {
        self.unload();
    }
}
