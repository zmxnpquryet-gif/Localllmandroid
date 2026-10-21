# -*- coding: utf-8 -*-
import sys, os, re, io
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from i18n_lib import edit, add_res, BASE, _read, _write

E = []
def rep(file, old, new, key=None, en=None, ko=None, zh=None, n=1):
    E.append((file, old, new, key, en, ko, zh, n))

# ========== 0) LlmModel 目录：程序化提取韩文原文 ==========
lp = BASE + '/model/LlmModel.kt'
ls = _read(lp)
descs = re.findall(r'description = "([^"]*)",', ls)
ids = re.findall(r'id = "([^"]+)",', ls)
assert len(descs) == 11 and len(ids) == 11, (len(descs), len(ids))
think_token = re.search(r'사고 과정\((.*?)\)을', descs[3]).group(1)

EN = [
 'Ultra-light 360M model. Downloads very fast and runs snappily on any device.',
 '0.5B ultra-light model with Korean and multilingual support. Lightweight with fast responses.',
 'Meta Llama 3.2 1B lightweight high-performance on-device assistant model.',
 'Deep reasoning (CoT) model that generates its own thinking process (%s) on-device.' % think_token,
 '1.5B model with high-quality dialogue, coding, and summarization capabilities.',
 'Mobile-optimized 1.7B model with strong reasoning and instruction-following abilities.',
 'High-performance Google Gemma 2 2B model with full support for the official Gemma template.',
 'Official Qwen2-VL multimodal vision model with image input and analysis support.',
 'Qwen 2.5 Coder 1.5B for the Google LiteRT LM engine, with full official Jinja prompt template (chat_template.jinja) support.',
 'Google official LiteRT-LM Gemma 3 1B IT on-device model with ultra-light 584MB INT4 quantization.',
 'Google official LiteRT Community ultra-light 284MB model for mobile actions and function calling.',
]
ZH = [
 '超轻量 360M 模型，下载极快，各类设备均可流畅运行。',
 '支持韩语与多语言的 0.5B 超轻量模型，轻巧、响应迅速。',
 'Meta Llama 3.2 1B 轻量高性能端侧助手模型。',
 '可在本机生成思考过程（%s）的深度推理（CoT）模型。' % think_token,
 '具备高质量对话、编程与摘要能力的 1.5B 模型。',
 '推理与指令遵循能力出色的 1.5B 移动端优化模型。' .replace('1.5B', '1.7B'),
 '高性能 Google Gemma 2 2B 模型，完整支持 Gemma 官方模板规范。',
 '支持图像输入与分析的官方 Qwen2-VL 多模态视觉模型。',
 '面向 Google LiteRT LM 引擎的 Qwen 2.5 Coder 1.5B，完整支持官方 Jinja 提示词模板（chat_template.jinja）。',
 'Google 官方 LiteRT-LM 规格 Gemma 3 1B IT 端侧模型，内置 584MB 超轻量 INT4 量化。',
 'Google 官方 LiteRT Community 超轻量 284MB 模型，支持移动端操作与函数调用。',
]
KEYS = ['model_desc_smollm2_360m','model_desc_qwen05b','model_desc_llama1b','model_desc_ds_r1_15b',
        'model_desc_qwen15b','model_desc_smollm2_17b','model_desc_gemma2_2b','model_desc_qwen2vl',
        'model_desc_coder_lr','model_desc_gemma3_lr','model_desc_funcgemma_lr']

for i in range(11):
    old = 'description = "%s",' % descs[i]
    new = 'description = "%s",' % EN[i]
    E.append(('model/LlmModel.kt', old, new, None, None, None, None, 1))
E.append(('model/LlmModel.kt', 'name = "DeepSeek-R1 Distill 1.5B (사고 추론)"',
          'name = "DeepSeek-R1 Distill 1.5B"', None, None, None, None, 1))
E.append(('model/LlmModel.kt', 'name = "Qwen2-VL 2B (비전 멀티모달)"',
          'name = "Qwen2-VL 2B"', None, None, None, None, 1))

# ========== 1) ModelManagerScreen：描述本地化 + 默认名 ==========
F = 'ui/models/ModelManagerScreen.kt'
rep(F, '            text = model.description,', '            text = modelDescriptionText(model),')
rep(F, 'modelNameInput = "Gemma 3 1B (LiteRT 통합 비전)"', 'modelNameInput = stringResource(R.string.mm_default_name)',
    'mm_default_name', 'Gemma 3 1B (LiteRT integrated vision)', 'Gemma 3 1B (LiteRT 통합 비전)', 'Gemma 3 1B（LiteRT 内置视觉）')

# ========== 2) advisory 4 处替换 + SdEngineText ==========
os.makedirs(BASE + '/i18n', exist_ok=True)
_write(BASE + '/i18n/SdEngineText.kt', '''package com.localllm.android.i18n

import com.localllm.android.R
import com.localllm.engine.SDEngine

/**
 * SDengine 警示文案的本地化渲染：资源就绪时按当前语言输出，
 * 否则回退到 engine 模块的英文原文。
 */
object SdEngineText {
    fun advisoryText(): String {
        val a1 = AppStrings.get(R.string.sd_adv_1)
        val original = SDEngine.advisoryText()
        if (a1.isEmpty()) return original
        val prefix = original.substringBefore(' ')
        val items = listOf(
            a1,
            AppStrings.get(R.string.sd_adv_2),
            AppStrings.get(R.string.sd_adv_3),
            AppStrings.get(R.string.sd_adv_4)
        )
        return "$prefix " + items.joinToString(" ")
    }
}
''')
print("  ✓ 创建 i18n/SdEngineText.kt")

rep('engine/LlmEngine.kt', '+ SDEngine.advisoryText()', '+ SdEngineText.advisoryText()')
rep('engine/LlmEngine.kt', 'import com.localllm.android.i18n.AppStrings',
    'import com.localllm.android.i18n.AppStrings\nimport com.localllm.android.i18n.SdEngineText')
rep('ui/MainViewModel.kt', 'ModelRuntimeType.SD_ENGINE -> com.localllm.engine.SDEngine.advisoryText()',
    'ModelRuntimeType.SD_ENGINE -> SdEngineText.advisoryText()')
rep('ui/MainViewModel.kt', 'import com.localllm.android.engine.HfSource',
    'import com.localllm.android.engine.HfSource\nimport com.localllm.android.i18n.SdEngineText')
rep('ui/chat/ChatScreen.kt', 'com.localllm.engine.SDEngine.advisoryText(),', 'SdEngineText.advisoryText(),')
rep('ui/chat/ChatScreen.kt', 'import com.localllm.android.R', 'import com.localllm.android.R\nimport com.localllm.android.i18n.SdEngineText')
rep('ui/settings/SettingsScreen.kt', 'com.localllm.engine.SDEngine.advisoryText(),', 'SdEngineText.advisoryText(),')
rep('ui/settings/SettingsScreen.kt', 'import com.localllm.android.R', 'import com.localllm.android.R\nimport com.localllm.android.i18n.SdEngineText')

# ========== 3) OllamaApiServer ==========
F = 'server/OllamaApiServer.kt'
rep(F, 'package com.localllm.android.server',
    'package com.localllm.android.server\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')
rep(F, 'onStatusChange(true, "서버가 이미 포트 $DEFAULT_PORT 에서 실행 중입니다.")',
    'onStatusChange(true, AppStrings.get(R.string.aps_already, DEFAULT_PORT))',
    'aps_already', 'Server is already running on port %1$s.', '서버가 이미 포트 %1$s 에서 실행 중입니다.', '服务器已在端口 %1$s 上运行。')
rep(F, 'val hostLabel = if (boundHost == "127.0.0.1") "로컬 루프백(127.0.0.1)" else "전체 인터페이스($boundHost)"',
    'val hostLabel = if (boundHost == "127.0.0.1") AppStrings.get(R.string.aps_loopback) else AppStrings.get(R.string.aps_alliface, boundHost)',
    'aps_loopback', 'local loopback (127.0.0.1)', '로컬 루프백(127.0.0.1)', '本地回环（127.0.0.1）')
rep(F, 'aps_alliface_KEY', None) if False else None
E.append((F, 'aps_alliface_KEY', None, None, None, None, None, -1)) if False else None
rep(F, 'onStatusChange(true, "보안 API 서버가 $hostLabel 포트 $DEFAULT_PORT 에서 시작되었습니다.")',
    'onStatusChange(true, AppStrings.get(R.string.aps_started, hostLabel, DEFAULT_PORT))',
    'aps_started', 'Secure API server started on %1$s port %2$s.', '보안 API 서버가 %1$s 포트 %2$s 에서 시작되었습니다.',
    '安全 API 服务器已在 %1$s 端口 %2$s 启动。')
rep(F, 'onStatusChange(false, "서버 시작 실패 ($boundHost:$DEFAULT_PORT): ${e.localizedMessage}")',
    'onStatusChange(false, AppStrings.get(R.string.aps_start_fail, boundHost, DEFAULT_PORT, e.localizedMessage))',
    'aps_start_fail', 'Server start failed (%1$s:%2$s): %3$s', '서버 시작 실패 (%1$s:%2$s): %3$s', '服务器启动失败（%1$s:%2$s）：%3$s')
rep(F, 'onStatusChange(false, "API 서버가 중지되었습니다.")', 'onStatusChange(false, AppStrings.get(R.string.aps_stopped))',
    'aps_stopped', 'API server has stopped.', 'API 서버가 중지되었습니다.', 'API 服务器已停止。')
rep(F, 'onStatusChange(false, "서버 중지 중 오류: ${e.localizedMessage}")',
    'onStatusChange(false, AppStrings.get(R.string.aps_stop_err, e.localizedMessage))',
    'aps_stop_err', 'Error while stopping server: %1$s', '서버 중지 중 오류: %1$s', '停止服务器时出错：%1$s')
# API JSON 错误 → 英文（面向 API 消费者，语言无关）
rep(F, '''put("error", "인증 실패: 유효한 API 키가 필요합니다. 'Authorization: Bearer <api_key>' 또는 'X-API-Key' 헤더를 전달하세요.")''',
    '''put("error", "Authentication failed: a valid API key is required. Pass the 'Authorization: Bearer <api_key>' or 'X-API-Key' header.")''')
rep(F, '''put("error", "선택되거나 로드된 로컬 모델이 없습니다. 앱에서 모델을 먼저 로드하세요.").toString()''',
    '''put("error", "No local model is selected or loaded. Load a model in the app first.").toString()''', n=2)
rep(F, '''put("message", "선택되거나 로드된 로컬 모델이 없습니다.")''',
    '''put("message", "No local model is selected or loaded.")''')

# ========== 4) GgufMetadataDetector ==========
F = 'engine/GgufMetadataDetector.kt'
rep(F, 'package com.localllm.android.engine',
    'package com.localllm.android.engine\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')
rep(F, 'detailsList.add("LiteRT 올인원 통합 모델")', 'detailsList.add(AppStrings.get(R.string.det_litert_all))',
    'det_litert_all', 'LiteRT all-in-one unified model', 'LiteRT 올인원 통합 모델', 'LiteRT 一体化统一模型')
rep(F, 'detailsList.add("MoE 전문가 ${expertCount}개 (SDengine 수동 전환 가능)")',
    'detailsList.add(AppStrings.get(R.string.det_moe, expertCount))',
    'det_moe', '%1$s MoE experts (can switch to SDengine manually)', 'MoE 전문가 %1$s개 (SDengine 수동 전환 가능)',
    '%1$s 个 MoE 专家（可手动切换到 SDengine）')
rep(F, 'detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) "통합 비전타워(Vision Encoder) 내장" else "비전타워 내장 감지")',
    'detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) AppStrings.get(R.string.det_vision_integrated) else AppStrings.get(R.string.det_vision_detected))',
    'det_vision_integrated', 'Integrated vision tower (Vision Encoder)', '통합 비전타워(Vision Encoder) 내장', '内置视觉塔（视觉编码器）')
rep(F, 'det_vision_detected_KEY', None) if False else None
rep(F, 'detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) "통합 추측 디코딩 드래프터 내장" else "드래프터(MTP) 내장 감지")',
    'detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) AppStrings.get(R.string.det_drafter_integrated) else AppStrings.get(R.string.det_drafter_detected))',
    'det_drafter_integrated', 'Integrated speculative decoding drafter', '통합 추측 디코딩 드래프터 내장', '内置推测解码草稿模型')

# ========== 5) LiteRtAcceleratorPolicy ==========
F = 'engine/LiteRtAcceleratorPolicy.kt'
rep(F, 'package com.localllm.android.engine',
    'package com.localllm.android.engine\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')
rep(F, 'Candidate("GPU 가속 (비전 연동)"', 'Candidate(AppStrings.get(R.string.pol_gpu_vision)',
    'pol_gpu_vision', 'GPU acceleration (vision linked)', 'GPU 가속 (비전 연동)', 'GPU 加速（视觉联动）')
rep(F, 'Candidate("GPU 가속 (${maxTokens} ctx)"', 'Candidate(AppStrings.get(R.string.pol_gpu, maxTokens)',
    'pol_gpu', 'GPU acceleration (%1$s ctx)', 'GPU 가속 (%1$s ctx)', 'GPU 加速（%1$s 上下文）')
rep(F, 'Candidate("NPU 가속 (${maxTokens} ctx)"', 'Candidate(AppStrings.get(R.string.pol_npu, maxTokens)',
    'pol_npu', 'NPU acceleration (%1$s ctx)', 'NPU 가속 (%1$s ctx)', 'NPU 加速（%1$s 上下文）')
rep(F, 'Candidate("CPU 멀티스레드(${threadCount}T, 비전 연동)"', 'Candidate(AppStrings.get(R.string.pol_cpu_mt_vision, threadCount)',
    'pol_cpu_mt_vision', 'CPU multithread (%1$sT, vision linked)', 'CPU 멀티스레드(%1$sT, 비전 연동)', 'CPU 多线程（%1$s 线程，视觉联动）')
rep(F, 'Candidate("CPU 멀티스레드(${threadCount}T, ${maxTokens} ctx)"', 'Candidate(AppStrings.get(R.string.pol_cpu_mt, threadCount, maxTokens)',
    'pol_cpu_mt', 'CPU multithread (%1$sT, %2$s ctx)', 'CPU 멀티스레드(%1$sT, %2$s ctx)', 'CPU 多线程（%1$s 线程，%2$s 上下文）')
rep(F, 'Candidate("CPU 기본 컨텍스트(${threadCount}T)"', 'Candidate(AppStrings.get(R.string.pol_cpu_ctx, threadCount)',
    'pol_cpu_ctx', 'CPU default context (%1$sT)', 'CPU 기본 컨텍스트(%1$sT)', 'CPU 默认上下文（%1$s 线程）')
rep(F, 'Candidate("CPU 기본 백엔드"', 'Candidate(AppStrings.get(R.string.pol_cpu_default)',
    'pol_cpu_default', 'CPU default backend', 'CPU 기본 백엔드', 'CPU 默认后端')

# ========== 6) ChatCrypto ==========
F = 'data/crypto/ChatCrypto.kt'
rep(F, 'package com.localllm.android.data.crypto',
    'package com.localllm.android.data.crypto\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')
rep(F, 'throw SecurityException("Android Keystore 하드웨어 보안 키 접근에 실패했습니다: ${e.localizedMessage}", e)',
    'throw SecurityException(AppStrings.get(R.string.cry_keystore_fail, e.localizedMessage), e)',
    'cry_keystore_fail', 'Failed to access Android Keystore hardware security key: %1$s',
    'Android Keystore 하드웨어 보안 키 접근에 실패했습니다: %1$s', '访问 Android Keystore 硬件安全密钥失败：%1$s')
rep(F, 'throw SecurityException("암호화 처리 중 보안 오류가 발생했습니다: ${e.localizedMessage}", e)',
    'throw SecurityException(AppStrings.get(R.string.cry_encrypt_err, e.localizedMessage), e)',
    'cry_encrypt_err', 'Security error during encryption: %1$s', '암호화 처리 중 보안 오류가 발생했습니다: %1$s', '加密过程发生安全错误：%1$s')
rep(F, 'throw SecurityException("손상된 Base64 암호문 데이터입니다.", e)',
    'throw SecurityException(AppStrings.get(R.string.cry_corrupt), e)',
    'cry_corrupt', 'Corrupt Base64 ciphertext data.', '손상된 Base64 암호문 데이터입니다.', 'Base64 密文数据已损坏。')
rep(F, 'throw SecurityException("암호문 데이터 길이가 유효하지 않습니다 (최소 ${IV_LENGTH_BYTE + 1}바이트 필요).")',
    'throw SecurityException(AppStrings.get(R.string.cry_bad_length, IV_LENGTH_BYTE + 1))',
    'cry_bad_length', 'Invalid ciphertext data length (at least %1$s bytes required).',
    '암호문 데이터 길이가 유효하지 않습니다 (최소 %1$s바이트 필요).', '密文数据长度无效（至少需要 %1$s 字节）。')
rep(F, 'throw SecurityException("암호문 복호화 실패: 무결성 검증에 실패했거나 키가 일치하지 않습니다.", e)',
    'throw SecurityException(AppStrings.get(R.string.cry_decrypt_fail), e)',
    'cry_decrypt_fail', 'Decryption failed: integrity check failed or the key does not match.',
    '암호문 복호화 실패: 무결성 검증에 실패했거나 키가 일치하지 않습니다.', '解密失败：完整性校验未通过或密钥不匹配。')

# ========== 7) PromptFormatter 系统提示词 ==========
pf = BASE + '/engine/PromptFormatter.kt'
ps = _read(pf)
m = re.search(r'append\("\\n답변을 작성할 때 사고 및 추론 과정은一定是', ps)
line63 = [l for l in ps.splitlines() if '태그 안에 작성하세요' in l][0]
tok = re.search(r'반드시 (.*?) 태그 안에', line63).group(1)
F = 'engine/PromptFormatter.kt'
rep(F, 'package com.localllm.android.engine',
    'package com.localllm.android.engine\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')
rep(F, 'append("당신은 Android 기기에서 완전히 로컬로 실행되는 친절하고 유능한 AI 어시스턴트입니다. 외부 서버 없이 기기 내부에서 모든 답변을 생성합니다.")',
    'append(AppStrings.get(R.string.sys_prompt))',
    'sys_prompt',
    'You are a friendly and capable AI assistant that runs entirely locally on an Android device. You generate all answers on-device, without any external servers.',
    '당신은 Android 기기에서 완전히 로컬로 실행되는 친절하고 유능한 AI 어시스턴트입니다. 외부 서버 없이 기기 내부에서 모든 답변을 생성합니다.',
    '你是运行在 Android 设备上、完全本地执行的友善且能干的 AI 助手。所有回答都在设备内部生成，不依赖任何外部服务器。')
rep(F, 'append("\\n\\n[현재 기기 로컬 컨텍스트 및 도구 정보]:\\n").append(toolsContext.trim())',
    'append("\\n\\n" + AppStrings.get(R.string.sys_ctx_header) + "\\n").append(toolsContext.trim())',
    'sys_ctx_header', '[Current device local context and tool information]:',
    '[현재 기기 로컬 컨텍스트 및 도구 정보]:', '[当前设备本地上下文与工具信息]：')
rep(F, line63, '                append("\\n" + AppStrings.get(R.string.sys_think))',
    'sys_think',
    'When writing your answer, always put your thinking and reasoning process inside %s tags.' % tok,
    line63.split('append("\\n', 1)[1].rsplit('")', 1)[0],
    '撰写回答时，务必把思考与推理过程写在 %s 标签内。' % tok)

# ========== 8) SDEngine → 英文（engine 模块无资源，作为兜底/日志） ==========
F = '../engine/src/main/java/com/localllm/engine/SDEngine.kt'  # edit() 走 BASE 相对路径——用绝对修正
F = None
from i18n_lib import REPO
sp = os.path.join(REPO, 'engine/src/main/java/com/localllm/engine/SDEngine.kt')
ss = _read(sp)
sd_pairs = [
 ('"TEST 빌드: SDengine은 실험 단계의 자체 추론엔진입니다. 실행은 되지만 출력 품질을 신뢰하지 마세요.",',
  '"TEST build: SDengine is an experimental in-house engine. It runs, but do not trust its output quality.",'),
 ('"최적화 커널(융합 양자화 matvec, RoPE 캐시, 스크래치 재사용, top-k 힙)이 적용되었습니다. 성능 미측정, K-퀀트 bit-exact 검증은 pending이라 llama.cpp보다 느리고 수치 오차가 있을 수 있습니다.",',
  '"Optimized kernels (fused quantized matvec, RoPE cache, scratch reuse, top-k heap) are applied. Performance is unmeasured and K-quant bit-exact validation is pending, so it is slower than llama.cpp and may have numeric drift.",'),
 ('"SDengine은 MoE 전용입니다. Dense 전용 모델과 MoE가 아닌 하이브리드는 범위 밖이며, 로드에 실패하면 llama.cpp로 대체 실행됩니다.",',
  '"SDengine is MoE-only. Dense-only models and non-MoE hybrids are out of scope; a failed load falls back to llama.cpp.",'),
 ('"dense 가중치는 양자화 그대로 상주하고 expert 타일은 SSD에서 스트리밍됩니다. 파일 전체가 아닌 dense+KV만 RAM에 들어가면 되므로, 메모리보다 큰 MoE도 로드할 수 있습니다. dense+KV가 가용 RAM을 넘으면 로드가 거부됩니다."',
  '"Dense weights stay resident in quantized form while expert tiles stream from SSD. Only dense+KV needs to fit in RAM (not the whole file), so MoEs larger than memory can load; a load is refused when dense+KV exceeds available RAM."'),
 ('throw UnsupportedArchException("SDengine 미지원 아키텍처 \'$arch\' (llama.cpp로 실행하세요)")',
  'throw UnsupportedArchException("SDengine unsupported architecture \'$arch\' (run with llama.cpp)")'),
 ('throw UnsupportedArchException("SDengine 미지원 아키텍처 \'$arch\': $reason (llama.cpp로 실행하세요)")',
  'throw UnsupportedArchException("SDengine unsupported architecture \'$arch\': $reason (run with llama.cpp)")'),
]
for old, new in sd_pairs:
    assert ss.count(old) == 1, "SDEngine 锚点: %r" % old[:60]
    ss = ss.replace(old, new)
_write(sp, ss)
print("  ✓ SDEngine.kt 英文化 6 处")

# ========== 执行 edit + add_res ==========
from collections import defaultdict
groups = defaultdict(list)
res_all = [(KEYS[i], EN[i], descs[i], ZH[i]) for i in range(11)]
for (f, old, new, k, e, z, c, n) in E:
    if old == new: continue
    groups[f].append((old, new, k, e, z, c, n))
for f, items in groups.items():
    pairs = [(o, nw) for (o, nw, k, e, z, c, n) in items]
    counts = {o: n for (o, nw, k, e, z, c, n) in items if n != 1}
    edit(f, pairs, counts)
    for (o, nw, k, e, z, c, n) in items:
        if k: res_all.append((k, e, z, c))
# 目录描述行已单独进 res_all（在 groups 之前加入）——避免重复
seen = set(); uniq = []
for r in res_all:
    if r[0] in seen: continue
    seen.add(r[0]); uniq.append(r)
add_res(uniq)
print("4b: %d 键" % len(uniq))
