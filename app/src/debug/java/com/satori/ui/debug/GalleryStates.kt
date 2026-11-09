package com.satori.ui.debug

import com.satori.domain.language.MalayalamText
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.main.LlmStatus
import com.satori.presentation.main.MainUiState
import com.satori.presentation.main.RecorderError

/**
 * Recorder states for the debug gallery, with made-up content. Every state of the recorder has a
 * name here. The cover screen shows the same states: shrink the window (`emu.py cover on`) and
 * the compact layout is drawn. The recordings are voice notes, filed under the starter categories
 * ([starters]); while one records, its chip opens "Record as" and switches it in the gallery too.
 */
internal object GalleryStates {

    private const val SESSION = "gallery"
    private val start = System.currentTimeMillis() - 4 * 60_000L

    private val lines = listOf(
        "Okay, quick notes from the call with the landlord.",
        "He agreed to fix the heating before the end of the month.",
        "The rent stays the same until March, then it goes up by forty euros.",
        "I still need to send him the photos of the bathroom window.",
        "And ask whether the garage spot is still free."
    )

    private val segments = lines.mapIndexed { i, text ->
        TranscriptionSegment(id = "s$i", sessionId = SESSION, text = text, timestamp = start + i * 30_000L, isComplete = true)
    }

    private val partial = TranscriptionSegment(
        id = "partial", sessionId = SESSION, text = "Remind me to call the bank about",
        timestamp = start + 170_000L, isComplete = false
    )

    // Made up, as the English ones: the heating, the rent, the photos of the window.
    private val malayalamLines = listOf(
        "മാസാവസാനത്തിനു മുമ്പ് ഹീറ്റിംഗ് ശരിയാക്കാമെന്ന് അദ്ദേഹം സമ്മതിച്ചു",
        "മാർച്ച് വരെ വാടക അതേപടി തുടരും",
        "ബാത്ത്റൂം ജനലിന്റെ ഫോട്ടോകൾ ഇനിയും അയക്കണം"
    )

    private val malayalamSegments = malayalamLines.mapIndexed { i, text ->
        TranscriptionSegment(id = "m$i", sessionId = SESSION, text = text, timestamp = start + i * 30_000L, isComplete = true)
    }

    // A German meeting translated into English, made up: each translation cites the lines it read.
    private val germanLines = listOf(
        "Guten Morgen, wir fangen mit der Planung für die Woche an.",
        "Die Regale für die Werkstatt kommen am Dienstag.",
        "Bitte schickt mir bis morgen eure Urlaubstage."
    )

    private val germanSegments = germanLines.mapIndexed { i, text ->
        TranscriptionSegment(id = "g$i", sessionId = SESSION, text = text, timestamp = start + i * 30_000L, isComplete = true)
    }

    private val translations = listOf(
        LlmInsight(
            id = "t1", sessionId = SESSION, title = null, content = "Good morning, let's start with the plan for the week.",
            tasks = null, timestamp = start + 40_000L, sourceSegmentIds = listOf("g0")
        ),
        LlmInsight(
            id = "t2", sessionId = SESSION, title = null,
            content = "The shelves for the workshop arrive on Tuesday. Please send me your days off by tomorrow.",
            tasks = null, timestamp = start + 100_000L, sourceSegmentIds = listOf("g1", "g2")
        )
    )

    private val insight = LlmInsight(
        id = "i1", sessionId = SESSION, title = "Heating repair and rent",
        content = "The landlord will fix the heating this month. The rent stays the same until March, then rises by 40 euros.",
        tasks = "[\"Send the photos of the bathroom window\",\"Ask whether the garage spot is free\"]",
        timestamp = start + 150_000L, sourceSegmentIds = listOf("s0", "s1", "s2")
    )

    /**
     * Models loaded, nothing recorded yet, the recorder at rest; in English or German, without the
     * Malayalam model, as on the emulator.
     */
    private val ready = MainUiState(
        isInitializing = false,
        llmStatus = LlmStatus.Ready,
        sessionMode = RecordingMode.SIMPLE_LISTENING,
        sessionCategoryId = "voice-note",
        language = RecordingLanguage.ENGLISH_OR_GERMAN
    )

    private val live = ready.copy(
        isRecording = true,
        recordingDurationMillis = 207_000L,
        completedSegments = segments,
        currentPartialSegment = partial,
        insights = listOf(insight)
    )

    /** Stopped: the transcript is complete. */
    private val stopped = live.copy(isRecording = false, currentPartialSegment = null, recordingDurationMillis = 212_000L)

    /**
     * A voice note recorded with the Malayalam model on the phone, so the recorder has its language
     * key: "EN" for these English lines.
     */
    private val keyEn = live.copy(malayalamOnPhone = true)

    /** Too few words yet to tell English from German: "EN/DE". */
    private val keyEnDe = keyEn.copy(
        recordingDurationMillis = 9_000L,
        completedSegments = listOf(segments.first().copy(text = "Okay, quick notes.")),
        insights = emptyList()
    )

    /** German lines: "DE". */
    private val keyDe = keyEn.copy(
        completedSegments = germanSegments,
        currentPartialSegment = partial.copy(text = "Und am Freitag ist das Büro"),
        insights = emptyList()
    )

    /** In Malayalam: the key filled; its lines as they are stored, so as the recorder draws them ([MalayalamText]). */
    private val keyMl = keyEn.copy(
        language = RecordingLanguage.MALAYALAM,
        completedSegments = malayalamSegments.map { it.copy(text = MalayalamText.clean(it.text)) },
        currentPartialSegment = null,
        insights = emptyList()
    )

    /** Past an hour, where the cover's chip has the least room: 1:02:07. */
    private const val PAST_AN_HOUR_MS = 3_727_000L

    val byName: Map<String, MainUiState> = linkedMapOf(
        // Before recording
        "recording_ready" to ready,
        "recording_preparing" to ready.copy(isInitializing = true, isStartingRecording = true, llmStatus = LlmStatus.Loading),
        "recording_starting" to ready.copy(isStartingRecording = true),

        // Recording
        "recording_live_empty" to ready.copy(isRecording = true, recordingDurationMillis = 4_000L),
        // The microphone is open and the speech model still loads.
        "recording_listening" to ready.copy(isRecording = true, listening = true, recordingDurationMillis = 2_000L),
        "recording_live" to live,
        "recording_live_long" to live.copy(
            recordingDurationMillis = 3_727_000L,
            completedSegments = (0 until 6).flatMap { round ->
                segments.map { it.copy(id = "${it.id}-$round", timestamp = it.timestamp + round * 200_000L) }
            }
        ),
        "recording_meeting_live" to live.copy(sessionMode = RecordingMode.SHORT_MEETING, sessionCategoryId = "meeting"),
        // The chip's "Record as" sheet open over a voice note being recorded: the categories alone,
        // and with the Malayalam model on the phone its language row too.
        "recording_record_as" to live,
        "recording_record_as_ml" to live.copy(malayalamOnPhone = true),
        // A recording in Malayalam: its status line says so, and its notes come after Stop.
        "recording_malayalam_live" to live.copy(
            language = RecordingLanguage.MALAYALAM,
            malayalamOnPhone = true,
            completedSegments = malayalamSegments,
            currentPartialSegment = null,
            insights = emptyList()
        ),
        // The language key, with the Malayalam model on the phone: EN/DE until the transcript tells
        // English from German, EN or DE then, Malayalam filled; each also past an hour, where the
        // cover's chip ends in an ellipsis before the key moves. Over the lock screen ([locked]) it
        // switches only on a hold, as the chip opens, and a tap says how.
        "recording_key_en_de" to keyEnDe,
        "recording_key_en" to keyEn,
        "recording_key_de" to keyDe,
        "recording_key_ml" to keyMl,
        "recording_key_locked" to keyEn,
        "recording_key_en_de_long" to keyEnDe.copy(recordingDurationMillis = PAST_AN_HOUR_MS),
        "recording_key_en_long" to keyEn.copy(recordingDurationMillis = PAST_AN_HOUR_MS),
        "recording_key_de_long" to keyDe.copy(recordingDurationMillis = PAST_AN_HOUR_MS),
        "recording_key_ml_long" to keyMl.copy(recordingDurationMillis = PAST_AN_HOUR_MS),
        "recording_key_locked_long" to keyEn.copy(recordingDurationMillis = PAST_AN_HOUR_MS),
        // Stop pressed: the recording can't change its language any more, and the key is gone.
        "recording_key_saving" to keyEn.copy(isRecording = false, saving = true, currentPartialSegment = null),
        // Over the lock screen ([locked]): the grille and the chip act only on a hold, a tap says how.
        "recording_locked" to live,
        // A translation has no chip: it can't be switched. Its status line says the languages in and
        // out, the inner screen opens on its translation, the cover shows the newest one.
        "recording_translate_live" to live.copy(
            sessionMode = RecordingMode.REAL_TIME_TRANSLATION,
            sessionCategoryId = null,
            translationTarget = "en",
            completedSegments = germanSegments,
            currentPartialSegment = null,
            insights = translations
        ),
        // The same while the next line is being said: on the cover it shows under the translation.
        "recording_translate_partial" to live.copy(
            sessionMode = RecordingMode.REAL_TIME_TRANSLATION,
            sessionCategoryId = null,
            translationTarget = "en",
            completedSegments = germanSegments,
            currentPartialSegment = partial.copy(text = "Und am Freitag ist das Büro wegen des Ausflugs"),
            insights = translations
        ),
        // Before the first translation, and before the language that goes in is known.
        "recording_translate_first" to live.copy(
            sessionMode = RecordingMode.REAL_TIME_TRANSLATION,
            sessionCategoryId = null,
            translationTarget = "en",
            completedSegments = emptyList(),
            currentPartialSegment = partial.copy(text = "Guten Morgen, wir fangen"),
            insights = emptyList()
        ),
        // English said into an English translation: the status line warns.
        "recording_translate_same" to live.copy(
            sessionMode = RecordingMode.REAL_TIME_TRANSLATION,
            sessionCategoryId = null,
            translationTarget = "en",
            insights = emptyList()
        ),
        "recording_batch_waiting" to live.copy(insightStrategy = InsightStrategy.END_OF_SESSION, insights = emptyList()),
        // An older recording is being finished, so no live note yet.
        "recording_notes_waiting" to live.copy(insights = emptyList(), notesWaiting = true),

        // After Stop, until the recording is saved and the recorder goes home
        "recording_saving" to stopped.copy(saving = true),

        // The AI can't write notes
        "recording_no_model" to live.copy(llmStatus = LlmStatus.Missing, insights = emptyList()),
        "recording_ai_off" to live.copy(
            llmStatus = LlmStatus.Off,
            settings = AppSettings(llmEnabled = false),
            insights = emptyList()
        ),
        "recording_ai_failed" to live.copy(llmStatus = LlmStatus.Unavailable, insights = emptyList()),
        // The first run downloads the AI model: the notes are said to follow, with nothing to tap.
        "recording_ai_coming" to live.copy(llmStatus = LlmStatus.Missing, aiModelPending = true, insights = emptyList()),

        // Errors, one for each way forward
        "recording_error" to ready.copy(error = RecorderError.SpeechModelMissing),
        "recording_speech_model_damaged" to ready.copy(error = RecorderError.SpeechModelDamaged),
        "recording_speech_model_failed" to ready.copy(error = RecorderError.SpeechModelFailed),
        "recording_service_refused" to ready.copy(error = RecorderError.ServiceRefused),
        "recording_transcription_failed" to stopped.copy(error = RecorderError.TranscriptionFailed),
        // The microphone, asked for in place: not allowed yet, refused once, refused for good.
        "recording_mic_ask" to ready.copy(microphone = MicrophoneState.NOT_ALLOWED),
        "recording_mic_denied" to ready.copy(microphone = MicrophoneState.REFUSED_ONCE),
        "recording_mic_settings" to ready.copy(microphone = MicrophoneState.REFUSED_FOR_GOOD),

        // The first run's first recording: its coach line until the 20th word, and the AI model
        // still to come, so its notes are said to follow once it is in.
        "firstrun_s4_coach" to ready.copy(
            isRecording = true,
            recordingDurationMillis = 42_000L,
            completedSegments = listOf(
                TranscriptionSegment(id = "c0", sessionId = SESSION, text = "So this is my first note.", timestamp = start, isComplete = true)
            ),
            currentPartialSegment = partial.copy(text = "I want to see how fast the words show up"),
            llmStatus = LlmStatus.Missing,
            firstRecordingCoach = true,
            aiModelPending = true
        )
    )

    /** The state called [name], also without the "recording_" in front (`notes_waiting`). */
    fun find(name: String): MainUiState? = byName[name] ?: byName["recording_$name"]

    /** Whether the state called [name] opens with the "Record as" sheet up. */
    fun opensTypeSheet(name: String): Boolean = name.removePrefix("recording_") in setOf("record_as", "record_as_ml")

    /** Whether the state called [name] is drawn as over the lock screen, where a hold stops or switches the recording. */
    fun locked(name: String): Boolean =
        name == "recording_locked" || name == "locked" || name.removePrefix("recording_").startsWith("key_locked")

    /**
     * The four starter categories, as a fresh install has them, each named by [name] from its id
     * (the app's names, in its language).
     */
    fun starters(name: (String) -> String): List<RecordingCategory> = listOf(
        RecordingCategory("voice-note", name("voice-note"), RecordingMode.SIMPLE_LISTENING, sortOrder = 0, createdAt = 0L),
        RecordingCategory("meeting", name("meeting"), RecordingMode.SHORT_MEETING, sortOrder = 1, createdAt = 0L),
        RecordingCategory("idea", name("idea"), RecordingMode.SIMPLE_LISTENING, sortOrder = 2, createdAt = 0L),
        RecordingCategory("todo", name("todo"), RecordingMode.SIMPLE_LISTENING, sortOrder = 3, createdAt = 0L)
    )
}
