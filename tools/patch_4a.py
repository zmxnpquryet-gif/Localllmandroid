# -*- coding: utf-8 -*-
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from i18n_lib import edit, add_res

E = []
def rep(file, old, new, key=None, en=None, ko=None, zh=None, n=1):
    E.append((file, old, new, key, en, ko, zh, n))

IMP_OLD = 'package com.localllm.android.%s'
IMP_NEW = 'package com.localllm.android.%s\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings'

# ============ ModelDownloadService ============
F = 'service/ModelDownloadService.kt'
rep(F, IMP_OLD % 'service', IMP_NEW % 'service')
rep(F, '            title = if (model != null) "다운로드 시작: ${model.name}" else "다운로드 서비스",',
    '            title = if (model != null) AppStrings.get(R.string.svc_start_title, model.name) else AppStrings.get(R.string.svc_dl_service),',
    'svc_start_title', 'Download started: %1$s', '다운로드 시작: %1$s', '开始下载：%1$s')
rep(F, 'svc_dl_service_X', None) if False else None
E.append((F, 'DUMMY', 'DUMMY', None, None, None, None, -1))
E.pop()
rep(F, 'svc_dl_service_KEY', None) if False else None
# (占位条目清理)
rep(F, '            content = if (model != null) "${model.name} 다운로드를 준비하고 있습니다..." else "서비스 준비 중...",',
    '            content = if (model != null) AppStrings.get(R.string.svc_start_content, model.name) else AppStrings.get(R.string.svc_ready),',
    'svc_start_content', 'Preparing to download %1$s...', '%1$s 다운로드를 준비하고 있습니다...', '正在准备下载 %1$s…')
rep(F, 'speedText = "오류",', 'speedText = AppStrings.get(R.string.dl_error),')
rep(F, 'errorMessage = e.localizedMessage ?: "다운로드 중 오류 발생",',
    'errorMessage = e.localizedMessage ?: AppStrings.get(R.string.svc_dl_error),',
    'svc_dl_error', 'Error occurred during download', '다운로드 중 오류 발생', '下载过程中出错')
rep(F, '                title = "다운로드 완료",', '                title = AppStrings.get(R.string.svc_done_title),',
    'svc_done_title', 'Download complete', '다운로드 완료', '下载完成')
rep(F, '                content = "${model.name} 파일 다운로드가 완료되었습니다.",',
    '                content = AppStrings.get(R.string.svc_done_content, model.name),',
    'svc_done_content', 'The %1$s file has finished downloading.', '%1$s 파일 다운로드가 완료되었습니다.', '%1$s 文件下载完成。')
rep(F, '                title = "다운로드 오류",', '                title = AppStrings.get(R.string.svc_err_title),',
    'svc_err_title', 'Download error', '다운로드 오류', '下载错误')
rep(F, '            val etaText = if (status.etaSeconds > 0) " • 남은시간: ${status.etaSeconds}초" else ""',
    '            val etaText = if (status.etaSeconds > 0) " " + AppStrings.get(R.string.svc_eta, status.etaSeconds) else ""',
    'svc_eta', '• About %1$ss left', '• 남은시간: 약 %1$s초', '• 剩余约 %1$s 秒')
rep(F, '                title = "모델 다운로드 중: ${model.name}",',
    '                title = AppStrings.get(R.string.svc_dl_title, model.name),',
    'svc_dl_title', 'Downloading model: %1$s', '모델 다운로드 중: %1$s', '正在下载模型：%1$s')
rep(F, '                "취소",', '                AppStrings.get(R.string.svc_cancel),',
    'svc_cancel', 'Cancel', '취소', '取消')
rep(F, '                "모델 백그라운드 다운로드",', '                AppStrings.get(R.string.svc_channel),',
    'svc_channel', 'Model background download', '모델 백그라운드 다운로드', '模型后台下载')
rep(F, '                description = "AI 모델 가중치 파일 다운로드 진행 상황 및 알림"',
    '                description = AppStrings.get(R.string.svc_channel_desc)',
    'svc_channel_desc', 'AI model weight download progress and notifications', 'AI 모델 가중치 파일 다운로드 진행 상황 및 알림',
    'AI 模型权重下载进度与通知')
# svc_dl_service / svc_ready 两条补录
rep(F, 'REPLACED_TITLE_ANCHOR', None) if False else None
E.append((F, 'REPL', 'REP', None, None, None, None, -99)); E.pop()

# ============ McpClient ============
F = 'engine/McpClient.kt'
rep(F, IMP_OLD % 'engine', IMP_NEW % 'engine')
rep(F, '"MCP URL을 입력해 주세요."', 'AppStrings.get(R.string.mcp_enter_url)',
    'mcp_enter_url', 'Enter an MCP URL.', 'MCP URL을 입력해 주세요.', '请输入 MCP URL。')
rep(F, '"http(s) 형식의 MCP URL이 아닙니다."', 'AppStrings.get(R.string.mcp_bad_url)',
    'mcp_bad_url', 'Not an http(s) MCP URL.', 'http(s) 형식의 MCP URL이 아닙니다.', '不是 http(s) 格式的 MCP URL。')
rep(F, '"연결 성공: ${tools.size}개 도구"', 'AppStrings.get(R.string.mcp_connected, tools.size)',
    'mcp_connected', 'Connected: %1$s tools', '연결 성공: %1$s개 도구', '连接成功：%1$s 个工具')
rep(F, '"도구 목록 조회 실패: ${e.message}"', 'AppStrings.get(R.string.mcp_tools_fail, e.message)',
    'mcp_tools_fail', 'Failed to list tools: %1$s', '도구 목록 조회 실패: %1$s', '获取工具列表失败：%1$s', n=2)
rep(F, '"연결 성공(SSE): ${tools.size}개 도구"', 'AppStrings.get(R.string.mcp_connected_sse, tools.size)',
    'mcp_connected_sse', 'Connected (SSE): %1$s tools', '연결 성공(SSE): %1$s개 도구', '连接成功（SSE）：%1$s 个工具')
rep(F, 'else "연결 실패"', 'else AppStrings.get(R.string.mcp_conn_fail)',
    'mcp_conn_fail', 'Connection failed', '연결 실패', '连接失败')
rep(F, '''            "MCP 핸드셰이크 실패 ($hint). Streamable HTTP 또는 SSE 방식의 MCP 서버 URL인지 확인하세요."''',
    '''            AppStrings.get(R.string.mcp_handshake, hint)''',
    'mcp_handshake', 'MCP handshake failed (%1$s). Make sure the URL is a Streamable HTTP or SSE MCP server.',
    'MCP 핸드셰이크 실패 (%1$s). Streamable HTTP 또는 SSE 방식의 MCP 서버 URL인지 확인하세요.',
    'MCP 握手失败（%1$s）。请确认该 URL 是 Streamable HTTP 或 SSE 方式的 MCP 服务器。')
rep(F, '"MCP 서버에 연결되어 있지 않습니다. 먼저 연결하세요."', 'AppStrings.get(R.string.mcp_not_connected)',
    'mcp_not_connected', 'Not connected to an MCP server. Connect first.', 'MCP 서버에 연결되어 있지 않습니다. 먼저 연결하세요.',
    '尚未连接 MCP 服务器，请先连接。')
rep(F, '"도구 호출 요청 실패 (네트워크 오류)."', 'AppStrings.get(R.string.mcp_call_net)',
    'mcp_call_net', 'Tool call request failed (network error).', '도구 호출 요청 실패 (네트워크 오류).', '工具调用请求失败（网络错误）。')
rep(F, '"도구 호출 실패 (HTTP ${resp.code})."', 'AppStrings.get(R.string.mcp_call_http, resp.code)',
    'mcp_call_http', 'Tool call failed (HTTP %1$s).', '도구 호출 실패 (HTTP %1$s).', '工具调用失败（HTTP %1$s）。')
rep(F, '"도구 호출 응답을 해석하지 못했습니다."', 'AppStrings.get(R.string.mcp_call_parse)',
    'mcp_call_parse', 'Could not parse the tool call response.', '도구 호출 응답을 해석하지 못했습니다.', '无法解析工具调用响应。')
rep(F, '?: "알 수 없는 오류"', '?: AppStrings.get(R.string.eng_unknown_err)', n=2)
rep(F, '"도구 오류: $msg"', 'AppStrings.get(R.string.mcp_tool_error, msg)',
    'mcp_tool_error', 'Tool error: %1$s', '도구 오류: %1$s', '工具错误：%1$s')
rep(F, 'if (texts.isEmpty()) "도구가 빈 결과를 반환했습니다." else "도구 실행 성공"',
    'if (texts.isEmpty()) AppStrings.get(R.string.mcp_empty) else AppStrings.get(R.string.mcp_exec_ok)',
    'mcp_empty', 'The tool returned an empty result.', '도구가 빈 결과를 반환했습니다.', '工具返回了空结果。')
rep(F, 'mcp_ok', None) if False else None
E.append((F, 'QQ', 'QQ', None, None, None, None, -1)); E.pop()
rep(F, 'val sb = StringBuilder("MCP 서버 \\"").append(server).append("\\" 제공 도구 (실시간 조회됨):")',
    'val sb = StringBuilder(AppStrings.get(R.string.mcp_sb_head)).append(server).append(AppStrings.get(R.string.mcp_sb_tail))',
    'mcp_sb_head', 'MCP server "', 'MCP 서버 "', 'MCP 服务器 "')
rep(F, 'val sb = StringBuilder(AppStrings.get(R.string.mcp_sb_head)).append(server).append(AppStrings.get(R.string.mcp_sb_tail))',
    'val sb = StringBuilder(AppStrings.get(R.string.mcp_sb_head)).append(server).append(AppStrings.get(R.string.mcp_sb_tail))',
    'mcp_sb_tail', '" provided tools (live):', '" 제공 도구 (실시간 조회됨):', '" 提供的工具（实时查询）：')
rep(F, 't.parameters.take(MAX_TOOL_SCHEMA_CHARS) + "…(생략)"',
    't.parameters.take(MAX_TOOL_SCHEMA_CHARS) + AppStrings.get(R.string.mcp_schema_omitted)',
    'mcp_schema_omitted', '…(omitted)', '…(생략)', '…（已省略）')
rep(F, 'val line = "\\n- ${t.name}: ${t.description} 입력: $schema"',
    'val line = "\\n" + AppStrings.get(R.string.mcp_tool_line, t.name, t.description, schema)',
    'mcp_tool_line', '- %1$s: %2$s input: %3$s', '- %1$s: %2$s 입력: %3$s', '- %1$s：%2$s 输入：%3$s')
rep(F, 'sb.append("\\n…(나머지 도구 생략)")', 'sb.append("\\n" + AppStrings.get(R.string.mcp_more_omitted))',
    'mcp_more_omitted', '…(more tools omitted)', '…(나머지 도구 생략)', '…（其余工具已省略）')
rep(F, 'throw IllegalStateException("도구 목록 요청 실패 (네트워크 오류).")',
    'throw IllegalStateException(AppStrings.get(R.string.mcp_list_net))',
    'mcp_list_net', 'Tool list request failed (network error).', '도구 목록 요청 실패 (네트워크 오류).', '工具列表请求失败（网络错误）。')
rep(F, 'throw IllegalStateException("도구 목록 조회 실패 (HTTP ${first.code}).")',
    'throw IllegalStateException(AppStrings.get(R.string.mcp_list_http, first.code))',
    'mcp_list_http', 'Failed to list tools (HTTP %1$s).', '도구 목록 조회 실패 (HTTP %1$s).', '获取工具列表失败（HTTP %1$s）。')
rep(F, 'throw IllegalStateException("도구 목록 응답을 해석하지 못했습니다.")',
    'throw IllegalStateException(AppStrings.get(R.string.mcp_list_parse))',
    'mcp_list_parse', 'Could not parse the tool list response.', '도구 목록 응답을 해석하지 못했습니다.', '无法解析工具列表响应。')
rep(F, 'throw IllegalStateException("서버 오류: $msg")',
    'throw IllegalStateException(AppStrings.get(R.string.mcp_server_error, msg))',
    'mcp_server_error', 'Server error: %1$s', '서버 오류: %1$s', '服务器错误：%1$s')

# ============ VoiceManager ============
F = 'voice/VoiceManager.kt'
rep(F, IMP_OLD % 'voice', IMP_NEW % 'voice')
rep(F, 'onError("이 기기에서 로컬 녹음을 시작할 수 없습니다.")', 'onError(AppStrings.get(R.string.vmgr_rec_start_fail))',
    'vmgr_rec_start_fail', 'Cannot start local recording on this device.', '이 기기에서 로컬 녹음을 시작할 수 없습니다.', '本设备无法开始本地录音。')
rep(F, 'onError("음성 입력을 위해 마이크 권한이 필요합니다.")', 'onError(AppStrings.get(R.string.vmgr_mic_permission))',
    'vmgr_mic_permission', 'Microphone permission is required for voice input.', '음성 입력을 위해 마이크 권한이 필요합니다.', '语音输入需要麦克风权限。')
rep(F, 'onError("마이크 초기화 실패: ${e.localizedMessage}")', 'onError(AppStrings.get(R.string.vmgr_mic_init_fail, e.localizedMessage))',
    'vmgr_mic_init_fail', 'Microphone initialization failed: %1$s', '마이크 초기화 실패: %1$s', '麦克风初始化失败：%1$s')
rep(F, 'onError("마이크 초기화 실패 (상태 오류).")', 'onError(AppStrings.get(R.string.vmgr_mic_state))',
    'vmgr_mic_state', 'Microphone initialization failed (state error).', '마이크 초기화 실패 (상태 오류).', '麦克风初始化失败（状态错误）。')
rep(F, 'throw IllegalStateException("녹음된 음성이 너무 짧습니다.")', 'throw IllegalStateException(AppStrings.get(R.string.vmgr_too_short))',
    'vmgr_too_short', 'The recorded audio is too short.', '녹음된 음성이 너무 짧습니다.', '录制的语音太短。')
rep(F, '"음성을 인식하지 못했습니다."', 'AppStrings.get(R.string.vmgr_no_speech)', n=2,
    key='vmgr_no_speech', en='Speech not recognized.', ko='음성을 인식하지 못했습니다.', zh='未能识别语音。')
rep(F, 'e.localizedMessage ?: "로컬 음성 인식 실패"', 'e.localizedMessage ?: AppStrings.get(R.string.vmgr_stt_fail)',
    'vmgr_stt_fail', 'Local speech recognition failed', '로컬 음성 인식 실패', '本地语音识别失败')
rep(F, 'onError("음성 인식을 지원하지 않는 기기입니다.")', 'onError(AppStrings.get(R.string.vmgr_no_stt_support))',
    'vmgr_no_stt_support', 'This device does not support speech recognition.', '음성 인식을 지원하지 않는 기기입니다.', '本设备不支持语音识别。')
rep(F, 'SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간이 초과되었습니다."',
    'SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AppStrings.get(R.string.vmgr_timeout)',
    'vmgr_timeout', 'Speech input timed out.', '음성 입력 시간이 초과되었습니다.', '语音输入超时。')
rep(F, 'SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류가 발생했습니다."',
    'SpeechRecognizer.ERROR_AUDIO -> AppStrings.get(R.string.vmgr_audio_err)',
    'vmgr_audio_err', 'An audio recording error occurred.', '오디오 녹음 오류가 발생했습니다.', '发生音频录制错误。')
rep(F, 'else -> "음성 인식 오류 ($error)"', 'else -> AppStrings.get(R.string.vmgr_stt_err, error)',
    'vmgr_stt_err', 'Speech recognition error (%1$s)', '음성 인식 오류 (%1$s)', '语音识别错误（%1$s）')
rep(F, '.replace(Regex("`{1,3}[^`]*`{1,3}"), "코드 블록")',
    '.replace(Regex("`{1,3}[^`]*`{1,3}"), AppStrings.get(R.string.vmgr_code_block))',
    'vmgr_code_block', 'code block', '코드 블록', '代码块')

# ============ LocalSttEngine / LocalTtsEngine ============
F = 'voice/LocalSttEngine.kt'
rep(F, IMP_OLD % 'voice', IMP_NEW % 'voice')
rep(F, 'ModelState.Failed(e.localizedMessage ?: "다운로드 실패")',
    'ModelState.Failed(e.localizedMessage ?: AppStrings.get(R.string.stt_dl_fail))',
    'stt_dl_fail', 'Download failed', '다운로드 실패', '下载失败')
F = 'voice/LocalTtsEngine.kt'
rep(F, IMP_OLD % 'voice', IMP_NEW % 'voice')
rep(F, 'ModelState.Failed(e.localizedMessage ?: "다운로드 실패")',
    'ModelState.Failed(e.localizedMessage ?: AppStrings.get(R.string.stt_dl_fail))')

# 修正 rep() 把 key=en... 放错位置的条目（rep 签名是 file,old,new,key,en,ko,zh）——上面带 n=2 的 vmgr_no_speech 用了关键字正确

# svc 补两条
E_extra = []
# 处理 E 中 file 分组
from collections import defaultdict
groups = defaultdict(list)
for (f, old, new, k, e, z, c, n) in E:
    if old == new:
        continue
    groups[f].append((old, new, k, e, z, c, n))

res_all = []
for f, items in groups.items():
    pairs = [(o, nw) for (o, nw, k, e, z, c, n) in items]
    counts = {o: n for (o, nw, k, e, z, c, n) in items if n != 1}
    edit(f, pairs, counts)
    for (o, nw, k, e, z, c, n) in items:
        if k:
            res_all.append((k, e, z, c))

# 补：svc_dl_service 与 svc_ready（对应替换行的 else 分支资源）
res_all += [
    ('svc_dl_service', 'Download service', '다운로드 서비스', '下载服务'),
    ('svc_ready', 'Preparing service…', '서비스 준비 중…', '服务准备中…'),
    ('mcp_exec_ok', 'Tool executed successfully', '도구 실행 성공', '工具执行成功'),
]
add_res(res_all)
print("4a: %d 键 + 3 补录" % len(res_all))
