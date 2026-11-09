package com.satori.data.service

import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadServiceRulesTest {

    private val progress = DownloadProgress(276_000_000L, 808_000_000L, 34)

    @Test
    fun `a file being checked runs, so it keeps the service and its wake lock`() {
        assertTrue(DownloadServiceRules.isRunning(DownloadState.Downloading(progress)))
        assertTrue(DownloadServiceRules.isRunning(DownloadState.Verifying(progress)))
        assertTrue(DownloadServiceRules.keepsServiceRunning(DownloadState.Verifying(progress)))
    }

    @Test
    fun `a download held for what clears by itself keeps the service, one that waits for a tap doesn't`() {
        val keeps = PauseReason.entries.filter { DownloadServiceRules.keepsServiceRunning(DownloadState.Paused(it, progress)) }

        assertEquals(
            listOf(PauseReason.RECORDING, PauseReason.NO_NETWORK, PauseReason.WIFI_ONLY, PauseReason.NOT_ENOUGH_STORAGE, PauseReason.SERVER),
            keeps
        )
        assertFalse(DownloadServiceRules.isRunning(DownloadState.Paused(PauseReason.RECORDING, progress)))
    }

    @Test
    fun `nothing else keeps it`() {
        for (state in listOf(DownloadState.Idle, DownloadState.Completed, DownloadState.Error("cut", DownloadFailure.NO_CONNECTION))) {
            assertFalse(state.toString(), DownloadServiceRules.keepsServiceRunning(state))
        }
    }

    @Test
    fun `where a download stands, when its state says`() {
        assertEquals(progress, DownloadServiceRules.progressOf(DownloadState.Downloading(progress)))
        assertEquals(progress, DownloadServiceRules.progressOf(DownloadState.Verifying(progress)))
        assertEquals(progress, DownloadServiceRules.progressOf(DownloadState.Paused(PauseReason.USER, progress)))
        assertNull(DownloadServiceRules.progressOf(DownloadState.Paused(PauseReason.USER)))
        assertNull(DownloadServiceRules.progressOf(DownloadState.Completed))
    }
}
