package com.localllm.android.i18n

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
