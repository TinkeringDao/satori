package com.satori.ui.screens

import com.satori.domain.model.RecordingLanguage
import com.satori.ui.components.languageChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The words of the language a recording is made in: the language row of "Record as" and "Change
 * type", its link to the Malayalam model's download, the recorder's status line, notices and notes
 * line, and the line under home's chip, each in English and German, German in the neutral
 * infinitive style without du or Sie; Malayalam named so in both, with its own name in its script;
 * and which choices the row offers.
 */
class RecordingLanguageStringsTest {

    private val language = "strings_grille_language.xml"
    private val recorder = "strings_grille_recorder.xml"
    private val home = "strings_grille_home.xml"
    private val details = "strings_grille_details.xml"
    private val quickKey = "strings_grille_quickkey.xml"

    @Test
    fun `the row names the languages, Malayalam the same in both and with its own name`() {
        assertEquals("Language", text("values", language, "g_language_title"))
        assertEquals("Sprache", text("values-de", language, "g_language_title"))
        assertEquals("English or German", text("values", language, "g_language_en_de"))
        assertEquals("Englisch oder Deutsch", text("values-de", language, "g_language_en_de"))
        assertEquals("Malayalam", text("values", language, "g_language_ml"))
        assertEquals("Malayalam", text("values-de", language, "g_language_ml"))
        assertEquals("മലയാളം", text("values", language, "g_language_ml_own"))
        assertEquals("%1\$s · %2\$s", text("values", language, "g_language_pair"))
    }

    @Test
    fun `without the Malayalam model the row says where to get it, in both languages`() {
        // It opens Advanced settings, also from Settings, where "in Settings" said nothing.
        assertEquals("Download Malayalam in Advanced settings", text("values", language, "g_language_get_ml"))
        assertEquals("Malayalam unter „Erweitert“ herunterladen", text("values-de", language, "g_language_get_ml"))
    }

    @Test
    fun `the status line and home's chip say a recording is in Malayalam, as the chip names the category only`() {
        assertEquals("Recording in Malayalam", text("values", recorder, "g_rec_live_ml"))
        assertEquals("Aufnahme auf Malayalam läuft", text("values-de", recorder, "g_rec_live_ml"))
        assertEquals("In Malayalam", text("values", home, "g_record_as_in_ml"))
        assertEquals("Auf Malayalam", text("values-de", home, "g_record_as_in_ml"))
    }

    @Test
    fun `the recorder says what it records in, that notes come after Stop, and what went wrong`() {
        assertEquals("Recording in Malayalam. Notes come after Stop.", text("values", recorder, "g_rec_recording_in_ml"))
        assertEquals("Aufnahme auf Malayalam. Notizen folgen nach dem Stoppen.", text("values-de", recorder, "g_rec_recording_in_ml"))
        assertEquals("Recording in English or German", text("values", recorder, "g_rec_recording_in_en_de"))
        assertEquals("Aufnahme auf Englisch oder Deutsch", text("values-de", recorder, "g_rec_recording_in_en_de"))
        // When the notes come, not in which language (the owner, 2026-10-15).
        assertEquals(
            "This recording is in Malayalam. Its notes are written when you stop.",
            text("values", recorder, "g_rec_notes_after_ml")
        )
        assertEquals(
            "Diese Aufnahme ist auf Malayalam. Die Notizen entstehen nach dem Stoppen.",
            text("values-de", recorder, "g_rec_notes_after_ml")
        )
        for (folder in listOf("values", "values-de")) {
            val refused = text(folder, recorder, "g_rec_language_refused")
            assertTrue("$folder: $refused", "%1\$s" in refused && "%2\$s" in refused)
            assertEquals(folder, 2, Regex("%[0-9]+\\$[a-z]").findAll(refused).count())
        }
        assertEquals(
            "The speech model for %1\$s didn't load. The recording goes on in %2\$s.",
            text("values", recorder, "g_rec_language_refused")
        )
    }

    @Test
    fun `home's Translate row says why it is off for Malayalam, and the page what the language changes`() {
        assertEquals("English or German only", text("values", home, "g_record_as_translate_en_de"))
        assertEquals("Nur Englisch oder Deutsch", text("values-de", home, "g_record_as_translate_en_de"))
        assertEquals(
            "The transcript stays as it is. Transcribe again makes it anew in the language chosen.",
            text("values", details, "g_details_language_body")
        )
        assertEquals(
            "Das Transkript bleibt, wie es ist. „Neu transkribieren“ erstellt es in der gewählten Sprache neu.",
            text("values-de", details, "g_details_language_body")
        )
        assertEquals("The language wasn't changed. Try again.", text("values", details, "g_details_error_change_language"))
        assertEquals("Die Sprache wurde nicht geändert. Erneut versuchen.", text("values-de", details, "g_details_error_change_language"))
    }

    @Test
    fun `the power key's row says the language it records in, in both languages`() {
        // Set up, the row says what the key does, with no word about the setup (SETUP-ADB).
        assertEquals("Two presses start a recording as %1\$s, in %2\$s", text("values", quickKey, "g_quickkey_record_as_in"))
        assertEquals("Zweimal drücken startet eine Aufnahme als %1\$s auf %2\$s", text("values-de", quickKey, "g_quickkey_record_as_in"))
        // While nothing tells whether it is set up, what it records as.
        assertEquals("Record as %1\$s, in %2\$s", text("values", quickKey, "g_quickkey_row_unknown_in"))
        assertEquals("Als %1\$s auf %2\$s aufnehmen", text("values-de", quickKey, "g_quickkey_row_unknown_in"))
    }

    @Test
    fun `German says it without du or Sie`() {
        val strings = listOf("g_language_title", "g_language_en_de", "g_language_get_ml").map { language to it } +
            listOf("g_rec_live_ml", "g_rec_recording_in_ml", "g_rec_recording_in_en_de", "g_rec_language_refused", "g_rec_notes_after_ml").map { recorder to it } +
            listOf(home to "g_record_as_translate_en_de", home to "g_record_as_in_ml", details to "g_details_language_body", details to "g_details_error_change_language") +
            listOf(quickKey to "g_quickkey_record_as_in")
        for ((file, name) in strings) {
            val words = text("values-de", file, name).split(Regex("[^\\p{L}]+"))
            assertFalse(name, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        }
    }

    @Test
    fun `the row offers Malayalam only with its model on the phone, unless the recording is in it`() {
        assertEquals(RecordingLanguage.entries, languageChoices(RecordingLanguage.ENGLISH_OR_GERMAN, malayalamOnPhone = true))
        assertEquals(listOf(RecordingLanguage.ENGLISH_OR_GERMAN), languageChoices(RecordingLanguage.ENGLISH_OR_GERMAN, malayalamOnPhone = false))
        // A Malayalam recording whose model went keeps saying what it is in, and can go back.
        assertEquals(RecordingLanguage.entries, languageChoices(RecordingLanguage.MALAYALAM, malayalamOnPhone = false))
    }

    /**
     * The text of the string [name] in the app's res/[folder]/[file], with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun text(folder: String, file: String, name: String): String {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent.replace("\\'", "'")
    }
}
