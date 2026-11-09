package com.satori.domain.model

/**
 * The language a recording is made in: its category's, English or German, or Malayalam. It is
 * chosen as the recording starts and can be changed while it records and on its page; it is
 * stored with the recording ([TranscriptionSession.language]). It decides the speech model that
 * transcribes the recording ([liveModel]) and how the AI reads its transcript.
 *
 * The notes language ([NotesLanguage]) is another thing: the language the AI writes in. A
 * Malayalam recording's notes, name, to-dos and map topics are written in English (the owner's
 * decision, 2026-10-14), while its transcript stays Malayalam.
 *
 * @property code What is stored with the recording: null for [ENGLISH_OR_GERMAN], which every
 *   recording made before the language was stored is too, else an ISO 639-1 code
 * @property liveModel The speech model that transcribes the recording while it is recorded, with
 *   the live voice detection and two threads. It takes the other one's place for the recording,
 *   never loads beside it: a switch of the language while recording frees the one and loads the
 *   other between two utterances.
 * @property writesLiveNotes Whether the AI writes notes while the recording is made. Not for
 *   Malayalam: the small live model (Gemma 3 1B) reads it poorly (IndicGenBench 41.4, against 57.2
 *   for Gemma 3 4B), so its notes are all written after Stop, by the finishing's model.
 */
enum class RecordingLanguage(val code: String?, val liveModel: SpeechModelId, val writesLiveNotes: Boolean) {
    /**
     * English or German, the languages of the categories: Parakeet v3 finds which while it
     * transcribes, line by line, and the notes follow the language found ([NotesLanguage.AUTO]).
     */
    ENGLISH_OR_GERMAN(null, SpeechModelId.PARAKEET_V3, writesLiveNotes = true),

    /**
     * Malayalam, transcribed by AI4Bharat's Malayalam model ([SpeechModelId.INDICCONFORMER_ML])
     * once it is on the phone, which writes Malayalam script only: English words said in it come
     * out in Malayalam letters.
     */
    MALAYALAM("ml", SpeechModelId.INDICCONFORMER_ML, writesLiveNotes = false);

    /**
     * This language, when the phone can record in it now; else English or German, the language
     * every set-up phone has the model of. Malayalam needs its own model on the phone
     * ([malayalamOnPhone]): a choice stored while it was there (the home screen's, the quick
     * key's) records in English or German while it isn't, and in Malayalam again once it is back.
     */
    fun usableWith(malayalamOnPhone: Boolean): RecordingLanguage =
        if (this == MALAYALAM && !malayalamOnPhone) ENGLISH_OR_GERMAN else this

    /**
     * The other language a recording can be made in: Malayalam for English or German, and English
     * or German for Malayalam. A tap on the recorder's language key switches the recording to it.
     */
    val other: RecordingLanguage
        get() = if (this == MALAYALAM) ENGLISH_OR_GERMAN else MALAYALAM

    companion object {
        /**
         * The speech models that transcribe recordings as they are made, one per language. A
         * transcript one of them made is the one the recording was made with: no better
         * transcript after Stop comes from them (Parakeet v2 and Cohere Transcribe make those).
         */
        val LIVE_MODELS: Set<SpeechModelId> = entries.mapTo(LinkedHashSet()) { it.liveModel }

        /**
         * The language stored as [code]: [ENGLISH_OR_GERMAN] for null, and for a code this version
         * doesn't know, which only a database changed by hand holds (Room opens no newer one): the
         * categories' languages, transcribed by Parakeet v3, which takes any language it hears.
         */
        fun fromCode(code: String?): RecordingLanguage =
            entries.firstOrNull { it.code != null && it.code == code } ?: ENGLISH_OR_GERMAN

        /** The language [model] transcribes recordings in ([liveModel]), or null for a model that never runs live. */
        fun ofLiveModel(model: SpeechModelId): RecordingLanguage? = entries.firstOrNull { it.liveModel == model }

        /**
         * The language a recording is in once [model] made its transcript (Transcribe again): the
         * language [model] transcribes live ([ofLiveModel]), and English or German for the models
         * that only transcribe again, which know those (Parakeet v2, Cohere Transcribe). So the
         * Malayalam model makes a recording Malayalam, and Parakeet makes it English or German
         * again.
         */
        fun transcribedBy(model: SpeechModelId): RecordingLanguage = ofLiveModel(model) ?: ENGLISH_OR_GERMAN
    }
}
