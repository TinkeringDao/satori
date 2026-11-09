package com.satori.ui.screens

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When home's owner has seen the ideas row: on screen in full, a moment long, with the phone unlocked. */
class IdeasRowSeenTest {

    /**
     * A phone the wait looks at, locked while [lockedAt] says so. Every look at the clock is one
     * poll later than the one before, as the wait's delay would make it.
     */
    private class Phone(private val lockedAt: (Long) -> Boolean) {
        var now = -POLL_MS
            private set

        fun nowMs(): Long {
            now += POLL_MS
            return now
        }

        fun isLocked(): Boolean = lockedAt(now)
    }

    /** When the row began to be seen, or null when it wasn't within [waitMs] of real time. */
    private fun seenSince(phone: Phone, waitMs: Long = 2_000L): Long? = runBlocking {
        withTimeoutOrNull(waitMs) {
            awaitIdeasRowSeen(nowMs = phone::nowMs, isLocked = phone::isLocked, seenMs = SEEN_MS, pollMs = 1L)
        }
    }

    @Test
    fun `a row on screen with the phone unlocked is seen after a moment, as it read from the start`() {
        val phone = Phone { false }

        assertEquals(0L, seenSince(phone))
        assertEquals(SEEN_MS, phone.now)
    }

    @Test
    fun `an unlock that reads as one only after home has resumed counts from then`() {
        // The fingerprint unlock resumes home some 60 ms before the phone reads as unlocked.
        val phone = Phone { now -> now < 60L }

        assertEquals(POLL_MS, seenSince(phone))
    }

    @Test
    fun `a lock while the row is on screen starts the moment again`() {
        val phone = Phone { now -> now in 400L until 600L }

        assertEquals(600L, seenSince(phone))
        assertEquals(600L + SEEN_MS, phone.now)
    }

    @Test
    fun `behind the lock screen the row is never seen`() {
        // The recorder over the lock screen went back home, which Satori then hid behind it.
        val phone = Phone { true }

        assertNull(seenSince(phone, waitMs = 500L))
        // It looked for longer than a row on show takes to be seen.
        assertTrue(phone.now > SEEN_MS)
    }

    @Test
    fun `the row counts as on screen only in full, and not when only composed ahead of a scroll`() {
        assertTrue(Layout(Item(IDEAS_ROW_KEY, offset = 400, size = 48)).showsInFull(IDEAS_ROW_KEY))
        // Cut by the foot of the list, or by its head within the top padding.
        assertFalse(Layout(Item(IDEAS_ROW_KEY, offset = 580, size = 48)).showsInFull(IDEAS_ROW_KEY))
        assertFalse(Layout(Item(IDEAS_ROW_KEY, offset = -20, size = 48)).showsInFull(IDEAS_ROW_KEY))
        assertTrue(Layout(Item(IDEAS_ROW_KEY, offset = -8, size = 48)).showsInFull(IDEAS_ROW_KEY))
        // Composed but not laid out on screen, and another row where it would be.
        assertFalse(Layout(Item("todo-suggested", offset = 400, size = 48)).showsInFull(IDEAS_ROW_KEY))
        assertFalse(Layout().showsInFull(IDEAS_ROW_KEY))
    }

    private class Item(override val key: Any, override val offset: Int, override val size: Int) : LazyListItemInfo {
        override val index: Int = 0
    }

    /** A list 608 px tall with 8 px of padding at its head, showing [items]. */
    private class Layout(vararg items: LazyListItemInfo) : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = items.toList()
        override val viewportStartOffset: Int = -8
        override val viewportEndOffset: Int = 600
        override val totalItemsCount: Int = items.size
    }

    private companion object {
        const val POLL_MS = 200L
        const val SEEN_MS = 1_000L
    }
}
