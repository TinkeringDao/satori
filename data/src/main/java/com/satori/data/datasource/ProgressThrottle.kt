package com.satori.data.datasource

/**
 * When a running download's progress is worth telling ([due]): at most every [MIN_INTERVAL_MS],
 * and then once [STEP_BYTES] more came or [MAX_INTERVAL_MS] passed. The screens' numbers so move
 * about every quarter second on a fast network and at least every second while bytes come, in
 * steps of 1 MB, and no screen and no notification is updated for every 64 KB read. A step by
 * percent instead made the AI model race through 11 MB and then hold still for 43 MB.
 *
 * @param clock A monotonic clock in ms
 */
internal class ProgressThrottle(private val clock: () -> Long) {

    private var lastMs = 0L
    private var lastBytes = NONE

    /** Whether the progress at [bytes] is told now; when it is, the next is measured from it. */
    fun due(bytes: Long): Boolean {
        val now = clock()
        val due = lastBytes == NONE || (
            bytes != lastBytes && now - lastMs >= MIN_INTERVAL_MS &&
                (bytes - lastBytes >= STEP_BYTES || now - lastMs >= MAX_INTERVAL_MS)
            )
        if (due) told(bytes, now)
        return due
    }

    /** The progress at [bytes] was told anyway, at the start or after a file's check: the next is measured from it. */
    fun told(bytes: Long, nowMs: Long = clock()) {
        lastMs = nowMs
        lastBytes = bytes
    }

    companion object {
        const val MIN_INTERVAL_MS = 250L
        const val MAX_INTERVAL_MS = 1_000L
        const val STEP_BYTES = 1_000_000L
        private const val NONE = -1L
    }
}
