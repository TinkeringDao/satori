package com.satori.ui.screens

import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.domain.settings.ModelUse
import com.satori.presentation.settings.StoredModel
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models and storage names each model by what it is for and says whether a setting uses it; after
 * the chosen AI model goes, the next one on the phone writes the notes. Its words exist in English
 * and German, the same keys in both, and none of a developer's.
 */
class ModelStorageScreenTest {

    private fun stored(ref: ModelRef, uses: Set<ModelUse> = emptySet(), whole: Boolean = true) =
        StoredModel(ModelOnPhone(ref, 1L, whole), uses, refusal = null)

    @Test
    fun `each model is named by what it is for`() {
        assertEquals(R.string.g_storage_for_speech, purposeOf(ModelRef.Speech(SpeechModelId.PARAKEET_V3)))
        assertEquals(R.string.g_storage_for_malayalam, purposeOf(ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML)))
        assertEquals(R.string.g_storage_for_better_en, purposeOf(ModelRef.Speech(SpeechModelId.PARAKEET_V2_EN)))
        assertEquals(R.string.g_storage_for_better_en_de, purposeOf(ModelRef.Speech(SpeechModelId.COHERE_TRANSCRIBE)))
        assertEquals(R.string.g_storage_for_ai_notes, purposeOf(ModelRef.Ai(LlmModelVariant.IQ4_NL)))
        assertEquals(R.string.g_storage_for_finishing, purposeOf(ModelRef.Ai(LlmModelVariant.GEMMA4_E2B_Q4_0)))
    }

    @Test
    fun `the line says which setting uses the model, or none, or a paused download`() {
        assertEquals(R.string.g_storage_use_recording, useLineOf(stored(ModelRef.Speech(SpeechModelId.PARAKEET_V3), setOf(ModelUse.RECORDING))))
        assertEquals(R.string.g_storage_use_better, useLineOf(stored(ModelRef.Speech(SpeechModelId.COHERE_TRANSCRIBE), setOf(ModelUse.BETTER_TRANSCRIPT))))
        assertEquals(R.string.g_storage_use_malayalam, useLineOf(stored(ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML), setOf(ModelUse.MALAYALAM, ModelUse.LANGUAGE_KEY))))
        // On the phone, it is the recorder's language key's, whatever the settings say.
        assertEquals(R.string.g_storage_use_language_key, useLineOf(stored(ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML), setOf(ModelUse.LANGUAGE_KEY))))
        assertEquals(R.string.g_storage_use_ai_notes, useLineOf(stored(ModelRef.Ai(LlmModelVariant.Q8_0), setOf(ModelUse.AI_NOTES))))
        assertEquals(R.string.g_storage_use_finishing, useLineOf(stored(ModelRef.Ai(LlmModelVariant.GEMMA4_E2B_Q4_0), setOf(ModelUse.FINISHING))))
        assertEquals(R.string.g_storage_use_none, useLineOf(stored(ModelRef.Ai(LlmModelVariant.IQ4_NL))))
        assertEquals(R.string.g_storage_paused, useLineOf(stored(ModelRef.Speech(SpeechModelId.PARAKEET_V2_EN), whole = false)))
    }

    @Test
    fun `the next model for live notes on the phone writes them once the chosen one is gone`() {
        val efficient = ModelOnPhone(ModelRef.Ai(LlmModelVariant.IQ4_NL), 1L, whole = true)
        val e2b = ModelOnPhone(ModelRef.Ai(LlmModelVariant.GEMMA4_E2B_Q4_0), 1L, whole = true)
        val halfQwen = ModelOnPhone(ModelRef.Ai(LlmModelVariant.QWEN3_5_Q8_0), 1L, whole = false)

        assertEquals(LlmModelVariant.IQ4_NL, nextNotesModel(listOf(e2b, efficient)))
        // Gemma 4 E2B writes no live notes, and half a download is no model.
        assertNull(nextNotesModel(listOf(e2b, halfQwen)))
    }

    @Test
    fun `the words exist in both languages, and say nothing a developer would`() {
        val en = StringsFiles.all("values").filterKeys { it.startsWith("strings_grille_storage.xml/") }
        val de = StringsFiles.all("values-de").filterKeys { it.startsWith("strings_grille_storage.xml/") }

        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, de.keys)
        val jargon = Regex("\\b(VAD|RTF|int8|tokens?|GGUF|ONNX|sherpa|LLM|CPU|RAM|Q8_0|IQ4_NL|Q4_0)\\b", RegexOption.IGNORE_CASE)
        for (text in en.values + de.values) assertFalse(text, jargon.containsMatchIn(text))
        assertEquals("Remove what isn't used", en.getValue("strings_grille_storage.xml/g_storage_unused"))
        assertEquals("Ungenutztes entfernen", de.getValue("strings_grille_storage.xml/g_storage_unused"))
    }
}
