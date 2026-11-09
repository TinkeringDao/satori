package com.satori.service

import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.PauseReason
import com.satori.domain.model.SpeechModelId
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNotificationTextTest {

    private val progress = DownloadProgress(276_000_000L, 808_000_000L, 34)
    private val running = DownloadState.Downloading(progress)

    private fun speech(state: DownloadState, model: SpeechModelId? = SpeechModelId.PARAKEET_V3) =
        DownloadNotificationText.of(state, model, DownloadState.Idle, null)

    private fun ai(state: DownloadState, variant: LlmModelVariant? = LlmModelVariant.Q8_0) =
        DownloadNotificationText.of(DownloadState.Idle, null, state, variant)

    @Test
    fun `nothing that keeps the service, no notification`() {
        assertNull(DownloadNotificationText.of(DownloadState.Idle, null, DownloadState.Idle, null))
        assertNull(speech(DownloadState.Completed))
        assertNull(speech(DownloadState.Error("cut", DownloadFailure.NO_CONNECTION)))
        // Pause and a refused connection wait for a tap, and keep no service.
        assertNull(speech(DownloadState.Paused(PauseReason.USER, progress)))
        assertNull(speech(DownloadState.Paused(PauseReason.BLOCKED, progress)))
    }

    @Test
    fun `it says which part downloads, in the first run's and Settings' words`() {
        assertEquals(R.string.g_dl_speech, speech(running)?.title)
        assertEquals(R.string.g_dl_malayalam, speech(running, SpeechModelId.INDICCONFORMER_ML)?.title)
        assertEquals(R.string.g_dl_after_stop, speech(running, SpeechModelId.PARAKEET_V2_EN)?.title)
        assertEquals(R.string.g_dl_after_stop, speech(running, SpeechModelId.COHERE_TRANSCRIBE)?.title)
        assertEquals(R.string.g_dl_ai_model, ai(running)?.title)
        assertEquals(R.string.g_dl_ai_model, ai(running, LlmModelVariant.IQ4_NL)?.title)
        assertEquals(R.string.g_dl_bigger_model, ai(running, LlmModelVariant.GEMMA4_E2B_Q4_0)?.title)
    }

    @Test
    fun `a running download says its percent, with a bar of it`() {
        assertEquals(DownloadNotificationText(R.string.g_dl_speech, R.string.g_dl_percent, 34, listOf(34)), speech(running))
        // Before its size is known the bar runs without a percent.
        assertEquals(null, speech(DownloadState.Downloading(DownloadProgress(0, 0, 0)))?.percent)
    }

    @Test
    fun `a file being checked says so, with a bar that runs`() {
        assertEquals(DownloadNotificationText(R.string.g_dl_speech, R.string.g_dl_checking, null), speech(DownloadState.Verifying(progress)))
    }

    @Test
    fun `a held download says why, its bar where it stopped`() {
        val rows = mapOf(
            PauseReason.RECORDING to R.string.g_dl_paused_recording,
            PauseReason.NO_NETWORK to R.string.g_dl_no_connection,
            PauseReason.WIFI_ONLY to R.string.g_dl_waiting_wifi,
            PauseReason.SERVER to R.string.g_dl_server,
            PauseReason.NOT_ENOUGH_STORAGE to R.string.g_dl_space
        )
        for ((reason, text) in rows) {
            assertEquals(reason.name, DownloadNotificationText(R.string.g_dl_ai_model, text, 34), ai(DownloadState.Paused(reason, progress)))
        }
        assertNull(ai(DownloadState.Paused(PauseReason.RECORDING))?.percent)
    }

    @Test
    fun `a download that runs is shown before one that is held, a speech model's first`() {
        val heldSpeech = DownloadState.Paused(PauseReason.NO_NETWORK, progress)

        assertEquals(R.string.g_dl_ai_model, DownloadNotificationText.of(heldSpeech, SpeechModelId.INDICCONFORMER_ML, running, LlmModelVariant.Q8_0)?.title)
        assertEquals(R.string.g_dl_malayalam, DownloadNotificationText.of(running, SpeechModelId.INDICCONFORMER_ML, running, LlmModelVariant.Q8_0)?.title)
        assertEquals(R.string.g_dl_malayalam, DownloadNotificationText.of(heldSpeech, SpeechModelId.INDICCONFORMER_ML, DownloadState.Paused(PauseReason.SERVER), LlmModelVariant.Q8_0)?.title)
    }

    @Test
    fun `the reader's download goes by the AI model's name, after the AI model's, and keeps the service as they do`() {
        val reader = DownloadNotificationText.of(DownloadState.Idle, null, DownloadState.Idle, null, reader = running)
        assertEquals(R.string.g_dl_ai_model, reader?.title)
        assertEquals(R.string.g_dl_percent, reader?.text)

        // A held AI model gives way to the reader that runs; the reader's own hold says why.
        val heldAi = DownloadState.Paused(PauseReason.WIFI_ONLY, progress)
        assertEquals(R.string.g_dl_percent, DownloadNotificationText.of(DownloadState.Idle, null, heldAi, LlmModelVariant.Q8_0, reader = running)?.text)
        assertEquals(R.string.g_dl_waiting_wifi, DownloadNotificationText.of(DownloadState.Idle, null, DownloadState.Idle, null, reader = heldAi)?.text)
        assertNull(DownloadNotificationText.of(DownloadState.Idle, null, DownloadState.Idle, null, reader = DownloadState.Completed))
    }
}
