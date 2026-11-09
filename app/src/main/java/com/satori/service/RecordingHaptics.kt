package com.satori.service

import android.content.Context
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a [RecordingCues.Cue] on the phone's vibrator: one long pulse when the microphone goes
 * live, two short ones when it goes off, three when the recording could not start. The shapes tell
 * the three apart without looking, also through a pocket: the owner found the earlier start, one
 * 90 ms pulse, too weak to be sure the recording had started. At full strength where the motor
 * lets an app set it.
 *
 * Played by [RecordingService], which is in the foreground for the whole recording, also with the
 * screen off: Android ignores most vibrations from apps in the background. The usage is hardware
 * feedback, as the cue mostly answers the power key. Unlike touch feedback, stock Android also
 * plays it in battery saver and with touch vibration switched off in the system settings, so a
 * recording started in a pocket is still felt; the app's own setting is what turns it off.
 *
 * The first run's two cues ([FirstRunCues]) have shapes of their own, neither like a recording's:
 * Ready, a short pulse then a long one; the first notes, a long one then a short one. They answer
 * no key press but tell of something done while the owner was away, so they are played as a
 * notification's vibration is, which the phone's silent mode and Do Not Disturb keep quiet.
 */
@Singleton
class RecordingHaptics @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Plays [cue] now. A phone without a vibrator, or one that refuses, only misses the buzz. */
    fun play(cue: RecordingCues.Cue) {
        val manager = context.getSystemService(VibratorManager::class.java) ?: return
        val vibrator = manager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        try {
            manager.vibrate(CombinedVibration.createParallel(effectFor(cue, vibrator)), attributesFor(cue))
        } catch (e: RuntimeException) {
            // A buzz is never worth a recording: the vibrator service may refuse, and that is all.
            Log.w(TAG, "No buzz for $cue", e)
        }
    }

    private fun effectFor(cue: RecordingCues.Cue, vibrator: Vibrator): VibrationEffect {
        val amplitude = if (vibrator.hasAmplitudeControl()) FULL_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
        return when (cue) {
            RecordingCues.Cue.START -> VibrationEffect.createOneShot(START_MS, amplitude)
            RecordingCues.Cue.STOP -> pulses(STOP_TIMINGS, amplitude)
            RecordingCues.Cue.FAILED -> pulses(FAILED_TIMINGS, amplitude)
            RecordingCues.Cue.SETUP_DONE -> pulses(SETUP_DONE_TIMINGS, amplitude)
            RecordingCues.Cue.NOTES_READY -> pulses(NOTES_READY_TIMINGS, amplitude)
        }
    }

    /** A recording's cues as hardware feedback, the first run's as a notification's vibration. */
    private fun attributesFor(cue: RecordingCues.Cue): VibrationAttributes = when (cue) {
        RecordingCues.Cue.START, RecordingCues.Cue.STOP, RecordingCues.Cue.FAILED -> ATTRIBUTES
        RecordingCues.Cue.SETUP_DONE, RecordingCues.Cue.NOTES_READY -> FIRST_RUN_ATTRIBUTES
    }

    /** [timings] alternate off and on, starting with off; every on is at [amplitude]. */
    private fun pulses(timings: LongArray, amplitude: Int): VibrationEffect = VibrationEffect.createWaveform(
        timings,
        IntArray(timings.size) { index -> if (index % 2 == 0) 0 else amplitude },
        NO_REPEAT
    )

    private companion object {
        const val TAG = "RecordingHaptics"

        // The shapes, in ms, to be tuned after the owner's feel test in the hand and in a pocket:
        // the start is long enough to feel through clothing and to tell from a tap; the gaps keep
        // the pulses of a stop and of a failed start apart.
        const val START_MS = 300L
        val STOP_TIMINGS = longArrayOf(0, 100, 120, 100)
        val FAILED_TIMINGS = longArrayOf(0, 70, 90, 70, 90, 70)

        // The first run's: short then long for Ready, long then short for the first notes.
        val SETUP_DONE_TIMINGS = longArrayOf(0, 80, 120, 260)
        val NOTES_READY_TIMINGS = longArrayOf(0, 260, 120, 80)

        const val FULL_AMPLITUDE = 255
        const val NO_REPEAT = -1

        val ATTRIBUTES: VibrationAttributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()

        val FIRST_RUN_ATTRIBUTES: VibrationAttributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
            .build()
    }
}
