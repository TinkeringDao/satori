package com.satori.domain.reading

import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment

/**
 * The transcript as the AI steps read it (0.8.0, design 3.A, "readable segments"): the same lines,
 * with the same ids and times, each line the reader reads ([needsReader]) holding its English
 * reading in place of its text. Notes, names, to-dos and the map are written from it, with the
 * prompts of an English recording, so the AI model never reads Malayalam: a line the reader reads
 * that has no current reading is left out, never given as it is, and so is one whose reading is
 * empty (the reader's rules, D5). Every other line is given as it is. The transcript itself, which
 * the owner reads and edits, stays as it was said (D8). Pure, for the repository and the steps.
 */
object ReadableSegments {

    /**
     * Whether the reader reads [text]: it holds a letter or a sign of the Malayalam script, the
     * only script the reader turns into English and one the AI model can't read. A line the
     * Malayalam speech model wrote is in it whole; a line in Latin letters (English or German, or
     * digits alone) is not, and the AI steps read it as it is. A single Malayalam letter is enough:
     * no line goes to the AI model with Malayalam in it.
     */
    fun needsReader(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (codePoint in MALAYALAM_BLOCK && (Character.isLetter(codePoint) || isMark(codePoint))) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * [segments] as the AI steps read them, in their order: a line the reader reads with its current
     * reading's text ([SegmentReading.isCurrentFor]), and left out when it has none or its reading is
     * empty; every other line as it is. A reading of an earlier text of the line (it was edited
     * since) counts as none.
     */
    fun of(segments: List<TranscriptionSegment>, readings: List<SegmentReading>): List<TranscriptionSegment> {
        if (segments.none { needsReader(it.text) }) return segments
        val byLine = readings.associateBy { it.segmentId }
        return segments.mapNotNull { segment ->
            if (!needsReader(segment.text)) return@mapNotNull segment
            val reading = byLine[segment.id]?.takeIf { it.isCurrentFor(segment) } ?: return@mapNotNull null
            if (reading.text.isBlank()) null else segment.copy(text = reading.text)
        }
    }

    /**
     * [segments] with each line's current, non-empty reading in place of its text, every other line
     * as it is, a line the reader reads that has none too (0.8.0, R6): the text the rules that judge
     * what the AI found in a recording read, without the AI (RecheckFoundTodosUseCase,
     * MoveFoundIdeasUseCase). A to-do found in a line's reading is judged by that reading, as it was
     * when it was found; one of a line that has none is judged by the line as it was said, as it was
     * before the reader. No AI model reads it.
     */
    fun withReadings(segments: List<TranscriptionSegment>, readings: List<SegmentReading>): List<TranscriptionSegment> {
        if (readings.isEmpty()) return segments
        val byLine = readings.associateBy { it.segmentId }
        return segments.map { segment ->
            val reading = byLine[segment.id]?.takeIf { needsReader(segment.text) && it.isCurrentFor(segment) && it.text.isNotBlank() }
            if (reading == null) segment else segment.copy(text = reading.text)
        }
    }

    /**
     * The lines of [segments] the reader has still to read, in their order: complete lines it reads
     * ([needsReader]) without a current reading. A line still being transcribed is not read: it
     * changes until it is complete.
     */
    fun unread(segments: List<TranscriptionSegment>, readings: List<SegmentReading>): List<TranscriptionSegment> {
        val byLine = readings.associateBy { it.segmentId }
        return segments.filter { segment ->
            segment.isComplete && needsReader(segment.text) && byLine[segment.id]?.isCurrentFor(segment) != true
        }
    }

    /**
     * The complete lines of [segments] the reader reads that have no reading at all, not even one of
     * an earlier text: lines the Reading step never got to. Their recording's AI steps don't run
     * without them (D7). A line edited after it was read has a reading, of its old text, and is no
     * such line: its edit leaves it out of the notes ([of]) but doesn't take the notes away.
     */
    fun neverRead(segments: List<TranscriptionSegment>, readings: List<SegmentReading>): List<TranscriptionSegment> {
        val read = readings.mapTo(HashSet()) { it.segmentId }
        return segments.filter { it.isComplete && needsReader(it.text) && it.id !in read }
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Unicode's Malayalam block: its letters, vowel signs, virama, chillus and digits. */
    private val MALAYALAM_BLOCK = 0x0D00..0x0D7F
}
