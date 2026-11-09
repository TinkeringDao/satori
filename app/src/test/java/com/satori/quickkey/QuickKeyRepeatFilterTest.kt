package com.satori.quickkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickKeyRepeatFilterTest {

    private val filter = QuickKeyRepeatFilter(windowMs = 1_000L)

    @Test
    fun `the first press goes through`() {
        assertTrue(filter.accept(nowMs = 50_000L))
    }

    @Test
    fun `a press within the window is dropped`() {
        filter.accept(50_000L)
        assertFalse(filter.accept(50_400L))
        assertFalse(filter.accept(50_999L))
    }

    @Test
    fun `a press a full window later goes through`() {
        filter.accept(50_000L)
        assertTrue(filter.accept(51_000L))
    }

    @Test
    fun `a dropped press doesn't extend the window`() {
        filter.accept(50_000L)
        assertFalse(filter.accept(50_900L))
        // 1.1 s after the forwarded press, 0.2 s after the dropped one
        assertTrue(filter.accept(51_100L))
    }

    @Test
    fun `each forwarded press starts a new window`() {
        assertTrue(filter.accept(50_000L))
        assertTrue(filter.accept(52_000L))
        assertFalse(filter.accept(52_500L))
    }

    @Test
    fun `a reading before the last press goes through`() {
        // elapsedRealtime doesn't go back within a process; if it ever did, the key must not lock.
        filter.accept(50_000L)
        assertTrue(filter.accept(49_000L))
    }

    @Test
    fun `the app's filter uses a one second window`() {
        val appFilter = QuickKeyRepeatFilter()
        assertTrue(appFilter.accept(0L))
        assertFalse(appFilter.accept(999L))
        assertTrue(appFilter.accept(1_000L))
    }
}
