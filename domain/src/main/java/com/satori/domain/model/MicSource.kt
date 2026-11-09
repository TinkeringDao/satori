package com.satori.domain.model

/**
 * The microphone input speech is captured from ([AppSettings.micSource]), read as each capture
 * starts.
 */
enum class MicSource {
    /**
     * Android's input for speech recognition (`MediaRecorder.AudioSource.VOICE_RECOGNITION`). The
     * Android 16 compatibility definition (5.4.2) requires it to have no noise reduction and no
     * automatic gain control by default, so the speech model hears the sound as it was. On the
     * razr that is too quiet: speech arrives about 20 dB lower than on [MIC], at -55 to -64 dBFS,
     * and the speech model misses most of it (2026-10-13). It was the default from 0.4.0 to 0.5.2.
     * When the phone can't open it, the capture falls back to [MIC].
     */
    VOICE_RECOGNITION,

    /**
     * The general microphone input (`MediaRecorder.AudioSource.MIC`), the default, as it was
     * before 0.4.0. The phone may process it, with no rule on how; on the razr it gives speech the
     * level the speech model needs.
     */
    MIC,

    /**
     * Android's input for calls (`MediaRecorder.AudioSource.VOICE_COMMUNICATION`), with the
     * phone's noise reduction and echo cancelling, which can help in a loud place and cost words in
     * a quiet one. When the phone can't open it, the capture falls back to [MIC].
     */
    VOICE_COMMUNICATION;

    companion object {
        /**
         * Whether Settings offers [VOICE_RECOGNITION]. Not since 0.7.0 (the owner's decision of
         * 2026-10-16, question 5 of the release brief, its default): it recorded the razr's
         * speech about 20 dB too quietly and the speech model missed most words, and it was the
         * default from 0.4.0 to 0.5.2, so the list kept a way back to failed recordings. True
         * offers it again, with its warning, and stops [offered] from reading it as [MIC].
         */
        const val OFFERS_SPEECH_RECOGNITION_INPUT = false

        /** The inputs Settings offers, in its order: the recommended one first. */
        val OFFERED: List<MicSource> = if (OFFERS_SPEECH_RECOGNITION_INPUT) {
            listOf(MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION)
        } else {
            listOf(MIC, VOICE_COMMUNICATION)
        }

        /**
         * The input a stored [source] records with: itself when Settings offers it ([OFFERED]),
         * else [MIC], so a speech recognition input an earlier version stored becomes the usual
         * one.
         */
        fun offered(source: MicSource): MicSource = if (source in OFFERED) source else MIC
    }
}
