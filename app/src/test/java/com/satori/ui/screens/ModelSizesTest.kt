package com.satori.ui.screens

import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * One model has one size everywhere: Advanced settings writes the registry's bytes as the first
 * run, Models and storage and home's strip do ([modelSizeLabel]), so Malayalam is 138 MB and the
 * Standard AI model 1.1 GB on every screen. Only the two AI models the registry knows by their
 * least bytes keep a written size.
 */
class ModelSizesTest {

    private fun speech(model: SpeechModelId, locale: Locale = Locale.ENGLISH) = modelSizeLabel(SpeechModels.of(model).bytes, locale)

    private fun ai(variant: LlmModelVariant, locale: Locale = Locale.ENGLISH) =
        modelSizeLabel(checkNotNull(modelConfigForVariant(variant).llmSpec.bytes), locale)

    @Test
    fun `the speech models' sizes are the registry's`() {
        assertEquals("670\u00A0MB", speech(SpeechModelId.PARAKEET_V3))
        assertEquals("138\u00A0MB", speech(SpeechModelId.INDICCONFORMER_ML))
        assertEquals("661\u00A0MB", speech(SpeechModelId.PARAKEET_V2_EN))
        assertEquals("2,9\u00A0GB", speech(SpeechModelId.COHERE_TRANSCRIBE, Locale.GERMAN))
    }

    @Test
    fun `the AI models' sizes are the registry's where it knows them, written where it doesn't`() {
        assertEquals("1.1\u00A0GB", ai(LlmModelVariant.Q8_0))
        assertEquals("3,3\u00A0GB", ai(LlmModelVariant.GEMMA4_E2B_Q4_0, Locale.GERMAN))
        for (variant in LlmModelVariant.entries) {
            val exact = modelConfigForVariant(variant).llmSpec.bytes != null
            assertEquals("$variant", !exact, roughSize(variant) != null)
        }
        assertEquals(setOf(LlmModelVariant.IQ4_NL, LlmModelVariant.QWEN3_5_Q8_0), LlmModelVariant.entries.filter { roughSize(it) != null }.toSet())
    }
}
