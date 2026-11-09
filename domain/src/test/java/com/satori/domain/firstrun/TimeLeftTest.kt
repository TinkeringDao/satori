package com.satori.domain.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLeftTest {

    // 60 MB at 100 kB/s: 2 % is 1.2 MB, which comes after 12 s; the whole takes 10 min.
    private val total = 60_000_000L
    private val perMs = 100L

    /** Feeds one progress a second at [perMs] from [fromMs] to [toMs], as a download's screen sees them; the last result. */
    private fun TimeLeft.run(fromMs: Long, toMs: Long, startBytes: Long = 0L, rate: Long = perMs): Int? {
        var last: Int? = null
        var t = fromMs
        while (t <= toMs) {
            last = update(t, startBytes + (t - fromMs) * rate)
            t += 1_000L
        }
        return last
    }

    @Test
    fun `nothing shows in a run's first 10 seconds`() {
        // 1 MB/s: 2 % of the bytes in under 2 s.
        val fast = TimeLeft(total)
        for (t in 0L..9_000L step 1_000L) assertNull("at $t ms", fast.update(t, t * 1_000L))
        assertNotNull(fast.update(10_000L, 10_000_000L))
    }

    @Test
    fun `nor before 2 percent of the bytes came`() {
        val slow = TimeLeft(total)
        assertNull(slow.run(0L, 11_000L))
        // 1.2 MB at 12 s: 58.8 MB left at 100 kB/s is 9.8 min, rounded up.
        assertEquals(10, slow.update(12_000L, 1_200_000L))
    }

    @Test
    fun `the time is rounded up, so the last minute shows as 1`() {
        val nearlyDone = TimeLeft(total)
        nearlyDone.run(0L, 12_000L)
        // Well under a second left at the rate of the last 10 s.
        assertEquals(1, nearlyDone.update(18_000L, total - 3_000_000L))
    }

    /** What shows first, at 10 s of a steady 100 kB/s, with [secondsLeft] left then. */
    private fun shownWith(secondsLeft: Long): Int? {
        val total = 1_000_000L + secondsLeft * 100_000L
        return TimeLeft(total).run(0L, 10_000L)
    }

    @Test
    fun `from 2 minutes up the time is the nearest minute, so a short wait isn't overstated`() {
        // Design B3 at 25 Mbit/s: 2.14 min left at half the bytes, which rounding up showed as 3.
        assertEquals(2, shownWith(128L))
        assertEquals(3, shownWith(150L))
        assertEquals(3, shownWith(204L))
        assertEquals(4, shownWith(212L))
        // Within 20 % from 2 minutes on.
        for (seconds in 120L..480L) {
            val shown = checkNotNull(shownWith(seconds)) * 60.0
            assertTrue("$seconds s shown as $shown s", shown in seconds * 0.8..seconds * 1.2)
        }
    }

    @Test
    fun `under 2 minutes it says 2, and the last minute is 1`() {
        assertEquals(2, shownWith(119L))
        assertEquals(2, shownWith(64L))
        assertEquals(1, shownWith(60L))
        assertEquals(1, shownWith(20L))
    }

    @Test
    fun `a shown time changes at most every 5 seconds`() {
        val estimate = TimeLeft(total)
        assertEquals(10, estimate.run(0L, 12_000L))
        // The rate jumps tenfold: the next 4 s still show what was shown.
        var bytes = 1_200_000L
        for (t in 13_000L..16_000L step 1_000L) {
            bytes += 1_000_000L
            assertEquals("at $t ms", 10, estimate.update(t, bytes))
        }
        bytes += 1_000_000L
        val after = estimate.update(17_000L, bytes)
        // 5.5 MB in the last 10 s: 53.8 MB take 1.6 min.
        assertEquals(2, after)
    }

    @Test
    fun `it is hidden once no bytes have come for the whole window`() {
        val estimate = TimeLeft(total)
        assertEquals(10, estimate.run(0L, 12_000L))
        val stalled = 1_200_000L
        // The bytes stop: the average falls, and the time shows for as long as any came in the window.
        for (t in 13_000L..21_000L step 1_000L) assertNotNull("at $t ms", estimate.update(t, stalled))
        assertNull(estimate.update(22_000L, stalled))
        // It comes back with the bytes, at once.
        assertNotNull(estimate.update(23_000L, stalled + 100_000L))
    }

    @Test
    fun `the rate is the last 10 seconds, so a slower network shows within the window`() {
        val estimate = TimeLeft(total)
        estimate.run(0L, 30_000L)
        // 3 MB done; at half the rate, 57 MB take 19 min.
        val slower = estimate.run(31_000L, 60_000L, startBytes = 3_000_000L + 50_000L, rate = 50L)
        // 4.5 MB done at 60 s: 55.5 MB at 50 kB/s is 18.5 min.
        assertEquals(19, slower)
    }
}
