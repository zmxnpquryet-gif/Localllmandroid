package com.localllm.android

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the backup configuration itself. This verifies that the shipped XML
 * declares the exclusions (it cannot exercise the OS backup transport, which
 * requires an instrumented device test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

    /** Exclusions grouped by the element they are nested under (e.g. cloud-backup). */
    private fun excludesBySection(resId: Int): Map<String, List<Pair<String, String>>> {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(resId)
        val out = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val stack = ArrayDeque<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    stack.addLast(parser.name)
                    if (parser.name == "exclude") {
                        val section = stack.elementAtOrNull(stack.size - 2) ?: stack.first()
                        out.getOrPut(section) { mutableListOf() }.add(
                            (parser.getAttributeValue(null, "domain") ?: "") to
                                (parser.getAttributeValue(null, "path") ?: ".")
                        )
                    }
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeLast()
            }
            event = parser.next()
        }
        parser.close()
        return out
    }

    private val requiredDomains = setOf("database", "sharedpref", "file")

    @Test
    fun `full backup excludes the encrypted db, prefs and the files domain`() {
        val excludes = excludesBySection(R.xml.backup_rules).values.flatten()
        val domains = excludes.map { it.first }.toSet()
        assertTrue(
            "backup_rules.xml must exclude database/sharedpref/file, found $domains",
            domains.containsAll(requiredDomains)
        )
    }

    @Test
    fun `cloud backup and device transfer both exclude models and tokens`() {
        val sections = excludesBySection(R.xml.data_extraction_rules)
        val cloudDomains = sections["cloud-backup"]?.map { it.first }?.toSet().orEmpty()
        val transferDomains = sections["device-transfer"]?.map { it.first }?.toSet().orEmpty()

        assertTrue(
            "cloud-backup must exclude database/sharedpref/file, found $cloudDomains",
            cloudDomains.containsAll(requiredDomains)
        )
        assertTrue(
            "device-transfer must exclude database/sharedpref/file, found $transferDomains",
            transferDomains.containsAll(requiredDomains)
        )
    }
}
