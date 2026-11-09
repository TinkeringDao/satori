package com.satori.ui.debug

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import com.arm.aichat.InferenceEngine
import com.satori.data.monitor.ModelMemoryLines
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.AiModelPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Debug builds only: one memtest from adb, what loading an AI model costs the phone
 * (`DebugToolsActivity --es action memtest --es model e2b|1b`). `~/satori-audit/tools/memtest.sh`
 * runs it with a variant's settings set as debug properties, which only this run's load reads,
 * over its model's own ([InferenceEngine.withDebugLoadSettings]); with none set the load has its
 * model's settings, as every load of the app's own work. Logs under `DebugTools` only the model,
 * times and counts ([DebugMemtest.Report.line]); the memory lines come from the load and the
 * release themselves (`LlmRepositoryImpl`), the settings line from the engine.
 */
@HiltViewModel
class DebugMemtestViewModel @Inject constructor(
    private val llmRepository: LlmRepository,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val modelPaths: AiModelPaths,
    private val settingsRepository: SettingsRepository,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val activeRecorder: ActiveRecorder,
    private val processingService: LlmProcessingServiceController,
    private val inferenceEngine: InferenceEngine
) : DebugRunViewModel() {

    /**
     * The memtest of [modelId], run in the process's scope for debug runs ([DebugRuns]), which
     * outlives the activity being made again (a fold, an unfold, a turn) and its screen gone to the
     * back: the activity made again gets the run under way, so no second load lands in the window
     * memtest.sh samples. [firstLaunch] is false for an activity restored after its process ended:
     * that run is gone, and none starts in its place. Null when none runs. Main thread only.
     */
    internal fun start(modelId: String, firstLaunch: Boolean): DebugRun? = once.start(firstLaunch) { started -> run(modelId, started) }

    private suspend fun run(modelId: String, started: () -> Unit) {
        val model = DebugMemtest.Model.of(modelId)
        if (model == null) {
            Log.w(TAG, "Memtest: no model $modelId, only e2b or 1b")
            return
        }
        val engine = AppMemtestEngine(
            llmRepository,
            loadFinishingModel,
            withVariant = { load -> inferenceEngine.withDebugLoadSettings(load) }
        ) {
            modelPaths.livePath(settingsRepository.getSettings().first())
        }
        val report = try {
            DebugMemtest(activeRecorder.current, aiGate, finishingQueue.busy, processingService, engine).run(model, started)
        } catch (e: CancellationException) {
            // The screen was closed (Back): the run's own finally has let the model go.
            Log.w(TAG, "Memtest ${model.id} cancelled: the screen was closed; the model is let go")
            throw e
        }
        val line = report.line(model)
        if (report.outcome is DebugMemtest.Outcome.Done) Log.i(TAG, line) else Log.w(TAG, line)
    }

    private companion object {
        const val TAG = "DebugTools"
    }
}

/**
 * The one run of a screen ([DebugMemtestViewModel.start]): the first launch starts its block in
 * [scope], the process's ([DebugRuns.scope]) on the phone; every later call gets that run back,
 * and one that isn't a first launch and finds none starts none. The block calls the function it is
 * given once it has started ([DebugRun]). The screen's view model tells it when the screen is gone
 * for good ([screenClosed]). Main thread only.
 */
internal class OneRun(private val scope: CoroutineScope) {
    private var run: DebugRun? = null

    fun start(firstLaunch: Boolean, block: suspend (started: () -> Unit) -> Unit): DebugRun? =
        run ?: if (firstLaunch) DebugRun(scope, block).also { run = it } else null

    /** The screen has ended (its view model cleared): a run whose screen never went to the back ends with it. */
    fun screenClosed() {
        run?.screenClosed()
    }
}

/**
 * Where a long debug tool's run goes on (0.8.0-dev2): the process's, not its screen's. Once the
 * screen has gone to the back, Android ends it (seen on the emulator's Android 15): a task kept out
 * of Recents is trimmed as soon as it is moved behind the home screen (the events log's
 * `wm_finish_activity ... recent-task-trimmed`), which clears the screen's view model. The run must
 * outlive that; the screen's view model still ends one whose screen was closed in front
 * ([OneRun.screenClosed]).
 */
internal object DebugRuns {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * The view model of a long debug tool's screen (0.8.0-dev2): it holds the screen's one run
 * ([once]), which goes on in [scope], the process's on the phone ([DebugRuns.scope]). Its end, as
 * the screen ends for good, cancels a run whose screen never went to the back (Back in front, as
 * before) and ends nothing once it has ([DebugRun.screenClosed]). The memtest's, the prewarm
 * probe's, the rerun's and the reader's tools' view models are these.
 */
abstract class DebugRunViewModel(scope: CoroutineScope = DebugRuns.scope) : ViewModel() {
    internal val once = OneRun(scope)

    final override fun onCleared() {
        once.screenClosed()
    }
}

/**
 * Whether the intent's `front` extra ([front], its value as adb gave it) keeps a long tool's
 * screen in front while it runs: `--es front true` or `--ez front true`, for an emulator or a run
 * someone watches. Anything else, or none, sends the screen to the back, the phone's way.
 */
internal fun keepsScreenInFront(front: Any?): Boolean = front?.toString() == "true"

/**
 * A long debug tool's run ([OneRun]): its [job], and whether it has started, past its rules and
 * holding the AI's service (the block calls the function it is given then). Its screen leaves the
 * front once a run ([toTheBackOnceStarted]); until then the screen owns the run, and closing it
 * (Back) cancels the run ([screenClosed]). Afterwards the run is the process's alone: it ends by
 * itself, or as its rules end it (a recording that starts).
 */
internal class DebugRun(scope: CoroutineScope, block: suspend (started: () -> Unit) -> Unit) {
    private val started = CompletableDeferred<Unit>()
    private val back = AtomicBoolean(false)
    private val keptSaid = AtomicBoolean(false)

    val job: Job = scope.launch { block { started.complete(Unit) } }

    /** Whether the run's screen has gone to the back, after which the screen's end no longer ends the run. */
    val inTheBack: Boolean get() = back.get()

    /**
     * Once the run has started and its AI service is in the foreground ([protected], waited for
     * [waitMs] at most), the screen of [action]'s run goes to the back if the run still goes on:
     * [toTheBack] moves it and says whether Android did, and [log] says so, `<action> runs with its
     * screen in the back`, once a run however often the screen is made again. Android starts a
     * foreground service only while the app is in front, so the screen leaves only once the
     * service is up; a refused run, which never starts, never leaves the front.
     *
     * Without the service in the foreground by then the screen stays in front, as before
     * 0.8.0-dev2: in the back, the service is all that keeps the process out of the cached ones,
     * which Android freezes or kills without a line (seen on the emulator once its service had left
     * the foreground), while a screen in front keeps it, behind the keyguard too. A move Android
     * refuses (a task locked in front) leaves it in front as well. [warn] says why, once a run:
     * `<action> keeps its screen in front: <why>`. A screen in front stays its run's, and Back on it
     * cancels the run.
     */
    suspend fun toTheBackOnceStarted(
        action: String,
        protected: StateFlow<Boolean>,
        waitMs: Long,
        log: (String) -> Unit,
        warn: (String) -> Unit,
        toTheBack: () -> Boolean
    ) {
        started.await()
        val inForeground = withTimeoutOrNull(waitMs) { protected.first { it } } != null
        if (!job.isActive || back.get()) return
        if (!inForeground) return keep(action, "the AI service is not in the foreground", warn)
        // Set before the move: the screen's end that Android may make right after it must find the run gone back.
        back.set(true)
        if (toTheBack()) {
            log("$action runs with its screen in the back")
        } else {
            back.set(false)
            keep(action, "its task didn't move to the back", warn)
        }
    }

    // The screen stays in front, and why, said once a run.
    private fun keep(action: String, why: String, warn: (String) -> Unit) {
        if (keptSaid.compareAndSet(false, true)) warn("$action keeps its screen in front: $why")
    }

    /** The screen has ended: Back while in front cancels the run, as before; a screen in the back ending ends nothing. */
    fun screenClosed() {
        if (!back.get()) job.cancel()
    }
}

/**
 * One memtest: loads [Model] under the AI's foreground service and the AI work gate, as the
 * finisher of recordings does, runs [PROMPT] with an answer of [ANSWER_TOKENS] tokens, unloads the
 * model and waits [SETTLE_MS], until the memory line 10 s after the release has been logged
 * (ModelMemoryLines), so a reader of the log has the whole run before [Report.line].
 *
 * The load starts once the service is in the foreground, as the finisher's loads do, or after
 * [serviceWaitMs] without it; the report says which. Without the service, and with no screen of
 * the app in front, the phone runs the process in its background group on 3 of its 8 cores: on
 * 2026-10-13 Parakeet v3 loaded in 9.0 s there and in 3.0 s on all 8. How fast a load takes its
 * memory decides whether the kernel keeps up, so runs compare only in the same state.
 *
 * It keeps the rules of the debug transcribe ([DebugTranscription]): it starts only while no
 * recorder is busy, no recording waits to be finished and nobody holds the AI, and a recording
 * that starts meanwhile wins: the run is cancelled and the model unloaded, also when the native
 * call under way has to end first.
 *
 * @param engine The model work ([AppMemtestEngine] on the phone)
 * @param clock Milliseconds that never jump
 * @param pause Waits the given milliseconds, for the release's line 10 s later
 * @param serviceWaitMs How long the load waits for the service to be in the foreground
 */
internal class DebugMemtest(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val gate: AiWorkGate,
    private val finishingBusy: StateFlow<Boolean>,
    private val processing: LlmProcessingServiceController,
    private val engine: Engine,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val serviceWaitMs: Long = SERVICE_WAIT_MS
) {

    /** The model a memtest loads, by the name memtest.sh gives. */
    enum class Model(val id: String) {
        /** Gemma 4 E2B, the model of the work after Stop. */
        E2B("e2b"),

        /** The live model, Gemma 3 1B on the phone. */
        LIVE("1b");

        companion object {
            fun of(id: String): Model? = entries.firstOrNull { it.id == id }
        }
    }

    /** The model work of a memtest, which the app's AI does on the phone. */
    interface Engine {
        /** Loads [model], with the variant's llama.cpp settings: null once it is in memory, else why it isn't. */
        suspend fun load(model: Model): String?

        /** The pieces of the answer to [PROMPT], at most [ANSWER_TOKENS]. */
        fun answer(): Flow<String>

        /** Frees whatever model is in memory, and forgets it as the one to load next. */
        suspend fun unload()
    }

    /** How a memtest ended. */
    sealed interface Outcome {
        /** It didn't start, for the reason given. */
        data class Refused(val why: String) : Outcome

        /** The model didn't load in [loadMs], for the reason given; no prompt ran. */
        data class NotLoaded(val why: String, val loadMs: Long) : Outcome

        /** A recording started, so it stopped and let the model go. */
        data object RecorderStarted : Outcome

        /** The load, the prompt or the unload failed with a [kind] of exception; the model was let go. */
        data class Failed(val kind: String) : Outcome

        /**
         * It ran: the load took [loadMs], the first token of the answer came [firstTokenMs] after
         * the prompt was sent (the prompt's encoding, mostly), and [tokens] tokens came in
         * [answerMs], from the first to the last.
         */
        data class Done(val loadMs: Long, val firstTokenMs: Long, val tokens: Int, val answerMs: Long) : Outcome

        /**
         * The run's summary for the log, e.g. `Memtest e2b done: load 5092 ms, prompt 5930 chars
         * to the first token in 9123 ms, answer 200 tokens in 17890 ms (11.1 tokens/s)`. The speed
         * counts the tokens after the first over the time from the first to the last: generation
         * alone, without the prompt.
         */
        fun line(model: Model): String = when (this) {
            is Refused -> "Memtest ${model.id} refused: $why"
            is NotLoaded -> "Memtest ${model.id} not loaded after $loadMs ms: $why"
            RecorderStarted -> "Memtest ${model.id} cancelled: a recording started; the model is let go"
            is Failed -> "Memtest ${model.id} failed: $kind; the model is let go"
            is Done -> "Memtest ${model.id} done: load $loadMs ms, prompt ${PROMPT.length} chars to the first token in " +
                "$firstTokenMs ms, answer $tokens tokens in $answerMs ms (${speed(tokens, answerMs)} tokens/s)"
        }
    }

    /**
     * A memtest's [outcome], and whether its load started with the AI's service in the foreground
     * ([serviceInForeground]; null when it never came to a load).
     */
    data class Report(val outcome: Outcome, val serviceInForeground: Boolean? = null) {
        /**
         * [Outcome.line], then the service's state when the run came to a load, e.g. `Memtest e2b
         * done: load 5092 ms, ... (11.1 tokens/s); AI service in the foreground` or `...; AI
         * service not in the foreground`.
         */
        fun line(model: Model): String = outcome.line(model) + when (serviceInForeground) {
            null -> ""
            true -> "; AI service in the foreground"
            false -> "; AI service not in the foreground"
        }
    }

    /** The memtest of [model]; [onStarted] once it is past its rules and holds the AI's service. */
    suspend fun run(model: Model, onStarted: () -> Unit = {}): Report {
        if (recorder.value.busy()) return Report(Outcome.Refused("a recorder is busy"))
        if (finishingBusy.value || !gate.tryEnter(HOLDER)) return Report(Outcome.Refused("the AI is busy"))
        var inForeground: Boolean? = null
        try {
            // Inside the try, so a refused start still ends below.
            val accepted = processing.startProcessing(HOLDER)
            onStarted()
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val done = async {
                    inForeground = accepted && withTimeoutOrNull(serviceWaitMs) { processing.protected.first { it } } != null
                    measure(model)
                }
                val outcome = select<Outcome> {
                    done.onAwait { it }
                    started.onAwait { Outcome.RecorderStarted }
                }.also {
                    started.cancel()
                    done.cancel()
                }
                Report(outcome, inForeground)
            }
        } finally {
            withContext(NonCancellable) {
                if (!unloaded) {
                    try {
                        engine.unload()
                    } catch (e: Exception) {
                        // The AI's own code logs why its model couldn't be freed; the service and
                        // the gate go all the same, and the outcome stands.
                    }
                }
                processing.stopProcessing(HOLDER)
                gate.exit(HOLDER)
            }
        }
    }

    // Whether this run's model is out of memory already; a DebugMemtest runs once.
    @Volatile private var unloaded = false

    private suspend fun measure(model: Model): Outcome = try {
        val loadStarted = clock()
        val refusal = engine.load(model)
        val loadMs = clock() - loadStarted
        if (refusal != null) {
            Outcome.NotLoaded(refusal, loadMs)
        } else {
            var tokens = 0
            var firstAt = 0L
            var lastAt = 0L
            val sent = clock()
            engine.answer().collect {
                lastAt = clock()
                if (tokens == 0) firstAt = lastAt
                tokens++
            }
            withContext(NonCancellable) { engine.unload() }
            unloaded = true
            pause(SETTLE_MS)
            Outcome.Done(loadMs, firstTokenMs = if (tokens == 0) 0 else firstAt - sent, tokens = tokens, answerMs = lastAt - firstAt)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(e.javaClass.simpleName)
    }

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE

    companion object {
        /** The AI work gate's and the AI service's holder of a memtest. */
        const val HOLDER = "memtest"

        /** The answer's length, in tokens. */
        const val ANSWER_TOKENS = 200

        /** From the release to the summary: the release's memory line 10 s later, and a second. */
        const val SETTLE_MS = ModelMemoryLines.AFTER_MS + 1_000L

        /**
         * How long the load waits for the AI's service to be in the foreground: what the finisher
         * of recordings gives it before it turns to its wake job (RecordingFinisher.KEEP_ALIVE_WAIT_MS).
         */
        const val SERVICE_WAIT_MS = 5_000L

        /** The system prompt of the memtest, the same at every run. */
        const val SYSTEM_PROMPT = "You copy lists. Write the steps of the list again in their order, one per line, " +
            "with the same words and numbers."

        /**
         * The memtest's prompt: 66 made-up steps of moving boxes between shelves, about 5,900
         * characters and about 2,000 tokens for Gemma's tokenizer, which gives each digit a token of
         * its own. Fixed, so every run encodes the same; nothing of a recording is in it.
         */
        val PROMPT: String = (1..66).joinToString(" ") { step ->
            "Step $step: take box ${step % 9 + 1} from shelf ${step % 5 + 1}, put it on shelf ${(step + 2) % 5 + 1}, " +
                "and write the new count in the list."
        }

        private fun speed(tokens: Int, answerMs: Long): String =
            if (tokens < 2 || answerMs <= 0) "n/a" else String.format(Locale.ROOT, "%.1f", (tokens - 1) * 1_000.0 / answerMs)
    }
}

/**
 * The memtest's model work on the app's own AI, by the paths the app's work takes: Gemma 4 E2B
 * through [LoadFinishingModelUseCase], with the room every load of it keeps
 * ([LoadFinishingModelUseCase.E2B_MARGIN_BYTES]), and the live model through
 * [LlmRepository.initialize], which no bar holds. A model in memory before the run goes out first,
 * so the load is a load and not a model found loaded.
 *
 * @param withVariant Runs the load with the variant's settings over its model's own
 *   ([InferenceEngine.withDebugLoadSettings]): around that one load only, so the release before it
 *   and every load of the app's own work keep their model's
 * @param livePath The live model's file, which the E2B run registers as the finisher does
 */
internal class AppMemtestEngine(
    private val llm: LlmRepository,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val withVariant: suspend (load: suspend () -> String?) -> String? = { load -> load() },
    private val livePath: suspend () -> String?
) : DebugMemtest.Engine {

    override suspend fun load(model: DebugMemtest.Model): String? {
        val path = livePath() ?: return "no live model on the phone"
        if (llm.isModelInMemory) llm.cleanup()
        return when (model) {
            DebugMemtest.Model.LIVE -> withVariant {
                llm.initialize(path, DebugMemtest.SYSTEM_PROMPT, loadImmediately = true).exceptionOrNull()
                    ?.let { "the live model didn't load (${it.javaClass.simpleName})" }
            }
            DebugMemtest.Model.E2B -> {
                llm.initialize(path, DebugMemtest.SYSTEM_PROMPT, loadImmediately = false).exceptionOrNull()
                    ?.let { return "the live model couldn't be registered (${it.javaClass.simpleName})" }
                withVariant {
                    when (val loaded = loadFinishingModel()) {
                        FinishingModel.Switched -> null
                        FinishingModel.Live -> "Gemma 4 E2B is switched off or not on the phone"
                        is FinishingModel.FellBack -> loaded.cause.message ?: loaded.cause.javaClass.simpleName
                    }
                }
            }
        }
    }

    override fun answer(): Flow<String> =
        llm.generateInsight(DebugMemtest.PROMPT, DebugMemtest.SYSTEM_PROMPT, DebugMemtest.ANSWER_TOKENS)

    override suspend fun unload() {
        llm.cleanup()
        llm.forgetModel()
    }
}
