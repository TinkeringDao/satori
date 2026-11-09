package com.satori.ui.screens

import com.satori.domain.model.SpeechModelId.COHERE_TRANSCRIBE
import com.satori.domain.model.SpeechModelId.PARAKEET_V2_EN
import com.satori.domain.model.SpeechModelId.PARAKEET_V3
import com.satori.domain.usecase.stt.SpeechModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The time the Transcribe again sheet gives each model: "Under a minute." only for an estimate
 * under 60 s, otherwise "About N min." in whole minutes rounded up, which follow the times the
 * razr measured. The sheet's own text names no time.
 */
class TranscribeChoiceMinutesTest {

    @Test
    fun `under a minute only below 60 s, whole minutes rounded up from there`() {
        assertNull(estimateMinutes(0L))
        assertNull(estimateMinutes(59_999L))
        assertEquals(1, estimateMinutes(60_000L))
        assertEquals(2, estimateMinutes(60_001L))
        assertEquals(5, estimateMinutes(300_000L))
    }

    @Test
    fun `the minutes follow the phone's own times`() {
        // The estimate is the foreground group's time rounded up: Cohere Transcribe after Stop in
        // a pocket took 28 s over 43.6 s of audio. A 146 s recording: about 2 min. with Cohere
        // (66 s), under one with Parakeet (20 s).
        assertEquals(2, estimateMinutes(SpeechModelChoice.estimatedMs(COHERE_TRANSCRIBE, 146_400L)))
        assertNull(estimateMinutes(SpeechModelChoice.estimatedMs(PARAKEET_V3, 146_400L)))
        assertNull(estimateMinutes(SpeechModelChoice.estimatedMs(PARAKEET_V2_EN, 146_400L)))
        // Under one with Cohere over a 49 s recording (34 s), which took 18.5 s with Satori in front.
        assertNull(estimateMinutes(SpeechModelChoice.estimatedMs(COHERE_TRANSCRIBE, 49_100L)))
        // A ten-minute recording: about 4 min. with Cohere (216 s), about 2 with Parakeet (61 s).
        assertEquals(4, estimateMinutes(SpeechModelChoice.estimatedMs(COHERE_TRANSCRIBE, 600_000L)))
        assertEquals(2, estimateMinutes(SpeechModelChoice.estimatedMs(PARAKEET_V3, 600_000L)))
        assertEquals(2, estimateMinutes(SpeechModelChoice.estimatedMs(PARAKEET_V2_EN, 600_000L)))
    }

    @Test
    fun `the sheet's text names no time, each model's row gives its own`() {
        // "It can take a few minutes." stood above rows of, say, "About 17 min." for Cohere
        // Transcribe on a ten-minute recording.
        for (folder in listOf("values", "values-de")) {
            val body = shipped(folder, "g_audio_transcribe_body")
            assertFalse("$folder: $body", body.contains("minute", ignoreCase = true) || body.any { it.isDigit() })
        }
    }

    /**
     * The string [name] of the app's res/[folder]/strings_grille_audio.xml, with its escapes read
     * as aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun shipped(folder: String, name: String): String {
        val file = File("src/main/res/$folder/strings_grille_audio.xml")
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val raw = (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
        return raw.replace("\\'", "'")
    }
}
