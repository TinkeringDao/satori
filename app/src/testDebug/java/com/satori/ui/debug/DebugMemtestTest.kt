package com.satori.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A memtest from adb: it measures one load, prompt and unload under the AI's gate and service,
 * says whether the service was in the foreground, keeps the debug transcribe's rules, yields to a
 * recording and runs once per screen; and the app's engine loads each model by the path the app's
 * own work takes, with the variant's llama.cpp settings for that load only.
 */
class DebugMemtestTest {

    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val gate = AiWorkGate()
    private val finishingBusy = MutableStateFlow(false)
    private val service = HeldService()
    private val events = mutableListOf<String>()
    private var now = 0L

    private fun memtest(engine: DebugMemtest.Engine, serviceWaitMs: Long = DebugMemtest.SERVICE_WAIT_MS) = DebugMemtest(
        recorder, gate, finishingBusy, service, engine,
        clock = { now },
        pause = { ms ->
            events += "pause $ms under ${gate.holder.value} with ${service.holders}"
        },
        serviceWaitMs = serviceWaitMs
    )

    /** Loads in 5 s; answers 200 tokens, the first 2 s after the prompt, then one every 50 ms. */
    private inner class ScriptedEngine(
        private val refusal: String? = null,
        private val answer: Flow<String>? = null
    ) : DebugMemtest.Engine {
        override suspend fun load(model: DebugMemtest.Model): String? {
            events += "load ${model.id}"
            now += 5_000
            return refusal
        }

        override fun answer(): Flow<String> = answer ?: flow {
            events += "answer"
            now += 2_000
            emit("Step")
            repeat(199) {
                now += 50
                emit(" 1")
            }
        }

        override suspend fun unload() {
            events += "unload"
        }
    }

    @Test
    fun `it loads, prompts and unloads under the AI's gate and service, waits for the release's line, and reports`() = runBlocking {
        val report = memtest(ScriptedEngine()).run(DebugMemtest.Model.E2B)

        assertEquals(DebugMemtest.Outcome.Done(loadMs = 5_000, firstTokenMs = 2_000, tokens = 200, answerMs = 9_950), report.outcome)
        assertEquals(listOf("load e2b", "answer", "unload", "pause 11000 under memtest with [memtest]"), events)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
        assertEquals(listOf("start memtest", "stop memtest"), service.log)
        assertEquals(
            "Memtest e2b done: load 5000 ms, prompt 5930 chars to the first token in 2000 ms, " +
                "answer 200 tokens in 9950 ms (20.0 tokens/s); AI service in the foreground",
            report.line(DebugMemtest.Model.E2B)
        )
    }

    @Test
    fun `a load waits for the AI's service to be in the foreground, and the report says when it never was`() = runBlocking {
        service.comesToForeground = false
        val unprotected = memtest(ScriptedEngine(), serviceWaitMs = 50).run(DebugMemtest.Model.E2B)
        service.accepts = false
        val refused = memtest(ScriptedEngine(), serviceWaitMs = 60_000).run(DebugMemtest.Model.LIVE)

        assertEquals(false, unprotected.serviceInForeground)
        assertTrue(unprotected.line(DebugMemtest.Model.E2B), unprotected.line(DebugMemtest.Model.E2B).endsWith("tokens/s); AI service not in the foreground"))
        // A start the phone refused is not waited for.
        assertEquals(false, refused.serviceInForeground)
        assertTrue(refused.outcome is DebugMemtest.Outcome.Done)
        assertEquals(listOf("load e2b", "answer", "unload", "pause 11000 under memtest with [memtest]"), events.take(4))
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `it doesn't start beside a recorder, while a recording waits to be finished, or while the AI is held`() = runBlocking {
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)
        val besideRecorder = memtest(ScriptedEngine()).run(DebugMemtest.Model.E2B)
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.IDLE)
        finishingBusy.value = true
        val whileFinishing = memtest(ScriptedEngine()).run(DebugMemtest.Model.LIVE)
        finishingBusy.value = false
        gate.tryEnter(AiWorkGate.MAP)
        val whileHeld = memtest(ScriptedEngine()).run(DebugMemtest.Model.LIVE)

        assertEquals(DebugMemtest.Outcome.Refused("a recorder is busy"), besideRecorder.outcome)
        assertEquals(DebugMemtest.Outcome.Refused("the AI is busy"), whileFinishing.outcome)
        assertEquals(DebugMemtest.Outcome.Refused("the AI is busy"), whileHeld.outcome)
        assertNull(whileHeld.serviceInForeground)
        assertTrue(events.isEmpty())
        assertTrue(service.log.isEmpty())
        assertEquals(AiWorkGate.MAP, gate.holder.value)
        assertEquals("Memtest 1b refused: the AI is busy", whileHeld.line(DebugMemtest.Model.LIVE))
    }

    @Test
    fun `a model that doesn't load is reported with why, nothing is prompted, and the model is still let go`() = runBlocking {
        val why = "Not enough free memory: 3416 MB available, 3794 MB needed"

        val report = memtest(ScriptedEngine(refusal = why)).run(DebugMemtest.Model.E2B)

        assertEquals(DebugMemtest.Outcome.NotLoaded(why, loadMs = 5_000), report.outcome)
        assertEquals(listOf("load e2b", "unload"), events)
        assertNull(gate.holder.value)
        assertEquals("Memtest e2b not loaded after 5000 ms: $why; AI service in the foreground", report.line(DebugMemtest.Model.E2B))
    }

    @Test
    fun `a recording that starts meanwhile stops the run, and the model is let go before the AI is free`() = runBlocking {
        val answering = CompletableDeferred<Unit>()
        val engine = ScriptedEngine(answer = flow {
            emit("Step")
            answering.complete(Unit)
            awaitCancellation()
        })
        val run = async { memtest(engine).run(DebugMemtest.Model.LIVE) }
        answering.await()

        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)

        assertEquals(DebugMemtest.Outcome.RecorderStarted, run.await().outcome)
        assertEquals(listOf("load 1b", "unload"), events)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `an answer that fails is a failed run, and the model is let go`() = runBlocking {
        val report = memtest(ScriptedEngine(answer = flow { throw IllegalStateException("native crash") })).run(DebugMemtest.Model.E2B)

        assertEquals(DebugMemtest.Outcome.Failed("IllegalStateException"), report.outcome)
        assertEquals(listOf("load e2b", "unload"), events)
        assertNull(gate.holder.value)
        assertEquals(
            "Memtest e2b failed: IllegalStateException; the model is let go; AI service in the foreground",
            report.line(DebugMemtest.Model.E2B)
        )
    }

    @Test
    fun `the prompt is fixed, about 2,000 tokens long, and the models are named as memtest sh names them`() {
        assertEquals(5_930, DebugMemtest.PROMPT.length)
        assertTrue(DebugMemtest.PROMPT.startsWith("Step 1: take box 2 from shelf 2, put it on shelf 4, and write the new count in the list. Step 2:"))
        assertTrue(DebugMemtest.PROMPT.endsWith("Step 66: take box 4 from shelf 2, put it on shelf 4, and write the new count in the list."))
        assertEquals(200, DebugMemtest.ANSWER_TOKENS)
        assertEquals(DebugMemtest.Model.E2B, DebugMemtest.Model.of("e2b"))
        assertEquals(DebugMemtest.Model.LIVE, DebugMemtest.Model.of("1b"))
        assertNull(DebugMemtest.Model.of("4b"))
    }

    @Test
    fun `a memtest runs once per screen - made again after a fold it gets the run under way, restored after its process ended it starts none`() = runBlocking {
        val runs = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        val screen = OneRun(this)

        val first = screen.start(firstLaunch = true) { runs.incrementAndGet(); finish.await() }
        // The activity made again after a fold asks with its saved state, the same view model.
        val again = screen.start(firstLaunch = false) { runs.incrementAndGet() }
        val twice = screen.start(firstLaunch = true) { runs.incrementAndGet() }
        finish.complete(Unit)
        first?.job?.join()
        // Restored after its process ended: a new view model, and no saved run to go on with.
        val restored = OneRun(this).start(firstLaunch = false) { runs.incrementAndGet() }

        assertSame(first, again)
        assertSame(first, twice)
        assertNull(restored)
        assertEquals(1, runs.get())
    }

    // What a run's screen said under DebugTools, W lines marked.
    private val screenLines = mutableListOf<String>()

    // The screen of [run], the memtest's, sent to the back as the activity sends it; [moved] is Android's answer.
    private suspend fun toTheBack(run: DebugRun, protected: StateFlow<Boolean>, waitMs: Long, moved: () -> Boolean) =
        run.toTheBackOnceStarted("memtest", protected, waitMs, log = { screenLines += it }, warn = { screenLines += "W $it" }, toTheBack = moved)

    @Test
    fun `a run's screen goes to the back once the run has started and its service is in the foreground, once a run`() = runBlocking {
        val protected = MutableStateFlow(false)
        val start = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val moves = AtomicInteger()
        val run = OneRun(this).start(firstLaunch = true) { started ->
            start.await()
            started()
            finish.await()
        }!!
        val screen = async { toTheBack(run, protected, waitMs = 60_000) { moves.incrementAndGet(); true } }

        // Not before the run has started: its rules may still refuse it.
        yield()
        assertEquals(0, moves.get())
        start.complete(Unit)
        // Not before the service is in the foreground: Android starts one only while the app is in front.
        repeat(3) { yield() }
        assertEquals(0, moves.get())
        protected.value = true
        withTimeout(5_000) { screen.await() }
        assertEquals(1, moves.get())
        assertTrue(run.inTheBack)
        // The screen made again after a fold finds the run in the back already.
        toTheBack(run, protected, waitMs = 60_000) { moves.incrementAndGet(); true }
        assertEquals(1, moves.get())
        assertEquals(listOf("memtest runs with its screen in the back"), screenLines)
        finish.complete(Unit)
        run.job.join()
    }

    @Test
    fun `a service that never comes up keeps the screen in front, said once, and Back there still cancels the run`() = runBlocking {
        val protected = MutableStateFlow(false)
        val moves = AtomicInteger()
        val screen = OneRun(this)
        val waiting = screen.start(firstLaunch = true) { started -> started(); awaitCancellation() }!!

        withTimeout(5_000) { toTheBack(waiting, protected, waitMs = 50) { moves.incrementAndGet(); true } }
        // Made again after a fold: still no service, and nothing said twice.
        withTimeout(5_000) { toTheBack(waiting, protected, waitMs = 50) { moves.incrementAndGet(); true } }

        // In the back the service is all that keeps the process from being frozen; in front the screen keeps it.
        assertEquals(0, moves.get())
        assertFalse(waiting.inTheBack)
        assertEquals(listOf("W memtest keeps its screen in front: the AI service is not in the foreground"), screenLines)
        screen.screenClosed()
        withTimeout(5_000) { waiting.job.join() }
        assertTrue(waiting.job.isCancelled)
    }

    @Test
    fun `a task Android doesn't move to the back keeps its screen and its run, said once`() = runBlocking {
        val protected = MutableStateFlow(true)
        val screen = OneRun(this)
        val run = screen.start(firstLaunch = true) { started -> started(); awaitCancellation() }!!

        withTimeout(5_000) { toTheBack(run, protected, waitMs = 60_000) { false } }
        withTimeout(5_000) { toTheBack(run, protected, waitMs = 60_000) { false } }

        // Not said to be in the back, and the screen still owns the run: Back on it cancels it.
        assertFalse(run.inTheBack)
        assertEquals(listOf("W memtest keeps its screen in front: its task didn't move to the back"), screenLines)
        screen.screenClosed()
        withTimeout(5_000) { run.job.join() }
        assertTrue(run.job.isCancelled)
    }

    @Test
    fun `a run that never started or has ended sends no screen to the back`() = runBlocking {
        val protected = MutableStateFlow(true)
        val moves = AtomicInteger()
        // A run refused by its rules ends without starting; one that started and ended is over.
        val refused = OneRun(this).start(firstLaunch = true) { }!!
        refused.job.join()
        val ended = OneRun(this).start(firstLaunch = true) { started -> started() }!!
        ended.job.join()

        val waitedFor = async { toTheBack(refused, protected, waitMs = 50) { moves.incrementAndGet(); true } }
        withTimeout(5_000) { toTheBack(ended, protected, waitMs = 50) { moves.incrementAndGet(); true } }
        yield()

        assertTrue(waitedFor.isActive)
        waitedFor.cancel()
        assertEquals(0, moves.get())
        assertTrue(screenLines.isEmpty())
    }

    @Test
    fun `a screen closed in front ends its run, a screen that has gone to the back ends nothing`() = runBlocking {
        val protected = MutableStateFlow(true)
        val screen = OneRun(this)
        val inFront = screen.start(firstLaunch = true) { started -> started(); awaitCancellation() }!!
        yield()

        // Back while in front: the view model cleared, the run cancelled, as before 0.8.0-dev2.
        screen.screenClosed()
        withTimeout(5_000) { inFront.job.join() }
        assertTrue(inFront.job.isCancelled)

        val finish = CompletableDeferred<Unit>()
        val behind = OneRun(this)
        val inBack = behind.start(firstLaunch = true) { started -> started(); finish.await() }!!
        withTimeout(5_000) { toTheBack(inBack, protected, waitMs = 60_000) { true } }
        assertTrue(inBack.inTheBack)
        // Android ends a screen in the back, out of Recents, on its own: the run goes on.
        behind.screenClosed()
        yield()
        assertTrue(inBack.job.isActive)
        finish.complete(Unit)
        withTimeout(5_000) { inBack.job.join() }
        assertTrue(inBack.job.isCompleted && !inBack.job.isCancelled)
    }

    @Test
    fun `a long tool's view model, cleared as its screen ends, cancels a run still in front and none gone to the back`() = runBlocking {
        val inFrontStore = ViewModelStore()
        val inFront = ViewModelProvider(inFrontStore, screenModels(this))[ScreenModel::class.java].once
            .start(firstLaunch = true) { started -> started(); awaitCancellation() }!!
        yield()

        // Back while in front: the activity finishes and its view models are cleared.
        inFrontStore.clear()
        withTimeout(5_000) { inFront.job.join() }
        assertTrue(inFront.job.isCancelled)

        val inBackStore = ViewModelStore()
        val finish = CompletableDeferred<Unit>()
        val inBack = ViewModelProvider(inBackStore, screenModels(this))[ScreenModel::class.java].once
            .start(firstLaunch = true) { started -> started(); finish.await() }!!
        withTimeout(5_000) { toTheBack(inBack, MutableStateFlow(true), waitMs = 60_000) { true } }
        // Android trims the screen in the back: its view model is cleared, the run goes on.
        inBackStore.clear()
        yield()
        assertTrue(inBack.job.isActive)
        finish.complete(Unit)
        withTimeout(5_000) { inBack.job.join() }
        assertFalse(inBack.job.isCancelled)
    }

    @Test
    fun `only front true keeps a long tool's screen in front, as --es or --ez gives it`() {
        assertTrue(keepsScreenInFront("true"))
        assertTrue(keepsScreenInFront(true))
        for (other in listOf(null, "false", false, "", "yes", "TRUE", "1")) assertFalse("$other", keepsScreenInFront(other))
    }

    /** A long tool's view model as the screens have them, its runs in [scope]. */
    private class ScreenModel(scope: CoroutineScope) : DebugRunViewModel(scope)

    private fun screenModels(scope: CoroutineScope) = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(ScreenModel(scope))!!
    }

    @Test
    fun `a memtest says it has started once past its rules and holding the service, a refused one never`() = runBlocking {
        val started = mutableListOf<String>()
        memtest(ScriptedEngine()).run(DebugMemtest.Model.E2B) { started += "started holding ${service.holders} under ${gate.holder.value}" }
        gate.tryEnter(AiWorkGate.MAP)
        memtest(ScriptedEngine()).run(DebugMemtest.Model.E2B) { started += "refused one started" }

        assertEquals(listOf("started holding [memtest] under memtest"), started)
    }

    // ─── The app's engine ────────────────────────────────────────────────────────

    @Test
    fun `the variant's llama cpp settings are for the memtest's own load only, not the release before it or the unload`() = runBlocking {
        fun variantOf(llm: CallsLlm): suspend (suspend () -> String?) -> String? = { load ->
            llm.calls += "variant on"
            try {
                load()
            } finally {
                llm.calls += "variant off"
            }
        }
        val forE2b = CallsLlm(inMemory = true)
        val forLive = CallsLlm(inMemory = true)
        val e2b = AppMemtestEngine(forE2b, LoadFinishingModelUseCase(forE2b, E2bOnPhone()), variantOf(forE2b)) { "/models/live.gguf" }
        val live = AppMemtestEngine(forLive, LoadFinishingModelUseCase(forLive, E2bOnPhone()), variantOf(forLive)) { "/models/live.gguf" }

        assertNull(e2b.load(DebugMemtest.Model.E2B))
        e2b.unload()
        assertNull(live.load(DebugMemtest.Model.LIVE))

        assertEquals(
            listOf(
                "cleanup", "register /models/live.gguf",
                "variant on", "switch /models/e2b.gguf with 600 MB to spare", "variant off",
                "cleanup", "forget"
            ),
            forE2b.calls
        )
        assertEquals(listOf("cleanup", "variant on", "load /models/live.gguf", "variant off"), forLive.calls)
    }

    @Test
    fun `E2B loads by the finisher's path with 600 MB to spare, the live model registered first, after the model in memory goes`() = runBlocking {
        val llm = CallsLlm(inMemory = true)
        val engine = AppMemtestEngine(llm, LoadFinishingModelUseCase(llm, E2bOnPhone())) { "/models/live.gguf" }

        val refusal = engine.load(DebugMemtest.Model.E2B)

        assertNull(refusal)
        assertEquals(listOf("cleanup", "register /models/live.gguf", "switch /models/e2b.gguf with 600 MB to spare"), llm.calls)
    }

    @Test
    fun `E2B without the room says what was missing`() = runBlocking {
        val llm = CallsLlm(switchFailure = NotEnoughMemoryException(availableBytes = 3416L * MB, neededBytes = 3794L * MB))
        val engine = AppMemtestEngine(llm, LoadFinishingModelUseCase(llm, E2bOnPhone())) { "/models/live.gguf" }

        assertEquals("Not enough free memory: 3416 MB available, 3794 MB needed", engine.load(DebugMemtest.Model.E2B))
    }

    @Test
    fun `the live model loads as the recorder loads it, with no bar, and the unload frees and forgets it`() = runBlocking {
        val llm = CallsLlm()
        val engine = AppMemtestEngine(llm, LoadFinishingModelUseCase(llm, E2bOnPhone())) { "/models/live.gguf" }

        assertNull(engine.load(DebugMemtest.Model.LIVE))
        engine.unload()

        assertEquals(listOf("load /models/live.gguf", "cleanup", "forget"), llm.calls)
    }

    @Test
    fun `without a live model on the phone nothing loads`() = runBlocking {
        val llm = CallsLlm()
        val engine = AppMemtestEngine(llm, LoadFinishingModelUseCase(llm, E2bOnPhone())) { null }

        assertEquals("no live model on the phone", engine.load(DebugMemtest.Model.LIVE))
        assertTrue(llm.calls.isEmpty())
    }

    /**
     * The AI's processing service: its holders, and what it was asked, in order. It [accepts] a
     * start and, if it [comesToForeground], is in the foreground from then on.
     */
    private class HeldService : LlmProcessingServiceController {
        val holders = mutableSetOf<String>()
        val log = mutableListOf<String>()
        var accepts = true
        var comesToForeground = true
        private val inForeground = MutableStateFlow(false)
        override val protected: StateFlow<Boolean> = inForeground

        override fun startProcessing(holder: String): Boolean {
            log += "start $holder"
            if (!accepts) return false
            holders += holder
            if (comesToForeground) inForeground.value = true
            return true
        }

        override fun stopProcessing(holder: String) {
            log += "stop $holder"
            holders -= holder
            if (holders.isEmpty()) inForeground.value = false
        }
    }

    /** Gemma 4 E2B switched on and on the phone. */
    private class E2bOnPhone : FinishingModelRepository {
        override fun observeEnabled(): Flow<Boolean> = flowOf(true)
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun enabledModelPath(): String = "/models/e2b.gguf"
    }

    /** The calls that load, swap and free models, in order; a switch fails with [switchFailure]. */
    private class CallsLlm(
        private val inMemory: Boolean = false,
        private val switchFailure: Exception? = null
    ) : LlmRepository {
        val calls = mutableListOf<String>()

        override val isModelInMemory: Boolean get() = inMemory && calls.isEmpty()

        override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> {
            calls += if (loadImmediately) "load $modelPath" else "register $modelPath"
            return Result.success(Unit)
        }

        override suspend fun switchModel(modelPath: String): Result<Unit> = switchModel(modelPath, 0L)

        override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> {
            calls += "switch $modelPath with ${extraFreeBytes / MB} MB to spare"
            return switchFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun cleanup() {
            calls += "cleanup"
        }

        override suspend fun forgetModel() {
            calls += "forget"
        }

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = emptyFlow()
        override suspend fun reloadModel(): Result<Unit> = unused()
        override fun isMemoryConstrained(): Boolean = unused()
        override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = unused()
        override fun useConservativeThreads(): Unit = unused()
        override suspend fun checkAndCacheMemoryConstraint(): Unit = unused()
        override suspend fun recordConstrainedInference(inputChars: Int): Unit = unused()
        override fun isLargeContext(inputChars: Int): Boolean = unused()
        override val isGenerating: Boolean get() = unused()
        override fun beginInference(): Unit = unused()
        override fun endInference(): Unit = unused()
        override val thermalThrottleFlow: Flow<ThermalThrottle> get() = unused()

        private fun unused(): Nothing = error("not used by the memtest")
    }

    private companion object {
        const val MB = 1_048_576L
    }
}
