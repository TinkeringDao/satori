package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The words of switching what a recording is recorded as ("Record as" while recording, "Change
 * type" on its page): each one in English and German, with the category's name where it goes, and
 * German in the neutral infinitive style, without du or Sie.
 */
class RecordAsStringsTest {

    private val recorder = "strings_grille_recorder.xml"
    private val details = "strings_grille_details.xml"

    @Test
    fun `the recorder's notices say what it records as, in both languages`() {
        assertEquals("Recording as %1\$s", text("values", recorder, "g_rec_recording_as"))
        assertEquals("Wird als %1\$s aufgenommen", text("values-de", recorder, "g_rec_recording_as"))
        assertEquals("The recording couldn't be switched. It goes on as before.", text("values", recorder, "g_rec_switch_failed"))
        assertEquals(
            "Die Aufnahme ließ sich nicht umstellen und läuft wie bisher weiter.",
            text("values-de", recorder, "g_rec_switch_failed")
        )
    }

    @Test
    fun `over the lock screen the chip says to hold it, as the grille does, in both languages`() {
        assertEquals("Hold to change", text("values", recorder, "g_rec_hold_to_change"))
        assertEquals("Zum Ändern gedrückt halten", text("values-de", recorder, "g_rec_hold_to_change"))
        assertEquals("Hold to stop", text("values", recorder, "g_rec_hold_to_stop"))
    }

    @Test
    fun `the sheet and the chip are home's Record as, in both languages`() {
        assertEquals("Record as", text("values", "strings_grille_home.xml", "g_record_as_title"))
        assertEquals("Aufnehmen als", text("values-de", "strings_grille_home.xml", "g_record_as_title"))
        for (folder in listOf("values", "values-de")) {
            assertEquals(folder, 1, text(folder, "strings_grille_home.xml", "g_record_as_chip_cd").split("%1\$s").size - 1)
        }
    }

    @Test
    fun `the page's Change type, its line and its notices, in both languages`() {
        assertEquals("Change type", text("values", details, "g_details_change_type"))
        assertEquals("Art ändern", text("values-de", details, "g_details_change_type"))
        assertEquals(
            "The notes are written again if the new type writes other notes. Name and to-dos stay.",
            text("values", details, "g_details_change_type_body")
        )
        assertEquals(
            "Die Notizen werden neu geschrieben, wenn die neue Art andere Notizen schreibt. Name und Aufgaben bleiben.",
            text("values-de", details, "g_details_change_type_body")
        )
        assertEquals("Changed to %1\$s", text("values", details, "g_details_type_changed"))
        assertEquals("In %1\$s geändert", text("values-de", details, "g_details_type_changed"))
        assertEquals("The type wasn't changed. Try again.", text("values", details, "g_details_error_change_type"))
        assertEquals("Die Art wurde nicht geändert. Erneut versuchen.", text("values-de", details, "g_details_error_change_type"))
    }

    @Test
    fun `German says it without du or Sie`() {
        val strings = listOf(recorder to "g_rec_recording_as", recorder to "g_rec_switch_failed", recorder to "g_rec_hold_to_change") +
            listOf("g_details_change_type", "g_details_change_type_body", "g_details_type_changed", "g_details_error_change_type")
                .map { details to it }
        for ((file, name) in strings) {
            val words = text("values-de", file, name).split(Regex("[^\\p{L}]+"))
            assertFalse(name, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        }
    }

    @Test
    fun `the category's name is the one placeholder of the notices`() {
        for (folder in listOf("values", "values-de")) {
            for ((file, name) in listOf(recorder to "g_rec_recording_as", details to "g_details_type_changed")) {
                val notice = text(folder, file, name)
                assertEquals("$folder $name", 1, notice.split("%1\$s").size - 1)
                assertTrue("$folder $name", Regex("%[0-9]+\\$[a-z]").findAll(notice).count() == 1)
            }
        }
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
