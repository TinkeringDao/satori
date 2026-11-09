package com.satori.presentation.main

import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.finish.FinishingJobState
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.model.AppSettings
import com.satori.domain.model.CaptureState
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.reading.ReaderStatus
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.usecase.finish.BetterTranscriptRun
import com.satori.domain.usecase.finish.FinishRecordingUseCase
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.transcription.UpdateSessionDurationUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * The background finishing queue, on virtual time: the order of the jobs, their holders, the
 * gate across jobs, the keep-alive and the wake job, the waits and deferrals before a step,
 * cancel, what it does when a run or a write fails, resume after a process death, and what
 * screens see. The use case is scripted ([ScriptedFinish]): its own steps are
 * FinishRecordingUseCaseTest's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingFinisherTest {

    private val gate = AiWorkGate()
    private val jobs = InMemoryFinishingJobs()
    private val processing = FakeProcessing()
    private val wake = FakeWake()
    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val capture = FakeCapture()
    private val engine = EngineLog(gate)
    private val settings = HeldSettings()
    private val log = RecordedLog()
    private val lengths = StoredLengths()
    private val coordinator = FakeFirstRunCoordinator()
    private val readers = HeldReaderAvailability()
    private val notesReady = mutableListOf<String>()

    // The queue's looks for the AI model while jobs wait for it, every 30 s in the app; a test sends
    // them, as a timer that never ends would keep runTest from ending.
    private val modelRechecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var script: ScriptedFinish

    // The finisher's own scope, on the test's clock. Not the test's backgroundScope: advanceUntilIdle
    // stops once only background work is left, and the finisher's worker is all background work.
    private val scopes = mutableListOf<CoroutineScope>()
    private val failures = mutableListOf<Throwable>()

    @After
    fun stopFinishers() {
        scopes.forEach { it.cancel() }
        assertEquals(emptyList<Throwable>(), failures)
    }

    private fun TestScope.finisher(
        audio: HeardAudio = HeardAudio(),
        modelPaths: AiModelPaths = AiModelPaths { LIVE_PATH },
        owner: String = OWNER,
        repository: FinishingJobRepository = jobs,
        llm: LlmRepository = engine
    ): RecordingFinisher {
        script = ScriptedFinish(gate) { testScheduler.currentTime }
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e }
        ).also { scopes += it }
        return RecordingFinisher(
            finish = script::run,
            jobs = repository,
            gate = gate,
            controller = processing,
            wake = wake,
            recorder = recorder,
            stt = capture,
            llm = llm,
            memory = DeviceMemory { AVAILABLE_MB * MB },
            settingsRepository = settings,
            modelPaths = modelPaths,
            transcriptionRepository = lengths,
            audioRepository = audio,
            updateSessionDuration = UpdateSessionDurationUseCase(lengths),
            firstRun = Lazy { coordinator },
            reader = readers,
            notesReady = NotesReadyCue { notesReady += it },
            modelRechecks = modelRechecks,
            now = { testScheduler.currentTime },
            scope = scope,
            log = log,
            owner = owner
        )
    }

    /** The steps of a recording without lines only the reader reads, which has no Reading step. */
    private fun stepsOf(sessionId: String) = FinishStep.entries.filter { it != FinishStep.Reading }.map { "$sessionId $it" }

    /** The steps of a recording with lines only the reader reads ([ScriptedFinish.reads]). */
    private fun readSteps(sessionId: String) = FinishStep.entries.map { "$sessionId $it" }

    // ─── Order, holders and the gate ─────────────────────────────────────────────

    @Test
    fun `jobs are finished one at a time, in the order they were handed over`() = runTest {
        val f = finisher()

        f.handOff("C1", recognizerFailed = false)
        advanceTimeBy(1)
        f.handOff("A2", recognizerFailed = true)
        advanceTimeBy(1)
        f.handOff("B3", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("C1") + stepsOf("A2") + stepsOf("B3"), script.events)
        assertEquals(listOf(false, true, false), script.requests.map { it.recognizerFailed })
        assertTrue(script.requests.all { it.aiAvailable && it.mayChangeModel && !it.recovered && it.resumeFrom == null })
        assertNull(jobs.row("C1"))
        assertNull(jobs.row("A2"))
        assertNull(jobs.row("B3"))
    }

    @Test
    fun `the hand-off queues the job and takes its holder before it returns`() = runTest {
        val f = finisher()

        f.handOff("A", recognizerFailed = false)

        assertEquals(FinishingJobState.QUEUED, jobs.row("A")?.state)
        assertEquals(setOf("finish:A"), processing.holders)
        assertTrue("Finishing queued A" in log.lines)
    }

    @Test
    fun `a hand-off of a recording that is gone queues nothing and holds nothing`() = runTest {
        jobs.goneSessions += "A"
        val f = finisher()

        f.handOff("A", recognizerFailed = false)

        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `each job holds the service under its own key until its row goes`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        f.handOff("C", recognizerFailed = false)
        advanceUntilIdle()
        assertEquals(setOf("finish:A", "finish:B", "finish:C"), processing.holders)

        // C's recording is deleted elsewhere, which takes its row with it.
        jobs.drop("C")
        advanceUntilIdle()
        assertEquals(setOf("finish:A", "finish:B"), processing.holders)

        held.complete(Unit)
        advanceUntilIdle()
        assertTrue(processing.holders.isEmpty())
        assertEquals(listOf("stop finish:C", "stop finish:A", "stop finish:B"), processing.log.filter { it.startsWith("stop") })
    }

    @Test
    fun `the gate is held across consecutive jobs, and the model is freed and forgotten under it once the queue is empty`() = runTest {
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertEquals(setOf<String?>(AiWorkGate.FINISHER), script.gateHolders)
        // The live model is registered for each job, and freed once, after both.
        assertEquals(
            listOf("register $LIVE_PATH", "register $LIVE_PATH", "cleanup under finisher", "forget under finisher"),
            engine.log
        )
        assertNull(gate.holder.value)
        assertTrue("Finishing queue empty, model released" in log.lines)
    }

    @Test
    fun `with AI notes off the job runs without the AI, and the log says why`() = runTest {
        settings.settings.value = AppSettings(llmEnabled = false)
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertFalse(script.requests.single().aiAvailable)
        assertTrue("Finishing A without the AI: AI notes off" in log.lines)
    }

    @Test
    fun `without the live model on the phone the job runs without the AI, and nothing is registered`() = runTest {
        val f = finisher(modelPaths = AiModelPaths { null })

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertFalse(script.requests.single().aiAvailable)
        assertTrue(engine.log.none { it.startsWith("register") })
        assertTrue("Finishing A without the AI: no live model on the phone" in log.lines)
    }

    // ─── Keep-alive ──────────────────────────────────────────────────────────────

    @Test
    fun `no step starts without a keep-alive, and the wake job is scheduled 5 s after a start the service didn't get`() = runTest {
        processing.refused = true
        val f = finisher()
        f.handOff("A", recognizerFailed = false)

        advanceTimeBy(RecordingFinisher.KEEP_ALIVE_WAIT_MS - 1)
        assertEquals(0, wake.scheduled)
        advanceTimeBy(2)
        assertEquals(1, wake.scheduled)
        assertTrue(script.events.isEmpty())
        assertTrue("Finishing waits for a keep-alive" in log.lines)

        var done = 0
        f.runUnderWakeJob { done++ }
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        // Done once the queue is empty, and the job is no longer wanted.
        assertEquals(1, done)
        assertTrue(wake.cancelled > 0)
    }

    @Test
    fun `a service that comes up in time needs no wake job`() = runTest {
        processing.refused = true
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(2_000)

        processing.protected.value = true
        advanceUntilIdle()

        assertEquals(0, wake.scheduled)
        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `a wake job Android stops ends the step under way, and the job goes on at that step later`() = runTest {
        processing.refused = true
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        f.runUnderWakeJob {}
        advanceUntilIdle()

        f.wakeJobStopped()
        advanceUntilIdle()

        val row = jobs.row("A")!!
        assertEquals(FinishingJobState.QUEUED, row.state)
        assertEquals(FinishStep.Notes, row.nextStep)
        assertEquals(0, row.attempts)
        assertNull(gate.holder.value)

        held.complete(Unit)
        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertEquals(FinishStep.Notes, script.requests.last().resumeFrom)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `under the AI's service a stopped wake job changes nothing`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        f.wakeJobStopped()
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(1, script.requests.size)
    }

    @Test
    fun `a wake job that starts a new process finishes the jobs the dead one left, and is never scheduled while it runs`() = runTest {
        jobs.seed(job("A", FinishingJobState.RUNNING, owner = "gone", nextStep = FinishStep.Notes, attempts = 1, queuedAt = 1))
        val f = finisher()
        var done = 0

        // As FinishingWakeJob.onStartJob calls them, back to back.
        f.resume(mayStartForeground = false)
        f.runUnderWakeJob { done++ }
        advanceUntilIdle()

        assertEquals(stepsOf("A").drop(2), script.events)
        assertEquals(1, done)
        assertEquals(0, wake.scheduled)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a wake job that reports itself only after the take-up and the worker ran is not scheduled again`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, owner = "gone", queuedAt = 1))
        val f = finisher()

        f.resume(mayStartForeground = false)
        runCurrent()
        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(0, wake.scheduled)
    }

    @Test
    fun `a service Android ends while a job runs stops the job at its next step, and the wake job is scheduled`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // The service's onTimeout, with the holder still taken.
        processing.protected.value = false
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(stepsOf("A").take(3), script.events)
        val row = jobs.row("A")!!
        assertEquals(FinishingJobState.QUEUED, row.state)
        assertEquals(FinishStep.Name, row.nextStep)
        assertEquals(0, row.attempts)
        assertEquals(1, wake.scheduled)
        assertTrue("Finishing of A stopped for want of a keep-alive; its step runs again" in log.lines)
    }

    @Test
    fun `a wake job that starts while every job waits for a capture to end is let go at once`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        var done = 0

        f.runUnderWakeJob { done++ }
        advanceUntilIdle()

        assertEquals(1, done)
        assertTrue(script.events.isEmpty())
    }

    @Test
    fun `a wake job stopped while a step waits doesn't cut it short there, so the starts the step had stay`() = runTest {
        // Name began once before, in a process that died in it.
        jobs.seed(job("A", FinishingJobState.QUEUED, nextStep = FinishStep.Name, attempts = 1, queuedAt = 1))
        capture.captureState.value = CaptureState.OPENING
        val f = finisher()
        f.resume(mayStartForeground = false)
        f.runUnderWakeJob {}
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1_000)

        // Android stops the job as the step waits for a new recording's microphone.
        f.wakeJobStopped()
        advanceUntilIdle()

        assertTrue(script.events.isEmpty())
        val row = jobs.row("A")!!
        assertEquals(FinishingJobState.QUEUED, row.state)
        assertEquals(FinishStep.Name, row.nextStep)
        assertEquals(1, row.attempts)
        assertTrue("Finishing of A stopped for want of a keep-alive; its step runs again" in log.lines)
    }

    @Test
    fun `a step the wake job's end cuts short twice in a row is skipped, as one that began twice`() = runTest {
        processing.refused = true
        val f = finisher()
        // The whole-pass notes of a long meeting: longer than any run the wake job gets.
        script.holds["A" to FinishStep.Notes] = CompletableDeferred()
        f.handOff("A", recognizerFailed = false)

        repeat(RecordingFinisher.MAX_WAKE_JOB_CUTS) {
            f.runUnderWakeJob {}
            advanceUntilIdle()
            f.wakeJobStopped()
            advanceUntilIdle()
        }
        // Each cut gave its start back: the attempts rule alone would begin the step for ever.
        assertEquals(0, jobs.row("A")?.attempts)
        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertEquals(listOf("A Transcript", "A Lines", "A Notes", "A Notes", "A Name", "A Todos", "A Map"), script.events)
        assertTrue("Finishing skipped Notes of A: the wake job's end cut it short 2 times" in log.lines)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `the wake job's end never skips a Reading step, which goes on until it has read, and is no failure of the backfill`() = runTest {
        processing.refused = true
        val f = finisher()
        script.reads += "A"
        // A long recording's reading, longer than any run the wake job gets; each run stores what it read.
        val reading = CompletableDeferred<Unit>()
        script.readingHolds["A"] = reading
        f.handOff("A", recognizerFailed = false)

        repeat(RecordingFinisher.MAX_WAKE_JOB_CUTS + 1) {
            f.runUnderWakeJob {}
            advanceUntilIdle()
            f.wakeJobStopped()
            advanceUntilIdle()
        }
        reading.complete(Unit)
        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertTrue(log.lines.toString(), log.lines.none { it.startsWith("Finishing skipped") || it.contains("without its AI steps") })
        assertEquals(List(RecordingFinisher.MAX_WAKE_JOB_CUTS + 2) { "A Reading" }, script.events.filter { it == "A Reading" })
        assertEquals(listOf("A Notes", "A Name", "A Todos", "A Map"), script.events.takeLast(4))
        assertTrue(lengths.backfillFailures.isEmpty())
        assertNull(jobs.row("A"))
    }

    @Test
    fun `cuts of different steps don't add up`() = runTest {
        processing.refused = true
        val f = finisher()
        val notes = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = notes
        script.holds["A" to FinishStep.Name] = CompletableDeferred()
        f.handOff("A", recognizerFailed = false)
        f.runUnderWakeJob {}
        advanceUntilIdle()
        f.wakeJobStopped()
        advanceUntilIdle()

        // The next run gets through the notes and is cut short in the name.
        notes.complete(Unit)
        f.runUnderWakeJob {}
        advanceUntilIdle()
        f.wakeJobStopped()
        advanceUntilIdle()
        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertEquals(FinishStep.Name, script.requests.last().resumeFrom)
        assertTrue(log.lines.none { it.startsWith("Finishing skipped") })
    }

    @Test
    fun `an app opened while the wake job runs a job takes the AI's service for that job too`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, owner = "gone", queuedAt = 1))
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        // After a boot: the wake job takes up the queue and runs A.
        f.resume(mayStartForeground = false)
        f.runUnderWakeJob {}
        advanceUntilIdle()
        assertEquals(FinishingJobState.RUNNING, jobs.row("A")?.state)
        assertTrue(processing.holders.isEmpty())

        // The owner opens Satori, and then Android stops the wake job.
        f.resume(mayStartForeground = true)
        runCurrent()
        f.wakeJobStopped()
        held.complete(Unit)
        advanceUntilIdle()

        assertTrue("Finishing takes the AI's service for 1 unfinished" in log.lines)
        assertEquals(listOf("start finish:A", "stop finish:A"), processing.log)
        assertTrue(log.lines.none { it.contains("for want of a keep-alive") })
        assertEquals(stepsOf("A"), script.events)
        assertNull(jobs.row("A"))
    }

    // ─── What a step waits for ───────────────────────────────────────────────────

    @Test
    fun `the first step that may load a model waits 3 s after the hand-off, for a recording started right after Stop`() = runTest {
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // With no speech model set for a better transcript, neither it nor the joining of lines
        // loads one: they run at once, so the notes find out right after Stop whether there is
        // text enough for them.
        assertEquals(0L, script.times["A Transcript"])
        assertEquals(0L, script.times["A Lines"])
        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Notes"])
    }

    @Test
    fun `with a speech model set for the better transcript, its step waits the 3 s`() = runTest {
        settings.settings.value = AppSettings(transcribeAfterStop = SpeechModelId.COHERE_TRANSCRIBE)
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Transcript"])
        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Notes"])
    }

    @Test
    fun `a recording in Malayalam waits the 3 s with nothing set for after Stop, as the Malayalam model transcribes it again`() = runTest {
        lengths.languages["M"] = RecordingLanguage.MALAYALAM
        val f = finisher()

        f.handOff("M", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["M Transcript"])
        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["M Lines"])
    }

    @Test
    fun `a recording whose live recognizer left audio untranscribed waits the 3 s, as the live model transcribes it again`() = runTest {
        val f = finisher()

        f.handOff("A", recognizerFailed = true)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Transcript"])
        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Lines"])
        assertEquals(listOf(true), script.requests.map { it.recognizerFailed })
    }

    @Test
    fun `a quick-key recording started right after the Stop of a Malayalam one opens before its better transcript loads`() = runTest {
        lengths.languages["M"] = RecordingLanguage.MALAYALAM
        val f = finisher()
        f.handOff("M", recognizerFailed = false)
        advanceTimeBy(1_500)
        // The power key, 1.5 s after Stop: the next recorder starts, then its microphone opens.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        advanceTimeBy(2_000)
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.LISTENING
        advanceTimeBy(2_000)
        assertTrue(script.events.isEmpty())

        capture.captureState.value = CaptureState.TRANSCRIBING
        advanceUntilIdle()

        assertEquals(5_500L, script.times["M Transcript"])
        assertTrue(log.lines.any { it.startsWith("Finishing waited ") && it.endsWith("ms for a capture to open") })
    }

    @Test
    fun `a step waits at most 10 s for a capture that opens, steps that load no model don't wait, and later steps don't wait for it again`() = runTest {
        capture.captureState.value = CaptureState.OPENING
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        val opened = RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.CAPTURE_OPENING_WAIT_MS
        val loadingNone = listOf("A Transcript", "A Lines")
        assertEquals(loadingNone.map { it to 0L }, loadingNone.map { it to script.times[it] })
        val loading = stepsOf("A") - loadingNone.toSet()
        assertEquals(loading.map { it to opened }, loading.map { it to script.times[it] })
        assertEquals(1, log.lines.count { it.endsWith("for a capture to open, going on") })
    }

    @Test
    fun `a recorder held in its start is waited for once, and once more as its microphone opens`() = runTest {
        // Autostart waits for the screen: the recorder starts, and its microphone doesn't open yet.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        val wentOn = RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.CAPTURE_OPENING_WAIT_MS
        assertEquals(0L, script.times["A Transcript"])
        assertEquals(wentOn, script.times["A Notes"])

        // The microphone opens at last, as A's notes are written: the next step waits for its speech model.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.LISTENING
        held.complete(Unit)
        advanceTimeBy(2_000)
        capture.captureState.value = CaptureState.TRANSCRIBING
        advanceUntilIdle()

        assertEquals(wentOn + 2_000, script.times["A Name"])
        assertEquals(stepsOf("A"), script.events)
        assertEquals(1, log.lines.count { it.endsWith("for a capture to open, going on") })
        assertTrue("Finishing waited 2000 ms for a capture to open" in log.lines)
    }

    @Test
    fun `the next recorder held in its start is waited for again`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // B's start was given up, and C's start waits for the screen in its turn.
        recorder.value = ActiveRecorder.Snapshot("C", ActiveRecorder.Activity.STARTING)
        held.complete(Unit)
        advanceUntilIdle()

        val wentOn = RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.CAPTURE_OPENING_WAIT_MS
        assertEquals(wentOn + RecordingFinisher.CAPTURE_OPENING_WAIT_MS, script.times["A Name"])
        assertEquals(2, log.lines.count { it.endsWith("for a capture to open, going on") })
    }

    @Test
    fun `a step goes on as soon as the capture transcribes`() = runTest {
        capture.captureState.value = CaptureState.LISTENING
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(5_000)

        capture.captureState.value = CaptureState.TRANSCRIBING
        advanceUntilIdle()

        assertEquals(5_000L, script.times["A Notes"])
    }

    @Test
    fun `a hot phone pauses the job while a capture is live, until it cools`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        engine.thermal.value = ThermalThrottle.Reduced(1.5f)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(script.events.isEmpty())
        assertTrue("Finishing paused: phone hot" in log.lines)

        engine.thermal.value = ThermalThrottle.Normal
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `the pause for heat also ends with the capture, and heat alone doesn't pause`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        engine.thermal.value = ThermalThrottle.Reduced(1.5f)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        recorder.value = null
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(1, log.lines.count { it == "Finishing paused: phone hot" })
    }

    @Test
    fun `the recording's own recorder, still saving, is no capture beside its job`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("A", ActiveRecorder.Activity.RECORDING, translation = true)
        engine.thermal.value = ThermalThrottle.Reduced(1.5f)
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `a step also waits for another recorder that is starting, until it records`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 4_000)
        // Only what loads no model has run.
        assertEquals(listOf("A Transcript", "A Lines"), script.events)

        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS + 4_000, script.times["A Notes"])
        assertTrue(log.lines.any { it.startsWith("Finishing waited 4000 ms for a capture to open") })
    }

    @Test
    fun `jobs taken up as the process starts give a recording started with it the same 3 s`() = runTest {
        jobs.seed(job("A", FinishingJobState.RUNNING, owner = "gone", nextStep = FinishStep.Notes, attempts = 1, queuedAt = 1))
        val f = finisher()

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Notes"])
    }

    @Test
    fun `the waits before a step don't count as a start of it, so a process death in them costs it nothing`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // A new recording opens its microphone as the Name step would begin.
        capture.captureState.value = CaptureState.OPENING
        held.complete(Unit)
        advanceTimeBy(1_000)

        // What a process that died now would leave: the job at Name, with no start of it counted.
        assertEquals(FinishStep.Name, jobs.row("A")?.nextStep)
        assertEquals(0, jobs.row("A")?.attempts)

        capture.captureState.value = CaptureState.TRANSCRIBING
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a deferral keeps the starts a step had in a process that died`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, nextStep = FinishStep.Notes, attempts = 1, queuedAt = 1))
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        val row = jobs.row("A")!!
        assertTrue(row.waitsForRecording)
        assertEquals(FinishStep.Notes, row.nextStep)
        assertEquals(1, row.attempts)
    }

    @Test
    fun `a hot phone beside a Translate capture defers the job at once, as a pause would keep the model from it`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING, translation = true)
        engine.thermal.value = ThermalThrottle.Reduced(1.5f)
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(jobs.row("A")!!.waitsForRecording)
        assertNull(gate.holder.value)
        assertFalse("Finishing paused: phone hot" in log.lines)
    }

    @Test
    fun `a step that begins beside a capture with a model in memory logs the memory free`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        engine.inMemory = true
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue("Finishing: Notes of A after 3000 ms, beside a capture with a model in memory, 3400 MB available" in log.lines)
    }

    // ─── Deferred until no capture is live ───────────────────────────────────────

    @Test
    fun `a Translate capture defers the job, which goes on from that step once the capture ends`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(script.events.isEmpty())
        val waiting = jobs.row("A")!!
        assertTrue(waiting.waitsForRecording)
        assertEquals(FinishStep.Transcript, waiting.nextStep)
        assertEquals(0, waiting.attempts)
        assertEquals(listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_RECORDING)), f.statuses.value)
        assertTrue(f.busy.value)
        assertNull(gate.holder.value)
        assertTrue("Finishing deferred A at Transcript: a Translate capture is live" in log.lines)

        recorder.value = null
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(FinishStep.Transcript, script.requests.last().resumeFrom)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `no room for Gemma 4 E2B beside a capture defers the job at its notes, which go on once it ends`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        val f = finisher()
        script.deferWithoutRoom = true
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(listOf("A Transcript", "A Lines"), script.events)
        assertEquals(FinishStep.Notes, jobs.row("A")?.nextStep)
        assertTrue(jobs.row("A")!!.waitsForRecording)
        assertTrue(
            "Finishing deferred A at Notes: no room for Gemma 4 E2B beside a capture (3000 MB available, 3700 MB needed)" in log.lines
        )

        recorder.value = null
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(FinishStep.Notes, script.requests.last().resumeFrom)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a job deferred by a process that is gone goes on once no capture is live`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, nextStep = FinishStep.Name, waitsForRecording = true, queuedAt = 1))
        val f = finisher()

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(stepsOf("A").drop(3), script.events)
    }

    @Test
    fun `jobs a process that is gone deferred go on in their place once no recording is live`() = runTest {
        jobs.seed(job("C", FinishingJobState.QUEUED, nextStep = FinishStep.Name, waitsForRecording = true, queuedAt = 1))
        val f = finisher()
        advanceTimeBy(5)

        f.handOff("A", recognizerFailed = false)
        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(stepsOf("C").drop(3) + stepsOf("A"), script.events)
    }

    @Test
    fun `a deferred job goes on once its capture ended, also when the next one started before the recorder read idle`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        assertTrue(jobs.row("A")!!.waitsForRecording)

        // X is handed over and the quick key starts C at once: the recorder never reads idle between.
        recorder.value = ActiveRecorder.Snapshot("C", ActiveRecorder.Activity.STARTING)
        f.handOff("X", recognizerFailed = false)
        recorder.value = ActiveRecorder.Snapshot("C", ActiveRecorder.Activity.RECORDING)
        advanceUntilIdle()

        // C is no Translate capture, so both go on beside it.
        assertEquals(stepsOf("A") + stepsOf("X"), script.events)
        assertTrue("Finishing goes on with 1 deferred" in log.lines)
    }

    @Test
    fun `a hand-off while every job waits for a Translate capture is looked at, and waits with them`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // An older recorder hands its recording over late, while X still records.
        f.handOff("Y", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(script.events.isEmpty())
        assertTrue(jobs.row("Y")!!.waitsForRecording)
        assertEquals(
            listOf(
                FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_RECORDING),
                FinishingStatus("Y", FinishingStatus.Phase.WAITS_FOR_RECORDING)
            ),
            f.statuses.value
        )

        recorder.value = null
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("Y"), script.events)
    }

    @Test
    fun `a job deferred for a Translate capture goes on ahead of that capture's own job, handed off while its recorder still saves`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.RECORDING, translation = true)
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        assertTrue(jobs.row("A")!!.waitsForRecording)

        // X's Stop, as the recorder runs it: it saves, hands X off, and only then reads idle.
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.SAVING, translation = true)
        f.handOff("X", recognizerFailed = false)
        runCurrent()
        val midway = f.statuses.value
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.IDLE, translation = true)
        advanceUntilIdle()

        // No recording is live any more, so nothing waits for one.
        assertEquals(listOf("A", "X"), midway.map { it.sessionId })
        assertTrue(midway.none { it.phase == FinishingStatus.Phase.WAITS_FOR_RECORDING })
        assertEquals(stepsOf("A") + stepsOf("X"), script.events)
        assertTrue("Finishing goes on with 1 deferred" in log.lines)
    }

    @Test
    fun `a job deferred for want of room writes its notes ahead of the job of the capture it waited for`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        val f = finisher()
        script.deferWithoutRoom = true
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        assertEquals(listOf("A Transcript", "A Lines"), script.events)

        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.SAVING)
        f.handOff("B", recognizerFailed = false)
        runCurrent()
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.IDLE)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertEquals(listOf("A" to null, "A" to FinishStep.Notes, "B" to null), script.requests.map { it.sessionId to it.resumeFrom })
    }

    @Test
    fun `a job deferred for a capture that is handed off as the queue lets go of the model still goes on first`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.RECORDING, translation = true)
        val freed = CompletableDeferred<Unit>()
        val slowRelease = object : LlmRepository by engine {
            override suspend fun cleanup() {
                freed.await()
                engine.cleanup()
            }
        }
        val f = finisher(llm = slowRelease)
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        // A is deferred, and the queue is freeing the model for X's live translation.
        assertTrue(jobs.row("A")!!.waitsForRecording)

        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.SAVING, translation = true)
        f.handOff("X", recognizerFailed = false)
        freed.complete(Unit)
        runCurrent()
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.IDLE, translation = true)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("X"), script.events)
    }

    @Test
    fun `a recorder that still reads saving after its hand-off defers nothing and makes no room`() = runTest {
        // X was stopped and handed off; its recorder has not read idle yet.
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.SAVING, translation = true)
        val f = finisher()
        script.deferWithoutRoom = true

        f.handOff("A", recognizerFailed = false)
        f.handOff("X", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("X"), script.events)
        assertTrue(log.lines.none { it.startsWith("Finishing deferred") })
    }

    @Test
    fun `a hot phone doesn't pause for a recorder that still reads saving after its hand-off`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.SAVING)
        engine.thermal.value = ThermalThrottle.Reduced(1.5f)
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        f.handOff("X", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("X"), script.events)
        assertFalse("Finishing paused: phone hot" in log.lines)
    }

    @Test
    fun `a new capture of a recording handed off before is live again until its own hand-off`() = runTest {
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        // X is handed off, then recorded on in the same recorder, and is being stopped again.
        f.handOff("X", recognizerFailed = false)
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.RECORDING, translation = true)
        f.captureStarted("X")
        recorder.value = ActiveRecorder.Snapshot("X", ActiveRecorder.Activity.SAVING, translation = true)
        advanceUntilIdle()

        assertTrue(jobs.row("A")!!.waitsForRecording)
        assertTrue("Finishing deferred A at Transcript: a Translate capture is live" in log.lines)
    }

    // ─── Gemma 4 E2B beside a capture whose speech model changes ─────────────────

    @Test
    fun `beside a capture whose live model is changed after a switch of its language, E2B waits for the new one`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.TRANSCRIBING
        val f = finisher()
        var settled: Boolean? = null
        script.modelSteps["A"] = { progress ->
            // B is switched to Malayalam as the step begins: Parakeet is freed, IndicConformer loads.
            capture.captureState.value = CaptureState.LISTENING
            settled = progress.awaitCaptureSettled()
        }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1_500)
        assertNull(settled)

        capture.captureState.value = CaptureState.TRANSCRIBING
        advanceUntilIdle()

        assertEquals(true, settled)
        assertTrue("Finishing waited 1500 ms for a capture's speech model before Gemma 4 E2B" in log.lines)
    }

    @Test
    fun `a capture whose speech model isn't in after 10 s keeps E2B from being measured beside it`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        val f = finisher()
        var settled: Boolean? = null
        script.modelSteps["A"] = { progress ->
            capture.captureState.value = CaptureState.LISTENING
            settled = progress.awaitCaptureSettled()
        }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.CAPTURE_OPENING_WAIT_MS + 1_000)

        assertEquals(false, settled)
        assertTrue("Finishing waited 10000 ms for a capture's speech model before Gemma 4 E2B, deferring" in log.lines)
    }

    @Test
    fun `beside a capture with its speech model in, E2B is measured at once`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.TRANSCRIBING
        val f = finisher()
        var settled: Boolean? = null
        script.modelSteps["A"] = { progress -> settled = progress.awaitCaptureSettled() }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)

        assertEquals(true, settled)
        assertTrue(log.lines.none { it.contains("for a capture's speech model") })
    }

    @Test
    fun `beside a capture with the Malayalam model in, E2B's room keeps what a switch back to English or German loads`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.TRANSCRIBING
        capture.growthBytes = 508L * 1_048_576L
        val f = finisher()
        var kept: Long? = null
        script.modelSteps["A"] = { progress -> kept = progress.captureGrowthBytes() }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)

        assertEquals(508L * 1_048_576L, kept)
        assertTrue("Finishing A: Gemma 4 E2B's room beside the capture keeps 508 MB for its speech model" in log.lines)
    }

    @Test
    fun `with no capture live, or one whose speech model can't grow, nothing is kept for it`() = runTest {
        capture.growthBytes = 508L * 1_048_576L
        val f = finisher()
        val kept = mutableListOf<Long>()
        script.modelSteps["A"] = { progress -> kept += progress.captureGrowthBytes() }
        script.modelSteps["C"] = { progress -> kept += progress.captureGrowthBytes() }
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        // B records in English or German, with Parakeet v3 in.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)
        capture.captureState.value = CaptureState.TRANSCRIBING
        capture.growthBytes = 0L
        f.handOff("C", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)

        assertEquals(listOf(0L, 0L), kept)
        assertTrue(log.lines.none { it.contains("for its speech model") })
    }

    @Test
    fun `the languages a job reads and writes in go to the log`() = runTest {
        val f = finisher()
        script.modelSteps["A"] = { progress ->
            progress.onLanguages(FinishRecordingUseCase.Languages("ml", RecordingLanguage.MALAYALAM, "en"))
        }
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue("Finishing A: transcript in ml, recorded in MALAYALAM, notes in en" in log.lines)
    }

    // ─── Gemma 4 E2B with no capture live ────────────────────────────────────────

    @Test
    fun `before E2B loads the step waits for the speech model of the recorder that closed, and says how long`() = runTest {
        capture.liveModelLoaded.value = true
        val f = finisher()
        script.modelSteps["A"] = { progress -> progress.awaitSpeechModelFreed() }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1_000)
        assertNull(script.times["A Notes"])

        capture.liveModelLoaded.value = false
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS + 1_000, script.times["A Notes"])
        assertTrue("Finishing waited 1000 ms for the speech model to be freed" in log.lines)
        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `a speech model that is out already, as after a Stop, is not waited for`() = runTest {
        val f = finisher()
        script.modelSteps["A"] = { progress -> progress.awaitSpeechModelFreed() }
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Notes"])
        assertTrue(log.lines.none { it.contains("for the speech model") })
    }

    @Test
    fun `a speech model still in memory after 5 s is waited for no longer`() = runTest {
        capture.liveModelLoaded.value = true
        val f = finisher()
        script.modelSteps["A"] = { progress -> progress.awaitSpeechModelFreed() }
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.SPEECH_MODEL_FREE_WAIT_MS, script.times["A Notes"])
        assertTrue("Finishing waited 5000 ms for the speech model to be freed, going on" in log.lines)
    }

    @Test
    fun `a capture that starts ends the wait for the speech model, which is that capture's own then`() = runTest {
        capture.liveModelLoaded.value = true
        val f = finisher()
        script.modelSteps["A"] = { progress -> progress.awaitSpeechModelFreed() }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 500)

        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS + 500, script.times["A Notes"])
        assertTrue("Finishing waited 500 ms for the speech model to be freed, a capture started" in log.lines)
    }

    @Test
    fun `a capture that starts during that wait defers the job until it ends, and the log says why`() = runTest {
        capture.liveModelLoaded.value = true
        val f = finisher()
        script.modelSteps["A"] = { progress -> progress.awaitSpeechModelFreed() }
        script.deferOnOpening = true
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 500)

        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        advanceUntilIdle()

        assertEquals(listOf("A Transcript", "A Lines"), script.events)
        assertEquals(FinishStep.Notes, jobs.row("A")?.nextStep)
        assertTrue(jobs.row("A")!!.waitsForRecording)
        assertTrue("Finishing deferred A at Notes: a capture started as Gemma 4 E2B was to load" in log.lines)

        // B's capture ends, and its speech model goes with it.
        capture.liveModelLoaded.value = false
        recorder.value = null
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(FinishStep.Notes, script.requests.last().resumeFrom)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `the waits for memory are logged with the figures, and the live model they ended on`() = runTest {
        val f = finisher()
        // 3 s after a capture's Stop on the phone 3096 MB were free, against the 3794 MB E2B needs
        // (its 3194 MB file and 600 MB to spare); here memory is still short at the last try.
        val short = NotEnoughMemoryException(availableBytes = 3_096L * MB, neededBytes = 3_794L * MB)
        val last = NotEnoughMemoryException(availableBytes = 3_150L * MB, neededBytes = 3_794L * MB)
        script.modelSteps["A"] = { progress ->
            progress.onModelWait(FinishRecordingUseCase.ModelWait.Short(short))
            delay(FinishRecordingUseCase.IDLE_RETRY_MS)
            progress.onModelWait(FinishRecordingUseCase.ModelWait.StillShort(last))
            progress.onModel(FinishingModel.FellBack(last))
        }
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        val from = log.lines.indexOf(
            "Finishing A: no room for Gemma 4 E2B with no capture live (3096 MB available, 3794 MB needed), trying again for up to 15 s"
        )
        assertTrue(log.lines.joinToString("\n"), from >= 0)
        assertEquals(
            listOf(
                "Finishing A: still no room for Gemma 4 E2B after 15000 ms (3150 MB available, 3794 MB needed), " +
                    "the live model does the work",
                "W Gemma 4 E2B could not be loaded (15000 ms), finishing A on the live model"
            ),
            log.lines.subList(from + 1, from + 3)
        )
    }

    @Test
    fun `room found within the tries, and a capture that starts during them, are logged`() = runTest {
        val f = finisher()
        script.modelSteps["A"] = { progress ->
            delay(2_000L)
            progress.onModelWait(FinishRecordingUseCase.ModelWait.Room(tries = 2))
            progress.onModel(FinishingModel.Switched)
        }
        script.modelSteps["B"] = { progress ->
            delay(4_000L)
            progress.onModelWait(FinishRecordingUseCase.ModelWait.CaptureStarted)
        }
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue("Finishing A: room for Gemma 4 E2B at try 2, after 2000 ms" in log.lines)
        assertTrue("Finishing A on Gemma 4 E2B, ready after 2000 ms" in log.lines)
        assertTrue("Finishing B: a capture started while Gemma 4 E2B didn't fit, after 4000 ms" in log.lines)
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────────

    @Test
    fun `a waiting job that is cancelled goes, with its holder, and never runs`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        f.cancel("B")

        assertNull(jobs.row("B"))
        assertFalse("finish:B" in processing.holders)
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `a running job that is cancelled stops at its step and is gone when cancel returns, and the next one runs`() = runTest {
        val f = finisher()
        script.holds["A" to FinishStep.Notes] = CompletableDeferred()
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        f.cancel("A")

        assertNull(jobs.row("A"))
        assertFalse("finish:A" in processing.holders)
        assertTrue("Finishing of A cancelled" in log.lines)
        advanceUntilIdle()
        assertEquals(stepsOf("A").take(3) + stepsOf("B"), script.events)
    }

    @Test
    fun `a running job that is cancelled ends as cancelled also when its work throws something else as it stops`() = runTest {
        val f = finisher()
        // The notes step's work, cut short by the cancel, throws an error of its own instead of the cancel.
        script.modelSteps["A"] = {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                throw OutOfMemoryError("no room as the step stopped")
            }
        }
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        f.cancel("A")
        advanceUntilIdle()

        assertNull(jobs.row("A"))
        assertTrue("Finishing of A cancelled" in log.lines)
        assertTrue(log.lines.toString(), "W Finishing of A threw as it was stopped" in log.lines)
        assertFalse(log.lines.toString(), "Finishing of A runs once more" in log.lines)
        // A ran once, up to its notes, and never again; B ran after it.
        assertEquals(1, script.requests.count { it.sessionId == "A" })
        assertEquals(stepsOf("A").take(2) + stepsOf("B"), script.events)
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `a cancel whose caller goes away meanwhile still takes the running job out`() = runTest {
        val f = finisher()
        script.holds["A" to FinishStep.Notes] = CompletableDeferred()
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        val caller = launch { f.cancel("A") }
        runCurrent()
        caller.cancel()
        advanceUntilIdle()

        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
        assertEquals(stepsOf("A").take(3), script.events)
    }

    @Test
    fun `a job whose row goes while it runs stops before its next step`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        jobs.drop("A")
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(stepsOf("A").take(3), script.events)
        assertTrue(processing.holders.isEmpty())
        assertTrue("Finishing of A stopped: its job is gone" in log.lines)
    }

    @Test
    fun `a new capture of a recording whose job waits takes the job back`() = runTest {
        val f = finisher()
        f.handOff("A", recognizerFailed = false)

        f.captureStarted("A")
        advanceUntilIdle()

        assertEquals(FinishingJobState.CAPTURING, jobs.row("A")?.state)
        assertEquals(OWNER, jobs.row("A")?.owner)
        assertTrue(processing.holders.isEmpty())
        assertTrue(script.events.isEmpty())
    }

    @Test
    fun `a new capture whose caller goes away while the old job's model load ends still stores the capture`() = runTest {
        val f = finisher()
        val load = CompletableDeferred<Unit>()
        script.loads["A" to FinishStep.Notes] = load
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        val caller = launch { f.captureStarted("A") }
        runCurrent()
        caller.cancel()
        load.complete(Unit)
        advanceUntilIdle()

        assertEquals(FinishingJobState.CAPTURING, jobs.row("A")?.state)
        assertTrue(processing.holders.isEmpty())
        assertEquals(stepsOf("A").take(3), script.events)
    }

    @Test
    fun `a hand-off that comes while its capture's start is stored waits for it, and the job runs`() = runTest {
        val f = finisher()
        val write = CompletableDeferred<Unit>()
        jobs.captureWrite = write
        // The recorder closes as its capture starts (onCleared): the start's write is on its way.
        launch { f.captureStarted("A") }
        runCurrent()

        val handOff = launch { f.handOff("A", recognizerFailed = true) }
        runCurrent()
        val waited = !handOff.isCompleted
        write.complete(Unit)
        advanceUntilIdle()

        assertTrue("the hand-off waits for the start", waited)
        assertTrue(handOff.isCompleted)
        assertEquals(stepsOf("A"), script.events)
        assertTrue(script.requests.single().recognizerFailed)
        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `a hand-off whose caller goes away while the job is written still holds the job and runs it`() = runTest {
        val f = finisher()
        val write = CompletableDeferred<Unit>()
        jobs.queueWrite = write

        val caller = launch { f.handOff("A", recognizerFailed = false) }
        runCurrent()
        caller.cancel()
        write.complete(Unit)
        runCurrent()

        assertEquals(setOf("finish:A"), processing.holders)
        advanceUntilIdle()
        assertEquals(stepsOf("A"), script.events)
    }

    // ─── When something fails ────────────────────────────────────────────────────

    @Test
    fun `a run that fails is run once more`() = runTest {
        val f = finisher()
        script.failing["A"] = 1

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(2, script.requests.size)
        assertEquals(stepsOf("A"), script.events)
        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
        assertTrue("W Finishing of A failed" in log.lines)
        assertTrue("Finishing of A runs once more" in log.lines)
    }

    @Test
    fun `a job whose run fails twice waits for the next process, holding nothing, and the next job runs`() = runTest {
        val f = finisher()
        script.failing["A"] = 2

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("B"), script.events)
        // Its row stays as it was, running, which the next process takes up.
        assertEquals(FinishingJobState.RUNNING, jobs.row("A")?.state)
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
        assertTrue("W Finishing of A failed again; its job waits for the next start" in log.lines)

        val next = finisher(owner = "next-process")
        next.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertNull(jobs.row("A"))
    }

    // 0.7.4: on 2026-10-19 an ExceptionInInitializerError out of a run ended the worker, and the
    // recording stayed in "Finishing up" with every job after it.

    @Test
    fun `a run that throws an error is run once more from its step, and the queue goes on`() = runTest {
        val f = finisher()
        script.throwsAt["A" to FinishStep.Todos] = mutableListOf(ExceptionInInitializerError("the rules could not start"))

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertEquals(listOf(null, FinishStep.Todos, null), script.requests.map { it.resumeFrom })
        assertTrue(log.lines.toString(), "W Finishing of A failed" in log.lines)
        assertTrue("Finishing of A runs once more" in log.lines)
        assertTrue(log.lines.toString(), "Finishing started A at Todos, attempt 2" in log.lines)
        assertNull(jobs.row("A"))
        assertNull(jobs.row("B"))
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `a run that throws an error every time ends its job after its second run, with what it made, and the queue goes on`() = runTest {
        val f = finisher()
        script.throwsAlways["A" to FinishStep.Todos] = { NoClassDefFoundError("Could not initialize the rules") }

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        // A's steps before its to-dos, once, then all of B's.
        assertEquals(stepsOf("A").take(4) + stepsOf("B"), script.events)
        assertEquals(listOf(null, FinishStep.Todos, null), script.requests.map { it.resumeFrom })
        assertTrue(
            log.lines.toString(),
            "W Finishing of A failed again on NoClassDefFoundError; its job ends with what it made" in log.lines
        )
        // The page no longer says it is finishing: no row, no holder, no status.
        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)

        // Nothing is left for the next process to run again.
        val next = finisher(owner = "next-process")
        next.resume(mayStartForeground = true)
        advanceUntilIdle()
        assertTrue(script.requests.isEmpty())
    }

    @Test
    fun `an error in the queue's own work lets go of every holder, and the next hand-off takes the queue up`() = runTest {
        var failClaim = true
        val failing = object : FinishingJobRepository by jobs {
            override suspend fun claimNext(owner: String): FinishingJob? {
                if (failClaim) {
                    failClaim = false
                    throw OutOfMemoryError("no room to read the queue")
                }
                return jobs.claimNext(owner)
            }
        }
        val f = finisher(repository = failing)

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(processing.holders.isEmpty())
        assertTrue(script.events.isEmpty())
        assertNull(gate.holder.value)
        assertTrue(log.lines.toString(), "W Finishing stopped on an error; the queue is taken up again at the next hand-off or start" in log.lines)

        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `an error as the queue frees the model lets go of the gate, and the next hand-off runs its job`() = runTest {
        var failCleanup = true
        val failing = object : LlmRepository by engine {
            override suspend fun cleanup() {
                if (failCleanup) {
                    failCleanup = false
                    throw OutOfMemoryError("no room to free the model")
                }
                engine.cleanup()
            }
        }
        val f = finisher(llm = failing)

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
        assertNull(gate.holder.value)
        assertTrue(processing.holders.isEmpty())
        assertTrue(log.lines.toString(), "W Finishing stopped on an error; the queue is taken up again at the next hand-off or start" in log.lines)

        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertNull(jobs.row("B"))
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertNull(gate.holder.value)
        assertEquals(listOf("cleanup under finisher", "forget under finisher"), engine.log.filterNot { it.startsWith("register") })
    }

    // ─── A model load the phone's memory stopped (0.6.1's paced loads) ────────────

    @Test
    fun `a run whose model load the phone's memory stopped runs again from that step after a pause, its start not counted`() = runTest {
        val f = finisher()
        script.loadStops["A"] = 1

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // Each step once: the Notes step and those after it in the second run.
        assertEquals(stepsOf("A"), script.events)
        assertEquals(listOf(null, FinishStep.Notes), script.requests.map { it.resumeFrom })
        assertTrue(script.times.getValue("A Notes") >= script.times.getValue("A stopped") + RecordingFinisher.MEMORY_PAUSE_MS)
        assertTrue(
            log.lines.toString(),
            "Finishing of A stopped at Notes: the phone stayed short of memory as the model loaded; it runs again in 60 s" in log.lines
        )
        assertTrue(log.lines.toString(), "Finishing started A at Notes, attempt 1" in log.lines)
        assertNull(jobs.row("A"))
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `while the queue waits for memory it holds neither the gate nor a model, and screens see the job waiting`() = runTest {
        val f = finisher()
        script.loadStops["A"] = 1
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + RecordingFinisher.MEMORY_PAUSE_MS / 2)
        runCurrent()

        assertTrue(script.times.containsKey("A stopped"))
        assertNull(gate.holder.value)
        assertTrue(engine.log.toString(), "cleanup under finisher" in engine.log)
        assertEquals(listOf(FinishingStatus.Phase.WAITING), f.statuses.value.map { it.phase })
        assertEquals(FinishingJobState.QUEUED, jobs.row("A")?.state)
        assertEquals(setOf("finish:A"), processing.holders)

        advanceUntilIdle()
        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `a job whose model loads the phone's memory stopped three times waits for the next process, and the next job runs`() = runTest {
        val f = finisher()
        script.loadStops["A"] = RecordingFinisher.MAX_MEMORY_STOPS
        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A").take(2) + stepsOf("B"), script.events)
        assertEquals(FinishingJobState.RUNNING, jobs.row("A")?.state)
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertTrue(log.lines.toString(), "W Finishing of A stopped at Notes for want of memory 3 times; its job waits for the next start" in log.lines)
        // Its step's starts were given back each time, so the next process doesn't skip it.
        assertEquals(0, jobs.row("A")?.attempts)
        log.lines.clear()

        val next = finisher(owner = "next-process")
        next.resume(mayStartForeground = true)
        advanceUntilIdle()

        // From the Notes step, its starts given back each time.
        assertEquals(stepsOf("A").drop(2), script.events)
        assertEquals(FinishStep.Notes, script.requests.single().resumeFrom)
        assertTrue(log.lines.toString(), "Finishing started A at Notes, attempt 1" in log.lines)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a job whose end can't be stored waits for the next process, and its holder goes`() = runTest {
        val f = finisher()
        jobs.failing["delete"] = 1

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertEquals(FinishingJobState.RUNNING, jobs.row("A")?.state)
        assertNull(jobs.row("B"))
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
    }

    @Test
    fun `a job whose end can't be stored for an error waits for the next process too, and the next job runs`() = runTest {
        var failDelete = true
        val failing = object : FinishingJobRepository by jobs {
            override suspend fun delete(sessionId: String) {
                if (failDelete) {
                    failDelete = false
                    throw OutOfMemoryError("no room to store the end")
                }
                jobs.delete(sessionId)
            }
        }
        val f = finisher(repository = failing)

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        // A's row stays as it was, running; nothing holds the service for it, and screens don't show it.
        assertEquals(FinishingJobState.RUNNING, jobs.row("A")?.state)
        assertNull(jobs.row("B"))
        assertTrue(processing.holders.isEmpty())
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
        assertTrue(log.lines.toString(), "W Finishing could not store how A ended; its job waits for the next start" in log.lines)

        val next = finisher(owner = "next-process")
        next.resume(mayStartForeground = true)
        advanceUntilIdle()

        // Its last step began once, so the next process runs it again, and the job ends there.
        assertEquals(listOf("A ${FinishStep.entries.last()}"), script.events)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a job whose step can't be stored as it begins is run once more`() = runTest {
        val f = finisher()
        jobs.failing["beginStep"] = 1

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(2, script.requests.size)
        assertEquals(stepsOf("A"), script.events)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a queue that can't be read lets go of every holder, and the next hand-off takes it up`() = runTest {
        val f = finisher()
        jobs.failing["claimNext"] = 1

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(processing.holders.isEmpty())
        assertTrue(script.events.isEmpty())
        assertEquals(FinishingJobState.QUEUED, jobs.row("A")?.state)
        assertNull(gate.holder.value)

        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertTrue(processing.holders.isEmpty())
    }

    @Test
    fun `what screens see of the queue is read again after a read that failed`() = runTest {
        var failNextRead = false
        var reads = 0
        val flaky = object : FinishingJobRepository by jobs {
            // The first flow asked for is the one screens see; the worker reads its own.
            override fun observeAll(): Flow<List<FinishingJob>> =
                if (reads++ > 0) {
                    jobs.observeAll()
                } else {
                    jobs.observeAll().onEach {
                        if (failNextRead) {
                            failNextRead = false
                            throw IllegalStateException("disk I/O error")
                        }
                    }
                }
        }
        val f = finisher(repository = flaky)
        val held = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Notes] = held
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()
        assertTrue(f.busy.value)

        // One read fails as A is finished.
        failNextRead = true
        held.complete(Unit)
        advanceUntilIdle()

        assertTrue("W Finishing queue could not be read; reading it again in 1000 ms" in log.lines)
        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
        assertEquals(0 to null, processing.shown.last())
    }

    // ─── After a process death ───────────────────────────────────────────────────

    @Test
    fun `resume takes up the jobs of a process that is gone once, and skips a step that began twice`() = runTest {
        jobs.seed(job("X", FinishingJobState.CAPTURING, owner = "gone", createdAt = 1))
        jobs.seed(job("Y", FinishingJobState.RUNNING, owner = "gone", nextStep = FinishStep.Notes, attempts = 2, queuedAt = 5, createdAt = 2))
        jobs.seed(job("Z", FinishingJobState.CAPTURING, owner = OWNER, createdAt = 3))
        val f = finisher()

        f.resume(mayStartForeground = true)
        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(1, jobs.recoveries)
        assertTrue("Finishing resumed 2 (recovered 1)" in log.lines)
        // This process's own capture is left alone.
        assertEquals(FinishingJobState.CAPTURING, jobs.row("Z")?.state)
        val x = script.requests.single { it.sessionId == "X" }
        assertTrue(x.recovered)
        assertNull(x.resumeFrom)
        val y = script.requests.single { it.sessionId == "Y" }
        assertEquals(FinishStep.Name, y.resumeFrom)
        assertTrue("Finishing skipped Notes of Y: began twice" in log.lines)
        assertEquals(listOf("start finish:X", "start finish:Y"), processing.log.filter { it.startsWith("start") })
        assertTrue(processing.holders.isEmpty())
        assertNull(jobs.row("X"))
        assertNull(jobs.row("Y"))
    }

    @Test
    fun `a step that began once runs again, and a last step that began twice ends the job`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, nextStep = FinishStep.Todos, attempts = 1, queuedAt = 1))
        jobs.seed(job("B", FinishingJobState.QUEUED, nextStep = FinishStep.Map, attempts = 2, queuedAt = 2))
        val f = finisher()

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(listOf("A Todos", "A Map"), script.events)
        assertTrue("Finishing started A at Todos, attempt 2" in log.lines)
        assertTrue(script.requests.none { it.sessionId == "B" })
        assertNull(jobs.row("B"))
    }

    @Test
    fun `resumed without leave to start the service, the queue schedules the wake job and waits for it`() = runTest {
        jobs.seed(job("A", FinishingJobState.QUEUED, queuedAt = 1))
        val f = finisher()

        f.resume(mayStartForeground = false)
        // A wake job that started the process would have reported itself by now.
        advanceTimeBy(RecordingFinisher.WAKE_JOB_SETTLE_MS - 1)
        assertEquals(0, wake.scheduled)
        advanceUntilIdle()

        assertTrue(processing.holders.isEmpty())
        assertEquals(1, wake.scheduled)
        assertTrue(script.events.isEmpty())

        f.runUnderWakeJob {}
        advanceUntilIdle()

        assertEquals(stepsOf("A"), script.events)
    }

    @Test
    fun `an app opened after a boot takes the AI's service for the jobs the boot took up`() = runTest {
        jobs.seed(job("A", FinishingJobState.RUNNING, owner = "gone", nextStep = FinishStep.Notes, attempts = 1, queuedAt = 1))
        val f = finisher()
        f.resume(mayStartForeground = false)
        runCurrent()
        assertTrue(processing.holders.isEmpty())

        f.resume(mayStartForeground = true)
        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(1, jobs.recoveries)
        assertEquals(listOf("start finish:A"), processing.log.filter { it.startsWith("start") })
        assertTrue("Finishing takes the AI's service for 1 unfinished" in log.lines)
        assertEquals(stepsOf("A").drop(2), script.events)
        assertEquals(0, wake.scheduled)
    }

    @Test
    fun `a holder the resume takes for a job that ended as the queue was read is let go at once`() = runTest {
        // A's row is gone, but the queue reads as it was a moment before the worker finished A,
        // until A's holder is taken.
        val stale = object : FinishingJobRepository by jobs {
            override fun observeAll(): Flow<List<FinishingJob>> = flow {
                emit(if ("start finish:A" in processing.log) emptyList() else listOf(job("A", FinishingJobState.QUEUED, queuedAt = 1)))
            }
            override suspend fun get(sessionId: String): FinishingJob? = null
        }
        val f = finisher(repository = stale)

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertTrue(processing.holders.isEmpty())
        assertEquals(listOf("start finish:A", "stop finish:A"), processing.log)
    }

    @Test
    fun `a recording whose capture was cut off gets the length its audio has`() = runTest {
        lengths.lengths["X"] = 20_000L
        lengths.lengths["Y"] = 40_000L
        jobs.seed(job("X", FinishingJobState.CAPTURING, owner = "gone", createdAt = 1))
        jobs.seed(job("Y", FinishingJobState.CAPTURING, owner = "gone", createdAt = 2))
        val f = finisher(audio = HeardAudio(mapOf("X" to 27_500L, "Y" to 39_000L)))

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(27_500L, lengths.lengths["X"])
        // A stored length that is longer stays.
        assertEquals(40_000L, lengths.lengths["Y"])
    }

    // ─── Waiting for the AI model the first run downloads ───────────────────────

    /** The first run with the AI model planned, not on the phone yet, or with it checked. */
    private val modelOnItsWay = FirstRunSnapshot(
        stage = FirstRunStage.DONE,
        plan = FirstRunPlan.initial(speechBytes = 670L * MB, malayalamBytes = null, aiModelBytes = 1_020L * MB)
            .done(FirstRunPart.SPEECH)
    )
    private val modelIn = modelOnItsWay.copy(plan = modelOnItsWay.plan.done(FirstRunPart.AI_MODEL))

    @Test
    fun `a job saved while the AI model is on its way runs up to its first AI step and waits for it there, holding nothing`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        val f = finisher(modelPaths = AiModelPaths { null })

        f.handOff("A", recognizerFailed = false)
        runCurrent()

        // At once after Stop: the lines are joined, and the notes wait for the model, not for a capture.
        assertEquals(listOf("A Transcript", "A Lines"), script.events)
        assertTrue(script.requests.single().modelPending)
        assertFalse(script.requests.single().aiAvailable)
        assertEquals(listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL)), f.statuses.value)
        // Nothing runs for it until the model is in: work that gives way to the queue may run.
        assertFalse(f.busy.value)
        val row = jobs.row("A")!!
        assertEquals(FinishingJobState.QUEUED, row.state)
        assertEquals(FinishStep.Notes, row.nextStep)
        assertEquals(0, row.attempts)
        assertTrue(processing.holders.isEmpty())
        assertNull(gate.holder.value)
        // The AI's notification doesn't count it: the service doesn't run for it.
        assertEquals(0 to null, processing.shown.last())
        assertTrue("Finishing A up to its first AI step: the AI model is on its way" in log.lines)
        assertTrue("Finishing of A waits for the AI model at Notes" in log.lines)
    }

    @Test
    fun `once the first run has the AI model, the waiting job goes on from its step with the AI, under the service again`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        var livePath: String? = null
        val f = finisher(modelPaths = AiModelPaths { livePath })
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(60_000)
        assertEquals(listOf("A Transcript", "A Lines"), script.events)

        // The download is checked: the model is on the phone, and the plan has it.
        livePath = LIVE_PATH
        coordinator.snapshot.value = modelIn
        runCurrent()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(60_000L, script.times["A Notes"])
        val resumed = script.requests.last()
        assertEquals(FinishStep.Notes, resumed.resumeFrom)
        assertTrue(resumed.aiAvailable)
        assertFalse(resumed.modelPending)
        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
        assertEquals(listOf("start finish:A", "stop finish:A", "start finish:A", "stop finish:A"), processing.log)
        assertTrue("Finishing goes on with 1 that waited for the AI model" in log.lines)
    }

    @Test
    fun `a model that comes onto the phone without a word from the first run is found at the next look`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        var livePath: String? = null
        val f = finisher(modelPaths = AiModelPaths { livePath })
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(10_000)
        // A look finds nothing yet.
        assertTrue(modelRechecks.tryEmit(Unit))
        runCurrent()
        assertEquals(listOf("A Transcript", "A Lines"), script.events)

        // Downloaded in Settings, or copied in by hand: the first run's plan still has it on its way.
        livePath = LIVE_PATH
        advanceTimeBy(20_000)
        assertTrue(modelRechecks.tryEmit(Unit))
        runCurrent()

        assertEquals(stepsOf("A"), script.events)
        assertEquals(30_000L, script.times["A Notes"])
        assertNull(jobs.row("A"))
        assertEquals(30_000L, RecordingFinisher.MODEL_RECHECK_MS)
    }

    @Test
    fun `AI notes switched off while a job waits for the model let it finish without the AI`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        val f = finisher(modelPaths = AiModelPaths { null })
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(5_000)

        settings.settings.value = AppSettings(llmEnabled = false)
        runCurrent()

        val last = script.requests.last()
        assertEquals(FinishStep.Notes, last.resumeFrom)
        assertFalse(last.aiAvailable)
        assertFalse(last.modelPending)
        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
    }

    @Test
    fun `no job waits with AI notes off, nor without an AI model planned`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        settings.settings.value = AppSettings(llmEnabled = false)
        val f = finisher(modelPaths = AiModelPaths { null })
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        // AI notes back on, but the first run plans no model (an install that had one, say).
        settings.settings.value = AppSettings()
        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.DONE)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(stepsOf("A") + stepsOf("B"), script.events)
        assertTrue(script.requests.none { it.modelPending })
        assertNull(jobs.row("A"))
        assertNull(jobs.row("B"))
        assertTrue(log.lines.none { it.contains("waits for the AI model") })
    }

    @Test
    fun `a recording saved while another waits runs up to its own first AI step, says at once that it is too short, and both go on in order`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        var livePath: String? = null
        val f = finisher(modelPaths = AiModelPaths { livePath })
        script.tooLittle["B"] = FinishRecordingUseCase.TooLittleText(9, 20, keepsNotes = false, translation = false)
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(1)
        f.handOff("B", recognizerFailed = false)
        runCurrent()

        assertEquals(listOf("A Transcript", "A Lines", "B Transcript", "B Lines"), script.events)
        assertEquals(
            listOf(
                FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL),
                FinishingStatus("B", FinishingStatus.Phase.WAITS_FOR_MODEL, noNotes = true)
            ),
            f.statuses.value
        )
        // B has too little for notes: it waits for the model for its name and its to-dos.
        assertEquals(FinishStep.Name, jobs.row("B")?.nextStep)

        advanceTimeBy(10_000)
        livePath = LIVE_PATH
        coordinator.snapshot.value = modelIn
        runCurrent()

        assertEquals(stepsOf("A").take(2) + stepsOf("B").take(2) + stepsOf("A").drop(2) + stepsOf("B").drop(3), script.events)
        assertTrue(f.statuses.value.isEmpty())
    }

    @Test
    fun `a job a dead process left waiting for the model waits again, after a look that runs no step`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        // Deferred as it waited, at its notes: the row as the dead process left it.
        jobs.seed(job("A", FinishingJobState.QUEUED, nextStep = FinishStep.Notes, waitsForRecording = true, queuedAt = 1))
        val f = finisher(modelPaths = AiModelPaths { null })

        f.resume(mayStartForeground = true)
        runCurrent()

        assertTrue(script.events.isEmpty())
        assertEquals(FinishStep.Notes, script.requests.single().resumeFrom)
        assertEquals(listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL)), f.statuses.value)
        assertEquals(0, jobs.row("A")!!.attempts)
        assertTrue(processing.holders.isEmpty())
        assertEquals(0, wake.scheduled)
    }

    @Test
    fun `a capture's end that lets the deferred jobs go on finds the waiting one still waiting, and runs the other up to the model`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        val f = finisher(modelPaths = AiModelPaths { null })
        f.handOff("A", recognizerFailed = false)
        runCurrent()
        // A Translate capture defers the next job before its first step.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING, translation = true)
        f.handOff("X", recognizerFailed = false)
        runCurrent()
        assertEquals(
            listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL), FinishingStatus("X", FinishingStatus.Phase.WAITS_FOR_RECORDING)),
            f.statuses.value
        )

        recorder.value = null
        runCurrent()

        assertEquals(listOf("A Transcript", "A Lines", "X Transcript", "X Lines"), script.events)
        assertEquals(
            listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL), FinishingStatus("X", FinishingStatus.Phase.WAITS_FOR_MODEL)),
            f.statuses.value
        )
        assertTrue(processing.holders.isEmpty())
        assertEquals(0, wake.scheduled)
    }

    @Test
    fun `a job that waits for the model and is cancelled goes, and the queue no longer looks for the model for it`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        val f = finisher(modelPaths = AiModelPaths { null })
        f.handOff("A", recognizerFailed = false)
        runCurrent()

        f.cancel("A")
        runCurrent()

        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
    }

    // ─── Waiting for the reader (0.8.0, R5) ──────────────────────────────────────

    /** A recording with lines only the reader reads, whose reader's files aren't on the phone yet. */
    private fun waitsForReader(sessionId: String) {
        script.reads += sessionId
        script.readerMissing += sessionId
    }

    @Test
    fun `a Malayalam recording saved while the reader is on its way runs up to its Reading step and waits there, holding nothing`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        val f = finisher()
        waitsForReader("A")

        f.handOff("A", recognizerFailed = false)
        runCurrent()

        assertEquals(listOf("A Transcript", "A Lines"), script.events)
        assertTrue(script.requests.single().readerPending)
        // The screens say it waits for the AI model: the reader has no words of its own.
        assertEquals(listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_MODEL)), f.statuses.value)
        assertFalse(f.busy.value)
        val row = jobs.row("A")!!
        assertEquals(FinishStep.Reading, row.nextStep)
        assertEquals(0, row.attempts)
        assertTrue(row.waitsForRecording)
        assertTrue(processing.holders.isEmpty())
        assertNull(gate.holder.value)
        assertTrue(log.lines.toString(), "Finishing of A waits for the reader at Reading" in log.lines)
    }

    @Test
    fun `a job that waits for the reader is not let go while the AI model is on the phone, only once the reader is in`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        val f = finisher()
        waitsForReader("A")
        f.handOff("A", recognizerFailed = false)
        runCurrent()
        val runs = script.requests.size

        // The looks every 30 s find the 1B on the phone and the reader still on its way: the job stays as it is.
        repeat(3) {
            advanceTimeBy(30_000)
            assertTrue(modelRechecks.tryEmit(Unit))
            runCurrent()
        }
        assertEquals(runs, script.requests.size)
        assertTrue(processing.holders.isEmpty())
        assertEquals(listOf("start finish:A", "stop finish:A"), processing.log)

        // The reader's download is checked.
        script.readerMissing -= "A"
        readers.status = ReaderStatus.PRESENT
        assertTrue(readers.changed.tryEmit(Unit))
        runCurrent()
        advanceUntilIdle()

        assertEquals(readSteps("A"), script.events)
        assertEquals(FinishStep.Reading, script.requests.last().resumeFrom)
        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
        assertTrue(log.lines.toString(), "Finishing goes on with 1 that waited for the reader" in log.lines)
    }

    @Test
    fun `each job goes on once its own wait is over, so the AI model's coming lets its own job go and not the reader's`() = runTest {
        coordinator.snapshot.value = modelOnItsWay
        readers.status = ReaderStatus.ON_ITS_WAY
        var livePath: String? = null
        val f = finisher(modelPaths = AiModelPaths { livePath })
        waitsForReader("M")
        f.handOff("E", recognizerFailed = false)
        advanceTimeBy(1)
        f.handOff("M", recognizerFailed = false)
        runCurrent()
        assertEquals(
            listOf(FinishingStatus("E", FinishingStatus.Phase.WAITS_FOR_MODEL), FinishingStatus("M", FinishingStatus.Phase.WAITS_FOR_MODEL)),
            f.statuses.value
        )

        livePath = LIVE_PATH
        coordinator.snapshot.value = modelIn
        runCurrent()
        advanceUntilIdle()

        // E went on from its notes; M stays parked at its Reading step, its row deferred, run no more.
        assertEquals(listOf("E Transcript", "E Lines", "M Transcript", "M Lines") + stepsOf("E").drop(2), script.events)
        assertNull(jobs.row("E"))
        assertTrue(jobs.row("M")!!.waitsForRecording)
        assertEquals(1, script.requests.count { it.sessionId == "M" })
        assertEquals(listOf(FinishingStatus("M", FinishingStatus.Phase.WAITS_FOR_MODEL)), f.statuses.value)
        assertTrue(log.lines.toString(), "Finishing goes on with 1 that waited for the AI model" in log.lines)
    }

    @Test
    fun `a reader that isn't coming lets the waiting job go, and its run finishes without its AI steps`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        val f = finisher()
        waitsForReader("A")
        f.handOff("A", recognizerFailed = false)
        runCurrent()

        // The first run drops the reader's part after two failed downloads.
        readers.status = ReaderStatus.NOT_COMING
        assertTrue(readers.changed.tryEmit(Unit))
        runCurrent()
        advanceUntilIdle()

        val last = script.requests.last()
        assertFalse(last.readerPending)
        assertEquals(FinishStep.Reading, last.resumeFrom)
        assertNull(jobs.row("A"))
        assertTrue(f.statuses.value.isEmpty())
        assertTrue(log.lines.toString(), "Finishing A without its AI steps: the reader isn't on the phone and isn't coming" in log.lines)
        assertTrue(notesReady.isEmpty())
    }

    @Test
    fun `AI notes switched off let a job that waits for the reader finish without the AI`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        val f = finisher()
        waitsForReader("A")
        f.handOff("A", recognizerFailed = false)
        runCurrent()

        settings.settings.value = AppSettings(llmEnabled = false)
        runCurrent()
        advanceUntilIdle()

        val last = script.requests.last()
        assertFalse(last.aiAvailable)
        assertFalse(last.readerPending)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `no run waits for the reader with it on the phone, not coming, or with AI notes off`() = runTest {
        val f = finisher()
        for ((status, ai) in listOf(ReaderStatus.PRESENT to true, ReaderStatus.NOT_COMING to true, ReaderStatus.ON_ITS_WAY to false)) {
            readers.status = status
            settings.settings.value = AppSettings(llmEnabled = ai)
            f.handOff("${status.name}-$ai", recognizerFailed = false)
            advanceUntilIdle()
        }

        assertTrue(script.requests.none { it.readerPending })
        assertTrue(log.lines.none { it.contains("waits for the reader") })
    }

    @Test
    fun `a recorder that starts while the reader reads stops it at once, and the job waits for that capture to end`() = runTest {
        readers.status = ReaderStatus.PRESENT
        val f = finisher()
        script.reads += "A"
        script.readingHolds["A"] = CompletableDeferred()
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf("A Transcript", "A Lines", "A Reading"), script.events)

        // The quick key starts another recording: its recorder leaves IDLE mid-reading.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        runCurrent()

        assertEquals(listOf("A"), script.stoppedReadings)
        assertEquals(listOf(FinishingStatus("A", FinishingStatus.Phase.WAITS_FOR_RECORDING)), f.statuses.value)
        assertTrue(log.lines.toString(), "Finishing deferred A at Reading: a capture is live, which the reader never reads beside" in log.lines)
        assertEquals(FinishStep.Reading, jobs.row("A")?.nextStep)
        assertEquals(0, jobs.row("A")?.attempts)

        // That recorder goes away; the reading goes on from where it was.
        script.readingHolds.remove("A")
        recorder.value = null
        advanceUntilIdle()

        assertEquals(FinishStep.Reading, script.requests.last().resumeFrom)
        assertEquals(listOf("A Transcript", "A Lines", "A Reading") + readSteps("A").drop(2), script.events)
        assertNull(jobs.row("A"))
    }

    @Test
    fun `the capture-open path stops the reader at once, before any recorder shows the capture`() = runTest {
        readers.status = ReaderStatus.PRESENT
        val f = finisher()
        script.reads += "A"
        val hold = CompletableDeferred<Unit>()
        script.readingHolds["A"] = hold
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()

        f.captureStarted("B")
        runCurrent()

        assertEquals(listOf("A"), script.stoppedReadings)
        assertEquals(FinishingJobState.CAPTURING, jobs.row("B")?.state)
        // No recorder shows that capture: the job is taken again at once, and its reading goes on.
        hold.complete(Unit)
        advanceUntilIdle()
        assertNull(jobs.row("A"))
        assertEquals(listOf(null, FinishStep.Reading), script.requests.filter { it.sessionId == "A" }.map { it.resumeFrom })
    }

    @Test
    fun `a recorder that leaves IDLE just before the reading is handed out stops it as it is handed out`() = runTest {
        readers.status = ReaderStatus.PRESENT
        val f = finisher()
        script.reads += "A"
        script.readingHolds["A"] = CompletableDeferred()
        // After the step looked for a live capture, before the reading is handed out: the watch on
        // the recorder sees the recorder leave IDLE while there is no reading yet to stop.
        script.beforeReadingStart["A"] = {
            recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
            yield()
        }
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()

        assertEquals(listOf("A"), script.stoppedReadings)
        assertTrue(log.lines.toString(), "Finishing deferred A at Reading: a capture is live, which the reader never reads beside" in log.lines)
        assertEquals(true, jobs.row("A")?.waitsForRecording)
    }

    @Test
    fun `a reader held off by the speech model runs again after the pause, and the third time waits for the next process`() = runTest {
        readers.status = ReaderStatus.PRESENT
        val f = finisher()
        script.reads += "A"
        script.heldOff["A"] = 3
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()

        assertTrue(
            log.lines.toString(),
            "Finishing of A stopped at Reading: the reader can't load beside the speech model now; it runs again in 60 s" in log.lines
        )
        assertEquals(0, jobs.row("A")?.attempts)
        assertNull(gate.holder.value)

        advanceTimeBy(RecordingFinisher.MEMORY_PAUSE_MS)
        runCurrent()
        advanceTimeBy(RecordingFinisher.MEMORY_PAUSE_MS)
        runCurrent()

        assertTrue(
            log.lines.toString(),
            "W Finishing of A stopped at Reading 3 times, the reader held off by the speech model; its job waits for the next start" in log.lines
        )
        assertTrue(processing.holders.isEmpty())
        assertTrue(script.events.none { it == "A Reading" })
    }

    @Test
    fun `the reading's counts go to the field log, with the floor it ran with and no text`() = runTest {
        readers.status = ReaderStatus.PRESENT
        val f = finisher()
        script.reads += "A"
        script.readingCounts["A"] = ReadingStep.Counts(lines = 9, kept = 5, fragments = 1, floor = 1, band = 1, repeats = 0, blank = 0, notRead = 1, notStored = 1, ms = 1_200)

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue(
            log.lines.toString(),
            "Finishing A: read 9 lines in 1200 ms, 5 kept, 4 empty (1 fragments, 1 under the floor of -1.5, 1 outside the band, " +
                "0 repeating, 0 blank, 1 not read), 1 not stored" in log.lines
        )
        assertEquals(readSteps("A"), script.events)
    }

    @Test
    fun `each hand-off tells the first run a recording is saved, and so does the finishing of a cut-off capture that had words`() = runTest {
        jobs.goneSessions += "G"
        jobs.seed(job("C", FinishingJobState.CAPTURING, owner = "gone", createdAt = 1))
        jobs.seed(job("S", FinishingJobState.CAPTURING, owner = "gone", createdAt = 2))
        lengths.lines["C"] = listOf("Call the bank about the card.")
        val f = finisher()
        script.discarded += "S"

        f.handOff("G", recognizerFailed = false)
        assertTrue(coordinator.calls.isEmpty())
        f.handOff("A", recognizerFailed = false)
        assertEquals(listOf("recordingSaved"), coordinator.calls)
        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        // C was finished, S deleted as nothing was said in it.
        assertEquals(listOf("recordingSaved", "recordingSaved"), coordinator.calls)
    }

    @Test
    fun `a cut-off capture kept for its audio, without a word in its lines, doesn't end the first run`() = runTest {
        // Kept, not deleted: its audio may hold words the lines lack (the app died as it began).
        jobs.seed(job("C", FinishingJobState.CAPTURING, owner = "gone", createdAt = 1))
        lengths.lines["C"] = listOf(".", "")
        val f = finisher()

        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(stepsOf("C"), script.events)
        assertTrue(coordinator.calls.isEmpty())
    }

    @Test
    fun `a finished recording with notes is said to have them ready, one without notes is not`() = runTest {
        lengths.notes["A"] = listOf(
            LlmInsight(id = "n1", sessionId = "A", title = "Plan", content = "The plan.", timestamp = 1L, sourceSegmentIds = emptyList())
        )
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(listOf("A"), notesReady)
    }

    // ─── What screens see ────────────────────────────────────────────────────────

    @Test
    fun `statuses follow the queue, with the step and the better transcript of the job that runs`() = runTest {
        val f = finisher()
        val held = CompletableDeferred<Unit>()
        var skipped = false
        script.transcriptRuns["A"] = BetterTranscriptRun(SpeechModelId.COHERE_TRANSCRIBE) { skipped = true }
        script.holds["A" to FinishStep.Transcript] = held
        // Deferred by a process that is gone, which no resume has taken up, so it holds nothing.
        // It waits for the recording that is live now.
        jobs.seed(job("C", FinishingJobState.QUEUED, waitsForRecording = true, queuedAt = 10, createdAt = 10))
        recorder.value = ActiveRecorder.Snapshot("R", ActiveRecorder.Activity.RECORDING)
        // A capture: no status of its own.
        jobs.seed(job("D", FinishingJobState.CAPTURING, owner = OWNER, createdAt = 11))
        assertFalse(f.busy.value)

        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(1)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(
            listOf(
                FinishingStatus("A", FinishingStatus.Phase.RUNNING, FinishStep.Transcript, transcript = TranscriptProgress(SpeechModelId.COHERE_TRANSCRIBE)),
                FinishingStatus("B", FinishingStatus.Phase.WAITING, position = 1),
                FinishingStatus("C", FinishingStatus.Phase.WAITS_FOR_RECORDING)
            ),
            f.statuses.value
        )
        assertTrue(f.busy.value)
        assertTrue(3 to FinishStep.Transcript in processing.shown)

        f.skipBetterTranscript("A")
        runCurrent()
        assertTrue(skipped)
        assertTrue(f.statuses.value.first().transcript!!.skipping)

        held.complete(Unit)
        recorder.value = null
        advanceUntilIdle()

        // No capture is live, so C may run, but with the service gone it waits for a keep-alive.
        assertEquals(listOf(FinishingStatus("C", FinishingStatus.Phase.WAITING, position = 1)), f.statuses.value)
        assertTrue(f.busy.value)

        jobs.drop("C")
        advanceUntilIdle()

        assertTrue(f.statuses.value.isEmpty())
        assertFalse(f.busy.value)
        assertEquals(0 to null, processing.shown.last())
    }

    @Test
    fun `the field log says when a job is queued, started, at each step, and done`() = runTest {
        val f = finisher()

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue("Finishing started A at the start, attempt 1" in log.lines)
        assertTrue("Finishing: Notes of A after 3000 ms" in log.lines)
        assertTrue(log.lines.any { it.startsWith("Finishing done A in ") && it.endsWith(": Finished") })
    }

    @Test
    fun `too little text for notes is in the status at once, while the name waits its turn, and in the field log`() = runTest {
        val f = finisher()
        script.tooLittle["A"] = FinishRecordingUseCase.TooLittleText(17, 20, keepsNotes = false, translation = false)

        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(1_000)

        // Right after Stop: the Notes step needs no model to know, and the name, the first step
        // that may load one, still waits its 3 s.
        assertEquals(0L, script.times["A too little"])
        val status = f.statuses.value.single()
        assertTrue(status.noNotes)
        assertEquals(FinishStep.Name, status.step)
        assertEquals(listOf("A Transcript", "A Lines"), script.events)
        assertTrue("Finishing A: too little text for notes (17 words, 20 needed), no model chosen for it" in log.lines)

        advanceUntilIdle()

        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["A Name"])
        assertTrue(f.statuses.value.isEmpty())
    }

    @Test
    fun `a short tail after the notes a recording has, or a short translation, says nothing of no notes`() = runTest {
        val f = finisher()
        script.tooLittle["A"] = FinishRecordingUseCase.TooLittleText(5, 20, keepsNotes = true, translation = false)
        script.tooLittle["B"] = FinishRecordingUseCase.TooLittleText(6, 20, keepsNotes = false, translation = true)
        val heldA = CompletableDeferred<Unit>()
        val heldB = CompletableDeferred<Unit>()
        script.holds["A" to FinishStep.Name] = heldA
        script.holds["B" to FinishStep.Name] = heldB
        f.handOff("A", recognizerFailed = false)
        advanceTimeBy(1)
        f.handOff("B", recognizerFailed = false)
        advanceUntilIdle()

        assertFalse(f.statuses.value.first { it.sessionId == "A" }.noNotes)
        heldA.complete(Unit)
        advanceUntilIdle()
        assertFalse(f.statuses.value.single { it.sessionId == "B" }.noNotes)
        heldB.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            "Finishing A: too little text for a final note (5 words, 20 needed), no model chosen for it; the notes it has stay" in log.lines
        )
        assertTrue("Finishing B: too little text for a translation (6 words, 20 needed), no model chosen for it" in log.lines)
    }

    @Test
    fun `a run in which no AI step had anything to do says so in the field log`() = runTest {
        val f = finisher()
        script.noModelNeeded = true

        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertTrue("Finishing A: no AI step had anything to do, no model loaded" in log.lines)
    }

    // ─── Made again over the readings (0.8.0, R9: the backfill) ─────────────────

    @Test
    fun `a redo queues a finished recording from its Reading step, holds the service, and runs it made again without the notes' cue`() = runTest {
        lengths.notes["M"] = listOf(
            LlmInsight(id = "n1", sessionId = "M", title = "Papers", content = "The papers go to the office.", timestamp = 1L, sourceSegmentIds = emptyList())
        )
        val f = finisher()
        script.reads += "M"

        assertTrue(f.redo("M"))

        val queued = jobs.row("M")!!
        assertTrue(queued.redo)
        assertEquals(FinishingJobState.QUEUED, queued.state)
        assertEquals(FinishStep.Reading, queued.nextStep)
        assertEquals(setOf("finish:M"), processing.holders)
        assertTrue("Finishing queued M again from its Reading step, to be made again over its readings" in log.lines)

        advanceUntilIdle()

        assertEquals(readSteps("M").drop(2), script.events)
        val request = script.requests.single()
        assertTrue(request.redo)
        assertEquals(FinishStep.Reading, request.resumeFrom)
        assertTrue("Finishing started M at Reading, attempt 1, made again over its readings" in log.lines)
        assertNull(jobs.row("M"))
        assertTrue(processing.holders.isEmpty())
        // The owner waits for the notes of the recordings he makes, not for these.
        assertTrue(notesReady.isEmpty())
        // As a hand-off's job, it loads nothing in the grace a recording started right then gets.
        assertEquals(RecordingFinisher.HAND_OFF_GRACE_MS, script.times["M Reading"])
    }

    @Test
    fun `a redo of a recording that has a job, or is gone, queues nothing`() = runTest {
        val f = finisher()
        f.handOff("A", recognizerFailed = false)
        jobs.goneSessions += "G"

        assertFalse(f.redo("A"))
        assertFalse(f.redo("G"))

        assertFalse(jobs.row("A")!!.redo)
        assertNull(jobs.row("G"))
        assertEquals(setOf("finish:A"), processing.holders)
    }

    @Test
    fun `a write of the redo that fails queues nothing and holds nothing`() = runTest {
        val f = finisher()
        jobs.failing["queueRedo"] = 1

        assertFalse(f.redo("M"))

        assertNull(jobs.row("M"))
        assertTrue(processing.holders.isEmpty())
        assertTrue(log.lines.any { it.startsWith("W Finishing could not queue M again") })
    }

    @Test
    fun `a finishing that ends with lines never read counts against the backfill, all at once when its step was skipped, not for a missing reader`() = runTest {
        val f = finisher()
        // The reader fails on a hand-off's recording and on one made again.
        script.reads += listOf("A", "M")
        script.readerFails += listOf("A", "M")
        // A redo whose Reading step began twice: skipped, its lines never read.
        script.reads += "S"
        script.neverRead += "S"
        jobs.seed(job("S", FinishingJobState.QUEUED, nextStep = FinishStep.Reading, attempts = 2, queuedAt = 0L).copy(redo = true))
        // No reader on the phone, and none coming: no failure of the recording's.
        script.reads += "O"
        script.readerMissing += "O"
        // A job an older version left at its Notes step, its lines never read: past the Reading step
        // with no crash in it, one failure, so the backfill tries it again.
        script.reads += "P"
        script.neverRead += "P"
        jobs.seed(job("P", FinishingJobState.QUEUED, nextStep = FinishStep.Notes, queuedAt = 1L))

        f.handOff("A", recognizerFailed = false)
        f.redo("M")
        f.redo("O")
        f.resume(mayStartForeground = true)
        advanceUntilIdle()

        assertEquals(mapOf("A" to 1, "M" to 1, "P" to 1, "S" to ReadingBackfill.MAX_FAILURES), lengths.backfillFailures)
        assertTrue("Finishing skipped Reading of S: began twice" in log.lines)
        assertTrue("Finishing S without its AI steps: its Reading step was skipped, and lines were never read" in log.lines)
        assertNull(jobs.row("O"))
    }

    @Test
    fun `a run that ends without its AI steps for want of its readings leaves its recording due to be made again, and one made again that finishes leaves it done`() = runTest {
        val f = finisher()
        // A hand-off's recording without the reader, and one made again whose notes fail.
        script.reads += listOf("A", "M", "F")
        script.readerMissing += "A"
        script.notesFail += "F"

        f.handOff("A", recognizerFailed = false)
        f.redo("M")
        f.redo("F")
        advanceUntilIdle()

        assertEquals(mapOf("A" to true, "M" to false), lengths.redoDue)
        // The one whose notes failed is tried again: one failure, and still due.
        assertEquals(mapOf("F" to 1), lengths.backfillFailures)
        assertNull(jobs.row("F"))
    }

    @Test
    fun `a recording made again comes after a hand-off queued after it`() = runTest {
        val f = finisher()
        script.reads += listOf("M", "A")

        f.redo("M")
        f.handOff("A", recognizerFailed = false)
        advanceUntilIdle()

        assertEquals(readSteps("A") + readSteps("M").drop(2), script.events)
        assertNull(jobs.row("M"))
        assertNull(jobs.row("A"))
    }

    @Test
    fun `a recording made again begins no step beside a live capture, and goes on once it is over`() = runTest {
        val f = finisher()
        script.reads += "M"
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.RECORDING)

        f.redo("M")
        advanceUntilIdle()

        assertTrue(script.events.isEmpty())
        assertTrue(log.lines.toString(), "Finishing deferred M at Reading: a capture is live, which a recording made again never runs beside" in log.lines)
        assertEquals(true, jobs.row("M")?.waitsForRecording)
        assertEquals(0, jobs.row("M")?.attempts)

        recorder.value = null
        advanceUntilIdle()

        assertEquals(readSteps("M").drop(2), script.events)
        assertNull(jobs.row("M"))
    }

    @Test
    fun `a capture that starts while a recording made again runs an AI step stops that step at once, which runs again once the capture is over`() = runTest {
        val f = finisher()
        script.reads += "M"
        val notes = CompletableDeferred<Unit>()
        script.holds["M" to FinishStep.Notes] = notes
        f.redo("M")
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf("M Reading", "M Notes"), script.events)

        // The quick key starts a recording while the model writes M's notes.
        recorder.value = ActiveRecorder.Snapshot("B", ActiveRecorder.Activity.STARTING)
        runCurrent()

        assertTrue(log.lines.toString(), "Finishing deferred M at Notes: a capture is live, which a recording made again never runs beside" in log.lines)
        assertEquals(FinishStep.Notes, jobs.row("M")?.nextStep)
        assertEquals(0, jobs.row("M")?.attempts)
        assertEquals(true, jobs.row("M")?.waitsForRecording)
        // The step's work was cut short, the model and the gate let go meanwhile.
        assertNull(gate.holder.value)

        notes.complete(Unit)
        recorder.value = null
        advanceUntilIdle()

        assertEquals(FinishStep.Notes, script.requests.last().resumeFrom)
        assertEquals(listOf("M Reading", "M Notes", "M Notes", "M Name", "M Todos", "M Map"), script.events)
        assertNull(jobs.row("M"))
    }

    @Test
    fun `the capture-open path stops a recording made again at once, before any recorder shows the capture`() = runTest {
        val f = finisher()
        script.reads += "M"
        val todos = CompletableDeferred<Unit>()
        script.holds["M" to FinishStep.Todos] = todos
        f.redo("M")
        advanceTimeBy(RecordingFinisher.HAND_OFF_GRACE_MS + 1)
        runCurrent()
        assertEquals("M Todos", script.events.last())

        f.captureStarted("B")
        runCurrent()

        assertTrue(log.lines.toString(), "Finishing deferred M at Todos: a capture is live, which a recording made again never runs beside" in log.lines)
        assertEquals(FinishingJobState.CAPTURING, jobs.row("B")?.state)
        // No recorder shows that capture: the job is taken again at once, at the step it was stopped in.
        todos.complete(Unit)
        advanceUntilIdle()
        assertNull(jobs.row("M"))
        assertEquals(listOf(FinishStep.Reading, FinishStep.Todos), script.requests.filter { it.sessionId == "M" }.map { it.resumeFrom })
    }

    private fun job(
        sessionId: String,
        state: FinishingJobState,
        owner: String = "",
        nextStep: FinishStep? = null,
        attempts: Int = 0,
        waitsForRecording: Boolean = false,
        queuedAt: Long? = null,
        createdAt: Long = 0L
    ) = FinishingJob(
        sessionId = sessionId,
        state = state,
        nextStep = nextStep,
        attempts = attempts,
        owner = owner,
        recognizerFailed = false,
        recovered = false,
        waitsForRecording = waitsForRecording,
        queuedAt = queuedAt,
        createdAt = createdAt
    )

    private companion object {
        const val OWNER = "this-process"
        const val LIVE_PATH = "live.gguf"
        const val MB = 1_048_576L
        const val AVAILABLE_MB = 3_400L
    }
}
