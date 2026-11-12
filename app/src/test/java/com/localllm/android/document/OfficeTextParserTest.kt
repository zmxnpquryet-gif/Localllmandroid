package com.localllm.android.document

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeTextParserTest {

    @Test
    fun `xlsx resolves shared strings into readable rows`() {
        val bytes = zip(
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Revenue</t></si>
                  <si><t>42</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent()
        )

        val text = OfficeTextParser.parseXlsx(bytes)
        assertTrue(text, text.contains("Revenue | 42"))
    }

    @Test
    fun `pptx keeps slide order and text`() {
        val bytes = zip(
            "ppt/slides/slide2.xml" to """
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <a:t>Second slide</a:t>
                </p:sld>
            """.trimIndent(),
            "ppt/slides/slide1.xml" to """
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <a:t>First slide</a:t>
                </p:sld>
            """.trimIndent()
        )

        val text = OfficeTextParser.parsePptx(bytes)
        assertTrue(text, text.indexOf("First slide") in 0 until text.indexOf("Second slide"))
    }

    @Test
    fun `malformed xml does not throw`() {
        val bytes = zip("ppt/slides/slide1.xml" to "<a:t>broken")
        assertTrue(OfficeTextParser.parsePptx(bytes).isEmpty())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
