package com.satori.presentation.main

import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordRequest
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.presentation.firstrun.MicrophoneState

/**
 * UI state for the main screen.
 *
 * @property isRecording Whether audio recording and STT is currently active
 * @property saving Whether Stop is saving the recording: from Stop until its last words are saved
 *   and it is handed over to be finished in the background (normally well under a second, at
 *   most the drain guard). Recording is already stopped then (isRecording = false).
 * @property leaveWhenSaved Whether Back was pressed while [saving]: the recorder goes back once
 *   the recording is saved ([RecorderExit.BACK])
 * @property listening Whether the microphone is live but the speech model is still loading (the
 *   capture opens the microphone first) and no line has come yet: the status line says "Getting
 *   ready" until the model is in
 * @property notesWaiting Whether the AI is finishing an older recording, so no live note can be
 *   written now: the first one comes once it is done
 * @property isInitializing Whether the speech model's files are still being checked
 * @property isStartingRecording Whether a start is pending: the recorder was opened to record (or
 *   its record button was tapped while the models load) and recording has not begun. It waits
 *   for the models, Android's microphone window, or for the screen to be on show, since Android
 *   refuses the recording service in the background. False once recording has begun, and false
 *   when the start was given up (an error) or never asked for, and while the microphone isn't
 *   allowed and the recorder asks for it in place. A quick-key press reads this to tell "cancel
 *   the start" from "stop the recording".
 * @property microphone The microphone permission as the recorder last read it, on every return
 *   to the screen: not allowed, it explains that in place with the one way on (Android's window,
 *   or the app's settings page once Android no longer asks), and nothing can be recorded
 * @property completedSegments List of completed transcription segments (isComplete=true)
 * @property currentPartialSegment Current partial transcription segment being built (isComplete=false)
 * @property insights List of LLM-generated insights
 * @property settings Current application settings
 * @property error Why recording failed or can't start, null if nothing went wrong
 * @property llmStatus State of the AI model for this recording
 * @property insightStrategy The insight generation strategy for the current session
 * @property sessionMode The mode the recording records in: the one it started in, then the one
 *   "Record as" switched it to; null until it is read
 * @property sessionCategoryId The category the recording is recorded as, or null for none
 * @property categories The recording categories there are, in their order: for the label at the
 *   top ([recordingAs]) and the categories it can be switched to ([typeChoices])
 * @property language The language the recording is made in: the one it started in, then the one
 *   "Record as" switched it to; null until it is read
 * @property malayalamOnPhone Whether the Malayalam model is on the phone, so "Record as" offers
 *   Malayalam ([canChangeLanguage])
 * @property translationTarget A Translate recording's own target language, as it was created with
 *   it; null for the settings' one ([translatesInto]) and for any other recording
 * @property exit Where the recorder goes once it is done, or null while it stays: set once Stop
 *   has saved the recording ([afterHandOff]), and by a recorder Android restored for a recording
 *   that is gone (deleted as it was stopped in the background)
 * @property firstRecordingCoach The first recording of a fresh install: one line under the status
 *   coaches it ("Talk for a minute. Notes follow when you stop.") until its 20th word is heard or
 *   90 s have passed, and never again
 * @property aiModelPending The AI model is planned or downloading, as the first run fetches it
 *   after the first recording: the notes are said to follow once it is in, and no pill offers to
 *   get it
 */
data class MainUiState(
    val isRecording: Boolean = false,
    val saving: Boolean = false,
    val leaveWhenSaved: Boolean = false,
    val listening: Boolean = false,
    val notesWaiting: Boolean = false,
    val insightStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    val isInitializing: Boolean = true,
    val isStartingRecording: Boolean = false,
    val microphone: MicrophoneState = MicrophoneState.ALLOWED,
    val completedSegments: List<TranscriptionSegment> = emptyList(),
    val currentPartialSegment: TranscriptionSegment? = null,
    val insights: List<LlmInsight> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val error: RecorderError? = null,
    val llmStatus: LlmStatus = LlmStatus.Loading,
    val recordingDurationMillis: Long = 0,
    val sessionName: String? = null,
    val sessionMode: RecordingMode? = null,
    val sessionCategoryId: String? = null,
    val categories: List<RecordingCategory> = emptyList(),
    val language: RecordingLanguage? = null,
    val malayalamOnPhone: Boolean = false,
    val translationTarget: String? = null,
    val exit: RecorderExit? = null,
    val firstRecordingCoach: Boolean = false,
    val aiModelPending: Boolean = false
) {
    /**
     * The language a Translate recording is translated into: its own, else the settings' as a
     * translation starts with it ([RecordRequest.translationTarget]); null for any other recording.
     */
    val translatesInto: String?
        get() = if (sessionMode == RecordingMode.REAL_TIME_TRANSLATION) {
            translationTarget ?: RecordRequest.translationTarget(settings.translationTargetLanguage)
        } else {
            null
        }

    /** The category the recording is recorded as, while it has one that exists. */
    val recordingAs: RecordingCategory?
        get() = categories.firstOrNull { it.id == sessionCategoryId }

    /**
     * The categories the recording can be switched to ("Record as"), in their order: all but those
     * that translate, and none for a Translate recording, which keeps what it is
     * ([ChangeRecordingTypeUseCase.choices]).
     */
    val typeChoices: List<RecordingCategory>
        get() = ChangeRecordingTypeUseCase.choices(sessionMode, categories)

    /**
     * Whether the label at the top switches the recording to another category: while the
     * microphone is live, when there is another category to switch to. Before the recording starts
     * and once Stop is pressed it is only a label.
     */
    val canChangeType: Boolean
        get() = phase == RecorderPhase.Recording && typeChoices.any { it.id != sessionCategoryId }

    /**
     * Whether "Record as" switches the recording to another language: while the microphone is
     * live, for any recording but a translation ([ChangeRecordingLanguageUseCase.offered]), when
     * there is another language to record in: Malayalam only while its model is on the phone
     * ([malayalamOnPhone]), English or German for a recording in Malayalam always. Without the
     * model the recorder's sheet has no language row, nor a link to the download: on the cover it
     * pushed the categories below the fold, and over the lock screen Settings would hide the live
     * recorder. Home, the page and the power key's sheet have the link.
     */
    val canChangeLanguage: Boolean
        get() = phase == RecorderPhase.Recording && language != null && ChangeRecordingLanguageUseCase.offered(sessionMode) &&
            (malayalamOnPhone || language == RecordingLanguage.MALAYALAM)

    /**
     * Whether the label at the top opens "Record as": while the recording can be switched to another
     * category or to another language.
     */
    val opensRecordAs: Boolean
        get() = canChangeType || canChangeLanguage

    /** Whether no live note is written for the recording now, as its language gets none ([RecordingLanguage.writesLiveNotes]). */
    val liveNotesOff: Boolean
        get() = language?.writesLiveNotes == false

    /**
     * Whether the recorder is done and about to be replaced ([exit]). It is still drawn while the
     * next screen fades in, and must not offer to record again meanwhile.
     */
    val isLeaving: Boolean
        get() = exit != null

    /**
     * Whether the recording is being saved after Stop, or has been and the recorder is on its way
     * out ([isLeaving]). Back only marks the recorder to go back once saved ([leaveWhenSaved]),
     * and nothing can be started or fixed meanwhile.
     */
    val isFinalizingSession: Boolean
        get() = saving || isLeaving

    /** Where the recorder is, for the screen to draw: the record button, the status line, the hint. */
    val phase: RecorderPhase
        get() = when {
            isFinalizingSession -> RecorderPhase.Saving
            isRecording -> RecorderPhase.Recording
            microphone != MicrophoneState.ALLOWED || error?.recordingBlocked == true -> RecorderPhase.Blocked
            isInitializing || isStartingRecording -> RecorderPhase.Preparing
            else -> RecorderPhase.Ready
        }

    /**
     * Whether the AI model still has to be prepared because AI notes were off when the recorder
     * looked for the model and have been switched on since (from the recorder's own note, while
     * it got ready or while it records). Not once Stop has been pressed: the recording is the
     * finisher's from then on.
     */
    val isAiPreparationDue: Boolean
        get() = settings.llmEnabled && llmStatus == LlmStatus.Off && !isFinalizingSession

    /**
     * What keeps the AI from writing notes for this recording: [LlmStatus.Off], [LlmStatus.Missing]
     * or [LlmStatus.Unavailable]; null when it works or is still loading. The screen says it
     * once, with a way to fix it.
     */
    val aiProblem: LlmStatus?
        get() = when {
            !settings.llmEnabled -> LlmStatus.Off
            llmStatus == LlmStatus.Off || llmStatus == LlmStatus.Missing || llmStatus == LlmStatus.Unavailable -> llmStatus
            else -> null
        }

    /**
     * Whether the missing AI model is on its way ([aiModelPending]): AI notes are on, no model is on
     * the phone, and the first run downloads one. The recorder then says that the notes follow once
     * it is in, in place of the missing model's sentence, and offers no way to get it.
     */
    val aiModelComing: Boolean
        get() = aiModelPending && aiProblem == LlmStatus.Missing

    /**
     * Whether the first recording's coach line shows now ([firstRecordingCoach]): while the
     * microphone is live, until [COACH_WORDS] words are heard or [COACH_MS] have passed, so it never
     * comes back once gone. Not for a translation, whose notes come as it records.
     */
    val showsCoach: Boolean
        get() = firstRecordingCoach && phase == RecorderPhase.Recording && translatesInto == null &&
            recordingDurationMillis < COACH_MS && !heardWords(COACH_WORDS)

    /** Whether the recording's lines, the one being heard too, hold [count] words; counting stops there. */
    private fun heardWords(count: Int): Boolean {
        var words = 0
        for (segment in allSegments) {
            words += segment.text.split(WHITESPACE).count { it.isNotEmpty() }
            if (words >= count) return true
        }
        return false
    }

    /**
     * The state once Stop has dealt with the recording: handed it to the finisher, or deleted it
     * because nothing was said in it ([deleted]). The recorder then goes home, where the
     * recording's row shows how its finishing goes; the owner chose that over the recording's
     * page. It goes back instead when Back was pressed while saving ([leaveWhenSaved]), and home
     * with the reason when the recording is gone. While a quick-key start waits for this
     * ([startInFlight]) it goes nowhere: the start replaces it. A recording deleted then goes
     * without the reason: home is seen next only once the new recording is stopped, and "Nothing
     * was said" would then read as being about that one.
     */
    fun afterHandOff(deleted: Boolean, startInFlight: Boolean): MainUiState = copy(
        saving = false,
        exit = when {
            startInFlight -> RecorderExit.REPLACED
            deleted -> RecorderExit.NOTHING_SAID
            leaveWhenSaved -> RecorderExit.BACK
            else -> RecorderExit.HOME
        }
    )

    /**
     * The state once no quick-key start is under way any more: a recorder that was left for one
     * to replace ([RecorderExit.REPLACED]) goes home itself, as the start was given up (its
     * recording couldn't be created, or another recorder opened meanwhile). After a start that
     * did replace it, the recorder is no longer on top, and its way home goes nowhere.
     */
    fun afterStartGivenUp(): MainUiState =
        if (exit == RecorderExit.REPLACED) copy(exit = RecorderExit.HOME) else this

    /**
     * Returns all segments for display: completed + current partial (if exists).
     * Use this in UI to show the full transcription without duplicates.
     */
    val allSegments: List<TranscriptionSegment>
        get() = completedSegments + listOfNotNull(currentPartialSegment)

    /**
     * Returns insights sorted by timestamp (creation time) instead of arrival time.
     * This prevents insights from appearing out of order when longer insights
     * take more time to generate than shorter ones.
     */
    val sortedInsights: List<LlmInsight>
        get() = insights.sortedBy { it.timestamp }

    companion object {
        /** How long the coach line shows at most: 90 s into the first recording. */
        const val COACH_MS = 90_000L

        /** The words after which the coach line goes: those the final note asks for. */
        const val COACH_WORDS = GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT

        private val WHITESPACE = Regex("\\s+")

        /**
         * The microphone after the permission was read ([granted]) with the recorder at [before]:
         * allowed counts at once. Not allowed when it was, at the first read or after it was taken
         * back ("Only this time", Settings): refused once if Android says it was refused before
         * ([refusedBefore], its shouldShowRequestPermissionRationale), else not allowed yet. A
         * refusal stays as it was, as on Getting ready ([MicrophoneState.reread]).
         */
        fun microphoneRead(before: MicrophoneState, granted: Boolean, refusedBefore: Boolean): MicrophoneState = when {
            granted -> MicrophoneState.ALLOWED
            before == MicrophoneState.ALLOWED -> if (refusedBefore) MicrophoneState.REFUSED_ONCE else MicrophoneState.NOT_ALLOWED
            else -> before
        }
    }
}

/** A short confirmation the recorder shows once, at the bottom. */
sealed interface RecorderNotice {
    /** The recording is recorded as the category called [name] from now on. */
    data class RecordingAs(val name: String) : RecorderNotice

    /** The switch to another category or language couldn't be stored: the recording goes on as it was. */
    data object SwitchFailed : RecorderNotice

    /** The recording is in [language] from now on; one in Malayalam gets its notes after Stop. */
    data class RecordingIn(val language: RecordingLanguage) : RecorderNotice

    /**
     * The speech model of [wanted] didn't load after a switch: the recording goes on in [instead],
     * with the model it had, and is stored as in that language again.
     */
    data class LanguageRefused(val wanted: RecordingLanguage, val instead: RecordingLanguage) : RecorderNotice
}

/** Where the recorder goes once it is done ([MainUiState.exit]). */
enum class RecorderExit {
    /** Home: the recording is saved and is finished in the background. */
    HOME,

    /** Back: Back was pressed while the recording was being saved. */
    BACK,

    /** Home, which says that nothing was said: the recording is gone. */
    NOTHING_SAID,

    /** Nowhere: a quick-key start waited for the recording to be saved and replaces the recorder itself. */
    REPLACED
}

/** Where the recorder is in its life; see [MainUiState.phase]. */
enum class RecorderPhase {
    /** The models load, or a start waits for them, the permission or the screen. */
    Preparing,

    /** Ready to record, or at rest after an error that can be tried again. */
    Ready,

    /** The microphone is live. */
    Recording,

    /** Stopped; the last words are being saved and the recording handed over, and the recorder is on its way out. */
    Saving,

    /** Nothing can be recorded until something is fixed: the microphone is refused, or the error says so. */
    Blocked
}

/**
 * Why the recorder can't record. The screen words it, in the app's language, with a way forward.
 *
 * @property recordingBlocked Whether recording is out of the question in this recorder until the
 *   problem is fixed; the record button is then off. The others can be tried again.
 */
enum class RecorderError(val recordingBlocked: Boolean) {
    /** No speech model on the phone, or part of it is missing. Settings downloads it. */
    SpeechModelMissing(recordingBlocked = true),

    /** The speech model is there but is the wrong size or not a model, and was not loaded. */
    SpeechModelDamaged(recordingBlocked = true),

    /**
     * The speech model is on the phone but didn't load, or its files couldn't be read. Trying
     * again may work. A capture whose model failed to load once the microphone was open keeps
     * the audio it recorded.
     */
    SpeechModelFailed(recordingBlocked = true),

    /** Android refused to start the recording service, so nothing was recorded. */
    ServiceRefused(recordingBlocked = false),

    /** The microphone or the transcription failed while recording. What was said is kept. */
    TranscriptionFailed(recordingBlocked = false)
}

/** The AI model's state for this recording. */
enum class LlmStatus {
    /** Being loaded, or not looked at yet while the recorder gets ready. */
    Loading,

    /**
     * Found and usable: registered with the engine, or registered once the engine is free (an
     * older recording is being finished); loaded at the first note at the latest.
     */
    Ready,

    /** Turned off in Settings. */
    Off,

    /** No model on the phone. */
    Missing,

    /** The model file is damaged or didn't load. */
    Unavailable
}
