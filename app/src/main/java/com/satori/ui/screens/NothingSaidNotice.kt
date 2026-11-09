package com.satori.ui.screens

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.delay

/**
 * Home's notice that a recording was deleted because nothing was said in it
 * (DiscardSilentRecordingUseCase). As the recorder goes back to home it leaves the time in home's
 * SavedStateHandle ([post]), which outlasts a fold and a restored process; home says it once, the
 * next time it is seen ([takeOnceSeen]).
 *
 * The recorder only goes back while it is on show, so home is normally seen at once. Not after a
 * quick-key press that recorded over the lock screen: home is then behind it until the phone is
 * unlocked, and the notice waits for that, but only [SHOWN_WITHIN_MS]. Any later it would explain
 * a recording the owner no longer has in mind. An unlock starts home before the phone reads as
 * unlocked (the lock screen says so only at the end of its exit animation, some 60 ms later on
 * the razr), so home looks again until it does.
 */
internal object NothingSaidNotice {

    /** How long after the recorder went back home still says why its recording is gone. */
    const val SHOWN_WITHIN_MS = 60_000L

    /** How often home looks again whether the phone is unlocked, while a notice waits. */
    const val UNLOCK_POLL_MS = 200L

    private const val KEY = "nothing_said_at"

    /** Leaves the notice for [home] at [nowMs], on the clock since boot (elapsedRealtime). */
    fun post(home: SavedStateHandle, nowMs: Long) {
        home[KEY] = nowMs
    }

    /**
     * Whether home says it, now that home has started; [nowMs] is the clock of [post]. While
     * [isLocked] says the phone is locked home can't be seen, so this waits, looking again every
     * [pollMs], until the phone is unlocked or the notice is no longer news. It is used up then,
     * said or not. A wait that is cancelled, as home stops behind the lock screen, leaves it for
     * the next start.
     */
    suspend fun takeOnceSeen(
        home: SavedStateHandle,
        nowMs: () -> Long,
        isLocked: () -> Boolean,
        pollMs: Long = UNLOCK_POLL_MS
    ): Boolean {
        val postedAt = home.get<Long>(KEY) ?: return false
        while (isLocked() && isDue(postedAt, nowMs())) delay(pollMs)
        home.remove<Long>(KEY)
        return !isLocked() && isDue(postedAt, nowMs())
    }

    /**
     * Whether a notice left at [postedAtMs] is still news at [nowMs]. A time ahead of [nowMs] is
     * from before the phone restarted, as that clock starts again at boot, and is not.
     */
    fun isDue(postedAtMs: Long, nowMs: Long): Boolean = nowMs - postedAtMs in 0..SHOWN_WITHIN_MS
}
