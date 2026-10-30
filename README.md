<div align="center">

# ⚡ Local LLM Android

### Run State-of-the-Art LLMs 100% Offline in Your Pocket

**Privacy-first on-device AI · 완전 오프라인 온디바이스 AI · 100% 离线设备端 AI**

[📱 Screenshots](#-screenshots) · [✨ Key Features](#-key-features) · [🤗 Models](#-supported-models--hardware) · [🏛️ Architecture](#️-architecture) · [🌐 API Server](#-on-device-ollama--openai-api-server) · [🚀 Getting Started](#-getting-started)

</div>

<p align="center">
  <img src="docs/screenshots/chat_screen.png" alt="Local LLM Android" width="45%" />
  <img src="docs/screenshots/model_manager.png" alt="Model Manager" width="45%" />
</p>

<p align="center">
  <strong>English</strong> | <a href="README.ko.md">한국어</a> | <a href="README.zh-CN.md">中文</a>
</p>

<p align="center">
  <a href="https://github.com/zmxnpquryet-gif/Localllmandroid/releases/latest"><img src="https://img.shields.io/github/v/release/zmxnpquryet-gif/Localllmandroid?label=Release%20APK&color=success&logo=android" alt="Release APK" /></a>
  <a href="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml"><img src="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Runtime-llama.cpp%20%2B%20LiteRT--LM%20%2B%20SDengine-orange.svg" alt="Dual Engine" />
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.2-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-Ollama%20%7C%20OpenAI%20Port%2011434-blueviolet.svg" alt="API" />
  <img src="https://img.shields.io/badge/MCP-Client%20Ready-1f8f8f.svg" alt="MCP" />
  <img src="https://img.shields.io/badge/Encryption-Keystore%20AES--256--GCM-red.svg" alt="Keystore AES-256" />
  <img src="https://img.shields.io/badge/Telemetry-Zero%20%F0%9F%9A%AB-black.svg" alt="Zero Telemetry" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/UI-EN%20%2F%20KO%20%2F%20ZH%20Switcher-ff69b4.svg" alt="Multilingual" />
</p>

**Local LLM Android** is a privacy-first, fully offline on-device Large Language Model (LLM) application and server for Android. Powered by a **hybrid dual-runtime architecture** combining **llama.cpp** and **Google LiteRT-LM**, it runs state-of-the-art AI models entirely on-device — CPU by default, with GPU/NPU acceleration where the device and runtime support it (automatic CPU fallback) — and no internet or cloud dependency. The UI runs on a first-party **glass design system** with instant **English/Korean/Chinese switching**, chats can call tools through a native **MCP client**, and a first-party MoE research engine (**SDengine**) is being built in the open under the `:engine` module.

---

## 📱 Screenshots

| On-Device Chat & Live Metrics | Model Hub & Download Manager |
| :---: | :---: |
| <img src="docs/screenshots/chat_screen.png" width="100%" /> | <img src="docs/screenshots/model_manager.png" width="100%" /> |
| **Ollama / OpenAI API Server Mode** | **Hands-Free Voice Conversation** |
| <img src="docs/screenshots/api_server.png" width="100%" /> | <img src="docs/screenshots/voice_mode.png" width="100%" /> |

---

## ✨ Key Features

### 🏎️ Dual-Runtime Inference

Two production inference engines behind one abstraction — pick the right tool per model instead of settling for one:

- **llama.cpp (GGUF)** — tens of thousands of community quants (Q4_K_M, Q8_0, K-quants…), reasoning models, chat templates, and `mmproj` vision towers, all from Hugging Face.
- **Google LiteRT-LM** — Google's mobile-optimized runtime with GPU/NPU delegates where supported (automatic CPU fallback), tuned for Snapdragon / MediaTek / Tensor / Exynos SoCs.
- **SDengine** — an experimental first-party MoE engine under `:engine`, developed in the open. See [🧪 SDengine](#-sdengine--experimental-first-party-engine-engine).

### 🌐 On-Device Ollama / OpenAI Server (Port 11434)

Turn your phone into a **local AI server**: Ollama- and OpenAI-compatible HTTP endpoints, LAN toggle with live rebinding, bearer-token auth, and a foreground service that keeps serving after the UI closes. Wire it into **VS Code Continue, Cursor, or plain cURL** — full guide in [🌐 API Server](#-on-device-ollama--openai-api-server).

### 🔌 Native Model Context Protocol (MCP) Client

Chats can call **real tools** — a hardened Streamable-HTTP-first JSON-RPC client with legacy SSE fallback, per-request bearer re-checks, size-capped bodies, and an explicit approval dialog before every call. Every tool result is framed as untrusted external data. Deep dive in [⚡ Engineering Highlights](#-engineering-highlights).

### 🎙️ Zero-Cloud Voice Mode

A hands-free conversational loop, entirely on-device: **local Whisper STT** → on-device LLM generation → **supertonic Korean neural TTS** (downloaded once; device TTS as fallback), visualized with an animated reactive **Voice Orb**. Your voice never leaves the phone.

### 🛡️ Keystore AES-256-GCM & Zero Telemetry

Conversations and credentials live in an **Android Keystore AES-256-GCM** encrypted Room database with AEAD row binding. Zero telemetry, zero analytics, zero external API dependencies for text inference. Details in [🔒 Security & Privacy](#-security--privacy).

### 💎 Bespoke Glass Theme

**Material3 stripped out entirely.** A first-party glass UI toolkit (`GlassTheme`, `GButtons`, `GControls`, `GOverlays`…) covers every screen, with instant EN / KO / ZH switching — no app restart required.

### 🛡️ Android OOM Guard

Estimates the real footprint (weights + KV cache + compute buffers) before mapping a model and shrinks the context instead of walking into an OOM kill. Detects mid-inference process kills, polls available RAM while generating, and stops safely before the LMK does. Details in [⚡ Engineering Highlights](#-engineering-highlights).

### More engineering highlights

- **Real-time hardware benchmarking** — prompt prefill speed (`promptSpeed` tokens/sec), generation decode speed (`tps`), and active context token counts on every message.
- **Streaming reasoning state machine (`ReasoningStreamParser`)** — elegantly parses `<​think>` … `<​/think>` tags across token streaming chunks for reasoning models like DeepSeek-R1.
- **Resilient mobile downloader** — foreground-service downloads with resume support, SHA-256 verification against hashes Hugging Face publishes, and truncated transfers never published as completed.

---

## 🤗 Supported Models & Hardware

| Model | Params | Recommended Quant | File Size (approx.) | Min. Device RAM | Runtime | Notes |
| :--- | :---: | :--- | :---: | :---: | :--- | :--- |
| **Qwen 2.5** | 1.5B | Q4_K_M | ~1.0 GB | 3 GB | llama.cpp | Excellent quality/size ratio; strong multilingual |
| **Qwen 2.5** | 3B | Q4_K_M | ~2.0 GB | 4 GB | llama.cpp | Sweet spot for most 8 GB phones |
| **Qwen 2.5** | 7B | Q4_K_M | ~4.7 GB | 6 GB | llama.cpp | Best in-app quality; needs a flagship or 12 GB device |
| **LFM3** | 1.7B | Q4_K_M | ~1.0 GB | 3 GB | llama.cpp | Liquid AI; hybrid reasoning with 262K-context support |
| **LFM3-VL** | 2B | Q4_K_M + mmproj Q8_0 | ~1.3 GB | 3 GB | llama.cpp | Liquid AI vision model; image input through `mmproj` |
| **DeepSeek-R1-Distill** | 1.5B | Q4_K_M | ~1.1 GB | 3 GB | llama.cpp | Reasoning model — `<​think>` parsing built in |
| **DeepSeek-R1-Distill** | 7B | Q4_K_M | ~4.7 GB | 6 GB | llama.cpp | Chain-of-thought on device |
| **Llama 3.2** | 1B | Q4_K_M | ~0.8 GB | 2.5 GB | llama.cpp | Fast drafts, tool calls |
| **Llama 3.2** | 3B | Q4_K_M | ~2.0 GB | 4 GB | llama.cpp | Balanced daily driver |
| **Gemma 2** | 2B | Q4_K_M / `.task` | ~1.8 GB | 3.5 GB | llama.cpp **or** LiteRT-LM | LiteRT-LM path unlocks GPU/NPU delegates |
| **FunctionGemma** | — | mobile binaries | — | 3 GB | LiteRT-LM | Mobile-optimized function calling |

> **📋 Notes on the table**
>
> - File sizes are typical GGUF Q4_K_M payloads; **Min. Device RAM** is the practical footprint including KV cache and compute buffers, as estimated by the app's OOM Guard before load. Actual numbers depend on context length.
> - Generation speed is device-dependent and displayed live on every message (`promptSpeed` / `tps`): roughly 20+ tok/s for 1–1.5B models, ~10–15 tok/s for 3B, and ~5–10 tok/s for 7B on recent flagship-class SoCs in 4-bit. Use the in-app benchmark for your own device — the app never claims speed it didn't measure.
> - Any community **GGUF** from Hugging Face works through llama.cpp; the list above is a curated starting point, not a whitelist.

---

## 🏛️ Architecture

Rather than being tied to a single engine, Local LLM Android integrates both **llama.cpp** and **Google LiteRT-LM** behind a unified inference abstraction. The upstream llama.cpp engine is vendored as a pinned git submodule (v0.8.0 / b11960), so the GGUF runtime, LFM3 architecture, and mtmd vision support build from source with the app.

```
┌──────────────────────────────────────────────────────────────────────┐
│                            UI (Compose)                              │
│        Chat · Model Manager · API Server · Voice Mode · Settings     │
│                     [ first-party Glass design system ]              │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                           MainViewModel                              │
│      chat sessions · MCP tool loop · voice loop · OOM guard          │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                             LlmEngine                                │
│         one abstraction · streaming · tokenization · cancel          │
└───────┬───────────────────────────┬──────────────────────────┬───────┘
        ▼                           ▼                          ▼
┌───────────────────┐   ┌──────────────────────┐   ┌─────────────────────┐
│  llama.cpp        │   │  Google LiteRT-LM    │   │  SDengine (exp.)    │
│  Runtime          │   │  Runtime             │   │  :engine module     │
├───────────────────┤   ├──────────────────────┤   ├─────────────────────┤
│ • GGUF quants     │   │ • Google mobile      │   │ • MoE-only core     │
│   (Q4_K_M, Q8_0,  │   │   binaries           │   │ • paged expert      │
│   K-quants)       │   │ • Gemma-2,           │   │   streaming         │
│ • Qwen, DeepSeek, │   │   FunctionGemma      │   │ • Kotlin/JVM +      │
│   Llama 3, LFM3   │   │ • GPU/NPU delegates  │   │   C++ JNI reference │
│ • CPU multi-      │   │   where supported    │   │ • GGUF reader, BPE, │
│   threading & GPU │   │   (automatic CPU     │   │   K-quants, RoPE    │
│   layer offload   │   │   fallback)          │   │                     │
│ • mmproj vision   │   │ • low-power mobile   │   │                     │
│   (Qwen2/LFM3-VL) │   │   inference          │   │                     │
└─────────┬─────────┘   └──────────┬───────────┘   └──────────┬──────────┘
          ▼                        ▼                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                            Hardware (SoC)                            │
│           CPU (multi-thread) · GPU delegate · NPU delegate           │
└──────────────────────────────────────────────────────────────────────┘
```

### Why Dual Runtimes?

1. **Broad Model Compatibility (`llama.cpp` / GGUF)**
   - Download and run tens of thousands of community quantized models (Q4_K_M, Q8_0, etc.) directly from Hugging Face.
   - Reasoning models (DeepSeek-R1), GGUF chat templates, and vision towers (`mmproj`) all work through the same runtime.

2. **Native Mobile Hardware Acceleration (`Google LiteRT-LM`)**
   - Direct integration with Google's on-device AI runtime, deeply optimized for mobile SoCs (Snapdragon, MediaTek, Tensor, Exynos).
   - High-throughput, thermal-efficient inference utilizing specialized NPU and GPU delegates.

---

## 🌐 On-Device Ollama / OpenAI API Server

Turn your Android phone or tablet into an autonomous, on-device AI server accessible from desktop applications, IDEs (VS Code, Cursor, Continue), and other local network devices.

### Endpoints

| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/api/tags` | List installed on-device models |
| `POST` | `/api/generate` | Single-turn completion (Ollama spec) |
| `POST` | `/api/chat` | Multi-turn conversational completion (Ollama spec) |
| `POST` | `/api/show` | Model details for the active model |
| `GET` | `/api/version` | Server version (reports Ollama-compatible `0.5.1`) |
| `POST` | `/v1/chat/completions` | OpenAI API compatible endpoint |
| `GET` | `/v1/models` | OpenAI model list |

> Protocol notes: one request per connection (`Connection: close`); request bodies must carry `Content-Length` (chunked request bodies are answered with `411`).

### Network & Access Controls

- **Local Loopback (`127.0.0.1`) Isolation**: secure default mode preventing unauthorized external access.
- **External Network (LAN) Toggle**: instantly bind to `0.0.0.0` with live socket rebinding, enabling access from any computer or device on your local Wi-Fi / LAN network.
- **Bearer Token Authentication**: `sk-local-...` API keys, generated once and persisted on-device (regenerable from the API screen). No TLS: on LAN the bearer travels in plaintext HTTP, so use a trusted network or an SSH tunnel.
- **Foreground Service**: the server keeps running after the app UI is closed (visible notification with a Stop action); killing the process stops it.

### Quickstart: cURL

```bash
curl -X POST http://<YOUR_DEVICE_IP>:11434/api/generate \
  -H "Authorization: Bearer <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello from my terminal!", "stream": false}'
```

### Quickstart: VS Code Continue

Install the [Continue](https://marketplace.visualstudio.com/items?itemName=Continue.continue) extension, enable the LAN toggle in the app's API screen, then add your phone as a provider:

```json
{
  "models": [
    {
      "title": "Phone LLM (Local LLM Android)",
      "provider": "ollama",
      "model": "qwen2.5-3b",
      "apiBase": "http://<YOUR_DEVICE_IP>:11434",
      "apiKey": "sk-local-...",
      "apiType": "openai"
    }
  ]
}
```

The model id is the filename of the GGUF installed on the phone (without the `.gguf` extension) — check `GET /v1/models` or the API screen for the exact list. Any **OpenAI-compatible** client works the same way: point its base URL at `http://<YOUR_DEVICE_IP>:11434/v1`, supply the `sk-local-...` key, and pick the model.

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

## ⚡ Engineering Highlights

<details>
<summary><b>Real-Time Hardware Benchmarking</b></summary>

- **llama.cpp (GGUF)**: `llamaCtx.tokenize()` native tokenization calls give token-count-based `promptSpeed` (prompt tokens/sec) and `tps` (generation tokens/sec).
- **Google LiteRT-LM**: the C++ core's `conv.getBenchmarkInfo()` native metrics are wired in directly for error-free real-time readouts.

</details>

<details>
<summary><b>Interactive Voice Mode</b></summary>

- Hands-free conversational loop: **local Whisper STT** (on-device speech-to-text) → local LLM generation → Text-to-Speech (TTS) response.
- Visualized with an animated reactive Voice Orb.

</details>

<details>
<summary><b>Streaming Reasoning State Machine (<code>ReasoningStreamParser</code>)</b></summary>

- Elegantly parses `<​think>` ... `<​/think>` tags across token streaming chunks for reasoning models like DeepSeek-R1: the buffer state machine separates the thinking process from the final answer even when the tags arrive split across streaming chunks.

</details>

<details>
<summary><b>Model Context Protocol (MCP) Client (<code>McpClient</code>)</b></summary>

- JSON-RPC client speaking Streamable HTTP first, legacy SSE second (with the session id kept and `notifications/initialized` sent on both paths; the legacy SSE stream stays open for the whole session and responses delivered there — including servers that answer only over SSE — are matched to their pending POST by request id). Responses are matched to their request id (stale or unsolicited frames are ignored), bodies and stream lines are size-capped, and tool names are sanitized before they reach the prompt. Optional bearer token, re-checked per request: https anywhere, or plain http only to loopback/private-LAN hosts (mDNS `.local` excluded — it resolves over broadcast, so it needs https); a token saved for one server is never re-sent to a changed address without re-entry, and https is never downgraded on redirect. Legacy-SSE endpoints on another origin are rejected, and `Mcp-Session-Id` is tracked per connection.
- Tools are offered in the prompt and called with one `<​tool_call>{"name": ..., "arguments": {...}}<​/tool_call>` block per reply (only names the connected server advertised; first call per turn only; fenced code examples and unclosed blocks are never calls). Every call waits for an explicit approval dialog (no always-allow, auto-declines after 60 s); a decline ends the tool use and a second straight syntax error ends it too, at most 3 tool rounds per message. Requests are async and cancellable, so Stop never leaves an orphan running on the tool server. Results come back wrapped as untrusted `<tool_result>` data (all tag spellings neutralized), shown as a tool card with the recorded call, and fed to the next turn framed as external data. Whether a small local model calls tools well depends on the model.

</details>

<details>
<summary><b>First-Party Glass Design System (<code>ui/glass</code>) &amp; In-App Multilingual UI</b></summary>

- Material3 stripped out entirely; a custom glass UI toolkit (`GlassTheme`, `GButtons`, `GControls`, `GOverlays`...) covers every screen.
- Instant real-time language switching (system default / English / 한국어 / 中文) from Settings — no app restart required.

</details>

<details>
<summary><b>Out-of-Memory Protection (Android-aware)</b></summary>

- Estimates the real footprint (weights + KV cache + compute buffers) before mapping a model and reduces the context window instead of walking into an OOM kill.
- Writes an in-flight marker per generation: a marker found at startup means the previous process was killed mid-inference, which is recorded and lowers the memory-heavy settings for the next run (memory guard log + incidents in Settings).
- Polls available RAM while generating and stops the run safely when the device runs critical, before the LMK kills the app.

</details>

<details>
<summary><b>Type-Safe Navigation &amp; Resilient Mobile Downloader</b></summary>

- **Type-safe global navigation (`AppScreen`)** & system gesture handling: every screen transition is managed by the `AppScreen` enum, and back gestures on sub-screens return naturally to the main chat.
- Background foreground-service downloads with resume support (interrupted downloads pick up from part files; there is no manual pause button). SHA-256 is verified against the hash Hugging Face publishes (`X-Linked-ETag`, read across the redirect chain) when present — a mismatch deletes the file and fails the download, and files already on disk are re-checked (a mismatch re-downloads). Truncated transfers are never published as completed (byte counts are checked before the file is moved into place, and resume parts are discarded unless their sidecar proves they match the remote revision). Files with no published hash complete with a format check of the weight files only (tiny template sidecars are accepted as-is) and the download card says so instead of claiming verification.

</details>

---

## 🧪 SDengine — Experimental First-Party Engine (`:engine`)

<details>
<summary><b>Scope, internals, and current status</b></summary>

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

</details>

---

## 🚀 Getting Started

### 📲 Install the Release APK

Grab the signed APK from the [**latest release**](https://github.com/zmxnpquryet-gif/Localllmandroid/releases/latest), allow "install unknown apps" for your browser or file manager, and install. Then:

1. Open **Model Manager** and download a model (see [🤗 Supported Models](#-supported-models--hardware) for a starting point).
2. Chat — or open the **API Server** screen, flip the LAN toggle, and point your desktop at `http://<phone-ip>:11434`.
3. *(Optional)* Turn on **Voice Mode** for a fully hands-free, zero-cloud loop.

### 🛠️ Build from Source

<details open>
<summary><b>Prerequisites</b></summary>

- Android Studio Ladybug (2024.2.1+) or newer
- JDK 17
- Android SDK 36 (Min SDK 24)
- Android NDK 27.2 + CMake 3.22+ (required by the `:engine` module and the upstream llama.cpp build)
- Git submodule checkout: clone with `--recurse-submodules` or run `git submodule update --init --recursive`. `app/src/main/cpp/llama.cpp` is pinned to upstream v0.8.0 (b11960), the first release with LFM3 support
- Target device with 64-bit ARM architecture (`arm64-v8a`) or `x86_64`
- No manual native dependencies: the sherpa-onnx STT AAR (~50 MB) is not committed — Gradle fetches it automatically via the `fetchSherpaAar` task (pinned version + SHA-256) before every build

</details>

<details open>
<summary><b>Gradle commands</b></summary>

```bash
# Compile Kotlin sources
./gradlew compileDebugKotlin

# Run unit test suite
./gradlew testDebugUnitTest

# Assemble Release APK
./gradlew assembleRelease
```

</details>

<details>
<summary><b>Release signing</b></summary>

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

</details>

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).

---

<div align="center">

**⭐ Star this repo if you believe your AI should run on your device — not someone else's server.**

</div>
