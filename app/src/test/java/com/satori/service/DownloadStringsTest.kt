package com.satori.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The download notification's words: the same keys in English and German, German without du or
 * Sie, and one name for one thing (design R5): what the first run and the status strip call a
 * part, and how they say a download is held, the notification says too.
 */
class DownloadStringsTest {

    private val download = "strings_grille_download.xml"

    @Test
    fun `English and German have the same strings`() {
        assertEquals(names("values", download), names("values-de", download))
    }

    @Test
    fun `German says it without du or Sie`() {
        for (name in names("values-de", download)) {
            val words = text("values-de", download, name).split(Regex("[^\\p{L}]+"))
            assertFalse(name, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        }
    }

    @Test
    fun `it names the parts and the holds in the first run's words`() {
        val same = mapOf(
            "g_dl_speech" to ("strings_grille_firstrun.xml" to "g_fr_part_speech"),
            "g_dl_malayalam" to ("strings_grille_firstrun.xml" to "g_fr_part_malayalam"),
            "g_dl_checking" to ("strings_grille_firstrun.xml" to "g_fr_checking"),
            "g_dl_no_connection" to ("strings_grille_firstrun.xml" to "g_fr_err_conn_title"),
            "g_dl_waiting_wifi" to ("strings_grille_firstrun.xml" to "g_fr_err_wifi_title"),
            "g_dl_space" to ("strings_grille_firstrun.xml" to "g_fr_space_title"),
            "g_dl_ai_model" to ("strings_grille_strip.xml" to "g_strip_ai_model"),
            "g_dl_bigger_model" to ("strings_grille_strip.xml" to "g_strip_bigger_model"),
            "g_dl_paused_recording" to ("strings_grille_strip.xml" to "g_strip_paused_recording")
        )
        for (folder in listOf("values", "values-de")) {
            for ((name, other) in same) {
                assertEquals("$folder $name", text(folder, other.first, other.second), text(folder, download, name))
            }
        }
    }

    private fun strings(folder: String, file: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun names(folder: String, file: String): Set<String> = strings(folder, file).map { it.getAttribute("name") }.toSet()

    /** The text of the string [name] in res/[folder]/[file], with its escapes read as aapt2 reads them. Gradle runs the tests in the module's folder. */
    private fun text(folder: String, file: String, name: String): String =
        strings(folder, file).single { it.getAttribute("name") == name }.textContent.replace("\\'", "'")
}
