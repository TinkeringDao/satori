package com.satori.domain.model

/**
 * Application configuration settings.
 *
 * A voice detection value an earlier version stored at its default then (threshold 0.5, minimum
 * silence 0.5 s, maximum speech 10 s) reads as today's default: those versions stored every
 * setting at each change, and the owner never changed these.
 *
 * @property simpleListeningIntervalSeconds Seconds between the live notes of a voice note; the
 *   meeting's, the long meeting's (in minutes) and the translation's follow it
 * @property llmModelPath Absolute path to the LLM (GGUF) model file
 * @property llmEnabled Whether AI notes are on: off, no AI model is loaded while recording and no
 *   notes are written
 * @property themeMode App theme mode (SYSTEM/LIGHT/DARK)
 * @property vadMinSilenceDuration Minimum silence duration (seconds) to consider speech ended (0.1-2.0, default 0.5)
 * @property vadMaxSpeechDuration Maximum continuous speech duration (seconds) before forced segmentation (3.0-30.0,
 *   default 20.0). Past it the detector cuts at the next short dip, often mid-phrase, and each piece is transcribed
 *   without the other; sherpa-onnx's own examples for long audio use 20 s.
 * @property vadThreshold VAD detection threshold (0.2-0.9, default 0.4). Lower = more sensitive
 * @property micSource The microphone input speech is captured from; read as each capture starts.
 *   [MicSource.MIC] by default, the phone's usual input. [MicSource.VOICE_RECOGNITION], the default
 *   from 0.4.0 to 0.5.2, records speech on the razr about 20 dB lower (-55 to -64 dBFS): on
 *   2026-10-13 the speech model heard 3 words of it in 23 s, where MIC gave 56 words in 21 s. A
 *   stored input reads as stored, that one too: only an unset one takes the default.
 * @property transcribeAfterStop The speech model that transcribes a recording again after Stop, for a
 *   better transcript than the live one, or null for none (the default): [SpeechModelId.PARAKEET_V2_EN]
 *   for recordings detected as English, [SpeechModelId.COHERE_TRANSCRIBE] for English and German ones.
 *   It runs only when the model is on the phone, the recording kept its audio and its language suits the
 *   model ([SpeechModelId.understands]). While recording, the recording's language decides the
 *   model ([RecordingLanguage.liveModel]): Parakeet v3 for English or German.
 * @property savePlace Whether a recording stores where it starts ([TranscriptionSession.place]), read by
 *   GPS on the phone. Off by default: it needs the location permission, which the owner grants by turning
 *   it on.
 * @property notesLanguage Language of the AI's notes, summaries and names; [NotesLanguage.AUTO]
 *   follows what was said. Translation uses [translationTargetLanguage] instead.
 * @property homeCategoryId What the one-tap Record on the home screen records as: a category id,
 *   or [RecordTargets.TRANSLATE_ID]. It is the last thing recorded as; see [RecordTargets.resolve]
 *   for an id that no longer exists. Only SettingsRepository.setHomeCategoryId writes it, so a
 *   screen's copy of the settings can't undo the choice.
 * @property homeLanguage The language the one-tap Record records in, beside [homeCategoryId]: the
 *   last one a recording was started in from home's "Record as". Malayalam records in English or
 *   German while its model isn't on the phone ([RecordingLanguage.usableWith]). Only
 *   SettingsRepository.setHomeLanguage writes it.
 * @property vibrateOnStartStop Whether the phone buzzes once when the microphone goes live and
 *   twice when it goes off, however the recording was started or stopped. On by default: a
 *   recording started with the power key in a pocket is otherwise not felt at all.
 * @property keepScreenOnWhileRecording Whether the recorder keeps the screen from timing out
 *   while the microphone is live, to watch the transcript; the power key still turns it off, and
 *   the recording goes on. On by default, so a recording can be read on the screen as it is made.
 *   Earlier versions had it off by default and stored every setting at each change, so a false
 *   one of them stored reads as on.
 * @property keepAudio Whether a recording keeps its audio (16 kHz mono WAV, about 2 MB a minute),
 *   so it can be played and transcribed again; read as each capture starts. On by default: the
 *   recordings are the owner's own, and their audio is what a better transcript is made from.
 * @property audioCompressAfterDays After how many days the audio of a recording is compressed
 *   (AAC, about 8 times smaller; it still plays and can be transcribed again), or null for never,
 *   the default. One of [AUDIO_COMPRESS_CHOICES]; a stored value that isn't one reads as never.
 *   Audio the owner keeps ([RecordingAudio.kept]) is never compressed.
 * @property audioDeleteAfterDays After how many days the audio of a recording is deleted (its
 *   transcript, notes and to-dos stay), or null for never, the default. One of
 *   [AUDIO_DELETE_CHOICES]; a stored value that isn't one reads as never. Audio the owner keeps is
 *   never deleted.
 */
data class AppSettings(
    // Mode-specific intervals
    val simpleListeningIntervalSeconds: Int = 60,
    val shortMeetingIntervalSeconds: Int = 60, // 60 s default gives the model enough context per inference
    val longMeetingIntervalMinutes: Int = 5, // Default: 5 min (options: 3, 5, 10)
    val translationIntervalSeconds: Int = 30, // 30 s default balances real-time feel with accuracy

    // The shipped system prompts in the app language, read from the resources; never stored
    val simpleListeningSystemPrompt: String = "",
    val shortMeetingSystemPrompt: String = "",
    val longMeetingSystemPrompt: String = "",
    val translationSystemPrompt: String = "",

    // Translation settings — stores BCP-47 locale code (see SupportedLanguages)
    val translationTargetLanguage: String = SupportedLanguages.DEFAULT.code,

    // Language of the AI notes; AUTO detects it from the transcript (LanguageDetector).
    val notesLanguage: NotesLanguage = NotesLanguage.AUTO,

    // What the one-tap Record starts, and in which language; written only by their setters.
    val homeCategoryId: String = RecordTargets.DEFAULT_ID,
    val homeLanguage: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,

    // General settings
    val llmModelPath: String = "",
    val llmEnabled: Boolean = true, // When false, LLM is never loaded during recording sessions
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // LLM model variant — determines which GGUF file is downloaded and used.
    // Default Q8_0 preserves backward compatibility for existing installs;
    // DeviceTierDetector surfaces the recommended variant in Settings UI.
    val llmModelVariant: LlmModelVariant = LlmModelVariant.Q8_0,

    // Recording: how a start and a stop feel, and whether the screen stays on meanwhile.
    val vibrateOnStartStop: Boolean = true,
    val keepScreenOnWhileRecording: Boolean = true,
    // Keep the audio of each recording for playback and Transcribe again.
    val keepAudio: Boolean = true,
    // When older audio is compressed and when it is deleted (null: never).
    val audioCompressAfterDays: Int? = null,
    val audioDeleteAfterDays: Int? = null,
    // Store where each recording starts.
    val savePlace: Boolean = false,

    // VAD parameters for better accuracy vs speed trade-off
    val vadMinSilenceDuration: Float = 0.5F,
    val vadMaxSpeechDuration: Float = 20.0F,
    val vadThreshold: Float = 0.4F,

    // Speech: the microphone input, and the model that transcribes again after Stop (null: none).
    val micSource: MicSource = MicSource.MIC,
    val transcribeAfterStop: SpeechModelId? = null,

    // Per-mode insight strategy: notes written while recording or after Stop (Advanced settings).
    val simpleListeningDefaultStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    val shortMeetingDefaultStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    val longMeetingDefaultStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    val translationDefaultStrategy: InsightStrategy = InsightStrategy.REAL_TIME,


    // Adaptive conservative-threads state — persisted across sessions. Only the dedicated
    // SettingsRepository setters write it: updateSettings() would let a screen's stale copy of
    // the settings undo what LlmRepositoryImpl learned in the background.
    //
    // memoryConstrainedDetected: cached result of the post-load isMemoryConstrained() check.
    // Sticky-true: once true, stays true until a future post-load check returns false.
    // Default false so fresh installs begin with the auto thread count.
    val memoryConstrainedDetected: Boolean = false,

    // conservativeThreadsCharThreshold: smallest input size (chars) at which a RAM-constrained
    // inference was observed, multiplied by a safety margin (see LlmRepositoryImpl).
    // Future inferences with chars >= threshold proactively use conservative threads.
    // Default Int.MAX_VALUE means "not yet calibrated" — no proactive throttling until learned.
    val conservativeThreadsCharThreshold: Int = Int.MAX_VALUE
) {
    companion object {
        /** What [audioCompressAfterDays] can be set to, in the order Settings offers it; null is never. */
        val AUDIO_COMPRESS_CHOICES: List<Int?> = listOf(null, 7, 14, 30)

        /** What [audioDeleteAfterDays] can be set to, in the order Settings offers it; null is never. */
        val AUDIO_DELETE_CHOICES: List<Int?> = listOf(null, 15, 30, 60, 90)
    }
}
