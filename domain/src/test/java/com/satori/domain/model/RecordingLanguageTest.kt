package com.satori.domain.model

import com.satori.domain.model.RecordingLanguage.ENGLISH_OR_GERMAN
import com.satori.domain.model.RecordingLanguage.MALAYALAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language a recording is made in, as it is stored: null for English or German, which every
 * recording from before it was stored reads as, and "ml" for Malayalam; the live model each is
 * transcribed with while it records, whether it gets live notes, and what records while Malayalam's
 * model isn't on the phone.
 */
class RecordingLanguageTest {

    @Test
    fun `the codes are the ones stored with recordings`() {
        assertNull(ENGLISH_OR_GERMAN.code)
        assertEquals("ml", MALAYALAM.code)
    }

    @Test
    fun `a code reads back as its language`() {
        RecordingLanguage.entries.forEach { assertEquals(it, RecordingLanguage.fromCode(it.code)) }
    }

    @Test
    fun `no code, and a code this version doesn't know, read as English or German`() {
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.fromCode(null))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.fromCode("ta"))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.fromCode(""))
        // The stored code is written by the app itself, in lower case; anything else is no code it wrote.
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.fromCode("ML"))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.fromCode("MALAYALAM"))
    }

    @Test
    fun `a recording is English or German unless it is made in Malayalam`() {
        assertEquals(ENGLISH_OR_GERMAN, TranscriptionSession(id = "s1", name = null, createdAt = 1L, lastModifiedAt = 1L).language)
    }

    @Test
    fun `English or German is transcribed live by Parakeet v3, Malayalam by the Malayalam model in its place`() {
        assertEquals(SpeechModelId.PARAKEET_V3, ENGLISH_OR_GERMAN.liveModel)
        assertEquals(SpeechModelId.LIVE, ENGLISH_OR_GERMAN.liveModel)
        assertEquals(SpeechModelId.INDICCONFORMER_ML, MALAYALAM.liveModel)
        // Each live model runs live and knows its language.
        RecordingLanguage.entries.forEach { language ->
            assertTrue(language.liveModel.runsLive)
            assertTrue(language.liveModel.understands(language.code ?: "de"))
        }
        assertTrue(MALAYALAM.liveModel.understands("ml"))
        assertFalse(ENGLISH_OR_GERMAN.liveModel.understands("ml"))
    }

    @Test
    fun `a transcript of the live models is a live one, a better transcript's model is none of them`() {
        assertEquals(setOf(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML), RecordingLanguage.LIVE_MODELS)
        assertFalse(SpeechModelId.PARAKEET_V2_EN in RecordingLanguage.LIVE_MODELS)
        assertFalse(SpeechModelId.COHERE_TRANSCRIBE in RecordingLanguage.LIVE_MODELS)
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.ofLiveModel(SpeechModelId.PARAKEET_V3))
        assertEquals(MALAYALAM, RecordingLanguage.ofLiveModel(SpeechModelId.INDICCONFORMER_ML))
        assertNull(RecordingLanguage.ofLiveModel(SpeechModelId.COHERE_TRANSCRIBE))
    }

    @Test
    fun `a transcript made again by the Malayalam model makes the recording Malayalam, by any other English or German`() {
        assertEquals(MALAYALAM, RecordingLanguage.transcribedBy(SpeechModelId.INDICCONFORMER_ML))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.transcribedBy(SpeechModelId.PARAKEET_V3))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.transcribedBy(SpeechModelId.PARAKEET_V2_EN))
        assertEquals(ENGLISH_OR_GERMAN, RecordingLanguage.transcribedBy(SpeechModelId.COHERE_TRANSCRIBE))
    }

    @Test
    fun `live notes are written for English and German, not for Malayalam, whose notes come after Stop`() {
        assertTrue(ENGLISH_OR_GERMAN.writesLiveNotes)
        assertFalse(MALAYALAM.writesLiveNotes)
    }

    @Test
    fun `the other language of each is the one the recorder's language key switches to`() {
        assertEquals(MALAYALAM, ENGLISH_OR_GERMAN.other)
        assertEquals(ENGLISH_OR_GERMAN, MALAYALAM.other)
        RecordingLanguage.entries.forEach { assertEquals(it, it.other.other) }
    }

    @Test
    fun `Malayalam records in Malayalam while its model is on the phone, and in English or German while it isn't`() {
        assertEquals(MALAYALAM, MALAYALAM.usableWith(malayalamOnPhone = true))
        assertEquals(ENGLISH_OR_GERMAN, MALAYALAM.usableWith(malayalamOnPhone = false))
        assertEquals(ENGLISH_OR_GERMAN, ENGLISH_OR_GERMAN.usableWith(malayalamOnPhone = true))
        assertEquals(ENGLISH_OR_GERMAN, ENGLISH_OR_GERMAN.usableWith(malayalamOnPhone = false))
    }
}
