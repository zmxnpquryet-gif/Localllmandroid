//! OffconAI Desktop: Native Rust + Slint workspace over typed local/gateway LLM backends.

pub mod backends;
pub mod contracts;
pub mod controller;
pub mod image_backend;
pub mod process;
pub mod session;
pub mod transport;

slint::include_modules!();
