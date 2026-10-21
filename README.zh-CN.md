# Local LLM Android（中文）

<p align="center">
  <img src="docs/screenshots/chat_screen.png" alt="Local LLM Android" width="45%" />
  <img src="docs/screenshots/model_manager.png" alt="模型管理" width="45%" />
</p>

<p align="center">
  <a href="README.md">English</a> | <a href="README.ko.md">한국어</a> | <strong>中文</strong>
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

**Local LLM Android** 是一款隐私优先、完全离线的安卓端侧大语言模型（LLM）应用与服务器。它采用 **llama.cpp** 与 **Google LiteRT-LM** 结合的**混合双运行时架构**，无需联网或云端服务器，即可直接利用设备的 CPU、GPU 与 NPU 执行最先进的 AI 模型。界面基于自研**玻璃质感设计系统**，支持**英/韩/中即时切换**；聊天可通过原生 **MCP 客户端**调用工具；自研 MoE 研究引擎（**SDengine**）正以 `:engine` 模块的形式公开开发。

---

## 📱 截图

| 端侧对话与实时性能指标 | 模型库与下载管理 |
| :---: | :---: |
| <img src="docs/screenshots/chat_screen.png" width="100%" /> | <img src="docs/screenshots/model_manager.png" width="100%" /> |
| **Ollama / OpenAI API 服务器模式** | **免提交互语音对话** |
| <img src="docs/screenshots/api_server.png" width="100%" /> | <img src="docs/screenshots/voice_mode.png" width="100%" /> |

---

## 🏛️ 双运行时混合架构

Local LLM Android 不绑定单一引擎，而是在统一的推理抽象之下同时集成 **llama.cpp** 与 **Google LiteRT-LM**。

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
      [ llama.cpp 运行时 ]                          [ Google LiteRT-LM 运行时 ]
   • 通用 GGUF 格式支持                             • Google 移动端优化二进制
   • Qwen、DeepSeek-R1、Llama 3 等                  • Gemma-2、FunctionGemma 移动模型
   • CPU 多线程与 GPU 层卸载                        • 硬件 GPU（OpenCL/Vulkan）与 NPU 委托
   • mmproj 视觉塔支持（多模态）                    • 超低功耗移动端推理

        （+ SDengine — :engine 下的实验性自研 MoE 引擎，见下文）
```

### 为什么需要双运行时？

1. **通用模型兼容性（`llama.cpp` / GGUF）**
   - 可直接从 Hugging Face 下载并运行数以万计的社区量化模型（Q4_K_M、Q8_0 等）。
   - 完整支持推理模型（DeepSeek-R1）、自定义 Jinja 聊天模板与视觉塔（`mmproj`）。

2. **原生移动端硬件加速（`Google LiteRT-LM`）**
   - 深度对接 Google 端侧 AI 运行时，针对移动 SoC（骁龙、联发科、Tensor、Exynos）深度优化。
   - 利用专用 NPU 与 GPU 委托实现高吞吐、低温升的推理。

---

## 🌐 端侧 Ollama / OpenAI API 服务器（端口 11434）

把你的安卓手机或平板变成一台自主的端侧 AI 服务器，供桌面应用、IDE（VS Code、Cursor、Continue）及其他局域网设备访问。

- **完整的协议兼容**：
  - `GET /api/tags` - 列出已安装的端侧模型
  - `POST /api/generate` - 单轮补全（Ollama 规范）
  - `POST /api/chat` - 多轮对话补全（Ollama 规范）
  - `POST /v1/chat/completions` - 兼容 OpenAI API 的端点
- **灵活的网络与访问控制**：
  - **本地回环（`127.0.0.1`）隔离**：默认安全模式，阻止未授权的外部访问。
  - **外部网络（局域网）开关**：一键绑定 `0.0.0.0`（实时套接字重绑定），允许同一 Wi-Fi / 局域网内任意电脑或设备访问。
  - **Bearer 令牌认证**：`sk-local-...` API 密钥，生成一次并持久化在设备上（可在 API 页面重新生成）。无 TLS：在局域网中 bearer 以明文 HTTP 传输，请使用可信网络或 SSH 隧道。
  - **前台服务**：关闭应用界面后服务器继续运行（带有「停止」操作的可见通知）；结束进程即停止服务。
- **cURL 用法**：
  ```bash
  curl -X POST http://<你的设备IP>:11434/api/generate \
    -H "Authorization: Bearer <API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{"prompt": "Hello from my terminal!", "stream": false}'
  ```

---

## 🔒 安全与隐私

1. **硬件级 AES-256-GCM 加密（`ChatCrypto`）**
   - 加密密钥在硬件 **Android KeyStore**（安全元件 / TEE）中生成与存储。
   - 认证加密（AEAD）确保对话数据无法被截获或篡改。
   - 严格防止静默降级到不安全的硬编码密钥。

2. **离线文本推理与私有存储**
   - 文本推理零遥测、零统计上报、零外部 API 依赖。
   - 对话与提示词严格保留在物理设备上的加密 Room SQLite 数据库中。
   - 注意：语音识别通过**本地 Whisper STT 引擎**（`LocalSttEngine`）执行；语音合成使用端侧 **supertonic 韩语神经语音**（`LocalTtsEngine`，从 Hugging Face 下载一次），并回退到设备内置语音服务。

---

## ⚡ 核心亮点

- **实时硬件基准测试**：
  - 每条消息都显示提示词预填充速度（`promptSpeed` 词元/秒）、生成解码速度（`tps`）与活跃上下文词元数。
- **交互式语音模式**：
  - 免提交互闭环：**本地 Whisper STT**（端侧语音识别）→ 本地 LLM 生成 → 语音合成（TTS）应答。
  - 以动画响应式语音光球呈现。
- **流式推理状态机（`ReasoningStreamParser`）**：
  - 优雅地跨词元流式分块解析 `<think>` ... `</think>` 标签，适配 DeepSeek-R1 等推理模型。
- **模型上下文协议（MCP）客户端（`McpClient`）**：
  - 原生 MCP 协议客户端，具备按会话隔离防护，可在端侧聊天中使用工具。
- **自研玻璃质感设计系统（`ui/glass`）**：
  - 完全移除 Material3；自研玻璃 UI 工具包（`GlassTheme`、`GButtons`、`GControls`、`GOverlays`……）覆盖所有界面。
- **应用内多语言界面（英 / 韩 / 中）**：
  - 在设置中即时实时切换（跟随系统 / English / 한국어 / 中文），无需重启应用。
- **内存不足保护（Android 感知）**：
  - 映射模型前预估真实占用（权重 + KV 缓存 + 计算缓冲），宁可缩小上下文窗口也不撞上 OOM 杀进程。
  - 每次生成写入进行中标记：启动时发现标记即说明上个进程在推理中途被杀，会被记录并在下次运行时降低高内存占用设置（设置中的内存保护日志 + 事件）。
  - 生成期间轮询可用内存，在设备内存告急、LMK 杀掉应用前安全终止本次运行。
- **可靠的移动端下载器**：
  - 后台前台服务下载，支持暂停/续传与完整性校验。

---

## 🧪 SDengine — 实验性自研引擎（`:engine`）

一个仅面向 MoE 的自研推理核心，正作为独立的安卓库模块公开开发。

**范围（刻意限定）：** Android 上的门控专家混合模型（Kotlin/JVM 核心 + C++ JNI 桩；NEON/GPU 内核待实现）。纯稠密模型与非 MoE 混合模型不在范围内。

**当前包含：**
- GGUF 格式读取器 + 元数据检测、BPE 分词器、采样器、KV 缓存（`GgufReader`、`BpeTokenizer`、`Sampler`、`KvCache`）
- 分页专家流式加载：稠密权重在上限内常驻解码，专家分片留在存储上并通过 `ExpertPager` 流式读取
- 经 NDK 27 + CMake 的 C++ JNI 核心（`arm64-v8a`、`x86_64`）
- 基于真实 MoE 权重的数值验证（经 HTTP range 流式加载实测 Qwen3-30B-A3B）
- 门控专家 GGUF 的端到端生成，由 `SDEngineEndToEndTest` 覆盖（合成 MoE 模型：加载、流式出词、取消、常驻上限强制）

**状态：测试构建 / 实验性 — 能跑，但请勿用于生产。**
- 参考标量内核验证了数学正确性；NEON/GPU 内核是下一步，因此真实 MoE 模型在设备上仅有个位数词元/秒的解码速度。
- 应用会把自动检测到的 MoE GGUF（导入的模型）路由到 SDengine，并保持引擎提示（`SDEngine.ADVISORIES`）可见；SDengine 加载失败会在 llama.cpp 上重试一次，而不是让模型无处可用。每个模型的运行时都可在模型管理中手动覆盖。

---

## 🛠️ 构建与安装

### 环境要求
- Android Studio Ladybug（2024.2.1+）或更新版本
- JDK 17
- Android SDK 36（最低 SDK 24）
- Android NDK 27.2 + CMake 3.22+（`:engine` 模块必需）
- 目标设备需为 64 位 ARM 架构（`arm64-v8a`）或 `x86_64`
- 无需手动准备原生依赖：sherpa-onnx STT AAR（约 50 MB）不入库 —— Gradle 会在每次构建前通过 `fetchSherpaAar` 任务自动获取（固定版本 + SHA-256 校验）

### Gradle 命令
```bash
# 编译 Kotlin 源码
./gradlew compileDebugKotlin

# 运行单元测试
./gradlew testDebugUnitTest

# 打包 Release APK
./gradlew assembleRelease
```

### Release 签名

Release 构建绝不使用 debug 签名。请通过环境变量提供上传密钥库凭据
（`KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_PASSWORD`，可选 `KEY_ALIAS`）；
或在本地构建时，写入仓库根目录下被 gitignore 的 `keystore.properties`：

```properties
storeFile=my-upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

插桩测试需连接真机或模拟器运行：
`./gradlew connectedDebugAndroidTest`。

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。
