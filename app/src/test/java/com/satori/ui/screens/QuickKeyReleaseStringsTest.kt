package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The power key's setup from a computer leaves release builds (the owner's decision of 2026-10-15;
 * design A6): no string a release build holds, its main and release resources, says "adb" or
 * "computer", in English or German, and the sheet's sentence says what the key does without a
 * setup. The debug build keeps the setup's strings, in both languages.
 */
class QuickKeyReleaseStringsTest {

    private val setupKeys = setOf("g_quickkey_setup_open", "g_quickkey_setup_title", "g_quickkey_setup_desc", "g_quickkey_copy", "g_quickkey_share")

    @Test
    fun `no string of a release build mentions adb or a computer`() {
        val release = listOf("main", "release").flatMap { set -> listOf("values", "values-de").flatMap { strings(set, it).entries } }
        assertTrue(release.size > 500)
        for ((name, text) in release) {
            assertFalse("$name: \"$text\"", MENTIONS_SETUP.containsMatchIn(text))
        }
        assertTrue(release.none { it.key in setupKeys })
    }

    @Test
    fun `no code of a release build makes the adb commands, so its dex holds none of them`() {
        // The app's main and release sources and every module a release build bundles.
        val roots = listOf("src/main", "src/release", "../domain/src/main", "../data/src/main", "../presentation/src/main")
        val sources = roots.flatMap { root -> File(root).walk().filter { it.extension == "kt" }.toList() }
        assertTrue(sources.size > 300)
        for (source in sources) {
            val code = source.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            assertFalse(source.path, code.any { "QuickKeyAdbSetup" in it || "settings put" in it })
        }
        // The debug build makes them, for the main session's setup.
        assertTrue(File("src/debug/java/com/satori/ui/screens/QuickKeyAdbSetup.kt").readText().contains("adb shell settings put"))
    }

    @Test
    fun `the debug build keeps the setup's strings, in both languages`() {
        for (folder in listOf("values", "values-de")) {
            val debug = strings("debug", folder)
            assertEquals(folder, setupKeys, debug.keys)
            assertTrue(folder, MENTIONS_SETUP.containsMatchIn(debug.getValue("g_quickkey_setup_desc")))
        }
    }

    @Test
    fun `the sheet's sentence says what the key does, and nothing of a setup`() {
        assertEquals("Press the power key twice to start a recording.", strings("main", "values").getValue("g_quickkey_explain"))
        assertEquals("Zweimal die Ein-/Aus-Taste drücken, um eine Aufnahme zu starten.", strings("main", "values-de").getValue("g_quickkey_explain"))
        for (folder in listOf("values", "values-de")) {
            val explain = strings("main", folder).getValue("g_quickkey_explain")
            assertFalse(explain, Regex("(?i)set ?up|einricht").containsMatchIn(explain))
        }
    }

    @Test
    fun `German says it without du or Sie`() {
        val addressed = setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre")
        val german = strings("main", "values-de").filterKeys { it.startsWith("g_quickkey_") } + strings("debug", "values-de")
        for ((name, text) in german) {
            assertTrue("$name: \"$text\"", text.split(Regex("[^\\p{L}]+")).none { it in addressed })
        }
    }

    /**
     * Every string, plural and array item of src/[set]/res/[folder] by name (an item as its name and
     * index), with its escapes read as aapt2 reads them; none for a folder the set doesn't have.
     * Gradle runs the tests in the module's folder.
     */
    private fun strings(set: String, folder: String): Map<String, String> =
        File("src/$set/res/$folder").listFiles { file -> file.name.startsWith("strings") }.orEmpty().flatMap { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
            }
            val items = listOf("plurals", "string-array").flatMap { tag ->
                document.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            }.flatMap { parent ->
                val children = parent.getElementsByTagName("item")
                (0 until children.length).map { "${parent.getAttribute("name")}[$it]" to children.item(it).textContent }
            }
            strings + items
        }.associate { (name, text) -> name to text.replace("\\'", "'").replace("\\\"", "\"") }

    private companion object {
        /** "adb", "computer" or "Computer" as a word of its own, also inside "adb-Befehlen". */
        val MENTIONS_SETUP = Regex("(?i)(?<![\\p{L}])(adb|computer)(?![\\p{L}])")
    }
}
