package com.satori.reader

/**
 * The JNI of libreader-onnx.so (reader_jni.cpp). A handle is a native reader made by [create] and
 * freed by [release]; [ReaderEngine] and [ReaderTokenizer] keep every other call off a handle that
 * is being or has been released. Text goes both ways as UTF-8 byte arrays, never as String, as JNI's
 * modified UTF-8 can't carry characters outside the BMP.
 *
 * The library needs libonnxruntime.so, which lib-sherpa-onnx ships; Android's linker finds it in
 * the APK beside this one. The JVM host test finds a macOS build of the same sources the same way,
 * on the java.library.path its Gradle task sets (build.gradle.kts).
 *
 * The library is loaded once, when this object is first touched, and a failure to load it is kept
 * rather than thrown: an Error out of this initializer would reach the caller of [ReaderEngine] or
 * [ReaderTokenizer] as UnsatisfiedLinkError the first time and NoClassDefFoundError every time
 * after, past any catch of [ReaderException]. [newHandle] turns it into
 * [ReaderException.LoadFailed] at every construction instead (an ONNX Runtime without
 * OrtGetApiBase@VERS_1.28.2, say, after sherpa-onnx's was updated alone).
 */
internal object ReaderNative {
    private val loadError: Throwable? = try {
        System.loadLibrary("reader-onnx")
        null
    } catch (e: Throwable) {
        e
    }

    /** A new native reader, 0 when there is no memory for one. */
    @JvmStatic
    external fun create(): Long

    /** Loads the three graphs; [options] is [ReaderOptions.toIntArray]. A status. */
    @JvmStatic
    external fun load(handle: Long, encoder: ByteArray, decoder: ByteArray, decoderWithPast: ByteArray, options: IntArray): Int

    /** Loads SentencePiece's model.SRC. A status. */
    @JvmStatic
    external fun loadTokenizer(handle: Long, model: ByteArray): Int

    /** The pieces of [text] as UTF-8, or null; status[0] gets the status. */
    @JvmStatic
    external fun pieces(handle: Long, text: ByteArray, status: IntArray): Array<ByteArray>?

    /**
     * Translates [sourceIds], with no end-of-sentence before [minTokens] tokens but at the cap's
     * last. On success the ids go into [outIds] (at least the model's target positions long),
     * [outCounts] gets the id count, 1 if capped, and the decoder runs, and [outScore] the mean
     * log-probability. A status.
     */
    @JvmStatic
    external fun translate(
        handle: Long,
        sourceIds: IntArray,
        beam: Int,
        maxNewTokens: Int,
        minTokens: Int,
        outIds: IntArray,
        outCounts: IntArray,
        outScore: DoubleArray,
    ): Int

    /** Layers, heads and head size as the sessions give them, 0 for what isn't known yet. */
    @JvmStatic
    external fun dims(handle: Long): IntArray?

    /** Stops a running Run or load; any thread, any time before [release]. */
    @JvmStatic
    external fun cancel(handle: Long)

    /** The last failed call's message as UTF-8. */
    @JvmStatic
    external fun lastError(handle: Long): ByteArray?

    @JvmStatic
    external fun release(handle: Long)

    /** A new handle, the library's failure to load turned into the reader's exception. */
    fun newHandle(): Long {
        loadError?.let { throw ReaderException.LoadFailed("libreader-onnx did not load: ${it.message}") }
        val handle = try {
            create()
        } catch (e: LinkageError) {
            throw ReaderException.LoadFailed("libreader-onnx did not load: ${e.message}")
        }
        if (handle == 0L) throw ReaderException.OutOfMemory("no memory for a native reader")
        return handle
    }

    fun errorOf(handle: Long): String = lastError(handle)?.decodeToString().orEmpty()
}
