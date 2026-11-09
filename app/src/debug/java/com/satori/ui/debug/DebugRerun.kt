package com.satori.ui.debug

import android.os.SystemClock
import android.util.Log
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.monitor.ThermalMonitor
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.AiSampling
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.Sampling
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.finish.FinishingStepRerun
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.domain.usecase.finish.RerunAnswerLog
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.AiModelPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Debug builds only: runs a finishing step of a stored recording again from adb, N times, so the
 * main session can see on the phone what the model makes of a real recording, and compare
 * temperatures (brief 0.7.2 A.6, investigation C's C.2.6):
 *
 * `adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action rerun --es session <id>
 * --es step notes|name|todos|reading [--es times 1-10] [--es temp 0.0-2.0 | --ef temp 0.0-2.0] [--es from notes|transcript]
 * [--es store true] [--es reader on|off] [--es model 1b|e2b] [--es window 1|2]`
 *
 * Each run keeps its raw answers under the rerun's tags (RerunAnswerLog) and logs its drops on the
 * usual lines; nothing is stored unless `store` is true ([FinishingStepRerun.store]). The run goes
 * on as the memtest's does ([OneRun], [DebugRuns]).
 *
 * Since 0.8.0 (R8, for the design's V3 and V4): `step reading` reads every line only the reader
 * reads again, loading no AI model, and each run's lines go to an answer file
 * (`<id>-rerun-reading-<run>-lines.txt`, [FinishingStepRerun.Ran.Readings.answer]), nothing stored;
 * with `window 2` each two lines are read together, design D1's windows (`...-windows.txt`).
 * The notes, the name and the to-dos read the recording as the finishing does, its readings made
 * first where they lack ([EnsureReadingsUseCase], `reader on`, the default), or with `reader off`
 * the lines as they were said, Malayalam and all, as 0.7.3 read them: never with `store`. `model`
 * picks the AI model: `1b` the live model alone, `e2b` Gemma 4 E2B or nothing; without it, the
 * finishing's choice.
 */
@HiltViewModel
class DebugRerunViewModel @Inject constructor(
    private val transcriptionRepository: TranscriptionRepository,
    private val notesLanguage: NotesLanguageResolver,
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val resourceProvider: ResourceProvider,
    private val thermalMonitor: ThermalMonitor,
    private val echoLog: PromptEchoLog,
    private val notesLog: NotesLog,
    private val answerLog: AnswerLog,
    private val foundLog: FoundTodosLog,
    private val timeParser: TimeExpressionParser,
    private val todoRepository: TodoRepository,
    private val reminderScheduler: ReminderScheduler,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val modelPaths: AiModelPaths,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val activeRecorder: ActiveRecorder,
    private val processingService: LlmProcessingServiceController,
    private val readingStep: ReadingStep,
    private val ensureReadings: EnsureReadingsUseCase
) : DebugRunViewModel() {

    /**
     * The rerun asked for by [extras] (the intent's: a name and its value as a string), run as the
     * memtest is ([DebugRuns]); null when none runs ([OneRun.start]: an activity restored after its
     * process ended starts none). Main thread only.
     */
    internal fun start(extras: Map<String, String?>, firstLaunch: Boolean): DebugRun? = once.start(firstLaunch) { started ->
        val args = DebugRerun.Args.of(extras)
        val rerun = DebugRerun(activeRecorder.current, aiGate, finishingQueue.busy, processingService, engine(), warn = { Log.w(TAG, it) }) { Log.i(TAG, it) }
        try {
            rerun.run(args, started)
        } catch (e: CancellationException) {
            Log.w(TAG, "Debug rerun cancelled: the screen was closed; the model is let go")
            throw e
        }
    }

    private fun engine() = AppRerunEngine(
        llmRepository, loadFinishingModel, transcriptionRepository, answerLog,
        livePath = { modelPaths.livePath(settingsRepository.getSettings().first()) },
        readingsOf = { sessionId -> ensureReadings(sessionId, EnsureReadingsUseCase.Work.RERUN) }
    ) { answers ->
        FinishingStepRerun(
            transcriptionRepository,
            notesLanguage,
            GenerateFinalInsightUseCase(llmRepository, settingsRepository, resourceProvider, echoLog, notesLog, answers),
            GenerateBatchInsightUseCase(llmRepository, settingsRepository, resourceProvider, thermalMonitor, echoLog, notesLog, answers),
            GenerateSessionTitleUseCase(llmRepository, resourceProvider, echoLog, notesLog, answers),
            ExtractSessionTodosUseCase(
                settingsRepository, transcriptionRepository, todoRepository,
                ExtractTodosUseCase(llmRepository, resourceProvider, timeParser, answerLog = answers, foundLog = foundLog),
                reminderScheduler, notesLanguage
            ),
            readingStep
        )
    }

    private companion object {
        const val TAG = "DebugTools"
    }
}

/**
 * One rerun of a finishing step ([DebugToolsActivity]'s `rerun`), under the rules of the debug
 * transcribe ([DebugTranscription]) and the memtest ([DebugMemtest]): it starts only while no
 * recorder is busy, no recording waits to be finished and nobody holds the AI, it holds the AI work
 * gate ([AiWorkGate.DEBUG_RERUN]) and the AI's service ([ProcessingHolders.rerun]) to its end, and a
 * recording that starts meanwhile wins: the runs stop and the model is let go. The model is the
 * finishing's (Gemma 4 E2B with its room, else the live model), loaded once, paced as every load
 * is, and freed at the end in a NonCancellable finally, also after a cancel or a failure.
 *
 * With a temperature, the runs go inside `withContext(AiSampling(...))`: their calls, and only
 * theirs, are sampled at it; the finishing of the owner's recordings, which runs in its own scope,
 * never sees it, and the first call after the rerun is sampled at the default again.
 *
 * @param warn Where its refusals, stops and failures go (DebugTools at W)
 * @param log Where the rerun's lines go (DebugTools); counts and ids only
 */
internal class DebugRerun(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val gate: AiWorkGate,
    private val finishingBusy: StateFlow<Boolean>,
    private val processing: LlmProcessingServiceController,
    private val engine: Engine,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val warn: ((String) -> Unit)? = null,
    private val log: (String) -> Unit
) {

    /** A rerun's arguments, checked ([of]), or why they can't be: the refusal's reason. */
    sealed interface Args {
        /**
         * What adb asked for. [reader] (0.8.0): the notes, the name and the to-dos read the readings,
         * as the finishing does, or with false the lines as they were said. [model]: the AI model the
         * runs are on, null for the finishing's choice. [window]: a reading's lines read together, 1
         * or 2 (design D1).
         */
        data class Rerun(
            val sessionId: String,
            val step: FinishingStepRerun.Step,
            val times: Int = 1,
            val temperature: Float? = null,
            val from: FinishingStepRerun.From = FinishingStepRerun.From.NOTES,
            val store: Boolean = false,
            val reader: Boolean = true,
            val model: Model? = null,
            val window: Int = 1
        ) : Args {
            /** The sampling of the runs: [temperature] over the default's others, or the default. */
            val sampling: Sampling get() = temperature?.let { Sampling.DEFAULT.copy(temperature = it) } ?: Sampling.DEFAULT
        }

        /** The arguments don't make a rerun, for [why]. */
        data class Wrong(val sessionId: String, val why: String) : Args

        /** The AI model a rerun is on, by the word adb gives for it (`--es model`). */
        enum class Model(val id: String) {
            /** The live model alone, Gemma 3 1B: Gemma 4 E2B is not asked for. */
            ONE_B("1b"),

            /** Gemma 4 E2B, as the finishing loads it, with its room; without it, the rerun doesn't start. */
            E2B("e2b");

            companion object {
                fun of(id: String?): Model? = entries.firstOrNull { it.id == id?.trim() }
            }
        }

        companion object {
            const val MAX_TIMES = 10
            const val MAX_TEMPERATURE = 2f

            /** The most lines a reading reads together: design D1's 2-segment windows. */
            const val MAX_WINDOW = 2

            /**
             * The rerun of the intent's [extras] (session, step, times, temp, from, store, reader,
             * model, window), or what is wrong with them. A reading takes neither a temperature, the
             * reader off nor a model, as it asks no AI model, and only a reading takes a window; what
             * the reader off writes is never stored.
             */
            fun of(extras: Map<String, String?>): Args {
                val session = extras["session"]?.trim().orEmpty()
                if (!SESSION_ID.matches(session)) return Wrong(session, "no such recording")
                val step = FinishingStepRerun.Step.of(extras["step"]) ?: return Wrong(session, "step must be notes, name, todos or reading")
                val times = extras["times"]?.let { it.trim().toIntOrNull()?.takeIf { n -> n in 1..MAX_TIMES } ?: return Wrong(session, "times must be 1-$MAX_TIMES") } ?: 1
                val temperature = extras["temp"]?.let {
                    it.trim().toFloatOrNull()?.takeIf { t -> t in 0f..MAX_TEMPERATURE } ?: return Wrong(session, "temp must be 0-2")
                }
                val from = extras["from"]?.let { FinishingStepRerun.From.of(it) ?: return Wrong(session, "from must be notes or transcript") }
                    ?: FinishingStepRerun.From.NOTES
                val store = when (extras["store"]?.trim()) {
                    null, "false" -> false
                    "true" -> true
                    else -> return Wrong(session, "store must be true or false")
                }
                val reader = when (extras["reader"]?.trim()) {
                    null, "on" -> true
                    "off" -> false
                    else -> return Wrong(session, "reader must be on or off")
                }
                val model = extras["model"]?.let { Model.of(it) ?: return Wrong(session, "model must be 1b or e2b") }
                if (step == FinishingStepRerun.Step.READING && (temperature != null || !reader || model != null)) {
                    return Wrong(session, "step reading takes no temp, reader or model")
                }
                if (store && !reader) return Wrong(session, "store needs the reader on")
                val window = extras["window"]?.let { it.trim().toIntOrNull()?.takeIf { n -> n in 1..MAX_WINDOW } ?: return Wrong(session, "window must be 1 or 2") } ?: 1
                if (window != 1 && step != FinishingStepRerun.Step.READING) return Wrong(session, "window is for step reading")
                return Rerun(session, step, times, temperature, from, store, reader, model, window)
            }

            // Recording ids are UUIDs.
            private val SESSION_ID = Regex("[A-Za-z0-9-]+")
        }
    }

    /** The model work of a rerun, which the app's AI does on the phone ([AppRerunEngine]). */
    interface Engine {
        /** Whether the recording [sessionId] is stored. */
        suspend fun exists(sessionId: String): Boolean

        /**
         * Puts the model [model] asks for in memory, the finishing's when null: its name for the log,
         * or null with [notLoaded] saying why.
         */
        suspend fun load(model: Args.Model?): String?

        /** Why the last [load] gave no model. */
        val notLoaded: String

        /**
         * Makes the readings the recording [sessionId] lacks (0.8.0), as the finishing has them,
         * before a model loads: null once its lines all have theirs, else why they are missing.
         */
        suspend fun readings(sessionId: String): String?

        /** Run [index] (from 1) of [args]; [last] for the last one. */
        suspend fun run(args: Args.Rerun, index: Int, last: Boolean): FinishingStepRerun.Ran

        /** Stores what the last run made, as [args] asked; whether something was. */
        suspend fun store(args: Args.Rerun, ran: FinishingStepRerun.Ran): Boolean

        /** Frees whatever model is in memory, and forgets it as the one to load next. */
        suspend fun release()
    }

    /** How a rerun ended. */
    sealed interface Outcome {
        data class Refused(val why: String) : Outcome
        data class NotLoaded(val why: String) : Outcome
        data object RecorderStarted : Outcome
        data class Failed(val run: Int, val kind: String) : Outcome
        data class Done(val runs: List<FinishingStepRerun.Ran>, val stored: Boolean) : Outcome
    }

    /** The rerun [args] ask for; [onStarted] once it is past its rules and holds the AI's service. */
    suspend fun run(args: Args, onStarted: () -> Unit = {}): Outcome {
        val rerun = when (args) {
            is Args.Wrong -> return refuse(args.sessionId, args.why)
            is Args.Rerun -> args
        }
        val id = rerun.sessionId
        if (recorder.value.busy()) return refuse(id, "a recorder is busy")
        if (!engine.exists(id)) return refuse(id, "no such recording")
        if (finishingBusy.value || !gate.tryEnter(AiWorkGate.DEBUG_RERUN)) return refuse(id, "the AI is busy")
        try {
            // Inside the try, so a refused start still ends below.
            processing.startProcessing(ProcessingHolders.rerun(id))
            onStarted()
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val done = async { runs(rerun) }
                select<Outcome> {
                    done.onAwait { it }
                    started.onAwait { Outcome.RecorderStarted }
                }.also {
                    started.cancel()
                    done.cancel()
                    if (it == Outcome.RecorderStarted) warning("Debug rerun of $id stopped: a recording started")
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    engine.release()
                } catch (e: Exception) {
                    // The AI's own code logs why its model couldn't be freed; the service and the gate go all the same.
                }
                processing.stopProcessing(ProcessingHolders.rerun(id))
                gate.exit(AiWorkGate.DEBUG_RERUN)
            }
        }
    }

    private suspend fun runs(args: Args.Rerun): Outcome {
        val step = args.step.id
        val id = args.sessionId
        val runs = "${args.times} ${if (args.times == 1) "run" else "runs"}"
        if (args.step == FinishingStepRerun.Step.READING) {
            // The reader alone: no AI model loads, and the step lets go of any in memory first.
            val windows = if (args.window > 1) ", ${args.window} lines read together" else ""
            log("Debug rerun $step of $id on the reader, $runs$windows, nothing stored")
        } else {
            // Before the model: the reader never loads beside one, and E2B's load keeps its room.
            if (args.reader) {
                engine.readings(id)?.let { why ->
                    warning("Debug rerun $step of $id not started: its readings are missing ($why)")
                    return Outcome.NotLoaded(why)
                }
            }
            val model = engine.load(args.model) ?: return Outcome.NotLoaded(engine.notLoaded).also {
                warning("Debug rerun $step of $id not started: ${engine.notLoaded}")
            }
            val which = if (args.temperature == null) "the default" else "an override"
            log(
                "Debug rerun $step of $id on $model, $runs at ${args.sampling.describe()} ($which)" +
                    (if (args.step == FinishingStepRerun.Step.NAME) ", from its ${args.from.id}" else "") +
                    if (args.reader) "" else ", reader off"
            )
        }
        val made = mutableListOf<FinishingStepRerun.Ran>()
        val work: suspend () -> Outcome? = {
            var failed: Outcome? = null
            for (index in 1..args.times) {
                val startedAt = clock()
                val ran = try {
                    engine.run(args, index, last = index == args.times)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warning("Debug rerun $step of $id $index/${args.times} failed: ${e.javaClass.simpleName}")
                    failed = Outcome.Failed(index, e.javaClass.simpleName)
                    break
                }
                made += ran
                log("Debug rerun $step of $id $index/${args.times}: ${ran.line()} in ${clock() - startedAt} ms")
            }
            failed
        }
        // Only the runs' own calls carry the sampling.
        val failed = if (args.temperature == null) work() else withContext(AiSampling(args.sampling)) { work() }
        if (failed != null) return failed
        // The last run of the to-dos stored its finds itself, as Find again does.
        val stored = args.store && when (val last = made.last()) {
            is FinishingStepRerun.Ran.Todos -> last.outcome is ExtractSessionTodosUseCase.Outcome.Found
            else -> engine.store(args, last)
        }
        log("Debug rerun $step of $id done, stored ${if (stored) "the last run" else "nothing"}")
        return Outcome.Done(made, stored)
    }

    private fun refuse(sessionId: String, why: String): Outcome {
        warning("Debug rerun of $sessionId refused: $why")
        return Outcome.Refused(why)
    }

    private fun warning(line: String) = (warn ?: log)(line)

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE
}

/**
 * The rerun's model work on the app's own AI, by the finishing's paths: the live model registered
 * as the fallback, then Gemma 4 E2B through [LoadFinishingModelUseCase] with the room every load of
 * it keeps, else the live model, loaded before the first run; or the one model the rerun asks for
 * ([DebugRerun.Args.Model]). The steps' use cases come from [stepRerun], made once per rerun with the
 * answers' tags of its step ([RerunAnswerLog]). A reading's lines go to its answer file
 * ([FinishingStepRerun.Ran.Readings.answer], tag `rerun-reading-<run>-lines`, or
 * `rerun-reading-<run>-windows` for windows of lines), never to the log.
 *
 * @param kept Where the raw answers are kept: the debug build's files
 * @param livePath The live model's file, which the finishing registers as its fallback
 * @param readingsOf Makes the readings a recording lacks, as the finishing has them (0.8.0,
 *   EnsureReadingsUseCase)
 */
internal class AppRerunEngine(
    private val llm: LlmRepository,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val transcriptionRepository: TranscriptionRepository,
    private val kept: AnswerLog,
    private val livePath: suspend () -> String?,
    private val readingsOf: suspend (sessionId: String) -> EnsureReadingsUseCase.Outcome,
    private val stepRerun: (RerunAnswerLog) -> FinishingStepRerun
) : DebugRerun.Engine {

    override var notLoaded: String = ""
        private set

    private var made: Pair<FinishingStepRerun, RerunAnswerLog>? = null

    override suspend fun exists(sessionId: String): Boolean = transcriptionRepository.getSession(sessionId).first() != null

    override suspend fun readings(sessionId: String): String? =
        when (val readings = readingsOf(sessionId)) {
            EnsureReadingsUseCase.Outcome.Ready -> null
            is EnsureReadingsUseCase.Outcome.Missing -> readings.why.name.lowercase().replace('_', ' ')
        }

    override suspend fun load(model: DebugRerun.Args.Model?): String? {
        val path = livePath() ?: return null.also { notLoaded = "no live model on the phone" }
        llm.initialize(path, loadImmediately = false).exceptionOrNull()?.let {
            notLoaded = "the live model couldn't be registered (${it.javaClass.simpleName})"
            return null
        }
        if (model == DebugRerun.Args.Model.ONE_B) return loadLive()?.let { "the live model" }
        val loaded = loadFinishingModel()
        if (loaded == FinishingModel.Switched) return "Gemma 4 E2B"
        if (model == DebugRerun.Args.Model.E2B) {
            notLoaded = when (loaded) {
                is FinishingModel.FellBack -> "Gemma 4 E2B not loaded (${loaded.cause.javaClass.simpleName})"
                else -> "Gemma 4 E2B is switched off or not on the phone"
            }
            return null
        }
        loadLive() ?: return null
        return when (loaded) {
            is FinishingModel.FellBack -> "the live model, Gemma 4 E2B not loaded (${loaded.cause.javaClass.simpleName})"
            else -> "the live model"
        }
    }

    /** The live model put in memory: [Unit], or null with [notLoaded] saying why not. */
    private suspend fun loadLive(): Unit? {
        loadFinishingModel.loadLive().exceptionOrNull()?.let {
            notLoaded = "the live model didn't load (${it.javaClass.simpleName})"
            return null
        }
        return Unit
    }

    override suspend fun run(args: DebugRerun.Args.Rerun, index: Int, last: Boolean): FinishingStepRerun.Ran {
        val (rerun, answers) = made ?: RerunAnswerLog(kept, args.step.id).let { answers ->
            answers.begin(args.sessionId)
            (stepRerun(answers) to answers).also { made = it }
        }
        answers.run = index
        val ran = rerun.run(args.sessionId, args.step, args.from, stores = args.store && last, reader = args.reader, window = args.window)
        if (ran is FinishingStepRerun.Ran.Readings) answers.answer(args.sessionId, if (args.window > 1) READING_WINDOWS else READING_LINES, ran.answer())
        return ran
    }

    override suspend fun store(args: DebugRerun.Args.Rerun, ran: FinishingStepRerun.Ran): Boolean =
        made?.first?.store(args.sessionId, ran) ?: false

    override suspend fun release() {
        llm.cleanup()
        llm.forgetModel()
    }

    private companion object {
        /** A reading's answer file's call: `rerun-reading-<run>-lines` under the rerun's tags. */
        const val READING_LINES = "reading-lines"

        /** The same of a reading in windows of lines (design D1): `rerun-reading-<run>-windows`. */
        const val READING_WINDOWS = "reading-windows"
    }
}

/** The temperature as adb gives it, `--es temp 0.3` or `--ef temp 0.3`, as a string; null without one. */
internal fun temperatureExtra(asString: String?, hasExtra: Boolean, asFloat: () -> Float): String? =
    asString ?: if (hasExtra) asFloat().toString() else null
