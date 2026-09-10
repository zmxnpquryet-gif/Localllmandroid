# Local LLM Android

완전 오프라인 환경에서 동작하는 고성능 안드로이드 온디바이스 LLM(대형 언어 모델) 애플리케이션입니다. 외부 클라우드나 API 통신 없이, 기기의 CPU, GPU, NPU 하드웨어를 직접 활용하여 안전하게 인공지능 모델을 구동합니다.

---

## 🏛️ 하이브리드 이원화 런타임 아키텍처 (Hybrid Runtime Architecture)

Local LLM Android는 단일 엔진에 종속되지 않고, **llama.cpp**와 **Google LiteRT-LM** 두 가지 런타임을 모두 지원하는 하이브리드 엔진 아키텍처를 채택하고 있습니다.

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
      [ llama.cpp 런타임 ]                          [ Google LiteRT-LM 런타임 ]
   • GGUF 범용 모델 포맷 지원                     • 구글 모바일 최적화 바이너리
   • Qwen, DeepSeek-R1, Llama 3 등               • Gemma-2, 모바일 NPU 가속 타깃
   • CPU 멀티스레딩 & GPU 레이어 오프로딩          • GPU(OpenCL/Vulkan) & NPU 가속
   • mmproj 비전타워 연동 (멀티모달)              • 저전력 / 고효율 모바일 추론
```

### 왜 두 엔진을 함께 사용하는가? (Dual-Runtime Rationale)

1. **최대 모델 생태계 호환성 (llama.cpp GGUF)**
   - 허깅페이스(Hugging Face)에 존재하는 수만 개의 오픈소스 커뮤니티 GGUF 양자화 모델(Q4_K_M, Q8_0 등)을 즉시 다운로드하여 실행할 수 있습니다.
   - DeepSeek-R1 등의 최신 추론(Reasoning) 모델, Jinja 템플릿, 멀티모달 mmproj 비전 타워를 폭넓게 지원합니다.

2. **안드로이드 네이티브 하드웨어 가속 (Google LiteRT-LM)**
   - Google의 온디바이스 AI 런타임으로 모바일 SoC(Snapdragon, MediaTek, Tensor)의 GPU 및 NPU 델리게이트와 가장 긴밀하게 최적화되어 있습니다.
   - 전력 소모와 발열을 최소화하면서 고효율 추론을 제공합니다.

---

## 🔒 보안 아키텍처 (Security Design)

1. **Android Keystore 기반 AES-256-GCM 암호화 (`ChatCrypto`)**
   - 하드코딩된 마스터 키를 배제하고 기기별 하드웨어 보안 모듈(Secure Element/TEE)과 연동된 `AndroidKeyStore`에서 256비트 AES 키를 생성·보관합니다.
   - 인증 암호화(AEAD) 방식을 적용하여 데이터 변조를 원천 차단합니다.
   - 실기기 런타임에서 하드웨어 Keystore 실패 시 하드코딩 키로의 조용한 폴백(Silent Downgrade)을 전면 차단하고 `SecurityException`을 발생시킵니다 (구버전 레거시 키는 과거 대화 복호화 읽기 전용으로만 안전하게 제한).

2. **보안 강화된 API 서버 (`OllamaApiServer`)**
   - 포트 `11434`를 통해 Ollama 및 OpenAI 호환 API를 제공합니다.
   - **바인드 주소**: 기본값으로 로컬 루프백(`127.0.0.1`)에만 바인딩되어 동일 Wi-Fi 망의 무단 접근을 방지합니다.
   - **인증 토큰**: 서버 구동 시 무작위 고강도 API 키(`sk-local-...`)가 발급되며, 모든 요청에 `Authorization: Bearer <token>` 헤더를 필수로 요구합니다.
   - **CORS 제한**: 브라우저 기반 무단 호출을 방지하기 위해 와일드카드(`*`) 헤더를 제거하고 로컬호스트 출처로 제한합니다.

---

## ⚡ 주요 성능 및 신뢰성 기능

- **이원화 런타임 실측 지표 산출**:
  - **llama.cpp(GGUF)**: `llamaCtx.tokenize()` 네이티브 토큰화 호출을 통해 실제 토큰 수 기반의 `promptSpeed`(초당 처리 토큰 수) 및 `tps`(생성 속도)를 측정합니다.
  - **Google LiteRT-LM**: C++ 코어의 `conv.getBenchmarkInfo()` 네이티브 지표(`lastPrefillTokensPerSecond`, `lastDecodeTokensPerSecond`, `lastPrefillTokenCount`, `lastDecodeTokenCount`)를 직접 연동하여 오차 없는 실시간 성능을 표기합니다.
- **사전 OOM 진단 및 보호 가드 (`checkMemoryDiagnostics`)**: 모델 로딩 전 `ActivityManager.MemoryInfo`를 통해 가용 RAM과 모델 파일 크기를 대조하며, 기기 메모리가 극도로 고갈된 환경에서 네이티브 프로세스 강제 종료(SIGSEGV)를 예방하기 위해 조기 차단 및 명확한 안내를 제공합니다.
- **타입 안전한 전역 네비게이션 (`AppScreen`)**: 모든 UI 화면 컴포넌트(`ChatScreen`, `ModelManagerScreen`, `SettingsScreen` 등)의 화면 전환 호출부까지 `AppScreen` enum으로 완전 마이그레이션하여 라우팅 오타 및 런타임 결함을 원천 차단했습니다.
- **스트리밍 추론 상태머신 (`ReasoningStreamParser`)**: DeepSeek-R1 등 추론 모델의 `<think>`, `</think>` 태그가 토큰 스트리밍 단위로 쪼개져 수신되는 경우에도 버퍼 상태머신을 통해 안정적으로 분리 처리합니다.
- **모바일 친화적 다운로더**: 과도한 동시 소켓 연결을 제한하여 배터리와 네트워크를 보호하며, 이어받기와 무결성 검증을 지원합니다.
- **R8 / Minify 최적화**: 릴리즈 빌드에 `isMinifyEnabled = true`가 활성화되어 있으며, JNI 및 리플렉션 의존성을 제거하여 크래시 없이 컴팩트한 바이너리를 생성합니다.
- **일관된 프로덕션 패키지**: `applicationId`, 빌드 `namespace`, 소스 코드 패키지 구조 모두 `com.localllm.android`로 일관성 있게 정비되었습니다.

---

## 🛠️ 빌드 및 테스트

### 요구 사항
- Android Studio Ladybug 이상 / JDK 17
- Android SDK 36, Min SDK 24
- NDK 지원 기기 (ARM64-v8a, x86_64)

### 명령어
```bash
# 디버그 컴파일
./gradlew compileDebugKotlin

# 로컬 유닛 테스트 실행
./gradlew testDebugUnitTest

# 릴리즈 번들 / APK 빌드
./gradlew assembleRelease
```
