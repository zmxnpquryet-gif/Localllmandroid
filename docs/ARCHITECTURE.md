# OffconAI architecture

OffconAI keeps model files, prompts, generation, tool results, and conversation state on the user's machine. The Android app and native Rust + Slint desktop application share protocol and design principles while keeping runtime configuration and lifecycle rules tailored to their respective platforms.

## Android application (`:app`)

The Android edition is a modularized Kotlin + Jetpack Compose application:

- `ui/` contains Compose destinations (`ui/chat/`, `ui/models/`, `ui/settings/`, `ui/voice/`, `ui/api/`, `ui/drawer/`), design-system primitives (`ui/glass/`), offline streaming Markdown AST/renderer (`ui/markdown/`), and focused domain controllers (`ui/controllers/`).
- `ui/MainViewModel.kt` is a thin reactive orchestrator delegating to `ConversationController`, `ModelPortfolioController`, `McpSessionController`, `VoiceSessionController`, `ApiServerController`, and `ChatTurnRunner`.
- `data/preferences/AppSettingsStore.kt` is the single source of truth for `GenerationSettings`, Keystore-encrypted credentials (`SecretPrefs`), and per-model tool-dialect overrides across both the UI and foreground API service.
- `engine/LlmEngine.kt` serializes model swaps and inference behind a single `Mutex`, runs memory pre-flight estimates, formats structured prompts, and delegates runtime execution to `LlamaSessionRunner` (GGUF) and `LiteRtSessionRunner` (LiteRT-LM), backed by `ModelFileValidator`.
- `engine/ModelDownloader.kt` resolves Hugging Face LFS SHA-256 metadata and coordinates multi-component downloads through `SegmentedTransferWorker` (HTTP Range segments + zero-copy merge).
- `engine/McpToolProtocol.kt` and `McpClient.kt` parse and execute validated batches of tool calls across five dialects with explicit per-call user approval.
- `data/` owns AES-256-GCM encrypted Room persistence and local model metadata storage.
- `document/` provides encrypted on-device RAG over PDF, Office, and text files with ML Kit OCR fallback for scanned PDFs.
- `server/` exposes the active model over Ollama (`/api/*`) and OpenAI (`/v1/*`) endpoints on port 11434 (`OllamaApiServer`, `HttpWireWriter`, `ApiPayloadMapper`, `ApiServerService`).
- `voice/` and `audio/` keep the Whisper STT → LLM → TTS pipeline distinct from native `libmtmd` Omni audio (`LlamaOmniBackend`, `NativeOmniPromptBuilder`).
- `memory/` estimates weights plus KV cache, tracks abnormal process exits, and runs `MemoryWatchdog` during inference.

`OffconAIApplication` owns the single `LlmEngine` instance so the UI and foreground API server never allocate competing native contexts.

## Desktop application (`desktop/`)

The desktop workspace is a native **Rust + Slint** application (`offcon-desktop`):

- **UI (`desktop/ui/`)**: Modular Slint components (`theme.slint`, `widgets.slint`, `sidebar.slint`, `chat.slint`, `image.slint`, `app.slint`) implementing calm instrument-panel surfaces in Paper Light and Graphite Ink Dark modes.
- **Core (`desktop/src/`)**:
  - `contracts.rs`: Typed contracts for `LlamaCpp`, `Vllm`, and `Strata`, plus `LLM` and `All-in-One` editions.
  - `transport.rs`: OpenAI-compatible HTTP/SSE transport with multi-chunk `tool_calls` assembly and served model-id propagation.
  - `process.rs`: Subprocess management, executable resolution, log tailing, and `sysinfo` CPU/RSS telemetry.
  - `backends/`: Independent `llama_cpp.rs`, `vllm.rs`, and `strata.rs` adapters.
  - `session.rs` & `controller.rs`: UI-independent `ConversationSession` state machine and worker-thread event bridge.

## Build and verification

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
.\gradlew.bat :desktop:test :desktop:installDist
```

Or directly in `desktop/` with Cargo:

```powershell
cd desktop
cargo test
cargo build --release
```

## Compatibility policy

The Android package and application ID remain `com.localllm.android` so existing installations upgrade in place without stranding Keystore-encrypted conversations or downloaded multi-GB models.
