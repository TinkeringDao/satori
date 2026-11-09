package com.satori.domain.usecase.transcription

/**
 * Where the joining of transcript lines says what it did, for the field log: line counts and
 * recording ids ([TranscriptJoiner.Joined.summary]), never what was said. The domain has no
 * logger, so the app binds one.
 */
fun interface LinesLog {

    /**
     * @param line What happened
     * @param failure Why it failed, when it did; the transcript is then as it was
     */
    fun write(line: String, failure: Throwable?)

    companion object {
        /** Keeps nothing. */
        val NONE = LinesLog { _, _ -> }
    }
}
