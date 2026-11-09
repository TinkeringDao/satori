package com.satori.domain.model

import java.util.Locale

/**
 * The speech models Satori can transcribe with. [id] is what is stored: with a transcript
 * ([TranscriptionSession.transcriptModel]) and in the settings ([AppSettings.transcribeAfterStop]).
 *
 * A recording in English or German is transcribed live by [LIVE], Parakeet v3, which finds the
 * language itself and is fast enough to run next to the AI model; one in Malayalam by
 * [INDICCONFORMER_ML] in its place ([RecordingLanguage.liveModel]). The others work on the audio
 * a recording kept, after Stop (Transcribe again): Parakeet v2 is English only, with fewer errors
 * than v3 on Indian-accented English in the 2026 Open ASR Leaderboard; Cohere Transcribe has
 * fewer still there and in German, but is about four times the size and slower: on the razr about
 * 3.7 times as long per second of audio as Parakeet, as the research behind these models had
 * ([com.satori.domain.usecase.stt.SpeechModelChoice.transcribeTime] has the phone's own
 * times). AI4Bharat's IndicConformer knows Malayalam alone: it is the model of the recordings made
 * in Malayalam ([RecordingLanguage.MALAYALAM]), which none of the others knows.
 *
 * The language lists are those of the models' cards on Hugging Face; Cohere's is also the list
 * sherpa-onnx accepts for it (offline-cohere-transcribe-model-config.cc, v1.13.8).
 *
 * @property id Stable id, stored with transcripts and settings; never changed once shipped
 * @property languages The languages it was trained on, as ISO 639-1 codes
 * @property findsLanguage Whether it can transcribe speech whose language isn't known, because it
 *   tells the language itself
 * @property languagePerStream Whether the recognizer has to be told the language of each stream
 *   (sherpa-onnx `setOption("language", code)`, with a code from [languages])
 * @property runsLive Whether it may transcribe while recording, next to the AI model; the others
 *   only transcribe again, after Stop
 */
enum class SpeechModelId(
    val id: String,
    val languages: Set<String>,
    val findsLanguage: Boolean,
    val languagePerStream: Boolean,
    val runsLive: Boolean
) {
    /** NVIDIA Parakeet TDT 0.6B v3, int8: 25 European languages, told apart by the model. */
    PARAKEET_V3(
        id = "parakeet-tdt-0.6b-v3-int8",
        languages = setOf(
            "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it",
            "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk"
        ),
        findsLanguage = true,
        languagePerStream = false,
        runsLive = true
    ),

    /** NVIDIA Parakeet TDT 0.6B v2, int8: English only. */
    PARAKEET_V2_EN(
        id = "parakeet-tdt-0.6b-v2-int8",
        languages = setOf("en"),
        findsLanguage = false,
        languagePerStream = false,
        runsLive = true
    ),

    /** Cohere Transcribe 03-2026, int8: 14 languages; it is told the one spoken in each stream. */
    COHERE_TRANSCRIBE(
        id = "cohere-transcribe-14-lang-int8",
        languages = setOf("ar", "de", "el", "en", "es", "fr", "it", "ja", "ko", "nl", "pl", "pt", "vi", "zh"),
        findsLanguage = false,
        languagePerStream = true,
        runsLive = false
    ),

    /**
     * AI4Bharat IndicConformer Malayalam 120M (MIT), the CTC head in int8, as exported for
     * sherpa-onnx by the community: Malayalam only. Its pieces are all Malayalam script, digits
     * included, so English words said in Malayalam come out in Malayalam letters, without
     * punctuation. It decodes greedily, so it doesn't listen for words to recognize. A fifth of
     * Parakeet v3's size, so it may run live; on the razr it transcribed a file at 0.10 to 0.12
     * times the audio's length on four threads (2026-10-14).
     */
    INDICCONFORMER_ML(
        id = "indicconformer-ml-int8",
        languages = setOf("ml"),
        findsLanguage = false,
        languagePerStream = false,
        runsLive = true
    );

    /**
     * Whether it can transcribe a recording in [language]: a code such as "en", "de", "en-IN" or
     * "de_AT", of which only the language counts, or null when the language isn't known (as
     * [com.satori.domain.language.LanguageDetector] answers for a short or mixed text).
     * An unknown language suits only a model that [findsLanguage]: Parakeet v2 knows only English,
     * and Cohere can't start without being told the language.
     */
    fun understands(language: String?): Boolean {
        val code = language?.substringBefore('-')?.substringBefore('_')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return findsLanguage
        return code in languages
    }

    companion object {
        /**
         * The live model of English and German recordings, the one every set-up phone has: the
         * recorder can't start without it. A Malayalam recording transcribes live with
         * [INDICCONFORMER_ML] instead ([RecordingLanguage.liveModel]).
         */
        val LIVE: SpeechModelId = PARAKEET_V3

        /** The model stored as [id], or null for null or an id this version doesn't know. */
        fun fromId(id: String?): SpeechModelId? = entries.firstOrNull { it.id == id }

        /**
         * The model that made a transcript stored with [transcriptModel]
         * ([TranscriptionSession.transcriptModel]): Parakeet v3 for null, the only model before
         * the id was stored, and null for an id this version doesn't know.
         */
        fun ofTranscript(transcriptModel: String?): SpeechModelId? =
            if (transcriptModel == null) PARAKEET_V3 else fromId(transcriptModel)
    }
}
