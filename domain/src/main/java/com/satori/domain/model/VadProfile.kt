package com.satori.domain.model

/**
 * Which voice detection values split audio into the stretches of speech that are transcribed.
 */
enum class VadProfile {
    /**
     * While recording: the threshold, minimum silence and maximum speech set in Advanced settings
     * ([AppSettings.vadThreshold], [AppSettings.vadMinSilenceDuration],
     * [AppSettings.vadMaxSpeechDuration]), so each line appears soon after it is said.
     */
    LIVE,

    /**
     * Transcribe again, on the audio a recording kept: fixed values for a whole file. Nothing has
     * to appear while speaking, so the detection can wait out a longer pause and keep a phrase
     * together, which gives the speech model more context.
     */
    FILE
}
