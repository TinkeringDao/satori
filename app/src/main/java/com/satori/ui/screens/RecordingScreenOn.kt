package com.satori.ui.screens

/**
 * When the recorder keeps the screen from timing out ("Keep the screen on while recording").
 *
 * Only while the microphone is live, the setting is on, and the phone is unlocked: a recording the
 * power key starts on the lock screen goes on in a pocket, where a screen kept lit costs battery,
 * warms the phone and takes presses through the cloth. Over the lock screen the screen times out
 * as usual, and the recording goes on; unlocked meanwhile, it stays on again. The owner's
 * decision of 2026-10-16 (question 6 of the 0.7.0 brief, its default):
 * [KEEPS_ON_OVER_LOCK_SCREEN] true keeps it on over the lock screen too, as before.
 */
internal object RecordingScreenOn {

    /** Whether the screen is kept on over the lock screen too. */
    const val KEEPS_ON_OVER_LOCK_SCREEN = false

    /**
     * @param recording Whether the microphone is live
     * @param setting "Keep the screen on while recording"
     * @param locked Whether the phone is locked, the recorder shown over the lock screen
     */
    fun keeps(recording: Boolean, setting: Boolean, locked: Boolean): Boolean =
        recording && setting && (!locked || KEEPS_ON_OVER_LOCK_SCREEN)
}
