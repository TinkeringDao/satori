package com.satori.domain.usecase.todo

import com.satori.domain.model.TodoStatus
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import kotlinx.coroutines.flow.first

/**
 * Puts the to-dos found before [TodoGate] existed through it once: those it would have dropped are
 * dismissed (not deleted, so nothing is lost), open ones without a cue become suggestions. One the
 * gate would make an idea has no cue either, so it becomes a suggestion too: which of the to-dos
 * found then are ideas is [MoveFoundIdeasUseCase]'s to tell, which runs after it. Only to-dos the
 * app found and nobody has touched since: a to-do added or edited by hand stays as it is.
 *
 * Each change goes to [log] ([TodoSource.APP]).
 *
 * The lines a to-do cites are read as it was found in them (0.8.0, design R6): a line read into
 * English as its reading, whose English the gate's cues read, any other line as it was said
 * ([ReadableSegments.withReadings]). No AI model reads them.
 *
 * @return how many to-dos changed
 */
class RecheckFoundTodosUseCase(
    private val todoRepository: TodoRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val reminderScheduler: ReminderScheduler,
    private val log: TodoChangeLog = TodoChangeLog.NONE
) {
    suspend operator fun invoke(): Int {
        val candidates = todoRepository.observeOpen().first() + todoRepository.observeSuggested().first()
        var changed = 0
        val segmentsBySession = HashMap<String, Map<String, String>>()
        for (todo in candidates) {
            val sessionId = todo.sessionId ?: continue
            if (!todo.isUntouched()) continue
            val texts = segmentsBySession.getOrPut(sessionId) {
                // Each line as the to-do was found in it: its reading, if it was read into English.
                val lines = transcriptionRepository.getLinesWithReadings(sessionId)
                ReadableSegments.withReadings(lines.said, lines.readings).associate { it.id to it.text }
            }
            val source = todo.sourceSegmentIds.mapNotNull { texts[it] }.joinToString(" ")
            val target = when (TodoGate.verdict(todo, source)) {
                TodoGate.Verdict.DROP -> TodoStatus.DISMISSED
                TodoGate.Verdict.SUGGEST, TodoGate.Verdict.IDEA -> TodoStatus.SUGGESTED
                TodoGate.Verdict.OPEN -> todo.status
            }
            if (target != todo.status) {
                todoRepository.setStatus(todo.id, target)
                log.changed(todo.id, todo.status, target, TodoSource.APP)
                changed++
            }
        }
        if (changed > 0) reminderScheduler.reschedule()
        return changed
    }
}
