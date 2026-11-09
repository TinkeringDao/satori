package com.satori.ui.screens

import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.stt.ModelReadiness
import com.satori.domain.usecase.stt.TranscribeOption
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the Transcribe again sheet says of the recording's text: the line under its title says the
 * notes, name and to-dos stay unless a model's line says otherwise, and only where some model's
 * transcript has them made again; that model's line says so, and that the to-dos the owner changed
 * stay (brief item 2). A model's line says the language the recording is then in where it changes:
 * Malayalam for the Malayalam model, and English or German where another model changes it back, for
 * speech that wasn't Malayalam. English and German, German without du or Sie.
 */
class TranscribeChoiceNotesTest {

    private val parakeet = TranscribeOption(SpeechModelId.PARAKEET_V3, ModelReadiness.Ready, 60_000L, madeTranscript = true)

    // For an English or German recording: it makes the recording Malayalam, and its text again.
    private val malayalam = TranscribeOption(
        SpeechModelId.INDICCONFORMER_ML, ModelReadiness.Ready, 180_000L, madeTranscript = false,
        changesLanguage = true, makesTextAgain = true
    )

    // For a Malayalam recording: Parakeet makes it English or German again, and its text again.
    private val backToEnglish = parakeet.copy(madeTranscript = false, changesLanguage = true, makesTextAgain = true)

    @Test
    fun `the line under the title says the text stays unless a model's line says otherwise, only where one does`() {
        assertEquals(R.string.g_audio_transcribe_body_text_again, transcribeBodyRes(listOf(parakeet, malayalam), textFollows = true))
        // The page can't make the text again: everything but the transcript stays with each model.
        assertEquals(R.string.g_audio_transcribe_body, transcribeBodyRes(listOf(parakeet, malayalam), textFollows = false))
        // A Malayalam recording made again in Malayalam: nothing but the transcript changes.
        assertEquals(R.string.g_audio_transcribe_body, transcribeBodyRes(listOf(malayalam.copy(changesLanguage = false, makesTextAgain = false)), textFollows = true))
        assertEquals(R.string.g_audio_transcribe_body, transcribeBodyRes(listOf(parakeet), textFollows = true))
        assertEquals(R.string.g_speech_none_fits, transcribeBodyRes(emptyList(), textFollows = true))
    }

    @Test
    fun `a model's line says the language the recording is then in, and that its text is made again only where it is`() {
        assertEquals(listOf(R.string.g_speech_then_ml, R.string.g_speech_text_again), optionNoteRes(malayalam, textFollows = true))
        assertEquals(listOf(R.string.g_speech_then_ml), optionNoteRes(malayalam, textFollows = false))
        // The way back for a recording made in Malayalam by mistake says what it is for.
        assertEquals(
            listOf(R.string.g_speech_then_en_de, R.string.g_speech_not_for_ml, R.string.g_speech_text_again),
            optionNoteRes(backToEnglish, textFollows = true)
        )
        assertEquals(listOf(R.string.g_speech_then_en_de, R.string.g_speech_not_for_ml), optionNoteRes(backToEnglish, textFollows = false))
        assertEquals(listOf(R.string.g_speech_made_transcript), optionNoteRes(parakeet, textFollows = true))
        // A Malayalam recording made again in Malayalam stays so, its text as it is: nothing to say
        // of its language, which it has already (the settings audit's TRANSCRIBE-AGAIN-ML).
        assertEquals(
            listOf(R.string.g_speech_made_transcript),
            optionNoteRes(malayalam.copy(madeTranscript = true, changesLanguage = false, makesTextAgain = false), textFollows = true)
        )
        // Parakeet's lines in a Malayalam recording: made again in Malayalam, its text is made again too.
        assertEquals(
            listOf(R.string.g_speech_text_again),
            optionNoteRes(malayalam.copy(changesLanguage = false), textFollows = true)
        )
    }

    @Test
    fun `the words, in English and German`() {
        assertEquals(
            "A new transcript is made from the audio and replaces this one, lines corrected by hand too. Notes, name and to-dos " +
                "stay, except where the text under a model says they are made again.",
            text("values", "strings_grille_audio.xml", "g_audio_transcribe_body_text_again")
        )
        assertEquals(
            "Aus dem Audio entsteht ein neues Transkript, das dieses ersetzt, auch von Hand korrigierte Zeilen. Notizen, Name und " +
                "Aufgaben bleiben, außer wenn der Text unter einem Modell sagt, dass sie neu entstehen.",
            text("values-de", "strings_grille_audio.xml", "g_audio_transcribe_body_text_again")
        )
        // The other models keep today's words.
        assertEquals(
            "A new transcript is made from the audio and replaces this one, lines corrected by hand too. Notes, name and to-dos stay.",
            text("values", "strings_grille_audio.xml", "g_audio_transcribe_body")
        )
        assertEquals("Then in Malayalam.", text("values", "strings_grille_speech.xml", "g_speech_then_ml"))
        assertEquals("Danach auf Malayalam.", text("values-de", "strings_grille_speech.xml", "g_speech_then_ml"))
        assertEquals("Then in English or German.", text("values", "strings_grille_speech.xml", "g_speech_then_en_de"))
        assertEquals("Danach auf Englisch oder Deutsch.", text("values-de", "strings_grille_speech.xml", "g_speech_then_en_de"))
        assertEquals("Only for speech that wasn't Malayalam.", text("values", "strings_grille_speech.xml", "g_speech_not_for_ml"))
        assertEquals("Nur für Sprache, die kein Malayalam war.", text("values-de", "strings_grille_speech.xml", "g_speech_not_for_ml"))
        assertEquals(
            "Name, notes, to-dos and ideas are made again. To-dos edited, marked done or kept stay.",
            text("values", "strings_grille_speech.xml", "g_speech_text_again")
        )
        assertEquals(
            "Name, Notizen, Aufgaben und Ideen entstehen neu. Bearbeitete, erledigte oder behaltene Aufgaben bleiben.",
            text("values-de", "strings_grille_speech.xml", "g_speech_text_again")
        )
    }

    @Test
    fun `German says it without du or Sie`() {
        val german = listOf(
            text("values-de", "strings_grille_audio.xml", "g_audio_transcribe_body_text_again"),
            text("values-de", "strings_grille_speech.xml", "g_speech_then_ml"),
            text("values-de", "strings_grille_speech.xml", "g_speech_then_en_de"),
            text("values-de", "strings_grille_speech.xml", "g_speech_not_for_ml"),
            text("values-de", "strings_grille_speech.xml", "g_speech_text_again")
        )
        for (line in german) {
            val words = line.split(Regex("[^\\p{L}]+"))
            assertFalse(line, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        }
    }

    /**
     * The text of the string [name] in the app's res/[folder]/[file], with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun text(folder: String, file: String, name: String): String {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent.replace("\\'", "'").replace("\\u00A0", " ")
    }
}
