package com.satori.presentation.sessiondetails

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.presentation.ai.AiWorkGate

/** The two tabs of a recording's page, as the `initialTab` navigation argument numbers them. */
object DetailsTab {
    const val TRANSCRIPT = 0

    /** The AI's notes; for a Translate recording, its translation. */
    const val NOTES = 1
}

/**
 * UI state for the recording's details page.
 *
 * @property sessionDetails The recording with its transcript and notes; null while it loads and
 *   once it is gone
 * @property error What went wrong; null if nothing did
 * @property highlightId The line or note to scroll to when the page opens (from search)
 * @property openingTab The tab the page opens on, decided once the recording has loaded: the one
 *   asked for, else [DetailsTab.NOTES] when the recording has notes or is being finished (its notes
 *   are on their way), else [DetailsTab.TRANSCRIPT]
 * @property notesAi Whether the AI can write notes now; null until that has been checked
 * @property showRename Whether the rename sheet is open
 * @property showDeleteConfirmation Whether the delete sheet is open
 * @property deleting Whether the recording is being deleted. The delete first ends the finishing
 *   of the recording, if a step of it runs, which can last until a model load ends (5-15 s); the
 *   page shows no recording meanwhile, and once it is gone it closes
 * @property showRewriteConfirm Whether the sheet asking to replace the notes is open
 * @property editingSegment The transcript line being edited, if any
 * @property editingInsight The note being edited, if any
 * @property rewrite The rewrite of the notes in progress; null when none runs
 * @property finishing How the finishing of this recording in the background stands while it waits
 *   or runs; null once it is done, or when it was never handed over
 * @property aiBusy Whether the AI is in use (AiWorkGate), by the finishing of a recording, the mind
 *   map, a live note or work of this page, or a recording waits to be finished, which goes first
 * @property aiFinishing Whether that is the finishing of recordings: one waits to be finished, or the
 *   finisher holds the AI. The page's AI work is then available once finishing is done; otherwise
 *   once the AI is free, from the map's work, say, or the work of another recording's page
 * @property todos The recording's to-dos and ideas, listed at the top of the notes tab
 * @property todosLoaded Whether [todos] have been read once: the page waits for them, so that it
 *   can open on a note, which comes after them
 * @property openTodo The to-do whose sheet is open, if any
 * @property todoSearch The search for the recording's to-dos and ideas that runs; null when none does
 * @property categories The recording categories there are, in their order, for "Change type"
 * @property malayalamOnPhone Whether the Malayalam speech model is on the phone, so "Change type"
 *   offers Malayalam as the recording's language; without it the sheet links to its download
 */
data class SessionDetailsUiState(
    val sessionDetails: SessionWithDetails? = null,
    val error: DetailsError? = null,
    val highlightId: String? = null,
    val openingTab: Int? = null,
    val notesAi: NotesAi? = null,
    val showRename: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val deleting: Boolean = false,
    val showRewriteConfirm: Boolean = false,
    val editingSegment: TranscriptionSegment? = null,
    val editingInsight: LlmInsight? = null,
    val rewrite: RewriteProgress? = null,
    val finishing: FinishingStatus? = null,
    val aiBusy: Boolean = false,
    val aiFinishing: Boolean = false,
    val todos: RecordingTodos = RecordingTodos(),
    val todosLoaded: Boolean = false,
    val openTodo: Todo? = null,
    val todoSearch: TodoSearch? = null,
    val categories: List<RecordingCategory> = emptyList(),
    val malayalamOnPhone: Boolean = false
) {
    /**
     * How many open to-dos from this recording stay on the user's list if it is deleted: to-dos
     * outlive their recording, which the delete sheet says.
     */
    val keptTodos: Int get() = todos.open.size

    /**
     * Whether the finishing of this recording is still to write its notes: the AI can write them,
     * as the finisher sees it (AI notes on and a model installed), the job waits or runs at a step
     * up to Notes, and the transcript holds the words the Notes step writes from. Below them it
     * writes none, and the tab says there isn't enough text; so it does as soon as the finishing
     * has found that ([FinishingStatus.noNotes]), before any model is chosen.
     */
    val notesToCome: Boolean
        get() = notesAi == NotesAi.Ready && finishing?.let { notesAhead(it) && !it.noNotes } == true &&
            sessionDetails?.segments?.let(::holdsWordsForNotes) == true

    /**
     * Whether the notes tab says "Writing notes" where it would say there are none: the notes are
     * to come and none are there yet. A rewrite shows its own progress instead.
     */
    val showsWritingNotes: Boolean
        get() = notesToCome && rewrite == null && sessionDetails?.insights.isNullOrEmpty()

    /**
     * Whether the lines of the transcript can be edited: not while the finishing of this recording
     * may still replace them. The better transcript and the joining of lines give the lines new
     * ids, so an edit made meanwhile would be lost. That is while its job waits, in the queue or
     * for the recording being made to end, and while it runs at Transcript or Lines or has not
     * begun its first step.
     */
    val canEditTranscript: Boolean
        get() = finishing?.let { !mayReplaceLines(it) } ?: true

    /**
     * Whether the notes can be edited: not while the finishing of this recording may still write
     * them anew. Its Notes step replaces them after a better transcript or for a long tail, and
     * stores the notes of the end of the session again, so an edit made before it is over would be
     * lost without a word. That is while its job waits, and until it is past its Notes step.
     */
    val canEditNotes: Boolean
        get() = finishing?.let { !notesAhead(it) } ?: true

    /**
     * Whether the finishing of this recording waits for the AI model the first run downloads
     * ([FinishingStatus.Phase.WAITS_FOR_MODEL]): its notes, name and to-dos follow once it is in,
     * by themselves. Meanwhile the page says so, not that a model is missing.
     */
    val waitsForModel: Boolean
        get() = finishing?.phase == FinishingStatus.Phase.WAITS_FOR_MODEL

    /** Whether Skip can end the better transcript of this recording: it is being made, and Skip wasn't pressed yet. */
    val canSkipTranscript: Boolean
        get() = finishing?.let {
            it.phase == FinishingStatus.Phase.RUNNING && it.step == FinishStep.Transcript && it.transcript?.skipping != true
        } == true

    /**
     * Whether the page's AI work (writing the notes, Transcribe again, finding to-dos and ideas
     * again) waits for work elsewhere: the AI is in use, and not by this page, whose own work
     * shows where it runs, or a recording waits to be finished. Work started then would hold up
     * the notes of the recording just made, or find the AI taken. The menu and the empty notes
     * tab offer it switched off, saying when it is available ([aiFinishing]).
     *
     * @param transcribing Whether this page's Transcribe again runs ([RecordingAudioUiState.transcription])
     */
    fun aiWaits(transcribing: Boolean): Boolean = aiBusy && rewrite == null && todoSearch == null && !transcribing

    /**
     * Whether the menu offers Write or Rewrite notes: AI notes are on with a model, there is enough
     * text, it is no Translate recording (a rewrite would put a shortened translation in place of
     * the full one), and no other long work of this page runs: notes written while a new transcript
     * is made would come from the old one. Whether it can start now is [aiWaits].
     *
     * @param transcribing Whether this page's Transcribe again runs
     */
    fun offersWriteNotes(transcribing: Boolean): Boolean {
        val details = sessionDetails ?: return false
        return offersAiWork(transcribing) &&
            details.session.mode != RecordingMode.REAL_TIME_TRANSLATION &&
            RewriteNotesUseCase.hasEnoughText(details.segments)
    }

    /**
     * How Write or Rewrite notes is offered, on the empty notes tab as in the menu: not while
     * [offersWriteNotes] says no, and switched off while the AI's work waits ([aiWaits]).
     *
     * @param transcribing Whether this page's Transcribe again runs
     */
    fun writeNotesOffer(transcribing: Boolean): AiOffer = when {
        !offersWriteNotes(transcribing) -> AiOffer.NONE
        aiWaits(transcribing) -> AiOffer.WAITS
        else -> AiOffer.READY
    }

    /**
     * Whether the menu offers "Find to-dos and ideas again": AI notes are on with a model,
     * something was said, and no other long work of this page runs. Whether it can start now is
     * [aiWaits].
     *
     * @param transcribing Whether this page's Transcribe again runs
     */
    fun offersFindAgain(transcribing: Boolean): Boolean {
        val details = sessionDetails ?: return false
        return offersAiWork(transcribing) && details.segments.any { it.isComplete && it.text.isNotBlank() }
    }

    private fun offersAiWork(transcribing: Boolean): Boolean =
        notesAi == NotesAi.Ready && rewrite == null && todoSearch == null && !transcribing

    /**
     * Whether the page makes the recording's name, notes, to-dos and ideas again after Transcribe
     * again puts in a transcript in another language than the old one
     * (com.satori.domain.usecase.stt.TranscribeOption.makesTextAgain): AI notes are on
     * with a model, and it is no Translate recording, whose notes are its translation. Without,
     * only the transcript changes, and the sheet says the rest stays.
     */
    val makesTextAgain: Boolean
        get() = notesAi == NotesAi.Ready &&
            sessionDetails?.session?.mode?.let { it != RecordingMode.REAL_TIME_TRANSLATION } == true

    /** The category the recording is recorded as, while it has one that exists. */
    val recordingAs: RecordingCategory?
        get() = sessionDetails?.session?.categoryId?.let { id -> categories.firstOrNull { it.id == id } }

    /**
     * The categories the recording can be changed to ("Change type"), in their order: all but
     * those that translate, and none for a Translate recording, which keeps what it is
     * ([ChangeRecordingTypeUseCase.choices]).
     */
    val typeChoices: List<RecordingCategory>
        get() = ChangeRecordingTypeUseCase.choices(sessionDetails?.session?.mode, categories)

    /**
     * Whether "Change type" can write the notes anew in a new mode, as Rewrite notes does: the AI
     * can write them and there is enough text ([offersWriteNotes] without the page's other work).
     * Without, only the type changes and the notes stay as they are.
     */
    val changeRewritesNotes: Boolean
        get() {
            val details = sessionDetails ?: return false
            return notesAi == NotesAi.Ready &&
                details.session.mode != RecordingMode.REAL_TIME_TRANSLATION &&
                RewriteNotesUseCase.hasEnoughText(details.segments)
        }

    /**
     * Whether changing to the category [categoryId] writes the notes anew: when the AI can
     * ([changeRewritesNotes]) and the category records in another mode, whose notes are another
     * kind. A change to one in the same mode (the starter Voice note, Idea and To-do all record
     * voice notes) only files the recording under it: the same prompt would write the same notes
     * again, at the cost of loading Gemma 4 E2B, and replace any the owner edited.
     */
    fun rewritesNotesFor(categoryId: String): Boolean {
        val mode = sessionDetails?.session?.mode ?: return false
        val picked = categories.firstOrNull { it.id == categoryId }?.mode ?: return false
        return changeRewritesNotes && picked != mode
    }

    /** Whether any category "Change type" offers writes the notes anew ([rewritesNotesFor]), which its sheet then says. */
    val someChangeRewritesNotes: Boolean
        get() = typeChoices.any { rewritesNotesFor(it.id) }

    /**
     * How the menu offers "Change type": for any recording but a Translate one, while there is
     * another category or the other language to change to, and no other long work of the page runs
     * (a rewrite, a search for to-dos and ideas, Transcribe again). Switched off, saying why, while
     * this recording is still being finished, whose finishing reads its mode and its language, and
     * while the AI's work waits ([aiWaits]): a recording waits to be finished, or other work holds
     * the AI.
     *
     * @param transcribing Whether this page's Transcribe again runs
     */
    fun changeTypeOffer(transcribing: Boolean): AiOffer {
        val session = sessionDetails?.session ?: return AiOffer.NONE
        return when {
            rewrite != null || todoSearch != null || transcribing -> AiOffer.NONE
            typeChoices.none { it.id != session.categoryId } && !changesLanguage -> AiOffer.NONE
            finishing != null || aiWaits(transcribing) -> AiOffer.WAITS
            else -> AiOffer.READY
        }
    }

    /**
     * Whether "Change type" offers the recording's language too: for any recording but a Translate
     * one ([ChangeRecordingLanguageUseCase.offered]). Malayalam itself only while its model is on the
     * phone ([malayalamOnPhone]).
     */
    val changesLanguage: Boolean
        get() = ChangeRecordingLanguageUseCase.offered(sessionDetails?.session?.mode)

    /** Whether "Change type", switched off, waits for the finishing of recordings rather than for the AI. */
    val changeTypeWaitsForFinishing: Boolean
        get() = finishing != null || aiFinishing

    /**
     * This state with the AI as it stands: who holds the AI work gate ([holder], null while it is
     * free) and whether a recording waits to be finished or is being finished ([finishing]).
     */
    fun withAi(holder: String?, finishing: Boolean): SessionDetailsUiState =
        copy(aiBusy = holder != null || finishing, aiFinishing = finishing || holder == AiWorkGate.FINISHER)
}

/** How the page offers a piece of its AI work. */
enum class AiOffer {
    /** Not offered: the AI can't do it here, or other long work of the page runs. */
    NONE,

    /** Offered switched off, saying when it is available: the AI is in use elsewhere. */
    WAITS,

    /** Offered, ready to start. */
    READY
}

/**
 * Whether the Notes step of a recording's finishing is still ahead or under way. A job that waits
 * has its steps ahead of it, as far as the screen can tell; one that waits for the AI model has
 * the Notes step ahead, unless it found too little text for notes, which it waits past.
 */
private fun notesAhead(status: FinishingStatus): Boolean = when (status.phase) {
    FinishingStatus.Phase.WAITING, FinishingStatus.Phase.WAITS_FOR_RECORDING -> true
    FinishingStatus.Phase.WAITS_FOR_MODEL -> !status.noNotes
    FinishingStatus.Phase.RUNNING -> when (status.step) {
        null, FinishStep.Transcript, FinishStep.Lines, FinishStep.Reading, FinishStep.Notes -> true
        FinishStep.Name, FinishStep.Todos, FinishStep.Map -> false
    }
}

/**
 * Whether [segments] hold the words the Notes step needs to write anything: its complete lines
 * hold at least [GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH], the least its passes write notes
 * from (the final note of a short tail asks as many). Counting stops there: the page asks on every
 * redraw, and a long recording has thousands.
 */
private fun holdsWordsForNotes(segments: List<TranscriptionSegment>): Boolean {
    var words = 0
    for (segment in segments) {
        if (!segment.isComplete) continue
        words += segment.text.split(WHITESPACE).count { it.isNotEmpty() }
        if (words >= GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH) return true
    }
    return false
}

private val WHITESPACE = Regex("\\s+")

/**
 * Whether a recording's finishing may still replace its lines: a job that waits, also one deferred
 * until a recording ends, may still have its better transcript and the joining of its lines ahead
 * of it; a running one does at those steps and before its first. One that waits for the AI model
 * or the reader has done both: it waits at its Reading step or its first AI step, so the transcript
 * can be edited all through the download. The Reading step replaces no line (a line edited while it
 * reads is read again).
 */
private fun mayReplaceLines(status: FinishingStatus): Boolean = when (status.phase) {
    FinishingStatus.Phase.WAITING, FinishingStatus.Phase.WAITS_FOR_RECORDING -> true
    FinishingStatus.Phase.WAITS_FOR_MODEL -> false
    FinishingStatus.Phase.RUNNING -> when (status.step) {
        null, FinishStep.Transcript, FinishStep.Lines -> true
        FinishStep.Reading, FinishStep.Notes, FinishStep.Name, FinishStep.Todos, FinishStep.Map -> false
    }
}

/**
 * The to-dos and ideas of one recording, as the top of its notes tab lists them, each list in
 * transcript order: its open to-dos, the suggestions that wait to be kept or dismissed, and its
 * ideas. Done and dismissed ones are left out, as on the home screen.
 */
data class RecordingTodos(
    val open: List<Todo> = emptyList(),
    val suggested: List<Todo> = emptyList(),
    val ideas: List<Todo> = emptyList()
) {
    /** Whether there is nothing to list. */
    val isEmpty: Boolean get() = open.isEmpty() && suggested.isEmpty() && ideas.isEmpty()

    companion object {
        /** [todos], in transcript order as TodoRepository.observeForSession gives them, split by status. */
        fun of(todos: List<Todo>): RecordingTodos = RecordingTodos(
            open = todos.filter { it.status == TodoStatus.OPEN },
            suggested = todos.filter { it.status == TodoStatus.SUGGESTED },
            ideas = todos.filter { it.status == TodoStatus.IDEA }
        )
    }
}

/**
 * A search for the recording's to-dos and ideas that runs ("Find to-dos and ideas again").
 *
 * @property stopping Cancel was pressed and it is shutting down
 */
data class TodoSearch(val stopping: Boolean = false)

/**
 * What a search for the recording's to-dos and ideas found, for the notice that says so: the
 * [todos] (open or suggested) and the [ideas] it stored.
 */
data class TodosFound(val todos: Int, val ideas: Int) {
    companion object {
        /**
         * What a search added between [before] and [after], the recording's to-dos as they were
         * read on each side of it. The search stores only new ones, each with an id of its own, so
         * the ids tell them apart.
         */
        fun between(before: List<Todo>, after: List<Todo>): TodosFound {
            val known = before.mapTo(HashSet()) { it.id }
            val added = after.filter { it.id !in known }
            return TodosFound(
                todos = added.count { it.status == TodoStatus.OPEN || it.status == TodoStatus.SUGGESTED },
                ideas = added.count { it.status == TodoStatus.IDEA }
            )
        }
    }
}

/** A short confirmation the page shows once, at the bottom. */
sealed interface DetailsNotice {
    /** What a new search for to-dos and ideas stored. */
    data class Found(val found: TodosFound) : DetailsNotice

    /** A recording that started stopped Write or Rewrite notes; the notes stayed as they were. */
    data object RewriteStopped : DetailsNotice

    /** A recording that started stopped the search for to-dos and ideas; those found before stayed. */
    data object SearchStopped : DetailsNotice

    /** "Change type" stored the category called [name] as what the recording is recorded as. */
    data class TypeChanged(val name: String) : DetailsNotice

    /** "Change type" stored [language] as the language the recording is made in. */
    data class LanguageChanged(val language: RecordingLanguage) : DetailsNotice
}

/**
 * A rewrite of the notes that is running. The old notes stay until the new ones are complete.
 *
 * @property progress Where the pipeline is; [BatchInsightProgress.Idle] until it reports, which
 *   includes the time the model takes to load
 * @property stopping Cancel was pressed and the rewrite is shutting down
 */
data class RewriteProgress(
    val progress: BatchInsightProgress = BatchInsightProgress.Idle,
    val stopping: Boolean = false
)

/** Whether the AI can write notes, so the page offers it or says what is missing. */
enum class NotesAi {
    /** AI notes are on and a model is installed. */
    Ready,

    /** A model is installed, but AI notes are turned off in Settings. */
    TurnedOff,

    /** No AI model is installed. */
    NoModel
}

/** What went wrong on the details page. The screen words it, in the app's language. */
enum class DetailsError {
    /** The recording no longer exists. */
    Missing,

    /** The recording couldn't be read. */
    Load,
    Rename,
    Delete,

    /** A transcript correction wasn't saved. */
    Segment,

    /** A note edit wasn't saved. */
    Insight,

    /** A rewrite needs a model, and none is installed (or AI notes were turned off meanwhile). */
    NoModel,

    /** The AI model didn't load for a rewrite. */
    ModelFailed,

    /** There was too little text to write notes from. */
    TooShort,

    /** A rewrite failed on the way; the old notes are unchanged. */
    RewriteFailed,

    /**
     * The AI was taken, or a recording was handed over to be finished, between the offer and the
     * tap.
     */
    AiBusy,

    /** The new search for to-dos and ideas failed; what was stored before stays. */
    FindFailed,

    /** "Change type" couldn't be stored, or the category is gone; the recording is as it was. */
    ChangeType,

    /** The language of "Change type" couldn't be stored, or its model is gone; the recording is as it was. */
    ChangeLanguage,

    /** A change to a to-do wasn't saved. */
    Todo,

    /** The Markdown file couldn't be saved to Downloads. */
    Save,

    /** The Markdown file couldn't be prepared for sharing. */
    Share,

    /** The transcript couldn't be copied. */
    Copy
}
