package com.satori.domain.repository

/**
 * The reader as the finishing uses it (0.8.0, design 3.A): IndicTrans2, the model that reads a
 * Malayalam line into English, on the app's own ONNX Runtime (feature-reader's ReaderDataSource on
 * lib-reader-onnx). One reader at a time, loaded for a block of reading and gone after it.
 *
 * A load is a model load like any other: paced (the one-load gate with the recorder first, a
 * pre-read of its files, room for its parse), never beside a capture that is live or opening, the
 * live speech model or an AI model ([ReaderNotLoadedException]), and the native heap is purged
 * right before it, so what an AI model's unload left doesn't count against it, and right after its
 * release. A capture that opens while it loads or reads stops it at once, as a cancel of the caller
 * does (design D14: ONNX Runtime's load-cancellation and terminate flags), and it is released before
 * anything else happens. The caller holds the AI work gate and lets the AI model go first.
 */
interface ReaderRepository {

    /** The id of the reader's build that reads ([com.satori.domain.model.ReaderModelId.id]), kept with each reading. */
    val model: String

    /**
     * Whether the reader's files are on the phone and whole, as a load checks them: each file's
     * size and the first bytes of each graph. Reads the files; not on the main thread.
     */
    suspend fun isOnPhone(): Boolean

    /**
     * Loads the reader, runs [use] with it, then releases it and purges the native heap, however
     * [use] ends.
     *
     * @throws ReaderNotLoadedException refused before anything was loaded, for its reason
     * @throws ReaderStoppedException a capture opened while it loaded or read: stopped at once and released
     * @throws LoadStoppedForMemoryException the paced load waited as long as it may for memory
     * @throws ReaderFailedException the native reader refused its files, or failed as it loaded or read
     */
    suspend fun <R> withReader(use: suspend (LoadedReader) -> R): R
}

/** The reader loaded ([ReaderRepository.withReader]): one line at a time. */
fun interface LoadedReader {

    /**
     * [line] in English, read whole: cut where its pieces would be more than the model takes (D15),
     * each part's reading capped at D15's length, the parts joined.
     *
     * @throws LineNotReadException the reader refused the line or failed on it; it stays loaded
     */
    suspend fun read(line: String): ReaderOutput
}

/**
 * One line read: its English [text] and what design D5's rules look at (the Reading step's
 * [com.satori.domain.reading.ReadingRules]).
 *
 * @property pieces The source pieces read, the language tags and the end left out
 * @property tokens The English tokens written, the end left out
 * @property meanLogProb The mean log-probability per token over all parts, the end counted; null for
 *   a line with no piece to read; negative infinity when the model allowed nothing but the end
 * @property capped Whether a part's reading reached D15's cap without its own end
 */
data class ReaderOutput(
    val text: String,
    val pieces: Int,
    val tokens: Int,
    val meanLogProb: Double?,
    val capped: Boolean
)

/**
 * The reader was not loaded, for [why]; nothing was read. Every reason but [Why.NOT_ON_PHONE] passes:
 * the load may be tried again later.
 */
class ReaderNotLoadedException(val why: Why, message: String) : Exception(message) {
    enum class Why {
        /** Its files are missing, damaged or can't be read. */
        NOT_ON_PHONE,

        /** A capture is live or opening (the reader waits for it to end, design O5). */
        CAPTURE,

        /** The live speech model is still in memory. */
        SPEECH_MODEL,

        /** An AI model is in memory: the caller lets it go first. */
        AI_MODEL
    }
}

/** The reader stopped at once for a capture that opened (design D14), and was released. */
class ReaderStoppedException(message: String) : Exception(message)

/** The reader could not read one line: it refused the line, or its run failed on it. It stays loaded. */
class LineNotReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The native reader refused its files, or failed as it loaded or read (out of memory, say); it was released. */
class ReaderFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
