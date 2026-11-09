package com.satori.domain.usecase.stt

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.LinesNeverReadException
import com.satori.domain.usecase.finish.readingLanguage
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.RewriteOutcome
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.todo.isUntouched
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Makes a recording's text again from the transcript Transcribe again has just put in, in another
 * language than the old one ([SpeechModelChoice.makesTextAgain]). What the old transcript gave the
 * recording, its name, notes, to-dos and ideas, was written from what a model made of speech in
 * another language: each goes only where what replaces it is ready, so a run that stops, fails or
 * never begins (a recording that starts, the AI busy, the page left, a model that doesn't load)
 * leaves the rest as it was. In this order, as the finishing writes them after Stop:
 * 1. The notes, written again from the whole transcript as Rewrite notes writes them, but without
 *    the recording's name as their topic ([RewriteNotesUseCase] nameAsTopic): the name came from the
 *    old transcript. They replace the old ones in one write once they all exist. A transcript with
 *    too little text for notes ([RewriteNotesUseCase.hasEnoughText]) gets none, and the old notes
 *    go: they say what the old transcript said. Notes that fail to be written leave the old ones.
 * 2. The name, from the new notes, or the transcript without them ([AutoNameSessionUseCase]), never
 *    from old notes. It replaces the old name once it is written, while the recording still has
 *    that one, so a name the owner types meanwhile stays; one that can't be written leaves the old.
 * 3. The to-dos and ideas, found in the new transcript ([ExtractSessionTodosUseCase]). The ones the
 *    old transcript gave the recording that nobody has touched since ([isUntouched], read as the
 *    run begins and again as they go) are no repeats for the search, and go in the same write that
 *    stores the new finds, each to the field log ([TodoSource.TRANSCRIBED_AGAIN]) with its reminder
 *    ([TodoActions.delete]). Those the owner has edited, ticked, kept or changed otherwise stay,
 *    pointed at the new lines said where theirs were ([TranscribeAgainUseCase]), and the search
 *    doesn't find them twice. A search that can't run, fails or is stopped before the model has
 *    answered leaves the old ones.
 *
 * A Malayalam transcript's text is written in English (the owner's decision, 2026-10-14), as the
 * finishing writes it: since 0.8.0 from its lines' readings (design R6), each Malayalam line read
 * into English, none without one, with the prompts of an English recording (D6), in the language
 * the transcript as it was said gives (D8). The caller makes the readings first
 * (EnsureReadingsUseCase), before the model loads; a transcript with such a line never read changes
 * nothing ([LinesNeverReadException], D7): no text of the rest of it in place of the old. A
 * Translate recording is refused: its notes are its translation, and it stays
 * in English or German, so no transcript made again changes its language. Each step is best effort:
 * one that fails leaves what the steps before it stored, and the next one runs. The model is the one
 * for the work after Stop ([LoadFinishingModelUseCase]), put in memory once, as the first step with
 * something to do begins; the caller registers the live model before and releases the model after,
 * as for Rewrite notes.
 *
 * @property notesLanguage Chooses the language of the name, as the finishing does
 * @property todoRepository Where the recording's to-dos and ideas are read
 * @property todoActions Takes away the old ones, logs each and re-arms the reminders
 */
class MakeTextAgainUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val rewriteNotes: RewriteNotesUseCase,
    private val autoNameSession: AutoNameSessionUseCase,
    private val extractSessionTodos: ExtractSessionTodosUseCase,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val notesLanguage: NotesLanguageResolver,
    private val todoRepository: TodoRepository,
    private val todoActions: TodoActions
) {
    /** The steps, in their order, as each begins. */
    enum class Step { Notes, Name, Todos }

    /** What became of the notes. */
    enum class Notes {
        /** New notes are in place of the old ones. */
        Written,

        /** The new transcript holds too little text for notes: the recording has none now. */
        TooLittle,

        /** They couldn't be written or stored: the old ones stay. */
        Failed
    }

    /**
     * What became of the to-dos and ideas the old transcript gave the recording: [removed] went, in
     * place of what the search found; [kept] stay, those the owner had changed and, when the search
     * stored nothing, all of them. Counts only.
     */
    data class OldFinds(val removed: Int, val kept: Int)

    /**
     * What a run made, counts only, for the log and a notice: the [notes], whether a [name] was
     * given (false also when the owner typed one meanwhile, or there was nothing to name it
     * after: the old name stays then), what the search for to-dos and ideas came to ([todos]),
     * and what became of the old ones ([oldFinds]).
     */
    data class Outcome(
        val notes: Notes,
        val name: Boolean,
        val todos: ExtractSessionTodosUseCase.Outcome,
        val oldFinds: OldFinds
    )

    /**
     * @param mayChangeModel Whether the model in memory may be swapped for the one for the work
     *   after Stop; false while a recorder owns the loaded model
     * @param onModel The model the steps run on, once it is chosen, for the log; not called when no
     *   step has anything to do
     * @param onStep Each step as it begins, for the screen
     * @param onNotesProgress The notes' pipeline, for the screen's progress
     * @return What it made; a failure when the recording is gone, translates, its lines or its
     *   to-dos can't be read, or lines only the reader reads were never read
     *   ([LinesNeverReadException]), which changes nothing. Throws only [CancellationException]: a cancel
     *   ends the run where it is, and what was stored before stays, as does all the old text the
     *   steps after would have replaced (a swap of the notes begun runs to its end, and so does a
     *   store of what the search found, with the old finds' removal).
     */
    suspend operator fun invoke(
        sessionId: String,
        mayChangeModel: Boolean = true,
        onModel: (FinishingModel) -> Unit = {},
        onStep: (Step) -> Unit = {},
        onNotesProgress: (BatchInsightProgress) -> Unit = {}
    ): Result<Outcome> {
        val session = try {
            transcriptionRepository.getSession(sessionId).first()
                ?: return Result.failure(NoSuchElementException("Session not found: $sessionId"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        // Its notes are its translation, which notes written again would cut short.
        if (session.mode == RecordingMode.REAL_TIME_TRANSLATION) {
            return Result.failure(IllegalArgumentException("A Translate recording keeps its translation"))
        }
        val (lines, before) = try {
            transcriptionRepository.getLinesWithReadings(sessionId) to todoRepository.observeForSession(sessionId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        // D7: the old text stays rather than giving way to text of the rest of what was said.
        if (lines.hasNeverRead) return Result.failure(LinesNeverReadException())
        val said = lines.said.filter { it.isComplete }.sortedBy { it.timestamp }
        // What the AI reads: a line only the reader reads as its reading, none without one.
        val segments = lines.readable.filter { it.isComplete }.sortedBy { it.timestamp }
        // What the old transcript gave the recording: the name it was named after it, and the
        // finds nobody has touched since. Each is replaced by its step, not before.
        val oldName = session.name?.takeIf { it.isNotBlank() }
        val oldFinds = before.filter { it.isUntouched() }.mapTo(linkedSetOf()) { it.id }

        val writesNotes = RewriteNotesUseCase.hasEnoughText(segments)
        val names = wordsIn(segments) >= GenerateSessionTitleUseCase.MIN_WORDS
        val findsTodos = segments.any { it.text.isNotBlank() }
        if (writesNotes || names || findsTodos) onModel(if (mayChangeModel) loadFinishingModel() else FinishingModel.Live)

        onStep(Step.Notes)
        val notes = writeNotes(sessionId, writesNotes, session.insightStrategy, onNotesProgress)

        onStep(Step.Name)
        val named = names && bestEffort(false) {
            // The language of the transcript as it was said (D8): English for Malayalam.
            val transcript = said.joinToString(" ") { it.text.trim() }
            // Failed notes leave the old ones, which say what the old transcript said.
            val from: List<LlmInsight>? = if (notes == Notes.Failed) emptyList() else null
            autoNameSession(sessionId, notesLanguage.readingLanguage(session, transcript), notes = from, replacing = oldName)
                .getOrNull() != null
        }

        onStep(Step.Todos)
        var taken: OldFinds? = null
        val todos = if (findsTodos) {
            extractSessionTodos(sessionId, replacing = oldFinds) { ids -> taken = takeAway(sessionId, ids, before.mapTo(hashSetOf()) { it.id }) }
        } else {
            ExtractSessionTodosUseCase.Outcome.Skipped
        }
        return Result.success(Outcome(notes, named, todos, taken ?: OldFinds(removed = 0, kept = before.size)))
    }

    /**
     * The notes written again from the whole transcript, without the name as their topic, when it
     * holds enough text for them ([enoughText]); else those of the old transcript go.
     */
    private suspend fun writeNotes(
        sessionId: String,
        enoughText: Boolean,
        strategy: InsightStrategy,
        onProgress: (BatchInsightProgress) -> Unit
    ): Notes {
        if (!enoughText) return dropOldNotes(sessionId, strategy)
        // The model is in memory already: the rewrite doesn't load it again.
        val rewritten = rewriteNotes(sessionId, mayChangeModel = false, onProgress = onProgress, nameAsTopic = false)
        return when (rewritten.getOrNull()) {
            RewriteOutcome.Replaced -> Notes.Written
            // The lines changed since they were read.
            RewriteOutcome.TooLittle -> dropOldNotes(sessionId, strategy)
            null -> {
                rewritten.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                Notes.Failed
            }
        }
    }

    /**
     * No notes for a transcript with too little text: those of the old one go, in one write that
     * runs to its end once begun, and the recording keeps how its notes were written ([strategy]).
     * Notes that can't be removed stay, as notes that fail to be written do.
     */
    private suspend fun dropOldNotes(sessionId: String, strategy: InsightStrategy): Notes {
        val old = bestEffort(emptyList()) { transcriptionRepository.getInsightsBySession(sessionId).first() }
        if (old.isEmpty()) return Notes.TooLittle
        val dropped = bestEffort(false) {
            withContext(NonCancellable) { transcriptionRepository.replaceInsights(sessionId, emptyList(), strategy) }.isSuccess
        }
        return if (dropped) Notes.TooLittle else Notes.Failed
    }

    /**
     * Takes away the old finds of [ids] once the search has stored what it found, in its write
     * ([ExtractSessionTodosUseCase]): each that is still as the app found it ([isUntouched]); one
     * the owner changed while the search ran stays. Best effort, never throwing: one that can't be
     * read or removed stays.
     *
     * @param earlier The ids of all the recording had as the run began, to count those that stay
     */
    private suspend fun takeAway(sessionId: String, ids: Set<String>, earlier: Set<String>): OldFinds {
        var removed = 0
        try {
            todoRepository.observeForSession(sessionId).first()
                .filter { it.id in ids && it.isUntouched() }
                .forEach { old ->
                    todoActions.delete(old.id, TodoSource.TRANSCRIBED_AGAIN)
                    removed++
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What is removed stays removed; the rest stays.
        }
        val left = bestEffort(earlier.size - removed) {
            todoRepository.observeForSession(sessionId).first().count { it.id in earlier }
        }
        return OldFinds(removed = removed, kept = left)
    }

    private fun wordsIn(segments: List<TranscriptionSegment>): Int =
        segments.sumOf { segment -> segment.text.split(WHITESPACE).count { it.isNotEmpty() } }

    /** [work], or [fallback] when it throws anything but a cancel. */
    private suspend inline fun <T> bestEffort(fallback: T, work: () -> T): T = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
