package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripStateTest {

    private val running = StripState.Download(FirstRunPart.AI_MODEL, bytesDone = 246_000_000L, bytesTotal = 1_069_306_368L, minutesLeft = 3)
    private val needsHim = running.copy(hold = StripHold.WAITING_FOR_WIFI)
    private val firstNotes = StripState.FirstNotes("s1")
    private val nudge = StripState.Nudge(NudgeId.NOTIFY_PING)

    @Test
    fun `what needs him comes first, then a download, then the confirmation, then a nudge`() {
        assertEquals(needsHim, StripState.first(needsHim, malayalamFailed = true, firstNotes, nudge))
        assertEquals(StripState.MalayalamFailed, StripState.first(running, malayalamFailed = true, firstNotes, nudge))
        assertEquals(running, StripState.first(running, malayalamFailed = false, firstNotes, nudge))
        assertEquals(firstNotes, StripState.first(null, malayalamFailed = false, firstNotes, nudge))
        assertEquals(nudge, StripState.first(null, malayalamFailed = false, null, nudge))
        assertNull(StripState.first(null, malayalamFailed = false, null, null))
    }

    @Test
    fun `a nudge put first comes before the confirmation, never before what needs him or a download`() {
        val missed = StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true)
        assertEquals(missed, StripState.first(null, malayalamFailed = false, firstNotes, missed, nudgeFirst = true))
        assertEquals(needsHim, StripState.first(needsHim, malayalamFailed = false, firstNotes, missed, nudgeFirst = true))
        assertEquals(StripState.MalayalamFailed, StripState.first(null, malayalamFailed = true, firstNotes, missed, nudgeFirst = true))
        assertEquals(running, StripState.first(running, malayalamFailed = false, firstNotes, missed, nudgeFirst = true))
        // Without a nudge to put first, the confirmation shows.
        assertEquals(firstNotes, StripState.first(null, malayalamFailed = false, firstNotes, null, nudgeFirst = true))
    }

    @Test
    fun `a download held for a recording or a connection is progress, not a call for him`() {
        for (hold in listOf(StripHold.RECORDING, StripHold.NO_CONNECTION, StripHold.SERVER)) {
            assertFalse("$hold", hold.needsHim)
            assertEquals(StripState.MalayalamFailed, StripState.first(running.copy(hold = hold), malayalamFailed = true, null, null))
        }
        for (hold in listOf(StripHold.WAITING_FOR_WIFI, StripHold.BLOCKED, StripHold.NOT_ENOUGH_SPACE, StripHold.DAMAGED)) {
            assertTrue("$hold", hold.needsHim)
        }
    }

    @Test
    fun `the strip's bar counts its one download`() {
        assertEquals(23, running.percent)
        assertEquals(0, running.copy(bytesTotal = 0L).percent)
    }
}

/** The strip's download from the coordinator's (FirstRunSnapshot.later): running, checked, held and why, done. */
class StripDownloadTest {

    private val aiBytes = 1_069_306_368L
    private val plan = FirstRunPlan.initial(speechBytes = 670_478_772L, malayalamBytes = null, aiModelBytes = aiBytes)
    private val progress = DownloadProgress(246_000_000L, aiBytes, 23)

    private fun later(state: DownloadState, minutesLeft: Int? = 3, resumedFrom: Long? = null) =
        FirstRunProgress(FirstRunPart.AI_MODEL, state, bytesDone = 246_000_000L, bytesTotal = aiBytes, minutesLeft = minutesLeft, resumedFromBytes = resumedFrom)

    private fun hold(state: DownloadState, plan: FirstRunPlan = this.plan): StripHold? =
        checkNotNull(StripState.download(later(state), plan, space = null)).hold

    @Test
    fun `a running download shows its bar and time left, and says so while a file is checked`() {
        val running = checkNotNull(StripState.download(later(DownloadState.Downloading(progress)), plan, space = null))
        assertEquals(StripState.Download(FirstRunPart.AI_MODEL, 246_000_000L, aiBytes, minutesLeft = 3), running)

        val checking = checkNotNull(StripState.download(later(DownloadState.Verifying(progress), minutesLeft = null), plan, space = null))
        assertTrue(checking.checking)
        assertNull(checking.hold)
    }

    @Test
    fun `each hold says why, as Getting ready's state of the same name does`() {
        val paused = mapOf(
            PauseReason.USER to StripHold.PAUSED,
            PauseReason.RECORDING to StripHold.RECORDING,
            PauseReason.NO_NETWORK to StripHold.NO_CONNECTION,
            PauseReason.WIFI_ONLY to StripHold.WAITING_FOR_WIFI,
            PauseReason.BLOCKED to StripHold.BLOCKED,
            PauseReason.NOT_ENOUGH_STORAGE to StripHold.NOT_ENOUGH_SPACE,
            PauseReason.SERVER to StripHold.SERVER
        )
        assertEquals(PauseReason.entries.toSet(), paused.keys)
        for ((reason, expected) in paused) assertEquals("$reason", expected, hold(DownloadState.Paused(reason, progress)))

        val failed = mapOf(
            DownloadFailure.NO_CONNECTION to StripHold.NO_CONNECTION,
            DownloadFailure.NOT_ENOUGH_STORAGE to StripHold.NOT_ENOUGH_SPACE,
            DownloadFailure.SERVER to StripHold.SERVER,
            DownloadFailure.BLOCKED to StripHold.BLOCKED,
            DownloadFailure.OTHER to StripHold.DAMAGED_ONCE
        )
        assertEquals(DownloadFailure.entries.toSet(), failed.keys)
        for ((reason, expected) in failed) assertEquals("$reason", expected, hold(DownloadState.Error("failed", reason)))
    }

    @Test
    fun `a file damaged twice waits for Try again, and asks him for it`() {
        val twice = plan.failed(FirstRunPart.AI_MODEL, DownloadFailure.OTHER).failed(FirstRunPart.AI_MODEL, DownloadFailure.OTHER)
        assertEquals(StripHold.DAMAGED, hold(DownloadState.Error("damaged", DownloadFailure.OTHER), twice))
        assertEquals(StripHold.DAMAGED, hold(DownloadState.Idle, twice))
        assertTrue(StripHold.DAMAGED.needsHim)
        assertFalse(StripHold.DAMAGED_ONCE.needsHim)
    }

    @Test
    fun `a held download shows no time left and no resume, and the space only when it is short of it`() {
        val space = SpaceShortfall(neededBytes = 1_400_000_000L, freeBytes = 900_000_000L)
        val noRoom = checkNotNull(StripState.download(later(DownloadState.Paused(PauseReason.NOT_ENOUGH_STORAGE), resumedFrom = 12L), plan, space))
        assertNull(noRoom.minutesLeft)
        assertNull(noRoom.resumedFromBytes)
        assertEquals(space, noRoom.space)

        val noWifi = checkNotNull(StripState.download(later(DownloadState.Paused(PauseReason.WIFI_ONLY)), plan, space))
        assertNull(noWifi.space)
        assertEquals(aiBytes - 246_000_000L, noWifi.bytesLeft)
    }

    @Test
    fun `no download, or one done, shows nothing`() {
        assertNull(StripState.download(null, plan, space = null))
        assertNull(StripState.download(later(DownloadState.Completed), plan, space = null))
    }

    @Test
    fun `the strip's face is its download's part and hold, not its numbers`() {
        val running = StripState.Download(FirstRunPart.AI_MODEL, 1L, 10L, minutesLeft = 3)
        assertEquals(running.face, running.copy(bytesDone = 5L, minutesLeft = 1, checking = true).face)
        assertNotEquals(running.face, running.copy(hold = StripHold.RECORDING).face)
        assertNotEquals(running.face, running.copy(part = FirstRunPart.BIGGER_AI_MODEL).face)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), StripState.Nudge(NudgeId.NOTIFY_PING).face)
    }
}

/** The strip's pills ignore taps for a moment after it changed by itself (design R8). */
class StripTapShieldTest {

    @Test
    fun `the strip home opens with takes taps at once`() {
        val shield = StripTapShield()
        shield.saw(StripState.FirstNotes("s1"), nowMs = 1_000L)
        assertTrue(shield.accepts(1_000L))
    }

    @Test
    fun `a strip that appears or changes by itself ignores taps for 700 ms`() {
        val shield = StripTapShield()
        shield.saw(null, nowMs = 0L)
        shield.saw(StripState.Nudge(NudgeId.NOTIFY_PING), nowMs = 1_000L)
        assertFalse(shield.accepts(1_300L))
        assertTrue(shield.accepts(1_000L + FirstRunFlow.TAP_SHIELD_MS))

        shield.saw(StripState.FirstNotes("s1"), nowMs = 5_000L)
        assertFalse(shield.accepts(5_699L))
        assertTrue(shield.accepts(5_700L))
    }

    @Test
    fun `the same strip again, or its bar moving on, changes nothing`() {
        val shield = StripTapShield()
        val running = StripState.Download(FirstRunPart.AI_MODEL, 1L, 10L)
        shield.saw(running.face, nowMs = 0L)
        shield.saw(running.copy(bytesDone = 9L).face, nowMs = 2_000L)
        assertTrue(shield.accepts(2_000L))
    }
}
