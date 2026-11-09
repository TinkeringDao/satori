package com.satori.domain.model

import com.satori.domain.model.SpeechModelId.COHERE_TRANSCRIBE
import com.satori.domain.model.SpeechModelId.INDICCONFORMER_ML
import com.satori.domain.model.SpeechModelId.PARAKEET_V2_EN
import com.satori.domain.model.SpeechModelId.PARAKEET_V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelIdTest {

    @Test
    fun `the ids are the ones stored with transcripts and settings`() {
        assertEquals("parakeet-tdt-0.6b-v3-int8", PARAKEET_V3.id)
        assertEquals("parakeet-tdt-0.6b-v2-int8", PARAKEET_V2_EN.id)
        assertEquals("cohere-transcribe-14-lang-int8", COHERE_TRANSCRIBE.id)
        assertEquals("indicconformer-ml-int8", INDICCONFORMER_ML.id)
        assertEquals(SpeechModelId.entries.size, SpeechModelId.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `an id reads back as its model, anything else as none`() {
        SpeechModelId.entries.forEach { assertEquals(it, SpeechModelId.fromId(it.id)) }
        assertNull(SpeechModelId.fromId(null))
        assertNull(SpeechModelId.fromId("PARAKEET_V3"))
        assertNull(SpeechModelId.fromId("a-model-of-a-later-version"))
    }

    @Test
    fun `a transcript stored without a model is Parakeet v3's`() {
        assertEquals(PARAKEET_V3, SpeechModelId.ofTranscript(null))
        assertEquals(COHERE_TRANSCRIBE, SpeechModelId.ofTranscript("cohere-transcribe-14-lang-int8"))
        assertNull(SpeechModelId.ofTranscript("a-model-of-a-later-version"))
    }

    @Test
    fun `live recording uses Parakeet v3, and the models of its size may run live, not Cohere`() {
        assertEquals(PARAKEET_V3, SpeechModelId.LIVE)
        assertEquals(listOf(PARAKEET_V3, PARAKEET_V2_EN, INDICCONFORMER_ML), SpeechModelId.entries.filter { it.runsLive })
    }

    @Test
    fun `the Malayalam model takes speech known to be Malayalam, and nothing else`() {
        assertTrue(INDICCONFORMER_ML.understands("ml"))
        assertTrue(INDICCONFORMER_ML.understands("ml-IN"))
        assertFalse(INDICCONFORMER_ML.understands("en"))
        assertFalse(INDICCONFORMER_ML.understands("de"))
        assertFalse(INDICCONFORMER_ML.understands(null))
        assertFalse(INDICCONFORMER_ML.languagePerStream)
        // And no other model knows Malayalam.
        assertEquals(listOf(INDICCONFORMER_ML), SpeechModelId.entries.filter { it.understands("ml") })
    }

    @Test
    fun `Parakeet v3 takes English, German and speech of no known language`() {
        assertTrue(PARAKEET_V3.understands("en"))
        assertTrue(PARAKEET_V3.understands("de"))
        assertTrue(PARAKEET_V3.understands(null))
        assertFalse(PARAKEET_V3.understands("ja"))
        assertFalse(PARAKEET_V3.languagePerStream)
    }

    @Test
    fun `Parakeet v2 takes only speech known to be English`() {
        assertTrue(PARAKEET_V2_EN.understands("en"))
        assertFalse(PARAKEET_V2_EN.understands("de"))
        assertFalse(PARAKEET_V2_EN.understands(null))
        assertFalse(PARAKEET_V2_EN.languagePerStream)
    }

    @Test
    fun `Cohere takes English and German, told the language of each stream`() {
        assertTrue(COHERE_TRANSCRIBE.understands("en"))
        assertTrue(COHERE_TRANSCRIBE.understands("de"))
        assertFalse(COHERE_TRANSCRIBE.understands(null))
        assertFalse(COHERE_TRANSCRIBE.understands("hi"))
        assertTrue(COHERE_TRANSCRIBE.languagePerStream)
    }

    @Test
    fun `only the language of a code counts, in any case, and a blank one is unknown`() {
        assertTrue(PARAKEET_V2_EN.understands("en-IN"))
        assertTrue(PARAKEET_V2_EN.understands("EN"))
        assertTrue(COHERE_TRANSCRIBE.understands(" de-AT"))
        assertTrue(COHERE_TRANSCRIBE.understands("de_AT"))
        assertTrue(PARAKEET_V3.understands(""))
        assertFalse(COHERE_TRANSCRIBE.understands(" "))
    }
}
