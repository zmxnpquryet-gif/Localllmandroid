# OffconAI Desktop

The desktop module (`desktop/`) is a native **Rust + Slint** workspace in the same repository.

## Build & Run

Build and test directly with Cargo:

```powershell
cd desktop
cargo test
cargo build --release
cargo run
```

Or through the root Gradle wrapper tasks:

```powershell
.\gradlew.bat :desktop:test :desktop:installDist
```

The release executable is produced at `desktop/target/release/offcon-desktop` (`.exe` on Windows) and staged into `desktop/build/install/desktop/bin/` by `:desktop:installDist`.

## Architecture

- `desktop/ui/`: Modular Slint declarative UI (`theme.slint`, `widgets.slint`, `sidebar.slint`, `chat.slint`, `image.slint`, `app.slint`) with Paper Light and Graphite Ink Dark modes.
- `desktop/src/contracts.rs`: Typed backend, edition, request, tool-call, and telemetry contracts.
- `desktop/src/transport.rs`: Shared OpenAI-compatible HTTP/SSE client with multi-chunk `tool_calls` assembly and served model-id propagation.
- `desktop/src/process.rs`: Subprocess lifecycle (`ProcessSlot`), executable discovery, log tailing, and `sysinfo` process telemetry.
- `desktop/src/backends/`: Independent typed adapters (`llama_cpp.rs`, `vllm.rs`, `strata.rs`).
- `desktop/src/session.rs`: UI-independent `ConversationSession` state machine.
- `desktop/src/controller.rs`: Worker-thread orchestration bridging backend streams to the Slint event loop.

## Editions

- **LLM Edition** contains the chat workspace, backend selection, sampling controls, model lifecycle, and resource monitor.
- **All-in-One Edition** adds OpenAI-compatible still-image generation (`/v1/images/generations`) and preview.

## Backends

### llama.cpp

Launches `llama-server` with an explicit GGUF path, context size, thread count, and GPU layer count, waits for `/health` before marking the runtime ready, and terminates its owned child process on unload or window close.

### vLLM

Supports attaching to an existing OpenAI-compatible vLLM endpoint or spawning `python -m vllm.entrypoints.openai.api_server` with served model id, GPU memory utilization, tensor parallel, and dtype settings.

### Strata

Connects to an explicit OpenAI-compatible Strata gateway endpoint and optionally launches a deployment command, polling `/v1/models` before marking the session ready.

## Verification

`cargo test` runs local HTTP/SSE transport tests, streamed tool-call assembly tests, base64 image response decoding tests, subprocess/command parser tests, and `ConversationSession` state-machine tests.
