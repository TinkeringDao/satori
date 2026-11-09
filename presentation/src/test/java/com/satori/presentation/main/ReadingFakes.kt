package com.satori.presentation.main

import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.LoadedReader
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.finish.ReadingStep
import kotlinx.coroutines.CompletableDeferred
import java.util.Collections

// The reader of the page's and the map's AI work (0.8.0, R6), for their view models' tests.

/**
 * The reader in memory: each line read as [english] gives it (a made-up English line for each made-up
 * Malayalam one), else "English of the line". [log] hears "reader load", "reader read" and "reader
 * release" in order, beside whatever else a test logs there. With [onPhone] false its files aren't
 * there; [refusal] refuses the load for something it never loads beside.
 */
internal class ReaderHere(
    var onPhone: Boolean = true,
    var english: Map<String, String> = emptyMap(),
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
) : ReaderRepository {
    override val model: String = "reader-test"

    @Volatile
    var refusal: ReaderNotLoadedException.Why? = null

    /** Held inside the next line read, once, so a test can start a recorder while the reader reads. */
    @Volatile
    var readPause: Pause? = null

    /** Completed as the reader is released, from whatever thread releases it. */
    val released = CompletableDeferred<Unit>()

    override suspend fun isOnPhone(): Boolean = onPhone

    override suspend fun <R> withReader(use: suspend (LoadedReader) -> R): R {
        if (!onPhone) throw ReaderNotLoadedException(ReaderNotLoadedException.Why.NOT_ON_PHONE, "not on the phone")
        refusal?.let { throw ReaderNotLoadedException(it, "refused") }
        log += "reader load"
        try {
            return use(
                LoadedReader { line ->
                    log += "reader read"
                    readPause?.let { pause ->
                        readPause = null
                        pause.here()
                    }
                    ReaderOutput(english[line] ?: "English of the line", pieces = 20, tokens = 15, meanLogProb = -0.4, capped = false)
                }
            )
        } finally {
            log += "reader release"
            released.complete(Unit)
        }
    }
}

/** The readings the page's and the map's AI work make first, of [recordings] with [reader], the AI model [engine]'s. */
internal fun readingsFirst(recordings: TranscriptionRepository, reader: ReaderRepository, engine: LlmRepository) =
    EnsureReadingsUseCase(ReadingStep(recordings, reader, engine))
