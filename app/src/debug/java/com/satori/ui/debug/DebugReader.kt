package com.satori.ui.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.satori.data.config.ReaderModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.PinnedFileHash
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.di.readerDataSource
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NetworkPolicy
import com.satori.domain.firstrun.NetworkVerdict
import com.satori.domain.firstrun.NetworkWatch
import com.satori.domain.model.DownloadException
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.reading.PieceIds
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.SttRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.feature.reader.ReaderArm
import com.satori.feature.reader.ReaderDataSource
import com.satori.feature.reader.PackedBytes
import com.satori.feature.reader.ReaderFiles
import com.satori.feature.reader.ReaderNatives
import com.satori.feature.reader.ReaderRefusedException
import com.satori.feature.reader.ReaderYieldedException
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.reader.ReaderEngine
import com.satori.reader.ReaderException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/**
 * Debug builds only: the reader's three tools over adb (0.8.0, R8), each run as the memtest is
 * ([OneRun], [DebugRuns]): a fold or a turn gets the run under way back, its screen gone to the
 * back ends nothing, and an activity restored after its process ended starts none.
 *
 * - `--es action reader-download [--es variant int8|fp16] [--es mobile true]` ([DebugReaderDownload]).
 * - `--es action tokenizer-selftest [--es variant ...] [--es input v0b_inputs.tsv] [--es out <name>]`
 *   ([TokenizerSelftest]).
 * - `--es action reader-selftest [--es variant ...] [--es input <lines>] [--es arm prepack=session|all|off,arena=on|off,threads=2|4]
 *   [--ei beam 1-8] [--es worstcase true] [--es cold true] [--es firststep true] [--ei lines N] [--es out <name>]
 *   [--ei stopafter <ms> | --ei stopload <ms>]` ([ReaderSelftest]; a stop of its own, [ReaderStop]).
 * - Either selftest takes `--es dir <folder>`: a toy model in a folder of `files/debug/reader` instead of the
 *   build (an emulator's run of the native path; `~/satori-audit/tools/reading/toy_reader.py`).
 *
 * Inputs and outputs are in `files/debug/reader` ([ReaderSelftestFiles]). Lines under DebugTools,
 * counts, timings and file names only; the reader's own come under SatoriReader and SatoriLoads.
 *
 * @param pacing The app's pace of every model load, for a stop run's reader ([watchedReader])
 * @param sttRepository The capture and the live speech model, for a stop run's reader
 */
@HiltViewModel
class DebugReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: ReaderDataSource,
    private val downloads: ModelDownloadManager,
    private val network: NetworkWatch,
    private val llmRepository: LlmRepository,
    private val inferenceEngine: InferenceEngine,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val activeRecorder: ActiveRecorder,
    private val processingService: LlmProcessingServiceController,
    private val pacing: ModelLoadPacing,
    private val sttRepository: SttRepository,
    private val nativeHeap: NativeHeap
) : DebugRunViewModel() {

    // RssAnon before the load and after the release, each read the same way (SelftestMemory).
    private val memory = SelftestMemory(RssAnon::mb, purge = nativeHeap::purge)

    /** The run of [action] with the intent's [extras], or null when none runs. Main thread only. */
    internal fun start(action: String, extras: Map<String, String?>, firstLaunch: Boolean): DebugRun? = once.start(firstLaunch) { started ->
        try {
            when (action) {
                DOWNLOAD -> download(extras, started)
                TOKENIZER -> tokenizerSelftest(extras, started)
                else -> readerSelftest(extras, started)
            }
        } catch (e: CancellationException) {
            val then = if (action == DOWNLOAD) "what came stays for the next run" else "the reader is let go"
            Log.w(TAG, "${title(action)} cancelled: the screen was closed; $then")
            throw e
        }
    }

    private suspend fun download(extras: Map<String, String?>, started: () -> Unit) {
        val args = DebugReaderArgs.download(extras)
        if (args !is DebugReaderArgs.Download) {
            Log.w(TAG, "Reader download refused: ${(args as DebugReaderArgs.Wrong).why}")
            return
        }
        DebugReaderDownload(activeRecorder.current, network.facts, processingService, ReaderDownloads.of(downloads), warn = { Log.w(TAG, it) }) {
            Log.i(TAG, it)
        }.run(args.model, args.mobile, started)
    }

    private suspend fun tokenizerSelftest(extras: Map<String, String?>, started: () -> Unit) {
        val args = DebugReaderArgs.tokenizer(extras)
        if (args !is DebugReaderArgs.Tokenizer) {
            Log.w(TAG, "Tokenizer selftest refused: ${(args as DebugReaderArgs.Wrong).why}")
            return
        }
        val name = "Tokenizer selftest ${args.dir ?: args.model.variant}"
        val outcome = exclusive(TOKENIZER).hold(started) {
            val dir = folder() ?: return@hold SelftestEnded.Refused("no external files folder")
            val input = ReaderSelftestFiles.inFolder(dir, args.input) ?: return@hold SelftestEnded.Refused("no input file ${args.input}")
            if (!input.isFile) return@hold SelftestEnded.Refused("no input file ${input.path}; push one there")
            val out = ReaderSelftestFiles.inFolder(dir, args.out) ?: return@hold SelftestEnded.Refused("no output file ${args.out}")
            val files = if (args.dir == null) {
                val pinned = listOf(ReaderModels.SOURCE_PIECES, ReaderModels.SOURCE_VOCABULARY, ReaderModels.TARGET_VOCABULARY)
                val wrong = downloads.hashReaderModel(args.model, pinned).filter { !it.matches }
                if (wrong.isNotEmpty()) return@hold SelftestEnded.Refused("${wrong.joinToString { it.name }} not the pinned file (SHA-256); run reader-download")
                Log.i(TAG, "$name: ${pinned.joinToString()} are the pinned files (SHA-256)")
                reader.files(args.model)
            } else {
                toyFiles(dir, args.dir) ?: return@hold SelftestEnded.Refused("no folder ${args.dir} in ${dir.path}")
            }
            val (columns, rows) = withContext(Dispatchers.IO) { ReaderSelftestFiles.rows(input.readText()) }
            if (!letAiModelGo(name)) return@hold SelftestEnded.Refused("the AI model didn't leave memory")
            val summary = ReaderSelftestFiles.writing(out) { writer ->
                writer.append("# tokenizer-selftest ${files.name} on ${input.name}: SentencePiece's ${ReaderModels.SOURCE_PIECES} ")
                    .append("and ${ReaderModels.SOURCE_VOCABULARY} as on the phone, Satori's own preprocessing\n")
                reader.withTokenizer(files) { tokenizer ->
                    TokenizerSelftest(SystemClock::elapsedRealtime).run(columns, rows, tokenizer, writer)
                }
            }
            SelftestEnded.Ran(
                "done in ${summary.ms} ms: ${summary.lines} lines, ${summary.pieces} pieces, ${summary.unknown} unknown in " +
                    "${summary.linesWithUnknown} lines, ${summary.longLines} lines over ${PieceIds.MAX_SOURCE_PIECES} pieces; " +
                    "preprocessing as given on ${summary.matched} of ${summary.compared} lines; output ${out.name}"
            )
        }
        report(name, outcome)
    }

    private suspend fun readerSelftest(extras: Map<String, String?>, started: () -> Unit) {
        val args = DebugReaderArgs.reader(extras)
        if (args !is DebugReaderArgs.Reader) {
            Log.w(TAG, "Reader selftest refused: ${(args as DebugReaderArgs.Wrong).why}")
            return
        }
        val name = "Reader selftest ${args.dir ?: args.model.variant}"
        val outcome = exclusive(READER).hold(started) {
            val dir = folder() ?: return@hold SelftestEnded.Refused("no external files folder")
            val input = ReaderSelftestFiles.inFolder(dir, args.input) ?: return@hold SelftestEnded.Refused("no input file ${args.input}")
            if (!input.isFile) return@hold SelftestEnded.Refused("no input file ${input.path}; push one there")
            val out = ReaderSelftestFiles.inFolder(dir, args.out) ?: return@hold SelftestEnded.Refused("no output file ${args.out}")
            val lines = withContext(Dispatchers.IO) { ReaderSelftestFiles.lines(input.readText()) }.let { all -> args.lines?.let(all::take) ?: all }
            if (!letAiModelGo(name)) return@hold SelftestEnded.Refused("the AI model didn't leave memory")
            val files = if (args.dir == null) reader.files(args.model) else toyFiles(dir, args.dir) ?: return@hold SelftestEnded.Refused("no folder ${args.dir} in ${dir.path}")
            if (args.cold) {
                val dropped = files.loadFiles.count { inferenceEngine.dropCachedPages(it.path) }
                Log.i(TAG, "$name: cached pages dropped first for $dropped of ${files.loadFiles.size} files")
            }
            Log.i(TAG, "$name: ${if (args.worstCase) "the worst case from" else "${lines.size} lines of"} ${input.name}, ${args.label}")
            ReaderSelftestRun(reader, stopReader = ::watchedReader, memory) { Log.i(TAG, it) }.run(name, args, files, lines, input.name, out)
        }
        report(name, outcome)
    }

    /**
     * Never beside the AI model: a selftest holds the AI work gate, so nothing loads it again
     * meanwhile. False when it didn't leave memory.
     */
    private suspend fun letAiModelGo(name: String): Boolean {
        if (!llmRepository.isModelInMemory) return true
        llmRepository.cleanup()
        if (llmRepository.isModelInMemory) return false
        Log.i(TAG, "$name: the AI model was let go first")
        return true
    }

    /**
     * A stop run's reader ([ReaderSelftestRun]): the app's, wired as AppModule wires it
     * ([readerDataSource]), on [natives], lib-reader-onnx's watched ([WatchedNatives]), so the run
     * sees where its stop found the reader.
     */
    private fun watchedReader(natives: ReaderNatives): ReaderDataSource =
        readerDataSource(context, downloads, pacing, sttRepository, llmRepository, nativeHeap, natives)

    /**
     * The folder [name] in the selftests' folder [dir], holding a toy model under a build's file
     * names (`~/satori-audit/tools/reading/toy_reader.py`), for an emulator's run of the whole native
     * path without the model: not the registry's files, so neither their sizes nor their SHA-256 are
     * checked, only that each file a load reads is there; no parse room, the toy is small.
     */
    private suspend fun toyFiles(dir: File, name: String): ReaderFiles? = withContext(Dispatchers.IO) {
        val folder = ReaderSelftestFiles.inFolder(dir, name)?.takeIf { it.isDirectory } ?: return@withContext null
        Log.i(TAG, "Reader files from the folder ${folder.name}: a toy model, not the registry's, so not checked against it")
        val needed = listOf(
            ReaderModels.ENCODER, ReaderModels.DECODER, ReaderModels.DECODER_WITH_PAST, ReaderModels.SOURCE_PIECES,
            ReaderModels.SOURCE_VOCABULARY, ReaderModels.TARGET_VOCABULARY, ReaderModels.CONFIG
        )
        val present = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        ReaderFiles(folder.name, folder, PackedBytes(0L, 0L, 0L), present) {
            if (needed.all { File(folder, it).isFile }) ModelFileState.Intact else ModelFileState.Missing
        }
    }

    // The rules of the other debug runs, with this tool's holder.
    private fun exclusive(holder: String) = DebugExclusive(activeRecorder.current, aiGate, finishingQueue.busy, processingService, holder)

    private suspend fun folder(): File? = withContext(Dispatchers.IO) {
        context.getExternalFilesDir("debug")?.let { File(it, ReaderSelftestFiles.FOLDER).apply { mkdirs() } }
    }

    // How a selftest ended ([SelftestEnded.lines]): one line; its own stop's after its memory figures.
    private fun report(name: String, outcome: DebugExclusive.Run<SelftestEnded>) {
        for (line in SelftestEnded.lines(name, outcome)) {
            if (line.warn) Log.w(TAG, line.text) else Log.i(TAG, line.text)
        }
    }

    private fun title(action: String): String = when (action) {
        DOWNLOAD -> "Reader download"
        TOKENIZER -> "Tokenizer selftest"
        else -> "Reader selftest"
    }

    companion object {
        const val DOWNLOAD = "reader-download"
        const val TOKENIZER = "tokenizer-selftest"
        const val READER = "reader-selftest"

        private const val TAG = "DebugTools"
    }
}

/** This process's RssAnon, read from /proc/self/status alone, for the selftest's peak. */
internal object RssAnon {
    const val SAMPLE_MS = 100L

    fun mb(): Long? = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("RssAnon:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1_024)
    } catch (e: IOException) {
        null
    }
}

/**
 * The reader selftest's RssAnon before the load and after the release, read the same way, so that
 * V1's "after the release and purge, within 50 MB of its baseline" compares like with like ([settle]):
 * the Java heap collected and the native heap purged, then RssAnon read. Before the load that
 * gives back what an earlier release left in the allocator (the AI model's free purges nothing,
 * and the selftest lets the AI model go just before); after the release, the vocabularies' maps
 * the reader dropped, about 16 MB of Java heap for the real ones, which no collection had taken
 * yet. The release's own purge comes before either.
 *
 * @param rssAnonMb This process's RssAnon in MB ([RssAnon.mb]); null when it can't be read
 * @param collect A collection of the Java heap: [Runtime.gc], which runs one on ART before it
 *   returns, where System.gc() may collect nothing
 * @param purge The native heap's purge (the app's [NativeHeap], mallopt's M_PURGE_ALL)
 */
internal class SelftestMemory(
    private val rssAnonMb: () -> Long?,
    private val collect: () -> Unit = { Runtime.getRuntime().gc() },
    private val purge: () -> Purge
) {
    /** RssAnon before the collection and purge ([unsettledMb]) and after them ([mb]), and how far the purge went. */
    data class Settled(val unsettledMb: Long?, val mb: Long?, val purged: Purge)

    /** Reads RssAnon, collects the Java heap, purges the native heap and reads it again. Blocking; never throws for the purge. */
    fun settle(): Settled {
        val unsettled = rssAnonMb()
        collect()
        val purged = try {
            purge()
        } catch (e: RuntimeException) {
            Purge.NONE
        } catch (e: LinkageError) {
            // libai-chat.so, which holds the call, didn't load: nothing was given back.
            Purge.NONE
        }
        return Settled(unsettled, rssAnonMb(), purged)
    }

    companion object {
        /** `RssAnon 455 -> 412 MB, the Java heap collected and the native heap purged (M_PURGE_ALL)`, for the log. */
        fun settledLine(settled: Settled): String =
            "RssAnon ${settled.unsettledMb ?: "n/a"} -> ${settled.mb ?: "n/a"} MB, the Java heap collected and the native heap " +
                (settled.purged.call?.let { "purged ($it)" } ?: "not purged")

        /**
         * The summary's memory part, the figures V1 compares: `RssAnon 412 MB before the load, peak
         * 655 MB, 418 MB after the release (431 MB before its collection and purge)`, each of the two
         * ends after a collection and a purge; n/a for a figure that couldn't be read.
         */
        fun line(before: Settled, peakMb: Long, after: Settled): String =
            "RssAnon ${before.mb ?: "n/a"} MB before the load, peak $peakMb MB, ${after.mb ?: "n/a"} MB after the release " +
                "(${after.unsettledMb ?: "n/a"} MB before its collection and purge)"
    }
}

/**
 * A reader selftest's or download's arguments, from the intent's extras as strings, checked; or
 * why they make none ([Wrong]).
 */
internal sealed interface DebugReaderArgs {
    data class Wrong(val why: String) : DebugReaderArgs

    data class Download(val model: ReaderModelId, val mobile: Boolean) : DebugReaderArgs

    /** [dir]: a folder of a toy model in the selftests' folder instead of the build's own (an emulator's run). */
    data class Tokenizer(val model: ReaderModelId, val input: String, val out: String, val dir: String? = null) : DebugReaderArgs

    /** [stop]: a stop the run makes itself, `stopafter` or `stopload` ([ReaderStop]); none by default. */
    data class Reader(
        val model: ReaderModelId,
        val input: String,
        val out: String,
        val arm: ReaderArm,
        val beam: Int,
        val worstCase: Boolean,
        val cold: Boolean,
        val firstStep: Boolean,
        val lines: Int?,
        val dir: String? = null,
        val stop: ReaderStop? = null
    ) : DebugReaderArgs {
        /** The run's settings for its lines: `prepack=session,arena=on,threads=4, beam 4, cold`. */
        val label: String
            get() = "${arm.label}, beam $beam" + (if (cold) ", cold" else "") + (if (firstStep) ", first steps" else "") +
                (if (worstCase) ", worst case" else "") + (lines?.let { ", at most $it lines" } ?: "") +
                (stop?.let { ", ${it.label}" } ?: "")
    }

    companion object {
        const val V0B_INPUT = "v0b_inputs.tsv"
        const val READER_INPUT = "flores200_devtest.mal.txt"

        fun download(extras: Map<String, String?>): DebugReaderArgs {
            val model = model(extras) ?: return wrongVariant(extras)
            val mobile = flag(extras, "mobile") ?: return Wrong("mobile takes true or false")
            return Download(model, mobile)
        }

        fun tokenizer(extras: Map<String, String?>): DebugReaderArgs {
            val model = model(extras) ?: return wrongVariant(extras)
            val dir = extras["dir"]?.trim()
            return Tokenizer(model, extras["input"]?.trim() ?: V0B_INPUT, extras["out"]?.trim() ?: "v0b-${dir ?: model.variant}.tsv", dir)
        }

        fun reader(extras: Map<String, String?>): DebugReaderArgs {
            val model = model(extras) ?: return wrongVariant(extras)
            val arm = when (val parsed = ReaderArm.parse(extras["arm"])) {
                is ReaderArm.Parsed.Arm -> parsed.arm
                is ReaderArm.Parsed.Wrong -> return Wrong(parsed.why)
            }
            val beam = extras["beam"]?.trim()?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..ReaderEngine.MAX_BEAM } ?: return Wrong("beam takes 1-${ReaderEngine.MAX_BEAM}, not $text")
            } ?: ReaderEngine.DEFAULT_BEAM
            val worstCase = flag(extras, "worstcase") ?: return Wrong("worstcase takes true or false")
            val cold = flag(extras, "cold") ?: return Wrong("cold takes true or false")
            val firstStep = flag(extras, "firststep") ?: return Wrong("firststep takes true or false")
            val lines = extras["lines"]?.trim()?.let { text -> text.toIntOrNull()?.takeIf { it > 0 } ?: return Wrong("lines takes a count, not $text") }
            val after = extras[ReaderStop.Kind.AFTER.key]?.trim()
            val load = extras[ReaderStop.Kind.LOAD.key]?.trim()
            if (after != null && load != null) return Wrong("stopafter and stopload don't go together")
            val stop = (after?.let { ReaderStop.Kind.AFTER to it } ?: load?.let { ReaderStop.Kind.LOAD to it })?.let { (kind, text) ->
                ReaderStop(kind, text.toIntOrNull()?.takeIf { it >= 0 } ?: return Wrong("${kind.key} takes a number of ms, not $text"))
            }
            val dir = extras["dir"]?.trim()
            val input = extras["input"]?.trim() ?: READER_INPUT
            val out = extras["out"]?.trim() ?: readingsName(dir ?: model.variant, arm, beam, worstCase, firstStep, lines, input, stop, cold)
            return Reader(model, input, out, arm, beam, worstCase, cold, firstStep, lines, dir, stop)
        }

        /**
         * A reader selftest's own output name: every setting a run of the same model and arm could
         * differ in is part of it, so one run's file never takes another's name, e.g.
         * `readings-int8-prepack-session-arena-on-threads-4-beam4-cold.tsv` (V2's run) and
         * `readings-int8-prepack-session-arena-on-threads-4-beam4-firststep-lines200-cold.tsv`; an
         * input other than FLORES' devtest adds its name without `.txt` and `.mal`, its other points
         * made dashes (`-smoke` for smoke.mal.txt, `-v2b-noise` for v2b.noise.mal.txt, so two inputs
         * that share a stem keep their files apart), and a stop of the run's own its key and ms
         * (`-stopafter2000`; such a run writes its file only when its reading ended before the stop).
         */
        private fun readingsName(
            model: String,
            arm: ReaderArm,
            beam: Int,
            worstCase: Boolean,
            firstStep: Boolean,
            lines: Int?,
            input: String,
            stop: ReaderStop?,
            cold: Boolean
        ): String {
            val settings = arm.label.replace('=', '-').replace(',', '-')
            val inputPart = if (input == READER_INPUT) "" else "-" + input.removeSuffix(".txt").removeSuffix(".mal").replace('.', '-')
            return "readings-$model-$settings-beam$beam" + (if (worstCase) "-worstcase" else "") + (if (firstStep) "-firststep" else "") +
                (lines?.let { "-lines$it" } ?: "") + inputPart + (stop?.let { "-${it.kind.key}${it.ms}" } ?: "") + (if (cold) "-cold" else "") + ".tsv"
        }

        private fun model(extras: Map<String, String?>): ReaderModelId? = ReaderModelId.ofVariant(extras["variant"])

        private fun wrongVariant(extras: Map<String, String?>) = Wrong("no variant ${extras["variant"]}, only int8 or fp16")

        // true or false, false for none; null for anything else.
        private fun flag(extras: Map<String, String?>, name: String): Boolean? = when (extras[name]?.trim()) {
            null, "false" -> false
            "true" -> true
            else -> null
        }
    }
}

/**
 * The rules every debug run that uses the AI's room keeps ([DebugMemtest], [DebugRerun]): it starts
 * only while no recorder is busy, no recording waits to be finished and nobody holds the AI work
 * gate; it holds the gate and the AI's service ([holder]) to its end, the service so the process
 * stays in the foreground group with the screen off; and a recording that starts meanwhile wins:
 * the work is cancelled, and this returns only once it has ended, the reader released. The work
 * says how it ended itself, in a line ([Run.Done]); what it throws is [Run.Failed].
 *
 * @param serviceWaitMs How long the work waits for the service to be in the foreground first
 */
internal class DebugExclusive(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val gate: AiWorkGate,
    private val finishingBusy: StateFlow<Boolean>,
    private val processing: LlmProcessingServiceController,
    private val holder: String,
    private val serviceWaitMs: Long = DebugMemtest.SERVICE_WAIT_MS
) {
    /** How a run ended. */
    sealed interface Run<out T> {
        data class Refused(val why: String) : Run<Nothing>
        data object RecorderStarted : Run<Nothing>

        /** The work threw; [line] says what, for the log. */
        data class Failed(val line: String) : Run<Nothing>

        /** The work ended with [value]; [inForeground] whether the service was in the foreground for it. */
        data class Done<T>(val value: T, val inForeground: Boolean) : Run<T>
    }

    /** Runs [work] under the rules; [onStarted] once it is past them and holds the AI's service. */
    suspend fun <T> hold(onStarted: () -> Unit = {}, work: suspend () -> T): Run<T> {
        if (recorder.value.busy()) return Run.Refused("a recorder is busy")
        if (finishingBusy.value || !gate.tryEnter(holder)) return Run.Refused("the AI is busy")
        try {
            // Inside the try, so a refused start still ends below.
            val accepted = processing.startProcessing(holder)
            onStarted()
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val done = async {
                    val inForeground = accepted && withTimeoutOrNull(serviceWaitMs) { processing.protected.first { it } } != null
                    try {
                        Run.Done(work(), inForeground)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Run.Failed(failure(e))
                    }
                }
                select<Run<T>> {
                    done.onAwait { it }
                    started.onAwait { Run.RecorderStarted }
                }.also {
                    started.cancel()
                    done.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                processing.stopProcessing(holder)
                gate.exit(holder)
            }
        }
    }

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE

    companion object {
        /** The line of a run that threw [e]: names, statuses and shapes, never text. */
        internal fun failure(e: Exception): String = when (e) {
            is ReaderYieldedException -> "stopped: a capture opened; the reader is let go"
            is ReaderRefusedException -> "refused: ${e.refusal.because}"
            is LoadStoppedForMemoryException -> "not loaded: the phone stayed short of memory for 10 s (${e.javaClass.simpleName})"
            is ReaderException -> "failed: ${e.javaClass.simpleName}: ${e.message}"
            // The parser's message quotes the file it read.
            is SerializationException -> "failed: a file that is no JSON (${e.javaClass.simpleName})"
            is IllegalArgumentException -> "failed: the input: ${e.message}"
            else -> "failed: ${e.javaClass.simpleName}"
        }
    }
}

/**
 * Debug builds only: the reader's download from adb (`--es action reader-download [--es variant
 * int8|fp16] [--es mobile true]`): the pinned files of the build fetched on the phone into its
 * folder, as the app's own download does it, then each file's SHA-256 checked against its pin, a
 * file kept from an earlier run too; a file that isn't the pinned one is deleted. Wi-Fi only,
 * unless `mobile` is true; never beside a recorder or another reader download, and a recording that
 * starts meanwhile, or the network turning metered, stops it (what came stays for the next run). It
 * holds the AI's service, so the process stays in the foreground group with the screen off; it
 * loads no model.
 *
 * @param downloads The app's download of the reader ([ReaderDownloads.of] its ModelDownloadManager)
 * @param log Where its lines go, under DebugTools
 */
internal class DebugReaderDownload(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val network: StateFlow<NetworkFacts>,
    private val processing: LlmProcessingServiceController,
    private val downloads: ReaderDownloads,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val warn: (String) -> Unit,
    private val log: (String) -> Unit
) {
    /** How a download ended. */
    sealed interface Outcome {
        data class Refused(val why: String) : Outcome
        data class Stopped(val why: String) : Outcome
        data class Failed(val why: String) : Outcome
        data class Done(val files: Int, val bytes: Long, val ms: Long) : Outcome
    }

    /** The download of [model]; [onStarted] once it is past its refusals and holds the AI's service. */
    suspend fun run(model: ReaderModelId, mobileAllowed: Boolean, onStarted: () -> Unit = {}): Outcome {
        val name = "Reader download ${model.variant}"
        if (recorder.value.busy()) return refused(name, "a recorder is busy")
        when (NetworkPolicy.verdict(network.value, mobileAllowed)) {
            NetworkVerdict.WAIT_FOR_NETWORK -> return refused(name, "no network")
            NetworkVerdict.WAIT_FOR_WIFI -> return refused(name, "Wi-Fi only, and the network is metered (--es mobile true allows mobile data)")
            NetworkVerdict.GO -> Unit
        }
        // One at a time: a second adb start while one runs, its screen gone, would write the same files.
        if (!underWay.compareAndSet(false, true)) return refused(name, "another reader download is under way")
        try {
            processing.startProcessing(HOLDER)
            onStarted()
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val metered = async { network.first { NetworkPolicy.verdict(it, mobileAllowed) != NetworkVerdict.GO } }
                val done = async { download(name, model, mobileAllowed) }
                select<Outcome> {
                    done.onAwait { it }
                    started.onAwait { Outcome.Stopped("a recording started") }
                    metered.onAwait { Outcome.Stopped(if (it.connected) "the network is metered now" else "the network is gone") }
                }.also {
                    started.cancel()
                    metered.cancel()
                    done.cancel()
                    if (it is Outcome.Stopped) warn("$name stopped: ${it.why}; what came stays for the next run")
                }
            }
        } finally {
            withContext(NonCancellable) { processing.stopProcessing(HOLDER) }
            underWay.set(false)
        }
    }

    private suspend fun download(name: String, model: ReaderModelId, mobileAllowed: Boolean): Outcome {
        val spec = ReaderModels.of(model)
        log(
            "$name: ${spec.files.size} files, ${mb(spec.bytes)} MB, ${mb(downloads.readerModelBytesOnPhone(model))} MB on the phone already; " +
                if (mobileAllowed) "mobile data allowed" else "Wi-Fi only"
        )
        val started = clock()
        var told = 0L
        try {
            downloads.downloadReaderModel(model).collect { progress ->
                val now = clock()
                if (now - told >= PROGRESS_MS || progress.percentage == 100) {
                    told = now
                    log("$name: ${mb(progress.bytesDownloaded)} MB of ${mb(progress.totalBytes)} MB (${progress.percentage}%)" +
                        if (progress.verifying) ", checking a file" else "")
                }
            }
        } catch (e: DownloadException) {
            return Outcome.Failed("${e.failure} (${e.message})").also { warn("$name failed after ${clock() - started} ms: ${it.why}") }
        }
        val hashes = downloads.hashReaderModel(model)
        val wrong = hashes.filter { !it.matches }
        for (hash in wrong) {
            warn("$name: ${hash.name} has SHA-256 ${hash.sha256 ?: "none (no file)"}, not the pinned ${hash.pinned}; deleted")
            withContext(Dispatchers.IO) { File(downloads.readerModelDir(model), hash.name).delete() }
        }
        val ms = clock() - started
        if (wrong.isNotEmpty()) {
            return Outcome.Failed("${wrong.size} of ${hashes.size} files not the pinned ones").also { warn("$name failed after $ms ms: ${it.why}") }
        }
        log("$name done in $ms ms: ${hashes.size} of ${spec.files.size} files, ${spec.bytes} bytes, each SHA-256 the pinned one, in ${downloads.readerModelDir(model).name}")
        return Outcome.Done(hashes.size, spec.bytes, ms)
    }

    private fun refused(name: String, why: String): Outcome = Outcome.Refused(why).also { warn("$name refused: $why") }

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE

    companion object {
        /** The AI service's holder of a reader download. */
        const val HOLDER = "reader-download"

        /** How often a download says how far it has come. */
        const val PROGRESS_MS = 3_000L

        // Whether a reader download runs in this process: since 0.8.0-dev2 a run outlives its screen.
        private val underWay = AtomicBoolean(false)

        private fun mb(bytes: Long): Long = bytes / 1_000_000
    }
}

/**
 * What the reader's debug download asks of ModelDownloadManager ([of]): a seam, so the tests can
 * hold its stops and its last check to a download that is no HTTP one.
 */
internal interface ReaderDownloads {
    fun readerModelDir(model: ReaderModelId): File
    fun readerModelBytesOnPhone(model: ReaderModelId): Long
    fun downloadReaderModel(model: ReaderModelId): Flow<ModelDownloadManager.DownloadProgress>
    suspend fun hashReaderModel(model: ReaderModelId): List<PinnedFileHash>

    companion object {
        /** The app's own: [manager]'s reader download, the one the first run will plan. */
        fun of(manager: ModelDownloadManager): ReaderDownloads = object : ReaderDownloads {
            override fun readerModelDir(model: ReaderModelId): File = manager.readerModelDir(model)
            override fun readerModelBytesOnPhone(model: ReaderModelId): Long = manager.readerModelBytesOnPhone(model)
            override fun downloadReaderModel(model: ReaderModelId): Flow<ModelDownloadManager.DownloadProgress> = manager.downloadReaderModel(model)
            override suspend fun hashReaderModel(model: ReaderModelId): List<PinnedFileHash> = manager.hashReaderModel(model)
        }
    }
}
