package com.localllm.android.engine

/**
 * 模型下载源：Hugging Face 官方 / hf-mirror 镜像。
 * rewrite() 只替换官方域名前缀，CDN 与其它 URL 原样返回。
 */
object HfSource {
    const val OFFICIAL = "https://huggingface.co"
    const val MIRROR = "https://hf-mirror.com"
    const val OFFICIAL_HOST = "huggingface.co"
    const val MIRROR_HOST = "hf-mirror.com"

    @Volatile
    var useMirror: Boolean = false

    fun rewrite(url: String): String =
        if (useMirror) url.replaceFirst(OFFICIAL, MIRROR) else url

    val currentHost: String
        get() = if (useMirror) MIRROR_HOST else OFFICIAL_HOST
}
