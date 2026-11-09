package com.satori.reader

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** IndicTrans2's three graphs as files; each finds its external data file beside it. */
data class ReaderGraphs(val encoder: File, val decoder: File, val decoderWithPast: File)

/**
 * How the sessions are made and the model's limits. The defaults are the design's (D3, D4) and
 * IndicTrans2-Dist's config.json; V1 compares other settings on the phone without a rebuild.
 *
 * @property threads intra-op threads per session (D4). The reader never runs beside a capture.
 * @property prepackEncoder prepacking for the encoder, on (D3) ...
 * @property prepackDecoder ... off for decoder_model, which runs once per segment, so its weights
 *   stay file-backed pages ...
 * @property prepackDecoderWithPast ... and on for the per-token loop.
 * @property cpuArena ONNX Runtime's CPU arena (D3; V1 compares it off).
 * @property shrinkArenaAfterSegment the arena given back after each segment's encoder and first
 *   decoder step.
 * @property maxSourcePositions the source at most: the two tags, the pieces and </s>.
 * @property maxTargetPositions the start token and the output at most.
 * @property sourceVocabulary every source id is below it (encoder_vocab_size).
 * @property endId </s>, the same id in both dictionaries: the end-of-sentence the decoder emits,
 *   and the last id of every source, the only place a source may have it.
 */
data class ReaderOptions(
    val threads: Int = 4,
    val prepackEncoder: Boolean = true,
    val prepackDecoder: Boolean = false,
    val prepackDecoderWithPast: Boolean = true,
    val cpuArena: Boolean = true,
    val shrinkArenaAfterSegment: Boolean = true,
    val maxSourcePositions: Int = 256,
    val maxTargetPositions: Int = 256,
    val sourceVocabulary: Int = 122_706,
    val decoderStartId: Int = 2,
    val endId: Int = 2,
    val padId: Int = 1,
) {
    /** The layout reader_jni.cpp reads (its OptionIndex). */
    internal fun toIntArray(): IntArray = intArrayOf(
        threads,
        if (prepackEncoder) 1 else 0,
        if (prepackDecoder) 1 else 0,
        if (prepackDecoderWithPast) 1 else 0,
        if (cpuArena) 1 else 0,
        if (shrinkArenaAfterSegment) 1 else 0,
        maxSourcePositions,
        maxTargetPositions,
        sourceVocabulary,
        decoderStartId,
        endId,
        padId,
    )
}

/**
 * One segment's reading.
 *
 * @property ids the target ids, without the closing end-of-sentence; dict.TGT.json maps them to pieces.
 * @property meanLogProb the best hypothesis's log-probability per token, its end-of-sentence
 *   counted; D5's floor reads it. Negative infinity when the model allowed no token the search
 *   could take: none at all, or at the first step only the end-of-sentence, which fairseq's
 *   minimum length (one token) holds back. Empty ids otherwise come only from a cap of one token.
 * @property capped the output reached the token cap, or ended without an end-of-sentence.
 * @property steps decoder runs: one of decoder_model, then one of decoder_with_past per token.
 */
class Reading(val ids: IntArray, val meanLogProb: Double, val capped: Boolean, val steps: Int)

/** Layer and head counts as the sessions give them; heads and head size 0 until known. */
data class ReaderDims(val layers: Int, val heads: Int, val headDim: Int)

/**
 * The reader: IndicTrans2-Dist's encoder, decoder_model and decoder_with_past on the ONNX Runtime
 * that sherpa-onnx ships, with beam search and its caches in native code (libreader-onnx.so).
 * One engine per load: [load], then [translate] per segment, then [release]. A native failure is
 * a [ReaderException], never a crash.
 *
 * Threads: [load] and [translate] block, so they run off the main thread; calls on one engine
 * wait for each other, never [ReaderException.Busy]. [cancel] may come from any thread at any time
 * (a capture opening, D14): it stops a running Run at its next node and a load at ONNX Runtime's
 * next check, and makes every later call throw [ReaderException.Cancelled]. It never waits for the
 * call it stops. [release] cancels, waits for the running call to return and frees the native
 * memory; NativeHeap.purge belongs after it.
 */
class ReaderEngine : AutoCloseable {
    private val callLock = ReentrantLock()
    private val handleLock = Any()
    private var handle: Long = ReaderNative.newHandle()
    private var targetPositions = ReaderOptions().maxTargetPositions

    /** Loads the three sessions with [options]. Cancellable. */
    fun load(graphs: ReaderGraphs, options: ReaderOptions = ReaderOptions()) = call { native ->
        val status = ReaderNative.load(
            native,
            graphs.encoder.path.encodeToByteArray(),
            graphs.decoder.path.encodeToByteArray(),
            graphs.decoderWithPast.path.encodeToByteArray(),
            options.toIntArray(),
        )
        ReaderStatus.check(status) { ReaderNative.errorOf(native) }
        targetPositions = options.maxTargetPositions
    }

    /**
     * Reads one segment: [sourceIds] as the model takes them, the two language tags first and </s>
     * last (PieceIds). The output is capped at min(2 x pieces + 10, maxTargetPositions - 1) tokens
     * (D15, [maxNewTokens]); [maxNewTokens] > 0 lowers that cap. More than maxSourcePositions ids
     * are [ReaderException.BadInput]: the Reading step splits such a segment at a word boundary
     * first (at most [MAX_SOURCE_PIECES] pieces). So is a source without </s> last or with </s>
     * anywhere else (two segments' ids joined as they are). Cancellable.
     *
     * [minTokens] is the search's minimum length: no end-of-sentence before that many tokens,
     * except at the cap's last token. Every reading keeps fairseq's [DEFAULT_MIN_TOKENS]; a debug
     * worst case (V1) gives the cap or more, so the end is held back to the cap's last token and
     * the search runs every step of it. Negative is [ReaderException.BadInput].
     */
    fun translate(
        sourceIds: IntArray,
        beam: Int = DEFAULT_BEAM,
        maxNewTokens: Int = 0,
        minTokens: Int = DEFAULT_MIN_TOKENS,
    ): Reading = call { native ->
        val ids = IntArray(targetPositions)
        val counts = IntArray(3)
        val score = DoubleArray(1)
        val status = ReaderNative.translate(native, sourceIds, beam, maxNewTokens, minTokens, ids, counts, score)
        ReaderStatus.check(status) { ReaderNative.errorOf(native) }
        Reading(ids.copyOf(counts[0]), score[0], counts[1] != 0, counts[2])
    }

    /** The loaded graphs' layer and head counts, for the load line of the field log. */
    fun dims(): ReaderDims = call { native ->
        val dims = ReaderNative.dims(native) ?: throw ReaderException.OutOfMemory("no memory for the dims")
        ReaderDims(dims[0], dims[1], dims[2])
    }

    /** Stops the running call and every later one (D14). Any thread; returns at once. */
    fun cancel() {
        synchronized(handleLock) {
            if (handle != 0L) ReaderNative.cancel(handle)
        }
    }

    /**
     * Cancels, waits for the running call, and frees the sessions and caches. Idempotent. The wait
     * lasts until the running call sees the cancel: a Run stops within milliseconds, but ONNX
     * Runtime doesn't check while it plans a graph's memory during a load (2.4-3.2 s on the host
     * tests' toy encoder), so never call it on the main thread.
     */
    fun release() {
        cancel()
        callLock.withLock {
            val native = synchronized(handleLock) { handle.also { handle = 0L } }
            if (native != 0L) ReaderNative.release(native)
        }
    }

    override fun close() = release()

    private inline fun <T> call(block: (Long) -> T): T = callLock.withLock {
        val native = synchronized(handleLock) { handle }
        if (native == 0L) throw ReaderException.NotLoaded("the reader was released")
        block(native)
    }

    companion object {
        /** D2: beam 4; the paper's numbers are beam 5. V1 may turn it to 1 (greedy). */
        const val DEFAULT_BEAM = 4
        const val MAX_BEAM = 8

        /** fairseq's min_len: a reading's first token is never its end, unless the cap allows one token. */
        const val DEFAULT_MIN_TOKENS = 1

        /** D15: a segment's pieces at most, beside the two tags and </s> (max_source_positions 256). */
        const val MAX_SOURCE_PIECES = 253

        /** D15's output cap for a segment of [pieces] pieces; the native side applies the same. */
        fun maxNewTokens(pieces: Int, maxTargetPositions: Int = ReaderOptions().maxTargetPositions): Int =
            minOf(2 * maxOf(pieces, 0) + 10, maxTargetPositions - 1)
    }
}
