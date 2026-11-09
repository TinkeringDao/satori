package com.satori.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {

    private var now = 0L
    private val throttle = ProgressThrottle { now }

    /** Reads of [bytesPerRead] every [msPerRead] for [ms]; the bytes told. */
    private fun told(bytesPerRead: Long, msPerRead: Long, ms: Long): List<Long> {
        var bytes = 0L
        val told = mutableListOf<Long>()
        if (throttle.due(bytes)) told += bytes
        while (now < ms) {
            now += msPerRead
            bytes += bytesPerRead
            if (throttle.due(bytes)) told += bytes
        }
        return told
    }

    @Test
    fun `on a fast network every quarter second, not every read`() {
        // 64 KB every 5 ms: 12.8 MB/s.
        val told = told(bytesPerRead = 64_000L, msPerRead = 5L, ms = 10_000L)

        assertEquals("about 4 a second for 10 s", 41, told.size)
    }

    @Test
    fun `on a slow network every 1 MB, or every second when that takes longer`() {
        // 2 MB/s: each 1 MB, twice a second.
        assertEquals(21, told(bytesPerRead = 20_000L, msPerRead = 10L, ms = 10_000L).size)
        now = 0L
        // 100 kB/s: once a second.
        val slow = ProgressThrottle { now }
        var bytes = 0L
        var count = 0
        if (slow.due(bytes)) count++
        while (now < 10_000L) {
            now += 10L
            bytes += 1_000L
            if (slow.due(bytes)) count++
        }
        assertEquals(11, count)
    }

    @Test
    fun `nothing is told while no bytes come`() {
        assertTrue(throttle.due(5_000_000L))
        now += 10_000L
        assertFalse(throttle.due(5_000_000L))
    }

    @Test
    fun `a progress told anyway counts as the last one`() {
        assertTrue(throttle.due(0L))
        now += 2_000L
        throttle.told(3_000_000L)
        now += 100L
        assertFalse(throttle.due(4_500_000L))
        now += 200L
        assertTrue(throttle.due(4_500_000L))
    }
}
