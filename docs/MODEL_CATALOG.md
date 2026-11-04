# OffconAI Model Catalog

The Android model picker is curated for current small GGUF models that fit phones. File sizes below are the actual Hugging Face blob sizes, not estimates. A model listed here must still pass the in-app memory guard before loading.

## Native voice

### LFM3 Omni 2B

- Repository: [LiquidAI/LFM3-Omni-2B-GGUF](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF)
- Main GGUF: LFM3-Omni-2B-Q4_K_M.gguf (1,099,630,080 bytes)
- Projector: mmproj-LFM3-Omni-2B-Q8_0.gguf (506,842,528 bytes)
- Input: text, images, and 16 kHz PCM audio. The model card also lists video, but the current Android picker accepts images only.
- Output: text and 24 kHz PCM speech
- License: LFM Open License v1.0
- Runtime: llama.cpp v0.8.0 or newer
- OffconAI path: the model receives audio embeddings directly through libmtmd. The native voice screen does not invoke Whisper STT or a separate TTS engine.

## Text and multimodal models

| Model | GGUF | Weight bytes | Projector | Projector bytes | License |
| --- | --- | ---: | --- | ---: | --- |
| LFM3 1.7B | LFM3-1.7B-Q4_K_M.gguf | 1,099,627,808 | none | 0 | LFM Open License v1.0 |
| LFM3-VL 2B | LFM3-VL-2B-Q4_K_M.gguf | 1,099,627,808 | mmproj-LFM3-VL-2B-Q8_0.gguf | 203,031,872 | LFM Open License v1.0 |
| Qwen 3.5 0.8B | Qwen3.5-0.8B-Q4_K_M.gguf | 532,517,120 | mmproj-F16.gguf | 204,987,232 | Apache-2.0 |
| Qwen 3.5 2B | Qwen3.5-2B-Q4_K_M.gguf | 1,280,835,840 | mmproj-F16.gguf | 668,227,264 | Apache-2.0 |
| Qwen 3.5 4B | Qwen3.5-4B-Q4_K_M.gguf | 2,740,937,888 | mmproj-F16.gguf | 672,423,616 | Apache-2.0 |
| Gemma 4 E2B IT | gemma-4-E2B_q4_0-it.gguf | 3,349,516,256 | gemma-4-E2B-it-mmproj.gguf | 986,833,664 | Apache-2.0 |
| Gemma 4 E4B IT | gemma-4-E4B_q4_0-it.gguf | 5,154,941,280 | gemma-4-E4B-it-mmproj.gguf | 991,552,256 | Apache-2.0 |

Qwen quantizations use [unsloth/Qwen3.5-0.8B-GGUF](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF), [unsloth/Qwen3.5-2B-GGUF](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF), and [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF). Gemma entries use Google's official QAT GGUF repositories.

## Voice paths

OffconAI exposes two independent voice architectures:

1. Native Omni for LFM3 Omni 2B. PCM audio enters the model, and synchronized text plus PCM speech leave it.
2. Pipeline voice for text and vision models. On-device Whisper STT produces text, the selected text model generates a response, and the selected TTS engine speaks it.

The UI labels the active route explicitly so a text model's pipeline voice is not presented as native audio-to-audio support.
