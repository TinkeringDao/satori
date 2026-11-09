package com.satori.domain.model

/**
 * How far the capture that runs has come ([com.satori.domain.repository.SttRepository.captureState]),
 * in this order. The start cue goes with the microphone going live, not with the press, and
 * finishing an older recording leaves the phone to a capture that is still opening.
 */
enum class CaptureState {
    /** No capture runs. */
    IDLE,

    /** A capture has begun: the microphone opens, and the speech model and the VAD load. */
    OPENING,

    /**
     * The microphone delivers audio, which is kept until the speech model can take it: as the
     * capture starts, and again while it changes its speech model after a switch of the recording's
     * language (the one model freed, the other loading), which the finishing of an older recording
     * gives way to as to a start.
     */
    LISTENING,

    /** The audio is being transcribed as it comes. */
    TRANSCRIBING
}
