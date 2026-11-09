package com.satori.reader

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The reader's SentencePiece (model.SRC, built from the pinned third_party/sentencepiece v0.2.1),
 * as IndicTransTokenizer uses it: [pieces] is EncodeAsPieces on a preprocessed line after its two
 * language tags. The tags, the pieces' ids through dict.SRC.json and the closing </s> are
 * PieceIds' (domain/.../reading). It needs no ONNX session, so the debug tokenizer selftest (V0b)
 * loads it alone and writes the pieces of the public inputs for comparison with references made
 * off this machine.
 *
 * One call at a time; [release] after the last.
 */
class ReaderTokenizer : AutoCloseable {
    private val lock = ReentrantLock()
    private var handle: Long = ReaderNative.newHandle()

    /** Loads [model]; [ReaderException.LoadFailed] for a file SentencePiece can't read or parse. */
    fun load(model: File) = call { native ->
        val status = ReaderNative.loadTokenizer(native, model.path.encodeToByteArray())
        ReaderStatus.check(status) { ReaderNative.errorOf(native) }
    }

    /** The SentencePiece pieces of [text], in order; none for blank text. */
    fun pieces(text: String): List<String> = call { native ->
        val status = IntArray(1)
        val pieces = ReaderNative.pieces(native, text.encodeToByteArray(), status)
        ReaderStatus.check(status[0]) { ReaderNative.errorOf(native) }
        pieces?.map { it.decodeToString() } ?: throw ReaderException.Internal("no pieces and no failure")
    }

    /** Frees the tokenizer. Idempotent. */
    fun release() {
        lock.withLock {
            val native = handle
            handle = 0L
            if (native != 0L) ReaderNative.release(native)
        }
    }

    override fun close() = release()

    private inline fun <T> call(block: (Long) -> T): T = lock.withLock {
        if (handle == 0L) throw ReaderException.NotLoaded("the tokenizer was released")
        block(handle)
    }
}
