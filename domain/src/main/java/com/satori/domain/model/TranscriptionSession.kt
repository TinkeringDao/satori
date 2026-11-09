package com.satori.domain.model

/**
 * Represents a transcription session - a single recording session with its own context.
 *
 * Each session maintains an isolated context for both STT segments and LLM insights.
 * When a new session is created, the LLM context is reset to prevent cross-contamination.
 *
 * @property id Unique identifier for this session (UUID)
 * @property name Optional user-provided name (e.g., "Meeting with client", "History lecture notes")
 * @property createdAt Unix timestamp (milliseconds) when this session was created
 * @property lastModifiedAt Unix timestamp (milliseconds) of last update (segment/insight added)
 * @property recordingMode Mode of recording (Simple, Short Meeting, Long Meeting, Translation)
 * @property durationMs Total recording duration in milliseconds (0 until the session is stopped)
 * @property insightStrategy Whether insights are generated periodically or in one batch at end of session
 * @property topic Optional main subject/topic for focused AI insights (e.g., "Q1 Budget Review")
 * @property zoneId Time zone the recording was made in (e.g. "Europe/Berlin"), so "tomorrow"
 *   resolves against the recording's own clock; null for recordings from before it was stored
 * @property categoryId The [RecordingCategory] it was recorded as, or null
 * @property place Where the recording started, or null: none was saved ([AppSettings.savePlace]
 *   off, no permission or no fix in time), it was removed, or the recording is from before places
 *   were stored
 * @property transcriptModel The [SpeechModelId.id] of the speech model that made the transcript
 *   the recording has now: the live model of its [language] for a new recording
 *   ([RecordingLanguage.liveModel]), the new language's once the language was switched while it
 *   recorded, the one chosen after Transcribe again. Null for recordings from before it was
 *   stored, all transcribed by Parakeet v3; [SpeechModelId.ofTranscript] reads it either way.
 * @property language The language it is made in ([RecordingLanguage]): English or German, as its
 *   category records and every recording from before it was stored, or Malayalam. Not
 *   [inputLanguage], the language that was spoken in it.
 * @property inputLanguage The language spoken in it, as the finishing found it in its whole
 *   transcript (com.satori.domain.language.LanguageDetector: "en", "de" or "ml"); empty
 *   until it is finished, and when the detector wasn't sure. Recordings from before 0.6.0 hold
 *   "it", an old default (Italian) that nothing ever replaced. Nothing reads it but the field
 *   log's reader, and since 0.8.0 the backfill of readings, which takes up recordings found
 *   Malayalam ("ml") as well as those made in it.
 * @property nameByOwner [name] is one the owner typed (the page's Rename), which the app's naming
 *   never replaces; false for the app's name and for none, and for a name stored before 0.8.0-dev3,
 *   when nothing recorded who typed it (design R9)
 */
data class TranscriptionSession(
    val id: String,
    val name: String?,
    val createdAt: Long,
    val lastModifiedAt: Long,
    val mode: RecordingMode = RecordingMode.SIMPLE_LISTENING,
    val inputLanguage: String = "",
    val outputLanguage: String? = null, // Translation mode: the target language. Other modes: the language of the notes once known ("en" or "de")
    val durationMs: Long = 0L,
    val insightStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    val topic: String? = null, // Optional main subject for focused AI insights
    val zoneId: String? = null,
    val categoryId: String? = null,
    val place: RecordingPlace? = null,
    val transcriptModel: String? = null,
    val language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
    val nameByOwner: Boolean = false
)
