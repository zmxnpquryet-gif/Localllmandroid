# -*- coding: utf-8 -*-
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from i18n_lib import edit, add_res

K = 'engine/LlmEngine.kt'

# (old, new, key, en, ko, zh)
E = []
def rep(old, new, key=None, en=None, ko=None, zh=None):
    E.append((old, new, key, en, ko, zh))

rep('package com.localllm.android.engine',
    'package com.localllm.android.engine\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')

rep('} ?: throw IllegalStateException("모델 파일 디스크립터를 열 수 없습니다: ${file.path}")',
    '} ?: throw IllegalStateException(AppStrings.get(R.string.eng_no_fd, file.path))',
    'eng_no_fd', 'Cannot open model file descriptor: %1$s', '모델 파일 디스크립터를 열 수 없습니다: %1$s', '无法打开模型文件描述符：%1$s')

rep('return@withContext "다운로드된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 다운로드하거나 불러오세요."',
    'return@withContext AppStrings.get(R.string.eng_no_model)',
    'eng_no_model', 'No downloaded local model. Download or load one from the Model Manager first.',
    '다운로드된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 다운로드하거나 불러오세요.', '没有已下载的本地模型。请先在模型管理中下载或加载一个。')

rep('return@withContext "모델 파일 경로를 찾을 수 없습니다."',
    'return@withContext AppStrings.get(R.string.eng_no_path)',
    'eng_no_path', 'Model file path not found.', '모델 파일 경로를 찾을 수 없습니다.', '找不到模型文件路径。')

rep('return@withContext "모델 파일이 디스크에 존재하지 않거나 빈 파일입니다: ${modelFile.name}"',
    'return@withContext AppStrings.get(R.string.eng_no_file, modelFile.name)',
    'eng_no_file', 'Model file is missing on disk or empty: %1$s', '모델 파일이 디스크에 존재하지 않거나 빈 파일입니다: %1$s', '模型文件在磁盘上不存在或为空：%1$s')

rep('onStageUpdate?.invoke("가용 메모리 부족", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_nomem), 0f)',
    'eng_stage_nomem', 'Not enough free memory', '가용 메모리 부족', '可用内存不足')

rep('''                    return@withContext "SDengine으로도 메모리 부족: dense ${denseMb}MB + KV가 RAM에 들어가야 하지만 " +
                            "가용 RAM이 ${snapshot.availMb}MB입니다 (expert ${expertMb}MB는 SSD 스트리밍 대상이라 RAM과 무관). " +
                            "컨텍스트를 512까지 낮춰도 안 되면 이 기기에서는 무리입니다."''',
    '                    return@withContext AppStrings.get(R.string.eng_sd_nomem, denseMb, snapshot.availMb, expertMb)',
    'eng_sd_nomem',
    'Not enough memory even for SDengine: dense %1$sMB + KV must fit in RAM, but only %2$sMB is free (the %3$sMB of experts stream from SSD and do not use RAM). If lowering the context to 512 does not help, this device cannot run it.',
    'SDengine으로도 메모리 부족: dense %1$sMB + KV가 RAM에 들어가야 하지만 가용 RAM이 %2$sMB입니다 (expert %3$sMB는 SSD 스트리밍 대상이라 RAM과 무관). 컨텍스트를 512까지 낮춰도 안 되면 이 기기에서는 무리입니다.',
    'SDengine 同样内存不足：dense %1$sMB + KV 必须放入 RAM，但可用 RAM 仅 %2$sMB（专家 %3$sMB 从 SSD 流式读取，不占 RAM）。把上下文降到 512 仍不行的话，本设备无法运行。')

rep('''                return@withContext "가용 메모리 부족: ${model.name} 로드에 약 ${decision.estimate?.totalMb()}MB가 필요하지만 " +
                        "가용 RAM이 ${snapshot.availMb}MB입니다. 백그라운드 앱을 정리하거나 더 작은 양자화 모델을 선택하세요."''',
    '                return@withContext AppStrings.get(R.string.eng_no_mem_model, model.name, decision.estimate?.totalMb(), snapshot.availMb)',
    'eng_no_mem_model',
    'Not enough free memory: loading %1$s needs about %2$sMB, but only %3$sMB RAM is free. Close background apps or pick a smaller quantization.',
    '가용 메모리 부족: %1$s 로드에 약 %2$sMB가 필요하지만 가용 RAM이 %3$sMB입니다. 백그라운드 앱을 정리하거나 더 작은 양자화 모델을 선택하세요.',
    '可用内存不足：加载 %1$s 约需 %2$sMB，但可用 RAM 仅 %3$sMB。请清理后台应用或选择更小的量化版本。')

rep('                    "메모리 보호: 컨텍스트 ${settings.contextWindow} → $effectiveContext 자동 하향 (${decision.reason})",',
    '                    AppStrings.get(R.string.eng_mem_guard, settings.contextWindow, effectiveContext, decision.reason),',
    'eng_mem_guard', 'Memory guard: context %1$s → %2$s auto-reduced (%3$s)',
    '메모리 보호: 컨텍스트 %1$s → %2$s 자동 하향 (%3$s)', '内存保护：上下文 %1$s → %2$s 已自动降低（%3$s）')

rep('onStageUpdate?.invoke("모델 무결성 검증 중...", 0.10f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_verify), 0.10f)',
    'eng_stage_verify', 'Verifying model integrity...', '모델 무결성 검증 중...', '正在校验模型完整性…')

rep('onStageUpdate?.invoke("LiteRT 모델 파일 검증 중...", 0.10f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_verify), 0.10f)',
    'eng_stage_litert_verify', 'Verifying LiteRT model file...', 'LiteRT 모델 파일 검증 중...', '正在校验 LiteRT 模型文件…')

rep('onStageUpdate?.invoke("인증 실패 (401)", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_401), 0f)',
    'eng_stage_401', 'Authentication failed (401)', '인증 실패 (401)', '认证失败（401）')

rep('return@withContext "모델 파일 오류: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 HF 토큰을 입력 후 모델을 다시 다운로드하세요."',
    'return@withContext AppStrings.get(R.string.eng_err_401)',
    'eng_err_401', 'Model file error: Hugging Face authentication required (401). Enter an HF token in Settings and download the model again.',
    '모델 파일 오류: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 HF 토큰을 입력 후 모델을 다시 다운로드하세요.',
    '模型文件错误：需要 Hugging Face 认证（401）。请在设置中填写 HF 令牌后重新下载模型。')

rep('onStageUpdate?.invoke("HTML 오류 페이지", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_html), 0f)',
    'eng_stage_html', 'HTML error page', 'HTML 오류 페이지', 'HTML 错误页')

rep('return@withContext "모델 파일 오류: 다운로드된 파일이 모델 바이너리가 아닌 HTML 웹페이지입니다."',
    'return@withContext AppStrings.get(R.string.eng_err_html)',
    'eng_err_html', 'Model file error: the downloaded file is an HTML page, not a model binary.',
    '모델 파일 오류: 다운로드된 파일이 모델 바이너리가 아닌 HTML 웹페이지입니다.', '模型文件错误：下载到的是 HTML 网页，而非模型二进制文件。')

rep('onStageUpdate?.invoke("파일 크기 오류", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_size), 0f)',
    'eng_stage_size', 'File size error', '파일 크기 오류', '文件大小异常')

rep('return@withContext "모델 파일 오류: 파일 크기가 비정상적으로 작습니다 (${modelFile.length()} bytes). 올바른 모델 바이너리가 아닙니다."',
    'return@withContext AppStrings.get(R.string.eng_err_size, modelFile.length())',
    'eng_err_size', 'Model file error: file is abnormally small (%1$s bytes) and is not a valid model binary.',
    '모델 파일 오류: 파일 크기가 비정상적으로 작습니다 (%1$s bytes). 올바른 모델 바이너리가 아닙니다.',
    '模型文件错误：文件异常小（%1$s 字节），不是有效的模型二进制文件。')

rep('onStageUpdate?.invoke("LiteRT JNI 네이티브 라이브러리 검증 중...", 0.20f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_jni), 0.20f)',
    'eng_stage_jni', 'Verifying LiteRT JNI native library...', 'LiteRT JNI 네이티브 라이브러리 검증 중...', '正在校验 LiteRT JNI 原生库…')

rep('onStageUpdate?.invoke("이전 GPU 초기화 실패 기록 → CPU로 시작", 0.30f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_gpu_prev), 0.30f)',
    'eng_stage_gpu_prev', 'Previous GPU init failure recorded → starting on CPU', '이전 GPU 초기화 실패 기록 → CPU로 시작', '记录到上次 GPU 初始化失败 → 从 CPU 启动')

rep('onStageUpdate?.invoke("OpenCL 드라이버 없음 → GPU 건너뜀", 0.30f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_no_opencl), 0.30f)',
    'eng_stage_no_opencl', 'No OpenCL driver → skipping GPU', 'OpenCL 드라이버 없음 → GPU 건너뜀', '无 OpenCL 驱动 → 跳过 GPU')

rep('onStageUpdate?.invoke("LiteRT $backendName 초기화 중...", 0.45f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_init, backendName), 0.45f)',
    'eng_stage_litert_init', 'Initializing LiteRT %1$s...', 'LiteRT %1$s 초기화 중...', '正在初始化 LiteRT %1$s…')

rep('onStageUpdate?.invoke("LiteRT 가중치 매핑 및 모델 초기화...", 0.70f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_map), 0.70f)',
    'eng_stage_litert_map', 'Mapping LiteRT weights and initializing model...', 'LiteRT 가중치 매핑 및 모델 초기화...', '正在映射 LiteRT 权重并初始化模型…')

rep('onStageUpdate?.invoke("LiteRT 초기화 실패", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_fail), 0f)',
    'eng_stage_litert_fail', 'LiteRT initialization failed', 'LiteRT 초기화 실패', 'LiteRT 初始化失败')

rep('.ifBlank { "알 수 없는 오류" }',
    '.ifBlank { AppStrings.get(R.string.eng_unknown_err) }',
    'eng_unknown_err', 'Unknown error', '알 수 없는 오류', '未知错误')

rep('return@withContext "LiteRT LM 엔진 초기화 오류: $summary"',
    'return@withContext AppStrings.get(R.string.eng_litert_init_err, summary)',
    'eng_litert_init_err', 'LiteRT LM engine init error: %1$s', 'LiteRT LM 엔진 초기화 오류: %1$s', 'LiteRT LM 引擎初始化错误：%1$s')

rep('isVisionTowerLoaded = usedBackendName.contains("비전 연동")',
    'isVisionTowerLoaded = usedBackendName.contains("비전 연동") || usedBackendName.contains("vision") || usedBackendName.contains("视觉")',
    None, None, None, None)

rep('val visionMsg = if (isVisionTowerLoaded) " + 통합 올인원 비전타워" else ""',
    'val visionMsg = if (isVisionTowerLoaded) " " + AppStrings.get(R.string.eng_msg_vision) else ""',
    'eng_msg_vision', '+ Integrated all-in-one vision tower', '+ 통합 올인원 비전타워', '+ 内置一体化视觉塔')

rep('val drafterMsg = if (model.supportsMtp) " + 통합 드래프터" else ""',
    'val drafterMsg = if (model.supportsMtp) " " + AppStrings.get(R.string.eng_msg_drafter) else ""',
    'eng_msg_drafter', '+ Integrated drafter', '+ 통합 드래프터', '+ 内置草稿模型')

rep('val templateMsg = if (model.localTemplatePath != null) " (Jinja 템플릿 적용)" else ""',
    'val templateMsg = if (model.localTemplatePath != null) " " + AppStrings.get(R.string.eng_msg_template) else ""',
    'eng_msg_template', '(Jinja template applied)', '(Jinja 템플릿 적용)', '（已应用 Jinja 模板）')

rep('?.let { " · GPU/OpenCL 초기화 실패 → CPU 대체 실행 (${it.second})" }',
    '?.let { " · " + AppStrings.get(R.string.eng_gpu_fallback, it.second) }',
    'eng_gpu_fallback', 'GPU/OpenCL init failed → running on CPU (%1$s)', ' · GPU/OpenCL 초기화 실패 → CPU 대체 실행 (%1$s)', 'GPU/OpenCL 初始化失败 → 回退 CPU 运行（%1$s）')

rep('''            val resultMsg = "[LiteRT LM] ${model.name} 온디바이스 로드 완료 [$usedBackendName]$visionMsg$drafterMsg$templateMsg$fallbackNote"''',
    '''            val resultMsg = AppStrings.get(R.string.eng_litert_loaded, model.name, usedBackendName, visionMsg, drafterMsg, templateMsg, fallbackNote)''',
    'eng_litert_loaded',
    '[LiteRT LM] %1$s loaded on-device [%2$s]%3$s %4$s %5$s%6$s',
    '[LiteRT LM] %1$s 온디바이스 로드 완료 [%2$s]%3$s%4$s%5$s%6$s',
    '[LiteRT LM] %1$s 已在本机加载 [%2$s]%3$s %4$s %5$s%6$s')

rep('"Hugging Face 인증 필요 (401 Unauthorized). 설정에서 HF 토큰을 입력 후 모델을 다시 다운로드하세요."',
    'AppStrings.get(R.string.eng_err_401)', None, None, None, None)
rep('"모델 다운로드 링크가 유효하지 않습니다 (404 Not Found)."',
    'AppStrings.get(R.string.eng_err_404)', 'eng_err_404', 'Invalid model download link (404 Not Found).',
    '모델 다운로드 링크가 유효하지 않습니다 (404 Not Found).', '模型下载链接无效（404 Not Found）。')
rep('"다운로드된 파일이 모델 바이너리가 아닌 HTML 에러 페이지입니다. 파일을 삭제하고 올바른 URL로 다시 다운로드하세요."',
    'AppStrings.get(R.string.eng_err_html2)', 'eng_err_html2',
    'The downloaded file is an HTML error page, not a model binary. Delete it and download again with a correct URL.',
    '다운로드된 파일이 모델 바이너리가 아닌 HTML 에러 페이지입니다. 파일을 삭제하고 올바른 URL로 다시 다운로드하세요.',
    '下载到的是 HTML 错误页而非模型文件。请删除后用正确 URL 重新下载。')
rep('"파일 헤더가 GGUF 매직넘버(\'GGUF\')와 일치하지 않습니다. 손상되었거나 유효하지 않은 파일입니다."',
    'AppStrings.get(R.string.eng_err_magic)', 'eng_err_magic',
    "File header does not match the GGUF magic number ('GGUF'). The file is corrupted or invalid.",
    "파일 헤더가 GGUF 매직넘버('GGUF')와 일치하지 않습니다. 손상되었거나 유효하지 않은 파일입니다.",
    "文件头与 GGUF 魔数（'GGUF'）不匹配，文件已损坏或无效。")

rep('onStageUpdate?.invoke("무결성 검증 실패", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_integrity), 0f)',
    'eng_stage_integrity', 'Integrity check failed', '무결성 검증 실패', '完整性校验失败')

rep('return@withContext "모델 파일 형식 오류: $errorReason"',
    'return@withContext AppStrings.get(R.string.eng_fmt_err, errorReason)',
    'eng_fmt_err', 'Model file format error: %1$s', '모델 파일 형식 오류: %1$s', '模型文件格式错误：%1$s')

rep('onStageUpdate?.invoke("SDengine(TEST) 가중치 바인딩 중...", 0.30f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_sd_bind), 0.30f)',
    'eng_stage_sd_bind', 'Binding SDengine(TEST) weights...', 'SDengine(TEST) 가중치 바인딩 중...', '正在绑定 SDengine(TEST) 权重…')

rep('''                val resultMsg = "[SDengine][TEST] ${model.name} 로드 완료 (layers=${info.nLayers}, experts=${info.nExperts}, " +
                        "resident dense=${info.denseResidentBytes / MemorySnapshot.MB}MB, " +
                        "experts ${info.expertBytes / MemorySnapshot.MB}MB SSD 스트리밍, tensors=${info.tensorCount}) · " +
                        SDEngine.advisoryText()''',
    '''                val resultMsg = AppStrings.get(R.string.eng_sd_loaded, model.name, info.nLayers, info.nExperts, info.denseResidentBytes / MemorySnapshot.MB, info.expertBytes / MemorySnapshot.MB, info.tensorCount) + SDEngine.advisoryText()''',
    'eng_sd_loaded',
    '[SDengine][TEST] %1$s loaded (layers=%2$s, experts=%3$s, resident dense=%4$sMB, experts %5$sMB SSD streaming, tensors=%6$s) ·',
    '[SDengine][TEST] %1$s 로드 완료 (layers=%2$s, experts=%3$s, resident dense=%4$sMB, experts %5$sMB SSD 스트리밍, tensors=%6$s) ·',
    '[SDengine][TEST] %1$s 加载完成（layers=%2$s，experts=%3$s，常驻 dense=%4$sMB，专家 %5$sMB 走 SSD 流式，tensors=%6$s）·')

rep('onStageUpdate?.invoke("SDengine 로드 실패 → llama.cpp 대체 실행", 0.30f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_sd_fallback), 0.30f)',
    'eng_stage_sd_fallback', 'SDengine load failed → falling back to llama.cpp', 'SDengine 로드 실패 → llama.cpp 대체 실행',
    'SDengine 加载失败 → 回退 llama.cpp 运行')

rep('return@withContext "SDengine 로드 실패($reason) — llama.cpp 대체 실행. $fallbackStatus"',
    'return@withContext AppStrings.get(R.string.eng_sd_fallback_msg, reason, fallbackStatus)',
    'eng_sd_fallback_msg', 'SDengine load failed (%1$s) — running on llama.cpp instead. %2$s',
    'SDengine 로드 실패(%1$s) — llama.cpp 대체 실행. %2$s', 'SDengine 加载失败（%1$s）— 回退 llama.cpp 运行。%2$s')

rep('onStageUpdate?.invoke("llama.cpp 네이티브 컨텍스트 생성 중...", 0.35f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_llama_ctx), 0.35f)',
    'eng_stage_llama_ctx', 'Creating llama.cpp native context...', 'llama.cpp 네이티브 컨텍스트 생성 중...', '正在创建 llama.cpp 原生上下文…')

rep('candidates.add(GgufInitCandidate("GPU 가속 (${targetGpuLayers}L) + mmproj 비전타워", useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = targetGpuLayers))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand1, targetGpuLayers), useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = targetGpuLayers))',
    'eng_cand1', 'GPU offload (%1$sL) + mmproj vision tower', 'GPU 가속 (%1$sL) + mmproj 비전타워', 'GPU 加速（%1$s 层）+ mmproj 视觉塔')
rep('candidates.add(GgufInitCandidate("GPU 가속 (${targetGpuLayers}L, ${contextWindow} ctx)", useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = targetGpuLayers))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand2, targetGpuLayers, contextWindow), useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = targetGpuLayers))',
    'eng_cand2', 'GPU offload (%1$sL, %2$s ctx)', 'GPU 가속 (%1$sL, %2$s ctx)', 'GPU 加速（%1$s 层，%2$s 上下文）')
rep('candidates.add(GgufInitCandidate("네이티브 mmap + mmproj 비전타워", useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand3), useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))',
    'eng_cand3', 'Native mmap + mmproj vision tower', '네이티브 mmap + mmproj 비전타워', '原生 mmap + mmproj 视觉塔')
rep('candidates.add(GgufInitCandidate("직접 메모리 로드 + mmproj 비전타워", useMmap = false, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand4), useMmap = false, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))',
    'eng_cand4', 'Direct memory load + mmproj vision tower', '직접 메모리 로드 + mmproj 비전타워', '直接内存加载 + mmproj 视觉塔')
rep('candidates.add(GgufInitCandidate("네이티브 mmap (${contextWindow} ctx)", useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand5, contextWindow), useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))',
    'eng_cand5', 'Native mmap (%1$s ctx)', '네이티브 mmap (%1$s ctx)', '原生 mmap（%1$s 上下文）')
rep('candidates.add(GgufInitCandidate("직접 메모리 로드 (${contextWindow} ctx)", useMmap = false, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand6, contextWindow), useMmap = false, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))',
    'eng_cand6', 'Direct memory load (%1$s ctx)', '직접 메모리 로드 (%1$s ctx)', '直接内存加载（%1$s 上下文）')
rep('candidates.add(GgufInitCandidate("안정화 mmap 모드 (2048 ctx)", useMmap = true, useMmproj = false, ctxLength = 2048, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand7), useMmap = true, useMmproj = false, ctxLength = 2048, gpuLayers = 0))',
    'eng_cand7', 'Stabilized mmap mode (2048 ctx)', '안정화 mmap 모드 (2048 ctx)', '稳定 mmap 模式（2048 上下文）')
rep('candidates.add(GgufInitCandidate("절전 직접 메모리 모드 (1024 ctx)", useMmap = false, useMmproj = false, ctxLength = 1024, gpuLayers = 0))',
    'candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand8), useMmap = false, useMmproj = false, ctxLength = 1024, gpuLayers = 0))',
    'eng_cand8', 'Power-saving direct memory mode (1024 ctx)', '절전 직접 메모리 모드 (1024 ctx)', '省电直接内存模式（1024 上下文）')

rep('onStageUpdate?.invoke("${cand.desc} 로드 시도 중...", progressFraction)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_try, cand.desc), progressFraction)',
    'eng_stage_try', 'Trying to load %1$s...', '%1$s 로드 시도 중...', '正在尝试加载 %1$s…')

rep('throw IllegalStateException("llama.cpp 네이티브 컨텍스트 핸들(0) 반환 실패")',
    'throw IllegalStateException(AppStrings.get(R.string.eng_ctx_handle_fail))',
    'eng_ctx_handle_fail', 'llama.cpp native context handle (0) returned failure', 'llama.cpp 네이티브 컨텍스트 핸들(0) 반환 실패',
    'llama.cpp 原生上下文句柄（0）返回失败')

rep('val errorMsg = lastError?.localizedMessage ?: lastError?.message ?: "llama.cpp 네이티브 컨텍스트 생성 실패"',
    'val errorMsg = lastError?.localizedMessage ?: lastError?.message ?: AppStrings.get(R.string.eng_ctx_create_fail)',
    'eng_ctx_create_fail', 'Failed to create llama.cpp native context', 'llama.cpp 네이티브 컨텍스트 생성 실패',
    '创建 llama.cpp 原生上下文失败')

rep('onStageUpdate?.invoke("로드 실패", 0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_load_fail), 0f)',
    'eng_stage_load_fail', 'Load failed', '로드 실패', '加载失败')

rep('return@withContext "모델 로드 실패: $errorMsg"',
    'return@withContext AppStrings.get(R.string.eng_load_fail, errorMsg)',
    'eng_load_fail', 'Model load failed: %1$s', '모델 로드 실패: %1$s', '模型加载失败：%1$s')

rep('val visionText = if (isVisionTowerLoaded) " + mmproj 비전타워" else ""',
    'val visionText = if (isVisionTowerLoaded) " " + AppStrings.get(R.string.eng_msg_mmproj) else ""',
    'eng_msg_mmproj', '+ mmproj vision tower', '+ mmproj 비전타워', '+ mmproj 视觉塔')

rep('val resultMsg = "[llama.cpp GGUF] ${model.name} 온디바이스 로드 완료 ($successfulDesc, ${threadCount}T$visionText)"',
    'val resultMsg = AppStrings.get(R.string.eng_llama_loaded, model.name, successfulDesc, threadCount, visionText)',
    'eng_llama_loaded', '[llama.cpp GGUF] %1$s loaded on-device (%2$s, %3$sT%4$s)',
    '[llama.cpp GGUF] %1$s 온디바이스 로드 완료 (%2$s, %3$sT%4$s)', '[llama.cpp GGUF] %1$s 已在本机加载（%2$s，%3$s 线程%4$s）')

rep('throw IllegalStateException("BUSY_INFERENCE: 다른 추론 요청이 진행 중입니다. 잠시 후 다시 시도하세요.")',
    'throw IllegalStateException("BUSY_INFERENCE: " + AppStrings.get(R.string.eng_busy))',
    'eng_busy', 'Another inference request is already running. Try again in a moment.',
    '다른 추론 요청이 진행 중입니다. 잠시 후 다시 시도하세요.', '另一个推理请求正在进行，请稍后重试。')

rep('''                backendNote = "LiteRT GPU(OpenCL) 샘플러를 사용할 수 없어 CPU 백엔드로 전환했습니다. " +
                        "다음 실행부터는 GPU를 건너뜁니다 (설정에서 GPU 가속을 다시 켜면 재시도)."''',
    '''                backendNote = AppStrings.get(R.string.eng_sampler_cpu)''',
    'eng_sampler_cpu',
    'The LiteRT GPU (OpenCL) sampler was unavailable, so a CPU backend is used. GPU will be skipped on the next run (re-enable GPU acceleration in Settings to retry).',
    'LiteRT GPU(OpenCL) 샘플러를 사용할 수 없어 CPU 백엔드로 전환했습니다. 다음 실행부터는 GPU를 건너뜁니다 (설정에서 GPU 가속을 다시 켜면 재시도).',
    'LiteRT GPU（OpenCL）采样器不可用，已切换到 CPU 后端。下次运行将跳过 GPU（在设置中重新打开 GPU 加速可重试）。')

rep('?: throw IllegalStateException("선택된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 로드하세요.")',
    '?: throw IllegalStateException(AppStrings.get(R.string.eng_no_selected))',
    'eng_no_selected', 'No local model selected. Load a model from the Model Manager first.',
    '선택된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 로드하세요.', '未选择本地模型。请先在模型管理中加载一个模型。')

rep('throw IllegalStateException("${model.name} 모델이 아직 로드되지 않았습니다. 모델을 먼저 로드해 주세요.")',
    'throw IllegalStateException(AppStrings.get(R.string.eng_not_loaded, model.name))',
    'eng_not_loaded', 'Model %1$s is not loaded yet. Please load it first.', '%1$s 모델이 아직 로드되지 않았습니다. 모델을 먼저 로드해 주세요.',
    '模型 %1$s 尚未加载，请先加载。')

rep('?: throw IllegalStateException("SDengine이 준비되지 않았습니다.")',
    '?: throw IllegalStateException(AppStrings.get(R.string.eng_sd_not_ready))',
    'eng_sd_not_ready', 'SDengine is not ready.', 'SDengine이 준비되지 않았습니다.', 'SDengine 未就绪。')

rep('?: throw IllegalStateException("LiteRT 엔진이 준비되지 않았습니다.")',
    '?: throw IllegalStateException(AppStrings.get(R.string.eng_litert_not_ready))',
    'eng_litert_not_ready', 'LiteRT engine is not ready.', 'LiteRT 엔진이 준비되지 않았습니다.', 'LiteRT 引擎未就绪。')

rep('throw IllegalStateException("LiteRT 대화 세션을 준비하지 못했습니다: ${e.localizedMessage ?: e.message}")',
    'throw IllegalStateException(AppStrings.get(R.string.eng_session_fail, e.localizedMessage ?: e.message))',
    'eng_session_fail', 'Failed to prepare LiteRT chat session: %1$s', 'LiteRT 대화 세션을 준비하지 못했습니다: %1$s',
    '准备 LiteRT 对话会话失败：%1$s')

rep('throw RuntimeException("LiteRT 추론 오류: ${e.localizedMessage ?: e.message}")',
    'throw RuntimeException(AppStrings.get(R.string.eng_litert_infer_err, e.localizedMessage ?: e.message))',
    'eng_litert_infer_err', 'LiteRT inference error: %1$s', 'LiteRT 추론 오류: %1$s', 'LiteRT 推理错误：%1$s')

rep('?: throw IllegalStateException("추론 엔진이 초기화되지 않았습니다.")',
    '?: throw IllegalStateException(AppStrings.get(R.string.eng_not_init))',
    'eng_not_init', 'The inference engine is not initialized.', '추론 엔진이 초기화되지 않았습니다.', '推理引擎尚未初始化。')

rep('onStageUpdate?.invoke("로드 완료", 1.0f)',
    'onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_loaded), 1.0f)',
    'eng_stage_loaded', 'Load complete', '로드 완료', '加载完成',
)

pairs = [(o, n) for (o, n, k, e, z, c) in E if k is None or True]
pairs = [(o, n) for (o, n, k, e, z, c) in E]
counts = {o: 3 for (o, n, k, e, z, c) in E if o == 'onStageUpdate?.invoke("로드 완료", 1.0f)'}
edit(K, pairs, counts)
res = [(k, e, z, c) for (o, n, k, e, z, c) in E if k]
add_res(res)
print("LlmEngine: %d 处替换, %d 键" % (len(pairs), len(res)))
