package com.satori.ui.debug

import android.os.SystemClock
import com.satori.feature.reader.ReaderDataSource
import com.satori.feature.reader.ReaderFiles
import com.satori.feature.reader.ReaderNatives
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a reader selftest's own work ended (the tokenizer selftest's, [ReaderSelftestRun]'s): the
 * summary that follows its name ([Ran]), why it didn't run ([Refused]), or its own stop's last
 * line after the memory figures of the run it stopped ([Stopped]).
 */
internal sealed interface SelftestEnded {
    data class Ran(val line: String) : SelftestEnded
    data class Refused(val why: String) : SelftestEnded
    data class Stopped(val memory: String, val line: String) : SelftestEnded

    /** A line for the log under DebugTools, a W line when [warn]. */
    data class Line(val text: String, val warn: Boolean = false)

    companion object {
        /**
         * The lines that say how the selftest [name] ended, under its rules ([outcome]): one line
         * for done, refused, stopped by a recording, not loaded or failed; for a stop of its own,
         * the memory figures of the run it stopped, then its last line, exactly one.
         */
        fun lines(name: String, outcome: DebugExclusive.Run<SelftestEnded>): List<Line> = when (outcome) {
            is DebugExclusive.Run.Refused -> listOf(Line("$name refused: ${outcome.why}", warn = true))
            DebugExclusive.Run.RecorderStarted -> listOf(Line("$name stopped: a recording started; the reader is let go", warn = true))
            is DebugExclusive.Run.Failed -> listOf(Line("$name ${outcome.line}", warn = true))
            is DebugExclusive.Run.Done -> when (val ended = outcome.value) {
                is Ran -> listOf(Line("$name ${ended.line}; AI service ${if (outcome.inForeground) "in" else "not in"} the foreground"))
                is Refused -> listOf(Line("$name refused: ${ended.why}", warn = true))
                is Stopped -> listOf(Line("$name: ${ended.memory}"), Line(ended.line))
            }
        }
    }
}

/**
 * Debug builds only: a reader selftest's run of the reader (V1 and V2), once its rules and inputs
 * are through. RssAnon is settled and read before the load ([SelftestMemory]), its peak sampled
 * every 100 ms from then until after the release, the lines or the worst case read into the
 * output's file through the reader's data source with the memory lines of its load and release
 * after the rows, RssAnon settled and read again after the release, and the release's memory line
 * 10 s after it waited for, so it comes before the summary. A run with a stop of its own
 * ([DebugReaderArgs.Reader.stop]) reads through a reader of its own, the app's wiring on watched
 * native calls ([ReaderStopRun], [stopReader]), and gives the stop's last line instead of a summary
 * when the stop came; every other run reads through [reader], the app's own.
 *
 * @param reader The app's reader
 * @param stopReader The reader a stop run reads through: the app's wiring on the native calls given
 * @param memory RssAnon before the load and after the release, read alike
 * @param natives lib-reader-onnx's native calls, the ones a stop run watches
 * @param rssAnonMb This process's RssAnon now, for the peak
 * @param clock Milliseconds that never jump
 * @param pause Waits the given milliseconds, for the release's line 10 s after it
 * @param io Where the blocking reads of the memory run
 * @param log Where its lines go, under DebugTools
 */
internal class ReaderSelftestRun(
    private val reader: ReaderDataSource,
    private val stopReader: (ReaderNatives) -> ReaderDataSource,
    private val memory: SelftestMemory,
    private val natives: ReaderNatives = ReaderNatives.DEVICE,
    private val rssAnonMb: () -> Long? = RssAnon::mb,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String) -> Unit
) {
    /**
     * What the reader's own run read: its lines, or the worst case, or nothing (too few pieces for
     * it), or nothing as its own stop came ([Stopped], with the selftest's last line).
     */
    private sealed interface Read {
        data class Lines(val summary: ReaderSelftest.Summary) : Read
        data class Worst(val case: ReaderSelftest.WorstCase) : Read
        data class TooFew(val pieces: Int, val want: Int) : Read
        data class Stopped(val line: String) : Read
    }

    /** The selftest [name]'s run of [args] over [lines] of [inputName] with [files], written to [out]. */
    suspend fun run(
        name: String,
        args: DebugReaderArgs.Reader,
        files: ReaderFiles,
        lines: List<String>,
        inputName: String,
        out: File
    ): SelftestEnded = coroutineScope {
        val memoryLines = CopyOnWriteArrayList<String>()
        val before = withContext(io) { memory.settle() }
        log("$name: ${SelftestMemory.settledLine(before)} before the load")
        val peak = AtomicLong(before.mb ?: 0L)
        val sampler = launch(io) {
            while (isActive) {
                rssAnonMb()?.let { mb -> peak.accumulateAndGet(mb, ::maxOf) }
                delay(RssAnon.SAMPLE_MS)
            }
        }
        val selftest = ReaderSelftest(clock)
        val asked = clock()
        var loadMs = 0L
        val stopRun = args.stop?.let { ReaderStopRun(it, natives, clock) }
        // A stop run's reader is the app's on watched native calls; every other run's is the app's own.
        val source = stopRun?.let { stopReader(it.natives) } ?: reader
        val reading: suspend () -> Read = {
            ReaderSelftestFiles.writing(out) { writer ->
                writer.append("# reader-selftest ${files.name} on $inputName: ${args.label}\n")
                val read = source.withReader(files, args.arm, memoryLine = { memoryLines += it }) { loaded ->
                    loadMs = clock() - asked
                    stopRun?.readingBegins()
                    try {
                        if (args.worstCase) {
                            Read.Worst(selftest.worstCase(lines, loaded, args.beam, writer))
                        } else {
                            Read.Lines(selftest.lines(lines, loaded, args.beam, args.firstStep, writer))
                        }
                    } finally {
                        stopRun?.readingEnds()
                    }
                }
                memoryLines.forEach { writer.append("# ").append(it).append('\n') }
                read
            }
        }
        val read = try {
            if (stopRun == null) {
                reading()
            } else {
                when (val ended = stopRun.run(reading)) {
                    is ReaderStopRun.Ended.Read -> ended.value
                    is ReaderStopRun.Ended.Stopped -> Read.Stopped(ended.line)
                }
            }
        } catch (e: ReaderSelftest.TooFewPieces) {
            // Thrown inside the file's writing, so no file takes the output's name: an earlier one stays.
            Read.TooFew(e.pieces, e.want)
        } finally {
            sampler.cancel()
        }
        val after = withContext(io) { memory.settle() }
        // The release's memory line 10 s after it, for a reader of the log, before the summary.
        pause(DebugMemtest.SETTLE_MS)
        val memoryPart = SelftestMemory.line(before, peak.get(), after)
        // A stop run whose reading ended first is an ordinary one, and says why it wasn't stopped.
        val unstopped = stopRun?.let { "; ${it.stop.label} never came: the reading ended first" }.orEmpty()
        when (read) {
            is Read.Lines -> read.summary.let { r ->
                SelftestEnded.Ran(
                    "done: ${r.lines} lines (${r.inParts} in parts, ${r.empty} empty, ${r.capped} capped), ${r.pieces} pieces, ${r.tokens} tokens, " +
                        "${r.steps} decoder runs, in ${r.readMs} ms (${per(r.readMs, r.lines)} ms a line, ${r.msPerStep?.let(::oneDecimal) ?: "n/a"} " +
                        "ms a run, $FIRST_STEP_IN${r.firstSteps?.let(::firstStepsPart).orEmpty()}); load $loadMs ms; $memoryPart; ${args.label}; output ${out.name}" +
                        unstopped
                )
            }
            is Read.Worst -> read.case.let { w ->
                SelftestEnded.Ran(
                    "done, the worst case: ${w.pieces} pieces, ${w.tokens} tokens, ${w.steps} decoder runs${if (w.capped) ", capped" else ""}, in ${w.ms} ms " +
                        "(${oneDecimal(w.ms.toDouble() / maxOf(w.steps, 1))} ms a run, $FIRST_STEP_IN); load $loadMs ms; $memoryPart; ${args.label}; output ${out.name}" +
                        unstopped
                )
            }
            is Read.TooFew -> SelftestEnded.Refused("the worst case needs ${read.want} pieces, and $inputName's lines hold ${read.pieces}; no output written")
            is Read.Stopped -> SelftestEnded.Stopped(memoryPart, read.line)
        }
    }

    private companion object {
        // What a run's ms count besides decoder_with_past's step, said beside the figure: not D2's per-token one.
        const val FIRST_STEP_IN = "the encoder and first step included"

        fun per(ms: Long, n: Int): Long = if (n == 0) 0 else ms / n

        fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        // With first steps, the summary's figures of the lines' first steps and of the runs after them (D2's per-token figure).
        fun firstStepsPart(first: ReaderSelftest.FirstSteps): String =
            "; first steps ${oneDecimal(first.msPerLine)} ms a line, ${first.msPerLaterStep?.let(::oneDecimal) ?: "n/a"} ms a run after them"
    }
}
