package com.satori.domain.reading

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Where the reader stands for a recording whose lines only it reads (design D7). */
enum class ReaderStatus {
    /** Its files are on the phone and checked: the Reading step reads with it. */
    PRESENT,

    /**
     * Not on the phone yet, and on its way: the first run has its part (READER) planned and not
     * dropped. It may still wait for Wi-Fi or for AI notes to be switched on. A Malayalam
     * recording's finishing waits for it at its Reading step, as for the AI model.
     */
    ON_ITS_WAY,

    /**
     * Not on the phone and not coming: never planned (Malayalam not wanted), or its download was
     * dropped. A Malayalam recording is then finished without its AI steps, never with the AI model
     * reading Malayalam, and gets them once the reader is on the phone (design R9).
     */
    NOT_COMING
}

/**
 * The reader's availability as the finisher of recordings asks it (0.8.0, design D7 and R5): a job
 * whose recording needs the reader waits while it is [ReaderStatus.ON_ITS_WAY], and is let go once it
 * is on the phone or not coming. The first run implements it (design R7, FirstRunReaderAvailability:
 * its READER part, and the reader's files, which count as present when they are on the phone, whole
 * and found with their pinned SHA-256, wherever they came from).
 */
interface ReaderAvailability {

    /** Where the reader stands now. May read the reader's files: not on the main thread. */
    suspend fun status(): ReaderStatus

    /**
     * Emits whenever [status] may have changed: the first run planned or dropped the reader, a
     * download of it ended. The finisher looks again at each, and every 30 s besides while a job
     * waits, for files put on the phone another way. None by default.
     */
    val changes: Flow<Unit>
        get() = emptyFlow()
}
