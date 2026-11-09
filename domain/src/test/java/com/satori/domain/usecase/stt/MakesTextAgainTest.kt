package com.satori.domain.usecase.stt

import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingLanguage.ENGLISH_OR_GERMAN
import com.satori.domain.model.RecordingLanguage.MALAYALAM
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelId.COHERE_TRANSCRIBE
import com.satori.domain.model.SpeechModelId.INDICCONFORMER_ML
import com.satori.domain.model.SpeechModelId.PARAKEET_V2_EN
import com.satori.domain.model.SpeechModelId.PARAKEET_V3
import com.satori.domain.model.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcribe again has a recording's name, notes, to-dos and ideas made again when its new
 * transcript changes the recording's language, or replaces a transcript that wasn't in it (brief
 * item 2, phone evidence of 2026-10-15: a Malayalam note Parakeet had made gibberish of kept the
 * name and the idea the gibberish gave it). Any other model keeps them, as before.
 */
class MakesTextAgainTest {

    private fun line(text: String, at: Long, complete: Boolean = true) =
        TranscriptionSegment("l$at", "s1", text, at, isComplete = complete)

    // What Parakeet v3 makes of Malayalam speech: Latin letters, no clear language.
    private val gibberish = listOf(line("Uh a lay the porch in a key run on the wall here.", 1), line("Ah no para in the kettle so.", 2))

    // Tomorrow morning I have to go to the bank; don't forget the medicine.
    private val malayalam = listOf(line("നാളെ രാവിലെ ബാങ്കിൽ പോകണം.", 1), line("മരുന്ന് വാങ്ങാൻ മറക്കരുത്.", 2))

    private val english = listOf(line("Remind me to call the bank about the loan.", 1), line("The shelves go to the basement.", 2))

    @Test
    fun `a transcript is in its recording's language line by line, by the script`() {
        assertTrue(TranscriptLanguage.isIn(ENGLISH_OR_GERMAN, english))
        assertTrue(TranscriptLanguage.isIn(ENGLISH_OR_GERMAN, gibberish))
        assertTrue(TranscriptLanguage.isIn(MALAYALAM, malayalam))
        assertFalse(TranscriptLanguage.isIn(MALAYALAM, gibberish))
        assertFalse(TranscriptLanguage.isIn(ENGLISH_OR_GERMAN, malayalam))
        // Switched to Malayalam while it recorded: the lines before the switch are Parakeet's.
        assertFalse(TranscriptLanguage.isIn(MALAYALAM, gibberish + malayalam.map { it.copy(id = "m${it.id}", timestamp = it.timestamp + 10) }))
        // Switched back: a Malayalam line in an English or German recording.
        assertFalse(TranscriptLanguage.isIn(ENGLISH_OR_GERMAN, english + malayalam.first().copy(id = "m", timestamp = 9)))
    }

    @Test
    fun `lines without letters and lines still being said count for no language`() {
        val noise = line(".", 3)
        val partial = line("Then a lay the porch", 4, complete = false)

        assertTrue(TranscriptLanguage.isIn(MALAYALAM, malayalam + noise + partial))
        assertTrue(TranscriptLanguage.isIn(ENGLISH_OR_GERMAN, emptyList()))
        // English words the owner typed into a Malayalam line by hand leave it Malayalam.
        assertTrue(TranscriptLanguage.isIn(MALAYALAM, listOf(line("നാളെ രാവിലെ ബാങ്കിൽ പോകണം, loan.", 1))))
    }

    @Test
    fun `the Malayalam model makes an English or German recording's text again, Parakeet a Malayalam one's`() {
        assertTrue(SpeechModelChoice.makesTextAgain(INDICCONFORMER_ML, ENGLISH_OR_GERMAN, transcriptInLanguage = true))
        for (model in listOf(PARAKEET_V3, PARAKEET_V2_EN, COHERE_TRANSCRIBE)) {
            assertTrue(model.id, SpeechModelChoice.makesTextAgain(model, MALAYALAM, transcriptInLanguage = true))
            assertFalse(model.id, SpeechModelChoice.makesTextAgain(model, ENGLISH_OR_GERMAN, transcriptInLanguage = true))
        }
        // A Malayalam recording made again in Malayalam keeps its text.
        assertFalse(SpeechModelChoice.makesTextAgain(INDICCONFORMER_ML, MALAYALAM, transcriptInLanguage = true))
    }

    @Test
    fun `a transcript that wasn't in the recording's language has its text made again by any model`() {
        for (model in SpeechModelId.entries) {
            assertTrue(model.id, SpeechModelChoice.makesTextAgain(model, MALAYALAM, transcriptInLanguage = false))
            assertTrue(model.id, SpeechModelChoice.makesTextAgain(model, ENGLISH_OR_GERMAN, transcriptInLanguage = false))
        }
    }

    @Test
    fun `the sheet's options say which model changes the language and has the text made again`() {
        fun options(recording: RecordingLanguage, inLanguage: Boolean, spoken: String?) =
            SpeechModelChoice.options(
                language = spoken,
                installed = SpeechModelId.entries.toSet(),
                sizeBytes = { 640L * 1024 * 1024 },
                aiModelLoaded = false,
                availableBytes = 8L * 1024 * 1024 * 1024,
                audioMs = 21_000L,
                current = PARAKEET_V3,
                recording = recording,
                transcriptInLanguage = inLanguage
            ).associateBy { it.model }

        // The morning's Malayalam note, made gibberish of by Parakeet v3 on 0.5.3.
        val note = options(ENGLISH_OR_GERMAN, inLanguage = true, spoken = null)
        assertEquals(setOf(PARAKEET_V3, INDICCONFORMER_ML), note.keys)
        assertTrue(note.getValue(INDICCONFORMER_ML).changesLanguage)
        assertTrue(note.getValue(INDICCONFORMER_ML).makesTextAgain)
        assertFalse(note.getValue(PARAKEET_V3).changesLanguage)
        assertFalse(note.getValue(PARAKEET_V3).makesTextAgain)

        // A recording in Malayalam with Parakeet's lines: any model has its text made again.
        val switched = options(MALAYALAM, inLanguage = false, spoken = null)
        assertTrue(switched.values.all { it.makesTextAgain })
        assertFalse(switched.getValue(INDICCONFORMER_ML).changesLanguage)
        assertTrue(switched.getValue(PARAKEET_V3).changesLanguage)

        // An English recording made again in English keeps its text, as before.
        val meeting = options(ENGLISH_OR_GERMAN, inLanguage = true, spoken = "en")
        assertTrue(meeting.filterKeys { it != INDICCONFORMER_ML }.values.none { it.makesTextAgain || it.changesLanguage })
    }
}
