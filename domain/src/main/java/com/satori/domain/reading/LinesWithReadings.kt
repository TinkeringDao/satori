package com.satori.domain.reading

import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment

/**
 * A recording's lines as they were said, with the readings of its lines, read together
 * (com.satori.domain.repository.TranscriptionRepository.getLinesWithReadings), so that the two
 * views of a transcript the AI steps need are of one and the same transcript (0.8.0, design R6):
 * - [readable], the lines as the AI steps read them ([ReadableSegments.of]): each line the reader
 *   reads as its current reading, left out without one, so the AI model never reads Malayalam (D7);
 * - [said], the transcript as it was said (D8): the language the AI writes in is found in it, and a
 *   due time read in a line's reading must be one its line as it was said holds a time of (D9,
 *   [saidOfRead]).
 *
 * A recording without a line the reader reads ([readByReader] false) is read as it was said: its
 * [readable] lines are [said].
 *
 * @property said The lines as they were said, in the order given (by timestamp), complete or not
 * @property readings Every reading of them, current or stale, in no order
 */
class LinesWithReadings(val said: List<TranscriptionSegment>, val readings: List<SegmentReading>) {

    /** The lines as the AI steps read them, in [said]'s order, complete or not ([ReadableSegments.of]). */
    val readable: List<TranscriptionSegment> = ReadableSegments.of(said, readings)

    /**
     * Whether a line of the recording is one the reader reads ([ReadableSegments.needsReader]): its AI
     * steps read readings then, and the language they write in is the one of [said] (D8).
     */
    val readByReader: Boolean
        get() = said.any { ReadableSegments.needsReader(it.text) }

    /**
     * Whether a complete line the reader reads was never read at all ([ReadableSegments.neverRead]):
     * the Reading step never got to it. Work that replaces what the recording has doesn't run then
     * (D7): its notes would be written from the rest of what was said.
     */
    val hasNeverRead: Boolean
        get() = ReadableSegments.neverRead(said, readings).isNotEmpty()

    /**
     * The text, as it was said, of each line [readable] gives as its reading, by the line's id: the
     * Malayalam line a due time read in its English must have a time of (D9). Empty for a recording
     * read as it was said.
     */
    val saidOfRead: Map<String, String>
        get() {
            val given = readable.mapTo(HashSet()) { it.id }
            return said.filter { it.id in given && ReadableSegments.needsReader(it.text) }.associate { it.id to it.text }
        }
}
