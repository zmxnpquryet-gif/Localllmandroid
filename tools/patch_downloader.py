# -*- coding: utf-8 -*-
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from i18n_lib import edit, add_res

K = 'engine/ModelDownloader.kt'
E = []
def rep(old, new, key=None, en=None, ko=None, zh=None, n=1):
    E.append((old, new, key, en, ko, zh, n))

rep('package com.localllm.android.engine',
    'package com.localllm.android.engine\n\nimport com.localllm.android.R\nimport com.localllm.android.i18n.AppStrings')

# 认证白名单：允许镜像域携带 Bearer
rep('''            val isHfInfra = host == "huggingface.co" ||
                    host.endsWith(".huggingface.co") ||
                    host == "hf.co" ||
                    host.endsWith(".hf.co")''',
    '''            val isHfInfra = host == "huggingface.co" ||
                    host.endsWith(".huggingface.co") ||
                    host == "hf.co" ||
                    host.endsWith(".hf.co") ||
                    host == HfSource.MIRROR_HOST''')

# 镜像改写（下载请求统一出口）
rep('                    .url(initialUrl)', '                    .url(HfSource.rewrite(initialUrl))')
rep('                        .url(resolvedUrl)', '                        .url(HfSource.rewrite(resolvedUrl))')
rep('                                        .url(url)', '                                        .url(HfSource.rewrite(url))', n=1)
rep('                    .url(url)', '                    .url(HfSource.rewrite(url))', n=1)

# 枚举标签
rep('    MAIN_MODEL("메인 가중치 모델", "MAIN"),',
    '    MAIN_MODEL(AppStrings.get(R.string.dl_enum_main), "MAIN"),',
    'dl_enum_main', 'Main weights model', '메인 가중치 모델', '主权重模型')
rep('    VISION_TOWER("비전 타워 (mmproj)", "VISION"),',
    '    VISION_TOWER(AppStrings.get(R.string.dl_enum_vision), "VISION"),',
    'dl_enum_vision', 'Vision tower (mmproj)', '비전 타워 (mmproj)', '视觉塔（mmproj）')
rep('    MTP_DRAFTER("MTP 투기적 드래프터", "MTP"),',
    '    MTP_DRAFTER(AppStrings.get(R.string.dl_enum_mtp), "MTP"),',
    'dl_enum_mtp', 'MTP speculative drafter', 'MTP 투기적 드래프터', 'MTP 推测草稿模型')
rep('    TEMPLATE("LiteRT 프롬프트 템플릿", "TEMPLATE")',
    '    TEMPLATE(AppStrings.get(R.string.dl_enum_template), "TEMPLATE")',
    'dl_enum_template', 'LiteRT prompt template', 'LiteRT 프롬프트 템플릿', 'LiteRT 提示词模板')

rep('                title = "메인 가중치",', '                title = AppStrings.get(R.string.dl_title_main),',
    'dl_title_main', 'Main weights', '메인 가중치', '主权重')
rep('                    title = "비전 타워 (mmproj)",', '                    title = AppStrings.get(R.string.dl_title_vision),',
    'dl_title_vision', 'Vision tower (mmproj)', '비전 타워 (mmproj)', '视觉塔（mmproj）')
rep('                    title = "MTP 2x 투기적 드래프터",', '                    title = AppStrings.get(R.string.dl_title_mtp),',
    'dl_title_mtp', 'MTP 2x speculative drafter', 'MTP 2x 투기적 드래프터', 'MTP 2x 推测草稿模型')
rep('                    title = "프롬프트 템플릿",', '                    title = AppStrings.get(R.string.dl_title_template),',
    'dl_title_template', 'Prompt template', '프롬프트 템플릿', '提示词模板')

rep('speedText = "완료",', 'speedText = AppStrings.get(R.string.dl_done),',
    'dl_done', 'Done', '완료', '完成', n=3)
rep('speedText = "오류",', 'speedText = AppStrings.get(R.string.dl_error),',
    'dl_error', 'Error', '오류', '错误', n=3)

rep('                            activeComponentName = "${task.title} 다운로드 중 (${targetFile.name})",',
    '                            activeComponentName = AppStrings.get(R.string.dl_downloading_comp, task.title, targetFile.name),',
    'dl_downloading_comp', 'Downloading %1$s (%2$s)', '%1$s 다운로드 중 (%2$s)', '正在下载 %1$s（%2$s）')

rep('''                            "다운로드 실패: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 유효한 HF 토큰을 입력해 주세요."''',
    '''                            AppStrings.get(R.string.dl_fail_401)''',
    'dl_fail_401', 'Download failed: Hugging Face authentication required (401). Enter a valid HF token in Settings.',
    '다운로드 실패: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 유효한 HF 토큰을 입력해 주세요.',
    '下载失败：需要 Hugging Face 认证（401）。请在设置中填写有效的 HF 令牌。', n=2)

rep('''                            "다운로드 실패: 모델 파일 대신 HTML 웹페이지가 다운로드되었습니다. 링크 및 권한을 확인하세요."''',
    '''                            AppStrings.get(R.string.dl_fail_html)''',
    'dl_fail_html', 'Download failed: an HTML page was downloaded instead of the model file. Check the link and permissions.',
    '다운로드 실패: 모델 파일 대신 HTML 웹페이지가 다운로드되었습니다. 링크 및 권한을 확인하세요.',
    '下载失败：下载到的是网页而非模型文件，请检查链接与权限。', n=2)

rep('''                            "다운로드 완료 후 파일 검증 실패: 유효한 GGUF 파일 형식이 아닙니다."''',
    '''                            AppStrings.get(R.string.dl_verify_gguf)''',
    'dl_verify_gguf', 'Post-download verification failed: not a valid GGUF file.',
    '다운로드 완료 후 파일 검증 실패: 유효한 GGUF 파일 형식이 아닙니다.', '下载后校验失败：不是有效的 GGUF 文件。')

rep('''                            "다운로드 실패: 파일 크기가 너무 작습니다 (${targetFile.length()} bytes). 다운로드 링크를 확인하세요."''',
    '''                            AppStrings.get(R.string.dl_fail_small, targetFile.length())''',
    'dl_fail_small', 'Download failed: file is too small (%1$s bytes). Check the download link.',
    '다운로드 실패: 파일 크기가 너무 작습니다 (%1$s bytes). 다운로드 링크를 확인하세요.',
    '下载失败：文件过小（%1$s 字节），请检查下载链接。')

rep('''                            "다운로드 완료 후 파일 검증 실패: 유효한 LiteRT 모델 바이너리가 아닙니다."''',
    '''                            AppStrings.get(R.string.dl_verify_litert)''',
    'dl_verify_litert', 'Post-download verification failed: not a valid LiteRT model binary.',
    '다운로드 완료 후 파일 검증 실패: 유효한 LiteRT 모델 바이너리가 아닙니다.', '下载后校验失败：不是有效的 LiteRT 模型文件。')

rep('                activeComponentName = "다운로드 완료",', '                activeComponentName = AppStrings.get(R.string.dl_all_done),',
    'dl_all_done', 'Download complete', '다운로드 완료', '下载完成')

rep('''                    errorReason = "Hugging Face 인증 필요 (${resp.code}). 설정에서 유효한 HF 토큰을 입력해 주세요."''',
    '''                    errorReason = AppStrings.get(R.string.dl_probe_401, resp.code)''',
    'dl_probe_401', 'Hugging Face authentication required (%1$s). Enter a valid HF token in Settings.',
    'Hugging Face 인증 필요 (%1$s). 설정에서 유효한 HF 토큰을 입력해 주세요.', '需要 Hugging Face 认证（%1$s）。请在设置中填写有效的 HF 令牌。', n=2)

rep('''                    errorReason = "모델 파일을 찾을 수 없습니다 (404 Not Found). 다운로드 링크를 확인하세요."''',
    '''                    errorReason = AppStrings.get(R.string.dl_probe_404)''',
    'dl_probe_404', 'Model file not found (404). Check the download link.', '모델 파일을 찾을 수 없습니다 (404 Not Found). 다운로드 링크를 확인하세요.',
    '找不到模型文件（404），请检查下载链接。')

rep('val err = probe.errorMsg ?: "다운로드 인증 실패"', 'val err = probe.errorMsg ?: AppStrings.get(R.string.dl_auth_fail)',
    'dl_auth_fail', 'Download authentication failed', '다운로드 인증 실패', '下载认证失败')

rep('failureReason = "일부 분할 청크 다운로드 불완전"', 'failureReason = AppStrings.get(R.string.dl_chunk_incomplete)',
    'dl_chunk_incomplete', 'Some segmented chunks were not downloaded completely', '일부 분할 청크 다운로드 불완전', '部分分片未完整下载')

rep('failureReason = e.localizedMessage ?: "분할 다운로드 네트워크 실패"',
    'failureReason = e.localizedMessage ?: AppStrings.get(R.string.dl_net_fail)',
    'dl_net_fail', 'Segmented download network error', '분할 다운로드 네트워크 실패', '分片下载网络错误')

rep('onError("HTTP 오류 ${resp.code}: ${resp.message}")', 'onError(AppStrings.get(R.string.dl_http_err, resp.code, resp.message))',
    'dl_http_err', 'HTTP error %1$s: %2$s', 'HTTP 오류 %1$s: %2$s', 'HTTP 错误 %1$s：%2$s')

rep('onError("서버 응답 본문이 비어 있습니다.")', 'onError(AppStrings.get(R.string.dl_empty_body))',
    'dl_empty_body', 'Server response body is empty', '서버 응답 본문이 비어 있습니다.', '服务器响应正文为空。')

rep('val err = "다운로드 실패: ${e.localizedMessage ?: e.message}"',
    'val err = AppStrings.get(R.string.dl_fail_generic, e.localizedMessage ?: e.message)',
    'dl_fail_generic', 'Download failed: %1$s', '다운로드 실패: %1$s', '下载失败：%1$s')

pairs = [(o, n) for (o, n, k, e, z, c, cnt) in E]
counts = {o: cnt for (o, n, k, e, z, c, cnt) in E if cnt != 1}
# 清除 n=0 保护项（old==new 不应存在）
pairs = [(o, n) for (o, n) in pairs if o != n]
counts = {o: c for o, c in counts.items() if o in dict(pairs)}
edit(K, pairs, counts)
res = [(k, e, z, c) for (o, n, k, e, z, c, cnt) in E if k]
add_res(res)
print("ModelDownloader: %d 处替换, %d 键" % (len(pairs), len(res)))
