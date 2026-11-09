package com.satori.ui.screens

import com.satori.domain.model.TranscriptionSegment

/**
 * The lines the recorder's transcript shows, oldest first: the saved lines, then the line that
 * is still being heard.
 *
 * A line that ends reaches the screen in two steps. The recorder drops the line being heard as
 * soon as it has saved the finished one, and the saved line arrives from the database a few
 * milliseconds later. In between, the line being heard is kept on screen, so the newest line
 * doesn't vanish for a frame and the transcript, which follows it, doesn't jump down and back up.
 * It is kept only while recording, and only until another line is saved or new words are heard,
 * so it is never shown next to its own saved version (the database can also be the quicker one).
 *
 * Immutable: [next] gives the lines after the recorder's lines changed.
 */
internal class TranscriptLines private constructor(
    /** What to draw, oldest first. Only the last line can be one that is still being heard. */
    val lines: List<TranscriptionSegment>,
    // The line being heard that is on screen, heard now or kept, and how many saved lines there
    // were when it arrived (each update of the words arrives as a new line).
    private val heard: TranscriptionSegment?,
    private val savedWhenHeard: Int
) {

    /**
     * The lines once the recorder shows [segments]: its saved lines, then the line being heard
     * if there is one (MainUiState.allSegments).
     */
    fun next(segments: List<TranscriptionSegment>, isRecording: Boolean): TranscriptLines {
        val nowHeard = segments.lastOrNull()?.takeUnless { it.isComplete }
        val saved = if (nowHeard == null) segments.size else segments.size - 1
        return when {
            // A saved line that arrives while the same words are on screen is theirs: it doesn't
            // count as one saved after them.
            nowHeard != null ->
                TranscriptLines(segments, nowHeard, if (nowHeard == heard) savedWhenHeard else saved)
            heard != null && isRecording && saved == savedWhenHeard ->
                TranscriptLines(segments + heard, heard, savedWhenHeard)
            else -> TranscriptLines(segments, null, saved)
        }
    }

    companion object {
        /** Before the recorder has shown anything. */
        val None = TranscriptLines(emptyList(), null, 0)

        /**
         * The list key of [segment], which is the newest line if [isNewest]. Each update of the
         * line being heard arrives with a new id, so that line has one key of its own instead: the
         * list then updates its text in place rather than replacing it at every word.
         */
        fun keyOf(segment: TranscriptionSegment, isNewest: Boolean): String =
            if (isNewest && !segment.isComplete) LIVE_KEY else segment.id

        // Not an id: segment ids are UUIDs.
        private const val LIVE_KEY = "live-line"
    }
}
