# OffconAI Desktop

The desktop module is a Java 17 Swing application in the same repository. Build it with:

```powershell
.\gradlew.bat :desktop:installDist
```

Run the executable from `desktop/build/install/desktop/bin/desktop` (`.bat` on Windows), or during development:

```powershell
.\gradlew.bat :desktop:run
```

## Editions

- **LLM Edition** contains the chat workspace, backend selection, model lifecycle, and resource monitor.
- **All-in-One Edition** adds image generation and image model settings. The image tab is only shown in this edition.

## Backends

### llama.cpp

The desktop adapter launches `llama-server` with an explicit GGUF path, context size, thread count, and GPU layer count. A non-zero GPU layer setting is a request to llama-server; it is only effective when that executable was built with a compatible offline backend. The log path is shown in the resource monitor. When `GPU layers = 0`, the adapter exercises the CPU path.

The application waits for `/health` before marking the model ready and terminates its owned process on unload or exit.

### vLLM

The vLLM adapter supports two modes:

- Connect to an existing OpenAI-compatible vLLM endpoint.
- Start `python -m vllm.entrypoints.openai.api_server` with model, GPU memory utilization, tensor parallel, and dtype settings.

The server must expose `/v1/models` and `/v1/chat/completions`. A failed startup reports the log tail instead of leaving a half-loaded model.

### Strata

Strata deployments have different APIs across versions. OffconAI therefore exposes an explicit adapter rather than guessing a command line or endpoint. Configure a Strata gateway that exposes the OpenAI-compatible model and chat endpoints, and optionally provide the deployment's launch command. If the endpoint is absent or does not answer `/v1/models`, the backend reports unavailable and does not start another backend's process in disguise.

## Shared protocol layer

All three adapters use typed backend configuration, model load/unload, streaming request handling, tool definition serialization, tool-call event forwarding, explicit errors, and resource status. Model arguments and hardware settings remain backend-specific.

The current chat UI accepts text messages. Tool calls received from a backend remain structured events for an external orchestrator; the desktop shell does not execute arbitrary tools on its own.

## Verification

Unit tests use local HTTP servers to verify:

- SSE streaming aggregation.
- OpenAI tool schema serialization.
- Base64 image response decoding.

External llama.cpp, vLLM, and Strata servers were not available in this environment, so end-to-end generation against those binaries is not reported as verified. The module compiles and its transport tests pass.
