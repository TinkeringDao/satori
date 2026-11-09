package com.satori.presentation.settings

import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AI notes row works with a model for live notes on the phone; with only Gemma 4 E2B, which
 * Advanced lists as on the phone, it says that one for live notes is missing beside it.
 */
class AiNotesModelsTest {

    @Test
    fun `a model for live notes makes the row work, with or without E2B`() {
        assertEquals(AiNotesModels.READY, AiNotesModels.of(setOf(LlmModelVariant.Q8_0)))
        assertEquals(AiNotesModels.READY, AiNotesModels.of(setOf(LlmModelVariant.IQ4_NL, LlmModelVariant.GEMMA4_E2B_Q4_0)))
    }

    @Test
    fun `E2B alone says a model for live notes is missing beside it`() {
        assertEquals(AiNotesModels.MISSING_BESIDE_FINISHING_MODEL, AiNotesModels.of(setOf(LlmModelVariant.GEMMA4_E2B_Q4_0)))
    }

    @Test
    fun `no model at all is missing`() {
        assertEquals(AiNotesModels.MISSING, AiNotesModels.of(emptySet()))
    }

    @Test
    fun `a model for live notes on its way leaves the switch as set, with nothing to get`() {
        val q8 = LlmModelVariant.Q8_0
        val progress = DownloadProgress(600_000_000L, 1_069_306_368L, 56)
        // The first run's download after the first recording: running, checked, held by a
        // recording, waiting for Wi-Fi.
        for (state in listOf(
            DownloadState.Downloading(progress),
            DownloadState.Verifying(progress),
            DownloadState.Paused(PauseReason.RECORDING, progress),
            DownloadState.Paused(PauseReason.WIFI_ONLY, progress)
        )) {
            assertEquals("$state", AiNotesModels.ON_ITS_WAY, AiNotesModels.of(emptySet(), state, q8))
        }
        // Paused by the owner, ended, failed, or Gemma 4 E2B, which writes no live notes: missing.
        assertEquals(AiNotesModels.MISSING, AiNotesModels.of(emptySet(), DownloadState.Paused(PauseReason.USER, progress), q8))
        assertEquals(AiNotesModels.MISSING, AiNotesModels.of(emptySet(), DownloadState.Error("failed"), q8))
        assertEquals(AiNotesModels.MISSING, AiNotesModels.of(emptySet(), DownloadState.Downloading(progress), LlmModelVariant.GEMMA4_E2B_Q4_0))
        // Once one is on the phone, it is ready.
        assertEquals(AiNotesModels.READY, AiNotesModels.of(setOf(q8), DownloadState.Downloading(progress), LlmModelVariant.GEMMA4_E2B_Q4_0))
    }
}
