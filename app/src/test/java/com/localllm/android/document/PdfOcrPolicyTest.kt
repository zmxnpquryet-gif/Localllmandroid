package com.localllm.android.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOcrPolicyTest {

    @Test
    fun `a scanned pdf triggers ocr`() {
        assertTrue(PdfOcrPolicy.needsOcr("", 3))
        assertTrue(PdfOcrPolicy.needsOcr(" \n\u000C ", 1))
    }

    @Test
    fun `a real text layer skips ocr`() {
        assertFalse(PdfOcrPolicy.needsOcr("A".repeat(500), 3))
    }

    @Test
    fun `the longer extraction wins`() {
        assertEquals("ocr text", PdfOcrPolicy.selectText("", "ocr text"))
        assertEquals("digital", PdfOcrPolicy.selectText("digital", ""))
        assertEquals("digital", PdfOcrPolicy.selectText("digital", "x"))
        assertEquals("longer ocr", PdfOcrPolicy.selectText("abc", "longer ocr"))
    }

    @Test
    fun `ocr script follows the app language then the device locale`() {
        assertEquals(PdfOcrPolicy.Script.KOREAN, PdfOcrPolicy.scriptFor("ko", "en"))
        assertEquals(PdfOcrPolicy.Script.CHINESE, PdfOcrPolicy.scriptFor("zh", "ko"))
        assertEquals(PdfOcrPolicy.Script.LATIN, PdfOcrPolicy.scriptFor("en", "ko"))
        assertEquals(PdfOcrPolicy.Script.KOREAN, PdfOcrPolicy.scriptFor("system", "ko-KR"))
        assertEquals(PdfOcrPolicy.Script.CHINESE, PdfOcrPolicy.scriptFor("system", "zh-Hans"))
        assertEquals(PdfOcrPolicy.Script.LATIN, PdfOcrPolicy.scriptFor("system", "fr"))
    }
}
