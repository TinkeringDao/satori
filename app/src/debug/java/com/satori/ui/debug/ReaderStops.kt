package com.satori.ui.debug

import android.os.SystemClock
import com.satori.feature.reader.ReaderEngineCalls
import com.satori.feature.reader.ReaderNatives
import com.satori.feature.reader.ReaderTokenizerCalls
import com.satori.reader.ReaderDims
import com.satori.reader.ReaderGraphs
import com.satori.reader.ReaderOptions
import com.satori.reader.Reading
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug builds only: a stop the reader selftest makes itself (0.8.0-dev2), V1's stop timing
 * without a screen or a key. It is the cancel a recording's start makes ([ReaderStopRun]), at a
 * time of the run's own, so the data source's line gives the figure: `Reader <id> Run stopped <x>
 * ms after the cancel`, or `Reader <id> load stopped <x> ms after the cancel`.
 *
 * - [Kind.AFTER], `--ei stopafter <ms>`: [ms] after the reading began, the reader loaded and its
 *   first line's reading under way.
 * - [Kind.LOAD], `--ei stopload <ms>`: [ms] after the creation of the three sessions began, the
 *   part of the load that ONNX Runtime's load-cancellation flag stops (V1 measures that flag). The
 *   pre-read, the parse room, SentencePiece's model and the two vocabularies' parse come before it,
 *   and nothing interrupts the parse: a stop that landed there would time the parse's rest, not
 *   the flag.
 */
internal data class ReaderStop(val kind: Kind, val ms: Int) {
    enum class Kind(val key: String) {
        AFTER("stopafter"),
        LOAD("stopload")
    }

    /** Where the cancel found the reader, as the data source's stop reached its engine ([WatchedNatives]). */
    enum class Where { IN_LOAD, IN_RUN, BETWEEN_RUNS }

    /** `stopafter 2000`, as the run's label and lines say it. */
    val label: String get() = "${kind.key} $ms"

    /**
     * The selftest's last line once its stop came, e.g. `Reader selftest cancelled: stopafter 2000,
     * the cancel came inside a Run; the reader is let go`.
     */
    fun line(where: Where): String = "Reader selftest cancelled: $label, the cancel came ${place(where)}; the reader is let go"

    private fun place(where: Where): String = when (kind) {
        Kind.AFTER -> if (where == Where.IN_RUN) "inside a Run" else "between Runs"
        Kind.LOAD -> if (where == Where.IN_LOAD) "inside the load" else "after the load"
    }
}

/**
 * lib-reader-onnx's native calls ([natives]) as a stop run's data source makes them, watched: the
 * creation of the three sessions beginning, the engine's load, which the data source makes once
 * the pre-read, the parse room, SentencePiece's model and the vocabularies are done
 * ([sessionsBegin]); and where the reader was when the data source's stop reached the engine, its
 * first [ReaderEngineCalls.cancel] ([where]): still loading, in a Run, or loaded between Runs. The
 * data source's own `Run stopped` and `load stopped` lines go by that same stop, so the selftest's
 * line and theirs agree.
 */
internal class WatchedNatives(private val natives: ReaderNatives, private val sessionsBegin: () -> Unit) : ReaderNatives {
    @Volatile private var loaded = false
    @Volatile private var running = false
    private val stopped = AtomicReference<ReaderStop.Where?>(null)

    /** Where the first stop found the reader; null while none came. */
    val where: ReaderStop.Where? get() = stopped.get()

    override fun engine(): ReaderEngineCalls = Engine(natives.engine())

    override fun tokenizer(): ReaderTokenizerCalls = natives.tokenizer()

    private inner class Engine(private val engine: ReaderEngineCalls) : ReaderEngineCalls {
        override fun load(graphs: ReaderGraphs, options: ReaderOptions) {
            sessionsBegin()
            engine.load(graphs, options)
            loaded = true
        }

        override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            running = true
            try {
                return engine.translate(sourceIds, beam, maxNewTokens, minTokens)
            } finally {
                running = false
            }
        }

        override fun dims(): ReaderDims = engine.dims()

        override fun cancel() {
            val where = when {
                !loaded -> ReaderStop.Where.IN_LOAD
                running -> ReaderStop.Where.IN_RUN
                else -> ReaderStop.Where.BETWEEN_RUNS
            }
            stopped.compareAndSet(null, where)
            engine.cancel()
        }

        override fun release() = engine.release()
    }
}

/**
 * One reader selftest's reading with a stop of its own ([stop]). Run through [run], the reading is
 * cancelled once the stop is due, as a recording's start cancels a selftest (DebugExclusive): the
 * cancel reaches the engine through the data source's stoppable(), the reader is released and the
 * native heap purged as after every cancel, and the selftest's file goes with its `.partial`. Its
 * data source loads on [natives], lib-reader-onnx's watched ([WatchedNatives]), which say where the
 * cancel found the reader.
 *
 * The stop's clock starts as the reading begins ([readingBegins], `stopafter`) or as the creation
 * of the sessions does (`stopload`). A reading that ends before its stop is due ([readingEnds]) is
 * not stopped: it ends as any other.
 *
 * @param clock Milliseconds that never jump
 */
internal class ReaderStopRun(
    val stop: ReaderStop,
    natives: ReaderNatives,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private val from = CompletableDeferred<Long>()

    // ARMED until the stop is due (CAME) or the reading has ended (MISSED), whichever is first.
    private val state = AtomicInteger(ARMED)

    val natives = WatchedNatives(natives) { if (stop.kind == ReaderStop.Kind.LOAD) from.complete(clock()) }

    /** The reader is loaded and its reading begins: `stopafter`'s clock starts. */
    fun readingBegins() {
        if (stop.kind == ReaderStop.Kind.AFTER) from.complete(clock())
    }

    /** The reading has ended, read in full or not: a stop not due yet no longer comes. */
    fun readingEnds() {
        state.compareAndSet(ARMED, MISSED)
    }

    /** How a reading with a stop ended. */
    sealed interface Ended<out T> {
        /** It ended before its stop was due: what it read. */
        data class Read<T>(val value: T) : Ended<T>

        /** The stop came: the reading was cancelled and its reader released; [line] is the selftest's last. */
        data class Stopped(val line: String) : Ended<Nothing>
    }

    /**
     * Runs [reading] with the stop. What it throws is thrown as it is; a cancel of the caller (Back,
     * a recording that started) stays the caller's cancel.
     */
    suspend fun <T> run(reading: suspend () -> T): Ended<T> = coroutineScope {
        val read = async {
            // A failure reaches the caller as itself, never as this scope's cancel.
            try {
                Result.success(reading())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val timer = launch(Dispatchers.Default) {
            val at = from.await() + stop.ms
            delay(maxOf(0L, at - clock()))
            // The cancel a recording's start makes: through the data source's stoppable() to the engine.
            if (state.compareAndSet(ARMED, CAME)) read.cancel()
        }
        try {
            Ended.Read(read.await().getOrThrow())
        } catch (e: CancellationException) {
            if (!isActive || state.get() != CAME) throw e
            Ended.Stopped(stop.line(natives.where ?: ReaderStop.Where.BETWEEN_RUNS))
        } finally {
            timer.cancel()
        }
    }

    private companion object {
        const val ARMED = 0
        const val CAME = 1
        const val MISSED = 2
    }
}
