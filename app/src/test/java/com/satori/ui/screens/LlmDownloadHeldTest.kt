package com.satori.ui.screens

import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The AI model's own screen (Settings' "Get the AI model") while its download is checked or held:
 * its bar, and one line of why it waits, in the download notification's words; never the layout of
 * one not started, with its Download.
 */
class LlmDownloadHeldTest {

    private val progress = DownloadProgress(600_000_000L, 1_069_306_368L, 56)

    @Test
    fun `a download being checked or held says so under its bar`() {
        assertEquals(R.string.g_dl_checking, llmHeldLine(DownloadState.Verifying(progress)))
        val held = mapOf(
            PauseReason.RECORDING to R.string.g_dl_paused_recording,
            PauseReason.NO_NETWORK to R.string.g_dl_no_connection,
            PauseReason.WIFI_ONLY to R.string.g_dl_waiting_wifi,
            PauseReason.SERVER to R.string.g_dl_server,
            PauseReason.NOT_ENOUGH_STORAGE to R.string.g_dl_space,
            PauseReason.BLOCKED to R.string.g_fr_err_blocked_title,
            PauseReason.USER to R.string.g_fr_paused
        )
        assertEquals(PauseReason.entries.toSet(), held.keys)
        for ((reason, line) in held) assertEquals("$reason", line, llmHeldLine(DownloadState.Paused(reason, progress)))
    }

    @Test
    fun `a download not started, running, done or failed has no such line`() {
        assertNull(llmHeldLine(DownloadState.Idle))
        assertNull(llmHeldLine(DownloadState.Downloading(progress)))
        assertNull(llmHeldLine(DownloadState.Completed))
        assertNull(llmHeldLine(DownloadState.Error("failed")))
    }
}
