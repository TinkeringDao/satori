package com.satori.domain.finish

/**
 * The holder keys of the LLM processing service
 * ([com.satori.domain.service.LlmProcessingServiceController]): each piece of AI work
 * takes its own, and the service runs while any is held, so one piece ending never stops it under
 * another.
 */
object ProcessingHolders {

    /** A recording queued or being finished. */
    fun finishing(sessionId: String) = "finish:$sessionId"

    /** Write or Rewrite notes of a recording. */
    fun rewrite(sessionId: String) = "rewrite:$sessionId"

    /** Transcribe again of a recording. */
    fun transcribe(sessionId: String) = "transcribe:$sessionId"

    /** Find the to-dos and ideas of a recording again. */
    fun todos(sessionId: String) = "todos:$sessionId"

    /** A debug build's rerun of a finishing step of a recording. */
    fun rerun(sessionId: String) = "rerun:$sessionId"

    /** The mind map's work on one theme or recording. */
    const val MAP = "map"

    /** "Map N older recordings". */
    const val MAP_OLDER = "map-older"
}
