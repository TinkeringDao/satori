package com.satori.domain.repository

/**
 * Which recordings' audio is being read now (played, transcribed again), so that compressing or
 * deleting older audio leaves it alone. Uses nest: audio that two readers [begin] stays in use
 * until both [end]. Safe to call from any thread.
 */
interface AudioUsage {

    /** A reader starts on the audio of [sessionId]. */
    fun begin(sessionId: String)

    /** A reader of the audio of [sessionId] is done. One without a [begin] before it is ignored. */
    fun end(sessionId: String)

    /** The recordings whose audio is being read now. */
    fun inUse(): Set<String>
}
