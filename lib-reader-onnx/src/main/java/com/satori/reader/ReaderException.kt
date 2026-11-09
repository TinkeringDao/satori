package com.satori.reader

/**
 * What a reader call that failed throws: one subclass per native status (reader_status.h), so the
 * Reading step can tell a capture that stopped it ([Cancelled]: release, read again later) from a
 * segment it should leave empty ([BadInput]) or a reader file that is no good ([BadModel],
 * [LoadFailed]). [message] is the native side's, ONNX Runtime's or SentencePiece's text when they
 * gave one: names and shapes, never segment text.
 */
sealed class ReaderException(val status: Int, message: String) : Exception(message) {
    /** cancel() came: the running Run or load stopped, or the call saw it first. Final for the engine. */
    class Cancelled(message: String) : ReaderException(ReaderStatus.CANCELLED, message)

    /**
     * An argument out of range: no ids, more than the model's positions, an id outside the
     * vocabulary, a source not ending in its one </s>, a beam outside 1..8.
     */
    class BadInput(message: String) : ReaderException(ReaderStatus.BAD_INPUT, message)

    /** A graph whose inputs, outputs or shapes are not IndicTrans2's export. */
    class BadModel(message: String) : ReaderException(ReaderStatus.BAD_MODEL, message)

    /** A file that is missing or not a model, ONNX Runtime refusing it, or the native library not loading. */
    class LoadFailed(message: String) : ReaderException(ReaderStatus.LOAD_FAILED, message)

    /** ONNX Runtime failing a Run, or logits that are not finite. */
    class RunFailed(message: String) : ReaderException(ReaderStatus.RUN_FAILED, message)

    class OutOfMemory(message: String) : ReaderException(ReaderStatus.OUT_OF_MEMORY, message)

    /** A call before a successful load, or after release. */
    class NotLoaded(message: String) : ReaderException(ReaderStatus.NOT_LOADED, message)

    /**
     * A native call while another runs on the same native reader. [ReaderEngine] and
     * [ReaderTokenizer] make their calls wait for each other, so only a direct ReaderNative caller
     * can see it.
     */
    class Busy(message: String) : ReaderException(ReaderStatus.BUSY, message)

    /** The native side's own bookkeeping failed a check. */
    class Internal(message: String) : ReaderException(ReaderStatus.INTERNAL, message)
}

/** The native statuses, the numbers of reader_status.h, and the exceptions they become. */
internal object ReaderStatus {
    const val OK = 0
    const val CANCELLED = 1
    const val BAD_INPUT = 2
    const val BAD_MODEL = 3
    const val LOAD_FAILED = 4
    const val RUN_FAILED = 5
    const val OUT_OF_MEMORY = 6
    const val NOT_LOADED = 7
    const val BUSY = 8
    const val INTERNAL = 9

    fun exception(status: Int, message: String): ReaderException = when (status) {
        CANCELLED -> ReaderException.Cancelled(message)
        BAD_INPUT -> ReaderException.BadInput(message)
        BAD_MODEL -> ReaderException.BadModel(message)
        LOAD_FAILED -> ReaderException.LoadFailed(message)
        RUN_FAILED -> ReaderException.RunFailed(message)
        OUT_OF_MEMORY -> ReaderException.OutOfMemory(message)
        NOT_LOADED -> ReaderException.NotLoaded(message)
        BUSY -> ReaderException.Busy(message)
        else -> ReaderException.Internal(if (status == INTERNAL) message else "status $status: $message")
    }

    /** Throws the exception of [status] unless it is [OK]; [message] is read only then. */
    inline fun check(status: Int, message: () -> String) {
        if (status != OK) {
            throw exception(status, message())
        }
    }
}
