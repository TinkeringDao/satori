package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The words the recorder and the finishing say during the first run (design 3.7 to 3.9 and its
 * copy table, 7.3): the AI model on its way, a recording that waits for it, the notes ready, and the
 * first recording's coach line, in English and German, within the design's limits for its copy:
 * short sentences, no jargon, no blame, German without du or Sie. The dialog that closed the
 * recorder on a refused microphone is gone with its words.
 */
class RecorderFirstRunStringsTest {

    private val recorder = "strings_grille_recorder.xml"
    private val notifications = "strings_grille_notifications.xml"
    private val finishing = "strings_grille_finishing.xml"

    private val ours = listOf(
        recorder to "g_rec_notes_downloading",
        recorder to "g_rec_ai_downloading",
        finishing to "g_finish_waits_model",
        notifications to "g_notif_notes_ready",
        notifications to "g_notif_channel_notes_ready",
        notifications to "g_notif_channel_notes_ready_desc"
    )

    @Test
    fun `the AI model on its way, and a recording that waits for it, in both languages`() {
        assertEquals("The AI model is still downloading. Notes follow when it is in.", text("values", recorder, "g_rec_notes_downloading"))
        assertEquals(
            "Das KI-Modell wird noch heruntergeladen. Die Notizen folgen, sobald es da ist.",
            text("values-de", recorder, "g_rec_notes_downloading")
        )
        assertEquals("AI model: downloading", text("values", recorder, "g_rec_ai_downloading"))
        assertEquals("KI-Modell: Download läuft", text("values-de", recorder, "g_rec_ai_downloading"))
        assertEquals("Waits for the AI model", text("values", finishing, "g_finish_waits_model"))
        assertEquals("Wartet auf das KI-Modell", text("values-de", finishing, "g_finish_waits_model"))
    }

    @Test
    fun `the notes ready, in both languages, naming no recording`() {
        assertEquals("Your notes are ready", text("values", notifications, "g_notif_notes_ready"))
        assertEquals("Die Notizen sind fertig", text("values-de", notifications, "g_notif_notes_ready"))
        assertEquals("Notes ready", text("values", notifications, "g_notif_channel_notes_ready"))
        assertEquals("Notizen fertig", text("values-de", notifications, "g_notif_channel_notes_ready"))
        for (folder in listOf("values", "values-de")) {
            assertFalse(folder, text(folder, notifications, "g_notif_notes_ready").contains("%"))
        }
    }

    @Test
    fun `the coach line is the first run's, in both languages`() {
        assertEquals("Talk for a minute. Notes follow when you stop.", text("values", "strings_grille_firstrun.xml", "g_fr_coach"))
        assertEquals("Eine Minute reden. Die Notizen folgen nach dem Stoppen.", text("values-de", "strings_grille_firstrun.xml", "g_fr_coach"))
    }

    @Test
    fun `sentences stay short, and say nothing of the model's insides or of blame`() {
        val jargon = setOf("llm", "gguf", "gemma", "token", "inference", "weights", "checksum", "parakeet", "stt")
        val blame = setOf("forgot", "missed", "vergessen", "versäumt", "verpasst")
        for ((file, name) in ours) {
            for ((folder, most) in listOf("values" to 12, "values-de" to 14)) {
                val string = text(folder, file, name)
                for (sentence in string.split(Regex("[.!?]")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    assertTrue("$folder $name: $sentence", sentence.split(Regex("\\s+")).size <= most)
                }
                val words = string.lowercase().split(Regex("[^\\p{L}]+")).toSet()
                assertTrue("$folder $name", words.none { it in jargon || it in blame })
            }
        }
    }

    @Test
    fun `German says it without du or Sie, and every string is in both languages`() {
        for ((file, name) in ours) {
            val words = text("values-de", file, name).split(Regex("[^\\p{L}]+"))
            assertFalse(name, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
            assertTrue(name, text("values", file, name).isNotBlank())
        }
    }

    @Test
    fun `the dialog that closed the recorder on a refused microphone is gone, with its words`() {
        for (folder in listOf("values", "values-de")) {
            val names = names(folder, recorder)
            assertFalse(folder, "g_mic_title" in names)
            assertFalse(folder, "g_mic_body" in names)
        }
    }

    /** The names of the strings in the app's res/[folder]/[file]. Gradle runs the tests in the module's folder. */
    private fun names(folder: String, file: String): Set<String> {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until strings.length).map { (strings.item(it) as Element).getAttribute("name") }.toSet()
    }

    /** The text of the string [name] in the app's res/[folder]/[file], with its escapes read as aapt2 reads them. */
    private fun text(folder: String, file: String, name: String): String {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent.replace("\\'", "'")
    }
}
