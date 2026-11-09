package com.satori.ui.screens

import com.satori.domain.usecase.finish.TranscribeAfterStopUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The help of the better transcript after Stop in Advanced settings: the minutes it names for
 * the limit ([TranscribeAfterStopUseCase.MAX_ESTIMATED_MS]), how they fit the minutes of the
 * Transcribe again sheet, and its text in both languages.
 */
class AfterStopHelpTest {

    private val limit = TranscribeAfterStopUseCase.MAX_ESTIMATED_MS

    @Test
    fun `the help names today's limit, three minutes`() {
        assertEquals(3, afterStopLimitMinutes())
        assertEquals(3, afterStopLimitMinutes(limit))
    }

    @Test
    fun `a limit that isn't whole minutes is named rounded down, and never as no minutes`() {
        assertEquals(1, afterStopLimitMinutes(90_000L))
        assertEquals(2, afterStopLimitMinutes(179_999L))
        assertEquals(4, afterStopLimitMinutes(270_000L))
        assertEquals(1, afterStopLimitMinutes(20_000L))
        assertEquals(1, afterStopLimitMinutes(0L))
    }

    @Test
    fun `at the cut the sheet says the help's minutes, and a millisecond over one more`() {
        // TranscribeAfterStopUseCase runs an estimate of the limit and leaves one a millisecond
        // longer to Transcribe again.
        assertEquals(afterStopLimitMinutes(), estimateMinutes(limit))
        assertEquals(afterStopLimitMinutes() + 1, estimateMinutes(limit + 1))
    }

    @Test
    fun `whatever the sheet puts at the help's minutes or fewer is within the limit`() {
        for (limitMs in listOf(60_000L, 90_000L, 150_000L, 179_999L, 180_000L, 240_001L, 600_000L)) {
            val named = afterStopLimitMinutes(limitMs)
            val estimates = (0L..limitMs + 3 * 60_000L step 500L) + listOf(limitMs, limitMs + 1)
            for (estimateMs in estimates) {
                // "Under a minute." counts as none.
                val minutes = estimateMinutes(estimateMs) ?: 0
                if (minutes <= named) assertTrue("$estimateMs ms, limit $limitMs ms", estimateMs <= limitMs)
            }
        }
    }

    @Test
    fun `every form of the help names the minutes, in both languages`() {
        for (folder in listOf("values", "values-de")) {
            val forms = helpForms(folder)
            assertEquals(folder, setOf("one", "other"), forms.keys)
            for ((quantity, text) in forms) {
                assertEquals("$folder $quantity", 1, text.split("%1\$d").size - 1)
            }
        }
    }

    @Test
    fun `the help's sentence on Skip doesn't end on a bare it`() {
        // "Skip ends it." pointed back at the transcript once the sentence before it had gone,
        // and in German "es" at the model.
        val skip = mapOf("values" to "Skip", "values-de" to "„Überspringen“")
        for ((folder, word) in skip) {
            for ((quantity, text) in helpForms(folder)) {
                val sentence = text.split(". ").single { it.startsWith(word) }
                assertFalse("$folder $quantity: $sentence", sentence.endsWith(" it") || sentence.endsWith(" es"))
            }
        }
    }

    /**
     * The forms of the help in the app's res/[folder]/strings_grille_speech.xml by quantity, with
     * their escapes read as aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun helpForms(folder: String): Map<String, String> {
        val file = File("src/main/res/$folder/strings_grille_speech.xml")
        val plurals = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
        val help = (0 until plurals.length).map { plurals.item(it) as Element }
            .single { it.getAttribute("name") == "g_speech_after_stop_help" }
        val items = help.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }
            .associate { it.getAttribute("quantity") to it.textContent.replace("\\'", "'") }
    }
}
