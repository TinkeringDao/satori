package com.satori.domain.usecase.finish

import com.satori.domain.repository.LineNotReadException
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.LoadedReader
import com.satori.domain.repository.ReaderFailedException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.ReaderStoppedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// The double of the Reading step's domain tests (0.8.0, R5): the reader.

/**
 * The reader in memory: each line is read as [english] gives it (a made-up English line for each
 * synthetic Malayalam one), else as "English of <n> words", with the pieces, tokens and mean
 * log-probability [output] gives. [events] logs "load", "read <the English>" and "release" in order.
 *
 * - With [onPhone] false its files aren't there, and a load is refused for that.
 * - [refusal] refuses the load for another reason; [loadStops] stops it for memory; [loadFails]
 *   makes it fail.
 * - [failOn] lines are refused alone ([LineNotReadException]); [failsAfter] makes the native reader
 *   fail after that many lines ([ReaderFailedException]).
 * - [yieldsAt] (1 for the first line) is where a capture opens: the reader stops itself, as its own
 *   watch on the capture does ([ReaderStoppedException]), after the line before it was read.
 * - [holdAt] keeps that line's read waiting until it is cancelled (a stop) or [hold] completes; [held]
 *   completes as it waits.
 */
class FakeReader(
    var onPhone: Boolean = true,
    var english: Map<String, String> = emptyMap(),
    var output: (line: String, english: String) -> ReaderOutput = { line, text ->
        val pieces = line.split(' ').count { it.isNotBlank() } * 2
        ReaderOutput(text, pieces = pieces, tokens = pieces * 3 / 4, meanLogProb = -0.4, capped = false)
    }
) : ReaderRepository {
    override val model: String = "reader-test"
    val events = mutableListOf<String>()
    var refusal: ReaderNotLoadedException.Why? = null
    var loadStops = false
    var loadFails = false
    var failOn = setOf<String>()
    var failsAfter: Int? = null
    var yieldsAt: Int? = null
    var holdAt: Int? = null
    val hold = CompletableDeferred<Unit>()
    val held = CompletableDeferred<Unit>()

    /** Called with each line as it is read, before it is: a test edits the transcript there, say. */
    var onRead: suspend (line: String) -> Unit = {}

    private var reads = 0

    override suspend fun isOnPhone(): Boolean = onPhone

    override suspend fun <R> withReader(use: suspend (LoadedReader) -> R): R {
        if (!onPhone) throw ReaderNotLoadedException(ReaderNotLoadedException.Why.NOT_ON_PHONE, "not on the phone")
        refusal?.let { throw ReaderNotLoadedException(it, "refused") }
        if (loadStops) throw LoadStoppedForMemoryException("stopped for memory")
        if (loadFails) throw ReaderFailedException("load failed")
        events += "load"
        try {
            return use(LoadedReader { line -> read(line) })
        } finally {
            withContext(NonCancellable) { events += "release" }
        }
    }

    private suspend fun read(line: String): ReaderOutput {
        val at = ++reads
        onRead(line)
        if (at == yieldsAt) throw ReaderStoppedException("a capture opened")
        if (at == holdAt) {
            held.complete(Unit)
            hold.await()
        }
        failsAfter?.let { if (at > it) throw ReaderFailedException("the native reader failed") }
        if (line in failOn) throw LineNotReadException("refused")
        val text = english[line] ?: "English of ${line.split(' ').count { it.isNotBlank() }} words"
        events += "read $text"
        return output(line, text)
    }
}
