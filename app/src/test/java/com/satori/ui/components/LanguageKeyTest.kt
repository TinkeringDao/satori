package com.satori.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorder's language key: no key while the recording can't change its language; a tap
 * switches it, over the lock screen only a hold; and its label, EN/DE until the newest lines tell
 * English from German, then EN or DE, and Malayalam's letter for Malayalam. The lines are made up.
 */
class LanguageKeyTest {

    private val english = "So the plan for Friday is that we book the room and send the agenda to the team"
    private val moreEnglish = "and then we will see what the others want to do after that"
    private val german = "Also der Plan für Freitag ist, dass wir den Raum buchen und die Tagesordnung schicken"
    private val moreGerman = "und dann sehen wir, was die anderen danach noch machen wollen"

    // Made up of code points, in Malayalam script.
    private val malayalam = "\u0D2E\u0D32 \u0D15\u0D3E\u0D32 \u0D35\u0D30"

    private fun lines(vararg texts: String) = texts.mapIndexed { i, text ->
        TranscriptionSegment(id = "l$i", sessionId = "s1", text = text, timestamp = 1_000L * (i + 1), isComplete = true)
    }

    private fun label(vararg texts: String) = languageKeyLabel(RecordingLanguage.ENGLISH_OR_GERMAN, lines(*texts))

    @Test
    fun `while the recording can't change its language there is no key to take input`() {
        assertNull(languageKeyInput(canChangeLanguage = false, holdToStop = false))
        assertNull(languageKeyInput(canChangeLanguage = false, holdToStop = true))
    }

    @Test
    fun `a tap switches the language, and over the lock screen only a hold does`() {
        assertEquals(LanguageKeyInput.TAP, languageKeyInput(canChangeLanguage = true, holdToStop = false))
        assertEquals(LanguageKeyInput.HOLD, languageKeyInput(canChangeLanguage = true, holdToStop = true))
    }

    @Test
    fun `English or German is EN or DE once the transcript shows which, and EN-DE until then`() {
        assertEquals(LanguageKeyLabel.EN_DE, label())
        assertEquals(LanguageKeyLabel.EN_DE, label("Okay, quick notes."))
        assertEquals(LanguageKeyLabel.EN, label(english))
        assertEquals(LanguageKeyLabel.DE, label(german))
        // Both evenly: it doesn't say which.
        assertEquals(LanguageKeyLabel.EN_DE, label("$english $german"))
    }

    @Test
    fun `Malayalam is Malayalam's whatever its lines are`() {
        assertEquals(LanguageKeyLabel.MALAYALAM, languageKeyLabel(RecordingLanguage.MALAYALAM, emptyList()))
        assertEquals(LanguageKeyLabel.MALAYALAM, languageKeyLabel(RecordingLanguage.MALAYALAM, lines(english, malayalam)))
    }

    @Test
    fun `the label follows what is said now, from the newest lines`() {
        // English for long enough, then German for as long: the key says German.
        val turned = List(6) { english } + List(6) { german } + List(6) { moreGerman }
        assertEquals(LanguageKeyLabel.DE, label(*turned.toTypedArray()))
        assertEquals(LanguageKeyLabel.EN, label(german, moreGerman, english, moreEnglish, english, moreEnglish))
    }

    @Test
    fun `lines in Malayalam script from before a switch back say nothing of English or German`() {
        assertEquals(LanguageKeyLabel.EN_DE, label(malayalam, malayalam))
        assertEquals(LanguageKeyLabel.EN, label(malayalam, malayalam, english))
    }

    @Test
    fun `only complete lines count, read in the order they were said`() {
        val partial = TranscriptionSegment(id = "p", sessionId = "s1", text = german, timestamp = 9_000L, isComplete = false)
        assertEquals(LanguageKeyLabel.EN, languageKeyLabel(RecordingLanguage.ENGLISH_OR_GERMAN, lines(english) + partial))

        val newestFirst = lines(german, moreGerman, english, moreEnglish, english, moreEnglish).reversed()
        assertEquals(LanguageKeyLabel.EN, languageKeyLabel(RecordingLanguage.ENGLISH_OR_GERMAN, newestFirst))
    }

    @Test
    fun `the key names the language in full and the one a tap switches to`() {
        assertEquals(
            listOf(LanguageKeyLabel.EN_DE, LanguageKeyLabel.EN, LanguageKeyLabel.DE).map { it.switchesTo }.distinct(),
            listOf(LanguageKeyLabel.MALAYALAM.language)
        )
        assertEquals(LanguageKeyLabel.EN_DE.language, LanguageKeyLabel.MALAYALAM.switchesTo)
    }

    @Test
    fun `Malayalam's letter is set larger and a step heavier than the codes, on the same line height`() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
        for (label in listOf(LanguageKeyLabel.EN_DE, LanguageKeyLabel.EN, LanguageKeyLabel.DE)) {
            assertEquals(base, labelStyle(label, base))
        }
        val letter = labelStyle(LanguageKeyLabel.MALAYALAM, base)
        assertEquals(14f * MALAYALAM_LETTER_SCALE, letter.fontSize.value, 0.001f)
        assertEquals(FontWeight(600), letter.fontWeight)
        assertEquals(base.lineHeight, letter.lineHeight)
    }
}
