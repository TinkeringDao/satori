package com.satori.ui.debug

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.LinesWithReadings
import com.satori.domain.repository.AiSampling
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.LoadedReader
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.Sampling
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.finish.FinishingStepRerun
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * The debug rerun of a finishing step (0.7.2 A.6) keeps the rules of the debug transcribe and the
 * memtest, samples only its own runs at the temperature asked for, stores only when asked, and lets
 * go of the model, the gate and the service on every end.
 */
class DebugRerunTest {

    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val gate = AiWorkGate()
    private val finishingBusy = MutableStateFlow(false)
    private val service = HeldService()
    private val engine = FakeEngine()
    private val lines = mutableListOf<String>()
    private var now = 0L

    private fun rerun() = DebugRerun(recorder, gate, finishingBusy, service, engine, clock = { now += 250; now }) { lines += it }

    private fun args(vararg extras: Pair<String, String?>) = DebugRerun.Args.of(mapOf("session" to "s1", "step" to "notes") + extras.toMap())

    @Test
    fun `the arguments are checked, each with its line's reason`() {
        val wrong = mapOf(
            listOf("step" to "minutes") to "step must be notes, name, todos or reading",
            listOf("times" to "0") to "times must be 1-10",
            listOf("times" to "11") to "times must be 1-10",
            listOf("temp" to "-0.1") to "temp must be 0-2",
            listOf("temp" to "2.5") to "temp must be 0-2",
            listOf("temp" to "NaN") to "temp must be 0-2",
            listOf("temp" to "warm") to "temp must be 0-2",
            listOf("from" to "audio") to "from must be notes or transcript",
            listOf("store" to "yes") to "store must be true or false",
            listOf("session" to "../s1") to "no such recording",
            listOf("reader" to "maybe") to "reader must be on or off",
            listOf("model" to "4b") to "model must be 1b or e2b",
            listOf("step" to "reading", "temp" to "0.5") to "step reading takes no temp, reader or model",
            listOf("step" to "reading", "reader" to "off") to "step reading takes no temp, reader or model",
            listOf("step" to "reading", "model" to "1b") to "step reading takes no temp, reader or model",
            // What the 1B makes of the lines as they were said is for comparing, never for the recording.
            listOf("reader" to "off", "store" to "true") to "store needs the reader on",
            listOf("step" to "reading", "window" to "3") to "window must be 1 or 2",
            listOf("step" to "reading", "window" to "two") to "window must be 1 or 2",
            // Only a reading reads lines together; the AI steps read them as the finishing does.
            listOf("window" to "2") to "window is for step reading"
        )
        for ((extras, why) in wrong) {
            assertEquals("$extras", DebugRerun.Args.Wrong(extras.toMap()["session"] ?: "s1", why), args(*extras.toTypedArray()))
        }
        assertEquals(
            DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.NAME, times = 5, temperature = 0f, from = FinishingStepRerun.From.TRANSCRIPT, store = true),
            args("step" to "name", "times" to "5", "temp" to "0", "from" to "transcript", "store" to "true")
        )
        assertEquals(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.NOTES), args())
        assertEquals(
            DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.TODOS, reader = false, model = DebugRerun.Args.Model.E2B),
            args("step" to "todos", "reader" to "off", "model" to "e2b")
        )
        assertEquals(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.READING, times = 2), args("step" to "reading", "times" to "2", "reader" to "on"))
        assertEquals(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.READING, window = 2), args("step" to "reading", "window" to "2"))
        assertEquals(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.NOTES), args("window" to "1"))
        // A float extra comes as its string.
        assertEquals("0.7", temperatureExtra(null, hasExtra = true) { 0.7f })
        assertNull(temperatureExtra(null, hasExtra = false) { 0.7f })
    }

    @Test
    fun `it runs the step the times asked, holding the AI and its service, and lets go of all after`() = runBlocking {
        val outcome = rerun().run(args("times" to "3"))

        assertTrue(outcome is DebugRerun.Outcome.Done)
        assertEquals(listOf("load", "run 1", "run 2", "run 3 last", "release"), engine.calls)
        assertEquals(listOf(AiWorkGate.DEBUG_RERUN), engine.holders.distinct())
        assertEquals(listOf("start rerun:s1", "stop rerun:s1"), service.log)
        assertNull(gate.holder.value)
        assertEquals(
            listOf(
                "Debug rerun notes of s1 on Gemma 4 E2B, 3 runs at temp 0.30 top-k 40 top-p 0.95 min-p 0.05 (the default)",
                "Debug rerun notes of s1 1/3: a final note, 1 point, 1 titled, 0 with action items in 250 ms",
                "Debug rerun notes of s1 2/3: a final note, 1 point, 1 titled, 0 with action items in 250 ms",
                "Debug rerun notes of s1 3/3: a final note, 1 point, 1 titled, 0 with action items in 250 ms",
                "Debug rerun notes of s1 done, stored nothing"
            ),
            lines
        )
    }

    @Test
    fun `a temperature samples the runs' own calls only, and none is left after`() = runBlocking {
        rerun().run(args("times" to "2", "temp" to "0"))
        rerun().run(args())

        assertEquals(listOf(Sampling.GREEDY, Sampling.GREEDY, null), engine.samplings)
        assertNull(currentCoroutineContext()[AiSampling])
        assertTrue(lines.first(), lines.first().endsWith("2 runs at temp 0.00 top-k 40 top-p 0.95 min-p 0.05 (an override)"))
    }

    @Test
    fun `asked to store, the last run is stored, a name's or the notes', and the to-dos' last run stores itself`() = runBlocking {
        rerun().run(args("times" to "2", "store" to "true"))
        assertEquals(listOf("load", "run 1", "run 2 last", "store run 2", "release"), engine.calls)
        assertEquals("Debug rerun notes of s1 done, stored the last run", lines.last())

        engine.calls.clear()
        rerun().run(args("step" to "todos", "store" to "true"))
        assertEquals(listOf("load", "run 1 last", "release"), engine.calls)
        assertEquals("Debug rerun todos of s1 done, stored the last run", lines.last())
    }

    @Test
    fun `it doesn't start beside a recorder, while the AI is held or a recording waits, nor for a recording that isn't there`() = runBlocking {
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.RECORDING)
        assertEquals(DebugRerun.Outcome.Refused("a recorder is busy"), rerun().run(args()))
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.IDLE)
        finishingBusy.value = true
        assertEquals(DebugRerun.Outcome.Refused("the AI is busy"), rerun().run(args()))
        finishingBusy.value = false
        gate.tryEnter(AiWorkGate.MAP)
        assertEquals(DebugRerun.Outcome.Refused("the AI is busy"), rerun().run(args()))
        gate.exit(AiWorkGate.MAP)
        engine.exists = false
        assertEquals(DebugRerun.Outcome.Refused("no such recording"), rerun().run(args()))
        assertEquals(DebugRerun.Outcome.Refused("times must be 1-10"), rerun().run(args("times" to "20")))

        assertTrue(engine.calls.isEmpty())
        assertTrue(service.log.isEmpty())
        assertNull(gate.holder.value)
        assertEquals(
            listOf(
                "Debug rerun of s1 refused: a recorder is busy",
                "Debug rerun of s1 refused: the AI is busy",
                "Debug rerun of s1 refused: the AI is busy",
                "Debug rerun of s1 refused: no such recording",
                "Debug rerun of s1 refused: times must be 1-10"
            ),
            lines
        )
    }

    @Test
    fun `a recording that starts meanwhile stops the runs, and the model, the AI and the service are let go`() = runBlocking {
        engine.blockAt = 2
        val run = async { rerun().run(args("times" to "3")) }
        engine.blocked.await()

        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)

        assertEquals(DebugRerun.Outcome.RecorderStarted, run.await())
        assertEquals(listOf("load", "run 1", "run 2", "release"), engine.calls)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
        assertEquals("Debug rerun of s1 stopped: a recording started", lines.last())
    }

    @Test
    fun `a run that fails ends the rerun with its line, and a model that doesn't load starts none, each letting go of all`() = runBlocking {
        engine.failAt = 2
        assertEquals(DebugRerun.Outcome.Failed(2, "IOException"), rerun().run(args("times" to "3")))
        assertEquals("Debug rerun notes of s1 2/3 failed: IOException", lines.last())
        assertEquals("release", engine.calls.last())

        engine.calls.clear()
        engine.loads = false
        assertEquals(DebugRerun.Outcome.NotLoaded("no live model on the phone"), rerun().run(args()))
        assertEquals("Debug rerun notes of s1 not started: no live model on the phone", lines.last())
        assertEquals(listOf("load", "release"), engine.calls)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a rerun cancelled from outside, as when the debug screen goes, lets go of all`() = runBlocking {
        engine.blockAt = 1
        val caller = launch { rerun().run(args("times" to "2")) }
        engine.blocked.await()

        caller.cancelAndJoin()

        assertEquals("release", engine.calls.last())
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
        assertFalse(lines.any { it.contains("done") })
    }

    @Test
    fun `a rerun says it has started once past its rules and holding the service, before its model loads, a refused one never`() = runBlocking {
        val started = mutableListOf<String>()
        rerun().run(args()) { started += "started holding ${service.holders} under ${gate.holder.value}, ${engine.calls.size} calls" }
        engine.exists = false
        rerun().run(args()) { started += "refused one started" }

        assertEquals(listOf("started holding [rerun:s1] under ${AiWorkGate.DEBUG_RERUN}, 0 calls"), started)
    }

    @Test
    fun `a reading runs on the reader alone, no AI model loads, and no reading is made first`() = runBlocking {
        val outcome = rerun().run(args("step" to "reading", "times" to "2"))

        assertTrue(outcome is DebugRerun.Outcome.Done)
        assertEquals(listOf("run 1", "run 2 last", "release"), engine.calls)
        assertTrue(engine.readingsAsked.isEmpty())
        assertEquals(
            listOf(
                "Debug rerun reading of s1 on the reader, 2 runs, nothing stored",
                "Debug rerun reading of s1 1/2: 2 lines read in 0 ms, 2 kept, 0 empty (0 fragments, 0 under the floor, 0 outside the band, 0 repeating, 0 blank, 0 not read) in 250 ms",
                "Debug rerun reading of s1 2/2: 2 lines read in 0 ms, 2 kept, 0 empty (0 fragments, 0 under the floor, 0 outside the band, 0 repeating, 0 blank, 0 not read) in 250 ms",
                "Debug rerun reading of s1 done, stored nothing"
            ),
            lines
        )
    }

    @Test
    fun `a reading in windows says so, and asks the engine for its windows`() = runBlocking {
        rerun().run(args("step" to "reading", "window" to "2"))

        assertEquals("Debug rerun reading of s1 on the reader, 1 run, 2 lines read together, nothing stored", lines.first())
        assertEquals(listOf(2), engine.windows)
    }

    @Test
    fun `the notes, the name and the to-dos make the readings first, before the model, and missing ones start none`() = runBlocking {
        rerun().run(args("model" to "1b"))
        assertEquals(listOf("s1"), engine.readingsAsked)
        assertEquals(listOf(DebugRerun.Args.Model.ONE_B), engine.models)

        engine.calls.clear()
        lines.clear()
        engine.missing = "no reader"
        assertEquals(DebugRerun.Outcome.NotLoaded("no reader"), rerun().run(args("step" to "todos")))
        assertEquals(listOf("release"), engine.calls)
        assertEquals(listOf("Debug rerun todos of s1 not started: its readings are missing (no reader)"), lines)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `with the reader off nothing is read first, and the log says the reader is off`() = runBlocking {
        engine.missing = "no reader"

        rerun().run(args("step" to "name", "reader" to "off"))

        assertTrue(engine.readingsAsked.isEmpty())
        assertEquals(listOf("load", "run 1 last", "release"), engine.calls)
        assertEquals(
            "Debug rerun name of s1 on Gemma 4 E2B, 1 run at temp 0.30 top-k 40 top-p 0.95 min-p 0.05 (the default), from its notes, reader off",
            lines.first()
        )
    }

    // ─── The app's engine ────────────────────────────────────────────────────────

    /** The app's engine over [llm], with Gemma 4 E2B at [e2b] (none: not on the phone) and the readings [readings] gives. */
    private fun appEngine(
        llm: CallsLlm,
        e2b: String? = "/models/e2b.gguf",
        readings: EnsureReadingsUseCase.Outcome = EnsureReadingsUseCase.Outcome.Ready
    ) = AppRerunEngine(
        llm,
        LoadFinishingModelUseCase(llm, E2bChoice(e2b)),
        unused(),
        unused(),
        livePath = { "/models/live.gguf" },
        readingsOf = { readings }
    ) { unused() }

    @Test
    fun `model 1b loads the live model alone, e2b Gemma 4 E2B or nothing, and none the finishing's choice`() = runBlocking {
        val live = CallsLlm()
        assertEquals("the live model", appEngine(live).load(DebugRerun.Args.Model.ONE_B))
        assertEquals(listOf("register /models/live.gguf", "reload"), live.calls)

        val e2b = CallsLlm()
        assertEquals("Gemma 4 E2B", appEngine(e2b).load(DebugRerun.Args.Model.E2B))
        assertEquals(listOf("register /models/live.gguf", "switch /models/e2b.gguf"), e2b.calls)

        val notThere = appEngine(CallsLlm(), e2b = null)
        assertNull(notThere.load(DebugRerun.Args.Model.E2B))
        assertEquals("Gemma 4 E2B is switched off or not on the phone", notThere.notLoaded)

        val short = appEngine(CallsLlm(switchFailure = NotEnoughMemoryException(availableBytes = 3_000L, neededBytes = 4_000L)))
        assertNull(short.load(DebugRerun.Args.Model.E2B))
        assertEquals("Gemma 4 E2B not loaded (NotEnoughMemoryException)", short.notLoaded)

        // The finishing's choice: E2B without its room gives the live model.
        val choice = CallsLlm(switchFailure = NotEnoughMemoryException(availableBytes = 3_000L, neededBytes = 4_000L))
        assertEquals("the live model, Gemma 4 E2B not loaded (NotEnoughMemoryException)", appEngine(choice).load(null))
        assertEquals(listOf("register /models/live.gguf", "switch /models/e2b.gguf", "reload"), choice.calls)
    }

    @Test
    fun `the app's engine says why the readings are missing, in words`() = runBlocking {
        assertNull(appEngine(CallsLlm()).readings("s1"))
        val missing = appEngine(CallsLlm(), readings = EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.NO_READER))
        assertEquals("no reader", missing.readings("s1"))
        val held = appEngine(CallsLlm(), readings = EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.HELD_OFF))
        assertEquals("held off", held.readings("s1"))
    }

    @Test
    fun `the app's engine reads the lines in the window asked, and keeps them under reading-lines or reading-windows`() = runBlocking {
        // Made up: "We will meet tomorrow morning." / "Bring the papers." / "Okay, see you then."
        val said = listOf("നമുക്ക് നാളെ രാവിലെ കാണാം.", "പേപ്പറുകൾ കൊണ്ടുവരണം.", "ശരി, പിന്നെ കാണാം.").mapIndexed { index, text ->
            TranscriptionSegment("l$index", "s1", text, timestamp = 1_000L * (index + 1), isComplete = true)
        }
        val recording = OneRecording(said)
        val reader = EnglishReader()
        val kept = mutableListOf<Pair<String, String>>()
        val answers = object : AnswerLog {
            override fun todoAnswer(sessionId: String, window: Int, answer: String) = Unit
            override fun answer(sessionId: String, call: String, answer: String) {
                kept += call to answer
            }
        }
        val llm = NoModel()
        val engine = AppRerunEngine(llm, LoadFinishingModelUseCase(llm, E2bChoice(null)), recording, answers, livePath = { null }, readingsOf = { EnsureReadingsUseCase.Outcome.Ready }) { log ->
            FinishingStepRerun(
                recording,
                NotesLanguageResolver(unused(), recording),
                GenerateFinalInsightUseCase(llm, unused(), unused()),
                GenerateBatchInsightUseCase(llm, unused(), unused()),
                GenerateSessionTitleUseCase(llm, unused()),
                ExtractSessionTodosUseCase(unused(), recording, unused(), ExtractTodosUseCase(llm, unused(), unused(), answerLog = log), unused(), NotesLanguageResolver(unused(), recording)),
                ReadingStep(recording, reader, llm)
            )
        }

        val windows = engine.run(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.READING, window = 2), index = 1, last = false)
        val alone = engine.run(DebugRerun.Args.Rerun("s1", FinishingStepRerun.Step.READING), index = 2, last = true)

        assertEquals(listOf("${said[0].text} ${said[1].text}", said[2].text) + said.map { it.text }, reader.read)
        assertEquals(listOf("rerun-reading-1-windows", "rerun-reading-2-lines"), kept.map { it.first })
        assertEquals((windows as FinishingStepRerun.Ran.Readings).answer(), kept[0].second)
        assertTrue(kept[0].second, kept[0].second.lines()[1].startsWith("1\tl0,l1\tkept\t"))
        assertEquals(3, (alone as FinishingStepRerun.Ran.Readings).lines.size)
        assertTrue(recording.stored.isEmpty())
    }

    /** One recording, s1, of [lines]; no reading of them stored, and none ever stored. */
    private class OneRecording(private val lines: List<TranscriptionSegment>) : TranscriptionRepository by unused() {
        val stored = mutableListOf<SegmentReading>()
        override fun getSession(sessionId: String): Flow<TranscriptionSession?> = flowOf(TranscriptionSession(sessionId, name = null, createdAt = 0L, lastModifiedAt = 0L))
        override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> = flowOf(lines)
        override suspend fun readingsOf(sessionId: String): List<SegmentReading> = emptyList()
        override suspend fun getLinesWithReadings(sessionId: String): LinesWithReadings = LinesWithReadings(lines, emptyList())
        override suspend fun saveReading(reading: SegmentReading): Result<Unit> {
            stored += reading
            return Result.success(Unit)
        }
    }

    /** The reader, on the phone: each line read as one English sentence; [read] holds what it was given, in order. */
    private class EnglishReader : ReaderRepository {
        val read = mutableListOf<String>()
        override val model: String = "reader-test"
        override suspend fun isOnPhone(): Boolean = true
        override suspend fun <R> withReader(use: suspend (LoadedReader) -> R): R = use(
            LoadedReader { line ->
                read += line
                ReaderOutput("We will meet and bring the papers.", pieces = 14, tokens = 10, meanLogProb = -0.4, capped = false)
            }
        )
    }

    /** No AI model in memory, nor any to load. */
    private class NoModel : LlmRepository by unused() {
        override val isModelInMemory: Boolean get() = false
    }

    /** Where Gemma 4 E2B's file is, [path], or none. */
    private class E2bChoice(private val path: String?) : FinishingModelRepository {
        override fun observeEnabled(): Flow<Boolean> = flowOf(path != null)
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun enabledModelPath(): String? = path
    }

    /** The calls that register, load and swap models, in order; a switch fails with [switchFailure]. */
    private class CallsLlm(private val switchFailure: Exception? = null) : LlmRepository by unused() {
        val calls = mutableListOf<String>()

        override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> {
            calls += if (loadImmediately) "load $modelPath" else "register $modelPath"
            return Result.success(Unit)
        }

        override suspend fun reloadModel(): Result<Unit> {
            calls += "reload"
            return Result.success(Unit)
        }

        override suspend fun switchModel(modelPath: String): Result<Unit> = switchModel(modelPath, 0L)

        override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> {
            calls += "switch $modelPath"
            return switchFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }

    /** The rerun's model work: what it was asked, in order, with the gate's holder and the sampling at each run. */
    private inner class FakeEngine : DebugRerun.Engine {
        val calls = mutableListOf<String>()
        val holders = mutableListOf<String?>()
        val samplings = mutableListOf<Sampling?>()
        var exists = true
        var loads = true
        var failAt = 0
        var blockAt = 0
        val blocked = CompletableDeferred<Unit>()

        override suspend fun exists(sessionId: String) = exists

        /** The models each load was asked for, and the recordings whose readings were asked for, in order. */
        val models = mutableListOf<DebugRerun.Args.Model?>()
        val readingsAsked = mutableListOf<String>()

        /** Why the readings are missing, or null when they are all there. */
        var missing: String? = null

        override suspend fun load(model: DebugRerun.Args.Model?): String? {
            calls += "load"
            models += model
            return if (loads) "Gemma 4 E2B" else null
        }

        override suspend fun readings(sessionId: String): String? {
            readingsAsked += sessionId
            return missing
        }

        override val notLoaded: String get() = "no live model on the phone"

        /** The window of each run, in order. */
        val windows = mutableListOf<Int>()

        override suspend fun run(args: DebugRerun.Args.Rerun, index: Int, last: Boolean): FinishingStepRerun.Ran {
            calls += "run $index" + if (last) " last" else ""
            windows += args.window
            holders += gate.holder.value
            samplings += currentCoroutineContext()[AiSampling]?.sampling
            if (index == failAt) throw IOException("native crash")
            if (index == blockAt) {
                blocked.complete(Unit)
                awaitCancellation()
            }
            return when (args.step) {
                FinishingStepRerun.Step.TODOS -> FinishingStepRerun.Ran.Todos(ExtractSessionTodosUseCase.Outcome.Found(count = 1))
                FinishingStepRerun.Step.READING -> FinishingStepRerun.Ran.Readings(emptyList(), ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2)))
                else -> FinishingStepRerun.Ran.Notes(
                    listOf(LlmInsight(id = "n$index", sessionId = "s1", title = "Title", content = "- A point.", timestamp = 0L, sourceSegmentIds = emptyList())),
                    pass = false
                )
            }
        }

        override suspend fun store(args: DebugRerun.Args.Rerun, ran: FinishingStepRerun.Ran): Boolean {
            calls += "store run ${calls.count { it.startsWith("run ") }}"
            return true
        }

        override suspend fun release() {
            calls += "release"
        }
    }

    /** The AI's processing service: its holders, and what it was asked, in order. */
    private class HeldService : LlmProcessingServiceController {
        val holders = mutableSetOf<String>()
        val log = mutableListOf<String>()
        override val protected: StateFlow<Boolean> = MutableStateFlow(false)

        override fun startProcessing(holder: String): Boolean {
            log += "start $holder"
            holders += holder
            return true
        }

        override fun stopProcessing(holder: String) {
            log += "stop $holder"
            holders -= holder
        }
    }
}

/** An interface the test reaches none of. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("not used by this test: ${method.name}")
    } as T
