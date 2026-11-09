package com.satori.domain.model

/**
 * The language a recording's capture is in, as its recorder holds it ([RecordingLanguage]). The
 * capture reads [current] as it loads its speech model and again between two utterances, so a
 * switch while recording ("Record as") has the next stretch of speech transcribed by the model of
 * the new language ([RecordingLanguage.liveModel]). The microphone, the audio waiting to be
 * transcribed and the kept audio go on meanwhile: the capture is never stopped or started again
 * for a switch.
 *
 * Read and called on the capture's own threads.
 */
interface CaptureLanguage {
    /** The language the next stretch of speech is in. */
    val current: RecordingLanguage

    /**
     * The model of [wanted] didn't load while recording, after a switch: the capture went on in
     * [instead], with the model it had, and tries [wanted] again only once [current] has been
     * another language in between. The recorder takes the switch back and says so.
     */
    fun refused(wanted: RecordingLanguage, instead: RecordingLanguage) {}

    companion object {
        /** English or German throughout, as every recording was before a language could be chosen. */
        val ENGLISH_OR_GERMAN: CaptureLanguage = object : CaptureLanguage {
            override val current: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN
        }
    }
}
