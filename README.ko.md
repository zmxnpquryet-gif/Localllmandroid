<div align="center">

# ⚡ Local LLM Android

### 최첨단 LLM을 주머니 속에서 100% 오프라인으로

**프라이버시 퍼스트 온디바이스 AI · Fully offline on-device AI · 100% 离线设备端 AI**

[📱 스크린샷](#-스크린샷) · [✨ 핵심 기능](#-핵심-기능) · [🤗 지원 모델](#-지원-모델--하드웨어) · [🏛️ 아키텍처](#️-아키텍처) · [🌐 API 서버](#-온디바이스-ollama--openai-api-서버) · [🚀 시작하기](#-시작하기)

</div>

<p align="center">
  <img src="docs/screenshots/chat_screen.png" alt="Local LLM Android" width="45%" />
  <img src="docs/screenshots/model_manager.png" alt="Model Manager" width="45%" />
</p>

<p align="center">
  <a href="README.md">English</a> | <strong>한국어</strong> | <a href="README.zh-CN.md">中文</a>
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

**Local LLM Android**는 프라이버시를 최우선으로 하는, 완전 오프라인에서 동작하는 안드로이드 온디바이스 LLM(대형 언어 모델) 애플리케이션이자 서버입니다. **llama.cpp**와 **Google LiteRT-LM**을 결합한 **하이브리드 이원 런타임 아키텍처**로 최신 AI 모델을 전부 기기 안에서만 구동합니다 — 기본은 CPU 연산이며, 기기와 런타임이 지원하면 GPU/NPU 가속을 사용하고 자동으로 CPU로 폴백합니다. 클라우드 의존성은 없습니다. UI는 자체 개발 **글래스 디자인 시스템** 위에서 동작하며 **영어/한국어/중국어 실시간 전환**을 지원하고, 채팅은 네이티브 **MCP 클라이언트**로 도구를 호출할 수 있습니다. 또한 자체 MoE 연구 엔진(**SDengine**)이 `:engine` 모듈로 오픈소스로 개발되고 있습니다.

---

## 📱 스크린샷

| 온디바이스 채팅 & 실시간 지표 | 모델 허브 & 다운로드 관리자 |
| :---: | :---: |
| <img src="docs/screenshots/chat_screen.png" width="100%" /> | <img src="docs/screenshots/model_manager.png" width="100%" /> |
| **Ollama / OpenAI 호환 API 서버** | **핸즈프리 음성 대화 모드** |
| <img src="docs/screenshots/api_server.png" width="100%" /> | <img src="docs/screenshots/voice_mode.png" width="100%" /> |

---

## ✨ 핵심 기능

### 🏎️ 이원화 런타임 추론 (Dual-Runtime Inference)

하나의 추상화 뒤에 두 개의 프로덕션 추론 엔진 — 모델마다 맞는 도구를 고르세요:

- **llama.cpp (GGUF)** — 수만 개의 커뮤니티 양자화 모델(Q4_K_M, Q8_0, K-quants 등), 추론(Reasoning) 모델, 채팅 템플릿, `mmproj` 비전 타워를 Hugging Face에서 바로 실행.
- **Google LiteRT-LM** — 지원 기기에서 GPU/NPU 델리게이트를 활용하는(자동 CPU 폴백) Google 모바일 최적화 런타임. Snapdragon / MediaTek / Tensor / Exynos SoC에 맞춤.
- **SDengine** — `:engine` 모듈에서 공개적으로 개발 중인 실험적 자체 MoE 엔진. [🧪 SDengine](#-sdengine--실험적-자체-엔진-engine) 참조.

### 🌐 온디바이스 Ollama / OpenAI 서버 (포트 11434)

폰을 **로컬 AI 서버**로: Ollama·OpenAI 호환 HTTP 엔드포인트, 라이브 리바인딩이 되는 LAN 토글, Bearer 토큰 인증, UI를 닫아도 서빙을 유지하는 포그라운드 서비스. **VS Code Continue, Cursor, cURL**에 그대로 연결 — 전체 가이드는 [🌐 API 서버](#-온디바이스-ollama--openai-api-서버).

### 🔌 네이티브 MCP (Model Context Protocol) 클라이언트

채팅이 **진짜 도구**를 호출합니다 — Streamable HTTP 우선 + 레거시 SSE 차선의 강화된 JSON-RPC 클라이언트, 요청마다 Bearer 재확인, 크기 상한이 있는 본문, 모든 호출 전 명시적 승인 대화상자. 모든 도구 결과는 신뢰할 수 없는 외부 데이터로 취급됩니다. 자세한 내용은 [⚡ 엔지니어링 하이라이트](#-엔지니어링-하이라이트).

### 🎙️ 제로 클라우드 음성 모드 (Zero-Cloud Voice Mode)

완전 온디바이스 핸즈프리 대화 루프: **로컬 Whisper STT** → 온디바이스 LLM 생성 → **supertonic 한국어 신경망 TTS**(1회 다운로드, 기기 내장 TTS 폴백), 리액티브 **Voice Orb** 애니메이션으로 시각화. 목소리는 폰을 떠나지 않습니다.

### 🛡️ Keystore AES-256-GCM & 텔레메트리 제로

대화와 자격증명은 AEAD 행 바인딩이 적용된 **Android Keystore AES-256-GCM** 암호화 Room 데이터베이스에 저장됩니다. 텍스트 추론에 텔레메트리·분석·외부 API 의존성이 전혀 없습니다. 자세한 내용은 [🔒 보안 & 프라이버시](#-보안--프라이버시).

### 💎 베스포크 글래스 테마 (Bespoke Glass Theme)

**Material3를 완전히 제거.** 자체 글래스 UI 툴킷(`GlassTheme`, `GButtons`, `GControls`, `GOverlays` 등)이 모든 화면을 커버하며, 앱 재시작 없이 EN / KO / ZH를 즉시 전환합니다.

### 🛡️ 안드로이드 OOM 가드

모델 매핑 전 실제 필요 메모리(가중치 + KV 캐시 + 연산 버퍼)를 추정해 OOM 킬로 향하는 대신 컨텍스트를 자동으로 수축합니다. 추론 중 강제 종료를 감지하고, 생성 중 가용 RAM을 폴링해 LMK가 앱을 죽이기 전에 안전하게 중단합니다. 자세한 내용은 [⚡ 엔지니어링 하이라이트](#-엔지니어링-하이라이트).

### 추가 엔지니어링 하이라이트

- **실시간 하드웨어 벤치마킹** — 모든 메시지에서 프롬프트 프리필 속도(`promptSpeed`, tokens/sec), 생성 속도(`tps`), 활성 컨텍스트 토큰 수 표시.
- **스트리밍 추론 상태머신 (`ReasoningStreamParser`)** — DeepSeek-R1 같은 추론 모델의 `<​think>` … `<​/think>` 태그가 토큰 스트리밍 단위로 쪼개져 와도 안정적으로 파싱.
- **탄력적 모바일 다운로더** — 포그라운드 서비스 다운로드 + 이어받기, Hugging Face가 게시한 해시 기반 SHA-256 검증, 잘린 전송은 절대 완료로 등록되지 않음.

---

## 🤗 지원 모델 & 하드웨어

| 모델 | 파라미터 | 권장 양자화 | 파일 크기 (대략) | 최소 기기 RAM | 런타임 | 비고 |
| :--- | :---: | :--- | :---: | :---: | :--- | :--- |
| **Qwen 2.5** | 1.5B | Q4_K_M | ~1.0 GB | 3 GB | llama.cpp | 우수한 품질/크기 비율, 다국어 강점 |
| **Qwen 2.5** | 3B | Q4_K_M | ~2.0 GB | 4 GB | llama.cpp | 대부분의 8 GB 폰에 맞는 스위트 스팟 |
| **Qwen 2.5** | 7B | Q4_K_M | ~4.7 GB | 6 GB | llama.cpp | 인앱 최고 품질, 플래그십 또는 12 GB 기기 필요 |
| **LFM3** | 1.7B | Q4_K_M | ~1.0 GB | 3 GB | llama.cpp | Liquid AI, 262K 컨텍스트 하이브리드 추론 |
| **LFM3-VL** | 2B | Q4_K_M + mmproj Q8_0 | ~1.3 GB | 3 GB | llama.cpp | Liquid AI 비전 모델, `mmproj` 이미지 입력 |
| **DeepSeek-R1-Distill** | 1.5B | Q4_K_M | ~1.1 GB | 3 GB | llama.cpp | 추론 모델 — `<​think>` 파싱 내장 |
| **DeepSeek-R1-Distill** | 7B | Q4_K_M | ~4.7 GB | 6 GB | llama.cpp | 기기 위에서의 사고 연쇄(Chain-of-thought) |
| **Llama 3.2** | 1B | Q4_K_M | ~0.8 GB | 2.5 GB | llama.cpp | 빠른 초안 작성, 도구 호출 |
| **Llama 3.2** | 3B | Q4_K_M | ~2.0 GB | 4 GB | llama.cpp | 균형 잡힌 일상용 모델 |
| **Gemma 2** | 2B | Q4_K_M / `.task` | ~1.8 GB | 3.5 GB | llama.cpp **또는** LiteRT-LM | LiteRT-LM 경로에서 GPU/NPU 델리게이트 활용 |
| **FunctionGemma** | — | 모바일 바이너리 | — | 3 GB | LiteRT-LM | 모바일 최적화 함수 호출 |

> **📋 표에 대한 참고**
>
> - 파일 크기는 일반적인 GGUF Q4_K_M 페이로드이며, **최소 기기 RAM**은 KV 캐시와 연산 버퍼를 포함한 실질적 풋프린트로, 앱의 OOM 가드가 로드 전에 추정하는 값입니다. 실제 수치는 컨텍스트 길이에 따라 달라집니다.
> - 생성 속도는 기기에 따라 다르며 모든 메시지에서 실시간 표시됩니다(`promptSpeed` / `tps`): 최근 플래그십급 SoC의 4비트 기준으로 1–1.5B 모델은 대략 20+ tok/s, 3B는 ~10–15 tok/s, 7B는 ~5–10 tok/s. 본인 기기의 벤치마크는 인앱 지표를 사용하세요 — 앱은 측정하지 않은 속도를 주장하지 않습니다.
> - Hugging Face의 모든 커뮤니티 **GGUF**가 llama.cpp를 통해 동작합니다. 위 목록은 큐레이션된 출발점이지 허용 목록이 아닙니다.

---

## 🏛️ 아키텍처

Local LLM Android는 단일 엔진에 종속되지 않고, **llama.cpp**와 **Google LiteRT-LM** 두 런타임을 모두 통합 추론 추상화 뒤에 둡니다. 업스트림 llama.cpp 엔진은 v0.8.0(b11960)에 고정된 Git 서브모듈로 포함되어, GGUF 런타임·LFM3 아키텍처·mtmd 비전 지원이 앱과 함께 소스에서 빌드됩니다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                            UI (Compose)                              │
│      채팅 · 모델 관리자 · API 서버 · 음성 모드 · 설정                  │
│                  [ 자체 글래스 디자인 시스템 ]                         │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                           MainViewModel                              │
│     채팅 세션 · MCP 도구 루프 · 음성 루프 · OOM 가드                   │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                             LlmEngine                                │
│       단일 추상화 · 스트리밍 · 토크나이징 · 취소                       │
└───────┬───────────────────────────┬──────────────────────────┬───────┘
        ▼                           ▼                          ▼
┌───────────────────┐   ┌──────────────────────┐   ┌─────────────────────┐
│  llama.cpp        │   │  Google LiteRT-LM    │   │  SDengine (실험)    │
│  런타임           │   │  런타임              │   │  :engine 모듈       │
├───────────────────┤   ├──────────────────────┤   ├─────────────────────┤
│ • GGUF 양자화     │   │ • Google 모바일      │   │ • MoE 전용 코어     │
│   (Q4_K_M, Q8_0,  │   │   바이너리           │   │ • 페이징 전문가     │
│   K-quants)       │   │ • Gemma-2,           │   │   스트리밍          │
│ • Qwen, DeepSeek, │   │   FunctionGemma      │   │ • Kotlin/JVM +      │
│   Llama 3, LFM3   │   │ • 지원 기기에서      │   │   C++ JNI 레퍼런스  │
│ • CPU 멀티스레딩  │   │   GPU/NPU 델리게이트 │   │ • GGUF 리더, BPE,   │
│   & GPU 레이어    │   │   (자동 CPU 폴백)    │   │   K-quants, RoPE    │
│   오프로딩        │   │ • 저전력 모바일      │   │                     │
│ • mmproj 비전     │   │   추론               │   │                     │
│   (Qwen2/LFM3-VL) │   │                      │   │                     │
└─────────┬─────────┘   └──────────┬───────────┘   └──────────┬──────────┘
          ▼                        ▼                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          하드웨어 (SoC)                               │
│          CPU (멀티스레드) · GPU 델리게이트 · NPU 델리게이트            │
└──────────────────────────────────────────────────────────────────────┘
```

### 왜 두 엔진을 함께 사용하는가?

1. **폭넓은 모델 생태계 호환성 (llama.cpp / GGUF)**
   - 허깅페이스에 존재하는 수만 개의 커뮤니티 양자화 모델(Q4_K_M, Q8_0 등)을 즉시 다운로드하여 실행할 수 있습니다.
   - DeepSeek-R1 등의 추론 모델, GGUF 채팅 템플릿, 멀티모달 mmproj 비전 타워가 같은 런타임에서 동작합니다.

2. **안드로이드 네이티브 하드웨어 가속 (Google LiteRT-LM)**
   - Google의 온디바이스 AI 런타임으로 모바일 SoC(Snapdragon, MediaTek, Tensor, Exynos)의 GPU/NPU 델리게이트와 가장 긴밀하게 최적화되어 있습니다.
   - 전력 소모와 발열을 최소화하면서 고효율 추론을 제공합니다.

---

## 🌐 온디바이스 Ollama / OpenAI API 서버

안드로이드 폰이나 태블릿을 데스크톱 애플리케이션, IDE(VS Code, Cursor, Continue), 그리고 로컬 네트워크의 다른 기기에서 접근 가능한 자율적인 온디바이스 AI 서버로 바꿉니다.

### 엔드포인트

| 메서드 | 경로 | 설명 |
| :--- | :--- | :--- |
| `GET` | `/api/tags` | 설치된 온디바이스 모델 목록 |
| `POST` | `/api/generate` | 단일 턴 텍스트 생성 (Ollama 규격) |
| `POST` | `/api/chat` | 대화형 메시지 생성 (Ollama 규격) |
| `POST` | `/api/show` | 활성 모델 상세 정보 |
| `GET` | `/api/version` | 서버 버전 (Ollama 호환 `0.5.1` 보고) |
| `POST` | `/v1/chat/completions` | OpenAI API 호환 엔드포인트 |
| `GET` | `/v1/models` | OpenAI 모델 목록 |

> 프로토콜 참고: 연결당 요청 1개(`Connection: close`), 요청 본문은 `Content-Length` 필수(chunked 요청 본문은 `411`로 응답).

### 네트워크 & 접근 제어

- **로컬 루프백(`127.0.0.1`) 격리**: 무단 외부 접근을 막는 기본 보안 모드.
- **외부 네트워크(LAN) 토글**: 토글 시 `0.0.0.0`으로 즉시 바인딩(라이브 소켓 리바인딩), 같은 Wi-Fi/LAN의 어떤 PC나 기기에서도 접근 가능.
- **Bearer 토큰 인증**: `sk-local-...` API 키, 최초 1회 생성 후 기기에 저장(API 화면에서 재발급). TLS 없음: LAN에서는 평문 HTTP이므로 신뢰할 수 있는 네트워크나 SSH 터널을 사용하세요.
- **포그라운드 서비스**: 앱 UI를 닫아도 서버 유지(중지 액션이 있는 알림 표시), 프로세스 종료 시 함께 종료.

### 빠른 시작: cURL

```bash
curl -X POST http://<기기_IP>:11434/api/generate \
  -H "Authorization: Bearer <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "안녕하세요", "stream": false}'
```

### 빠른 시작: VS Code Continue

[Continue](https://marketplace.visualstudio.com/items?itemName=Continue.continue) 확장을 설치하고, 앱의 API 화면에서 LAN 토글을 켠 뒤, 폰을 프로바이더로 추가합니다:

```json
{
  "models": [
    {
      "title": "폰 LLM (Local LLM Android)",
      "provider": "ollama",
      "model": "qwen2.5-3b",
      "apiBase": "http://<기기_IP>:11434",
      "apiKey": "sk-local-...",
      "apiType": "openai"
    }
  ]
}
```

모델 id는 폰에 설치된 GGUF의 파일명(`.gguf` 확장자 제외)입니다 — 정확한 목록은 `GET /v1/models` 또는 API 화면에서 확인하세요. 모든 **OpenAI 호환** 클라이언트가 같은 방식으로 동작합니다: base URL을 `http://<기기_IP>:11434/v1`로 두고, `sk-local-...` 키를 넣고, 모델을 고르면 됩니다.

---

## 🔒 보안 & 프라이버시

1. **Android Keystore 기반 AES-256-GCM 암호화 (`ChatCrypto`)**
   - 키는 `AndroidKeyStore` 내부에서 내보내기 불가로 생성하며 하드코딩 키로의 폴백은 없습니다(실패 시 종료). TEE/StrongBox 지원 여부는 기기마다 다르므로 주장하지 않고, `KeyInfo`로 읽어 `keyProtection()`으로 노출합니다(계측 테스트가 기기별 수준을 로그에 남김).
   - 인증 암호화(AEAD)에 행 바인딩을 적용합니다. 암호문에 대화/메시지 ID와 컬럼을 연관 데이터로 묶어, 다른 칸으로 복사된 암호문은 인증에 실패합니다. 현행 포맷은 `v2:`이며 구 포맷 행은 실행 시 제자리에서 업그레이드됩니다.
   - 토큰·키(HF 토큰, API 서버 키, MCP Bearer 토큰)는 Keystore 암호문으로만 저장합니다(`SecretPrefs`). 이전 버전의 평문 복사본은 다음 접근 시 단일 원자적 커밋으로 마이그레이션 후 삭제하고(모델 파일 토큰은 다음 저장 시 재암호화), 깨진 암호문은 살아남은 평문이 있으면 1회 복구 후 재암호화합니다. 그 외 복호 불가 값은 유효한 값으로 쓰지 않고 없는 것으로 취급합니다.

2. **오프라인 텍스트 추론 & 프라이빗 저장**
   - 텍스트 추론 과정의 대화 내역 및 프롬프트가 외부 서버나 클라우드로 일절 전송되지 않습니다.
   - 모든 대화 기록은 로컬 암호화 SQLite Room 데이터베이스에만 저장됩니다.
   - 참고: 음성 인식(STT)은 **로컬 Whisper STT 엔진**(`LocalSttEngine`)으로 온디바이스 처리되며, 음성 합성(TTS)은 온디바이스 **supertonic 한국어 신경망 음성**(`LocalTtsEngine`, HF에서 1회 다운로드)을 사용하고 기기 내장 음성 서비스로 폴백합니다.

---

## ⚡ 엔지니어링 하이라이트

<details>
<summary><b>실시간 하드웨어 벤치마킹</b></summary>

- **llama.cpp (GGUF)**: `llamaCtx.tokenize()` 네이티브 토크나이징 호출을 통해 실제 토큰 수 기반의 `promptSpeed`(프롬프트 토큰/초)와 `tps`(생성 토큰/초)를 측정.
- **Google LiteRT-LM**: C++ 코어의 `conv.getBenchmarkInfo()` 네이티브 지표를 직접 연동해 오차 없는 실시간 성능 표기.

</details>

<details>
<summary><b>대화형 음성 모드</b></summary>

- 핸즈프리 대화 루프: **로컬 Whisper STT**(온디바이스 음성 인식) → 로컬 LLM 생성 → 음성 합성(TTS) 응답.
- 리액티브 Voice Orb 애니메이션으로 시각화.

</details>

<details>
<summary><b>스트리밍 추론 상태머신 (<code>ReasoningStreamParser</code>)</b></summary>

- DeepSeek-R1 등 추론 모델의 `<​think>` ... `<​/think>` 태그가 토큰 스트리밍 단위로 쪼개져 수신되는 경우에도 버퍼 상태머신을 통해 생각 과정과 최종 답변을 안정적으로 분리 표기.

</details>

<details>
<summary><b>MCP (Model Context Protocol) 클라이언트 (<code>McpClient</code>)</b></summary>

- Streamable HTTP 우선, 레거시 SSE 차선의 JSON-RPC 클라이언트입니다(양쪽 경로 모두 세션 ID 유지 + `notifications/initialized` 전송; 레거시 SSE 스트림은 세션 내내 열린 상태로 유지되며, SSE로만 응답하는 서버를 포함해 그 채널로 전달된 응답을 요청 ID로 해당 POST에 매칭합니다). 응답은 요청 ID로 매칭됩니다(오래되었거나 요청되지 않은 프레임은 무시). 응답 본문과 스트림 라인에 크기 상한이 있고, 도구 이름은 프롬프트에 들어가기 전에 정화(sanitize)됩니다. Bearer 토큰은 선택 사항이며 요청마다 재확인합니다. https 어디에나, 평문 http는 loopback/사설 LAN에만 전송하고(mDNS `.local` 제외 — 브로드캐스트로 풀리므로 https 필요), 한 서버용으로 저장된 토큰은 주소가 바뀌면 재입력 없이 재전송하지 않으며, 리다이렉트로 https가 http로 떨어지지 않습니다. 레거시 SSE의 타 오리진 endpoint는 거부하며 `Mcp-Session-Id`를 연결별로 추적합니다.
- 도구는 프롬프트에 제시되고, 턴마다 하나의 `<​tool_call>{"name": ..., "arguments": {...}}<​/tool_call>` 블록으로 호출합니다(연결된 서버가 광고한 이름만, 턴당 첫 호출만, 코드펜스 안 예시·닫히지 않은 블록은 호출 아님). 모든 호출은 승인 대화상자를 거치며(항상 허용 없음, 60초 무응답 자동 거부), 거절하면 도구 사용을 끝내고 연속 2회 문법 오류에도 끝냅니다. 메시지당 최대 3라운드, 요청은 비동기·취소 가능이라 중지 버튼이 서버에 고아 요청을 남기지 않습니다. 결과는 신뢰할 수 없는 `<tool_result>` 데이터로 감싸(모든 태그 변형 무력화) 호출 기록과 함께 도구 카드로 표시되고, 외부 데이터로 명시되어 다음 턴에 전달됩니다. 작은 로컬 모델이 도구를 잘 쓰는지는 모델에 따라 다릅니다.

</details>

<details>
<summary><b>자체 글래스 디자인 시스템 (<code>ui/glass</code>) &amp; 인앱 다국어 UI</b></summary>

- Material3를 완전히 제거하고 자체 글래스 UI 툴킷(`GlassTheme`, `GButtons`, `GControls`, `GOverlays` 등)으로 모든 화면을 커버합니다.
- 설정에서 시스템 기본값 / English / 한국어 / 中文을 앱 재시작 없이 즉시 실시간 전환합니다.

</details>

<details>
<summary><b>안드로이드 인지 OOM 보호</b></summary>

- 모델 매핑 전 실제 필요 메모리(가중치 + KV 캐시 + 연산 버퍼)를 추정하여, OOM 킬로 밀려 들어가는 대신 컨텍스트 길이를 자동으로 낮춥니다.
- 추론마다 in-flight 마커를 기록합니다. 다음 실행에서 마커가 남아 있으면 이전 프로세스가 추론 중 강제 종료된 것이므로 사고로 기록하고 다음 실행의 메모리 사용이 큰 설정을 자동으로 낮춥니다 (설정 화면에서 로그·사고 이력 확인 가능).
- 생성 중 가용 RAM을 주기적으로 확인해 위험 수준이면 앱이 죽기 전에 생성을 안전하게 중단합니다.

</details>

<details>
<summary><b>타입 안전 내비게이션 &amp; 탄력적 모바일 다운로더</b></summary>

- **타입 안전한 전역 네비게이션 (`AppScreen`)** & 시스템 제스처 핸들링: 모든 화면 전환을 `AppScreen` enum으로 관리하며, 서브 화면에서 시스템 뒤로가기 제스처 시 메인 채팅으로 자연스럽게 복귀.
- 포그라운드 서비스 백그라운드 다운로드 + 중단된 다운로드는 파트 파일에서 이어받기(수동 일시정지 버튼은 없음). 허깅페이스가 게시한 해시(`X-Linked-ETag`, 리다이렉트 체인 추적)가 있으면 SHA-256 검증 — 불일치 시 파일 삭제 후 실패, 이미 받은 파일도 재검사(불일치면 재다운로드). 잘려서 끝난 전송은 완료로 등록되지 않습니다(파일 이동 전 바이트 수를 검증하고, 이어받기 파트는 사이드카가 원격 리비전과 일치함을 증명하지 못하면 폐기). 해시 미게시 파일은 가중치 포맷 검사만으로 완료하고 다운로드 카드에 미검증으로 표시합니다(작은 템플릿 파일은 그대로 수용).

</details>

---

## 🧪 SDengine — 실험적 자체 엔진 (`:engine`)

<details>
<summary><b>범위, 내부 구조, 현재 상태</b></summary>

독립 안드로이드 라이브러리 모듈로 자체 MoE 전용 추론 코어를 오픈소스로 개발하고 있습니다.

**범위(의도된 설계):** 안드로이드에서 동작하는 gated-expert Mixture-of-Experts 전용 (Kotlin/JVM 코어 + 포터블 C++ JNI 레퍼런스, NEON/GPU 커널은 예정). Dense 전용 모델 및 비-MoE 하이브리드는 범위 밖입니다.

**현재 포함된 것:**
- GGUF 리더 + 메타데이터 감지, BPE 토크나이저, 샘플러, KV 캐시 (`GgufReader`, `BpeTokenizer`, `Sampler`, `KvCache`)
- 양자화 가중치 디퀀터: Q4_0/Q4_1/Q5_0/Q5_1/Q8_0/Q8_1과 K-quants Q4_K/Q5_K/Q6_K — ggml 레퍼런스 레이아웃에 스펙 벡터로 고정 (`Quant`, `QuantTest`)
- 페이징 전문가 스트리밍: dense 가중치는 상한 내 상주 디코딩, expert 타일은 스토리지에 두고 `ExpertPager`로 스트리밍 — RAM보다 큰 MoE 모델도 디코딩 가능
- 아키텍처 매칭 RoPE (llama.cpp의 아키텍처별 표에 따라 NeoX half-split / NORM interleaved 선택, GGUF 아키텍처에서 결정)
- NDK 27 + CMake 기반 포터블 C++ JNI 레퍼런스 (`arm64-v8a`, `x86_64`): `dot()` 동등성 확인용이며 NEON은 아직 없음 — 실제 연산은 JVM 스칼라 커널
- 실제 MoE 가중치 기반 수치 검증 (HTTP range 스트리밍, 수동/야간 `RealMoETest` + `LOCALENGINE_MOE_URL`; 일반 CI에서는 실행 안 함)
- 야간 골든 하네스: 실제 MoE GGUF에서 그리디 생성이 llama.cpp와 토큰 단위로 일치해야 함 (`GoldenLogitTest` + `nightly-real-model.yml`; 야간/예약 전용, 일반 CI 아님)
- gated-expert GGUF 엔드투엔드 생성 — `SDEngineEndToEndTest`가 합성 MoE 모델로 로드·토큰 스트리밍·중단·상주 상한 거부까지 검증

**상태: TEST BUILD / 실험체 — 실행은 되지만 프로덕션 사용 금지.**
- 레퍼런스 스칼라 커널로 수학을 검증 중이며, NEON/GPU 커널이 다음 단계입니다. 그래서 실기기에서 실제 MoE 모델은 초당 한 자릿수 토큰 속도로 동작합니다.
- 가져온 모델의 기본값은 llama.cpp이며 SDengine은 모델별 런타임 오버라이드(모델 관리자)로만 선택하는 opt-in입니다. SDengine 로드가 실패하면 모델 없이 남는 대신 llama.cpp로 한 번 재시도하고, 엔진 어드바이저리(`SDEngine.ADVISORIES`)를 계속 표시합니다.

</details>

---

## 🚀 시작하기

### 📲 릴리즈 APK 설치

[**최신 릴리즈**](https://github.com/zmxnpquryet-gif/Localllmandroid/releases/latest)에서 서명된 APK를 받아, "출처를 알 수 없는 앱" 설치를 허용한 후 설치하세요. 그 다음:

1. **모델 관리자**를 열어 모델을 다운로드합니다 (시작점은 [🤗 지원 모델](#-지원-모델--하드웨어) 참조).
2. 채팅을 시작하거나 — **API 서버** 화면에서 LAN 토글을 켜고 데스크톱을 `http://<폰_IP>:11434`로 향하게 하세요.
3. *(선택)* **음성 모드**를 켜서 완전 핸즈프리, 제로 클라우드 루프를 경험하세요.

### 🛠️ 소스에서 빌드

<details open>
<summary><b>요구 사항</b></summary>

- Android Studio Ladybug (2024.2.1+) 이상
- JDK 17
- Android SDK 36 (Min SDK 24)
- Android NDK 27.2 + CMake 3.22 이상 (`:engine` 모듈과 업스트림 llama.cpp 빌드에 필요)
- Git 서브모듈: `--recurse-submodules`로 클론하거나 `git submodule update --init --recursive`를 실행하세요. `app/src/main/cpp/llama.cpp`는 LFM3를 지원하는 업스트림 v0.8.0(b11960)에 고정되어 있습니다.
- 64비트 ARM 아키텍처(`arm64-v8a`) 또는 `x86_64` 대상 기기
- 네이티브 의존성 수동 설치 불필요: sherpa-onnx STT AAR(약 50MB)은 저장소에 커밋하지 않고, 빌드 전 Gradle `fetchSherpaAar` 태스크가 버전·SHA-256 고정값으로 자동 다운로드합니다.

</details>

<details open>
<summary><b>빌드 명령어</b></summary>

```bash
# 디버그 컴파일
./gradlew compileDebugKotlin

# 단위 테스트 실행
./gradlew testDebugUnitTest

# 릴리즈 APK 빌드
./gradlew assembleRelease
```

</details>

<details>
<summary><b>릴리즈 서명</b></summary>

릴리즈 빌드는 debug 키로 서명되지 않습니다. 업로드 키스토어 자격증명을 환경 변수
(`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, 선택 `KEY_ALIAS`)로 넘기거나, 로컬 빌드의
경우 저장소 루트의 gitignore 처리된 `keystore.properties`에 넣으세요:

```properties
storeFile=my-upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

계측 테스트는 연결된 기기/에뮬레이터에서 실행합니다: `./gradlew connectedDebugAndroidTest`.

</details>

---

## 📄 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE)에 따라 자유롭게 사용 및 배포할 수 있습니다.

---

<div align="center">

**⭐ 당신의 AI가 남의 서버가 아니라 당신의 기기에서 동작해야 한다고 믿으신다면 스타를 눌러주세요.**

</div>
