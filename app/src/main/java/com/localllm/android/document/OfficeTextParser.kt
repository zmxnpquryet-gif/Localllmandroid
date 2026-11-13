package com.localllm.android.document

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts readable text from OOXML packages (`.xlsx`, `.pptx`) without any Office
 * SDK: both formats are zip archives of XML parts. Each part is parsed with a
 * hardened [DocumentBuilder] (DOCTYPE disabled) and entity expansion off, because a
 * downloaded document is untrusted input.
 *
 * Output is plain text with sheet/slide markers, which is what the chunker and the
 * model consume. Formatting, formulas and charts are out of scope; cached cell
 * values and slide text are what is kept.
 */
object OfficeTextParser {

    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 40 * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    fun parseXlsx(bytes: ByteArray): String {
        val entries = readEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
        }
        if (entries.isEmpty()) return ""

        val shared = entries["xl/sharedStrings.xml"]?.let { sharedStrings(it) }.orEmpty()
        val sheets = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") }
            .sortedBy { numberIn(it) }

        val out = StringBuilder()
        sheets.forEachIndexed { index, name ->
            val rows = sheetRows(entries.getValue(name), shared)
            if (rows.isEmpty()) return@forEachIndexed
            out.append("Sheet ").append(index + 1).append('\n')
            rows.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString().trim()
    }

    fun parsePptx(bytes: ByteArray): String {
        val entries = readEntries(bytes) { name ->
            name.matches(Regex("ppt/slides/slide\\d+\\.xml"))
        }
        if (entries.isEmpty()) return ""

        val out = StringBuilder()
        entries.keys.sortedBy { numberIn(it) }.forEachIndexed { index, name ->
            val texts = slideTexts(entries.getValue(name))
            if (texts.isEmpty()) return@forEachIndexed
            out.append("Slide ").append(index + 1).append('\n')
            texts.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString().trim()
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val document = parse(bytes) ?: return emptyList()
        val nodes = document.getElementsByTagName("si")
        return (0 until nodes.length).mapNotNull { i ->
            (nodes.item(i) as? Element)?.textContent?.replace(Regex("\\s+"), " ")?.trim()
        }
    }

    private fun sheetRows(bytes: ByteArray, shared: List<String>): List<String> {
        val document = parse(bytes) ?: return emptyList()
        val rowNodes = document.getElementsByTagName("row")
        val rows = mutableListOf<String>()
        for (rowIndex in 0 until rowNodes.length) {
            val row = rowNodes.item(rowIndex) as? Element ?: continue
            val cellNodes = row.getElementsByTagName("c")
            val values = mutableListOf<String>()
            for (cellIndex in 0 until cellNodes.length) {
                val cell = cellNodes.item(cellIndex) as? Element ?: continue
                val type = cell.getAttribute("t")
                val raw = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                val value = when (type) {
                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    else -> raw
                }.replace(Regex("\\s+"), " ").trim()
                if (value.isNotEmpty()) values += value
            }
            if (values.isNotEmpty()) rows += values.joinToString(" | ")
        }
        return rows
    }

    private fun slideTexts(bytes: ByteArray): List<String> {
        val document = parse(bytes) ?: return emptyList()
        val nodes = document.getElementsByTagName("a:t")
        val fallback = if (nodes.length == 0) document.getElementsByTagName("t") else nodes
        return (0 until fallback.length).mapNotNull { i ->
            fallback.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document? = try {
        newBuilder().parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        // A malformed part must not abort the whole document: other sheets/slides
        // still carry useful text.
        null
    }

    private fun newBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder()
    }

    private fun readEntries(
        bytes: ByteArray,
        predicate: (String) -> Boolean
    ): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_ENTRIES) break
                if (!predicate(entry.name)) continue
                val data = readLimited(zip, MAX_ENTRY_BYTES) ?: continue
                total += data.size
                if (total > MAX_TOTAL_BYTES) break
                entries[entry.name] = data
            }
        }
        return entries
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= max) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > max) return null
        return out.toByteArray()
    }

    private fun numberIn(name: String): Int =
        Regex("(\\d+)").find(name)?.value?.toIntOrNull() ?: 0
}
