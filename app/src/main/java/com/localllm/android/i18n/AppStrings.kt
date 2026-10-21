package com.localllm.android.i18n

import android.content.res.Resources

/**
 * 全局字符串提供器：让无 Context 的引擎/数据层代码也能取到按当前语言（跟随系统/英/韩/中）
 * 解析后的资源串。由 MainActivity 在本地化 Context 建立后 attach。
 */
object AppStrings {
    @Volatile
    private var res: Resources? = null

    fun attach(resources: Resources) {
        res = resources
    }

    operator fun get(id: Int): String = res?.getString(id) ?: ""

    fun get(id: Int, vararg args: Any?): String = res?.getString(id, *args) ?: ""
}
