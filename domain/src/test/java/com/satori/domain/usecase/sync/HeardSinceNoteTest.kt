package com.satori.domain.usecase.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardSinceNoteTest {

    @Test
    fun `the words are counted as the segments come`() {
        val heard = HeardSinceNote(maxChars = 100)
        // The last note read the first one whole, as the line the recognizer was on.
        heard.add(HeardSegment("a", ""))
        heard.add(HeardSegment("b", "Book the room"))
        heard.add(HeardSegment("c", "  for Friday,   please  "))

        assertEquals(6, heard.words)
        assertEquals(listOf("a", "b", "c"), heard.segments.map { it.id })

        heard.clear()

        assertEquals(0, heard.words)
        assertTrue(heard.segments.isEmpty())
    }

    @Test
    fun `a note reads and cites what it would have, though what it can't reach is dropped`() {
        val maxChars = 40
        val heard = HeardSinceNote(maxChars)
        val all = mutableListOf<HeardSegment>()
        // Lines of all lengths, some with whitespace at either end; the first one's words were
        // read by the last note.
        val shapes = listOf("call the bank", " and the school", "then lunch  ", "ok", "  ask about the dates of the trip ")
        val said = listOf(HeardSegment("s0", "")) + (1..80).map { i -> HeardSegment("s$i", shapes[i % shapes.size] + " $i") }

        for (segment in said) {
            heard.add(segment)
            all += segment
            for (partial in listOf("", "and then", " ")) {
                val bounded = NoteInput.of(heard.segments, partial, maxChars)
                val whole = NoteInput.of(all, partial, maxChars)
                assertEquals(segment.id, whole.prompt, bounded.prompt)
                assertEquals(segment.id, whole.sourceIds, bounded.sourceIds)
            }
            assertEquals(segment.id, heard.segments.sumOf { wordsIn(it.text) }, heard.words)
        }

        // What is kept stays about what one note can read, however much was said.
        assertTrue(all.sumOf { it.text.length } > 20 * maxChars)
        assertTrue(heard.segments.sumOf { it.text.length } <= 4 * maxChars)
        // The segment the last note read whole is still there, to be cited.
        assertEquals("s0", heard.segments.first().id)
    }

    @Test
    fun `a segment longer than a note can read keeps nothing before it`() {
        val heard = HeardSinceNote(maxChars = 10)
        heard.add(HeardSegment("a", "one two three"))
        heard.add(HeardSegment("b", "four five"))
        heard.add(HeardSegment("c", "a line far longer than one note can read"))

        assertEquals(listOf("c"), heard.segments.map { it.id })
        assertEquals(9, heard.words)
    }
}
