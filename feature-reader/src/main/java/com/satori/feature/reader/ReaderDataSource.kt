package com.satori.feature.reader

import android.util.Log
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.ReleasePurge
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.data.monitor.stoppable
import com.satori.data.monitor.withLoaded
import com.satori.domain.model.CaptureState
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.reading.PieceIds
import com.satori.reader.ReaderDims
import com.satori.reader.ReaderEngine
import com.satori.reader.ReaderException
import com.satori.reader.ReaderGraphs
import com.satori.reader.ReaderOptions
import com.satori.reader.ReaderTokenizer
import com.satori.reader.Reading
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Why the reader was not loaded: refused before anything was read ([ReaderRefusedException]). */
enum class ReaderRefusal(val because: String) {
    NOT_DOWNLOADED("its files are not on the phone"),
    DAMAGED("its files are damaged or incomplete"),
    UNREADABLE("its files can't be read"),

    /** A capture is live or opening: the reader waits for it to end (D14, O5). */
    CAPTURE("a capture is live or opening"),

    /** The live speech model is still in memory, as after a recording, until its release. */
    SPEECH_MODEL("a speech model is in memory"),

    /** An AI model is in memory: the caller, holding the AI work gate, unloads it first. */
    AI_MODEL("an AI model is in memory")
}

/** The reader was refused before its load began, for [refusal]; nothing was read or loaded. */
class ReaderRefusedException(val refusal: ReaderRefusal, model: String) :
    Exception("The reader $model was not loaded: ${refusal.because}")

/**
 * The reader stopped for a capture that opened (D14): the running Run or load was cancelled, and
 * the reader released and the native heap purged before this was thrown. Read again once no
 * capture is live.
 */
class ReaderYieldedException(model: String) : Exception("The reader $model stopped for a capture that opened")

/** The tokenizer and the vocabularies of a loaded reader, or of the tokenizer alone ([ReaderDataSource.withTokenizer]). */
interface LoadedTokenizer {
    /** The model's name in the log: its build's id. */
    val name: String

    /** dict.SRC.json and dict.TGT.json: pieces to the model's ids and back. */
    val pieceIds: PieceIds

    /** SentencePiece's pieces of [text], a preprocessed line after its tags. */
    fun pieces(text: String): List<String>
}

/** A loaded reader ([ReaderDataSource.withReader]); one call at a time. */
interface LoadedReader : LoadedTokenizer {
    /** The model's limits, from its config.json. */
    val limits: ReaderLimits

    /** Layers, heads and head size, as the sessions give them. */
    fun dims(): ReaderDims

    /**
     * One source as the model takes it, read: ReaderEngine.translate, stopped at once by a cancel
     * of the caller (D14), on IO.
     */
    suspend fun translate(
        sourceIds: IntArray,
        beam: Int = ReaderEngine.DEFAULT_BEAM,
        maxNewTokens: Int = 0,
        minTokens: Int = ReaderEngine.DEFAULT_MIN_TOKENS
    ): Reading

    /** One line read into English, part by part ([LineReader]) at [beam]. */
    suspend fun read(line: String, beam: Int = ReaderEngine.DEFAULT_BEAM): LineReading
}

/**
 * The reader's data source (0.8.0, R3): its files' check, its load at the app's pace and its
 * release, around a block that reads with it ([withReader]). One reader at a time, made for the
 * block and gone after it; the Reading step and the debug selftests call it.
 *
 * The load is a model load like any other (the owner's rule: every load paced, nothing squeezes
 * memory), so it goes through [SpeechModelLoadPacing.otherLoad], the speech models' path for a
 * load for a file: the one-load gate, with the recorder's own load first; a pre-read of its files
 * at 120 MB/s (a debug build's `debug.satori.load.pace` over it); then room made for the parse of
 * the bytes its sessions turn anonymous, the packed weights of the arm's prepacked graphs, not the
 * `.data` files ([ReaderFiles.parseBytes], about 0.14 GB in the default arm); and it stops once it
 * has waited 10 s in all for memory ([com.satori.data.monitor.PacedLoadStoppedException]). Its
 * memory lines ([ModelMemoryLines], debug builds) give the load, with its peak, and the release; the
 * release purges the native heap ([ReleasePurge]) in every build, as a speech model's does.
 *
 * It never loads beside a live or opening capture, beside the live speech model, or beside an AI
 * model ([ReaderRefusal]), the tokenizer alone ([withTokenizer]) neither: the caller holds the AI
 * work gate and unloads the AI model first, as the after-Stop transcription does. A capture that
 * opens while it loads or reads stops it at once ([ReaderYieldedException], D14): the cancellation
 * flag of a load in progress, the terminate flag of a running Run; a cancel of the caller does the
 * same. Either way the line after says how long the native call took to return after the stop
 * (V1's bar for a Run: 100 ms). The release then runs before anything else, and it may wait
 * seconds for a load that ONNX Runtime is planning, so all of it runs on IO, never on the main
 * thread.
 *
 * Lines (tag SatoriReader), counts, ms and MB only:
 * ```
 * Reader <model> loaded in <ms> ms: <layers> layers, <heads> heads of <size>; prepack=<p>,arena=<a>,threads=<n>; parse room for <mb> MB
 * Reader tokenizer of <model> loaded in <ms> ms: <n> source pieces, <m> target pieces
 * Reader <model> Run stopped <ms> ms after the cancel
 * Reader <model> load stopped <ms> ms after the cancel
 * Reader <model> released in <ms> ms
 * W Reader <model> not loaded: <why>
 * W Reader <model>: config.json has no number <key>      (or: config.json is no JSON (SerializationException))
 * W Reader <model> load failed after <ms> ms (<exception class>)
 * W Reader <model> stopped for a capture that opened; released
 * W Reader <model> not released cleanly (<exception class>)
 * ```
 * For the tokenizer alone the model is `<model>/tokenizer` in its memory lines and in its stop,
 * release and failure lines; its load line is the tokenizer's above, and its refusals name the model.
 *
 * @param memoryLines The memory lines of one load and release, under SatoriReader, each also given
 *   to the sink a caller passes (the selftests write them to their files)
 * @param captureState The capture that runs, which the reader never loads or reads beside
 * @param speechModelLoaded Whether the live speech model is in memory
 * @param aiModelInMemory Whether an AI model is in memory
 * @param natives Makes the native reader and tokenizer: lib-reader-onnx's on the phone
 * @param io Where every blocking call runs
 */
class ReaderDataSource(
    private val downloads: ModelDownloadManager,
    private val pacing: SpeechModelLoadPacing,
    private val memoryLines: (sink: ((String) -> Unit)?) -> ModelMemoryLines,
    private val releasePurge: ReleasePurge,
    private val captureState: StateFlow<CaptureState>,
    private val speechModelLoaded: StateFlow<Boolean>,
    private val aiModelInMemory: () -> Boolean,
    private val natives: ReaderNatives = ReaderNatives.DEVICE,
    private val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val warn: (String) -> Unit = { Log.w(TAG, it) }
) {

    /** The downloaded build [model]'s files. */
    fun files(model: ReaderModelId = ReaderModelId.INT8): ReaderFiles = ReaderFiles.of(downloads, model)

    /**
     * Whether the build [model]'s files were each found once with their pinned SHA-256, as they are
     * now (design R7, [ModelDownloadManager.readerModelVerified]): what the app's own reading asks
     * besides their sizes and first bytes. The first run reads them; a load checks the sizes and
     * first bytes only, as the debug tools' selftests do. File access.
     */
    fun verified(model: ReaderModelId = ReaderModelId.INT8): Boolean = downloads.readerModelVerified(model)

    /**
     * Loads the reader from [files] with [arm]'s sessions, paced, runs [use] with it, then releases
     * it and purges the native heap, however [use] ends. Runs on IO.
     *
     * @param memoryLine Also gets each memory line of this load and release
     * @throws ReaderRefusedException before anything is read: its files aren't whole, a capture is
     *   live or opening, the speech model or an AI model is in memory
     * @throws ReaderYieldedException when a capture opened meanwhile
     * @throws com.satori.data.monitor.PacedLoadStoppedException when the load waited 10 s in all for memory
     * @throws ReaderException when the native reader refused its files or a source
     */
    suspend fun <R> withReader(
        files: ReaderFiles,
        arm: ReaderArm = ReaderArm.DEFAULT,
        memoryLine: ((String) -> Unit)? = null,
        use: suspend (LoadedReader) -> R
    ): R = withContext(io) {
        refuseUnlessAlone(files.name)
        val limits = limitsOf(files)
        run(Session(files, arm, limits, memoryLines(memoryLine)), use)
    }

    /**
     * Loads SentencePiece's model and the two vocabularies of [files] alone, paced, and runs [use]
     * with them, then releases them: the tokenizer selftest (V0b). It needs no ONNX session, but
     * it is a load of the reader's like any other (the owner's rule: never beside a capture or the
     * 1B), so it is refused where [withReader] is. Runs on IO.
     *
     * @throws ReaderRefusedException before anything is read, as [withReader]
     */
    suspend fun <R> withTokenizer(files: ReaderFiles, use: suspend (LoadedTokenizer) -> R): R = withContext(io) {
        refuseUnlessAlone(files.name)
        val limits = limitsOf(files)
        run(Session(files, arm = null, limits, memoryLines(null)), use)
    }

    private suspend fun <R> run(session: Session, use: suspend (LoadedReader) -> R): R = coroutineScope {
        // Watched from before the load: a capture that opens stops the reader at once (D14).
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            captureState.first { it != CaptureState.IDLE }
            session.yieldToCapture()
        }
        try {
            withLoaded(io, load = { session.load() }, release = { it.release() }, stop = session::stop) { use(it) }
        } catch (e: CancellationException) {
            if (session.yielded && isActive) throw yielded(session) else throw e
        } catch (e: ReaderException.Cancelled) {
            // The native reader saw the cancel first: the caller's, which stays a cancel, or the capture's.
            when {
                !isActive -> throw CancellationException("The reader stopped as its caller was cancelled").apply { initCause(e) }
                session.yielded -> throw yielded(session)
                else -> throw e
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun yielded(session: Session): ReaderYieldedException {
        warn("Reader ${session.name} stopped for a capture that opened; released")
        return ReaderYieldedException(session.name)
    }

    private fun refuseUnlessAlone(name: String) {
        val refusal = when {
            captureState.value != CaptureState.IDLE -> ReaderRefusal.CAPTURE
            speechModelLoaded.value -> ReaderRefusal.SPEECH_MODEL
            aiModelInMemory() -> ReaderRefusal.AI_MODEL
            else -> null
        } ?: return
        refuse(name, refusal)
    }

    // The model's limits from its files, once they are found whole; a configuration that doesn't
    // read is a damaged file.
    private fun limitsOf(files: ReaderFiles): ReaderLimits {
        when (files.check()) {
            ModelFileState.Intact -> Unit
            ModelFileState.Missing -> refuse(files.name, ReaderRefusal.NOT_DOWNLOADED)
            ModelFileState.Damaged -> refuse(files.name, ReaderRefusal.DAMAGED)
            ModelFileState.Unreadable -> refuse(files.name, ReaderRefusal.UNREADABLE)
        }
        return try {
            ReaderLimits.parse(files.config.readText())
        } catch (e: java.io.IOException) {
            refuse(files.name, ReaderRefusal.UNREADABLE)
        } catch (e: IllegalArgumentException) {
            // The parser's message quotes the file; the line says what is wrong with it only.
            val why = if (e is SerializationException) "config.json is no JSON (${e.javaClass.simpleName})" else e.message
            warn("Reader ${files.name}: $why")
            refuse(files.name, ReaderRefusal.DAMAGED)
        }
    }

    private fun refuse(name: String, refusal: ReaderRefusal): Nothing {
        warn("Reader $name not loaded: ${refusal.because}")
        throw ReaderRefusedException(refusal, name)
    }

    /**
     * One reader, from its load to its release: the tokenizer, the vocabularies, and with an [arm]
     * the three sessions. [stop] and [yieldToCapture] may come from any thread.
     */
    private inner class Session(
        private val files: ReaderFiles,
        private val arm: ReaderArm?,
        override val limits: ReaderLimits,
        private val lines: ModelMemoryLines
    ) : LoadedReader {
        override val name: String = if (arm == null) "${files.name}/tokenizer" else files.name

        private val tokenizer: ReaderTokenizerCalls = natives.tokenizer()
        private val engine: ReaderEngineCalls? = try {
            arm?.let { natives.engine() }
        } catch (e: Throwable) {
            tokenizer.release()
            throw e
        }

        @Volatile private var stopped = false
        @Volatile var yielded = false
            private set

        // The clock at the first stop, for the lines that say how long the native call took to
        // return after it (V1: a Run within 100 ms); NOT_STOPPED before.
        private val stoppedAt = AtomicLong(NOT_STOPPED)

        private var ids: PieceIds? = null
        override val pieceIds: PieceIds get() = checkNotNull(ids) { "not loaded" }

        // The vocabularies' sizes, for the tokenizer's load line.
        private var sourcePieces = 0
        private var targetPieces = 0

        /** Stops a load or a Run under way, and every later one. */
        fun stop() {
            stoppedAt.compareAndSet(NOT_STOPPED, clock())
            stopped = true
            engine?.cancel()
        }

        fun yieldToCapture() {
            yielded = true
            stop()
        }

        /**
         * The paced load, measured; on any failure the native objects are released before it
         * throws, as nothing else holds them yet. Blocking.
         */
        fun load(): Session {
            val started = clock()
            val parseBytes = arm?.let(files::parseBytes) ?: 0L
            try {
                lines.measured(ModelMemoryLines.Event.LOAD, name, peak = arm != null) {
                    pacing.otherLoad(name, if (arm == null) files.tokenizerFiles() else files.loadFiles, parseBytes, cancelled = { stopped }) {
                        tokenizer.load(files.sourcePieces)
                        val source = PieceIds.parse(files.sourceVocabulary.readText())
                        val target = PieceIds.parse(files.targetVocabulary.readText())
                        ids = PieceIds(source, target)
                        sourcePieces = source.size
                        targetPieces = target.size
                        if (stopped) throw CancellationException("The reader's load was stopped")
                        engine?.load(files.graphs, checkNotNull(arm).options(limits))
                    }
                }
            } catch (e: Throwable) {
                // A stop before the load ended, the gate's wait and the pre-read included.
                val stop = stoppedAt.get()
                if (stop != NOT_STOPPED) log("Reader $name load stopped ${msSince(stop)} ms after the cancel")
                release()
                if (e !is CancellationException && e !is ReaderException.Cancelled) {
                    warn("Reader $name load failed after ${msSince(started)} ms (${e.javaClass.simpleName})")
                }
                throw e
            }
            val ms = msSince(started)
            if (engine == null) {
                log("Reader tokenizer of ${files.name} loaded in $ms ms: $sourcePieces source pieces, $targetPieces target pieces")
            } else {
                // Loaded: from here the caller holds it, so nothing of the line may throw.
                val dims = try {
                    engine.dims().let { "${it.layers} layers, ${it.heads} heads of ${it.headDim}" }
                } catch (e: ReaderException) {
                    "dims not read (${e.javaClass.simpleName})"
                }
                log("Reader $name loaded in $ms ms: $dims; ${checkNotNull(arm).label}; parse room for ${parseBytes / BYTES_PER_MB} MB")
            }
            return this
        }

        /** The release of everything native, measured, then the purge. Blocking, on IO; never throws. */
        fun release() {
            val started = clock()
            try {
                lines.released(name, releasePurge.plan()) {
                    try {
                        engine?.release()
                    } finally {
                        tokenizer.release()
                    }
                }
                log("Reader $name released in ${msSince(started)} ms")
            } catch (e: RuntimeException) {
                warn("Reader $name not released cleanly (${e.javaClass.simpleName})")
            }
        }

        override fun dims(): ReaderDims = checkNotNull(engine) { "the tokenizer has no sessions" }.dims()

        override fun pieces(text: String): List<String> = tokenizer.pieces(text)

        override suspend fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            val running = checkNotNull(engine) { "the tokenizer has no sessions" }
            return stoppable(io, stop = ::stop) {
                val started = clock()
                try {
                    running.translate(sourceIds, beam, maxNewTokens, minTokens)
                } finally {
                    // Only a stop while this Run ran: a later Run of a stopped reader returns at once.
                    val stop = stoppedAt.get()
                    if (stop != NOT_STOPPED && stop - started >= 0) log("Reader $name Run stopped ${msSince(stop)} ms after the cancel")
                }
            }
        }

        override suspend fun read(line: String, beam: Int): LineReading =
            LineReader(::pieces, pieceIds, { source -> translate(source, beam) }, maxPieces = limits.maxSourcePieces).read(line)

        private fun msSince(started: Long): Long = (clock() - started) / NS_PER_MS
    }

    companion object {
        const val TAG = "SatoriReader"
        private const val NS_PER_MS = 1_000_000L
        private const val BYTES_PER_MB = 1_048_576L

        // No stop yet: a value the clock never gives a stop.
        private const val NOT_STOPPED = Long.MIN_VALUE
    }
}

/** The native reader's calls the data source makes ([ReaderEngine]'s), a seam for the tests. */
interface ReaderEngineCalls {
    fun load(graphs: ReaderGraphs, options: ReaderOptions)
    fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading
    fun dims(): ReaderDims
    fun cancel()
    fun release()
}

/** The native tokenizer's calls ([ReaderTokenizer]'s), a seam for the tests. */
interface ReaderTokenizerCalls {
    fun load(model: File)
    fun pieces(text: String): List<String>
    fun release()
}

/** Makes the native reader and tokenizer; [DEVICE] makes lib-reader-onnx's. */
interface ReaderNatives {
    fun engine(): ReaderEngineCalls
    fun tokenizer(): ReaderTokenizerCalls

    companion object {
        /** lib-reader-onnx's: ReaderException.LoadFailed when its library didn't load. */
        val DEVICE: ReaderNatives = object : ReaderNatives {
            override fun engine(): ReaderEngineCalls = object : ReaderEngineCalls {
                private val engine = ReaderEngine()
                override fun load(graphs: ReaderGraphs, options: ReaderOptions) = engine.load(graphs, options)
                override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading =
                    engine.translate(sourceIds, beam, maxNewTokens, minTokens)
                override fun dims(): ReaderDims = engine.dims()
                override fun cancel() = engine.cancel()
                override fun release() = engine.release()
            }

            override fun tokenizer(): ReaderTokenizerCalls = object : ReaderTokenizerCalls {
                private val tokenizer = ReaderTokenizer()
                override fun load(model: File) = tokenizer.load(model)
                override fun pieces(text: String): List<String> = tokenizer.pieces(text)
                override fun release() = tokenizer.release()
            }
        }
    }
}
