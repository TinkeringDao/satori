package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Once per install: the to-dos found before Satori told ideas apart from them, which are ideas
 * ("there should be an option to ..."), move to the ideas ([TodoStatus.IDEA]), where nothing
 * reminds.
 *
 * A to-do is looked at when it is open or suggested, came from a recording and was not noted as a
 * to-do outright: its capture is not EXPLICIT ("remind me", "note to self"). A commitment is looked
 * at too, as the model read commitments into feedback on the app. What it speaks of are the
 * sentences of the segments it was found in, and
 * of the segment before and after each, that share a content word with it
 * ([TodoTexts.sentencesAbout]): voice detection cuts sentences, and the cue is often said a segment
 * away from the words the to-do was taken from. Neighbouring segments are read as one text, so a
 * sentence cut in two is whole again. The to-do moves when one of those sentences puts forward an
 * idea ([IdeaCues]) and none says in the first person that the speaker will do it
 * ([TodoGate.hasFirstPersonCue]): "I'll add an option to ..." stays a to-do, but "I have to say,
 * it should be stronger" moves. Its reminder goes.
 *
 * A moved to-do is an idea and no longer looked at, so a second run moves nothing.
 *
 * @property todoRepository The to-dos to look at and move
 * @property transcriptionRepository The sentences the to-dos were found in
 * @property reminderScheduler Re-armed once after a move, as a moved to-do no longer reminds
 * @property log Hears of each move ([TodoSource.APP])
 */
class MoveFoundIdeasUseCase(
    private val todoRepository: TodoRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val reminderScheduler: ReminderScheduler,
    private val log: TodoChangeLog = TodoChangeLog.NONE
) {

    /**
     * Moves the to-dos that are ideas. One that fails to move fails the run, which can run again:
     * what moved before it stays an idea, and the reminders follow it either way.
     *
     * @return How many to-dos it moved to the ideas
     */
    suspend operator fun invoke(): Int {
        val candidates = (todoRepository.observeOpen().first() + todoRepository.observeSuggested().first())
            .filter { it.sessionId != null && it.capture != TodoCapture.EXPLICIT }
        val transcripts = HashMap<String, List<TranscriptionSegment>>()
        var moved = 0
        try {
            for (todo in candidates) {
                val sessionId = todo.sessionId ?: continue
                val lines = transcripts.getOrPut(sessionId) { spokenLines(sessionId) }
                val sentences = sentencesAround(todo, lines)
                if (sentences.none(IdeaCues::has) || sentences.any(TodoGate::hasFirstPersonCue)) continue
                // The reminder goes first: a run cut off between the two leaves a to-do without
                // its reminder, which the next run moves, never an idea that still has one.
                if (todo.remindAt != null) todoRepository.setRemindAt(todo.id, null)
                todoRepository.setStatus(todo.id, TodoStatus.IDEA)
                log.changed(todo.id, todo.status, TodoStatus.IDEA, TodoSource.APP)
                moved++
            }
        } finally {
            if (moved > 0) rearmReminders()
        }
        return moved
    }

    /**
     * The complete, spoken segments of the recording [sessionId], in the order they were said, each
     * as its to-dos were found in it: a line read into English as its reading, whose English the
     * cues read, any other as it was said ([ReadableSegments.withReadings], 0.8.0).
     */
    private suspend fun spokenLines(sessionId: String): List<TranscriptionSegment> {
        val lines = transcriptionRepository.getLinesWithReadings(sessionId)
        return ReadableSegments.withReadings(lines.said, lines.readings)
            .filter { it.isComplete && it.text.isNotBlank() }
            .sortedBy { it.timestamp }
    }

    /**
     * The sentences [todo] speaks of in [lines]: of the segments it cites and their neighbours, by
     * time, those that share a content word with it.
     */
    private fun sentencesAround(todo: Todo, lines: List<TranscriptionSegment>): List<String> {
        val cited = lines.indices.filter { lines[it].id in todo.sourceSegmentIds }
        val near = cited.flatMap { listOf(it - 1, it, it + 1) }.filter { it in lines.indices }.toSortedSet()
        return runsOf(near).flatMap { run ->
            TodoTexts.sentencesAbout(todo.text, run.joinToString(" ") { lines[it].text.trim() })
        }
    }

    /** [indices], sorted, cut into runs of neighbours: 1, 2, 3, 7, 8 gives 1-3 and 7-8. */
    private fun runsOf(indices: Set<Int>): List<List<Int>> {
        val runs = mutableListOf<MutableList<Int>>()
        for (index in indices) {
            if (runs.lastOrNull()?.last() == index - 1) runs.last() += index else runs += mutableListOf(index)
        }
        return runs
    }

    /** Runs to the end even when the caller is cancelled: a moved to-do's alarm must go. */
    private suspend fun rearmReminders() {
        withContext(NonCancellable) {
            try {
                reminderScheduler.reschedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not worth failing the move for, or losing its count: the next reschedule follows it.
            }
        }
    }
}
