package com.satori.ui.screens

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NothingSaidNoticeTest {

    private val closedAt = 5_000_000L

    private fun homeWithNotice() = SavedStateHandle().also { NothingSaidNotice.post(it, closedAt) }

    /**
     * Home started at [startedAt] on a phone locked for [lockedFor] more; each look at the lock
     * takes one poll, and every poll moves the clock on by [pollMs].
     */
    private class Phone(startedAt: Long, private val lockedFor: Long, private val pollMs: Long = 200L) {
        private val unlockAt = startedAt + lockedFor
        var now = startedAt
            private set
        var looks = 0
            private set

        fun isLocked(): Boolean {
            looks++
            return now < unlockAt
        }

        fun poll() {
            now += pollMs
        }
    }

    private fun take(home: SavedStateHandle, phone: Phone) = runBlocking {
        NothingSaidNotice.takeOnceSeen(home, nowMs = { phone.now }, isLocked = phone::isLocked, pollMs = 1L)
    }

    /** As [take], with the clock moved on by each wait of the poll. */
    private fun takeWhilePolling(home: SavedStateHandle, phone: Phone) = runBlocking {
        NothingSaidNotice.takeOnceSeen(home, nowMs = { phone.now }, isLocked = { phone.isLocked().also { if (it) phone.poll() } }, pollMs = 1L)
    }

    @Test
    fun `home says it once, the next time it is seen`() {
        val home = homeWithNotice()

        assertTrue(take(home, Phone(startedAt = closedAt + 300L, lockedFor = 0L)))
        assertFalse(take(home, Phone(startedAt = closedAt + 600L, lockedFor = 0L)))
    }

    @Test
    fun `an unlock that Android reports after home has started still shows it`() {
        // The fingerprint unlock starts home some 60 ms before the phone reads as unlocked.
        val home = homeWithNotice()
        val phone = Phone(startedAt = closedAt + 40_000L, lockedFor = 60L)

        assertTrue(takeWhilePolling(home, phone))

        assertTrue(phone.looks > 1)
        assertFalse(take(home, Phone(startedAt = closedAt + 41_000L, lockedFor = 0L)))
    }

    @Test
    fun `home started behind the lock screen keeps it for the start that follows the unlock`() = runBlocking {
        val home = homeWithNotice()

        // The recorder over the lock screen went back home, which stops again behind it.
        val behind = launch(start = CoroutineStart.UNDISPATCHED) {
            NothingSaidNotice.takeOnceSeen(home, nowMs = { closedAt + 300L }, isLocked = { true }, pollMs = 10_000L)
        }
        behind.cancelAndJoin()

        assertTrue(take(home, Phone(startedAt = closedAt + 40_000L, lockedFor = 0L)))
    }

    @Test
    fun `an unlock more than a minute later says nothing, and the notice is gone`() {
        val home = homeWithNotice()
        val phone = Phone(startedAt = closedAt + 300L, lockedFor = 2 * NothingSaidNotice.SHOWN_WITHIN_MS)

        // Home stayed started behind the lock screen: it stops looking once the notice is old.
        assertFalse(takeWhilePolling(home, phone))
        assertTrue(phone.now - closedAt <= NothingSaidNotice.SHOWN_WITHIN_MS + 1_000L)

        assertFalse(take(home, Phone(startedAt = phone.now, lockedFor = 0L)))
        assertEquals(false, take(homeWithNotice(), Phone(closedAt + NothingSaidNotice.SHOWN_WITHIN_MS + 1L, lockedFor = 0L)))
    }

    @Test
    fun `without a deleted recording home says nothing, and doesn't look at the lock`() {
        val phone = Phone(startedAt = closedAt, lockedFor = 0L)

        assertFalse(take(SavedStateHandle(), phone))

        assertEquals(0, phone.looks)
    }

    @Test
    fun `a minute is still news, and a time from before a restart of the phone is not`() {
        assertTrue(NothingSaidNotice.isDue(closedAt, closedAt + NothingSaidNotice.SHOWN_WITHIN_MS))
        assertFalse(NothingSaidNotice.isDue(closedAt, closedAt - 1L))
    }
}
