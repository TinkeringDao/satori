package com.satori.domain.usecase.mindmap

import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.service.LlmProcessingServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Maps the recordings that have no place in the mind map yet, those made before it existed or
 * while the AI was off ([MindMapRepository.unmappedSessionIds]). The map screen offers it as
 * "Map N older recordings" while the AI is available.
 *
 * What it does:
 * - Runs under the LLM processing foreground service, so it goes on while the app is in the
 *   background: [LlmProcessingServiceController.startProcessing] before the first recording, and
 *   stopProcessing however the run ends.
 * - Reads the waiting recordings one at a time, newest first, each with [MapRecordingUseCase],
 *   reporting [Progress.Linking] before each. The list is taken once, at the start.
 * - Then has [RefreshThemeSummariesUseCase] rewrite, with no limit, the themes the run linked
 *   recordings to and any other stale theme, reporting [Progress.Learning]. The summaries wait
 *   until every recording is read, so a theme that many older recordings join is written once,
 *   from all of them, rather than once for each.
 * - Runs once at a time in the whole app: a call while a run is going on returns
 *   [Outcome.AlreadyRunning] at once. The guard belongs to the class, not to an instance, so it
 *   holds whatever scope the use case is provided in.
 * - Can be cancelled: cancelling the caller's coroutine ends the run. What was mapped stays, and
 *   the themes not yet rewritten stay stale.
 * - Gives way to the finishing of a recording, and to a recording that starts: the caller's
 *   shouldYield is asked between two recordings, before the summaries and between two summaries,
 *   and a yes ends the run there with what it mapped and wrote ([Outcome.Finished.yielded]), so
 *   the AI is free for that recording within one model call. The rest wait for the next run, and
 *   the themes it linked or didn't get to stay stale until then.
 *
 * A recording whose answer can't be read ([MapRecordingUseCase.Outcome.Unreadable]) counts among
 * those without topics, and the run goes on; one deleted meanwhile is left out. A model that can't
 * be loaded or run ends the run, and so does a database that can't be read or written
 * ([MapRecordingUseCase.Outcome.Failed]): every recording after it would fail the same way, each
 * after a model call of its own.
 *
 * The caller sets the model up first (InitializeLlmUseCase with loadImmediately = false, as the
 * rewrite of notes does) and frees it when the run ends
 * ([com.satori.domain.repository.LlmRepository.cleanup]); the steps load it on demand. The
 * screen doesn't offer a run while a recording is being made or finished: the recorder and the
 * finishing need the memory and the AI, and that recording is mapped when it finishes.
 *
 * @property mindMapRepository Tells which recordings wait
 * @property mapRecording Maps one recording
 * @property refreshThemeSummaries Rewrites the summaries once the recordings are read
 * @property llmProcessingService Keeps the process alive while the run goes on
 */
class MapOlderRecordingsUseCase(
    private val mindMapRepository: MindMapRepository,
    private val mapRecording: MapRecordingUseCase,
    private val refreshThemeSummaries: RefreshThemeSummariesUseCase,
    private val llmProcessingService: LlmProcessingServiceController
) {
    private companion object {
        /** Set while a run goes on, in any instance: the app maps older recordings once at a time. */
        val running = AtomicBoolean(false)
    }

    /** Where a run is, for the screen's progress. */
    sealed interface Progress {
        /** Recording [index] (from 1) of [total] is being read. */
        data class Linking(val index: Int, val total: Int) : Progress

        /**
         * The summary of theme [index] (from 1) of [total] is being written, after every recording
         * was read.
         */
        data class Learning(val index: Int, val total: Int) : Progress
    }

    /** How a run ended, unless it was cancelled. */
    sealed interface Outcome {
        /**
         * Every waiting recording was read, or the run gave way before the end ([yielded]):
         * [mapped] were linked to at least one theme, [withoutTopics] gave no topic that held up
         * or no answer that could be read, and [summaries] summaries were written, fewer or none
         * when it gave way. The recordings a run that gave way didn't reach still wait.
         */
        data class Finished(
            val mapped: Int,
            val withoutTopics: Int,
            val summaries: Int,
            val yielded: Boolean = false
        ) : Outcome

        /** Another run was going on; this call did nothing. */
        data object AlreadyRunning : Outcome

        /**
         * The model could not be loaded or run, or the map could not be read or written (a
         * database error), so the run stopped. The [mapped] recordings mapped before it stay, and
         * their themes stay stale.
         */
        data class ModelFailed(val mapped: Int) : Outcome
    }

    /**
     * @param shouldYield Asked after each recording but the last, before the summaries and between
     *   two of them: true ends the run there ([Outcome.Finished.yielded])
     * @param leaveOut Recordings this run doesn't read: those whose lines only the reader reads
     *   lack readings the caller couldn't make now (EnsureReadingsUseCase, 0.8.0). They still wait,
     *   for a later run.
     * @param onProgress Called as each recording and each summary begins
     * @return How the run ended; throws only [kotlinx.coroutines.CancellationException]
     */
    suspend operator fun invoke(
        shouldYield: () -> Boolean = { false },
        leaveOut: Set<String> = emptySet(),
        onProgress: (Progress) -> Unit = {}
    ): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome.AlreadyRunning
        try {
            llmProcessingService.startProcessing(ProcessingHolders.MAP_OLDER)
            return run(shouldYield, leaveOut, onProgress)
        } finally {
            llmProcessingService.stopProcessing(ProcessingHolders.MAP_OLDER)
            running.set(false)
        }
    }

    private suspend fun run(shouldYield: () -> Boolean, leaveOut: Set<String>, onProgress: (Progress) -> Unit): Outcome {
        val waiting = try {
            mindMapRepository.unmappedSessionIds().first().filter { it !in leaveOut }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The database can't be read: like a recording that can't be, it ends the run.
            return Outcome.ModelFailed(mapped = 0)
        }
        var mapped = 0
        var withoutTopics = 0
        val joined = LinkedHashSet<String>()
        for ((index, sessionId) in waiting.withIndex()) {
            if (index > 0 && shouldYield()) return Outcome.Finished(mapped, withoutTopics, summaries = 0, yielded = true)
            onProgress(Progress.Linking(index + 1, waiting.size))
            when (val outcome = mapRecording(sessionId)) {
                is MapRecordingUseCase.Outcome.Mapped ->
                    if (outcome.themeIds.isEmpty()) {
                        withoutTopics++
                    } else {
                        mapped++
                        joined += outcome.themeIds
                    }
                MapRecordingUseCase.Outcome.Unreadable -> withoutTopics++
                MapRecordingUseCase.Outcome.Skipped -> Unit
                MapRecordingUseCase.Outcome.ModelFailed, MapRecordingUseCase.Outcome.Failed -> return Outcome.ModelFailed(mapped)
            }
        }
        // The summaries take a model call each: a recording waiting to be finished goes first.
        if (shouldYield()) return Outcome.Finished(mapped, withoutTopics, summaries = 0, yielded = true)
        var yielded = false
        val summaries = refreshThemeSummaries(
            joined.toList(),
            limit = Int.MAX_VALUE,
            shouldStop = { shouldYield().also { if (it) yielded = true } }
        ) { index, total ->
            onProgress(Progress.Learning(index, total))
        }
        return when (summaries) {
            is RefreshThemeSummariesUseCase.Outcome.Written -> Outcome.Finished(mapped, withoutTopics, summaries.count, yielded)
            is RefreshThemeSummariesUseCase.Outcome.ModelFailed -> Outcome.ModelFailed(mapped)
        }
    }
}
