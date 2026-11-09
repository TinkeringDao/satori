package com.satori.domain.model

/**
 * The values the voice detector (Silero, in sherpa-onnx) splits audio into utterances with, for
 * one [profile]. Each utterance is transcribed on its own, so where it is cut decides what context
 * the speech model hears. Past [maxSpeechDuration] sherpa-onnx cuts at the next short dip, often
 * mid-phrase; Satori also cuts any stretch of speech at 30 s itself, whatever these values are.
 *
 * @property profile Which use the values are for
 * @property threshold How sure the detector has to be that it hears speech, 0..1; lower picks up
 *   quieter speech
 * @property minSilenceDuration The pause that ends an utterance, in seconds
 * @property maxSpeechDuration The longest utterance before it is cut, in seconds
 */
data class VadParameters(
    val profile: VadProfile,
    val threshold: Float,
    val minSilenceDuration: Float,
    val maxSpeechDuration: Float
) {
    companion object {
        /**
         * Transcribe again. Nothing has to appear while speaking, so the detector waits out a
         * longer pause before it ends a line and keeps quieter word ends with a lower threshold:
         * a phrase that stays whole gives the speech model its context. sherpa-onnx's own
         * examples for long audio go further (threshold 0.2) and also cut at 20 s.
         */
        val FILE = VadParameters(VadProfile.FILE, threshold = 0.3F, minSilenceDuration = 0.7F, maxSpeechDuration = 20.0F)

        /**
         * The values for [profile]: for [VadProfile.LIVE] those of Advanced settings, as
         * [settings] has them (stored or default), and the fixed [FILE] ones for Transcribe again.
         */
        fun of(profile: VadProfile, settings: AppSettings): VadParameters = when (profile) {
            VadProfile.LIVE -> VadParameters(
                profile = VadProfile.LIVE,
                threshold = settings.vadThreshold,
                minSilenceDuration = settings.vadMinSilenceDuration,
                maxSpeechDuration = settings.vadMaxSpeechDuration
            )
            VadProfile.FILE -> FILE
        }
    }
}
