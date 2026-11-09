package com.satori.quickkey

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops a press that comes within [WINDOW_MS] of the last press [QuickKeyActivity] forwarded.
 * Such a press repeats the one before rather than asking for something new: My Key may fire
 * twice when the power key is pressed three or four times, and nobody starts a recording and
 * stops it again within a second. Forwarded, the repeat would cancel the start the first press
 * asked for (see [QuickKeyRequest.RecorderState.STARTING]).
 *
 * One per process, so it spans separate QuickKeyActivity instances. After the process has died,
 * the next press counts as the first.
 */
@Singleton
class QuickKeyRepeatFilter internal constructor(private val windowMs: Long) {

    @Inject
    constructor() : this(WINDOW_MS)

    private var lastForwardedAt: Long? = null

    /**
     * Whether to forward the press made at [nowMs], a SystemClock.elapsedRealtime() reading
     * (which no clock change moves). A forwarded press starts the next window; a dropped one
     * doesn't extend it.
     */
    @Synchronized
    fun accept(nowMs: Long): Boolean {
        val last = lastForwardedAt
        if (last != null && nowMs >= last && nowMs - last < windowMs) return false
        lastForwardedAt = nowMs
        return true
    }

    companion object {
        /** How long after a forwarded press another press counts as its repeat. */
        const val WINDOW_MS = 1_000L
    }
}
