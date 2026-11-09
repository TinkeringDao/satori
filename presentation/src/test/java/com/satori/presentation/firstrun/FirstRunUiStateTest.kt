package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.presentation.firstrun.FirstRunFooter.ALLOW_MICROPHONE
import com.satori.presentation.firstrun.FirstRunFooter.DOWNLOAD
import com.satori.presentation.firstrun.FirstRunFooter.OPEN_APP_SETTINGS
import com.satori.presentation.firstrun.FirstRunFooter.OPEN_STORAGE_SETTINGS
import com.satori.presentation.firstrun.FirstRunFooter.RECORD
import com.satori.presentation.firstrun.FirstRunFooter.TRY_AGAIN
import com.satori.presentation.firstrun.MicrophoneState.ALLOWED
import com.satori.presentation.firstrun.MicrophoneState.NOT_ALLOWED
import com.satori.presentation.firstrun.MicrophoneState.REFUSED_FOR_GOOD
import com.satori.presentation.firstrun.MicrophoneState.REFUSED_ONCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunUiStateTest {

    private val sizes = SetupSizes(speechBytes = 670_478_772L, malayalamBytes = 137_566_803L, aiModelBytes = 1_069_306_368L)
    private val getReady = FirstRunUiState(sizes = sizes)
    private val progress = SetupProgress(FirstRunPart.SPEECH, bytesDone = 276_000_000L, bytesTotal = 808_045_575L, minutesLeft = 2)
    private val downloading = getReady.copy(stage = FirstRunStage.DOWNLOADING, progress = progress)
    private val ready = downloading.copy(stage = FirstRunStage.READY, microphone = ALLOWED)
    private val space = FirstRunProblem.NotEnoughSpace(SpaceShortfall(neededBytes = 1_108_045_575L, freeBytes = 600_000_000L))

    @Test
    fun `get ready downloads speech and Malayalam, or speech alone with the switch off`() {
        assertEquals(FirstRunScreen.GET_READY, getReady.screen)
        assertEquals(DOWNLOAD, getReady.footer)
        assertEquals(808_045_575L, getReady.downloadBytes)
        assertEquals(670_478_772L, getReady.copy(malayalam = false).downloadBytes)
    }

    @Test
    fun `on mobile data get ready offers to wait for Wi-Fi, and the button stays the consent`() {
        assertNull(getReady.quiet)
        assertEquals(FirstRunQuiet.WAIT_FOR_WIFI, getReady.copy(onMobileData = true).quiet)
        assertEquals(DOWNLOAD, getReady.copy(onMobileData = true).footer)
    }

    @Test
    fun `short of space get ready offers the storage settings and nothing else`() {
        val short = getReady.copy(problem = space, onMobileData = true)

        assertEquals(OPEN_STORAGE_SETTINGS, short.footer)
        assertNull(short.quiet)
    }

    @Test
    fun `while downloading the footer is the microphone until it is allowed, then Record, off`() {
        // microphone, footer, enabled
        val rows = listOf(
            Triple(NOT_ALLOWED, ALLOW_MICROPHONE, true),
            Triple(REFUSED_ONCE, ALLOW_MICROPHONE, true),
            Triple(REFUSED_FOR_GOOD, OPEN_APP_SETTINGS, true),
            Triple(ALLOWED, RECORD, false)
        )
        for ((microphone, footer, enabled) in rows) {
            val state = downloading.copy(microphone = microphone)
            assertEquals("$microphone", footer, state.footer)
            assertEquals("$microphone", enabled, state.primaryEnabled)
        }
    }

    @Test
    fun `a problem that needs him beats the microphone, the others leave it`() {
        // problem, footer with the microphone not allowed
        val rows = listOf(
            FirstRunProblem.Blocked to TRY_AGAIN,
            FirstRunProblem.Damaged(again = true) to TRY_AGAIN,
            space to OPEN_STORAGE_SETTINGS,
            FirstRunProblem.Damaged(again = false) to ALLOW_MICROPHONE,
            FirstRunProblem.NoConnection to ALLOW_MICROPHONE,
            FirstRunProblem.WaitingForWifi to ALLOW_MICROPHONE,
            FirstRunProblem.ServerNotAnswering to ALLOW_MICROPHONE
        )
        for ((problem, footer) in rows) {
            assertEquals("$problem", footer, downloading.copy(problem = problem).footer)
        }
        // Also with the microphone refused for good.
        for ((problem, footer) in rows.filter { it.first.needsTap }) {
            assertEquals("$problem", footer, downloading.copy(problem = problem, microphone = REFUSED_FOR_GOOD).footer)
        }
        assertEquals(OPEN_APP_SETTINGS, downloading.copy(problem = FirstRunProblem.NoConnection, microphone = REFUSED_FOR_GOOD).footer)
    }

    @Test
    fun `ready records with the microphone allowed, and asks for it first otherwise`() {
        assertEquals(RECORD, ready.footer)
        assertTrue(ready.primaryEnabled)
        assertEquals(ALLOW_MICROPHONE, ready.copy(microphone = NOT_ALLOWED).footer)
        assertEquals(OPEN_APP_SETTINGS, ready.copy(microphone = REFUSED_FOR_GOOD).footer)
        assertEquals(FirstRunQuiet.LOOK_AROUND, ready.quiet)
        assertEquals(FirstRunScreen.GETTING_READY, ready.screen)
    }

    @Test
    fun `ready and a pause show no problem`() {
        assertNull(ready.copy(problem = FirstRunProblem.Blocked).shownProblem)
        val paused = downloading.copy(progress = progress.copy(paused = true), problem = FirstRunProblem.NoConnection)
        assertTrue(paused.paused)
        assertNull(paused.shownProblem)
        assertEquals(FirstRunQuiet.CONTINUE, paused.quiet)
    }

    @Test
    fun `the quiet control follows the download`() {
        assertEquals(FirstRunQuiet.PAUSE, downloading.quiet)
        assertEquals(FirstRunQuiet.TRY_NOW, downloading.copy(problem = FirstRunProblem.NoConnection).quiet)
        // While a file is checked nothing can be paused, and the line says why the percent holds.
        assertNull(downloading.copy(progress = progress.copy(checking = true)).quiet)
        for (problem in listOf(FirstRunProblem.Blocked, FirstRunProblem.WaitingForWifi, FirstRunProblem.ServerNotAnswering, space)) {
            assertNull("$problem", downloading.copy(problem = problem).quiet)
        }
    }

    @Test
    fun `use mobile data shows only while the setup waits for Wi-Fi`() {
        assertTrue(downloading.copy(problem = FirstRunProblem.WaitingForWifi).offersMobileData)
        assertFalse(downloading.offersMobileData)
        assertFalse(getReady.copy(onMobileData = true).offersMobileData)
    }

    @Test
    fun `the leave line shows only while Satori really keeps going`() {
        assertTrue(downloading.showsLeaveLine)
        assertTrue(downloading.copy(progress = progress.copy(checking = true)).showsLeaveLine)
        for (problem in listOf(FirstRunProblem.NoConnection, FirstRunProblem.ServerNotAnswering, FirstRunProblem.Damaged(again = false))) {
            assertTrue("$problem", downloading.copy(problem = problem).showsLeaveLine)
        }
        for (problem in listOf(FirstRunProblem.Blocked, FirstRunProblem.WaitingForWifi, FirstRunProblem.Damaged(again = true), space)) {
            assertFalse("$problem", downloading.copy(problem = problem).showsLeaveLine)
        }
        assertFalse(downloading.copy(progress = progress.copy(paused = true)).showsLeaveLine)
        assertFalse(getReady.showsLeaveLine)
        assertFalse(ready.showsLeaveLine)
    }

    @Test
    fun `microphone allowed is said on getting ready, not on ready`() {
        assertTrue(downloading.copy(microphone = ALLOWED).showsMicrophoneAllowed)
        assertFalse(downloading.showsMicrophoneAllowed)
        assertFalse(ready.showsMicrophoneAllowed)
    }

    @Test
    fun `the screen stays on while the wait is watched, and only then`() {
        assertTrue(downloading.keepScreenOn)
        assertTrue(downloading.copy(problem = FirstRunProblem.NoConnection).keepScreenOn)
        assertFalse(downloading.copy(progress = progress.copy(paused = true)).keepScreenOn)
        assertFalse(downloading.copy(problem = FirstRunProblem.Blocked).keepScreenOn)
        assertFalse(getReady.keepScreenOn)
        assertFalse(ready.keepScreenOn)
    }

    @Test
    fun `three dots, one lit for each step`() {
        assertEquals(listOf(0, 1, 2), listOf(getReady, downloading, ready).map { it.page })
    }

    @Test
    fun `a primary that changed by itself ignores taps for 700 ms`() {
        val atMs = 50_000L
        val nowReady = ready.withTapShield(previous = downloading.copy(microphone = ALLOWED), nowMs = atMs, byTap = false)

        assertEquals(atMs + FirstRunFlow.TAP_SHIELD_MS, nowReady.tapsFromMs)
        assertFalse(nowReady.acceptsTaps(atMs + 300))
        assertTrue(nowReady.acceptsTaps(atMs + 900))
    }

    @Test
    fun `a change the tap made, or one the primary doesn't see, raises no shield`() {
        val atMs = 50_000L
        // Download tapped: Getting ready follows the tap.
        assertEquals(0L, downloading.withTapShield(previous = getReady, nowMs = atMs, byTap = true).tapsFromMs)
        // More bytes: the same primary.
        val moved = downloading.copy(progress = progress.copy(bytesDone = 300_000_000L))
        assertEquals(0L, moved.withTapShield(previous = downloading, nowMs = atMs, byTap = false).tapsFromMs)
        // A shield up stays up through such a change.
        val shielded = downloading.copy(tapsFromMs = atMs + 700)
        assertEquals(atMs + 700, moved.withTapShield(previous = shielded, nowMs = atMs + 100, byTap = false).tapsFromMs)
    }

    @Test
    fun `the microphone after Android's window`() {
        val rows = listOf(
            Answer(NOT_ALLOWED, granted = true, canAskAgain = false, after = ALLOWED),
            // A first refusal can be asked again, also when the window was only dismissed.
            Answer(NOT_ALLOWED, granted = false, canAskAgain = false, after = REFUSED_ONCE),
            Answer(NOT_ALLOWED, granted = false, canAskAgain = true, after = REFUSED_ONCE),
            Answer(REFUSED_ONCE, granted = false, canAskAgain = true, after = REFUSED_ONCE),
            Answer(REFUSED_ONCE, granted = false, canAskAgain = false, after = REFUSED_FOR_GOOD),
            Answer(REFUSED_ONCE, granted = true, canAskAgain = false, after = ALLOWED),
            Answer(REFUSED_FOR_GOOD, granted = false, canAskAgain = false, after = REFUSED_FOR_GOOD)
        )
        for (row in rows) {
            assertEquals(row.toString(), row.after, MicrophoneState.answered(row.before, row.granted, row.canAskAgain))
        }
    }

    @Test
    fun `the microphone read again on a return to the screen`() {
        assertEquals(ALLOWED, MicrophoneState.reread(REFUSED_FOR_GOOD, granted = true))
        assertEquals(REFUSED_FOR_GOOD, MicrophoneState.reread(REFUSED_FOR_GOOD, granted = false))
        assertEquals(REFUSED_ONCE, MicrophoneState.reread(REFUSED_ONCE, granted = false))
        // "Only this time" ran out, or it was taken back in Settings.
        assertEquals(NOT_ALLOWED, MicrophoneState.reread(ALLOWED, granted = false))
    }

    @Test
    fun `the bar counts the setup's bytes`() {
        assertEquals(34, progress.percent)
        assertEquals(532_045_575L, progress.bytesLeft)
        assertEquals(0, progress.copy(bytesTotal = 0L).percent)
        assertEquals(100, progress.copy(bytesDone = 900_000_000L).percent)
    }

    private data class Answer(val before: MicrophoneState, val granted: Boolean, val canAskAgain: Boolean, val after: MicrophoneState)
}
