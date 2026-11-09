package com.satori.data.config

import com.satori.domain.model.LlmModelVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The AI models' file names, by which lib-llama-android's ModelDefaults (LoadSettings.kt) chooses
 * the settings of each load: renamed here alone, Gemma 3 1B or Gemma 4 E2B would load as any other
 * model does, paced but without run 4's settings (the tied output repacked, a full SWA cache, an F16
 * KV cache, the batch's outputs).
 */
class AiModelFilesTest {

    @Test
    fun `Gemma 3 1B's and Gemma 4 E2B's files keep the names their load settings are chosen by`() {
        assertSame(DefaultModelConfig.INSTANCE, modelConfigForVariant(LlmModelVariant.Q8_0))
        assertSame(Gemma4E2bModelConfig.INSTANCE, modelConfigForVariant(LlmModelVariant.GEMMA4_E2B_Q4_0))
        assertEquals("gemma-3-1b-it-Q8_0.gguf", DefaultModelConfig.INSTANCE.llmFilename)
        assertEquals("gemma-4-E2B_q4_0-it.gguf", Gemma4E2bModelConfig.INSTANCE.llmFilename)
    }
}
