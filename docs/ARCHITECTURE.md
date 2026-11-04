# OffconAI architecture

OffconAI keeps model files, prompts, generation, tool results, and conversation state on the user's machine. The Android app and desktop application share protocol concepts, while each runtime keeps configuration and lifecycle rules that match its backend.

## Android application

The Android edition is one Gradle application module. Its boundaries are:

- `ui/` contains Compose screens, navigation, and presentation-only components. `ui/markdown/` owns the block AST, inline parser, safe link rendering, lexical syntax highlighting, and the readable Unicode TeX subset. Streaming text is reparsed as it arrives; incomplete structures remain visible as their final block type.
- `engine/LlmEngine.kt` owns the active model, serializes inference, chooses llama.cpp or LiteRT-LM, applies memory limits, and exposes streamed generation chunks.
- `engine/llama/` owns the native llama.cpp session. `app/src/main/cpp/llama_jni.cpp` is the JNI adapter over the pinned upstream llama.cpp tree.
- `engine/gguf/` parses GGUF metadata and tensor descriptors without loading tensor payloads into RAM. Model classification uses this reader without depending on a removed inference engine.
- `engine/PromptFormatter.kt` converts structured chat turns into model-specific prompt text. Downloaded Jinja/JSON templates take precedence over built-in formats.
- `engine/McpToolProtocol.kt` parses Hermes JSON and namespaced XML tool requests in batches, validates the complete turn, hides partial streaming blocks, and exposes separate approval/invocation contracts. `McpClient.kt` is the transport; calls execute sequentially and each call still requires explicit approval.
- `data/` owns encrypted Room persistence and model storage. Downloads run through a foreground service and validate published integrity metadata.
- `server/` exposes the active on-device model through Ollama and OpenAI-compatible local endpoints, while sharing the single application-owned engine.
- `voice/` contains the separate speech pipeline available to text and vision models. It records audio, runs on-device Whisper transcription, invokes the selected text model, and sends the response to the selected TTS engine.
- `audio/` defines native Omni capabilities, family/protocol descriptors, Android `AudioRecord`/`AudioTrack` PCM endpoints, and a turn state machine. `LlamaOmniBackend` uses llama.cpp/libmtmd to encode 16 kHz PCM, evaluate it with the language model, and stream synchronized text and 24 kHz PCM. The session handles cancellation, ordered events, interruption, and idempotent cleanup. The Voice screen keeps this native path distinct from the STT → LLM → TTS pipeline.
- `memory/` estimates weights plus KV cache, records abnormal process exits, and watches available memory during inference.

`OffconAIApplication` owns the single `LlmEngine` instance so the UI and local API foreground service never create competing native contexts.

## Runtime capability rules

llama.cpp GPU offload is compiled support, not a device assumption. The JNI bridge exposes `llama_supports_gpu_offload()` and the engine requests GPU layers only when that call returns true. The current Android CMake configuration builds the CPU backend, so Settings explains CPU fallback instead of promising acceleration. Enabling a GPU backend requires adding and validating a compatible backend/toolchain for each packaged ABI.

LiteRT-LM acceleration is selected from the model bundle and accelerator policy. A failed GPU initialization records the failure and falls back to CPU where the engine supports it.

## Desktop application

The `desktop` Gradle application module targets Java 17 and has two editions:

- LLM Edition provides chat, model management, backend selection, and runtime configuration.
- All-in-One Edition adds modular image generation and image model workflows while retaining the LLM workspace.

llama.cpp, Strata, and vLLM have independent configuration and capability models. llama.cpp owns a `llama-server` process, vLLM can attach to an existing OpenAI-compatible server or launch its own process, and Strata requires an explicit deployment gateway because its API differs by release. A backend can be unavailable on the current host without preventing another backend from running. Streaming, tool definitions, tool-call events, errors, and resource telemetry cross the shared boundary; process arguments, model identifiers, server endpoints, and hardware requirements stay backend-specific.

## Build and verification

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
.\gradlew.bat :desktop:test :desktop:installDist
```

The CI workflow compiles the APK, unit tests, instrumented-test APK, desktop transport tests/distribution, and an R8-enabled release build. Release publishing attaches both the APK and a desktop ZIP. Instrumented execution needs a connected Android device or WSA. A successful compilation does not substitute for device execution.

WSA needs both the guest developer mode and a host ADB endpoint. The installed WSA build was observed with guest `adbd` listening on vsock:5555; a host TCP connection to that guest socket is required for the platform ADB client. If WSA does not expose a listening host port, restart WSA after enabling its developer/ADB setting and confirm the endpoint shown by WSA Settings before running `adb connect`.

## Compatibility policy

The Android package and application ID remain `com.localllm.android`. Changing them would install a second app and strand encrypted conversations and local models; project, resource, class, documentation, and user-facing names have moved to OffconAI while the package ID remains stable for upgrade compatibility.

## Verification snapshot

On 2026-11-04, the Android unit suite passed 179 tests, the desktop transport suite passed 2 tests, `assembleDebug`, `assembleDebugAndroidTest`, and the R8 `assembleRelease` build succeeded, `:desktop:installDist` produced launch scripts, and `:app:lintDebug` completed without errors. WSA ADB was authorized and the application installed. With the optimized native build, LFM3 Omni encoded a two-second PCM clip, generated text and 24 kHz speech, delivered PCM to `AudioTrack`, and returned to idle without a fatal exception. WSA supplied silence from its virtual microphone, so speech recognition quality and real ARM latency remain unverified. External llama.cpp, vLLM, and Strata servers were not available for desktop end-to-end generation.
