package com.satori.domain.usecase.finish

/**
 * The finishing step [FinishStep.Lines]: joins the transcript lines of a recording that the
 * speech recognizer cut mid-sentence, with everything that cites them pointed at the joined
 * lines. Runs with the AI off too, and can be repeated: a transcript with nothing left to join
 * stays as it is.
 */
fun interface TranscriptLinesStep {

    /** @return How many lines it joined away: the recording has that many lines fewer */
    suspend fun join(sessionId: String): Int
}
