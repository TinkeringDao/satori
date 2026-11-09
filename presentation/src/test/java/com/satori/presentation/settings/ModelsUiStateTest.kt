package com.satori.presentation.settings

import com.satori.domain.model.AiModelStatus
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelId.COHERE_TRANSCRIBE
import com.satori.domain.model.SpeechModelId.INDICCONFORMER_ML
import com.satori.domain.model.SpeechModelId.PARAKEET_V2_EN
import com.satori.domain.model.SpeechModelId.PARAKEET_V3
import com.satori.domain.model.SpeechModelStatus
import com.satori.domain.modelfile.ModelFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch for Gemma 4 E2B is offered only while its file is on the phone. The last speech model
 * on the phone can't be deleted, nor a whole Parakeet v3, which is offered again only when its
 * files are damaged; "Better transcript after Stop" offers only the better models on the phone,
 * and is off once the one it names is deleted. A delete question says what stops working.
 */
class ModelsUiStateTest {

    private fun models(
        e2b: AiModelStatus = AiModelStatus.NotInstalled,
        live: AiModelStatus = AiModelStatus.Installed(inUse = true),
        speech: Map<SpeechModelId, SpeechModelStatus> = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed),
        liveFiles: ModelFileState = ModelFileState.Intact
    ) = ModelsUiState(
        speech = SpeechModelId.entries.map { SpeechModelState(it, speech[it] ?: SpeechModelStatus.NotInstalled) },
        ai = LlmModelVariant.entries.map { variant ->
            AiModelState(variant, if (variant.writesLiveNotes) live else e2b)
        },
        liveModelFiles = liveFiles
    )

    @Test
    fun `the switch is shown with E2B on the phone`() {
        assertTrue(models(e2b = AiModelStatus.Installed(inUse = false)).finishingModelOnPhone)
    }

    @Test
    fun `the switch is hidden while E2B is not all there`() {
        assertFalse(models(e2b = AiModelStatus.NotInstalled).finishingModelOnPhone)
        assertFalse(models(e2b = AiModelStatus.Downloading(percent = 40)).finishingModelOnPhone)
        assertFalse(models(e2b = AiModelStatus.Paused).finishingModelOnPhone)
        assertFalse(models(e2b = AiModelStatus.Failed(DownloadFailure.NO_CONNECTION)).finishingModelOnPhone)
    }

    @Test
    fun `live models on the phone are no reason for the switch`() {
        assertFalse(models(e2b = AiModelStatus.NotInstalled, live = AiModelStatus.Installed(inUse = false)).finishingModelOnPhone)
    }

    // Speech models

    @Test
    fun `Parakeet v3 alone can't be deleted`() {
        assertFalse(models().mayDelete(PARAKEET_V3))
    }

    @Test
    fun `a whole Parakeet v3 can't be deleted, also while another speech model is on the phone`() {
        // Without it no recording in English or German can start (the settings audit's V3-GUARD).
        val both = models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed, COHERE_TRANSCRIBE to SpeechModelStatus.Installed))

        assertFalse(ModelsUiState.OFFERS_LIVE_MODEL_ACTIONS_WHEN_HEALTHY)
        assertFalse(both.mayDelete(PARAKEET_V3))
        assertTrue(both.mayDelete(COHERE_TRANSCRIBE))
    }

    @Test
    fun `Parakeet v3 is offered again only when its files are damaged or missing`() {
        assertFalse(models().repairOffered)
        assertTrue(models(liveFiles = ModelFileState.Damaged).repairOffered)
        assertTrue(models(liveFiles = ModelFileState.Missing).repairOffered)
        // Not known to be damaged: reading it again may work.
        assertFalse(models(liveFiles = ModelFileState.Unreadable).repairOffered)
        // A download that runs or stopped has its own buttons.
        assertFalse(models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Paused), liveFiles = ModelFileState.Damaged).repairOffered)
        assertFalse(models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Downloading(10)), liveFiles = ModelFileState.Damaged).repairOffered)
    }

    @Test
    fun `deleting the Malayalam model says the power key and Record as fall back to English or German`() {
        val withMalayalam = models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed, INDICCONFORMER_ML to SpeechModelStatus.Installed))

        assertEquals(DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN, withMalayalam.deleteConsequence(INDICCONFORMER_ML))
        assertEquals(DeleteConsequence.NONE, withMalayalam.deleteConsequence(COHERE_TRANSCRIBE))
        // What a paused download of it left costs nothing.
        assertEquals(
            DeleteConsequence.NONE,
            models(speech = mapOf(INDICCONFORMER_ML to SpeechModelStatus.Paused)).deleteConsequence(INDICCONFORMER_ML)
        )
    }

    @Test
    fun `deleting the only model for live notes says new recordings get none`() {
        val oneLive = ModelsUiState(
            speech = emptyList(),
            ai = LlmModelVariant.entries.map { variant ->
                AiModelState(
                    variant,
                    when (variant) {
                        LlmModelVariant.Q8_0 -> AiModelStatus.Installed(inUse = true)
                        LlmModelVariant.GEMMA4_E2B_Q4_0 -> AiModelStatus.Installed(inUse = false)
                        else -> AiModelStatus.NotInstalled
                    }
                )
            }
        )
        val twoLive = ModelsUiState(
            speech = emptyList(),
            ai = LlmModelVariant.entries.map { variant ->
                AiModelState(variant, if (variant.writesLiveNotes) AiModelStatus.Installed(inUse = variant == LlmModelVariant.Q8_0) else AiModelStatus.NotInstalled)
            }
        )

        assertEquals(DeleteConsequence.NO_NOTES, oneLive.deleteConsequence(LlmModelVariant.Q8_0))
        // Gemma 4 E2B writes no live notes: deleting it leaves the notes as they are.
        assertEquals(DeleteConsequence.NONE, oneLive.deleteConsequence(LlmModelVariant.GEMMA4_E2B_Q4_0))
        assertEquals(DeleteConsequence.NONE, twoLive.deleteConsequence(LlmModelVariant.Q8_0))
    }

    @Test
    fun `Gemma 4 E2B alone is no model for live notes`() {
        assertTrue(models().liveNotesModelOnPhone)
        assertFalse(models(live = AiModelStatus.NotInstalled, e2b = AiModelStatus.Installed(inUse = false)).liveNotesModelOnPhone)
        assertFalse(models(live = AiModelStatus.Paused).liveNotesModelOnPhone)
    }

    @Test
    fun `a model only partly there doesn't count as another one for Parakeet v3`() {
        val partly = models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed, PARAKEET_V2_EN to SpeechModelStatus.Paused))

        assertFalse(partly.mayDelete(PARAKEET_V3))
        // What a paused download left can go.
        assertTrue(partly.mayDelete(PARAKEET_V2_EN))
    }

    @Test
    fun `the last speech model on the phone stays, whichever it is`() {
        // Parakeet v3 was deleted while Parakeet v2 was there: v2 is the last one now.
        val v2Alone = models(speech = mapOf(PARAKEET_V2_EN to SpeechModelStatus.Installed))
        val twoWithoutV3 = models(speech = mapOf(PARAKEET_V2_EN to SpeechModelStatus.Installed, COHERE_TRANSCRIBE to SpeechModelStatus.Installed))

        assertFalse(v2Alone.mayDelete(PARAKEET_V2_EN))
        assertTrue(twoWithoutV3.mayDelete(PARAKEET_V2_EN))
        assertTrue(twoWithoutV3.mayDelete(COHERE_TRANSCRIBE))
    }

    @Test
    fun `what a paused download left can always go`() {
        assertTrue(models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Paused)).mayDelete(PARAKEET_V3))
    }

    @Test
    fun `deleting the model set for after Stop switches it off, and only that one`() {
        val cohere = AppSettings(transcribeAfterStop = COHERE_TRANSCRIBE)

        assertEquals(null, cohere.withoutSpeechModel(COHERE_TRANSCRIBE).transcribeAfterStop)
        assertEquals(cohere, cohere.withoutSpeechModel(PARAKEET_V2_EN))
        assertEquals(cohere, cohere.withoutSpeechModel(PARAKEET_V3))
    }

    @Test
    fun `a model that is not there or downloads has nothing to delete`() {
        val downloading = models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed, COHERE_TRANSCRIBE to SpeechModelStatus.Downloading(30)))

        assertFalse(downloading.mayDelete(COHERE_TRANSCRIBE))
        assertFalse(downloading.mayDelete(PARAKEET_V2_EN))
        assertTrue(downloading.speechDownloading)
        assertFalse(models().speechDownloading)
    }

    @Test
    fun `after Stop offers the better models on the phone, never the live one`() {
        assertEquals(emptyList<SpeechModelId>(), models().afterStopChoices)
        assertEquals(
            listOf(PARAKEET_V2_EN, COHERE_TRANSCRIBE),
            models(
                speech = mapOf(
                    PARAKEET_V3 to SpeechModelStatus.Installed,
                    PARAKEET_V2_EN to SpeechModelStatus.Installed,
                    COHERE_TRANSCRIBE to SpeechModelStatus.Installed
                )
            ).afterStopChoices
        )
        assertEquals(
            emptyList<SpeechModelId>(),
            models(speech = mapOf(COHERE_TRANSCRIBE to SpeechModelStatus.Paused)).afterStopChoices
        )
    }

    @Test
    fun `the Malayalam model is no better transcript after Stop, and alone leaves the setting out`() {
        val all = models(speech = SpeechModelId.entries.associateWith { SpeechModelStatus.Installed })
        val malayalamOnly = models(speech = mapOf(PARAKEET_V3 to SpeechModelStatus.Installed, INDICCONFORMER_ML to SpeechModelStatus.Installed))

        assertEquals(listOf(PARAKEET_V2_EN, COHERE_TRANSCRIBE), all.afterStopChoices)
        assertEquals(emptyList<SpeechModelId>(), malayalamOnly.afterStopChoices)
        // Like any other, it may go while Parakeet v3 stays.
        assertTrue(malayalamOnly.mayDelete(INDICCONFORMER_ML))
    }
}
