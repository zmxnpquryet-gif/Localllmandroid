# Local LLM Android

<p align="center">
  <img src="docs/screenshots/chat_screen.png" alt="Local LLM Android" width="45%" />
  <img src="docs/screenshots/model_manager.png" alt="Model Manager" width="45%" />
</p>

<p align="center">
  <strong>English</strong> | <a href="README.ko.md">한국어</a>
</p>

<p align="center">
  <a href="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml"><img src="https://github.com/zmxnpquryet-gif/Localllmandroid/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Runtime-llama.cpp%20%7C%20LiteRT--LM-orange.svg" alt="Runtime" />
  <img src="https://img.shields.io/badge/API-Ollama%20%7C%20OpenAI%20Port%2011434-blueviolet.svg" alt="API" />
  <img src="https://img.shields.io/badge/MCP-Client%20Ready-1f8f8f.svg" alt="MCP" />
  <img src="https://img.shields.io/badge/UI-EN%20%2F%20KO%20Switcher-ff69b4.svg" alt="Multilingual" />
</p>

**Local LLM Android** is a privacy-first, fully offline on-device Large Language Model (LLM) application and server for Android. Powered by a **hybrid dual-runtime architecture** combining **llama.cpp** and **Google LiteRT-LM**, it executes state-of-the-art AI models directly on your device's CPU, GPU, and NPU—without requiring internet connectivity or cloud servers. The UI runs on a first-party **glass design system** with instant **English/Korean switching**, chats can call tools through a native **MCP client**, and a first-party MoE research engine (**SDengine**) is being built in the open under the `:engine` module.

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
   • Universal GGUF format support               • Google mobile-optimized binaries
   • Qwen, DeepSeek-R1, Llama 3, etc.            • Gemma-2, FunctionGemma mobile models
   • CPU multi-threading & GPU layer offload     • Hardware GPU (OpenCL/Vulkan) & NPU delegates
   • mmproj vision tower support (Multimodal)    • Ultra-low power mobile inference

        (+ SDengine — experimental first-party MoE engine under :engine, see below)
```

### Why Dual Runtimes?

1. **Universal Model Compatibility (`llama.cpp` / GGUF)**
   - Download and run tens of thousands of community quantized models (Q4_K_M, Q8_0, etc.) directly from Hugging Face.
   - Comprehensive support for reasoning models (DeepSeek-R1), custom Jinja chat templates, and vision towers (`mmproj`).

2. **Native Mobile Hardware Acceleration (`Google LiteRT-LM`)**
   - Direct integration with Google's on-device AI runtime, deeply optimized for mobile SoCs (Snapdragon, MediaTek, Tensor, Exynos).
   - High-throughput, thermal-efficient inference utilizing specialized NPU and GPU delegates.

---

## 🌐 On-Device Ollama / OpenAI API Server (Port 11434)

Turn your Android phone or tablet into an autonomous, on-device AI server accessible from desktop applications, IDEs (VS Code, Cursor, Continue), and other local network devices.

- **Full Protocol Compatibility**:
  - `GET /api/tags` - List installed on-device models
  - `POST /api/generate` - Single-turn completion (Ollama spec)
  - `POST /api/chat` - Multi-turn conversational completion (Ollama spec)
  - `POST /v1/chat/completions` - OpenAI API compatible endpoint
- **Flexible Network & Access Controls**:
  - **Local Loopback (`127.0.0.1`) Isolation**: Secure default mode preventing unauthorized external access.
  - **External Network (LAN) Toggle**: Instantly bind to `0.0.0.0` with live socket rebinding, enabling access from any computer or device on your local Wi-Fi / LAN network.
  - **Bearer Token Authentication**: Enforces secure `sk-local-...` API keys to protect endpoints against unauthorized requests.
- **cURL Usage**:
  ```bash
  curl -X POST http://<YOUR_DEVICE_IP>:11434/api/generate \
    -H "Authorization: Bearer <API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{"prompt": "Hello from my terminal!", "stream": false}'
  ```

---

## 🔒 Enterprise-Grade Security & Privacy

1. **Hardware-Backed AES-256-GCM Encryption (`ChatCrypto`)**
   - Cryptographic keys are generated and stored in the hardware **Android KeyStore** (Secure Element / TEE).
   - Authenticated Encryption (AEAD) ensures conversational data cannot be intercepted or tampered with.
   - Strict prevention of silent downgrades to insecure hardcoded keys.

2. **Offline Text Inference & Private Storage**
   - Zero telemetry, zero analytics, zero external API dependencies for text inference.
   - Conversations and prompts remain strictly on your physical device in an encrypted Room SQLite database.
   - Note: speech-to-text runs through a **local Whisper STT engine** (`LocalSttEngine`); speech synthesis (TTS) may use the device's built-in speech service.

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
  - Native MCP protocol client with per-session isolation guards, enabling tool use in on-device chats.
- **First-Party Glass Design System (`ui/glass`)**:
  - Material3 stripped out entirely; a custom glass UI toolkit (`GlassTheme`, `GButtons`, `GControls`, `GOverlays`...) covers every screen.
- **In-App Bilingual UI (EN / KO)**:
  - Instant real-time language switching (system default / English / 한국어) from Settings—no app restart required.
- **Smart Out-of-Memory (OOM) Protection**:
  - Inspects `ActivityManager.MemoryInfo` before model allocation to prevent native memory exhaustion crashes (`SIGSEGV`).
- **Resilient Mobile Downloader**:
  - Background foreground-service downloads with pause/resume support and integrity verification.

---

## 🧪 SDengine — Experimental First-Party Engine (`:engine`)

A first-party MoE-only inference core is being built in the open as a separate Android library module.

**Scope (deliberate):** gated-expert Mixture-of-Experts on Android, in C++. Dense-only models and non-MoE hybrids are out of scope.

**What's inside today:**
- GGUF format reader + metadata detection, BPE tokenizer, sampler, KV cache (`GgufReader`, `BpeTokenizer`, `Sampler`, `KvCache`)
- Paged expert streaming: dense weights decode resident under a cap while expert tiles stay on storage and stream through `ExpertPager`
- C++ JNI core via NDK 27 + CMake (`arm64-v8a`, `x86_64`)
- Numerical validation against real MoE weights (live Qwen3-30B-A3B tested via HTTP range streaming)

**Status: TEST BUILD / EXPERIMENTAL — not for production.**
- Reference scalar kernels prove the math; NEON/GPU kernels are next.
- On-device end-to-end inference is not ready yet; the app layer refuses silent inference and surfaces engine advisories (`SDEngine.ADVISORIES`).

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
