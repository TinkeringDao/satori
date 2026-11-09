package com.satori.domain.usecase.todo

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.finish.readingLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The last step of finishing a recording: finds its to-dos and ideas in the saved transcript and
 * keeps them. It reads the recording's complete segments and what they may repeat (the
 * recording's own to-dos and ideas, every pending to-do and the ideas of the last 30 days), runs
 * [ExtractTodosUseCase] in the recording's own time zone and in the language of what was said,
 * stores the result and re-arms the reminders. A Translate recording's to-dos are written in its
 * target language, as its notes and name are (the owner's call, 2026-10-14: its German to-dos
 * stood among English notes), from what was said, without the spotter, whose to-dos would be in
 * the language spoken; and a Malayalam recording's in English.
 *
 * Since 0.8.0 it reads the lines as the AI steps read them (design R6): a line only the reader
 * reads (a Malayalam line) as its English reading, and none without one, so the model never reads
 * Malayalam. Such a recording's to-dos then come as an English recording's do, with the spotter, the
 * time parser and the gate's cues, and each still cites its line; a due time read in a reading is
 * kept only where its line as it was said holds a time (D9, [ExtractTodosUseCase]'s asSaid). The
 * language they are written in is found in the transcript as it was said (D8): English for
 * Malayalam, whatever the setting.
 *
 * It never fails the recording it runs for: whatever goes wrong here leaves the notes and the
 * name as they are, and only cancellation is passed on. Once the model has answered, what it found
 * is stored to the end, also when the caller is cancelled meanwhile (a recording that starts stops
 * Find to-dos and ideas again): a cancel keeps none of it or all of it, never a part.
 *
 * @property settingsRepository Tells whether the AI is switched on
 * @property transcriptionRepository Where the recording and its segments are read
 * @property todoRepository Where the to-dos are kept
 * @property extractTodos Reads the transcript; loads the AI model if it isn't loaded
 * @property reminderScheduler Told to re-arm the alarm after new to-dos are stored
 * @property notesLanguage Chooses the language of the to-do texts
 */
class ExtractSessionTodosUseCase(
    private val settingsRepository: SettingsRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val todoRepository: TodoRepository,
    private val extractTodos: ExtractTodosUseCase,
    private val reminderScheduler: ReminderScheduler,
    private val notesLanguage: NotesLanguageResolver
) {
    /** What the search for to-dos came to. */
    sealed interface Outcome {
        /** Nothing was looked for: the AI is off, the recording is gone, or nothing was said. */
        data object Skipped : Outcome

        /**
         * The transcript was read; [count] new to-dos and ideas were stored (possibly none),
         * [ideas] of them ideas and [suggested] of them suggestions. [overCap] more suggestions
         * were left out, over [TodoGate.MAX_SUGGESTIONS]. Counts only, for the log and a notice.
         */
        data class Found(val count: Int, val ideas: Int = 0, val suggested: Int = 0, val overCap: Int = 0) : Outcome

        /** Something went wrong; any to-dos stored before it stay. */
        data object Failed : Outcome
    }

    /**
     * @param sessionId The recording to read
     * @param replacing The ids of the recording's to-dos and ideas that the new finds take the place
     *   of (Transcribe again in another language: those the old transcript gave it, which nobody
     *   has touched, [com.satori.domain.usecase.stt.MakeTextAgainUseCase]). The search takes
     *   no new find for a repeat of one of them, and once the new ones are stored, in the same
     *   write that runs to its end, [takeAway] removes them: a cancel leaves either the old ones or
     *   the new ones. A search that is skipped or fails takes nothing away, nor does one cancelled
     *   before the model has answered, or one the model couldn't read all of
     *   ([ExtractTodosUseCase.Found.answered]), which stores nothing and is [Outcome.Failed]; one
     *   that finds nothing does take them away.
     * @param store False for a debug build's rerun of the finishing step (0.7.2): the search reads
     *   the recording as its finishing did, with the to-dos known then ([knownAtFinishing]: not its
     *   own, which the finishing had not found yet, nor any found after them), and stores nothing;
     *   its [Outcome.Found] counts what it would have stored
     * @param readings Whether the search reads the lines as the AI steps read them (0.8.0, the
     *   default): a line only the reader reads as its reading, none without one. False only for a
     *   debug build's rerun that reads the lines as they were said, as 0.7.3 did, to compare
     * @param takeAway Removes the to-dos and ideas of [replacing]; called with them once, after the
     *   new finds are stored, and never with an empty set. It must not throw.
     * @return What happened; never throws except for cancellation. [Outcome.Found] also when the
     *   caller was cancelled as the finds were stored, which then all are.
     */
    suspend operator fun invoke(
        sessionId: String,
        replacing: Set<String> = emptySet(),
        store: Boolean = true,
        readings: Boolean = true,
        takeAway: suspend (Set<String>) -> Unit = {}
    ): Outcome {
        var stored = 0
        try {
            if (!settingsRepository.getSettings().first().llmEnabled) return Outcome.Skipped
            val session = transcriptionRepository.getSession(sessionId).first() ?: return Outcome.Skipped
            val lines = transcriptionRepository.getLinesWithReadings(sessionId)
            val spoken = lines.said.filter { it.isComplete && it.text.isNotBlank() }
            // The lines the AI reads: a line only the reader reads as its reading, none without one.
            val segments = if (readings) lines.readable.filter { it.isComplete && it.text.isNotBlank() } else spoken
            if (segments.isEmpty()) return Outcome.Skipped

            // A Translate recording is read in its target language, the others in what was said (D8).
            val language = notesLanguage.readingLanguage(session, spoken.joinToString(" ") { it.text })
            val found = extractTodos(
                sessionId = sessionId,
                segments = segments,
                zone = ExtractTodosUseCase.zoneOf(session.zoneId),
                languageCode = language,
                existingTodos = (if (store) known(sessionId, session.createdAt) else knownAtFinishing(session))
                    .filter { it.id !in replacing },
                spotsOutright = session.mode != RecordingMode.REAL_TIME_TRANSLATION,
                asSaid = if (readings) lines.saidOfRead else emptyMap()
            )
            // Old finds give way only to a search the model read all of. From one it couldn't
            // finish (not loaded, an answer that failed) nothing is stored, and they stay.
            if (replacing.isNotEmpty() && !found.answered) return Outcome.Failed
            if (!store) return found.outcome(count = found.todos.size)
            withContext(NonCancellable) {
                for (todo in found.todos) {
                    todoRepository.upsert(todo)
                    stored++
                }
                // After the new ones: a process that dies between the two leaves both, never neither.
                if (replacing.isNotEmpty()) takeAway(replacing)
            }
            return found.outcome(count = stored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed
        } finally {
            if (stored > 0) rearmReminders()
        }
    }

    /**
     * What a new to-do or idea of the recording [sessionId] may repeat, as [ExtractTodosUseCase]
     * shows and compares it: everything the recording has, in any status, then every pending to-do
     * (open or suggested) and the ideas found from [IDEA_DAYS] days before the recording started
     * on, which is the last [IDEA_DAYS] days for a recording just made.
     *
     * The other recordings' to-dos and ideas only keep repeats out: when they can't be read, the
     * recording still gets its to-dos.
     */
    private suspend fun known(sessionId: String, startedAt: Long): List<Todo> {
        val own = todoRepository.observeForSession(sessionId).first()
        val others = try {
            val since = startedAt - IDEA_DAYS * DAY_MS
            todoRepository.observeOpen().first() + todoRepository.observeSuggested().first() +
                todoRepository.observeIdeas().first().filter { it.createdAt >= since }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return (own + others).distinctBy { it.id }
    }

    /**
     * What the recording's finishing showed the model as known ([known]), as near as can be told
     * now: the others' to-dos and ideas found before the recording's own (or, with none of its own,
     * before it ended), without its own, which the search had not found yet. Without this a rerun
     * would read the recording's own finds as "already noted", a prompt the finishing never sent.
     */
    private suspend fun knownAtFinishing(session: TranscriptionSession): List<Todo> {
        val all = known(session.id, session.createdAt)
        val finishedAt = all.filter { it.sessionId == session.id }.minOfOrNull { it.createdAt } ?: (session.createdAt + session.durationMs)
        return all.filter { it.sessionId != session.id && it.createdAt <= finishedAt }
    }

    /** The counts of what [ExtractTodosUseCase.Found] holds, [count] of them stored. */
    private fun ExtractTodosUseCase.Found.outcome(count: Int) = Outcome.Found(
        count = count,
        ideas = todos.count { it.status == TodoStatus.IDEA },
        suggested = todos.count { it.status == TodoStatus.SUGGESTED },
        overCap = overCap
    )

    /** Runs to the end even when the caller is cancelled: a stored reminder must get its alarm. */
    private suspend fun rearmReminders() {
        withContext(NonCancellable) {
            try {
                reminderScheduler.reschedule()
            } catch (e: Exception) {
                // Not worth failing the recording for: the next reschedule arms the alarm.
            }
        }
    }

    private companion object {
        /** The ideas of this many days before a recording are compared with its own. */
        const val IDEA_DAYS = 30L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
