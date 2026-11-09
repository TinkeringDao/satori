package com.satori.presentation.main

import com.satori.domain.model.CaptureState
import com.satori.domain.repository.MicrophoneLostException

/**
 * Whether the transcript of this recorder's captures may lack speech that their audio holds, as
 * the recorder knows it: the speech recognizer failed in a capture, or a capture ended before what
 * the microphone gave it was transcribed. The finisher is told at the hand-off
 * ([recognizerFailed]), and the silent rule through [RecordingEnd], which then keeps a recording
 * that has a length or audio although no saved line holds a word.
 *
 * A capture opens the microphone first and loads the speech model next to it, so the audio of its
 * first seconds is transcribed only once the model is in. When Stop gives up waiting for the
 * capture's end, or the recorder closes while the model still loads, that audio may never be
 * transcribed, and the recognizer has no evidence of the speech in it either: without this the
 * silent rule would delete a recording, with its audio, in which something was said.
 *
 * Kept out of MainViewModel, which reports to it, so its rules are tested.
 */
internal class TranscriptGaps(private val end: RecordingEnd) {

    /**
     * Whether part of the audio may never have been transcribed: a capture of this recorder failed
     * for another reason than a lost microphone ([MicrophoneLostException]), or was cut off. The
     * finisher may transcribe the recording again from its audio.
     */
    var recognizerFailed = false
        private set

    /** The speech stream of a capture failed with [error]; see [RecordingEnd.captureFailed]. */
    fun captureFailed(error: Throwable, current: Boolean, recognizerHeard: Boolean) {
        if (error !is MicrophoneLostException) recognizerFailed = true
        end.captureFailed(error, current, recognizerHeard)
    }

    /**
     * Stop gave up waiting for the end of the capture, which was in [state] then: what it had not
     * transcribed by that time is missing from the transcript. One that had not heard the
     * microphone yet ([CaptureState.OPENING]) recorded nothing that could be missing. What the
     * recognition heard is read later, once the speech model is released
     * ([RecordingEnd.captureStopped]).
     */
    fun drainTimedOut(state: CaptureState) {
        if (state != CaptureState.OPENING) cutOff("The capture did not end in time", recognizerHeard = false)
    }

    /**
     * The recorder closes while its capture, in [state], still runs. One that only listens
     * ([CaptureState.LISTENING]: the microphone is open and the speech model still loads) has
     * transcribed nothing of what it recorded, and never will. One that transcribes is cut off as
     * [RecordingEnd.atClose] says, with what its recognition still had open.
     */
    fun closedWhileCapturing(state: CaptureState, recognizerHeard: () -> Boolean) {
        if (state == CaptureState.LISTENING) cutOff("The recorder closed before the speech model was in", recognizerHeard())
    }

    private fun cutOff(reason: String, recognizerHeard: Boolean) {
        captureFailed(CaptureCutOffException(reason), current = true, recognizerHeard = recognizerHeard)
    }
}

/** A capture that ended before what the microphone gave it was transcribed ([TranscriptGaps]). */
internal class CaptureCutOffException(message: String) : Exception(message)
