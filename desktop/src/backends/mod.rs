//! Modular backend adapters.
//! Each adapter keeps typed hardware/server configuration while sharing `LlmBackend`.

pub mod llama_cpp;
pub mod strata;
pub mod vllm;

pub use llama_cpp::{LlamaCpp, LlamaCppConfig};
pub use strata::{Strata, StrataConfig};
pub use vllm::{Vllm, VllmConfig};
