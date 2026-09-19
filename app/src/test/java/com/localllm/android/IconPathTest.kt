package com.localllm.android

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves which SVG path spellings the runtime parser accepts. */
class IconPathTest {

    private fun nodes(data: String): Int {
        return try {
            PathParser().parsePathString(data).toNodes().size
        } catch (e: Exception) {
            -1
        }
    }

    @Test
    fun pathSpellings() {
        println("menu=" + nodes("M4 7h16M4 12h16M4 17h16"))
        println("dots=" + nodes("M12 5v.1M12 12v.1M12 19v.1"))
        println("dot2=" + nodes("M12 7.5v.1"))
        println("add=" + nodes("M12 5v14M5 12h14"))
        println("dotfixed=" + nodes("M12 5v0.1M12 12v0.1M12 19v0.1"))
        assertEquals(4, nodes("M12 5v14M5 12h14"))
    }
}
