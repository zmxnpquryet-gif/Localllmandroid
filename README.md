# Local LLM Android

<p align="center">
  <img src="docs/screenshots/chat_screen.png" alt="Local LLM Android" width="45%" />
  <img src="docs/screenshots/model_manager.png" alt="Model Manager" width="45%" />
</p>

<p align="center">
  <strong>English</strong> | <a href="README.ko.md">한국어</a> | <a href="README.zh-CN.md">中文</a>
</p>

<p align="center">
  <a href="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml"><img src="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.2-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Runtime-llama.cpp%20%7C%20LiteRT--LM-orange.svg" alt="Runtime" />
  <img src="https://img.shields.io/badge/API-Ollama%20%7C%20OpenAI%20Port%2011434-blueviolet.svg" alt="API" />
  <img src="https://img.shields.io/badge/MCP-Client%20Ready-1f8f8f.svg" alt="MCP" />
  <img src="https://img.shields.io/badge/UI-EN%20%2F%20KO%20%2F%20ZH%20Switcher-ff69b4.svg" alt="Multilingual" />
</p>

**Local LLM Android** is a privacy-first, fully offline on-device Large Language Model (LLM) application and server for Android. Powered by a **hybrid dual-runtime architecture** combining **llama.cpp** and **Google LiteRT-LM**, it runs state-of-the-art AI models entirely on-device—CPU by default, with GPU/NPU acceleration where the device and runtime support it (automatic CPU fallback), and no internet or cloud dependency. The UI runs on a first-party **glass design system** with instant **English/Korean/Chinese switching**, chats can call tools through a native **MCP client**, and a first-party MoE research engine (**SDengine**) is being built in the open under the `:engine` module.

---

## 📱 Screenshots

| On-Device Chat & Live Metrics | Model Hub & Download Manager |
| :---: | :---: |
| <img src="docs/screenshots/chat_screen.png" width="100%" /> | <img src="docs/screenshots/model_manager.png" width="100%" /> |
| **Ollama / OpenAI API Server Mode** | **Hands-Free Voice Conversation** |
| <img src="docs/screenshots/api_server.png" width="100%" /> | <img src="docs/screenshots/voice_mode.png" width="100%" /> |

---

## 🏛️ Dual-Runtime Hybrid Architecture

Rather than being tied to a single engine, Local LLM Android integrates both **llama.cpp** and **Google LiteRT-LM** behind a unified inference abstraction.

```
                  ┌────────────────────────────────────────┐
                  │            MainViewModel               │
                  └───────────────────┬────────────────────┘
                                      │
                         ┌────────────┴────────────┐
                         │        LlmEngine        │
                         └──────┬────────────┬─────┘
                                │            │
                ┌───────────────┘            └───────────────┐
                ▼                                            ▼
      [ llama.cpp Runtime ]                        [ Google LiteRT-LM Runtime ]
   • Broad GGUF quant support                     • Google mobile-optimized binaries
     (Q4_K_M, Q8_0, K-quants, ...)                  • Gemma-2, FunctionGemma mobile models
   • Qwen, DeepSeek-R1, Llama 3, etc.             • GPU/NPU delegates where supported
   • CPU multi-threading & GPU layer offload        (automatic CPU fallback)
   • mmproj vision tower support (Multimodal)     • Low-power mobile inference

        (+ SDengine — experimental first-party MoE engine under :engine, see below)
```

### Why Dual Runtimes?

1. **Broad Model Compatibility (`llama.cpp` / GGUF)**
   - Download and run tens of thousands of community quantized models (Q4_K_M, Q8_0, etc.) directly from Hugging Face.
   - Reasoning models (DeepSeek-R1), GGUF chat templates, and vision towers (`mmproj`) all work through the same runtime.

2. **Native Mobile Hardware Acceleration (`Google LiteRT-LM`)**
   - Direct integration with Google's on-device AI runtime, deeply optimized for mobile SoCs (Snapdragon, MediaTek, Tensor, Exynos).
   - High-throughput, thermal-efficient inference utilizing specialized NPU and GPU delegates.

---

## 🌐 On-Device Ollama / OpenAI API Server (Port 11434)

Turn your Android phone or tablet into an autonomous, on-device AI server accessible from desktop applications, IDEs (VS Code, Cursor, Continue), and other local network devices.

- **Ollama- & OpenAI-Compatible Endpoints**:
  - `GET /api/tags` - List installed on-device models
  - `POST /api/generate` - Single-turn completion (Ollama spec)
  - `POST /api/chat` - Multi-turn conversational completion (Ollama spec)
  - `POST /api/show` - Model details for the active model
  - `GET /api/version` - Server version (reports Ollama-compatible `0.5.1`)
  - `POST /v1/chat/completions` - OpenAI API compatible endpoint
  - `GET /v1/models` - OpenAI model list
  - Protocol notes: one request per connection (`Connection: close`); request bodies must carry `Content-Length` (chunked request bodies are answered with `411`).
- **Flexible Network & Access Controls**:
  - **Local Loopback (`127.0.0.1`) Isolation**: Secure default mode preventing unauthorized external access.
  - **External Network (LAN) Toggle**: Instantly bind to `0.0.0.0` with live socket rebinding, enabling access from any computer or device on your local Wi-Fi / LAN network.
  - **Bearer Token Authentication**: `sk-local-...` API keys, generated once and persisted on-device (regenerable from the API screen). No TLS: on LAN the bearer travels in plaintext HTTP, so use a trusted network or an SSH tunnel.
  - **Foreground Service**: the server keeps running after the app UI is closed (visible notification with a Stop action); killing the process stops it.
- **cURL Usage**:
  ```bash
  curl -X POST http://<YOUR_DEVICE_IP>:11434/api/generate \
    -H "Authorization: Bearer <API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{"prompt": "Hello from my terminal!", "stream": false}'
  ```

---

## 🔒 Security & Privacy

1. **Keystore AES-256-GCM Encryption (`ChatCrypto`)**
   - The key is generated non-exportable inside the **Android Keystore** (no hardcoded fallback, fail closed). Whether that Keystore is backed by a TEE/StrongBox is device-dependent: it is read back via `KeyInfo` and exposed by `keyProtection()` (the instrumented test logs the level per device) instead of being claimed.
   - Authenticated Encryption (AEAD) with per-row binding: ciphertexts carry the conversation/message id and column as associated data, so a row copied into another slot fails authentication. Current format is `v2:`; older rows are upgraded in place on launch.
   - Credentials (Hugging Face token, API server key, MCP bearer token) are stored only as Keystore ciphertext (`SecretPrefs`); legacy plaintext copies are migrated in a single atomic commit on next access and removed (model-file tokens are re-encrypted on next save). A broken ciphertext falls back to a surviving plaintext once, then re-encrypts; otherwise undecryptable values read as absent, never as valid.

2. **Offline Text Inference & Private Storage**
   - Zero telemetry, zero analytics, zero external API dependencies for text inference.
   - Conversations and prompts remain strictly on your physical device in an encrypted Room SQLite database.
   - Note: speech-to-text runs through a **local Whisper STT engine** (`LocalSttEngine`); speech synthesis uses the on-device **supertonic Korean neural voice** (`LocalTtsEngine`, downloaded once from Hugging Face) with the device's built-in speech service as fallback.

---

## ⚡ Key Highlights

- **Real-Time Hardware Benchmarking**:
  - Displays prompt prefill speed (`promptSpeed` tokens/sec), generation decode speed (`tps`), and active context token counts on every message.
- **Interactive Voice Mode**:
   - Hands-free conversational loop: **local Whisper STT** (on-device speech-to-text) → local LLM generation → Text-to-Speech (TTS) response.
  - Visualized with an animated reactive Voice Orb.
- **Streaming Reasoning State Machine (`ReasoningStreamParser`)**:
  - Elegantly parses `<think>` ... `</think>` tags across token streaming chunks for reasoning models like DeepSeek-R1.
- **Model Context Protocol (MCP) Client (`McpClient`)**:
  - JSON-RPC client speaking Streamable HTTP first, legacy SSE second (with the session id kept and `notifications/initialized` sent on both paths; the legacy path consumes responses answered on the POST endpoint — servers that stream every response on the SSE channel only are not supported). Responses are matched to their request id (stale or unsolicited frames are ignored), bodies and stream lines are size-capped, and tool names are sanitized before they reach the prompt. Optional bearer token, re-checked per request: https anywhere, or plain http only to loopback/private-LAN hosts (mDNS `.local` excluded — it resolves over broadcast, so it needs https); a token saved for one server is never re-sent to a changed address without re-entry, and https is never downgraded on redirect. Legacy-SSE endpoints on another origin are rejected, and `Mcp-Session-Id` is tracked per connection.
  - Tools are offered in the prompt and called with one `<tool_call>{"name": ..., "arguments": {...}}</tool_call>` block per reply (only names the connected server advertised; first call per turn only; fenced code examples and unclosed blocks are never calls). Every call waits for an explicit approval dialog (no always-allow, auto-declines after 60 s); a decline ends the tool use and a second straight syntax error ends it too, at most 3 tool rounds per message. Requests are async and cancellable, so Stop never leaves an orphan running on the tool server. Results come back wrapped as untrusted `<tool_result>` data (all tag spellings neutralized), shown as a tool card with the recorded call, and fed to the next turn framed as external data. Whether a small local model calls tools well depends on the model.
- **First-Party Glass Design System (`ui/glass`)**:
  - Material3 stripped out entirely; a custom glass UI toolkit (`GlassTheme`, `GButtons`, `GControls`, `GOverlays`...) covers every screen.
- **In-App Multilingual UI (EN / KO / ZH)**:
  - Instant real-time language switching (system default / English / 한국어 / 中文) from Settings—no app restart required.
- **Out-of-Memory Protection (Android-aware)**:
  - Estimates the real footprint (weights + KV cache + compute buffers) before mapping a model and reduces the context window instead of walking into an OOM kill.
  - Writes an in-flight marker per generation: a marker found at startup means the previous process was killed mid-inference, which is recorded and lowers the memory-heavy settings for the next run (memory guard log + incidents in Settings).
  - Polls available RAM while generating and stops the run safely when the device runs critical, before the LMK kills the app.
- **Resilient Mobile Downloader**:
  - Background foreground-service downloads with resume support (interrupted downloads pick up from part files; there is no manual pause button). SHA-256 is verified against the hash Hugging Face publishes (`X-Linked-ETag`, read across the redirect chain) when present — a mismatch deletes the file and fails the download, and files already on disk are re-checked (a mismatch re-downloads). Truncated transfers are never published as completed (byte counts are checked before the file is moved into place, and resume parts are discarded unless their sidecar proves they match the remote revision). Files with no published hash complete with a format check of the weight files only (tiny template sidecars are accepted as-is) and the download card says so instead of claiming verification.

---

## 🧪 SDengine — Experimental First-Party Engine (`:engine`)

A first-party MoE-only inference core is being built in the open as a separate Android library module.

**Scope (deliberate):** gated-expert Mixture-of-Experts on Android (Kotlin/JVM core plus a portable C++ JNI reference; NEON/GPU kernels pending). Dense-only models and non-MoE hybrids are out of scope.

**What's inside today:**
- GGUF format reader + metadata detection, BPE tokenizer, sampler, KV cache (`GgufReader`, `BpeTokenizer`, `Sampler`, `KvCache`)
- Quantized weight dequantizers: Q4_0/Q4_1/Q5_0/Q5_1/Q8_0/Q8_1 plus the K-quants Q4_K/Q5_K/Q6_K, pinned by spec vectors against the ggml reference layout (`Quant`, `QuantTest`)
- Paged expert streaming: dense weights decode resident under a cap while expert tiles stay on storage and stream through `ExpertPager`, so MoE models larger than RAM can decode
- Architecture-matched RoPE (NeoX half-split vs NORM interleaved, per llama.cpp's per-architecture table), selected from the GGUF architecture at load
- Portable C++ JNI reference via NDK 27 + CMake (`arm64-v8a`, `x86_64`): a `dot()` parity check only, no NEON yet — the real math is JVM scalar kernels
- Numerical validation against real MoE weights via HTTP range streaming (manual/nightly `RealMoETest` with `LOCALENGINE_MOE_URL`; never runs in normal CI)
- Nightly golden harness: greedy continuation must match llama.cpp token-for-token on a real MoE GGUF (`GoldenLogitTest` + `nightly-real-model.yml`; scheduled/nightly only, not in normal CI)
- End-to-end generation over a gated-expert GGUF, covered by `SDEngineEndToEndTest` (synthetic MoE model: load, stream tokens, cancel, resident-cap enforcement)

**Status: TEST BUILD / EXPERIMENTAL — runs, but not for production.**
- Reference scalar kernels prove the math; NEON/GPU kernels are next, so real MoE models decode at single-digit tokens per second on device.
- Imported models default to llama.cpp; SDengine is strictly opt-in per model (Model Manager runtime override). A failed SDengine load retries once on llama.cpp instead of leaving no model, and the engine advisories (`SDEngine.ADVISORIES`) stay visible.

---

## 🛠️ Build & Installation

### Prerequisites
- Android Studio Ladybug (2024.2.1+) or newer
- JDK 17
- Android SDK 36 (Min SDK 24)
- Android NDK 27.2 + CMake 3.22+ (required by the `:engine` module)
- Target device with 64-bit ARM architecture (`arm64-v8a`) or `x86_64`
- No manual native dependencies: the sherpa-onnx STT AAR (~50 MB) is not committed — Gradle fetches it automatically via the `fetchSherpaAar` task (pinned version + SHA-256) before every build

### Gradle Commands
```bash
# Compile Kotlin sources
./gradlew compileDebugKotlin

# Run unit test suite
./gradlew testDebugUnitTest

# Assemble Release APK
./gradlew assembleRelease
```

### Release signing

Release builds are never debug-signed. Supply the upload keystore credentials either as
environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, optional
`KEY_ALIAS`) or — for local builds — in a gitignored `keystore.properties` at the
repository root:

```properties
storeFile=my-upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Instrumented tests run against a connected device or emulator:
`./gradlew connectedDebugAndroidTest`.

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
