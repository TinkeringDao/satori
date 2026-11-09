package com.satori.domain.firstrun

import kotlin.math.floor

/**
 * The time left of one run of a download, as Getting ready and the status strip show it (design
 * 6.2): the bytes per second averaged over the last [WINDOW_MS], shown once [SHOW_AFTER_MS] have
 * passed and [SHOW_AFTER_FRACTION] of the bytes came in this run, in minutes ([minutes]: the last
 * one as 1, then 2, then the nearest minute), changed at most every [CHANGE_EVERY_MS], and hidden
 * while no bytes arrive. A person with a shaky sense of time reads it instead of his own, so it
 * must not jump about, nor say much more than there is.
 *
 * Feed it every progress there is, and once a second while nothing comes ([update]), on one
 * monotonic clock in ms. A new run (after a pause or a failure) starts a new one; Getting ready's
 * one bar keeps its own from one part of the setup to the next, as it counts them together.
 *
 * @param totalBytes What the bar counts to
 */
class TimeLeft(private val totalBytes: Long) {

    // (ms, bytes) of the last WINDOW_MS, oldest first.
    private val samples = ArrayDeque<LongArray>()
    private var startMs = 0L
    private var startBytes = -1L
    private var shown: Int? = null
    private var shownAtMs = 0L

    /** The minutes to show after [bytesDone] at [nowMs] ([minutes]), so 1 is the last minute; null while none is shown. */
    fun update(nowMs: Long, bytesDone: Long): Int? {
        if (startBytes < 0) {
            startMs = nowMs
            startBytes = bytesDone
        }
        samples.addLast(longArrayOf(nowMs, bytesDone))
        while (samples.size > 1 && samples.first()[0] < nowMs - WINDOW_MS) samples.removeFirst()

        val shownBefore = shown
        val oldest = samples.first()
        val spanMs = nowMs - oldest[0]
        val rate = if (spanMs > 0) (bytesDone - oldest[1]).toDouble() / spanMs else 0.0
        val eligible = nowMs - startMs >= SHOW_AFTER_MS &&
            bytesDone - startBytes >= totalBytes * SHOW_AFTER_FRACTION
        shown = when {
            !eligible || rate <= 0.0 -> null
            shownBefore != null && nowMs - shownAtMs < CHANGE_EVERY_MS -> shownBefore
            else -> {
                shownAtMs = nowMs
                minutes((totalBytes - bytesDone).coerceAtLeast(0L) / rate)
            }
        }
        return shown
    }

    /**
     * The minutes shown for [msLeft]: 1 for the last minute ("Under 1 min left"), 2 up to two
     * minutes, and from there the nearest minute. Rounded up throughout, the time said 3 minutes
     * with 2.1 left, 40 % too much (design B3 asks for 30 % at half the bytes); from 2 minutes on
     * the nearest is at most 20 % off.
     */
    private fun minutes(msLeft: Double): Int = when {
        msLeft <= MINUTE_MS -> 1
        msLeft < 2 * MINUTE_MS -> 2
        else -> floor(msLeft / MINUTE_MS + HALF).toInt()
    }

    companion object {
        /** The span the rate is averaged over. */
        const val WINDOW_MS = 10_000L

        /** No time is shown in a run's first seconds, while the rate means little. */
        const val SHOW_AFTER_MS = 10_000L

        /** Nor before this share of the bytes came in the run. */
        const val SHOW_AFTER_FRACTION = 0.02

        /** A shown time changes at most this often. */
        const val CHANGE_EVERY_MS = 5_000L

        private const val MINUTE_MS = 60_000.0
        private const val HALF = 0.5
    }
}
