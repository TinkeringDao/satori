package com.satori.domain.settings

import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model cleanup: what each setting uses, what "Remove what isn't used" removes and what it
 * frees, and what can't go now: Parakeet v3 never, and a model something running may hold not
 * until that ends.
 */
class ModelCleanupTest {

    private val v3 = ModelRef.Speech(SpeechModelId.PARAKEET_V3)
    private val v2 = ModelRef.Speech(SpeechModelId.PARAKEET_V2_EN)
    private val cohere = ModelRef.Speech(SpeechModelId.COHERE_TRANSCRIBE)
    private val malayalam = ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML)
    private val standard = ModelRef.Ai(LlmModelVariant.Q8_0)
    private val efficient = ModelRef.Ai(LlmModelVariant.IQ4_NL)
    private val e2b = ModelRef.Ai(LlmModelVariant.GEMMA4_E2B_Q4_0)

    private val facts = ModelSettingsFacts(
        aiNotesOn = true,
        chosenAi = LlmModelVariant.Q8_0,
        finishingModelOn = true,
        betterTranscript = null,
        malayalamChosen = false
    )

    private val all = listOf(
        ModelOnPhone(v3, 670_478_772L, whole = true),
        ModelOnPhone(v2, 660_000_000L, whole = true),
        ModelOnPhone(cohere, 2_900_000_000L, whole = true),
        ModelOnPhone(malayalam, 137_566_803L, whole = true),
        ModelOnPhone(standard, 1_069_306_368L, whole = true),
        ModelOnPhone(efficient, 650_000_000L, whole = true),
        ModelOnPhone(e2b, 3_300_000_000L, whole = true)
    )

    @Test
    fun `each setting uses its model`() {
        assertEquals(setOf(ModelUse.RECORDING), ModelCleanup.usesOf(v3, facts))
        assertEquals(setOf(ModelUse.AI_NOTES), ModelCleanup.usesOf(standard, facts))
        assertEquals(setOf(ModelUse.FINISHING), ModelCleanup.usesOf(e2b, facts))
        assertEquals(emptySet<ModelUse>(), ModelCleanup.usesOf(efficient, facts))
        // The recorder's language key offers it whenever it is on the phone.
        assertEquals(setOf(ModelUse.LANGUAGE_KEY), ModelCleanup.usesOf(malayalam, facts))
        assertEquals(
            listOf(ModelUse.MALAYALAM, ModelUse.LANGUAGE_KEY),
            ModelCleanup.usesOf(malayalam, facts.copy(malayalamChosen = true)).toList()
        )
        assertEquals(emptySet<ModelUse>(), ModelCleanup.usesOf(cohere, facts))
        assertEquals(setOf(ModelUse.BETTER_TRANSCRIPT), ModelCleanup.usesOf(cohere, facts.copy(betterTranscript = SpeechModelId.COHERE_TRANSCRIBE)))
    }

    @Test
    fun `with AI notes off no AI model is used, and E2B not with its switch off`() {
        val off = facts.copy(aiNotesOn = false)
        assertEquals(emptySet<ModelUse>(), ModelCleanup.usesOf(standard, off))
        assertEquals(emptySet<ModelUse>(), ModelCleanup.usesOf(e2b, off))
        assertEquals(emptySet<ModelUse>(), ModelCleanup.usesOf(e2b, facts.copy(finishingModelOn = false)))
    }

    @Test
    fun `remove what isn't used lists the whole models no setting uses, with what they free`() {
        val plan = ModelCleanup.unused(all, facts, ModelActivity())

        assertEquals(listOf(v2, cohere, efficient), plan.models.map { it.ref })
        assertEquals(660_000_000L + 2_900_000_000L + 650_000_000L, plan.bytes)
        assertTrue(plan.waiting.isEmpty())
    }

    @Test
    fun `after a fresh install's first run nothing is unused, so Malayalam stays for the language key`() {
        // Parakeet v3, Malayalam from Get ready's switch, the AI model for the phone, AI notes on,
        // home recording in English or German.
        val firstRun = listOf(
            ModelOnPhone(v3, 670_478_772L, whole = true),
            ModelOnPhone(malayalam, 137_566_803L, whole = true),
            ModelOnPhone(standard, 1_069_306_368L, whole = true)
        )

        val plan = ModelCleanup.unused(firstRun, facts.copy(finishingModelOn = false), ModelActivity())

        assertTrue(plan.models.isEmpty())
        assertTrue(plan.waiting.isEmpty())
        // It can still go alone, and its question says what that costs.
        assertNull(ModelCleanup.refusal(malayalam, ModelActivity(), facts))
    }

    @Test
    fun `Parakeet v3 is never removed, alone or with the rest`() {
        assertEquals(RemoveRefusal.NEEDED_TO_RECORD, ModelCleanup.refusal(v3, ModelActivity(), facts))
        assertFalse(v3 in ModelCleanup.unused(all, facts.copy(aiNotesOn = false), ModelActivity()).models.map { it.ref })
    }

    @Test
    fun `a half download never goes in the list, but can go alone`() {
        val half = listOf(ModelOnPhone(cohere, 1_000_000L, whole = false))
        assertTrue(ModelCleanup.unused(half, facts, ModelActivity()).models.isEmpty())
        assertNull(ModelCleanup.refusal(cohere, ModelActivity(), facts))
    }

    @Test
    fun `a model something running may hold waits until it ends`() {
        // A recording runs: the Malayalam model and the chosen AI model may be loaded.
        val recording = ModelActivity(recording = true)
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(malayalam, recording, facts))
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(standard, recording, facts))
        assertNull(ModelCleanup.refusal(cohere, recording, facts))
        assertNull(ModelCleanup.refusal(e2b, recording, facts))
        assertNull(ModelCleanup.refusal(efficient, recording, facts))
        // A finishing: every speech model may transcribe again, the AI models write.
        val finishing = ModelActivity(finishing = true)
        for (ref in listOf(v2, cohere, malayalam, standard, e2b)) {
            assertEquals(ref.toString(), RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(ref, finishing, facts))
        }
        assertNull(ModelCleanup.refusal(efficient, finishing, facts))
        // A Transcribe again: the speech models.
        val again = ModelActivity(transcribingAgain = true)
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(v2, again, facts))
        assertNull(ModelCleanup.refusal(standard, again, facts))
        // Rewrite notes or the mind map: the AI models, and the Malayalam entry, which holds the
        // reader that makes the readings that work reads (0.8.0).
        val ai = ModelActivity(aiWork = true)
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(e2b, ai, facts))
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(malayalam, ai, facts))
        assertNull(ModelCleanup.refusal(cohere, ai, facts))
        assertNull(ModelCleanup.refusal(v2, ai, facts))
        // Its own download.
        assertEquals(RemoveRefusal.IN_USE_NOW, ModelCleanup.refusal(efficient, ModelActivity(downloading = setOf(efficient)), facts))
    }

    @Test
    fun `while a finishing runs the unused models wait, and say so`() {
        val plan = ModelCleanup.unused(all, facts, ModelActivity(finishing = true))

        assertEquals(listOf(efficient), plan.models.map { it.ref })
        assertEquals(listOf(v2, cohere), plan.waiting.map { it.ref })
        assertEquals(650_000_000L, plan.bytes)
    }

    @Test
    fun `home's hint is due once unused models take a gigabyte`() {
        val small = CleanupPlan(listOf(ModelOnPhone(malayalam, 137_566_803L, whole = true)))
        val large = CleanupPlan(emptyList(), waiting = listOf(ModelOnPhone(cohere, 2_900_000_000L, whole = true)))

        assertFalse(ModelCleanup.hintDue(small))
        assertTrue(ModelCleanup.hintDue(large))
        assertTrue(ModelCleanup.hintDue(CleanupPlan(listOf(ModelOnPhone(efficient, ModelCleanup.HINT_BYTES, whole = true)))))
    }
}
