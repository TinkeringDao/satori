package com.satori.presentation.main

import com.satori.domain.finish.FinishingQueue
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.reading.ReaderAvailability
import com.satori.domain.reading.ReaderStatus
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TranscriptionRepository
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backfill of readings (0.8.0, design R9, the owner's decision O8): whenever the reader is on
 * the phone, the Malayalam recordings that are due to be made again get their readings, and with
 * them their notes, name, to-dos and map, through the finishing queue and its rules
 * ([FinishingQueue.redo]): those finished without their AI steps for want of the reader (D7), and
 * those an older version finished with the AI model reading the lines as they were said, whose
 * notes, name and to-dos the run made again replaces, but what the owner changed
 * (FinishRecordingUseCase.Redo). Each is queued from its Reading step, and runs in the queue after
 * the hand-offs' jobs, under its rules: one at a time, never beside a capture.
 *
 * A recording is queued when all of these hold:
 * - AI notes are on, and an AI model is on the phone or on its way (the first run downloads it), so
 *   the AI steps can run once the lines are read;
 * - the reader is on the phone ([ReaderStatus.PRESENT]: its files whole and found with their pinned
 *   SHA-256, wherever they came from);
 * - no capture is live: it waits for one to end, so nothing is queued beside it;
 * - the recording is made in Malayalam, or its transcript was found Malayalam, is due to be made
 *   again (every Malayalam recording of before version 13, and one whose finishing ended without its
 *   AI steps for want of its readings, until a run made again finishes it), has no finishing job
 *   (none runs or waits for it, and no capture of it is under way), and fewer than [MAX_FAILURES]
 *   failures count against it ([TranscriptionRepository.backfillCandidates]);
 * - a line of it is one the reader reads ([com.satori.domain.reading.LinesWithReadings.readByReader]).
 *   Whether its lines were read already doesn't matter: the page's work, the map's and a debug rerun
 *   read them too, and leave what an older version's AI model wrote.
 *
 * It looks as the app starts ([start], from MainActivity, once per process) and again whenever the
 * reader may have come ([ReaderAvailability.changes]), AI notes are switched on or off, the AI model
 * set changes, or the first run's AI model comes or is dropped. Logs go to logcat under
 * SatoriFinishing, counts only.
 */
@Singleton
class ReadingBackfill internal constructor(
    private val queue: FinishingQueue,
    private val transcriptionRepository: TranscriptionRepository,
    private val reader: ReaderAvailability,
    private val settingsRepository: SettingsRepository,
    private val modelPaths: AiModelPaths,
    // Lazy: the first run's coordinator may itself depend on the queue.
    private val firstRun: () -> FirstRunCoordinator,
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val scope: CoroutineScope,
    private val log: FinisherLog
) {

    @Inject constructor(
        queue: FinishingQueue,
        transcriptionRepository: TranscriptionRepository,
        reader: ReaderAvailability,
        settingsRepository: SettingsRepository,
        modelPaths: AiModelPaths,
        firstRun: Lazy<FirstRunCoordinator>,
        activeRecorder: ActiveRecorder
    ) : this(
        queue = queue,
        transcriptionRepository = transcriptionRepository,
        reader = reader,
        settingsRepository = settingsRepository,
        modelPaths = modelPaths,
        firstRun = firstRun::get,
        recorder = activeRecorder.current,
        // Nothing it does in the background may end the app.
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> FinisherLog.Logcat.warn("Backfill of readings failed", e) }
        ),
        log = FinisherLog.Logcat
    )

    private val started = AtomicBoolean(false)

    /**
     * Starts looking, once per process; later calls do nothing. Each look runs to its end, and the
     * reasons to look that come meanwhile make one more. Main-safe, never throws.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            merge(flowOf(Unit), reader.changes, settingChanges(), firstRunChanges())
                .conflate()
                .catch { e -> log.warn("Backfill of readings stopped looking; the next start looks again", e) }
                .collect { look() }
        }
    }

    private fun settingChanges(): Flow<Unit> =
        settingsRepository.getSettings().map { it.llmEnabled to it.llmModelPath }.distinctUntilChanged().map { }

    private fun firstRunChanges(): Flow<Unit> =
        firstRun().state.map { it.aiModelPending }.distinctUntilChanged().map { }

    /**
     * Queues every recording due now, once no capture is live. A capture that starts as they are
     * queued holds back the rest until it is over; then they are read again, as those queued have a
     * job by now. A read that fails queues nothing; the next look reads again.
     */
    private suspend fun look() {
        while (true) {
            if (!due()) return
            // Never beside a capture: it waits for the capture to end, then looks again.
            if (recorder.value.isLiveCapture()) {
                recorder.first { !it.isLiveCapture() }
                continue
            }
            val due = try {
                transcriptionRepository.backfillCandidates(MAX_FAILURES)
                    .filter { transcriptionRepository.getLinesWithReadings(it).readByReader }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Backfill of readings could not read the recordings", e)
                return
            }
            if (due.isEmpty()) return
            var queued = 0
            var heldBack = false
            for (sessionId in due) {
                // A capture that starts meanwhile holds back the rest.
                if (recorder.value.isLiveCapture()) {
                    heldBack = true
                    break
                }
                if (queue.redo(sessionId)) queued++
            }
            log.info("Backfill of readings queued $queued of ${due.size} Malayalam recordings to be made again over their readings")
            if (!heldBack) return
        }
    }

    /**
     * Whether the AI steps of a recording queued now could run: AI notes on, an AI model on the
     * phone or on its way, and the reader on the phone. A look that fails says no.
     */
    private suspend fun due(): Boolean = try {
        val settings = settingsRepository.getSettings().first()
        settings.llmEnabled &&
            (modelPaths.livePath(settings) != null || firstRun().state.value.aiModelPending) &&
            reader.status() == ReaderStatus.PRESENT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Backfill of readings could not tell whether the reader and the AI model are in", e)
        false
    }

    companion object {
        /**
         * The runs of a recording's Reading step that may end with lines it never read before the
         * backfill stops trying it: a reader that failed counts one; a step skipped after it began
         * twice, the process ending in it each time, counts as all of them (RecordingFinisher).
         */
        const val MAX_FAILURES = 2
    }
}
