package com.satori.service

import com.satori.domain.model.CaptureState

/**
 * When [RecordingService] buzzes: once as the microphone of a recording goes live, twice when it
 * stops, whichever way it stops (the record button, the notification, the power key, or an error
 * that ends it), and three times when it could not start.
 *
 * The start buzz waits for the microphone, not for the press: the capture reports it
 * ([CaptureState.LISTENING] or [CaptureState.TRANSCRIBING], [onCapture]), so a recording started
 * in a pocket buzzes once the microphone delivers its first audio, and words said from then on are
 * kept.
 *
 * A recording that never went live could not start ([Cue.FAILED]), unless the owner stopped it
 * while it opened, which buzzes neither way. Which of the two it was is only certain once the
 * service is told to stop the recording ([onEnded]): the recorder sends "stopping" ([onStopAsked])
 * before that stop, and Android hands a service its commands in the order they were sent, whereas
 * the end of the capture reaches the service another way and can come before the "stopping" that
 * the recorder sent first. So the end of a capture that never went live plays nothing, and the
 * service's stop decides.
 *
 * The service hears of one stop up to three times: the notification switches to "stopping" when
 * the recorder begins to stop ([onStopAsked]), the capture ends, and the service itself stops
 * after the hand-off or right away after an error ([onEnded]). Only the first report buzzes. A
 * stop buzzes only for a recording whose start did, so the two always come as a pair: the setting
 * is read as the recording starts, the start buzzes once both the setting and the live microphone
 * are in, in whichever order they come, and a recording that stops before both are in buzzes
 * neither way. A failure is worth telling however late its setting comes, so the service stays a
 * moment for it ([awaitsSetting]).
 *
 * Not thread-safe; [RecordingService] calls it on the main thread only.
 */
class RecordingCues {

    /** What to play. */
    enum class Cue {
        /** The microphone went live. */
        START,

        /** The microphone went off. */
        STOP,

        /** The recording could not start: it was stopped before its microphone went live. */
        FAILED,

        /**
         * No recording's: the first run's speech files are checked and Ready shows, while the app is
         * out of sight. [FirstRunCues] plays it; this class never does.
         */
        SETUP_DONE,

        /** No recording's: the first notes of any recording are stored. [FirstRunCues] plays it; this class never does. */
        NOTES_READY
    }

    // Whether a recording was started; its cues follow it until the next one starts.
    private var recording = false

    // Whether the recording buzzes; null until the setting has been read for it.
    private var vibrate: Boolean? = null

    // Its capture has begun, so an IDLE capture after this means it ended.
    private var began = false

    // Its microphone went live while it ran.
    private var live = false

    // Its capture ended.
    private var captureEnded = false

    // The recorder began to stop it.
    private var stopAsked = false

    // The service stopped it.
    private var stopped = false

    // The last cue played for it, if any.
    private var played: Cue? = null

    /**
     * Whether the recording, stopped by the service, still owes its cue once its setting is read: a
     * start that failed before the setting came in. The service stays in the foreground for it a
     * moment, as an app may vibrate while it is in the foreground.
     */
    val awaitsSetting: Boolean
        get() = recording && vibrate == null && stopped && !live && !stopAsked

    /** A recording has started. Its setting is read next and handed to [onSetting]. */
    fun onStarted() {
        recording = true
        vibrate = null
        began = false
        live = false
        captureEnded = false
        stopAsked = false
        stopped = false
        played = null
    }

    /**
     * The vibration setting, as read for the recording that started last.
     *
     * @return the cue the recording waited for: [Cue.START] if its microphone is live and it still
     *   runs, [Cue.FAILED] if the service stopped it before it went live and no stop was asked
     *   for; null otherwise, with the setting off, and for a second reading
     */
    fun onSetting(enabled: Boolean): Cue? {
        if (!recording || vibrate != null) return null
        vibrate = enabled
        return due()
    }

    /**
     * The capture of the recording, as [com.satori.domain.repository.SttRepository.captureState]
     * reports it from the service's start: a live microphone is [onLive], and IDLE once the capture
     * has begun is its end. An IDLE before it began is the moment before the capture starts, and
     * says nothing.
     *
     * @return [Cue.START] as for [onLive]; [Cue.STOP] when a capture whose start buzzed ends (an
     *   error, or the stop that "stopping" has not reported yet); null otherwise, also for the end
     *   of a capture that never went live, which the service's stop decides ([onEnded])
     */
    fun onCapture(state: CaptureState): Cue? = when (state) {
        CaptureState.IDLE -> if (began) onCaptureEnded() else null
        CaptureState.OPENING -> {
            began = true
            null
        }
        CaptureState.LISTENING, CaptureState.TRANSCRIBING -> onLive()
    }

    /**
     * The microphone of the recording delivers audio, so its capture has begun.
     *
     * @return [Cue.START] the first time, if the setting is read and on; null otherwise, and once
     *   the capture ended, a stop was asked for or the recording was stopped
     */
    fun onLive(): Cue? {
        if (!recording) return null
        began = true
        if (live || captureEnded || stopAsked || stopped) return null
        live = true
        return due()
    }

    /**
     * The recorder has begun to stop the recording (the notification then says "stopping"). Also
     * after its capture ended: a capture that never went live and ended a moment before this is a
     * stop, not a failure.
     *
     * @return [Cue.STOP] for a recording whose start buzzed, the first time; null otherwise
     */
    fun onStopAsked(): Cue? {
        if (!recording || stopAsked || stopped) return null
        stopAsked = true
        return due()
    }

    /**
     * The service stops the recording: the recorder stopped it (after "stopping", or without it on
     * an error or when the recorder closed), or the service gave up on it.
     *
     * @return [Cue.STOP] for a recording whose start buzzed and whose stop didn't yet (an error,
     *   the recorder closed while recording); [Cue.FAILED] for one that never went live, when no
     *   stop was asked for and the setting is on; null otherwise, and for a second report
     */
    fun onEnded(): Cue? {
        if (!recording || stopped) return null
        stopped = true
        return due()
    }

    // The capture of the recording has ended.
    private fun onCaptureEnded(): Cue? {
        if (!recording || captureEnded) return null
        captureEnded = true
        return due()
    }

    /** The cue the recording owes now, from what happened to it and what was played for it. */
    private fun due(): Cue? {
        if (vibrate != true) return null
        val cue = when {
            played == null && live && !captureEnded && !stopAsked && !stopped -> Cue.START
            played == Cue.START && (captureEnded || stopAsked || stopped) -> Cue.STOP
            played == null && stopped && !live && !stopAsked -> Cue.FAILED
            else -> null
        }
        if (cue != null) played = cue
        return cue
    }
}
