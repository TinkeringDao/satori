package com.satori.domain.usecase.finish

import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.reading.ReadingRules
import com.satori.domain.repository.LineNotReadException
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.LoadedReader
import com.satori.domain.repository.ReaderFailedException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.ReaderStoppedException
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A reading under way ([ReadingStep]): [stop] ends it at once, as a capture that opens does
 * (design D14): the reader's load or Run under way stops, the reader is released and the native
 * heap purged, and the readings stored so far stay. It may be called from any thread, before the
 * load begins too, and more than once; after the reading has ended it changes nothing.
 */
class ReadingRun(val stop: () -> Unit)

/**
 * The Reading step of the finishing (0.8.0, design 3.A and R5): the lines only the reader reads
 * ([ReadableSegments.needsReader], a Malayalam recording's), read into English one by one before the
 * AI steps, which then read the readings ([TranscriptionRepository.getReadableSegments]) with the
 * prompts of an English recording (D6). The transcript stays as it was said (D8).
 *
 * - It reads the complete lines that have no current reading ([ReadableSegments.unread]), in their
 *   order, and stores each reading as soon as it is made ([TranscriptionRepository.saveReading]): a
 *   reading cut short (a capture that opens, a process death) goes on from the next line. A line
 *   edited while it read is read once more at the end, while the reader is still loaded.
 * - D5 ([ReadingRules]): a fragment gets an empty reading without the model; the model's reading is
 *   emptied under the floor, outside the band, or when it repeats itself; a line the reader can't
 *   read gets an empty one too. An empty reading is stored like any other, so its line isn't read
 *   again, and the AI steps leave the line out.
 * - D15: the reader cuts a line at a word where its pieces would be more than the model takes, and
 *   caps each part's English ([LoadedReader.read]).
 * - The AI model is let go first, as the reader never loads beside it; then the reader loads at the
 *   app's pace and is released, the native heap purged, as soon as the lines are read
 *   ([ReaderRepository.withReader]). Lines that are all fragments load nothing.
 * - D14: [ReadingRun.stop], which the finisher calls when a capture starts, stops it at once, as the
 *   reader's own watch on the capture does ([ReaderStoppedException]): [Outcome.Stopped].
 *
 * The caller holds the AI work gate, as for any model load, and has seen that no capture is live.
 * Nothing here logs: [Counts] are the caller's to log, and hold no text.
 *
 * @property now The clock of [SegmentReading.createdAt] (epoch ms)
 * @property clock Nanoseconds that never jump, for [Counts.ms]
 */
class ReadingStep(
    private val transcriptionRepository: TranscriptionRepository,
    private val reader: ReaderRepository,
    private val llmRepository: LlmRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val clock: () -> Long = System::nanoTime
) {
    /**
     * What a reading came to, counts only: the [lines] it set out to read, the readings [kept] with
     * their words, those D5 left empty, by rule ([fragments], [floor], [band], [repeats], [blank]),
     * the lines the reader couldn't read ([notRead]), the readings that couldn't be stored
     * ([notStored]: their line went meanwhile, or the write failed), and the [ms] it took.
     */
    data class Counts(
        val lines: Int = 0,
        val kept: Int = 0,
        val fragments: Int = 0,
        val floor: Int = 0,
        val band: Int = 0,
        val repeats: Int = 0,
        val blank: Int = 0,
        val notRead: Int = 0,
        val notStored: Int = 0,
        val ms: Long = 0L
    ) {
        /** The readings left empty, for whatever reason. */
        val empty: Int get() = fragments + floor + band + repeats + blank + notRead

        /**
         * The counts as the field log says them, with the floor they were read with: `read <n> lines
         * in <ms> ms, <k> kept, <e> empty (<a> fragments, <b> under the floor of -1.5, <c> outside the
         * band, <d> repeating, <f> blank, <g> not read)`, then `, <h> not stored` when some weren't.
         * No text.
         */
        fun line(): String {
            val unstored = if (notStored > 0) ", $notStored not stored" else ""
            return "read $lines lines in $ms ms, $kept kept, $empty empty ($fragments fragments, " +
                "$floor under the floor of ${ReadingRules.LOG_PROB_FLOOR}, $band outside the band, $repeats repeating, " +
                "$blank blank, $notRead not read)$unstored"
        }

        internal fun plus(verdict: Verdict, stored: Boolean): Counts {
            val counted = when (verdict) {
                Verdict.KEPT -> copy(kept = kept + 1)
                Verdict.FRAGMENT -> copy(fragments = fragments + 1)
                Verdict.FLOOR -> copy(floor = floor + 1)
                Verdict.BAND -> copy(band = band + 1)
                Verdict.REPEATS -> copy(repeats = repeats + 1)
                Verdict.BLANK -> copy(blank = blank + 1)
                Verdict.NOT_READ -> copy(notRead = notRead + 1)
            }
            return if (stored) counted else counted.copy(notStored = notStored + 1)
        }
    }

    /** What became of one line: its reading kept, or emptied by a rule of D5, or not read at all. */
    enum class Verdict {
        KEPT, FRAGMENT, FLOOR, BAND, REPEATS, BLANK, NOT_READ;

        internal companion object {
            fun of(empty: ReadingRules.Empty?): Verdict = when (empty) {
                null -> KEPT
                ReadingRules.Empty.FRAGMENT -> FRAGMENT
                ReadingRules.Empty.FLOOR -> FLOOR
                ReadingRules.Empty.BAND -> BAND
                ReadingRules.Empty.REPEATS -> REPEATS
                ReadingRules.Empty.BLANK -> BLANK
            }
        }
    }

    /**
     * One line as a reading again read it ([readAgain]), for the debug rerun's answer files: the
     * line, the model's English ([output], null for a fragment or a line not read) and what D5 made
     * of it. Nothing of it is stored. Read in a window of lines (design D1, the rerun's V3 arm),
     * [window] holds them all, in order, [segment] the first, and the English is the window's.
     */
    data class LineRead(
        val segment: TranscriptionSegment,
        val output: ReaderOutput?,
        val verdict: Verdict,
        val window: List<TranscriptionSegment> = listOf(segment)
    )

    /** How a reading ended; each with the [counts] of what it read before. */
    sealed interface Outcome {
        val counts: Counts

        /** Every line it set out to read has its reading now, kept or empty. */
        data class Read(override val counts: Counts) : Outcome

        /** Stopped at once for a capture ([ReadingRun.stop], or the reader's own watch); the readings so far are stored. */
        data class Stopped(override val counts: Counts) : Outcome

        /** The reader was not loaded, for [why]: its files aren't on the phone, or something it never loads beside is in memory. */
        data class NotLoaded(val why: ReaderNotLoadedException.Why, override val counts: Counts) : Outcome

        /** The reader's paced load waited for memory as long as it may. */
        data class LoadStopped(override val counts: Counts) : Outcome

        /** The native reader failed ([cause]); it was released, and the readings so far are stored. */
        data class Failed(val cause: Throwable, override val counts: Counts) : Outcome
    }

    /** Whether the reader's files are on the phone and whole ([ReaderRepository.isOnPhone]). */
    suspend fun isOnPhone(): Boolean = reader.isOnPhone()

    /** What the lines of a recording still need of the step ([needs]). */
    enum class Needs {
        /** Every line the reader reads has its current reading. */
        NOTHING,

        /** Only fragments are left, whose empty readings need no model ([ReadingRules.isFragment]). */
        NO_MODEL,

        /** A line is left for the reader's model. */
        READER
    }

    /** What the lines of [sessionId] still need of the step: the unread ones ([ReadableSegments.unread]), and whether one needs the model. */
    suspend fun needs(sessionId: String): Needs {
        val lines = unread(sessionId)
        return when {
            lines.isEmpty() -> Needs.NOTHING
            lines.all { ReadingRules.isFragment(it.text) } -> Needs.NO_MODEL
            else -> Needs.READER
        }
    }

    /**
     * Whether [sessionId] has lines the reader reads that were never read at all
     * ([ReadableSegments.neverRead]): its Reading step never got to them.
     */
    suspend fun hasNeverRead(sessionId: String): Boolean =
        ReadableSegments.neverRead(linesOf(sessionId), transcriptionRepository.readingsOf(sessionId)).isNotEmpty()

    /**
     * Reads the lines of [sessionId] the reader has still to read, and stores their readings one by
     * one. [onStart] gets the run, which a capture that starts stops ([ReadingRun.stop]), right
     * before the reader loads; not when there is nothing to load it for.
     *
     * @return How it ended; throws only a cancel of the caller, a read of the recording that fails,
     *   and an error
     */
    suspend fun read(sessionId: String, onStart: (ReadingRun) -> Unit = {}): Outcome {
        val started = clock()
        var counts = Counts()
        val lines = unread(sessionId)
        if (lines.isEmpty()) return Outcome.Read(counts)
        counts = counts.copy(lines = lines.size)
        if (lines.all { ReadingRules.isFragment(it.text) }) {
            // Their empty readings need no model.
            for (line in lines) counts = counts.plus(Verdict.FRAGMENT, keep(line, "", null))
            return Outcome.Read(counts.copy(ms = msSince(started)))
        }
        letAiModelGo()?.let { return Outcome.NotLoaded(it, counts) }
        // Each line read in this run, by its text: a line edited meanwhile is a line to read again.
        val read = HashSet<Pair<String, String>>()
        val ended = stoppable(onStart) {
            reader.withReader { loaded ->
                var pending = lines
                var pass = 1
                while (true) {
                    for (line in pending) {
                        currentCoroutineContext().ensureActive()
                        val (output, verdict) = readOne(loaded, line)
                        val text = if (verdict == Verdict.KEPT) output!!.text.trim() else ""
                        counts = counts.plus(verdict, keep(line, text, output?.meanLogProb))
                        read += line.id to line.text
                    }
                    if (pass == PASSES) break
                    pending = unread(sessionId).filter { (it.id to it.text) !in read }
                    if (pending.isEmpty()) break
                    counts = counts.copy(lines = counts.lines + pending.size)
                    pass++
                }
            }
        }
        val done = counts.copy(ms = msSince(started))
        return ended.outcome(done)
    }

    /**
     * The debug rerun's reading (FinishingStepRerun's `reading`): every line of [sessionId] the reader
     * reads, whatever readings it has, read again as [read] reads one, and stored nowhere; each line
     * goes to [onLine] as it is read. With a [window] of 2 (design D1, compared in V3) each two lines
     * the reader reads, one after the other, are read as one text, their window, D5's rules applied
     * to it; a last line left over is read alone. [Counts.lines] counts what was read: lines, or
     * windows.
     *
     * @return How it ended, as [read]
     */
    suspend fun readAgain(sessionId: String, onLine: (LineRead) -> Unit, onStart: (ReadingRun) -> Unit = {}, window: Int = 1): Outcome {
        val started = clock()
        var counts = Counts()
        val windows = linesOf(sessionId).filter { it.isComplete && ReadableSegments.needsReader(it.text) }.chunked(window.coerceAtLeast(1))
        if (windows.isEmpty()) return Outcome.Read(counts)
        counts = counts.copy(lines = windows.size)
        letAiModelGo()?.let { return Outcome.NotLoaded(it, counts) }
        val ended = stoppable(onStart) {
            reader.withReader { loaded ->
                for (lines in windows) {
                    currentCoroutineContext().ensureActive()
                    val first = lines.first()
                    // A line alone is read as the Reading step reads it; a window as its lines' text, one after the other.
                    val text = if (lines.size == 1) first else first.copy(text = lines.joinToString(" ") { it.text.trim() })
                    val (output, verdict) = readOne(loaded, text)
                    counts = counts.plus(verdict, stored = true)
                    onLine(LineRead(first, output, verdict, window = lines))
                }
            }
        }
        return ended.outcome(counts.copy(ms = msSince(started)))
    }

    /** The complete lines still to read, in their order. */
    private suspend fun unread(sessionId: String): List<TranscriptionSegment> =
        ReadableSegments.unread(linesOf(sessionId), transcriptionRepository.readingsOf(sessionId))

    private suspend fun linesOf(sessionId: String): List<TranscriptionSegment> =
        transcriptionRepository.getSegmentsBySession(sessionId).first().sortedBy { it.timestamp }

    /**
     * Lets the AI model go before the reader loads, which never loads beside it; the reason not to
     * load when it stays in memory (a load of it was under way), else null.
     */
    private suspend fun letAiModelGo(): ReaderNotLoadedException.Why? {
        if (!llmRepository.isModelInMemory) return null
        llmRepository.cleanup()
        return ReaderNotLoadedException.Why.AI_MODEL.takeIf { llmRepository.isModelInMemory }
    }

    /** [line] read: a fragment without the model, any other line by it, and D5's verdict. */
    private suspend fun readOne(loaded: LoadedReader, line: TranscriptionSegment): Pair<ReaderOutput?, Verdict> {
        if (ReadingRules.isFragment(line.text)) return null to Verdict.FRAGMENT
        val output = try {
            loaded.read(line.text)
        } catch (e: LineNotReadException) {
            return null to Verdict.NOT_READ
        }
        return output to Verdict.of(ReadingRules.emptied(output))
    }

    /**
     * Stores [line]'s reading [text] (empty for one D5 or the reader left without), of its text as
     * it was read. Whether it was stored: a line that went meanwhile, or a write that failed, gets
     * none, and the AI steps leave it out.
     */
    private suspend fun keep(line: TranscriptionSegment, text: String, meanLogProb: Double?): Boolean {
        val reading = SegmentReading(
            segmentId = line.id,
            sessionId = line.sessionId,
            sourceSha256 = SegmentReading.sourceHashOf(line.text),
            text = text,
            model = reader.model,
            score = meanLogProb?.takeIf { it.isFinite() },
            createdAt = now()
        )
        val stored = transcriptionRepository.saveReading(reading)
        stored.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return stored.isSuccess
    }

    /** How the reading with the reader ended, before its counts are known. */
    private sealed interface Ended {
        data object Done : Ended
        data object Stopped : Ended
        data class Refused(val e: ReaderNotLoadedException) : Ended
        data object LoadStopped : Ended
        data class Failed(val e: Throwable) : Ended

        fun outcome(counts: Counts): Outcome = when (this) {
            Done -> Outcome.Read(counts)
            Stopped -> Outcome.Stopped(counts)
            is Refused -> Outcome.NotLoaded(e.why, counts)
            LoadStopped -> Outcome.LoadStopped(counts)
            is Failed -> Outcome.Failed(e, counts)
        }
    }

    /**
     * Runs [block], the reading with the reader, so that the run handed to [onStart] stops it at
     * once: a stop before the reading begins (as the run is handed out, say) keeps it from
     * beginning. A cancel of the caller is passed on; what the reader throws is the reading's end.
     */
    private suspend fun stoppable(onStart: (ReadingRun) -> Unit, block: suspend () -> Unit): Ended = coroutineScope {
        val stopped = AtomicBoolean(false)
        val work = async(start = CoroutineStart.LAZY) {
            try {
                block()
                Ended.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderStoppedException) {
                Ended.Stopped
            } catch (e: ReaderNotLoadedException) {
                Ended.Refused(e)
            } catch (e: LoadStoppedForMemoryException) {
                Ended.LoadStopped
            } catch (e: ReaderFailedException) {
                Ended.Failed(e)
            }
        }
        // The run's stop cancels the work: before await starts it, it never begins.
        onStart(
            ReadingRun {
                stopped.set(true)
                work.cancel()
            }
        )
        try {
            work.await()
        } catch (e: CancellationException) {
            // A cancel of the caller itself is passed on; one that was no stop is too.
            currentCoroutineContext().ensureActive()
            if (!stopped.get()) throw e
            Ended.Stopped
        }
    }

    private fun msSince(started: Long): Long = (clock() - started) / NS_PER_MS

    private companion object {
        /** A line edited while the reader read is read once more: two passes over the lines in all. */
        const val PASSES = 2

        const val NS_PER_MS = 1_000_000L
    }
}
