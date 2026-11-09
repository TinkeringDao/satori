package com.satori.domain.model

import com.satori.domain.model.LlmModelVariant.GEMMA4_E2B_Q4_0
import com.satori.domain.model.LlmModelVariant.IQ4_NL
import com.satori.domain.model.LlmModelVariant.Q8_0
import com.satori.domain.model.LlmModelVariant.QWEN3_5_Q8_0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LlmModelChoiceTest {

    @Test
    fun `the selected model is kept when its file is there`() {
        assertEquals(Q8_0, LlmModelChoice.pick(selected = Q8_0, recommended = IQ4_NL, installed = setOf(Q8_0, IQ4_NL)))
    }

    @Test
    fun `the recommended model is used when the selected one is not there`() {
        assertEquals(
            IQ4_NL,
            LlmModelChoice.pick(selected = Q8_0, recommended = IQ4_NL, installed = setOf(IQ4_NL, QWEN3_5_Q8_0))
        )
    }

    @Test
    fun `the first model in the enum's order is used when neither is there`() {
        assertEquals(
            QWEN3_5_Q8_0,
            LlmModelChoice.pick(selected = Q8_0, recommended = IQ4_NL, installed = setOf(QWEN3_5_Q8_0))
        )
        assertEquals(
            Q8_0,
            LlmModelChoice.pick(selected = IQ4_NL, recommended = IQ4_NL, installed = setOf(QWEN3_5_Q8_0, Q8_0))
        )
    }

    @Test
    fun `no model on the phone gives none`() {
        assertNull(LlmModelChoice.pick(selected = Q8_0, recommended = IQ4_NL, installed = emptySet()))
    }

    @Test
    fun `Gemma 4 E2B is never the live model, even alone on the phone or selected`() {
        assertNull(LlmModelChoice.pick(selected = Q8_0, recommended = IQ4_NL, installed = setOf(GEMMA4_E2B_Q4_0)))
        assertEquals(
            Q8_0,
            LlmModelChoice.pick(selected = GEMMA4_E2B_Q4_0, recommended = null, installed = setOf(GEMMA4_E2B_Q4_0, Q8_0))
        )
        assertEquals(
            IQ4_NL,
            LlmModelChoice.pick(selected = Q8_0, recommended = GEMMA4_E2B_Q4_0, installed = setOf(GEMMA4_E2B_Q4_0, IQ4_NL))
        )
    }

    @Test
    fun `without a recommendation the first model on the phone stands in for the selected one`() {
        // What the recorder and the details page do; they don't know the phone's tier.
        assertEquals(IQ4_NL, LlmModelChoice.pick(selected = IQ4_NL, recommended = null, installed = setOf(Q8_0, IQ4_NL)))
        assertEquals(
            QWEN3_5_Q8_0,
            LlmModelChoice.pick(selected = Q8_0, recommended = null, installed = setOf(QWEN3_5_Q8_0, GEMMA4_E2B_Q4_0))
        )
    }

    @Test
    fun `only Gemma 4 E2B is kept from the live notes`() {
        assertEquals(listOf(Q8_0, IQ4_NL, QWEN3_5_Q8_0), LlmModelVariant.forLiveNotes)
        assertFalse(GEMMA4_E2B_Q4_0.writesLiveNotes)
    }
}
