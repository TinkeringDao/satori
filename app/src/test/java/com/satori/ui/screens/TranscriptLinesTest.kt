package com.satori.ui.screens

import com.satori.domain.model.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptLinesTest {

    private fun saved(id: String) = TranscriptionSegment(id, "s", "Saved line $id.", 0L, isComplete = true)

    // Every update of the words being heard arrives with a new id, as from SttRepositoryImpl.
    private fun heard(id: String) = TranscriptionSegment(id, "s", "Words being heard $id", 0L, isComplete = false)

    private val c1 = saved("c1")
    private val c2 = saved("c2")
    private val c3 = saved("c3")

    /** Feeds the recorder's lines in turn, as the screen would see them, and returns what it shows each time. */
    private fun show(vararg steps: List<TranscriptionSegment>, isRecording: Boolean = true): List<List<TranscriptionSegment>> {
        var lines = TranscriptLines.None
        return steps.map { segments ->
            lines = lines.next(segments, isRecording)
            lines.lines
        }
    }

    @Test
    fun `shows the saved lines, then the words being heard`() {
        val words = heard("h1")
        assertEquals(listOf(listOf(c1, c2, words)), show(listOf(c1, c2, words)))
    }

    @Test
    fun `hands the recorder's own list on when it adds nothing`() {
        val segments = listOf(c1, c2)
        assertSame(segments, TranscriptLines.None.next(segments, isRecording = true).lines)
    }

    @Test
    fun `keeps the words being heard until their saved line arrives`() {
        val words = heard("h1")
        val shown = show(
            listOf(c1, c2, words),
            // The recorder has saved the line and dropped the words; the database hasn't answered yet.
            listOf(c1, c2),
            listOf(c1, c2, c3)
        )
        assertEquals(listOf(c1, c2, words), shown[1])
        assertEquals(listOf(c1, c2, c3), shown[2])
    }

    @Test
    fun `keeps them as long as nothing changes`() {
        val words = heard("h1")
        val shown = show(listOf(c1, words), listOf(c1), listOf(c1))
        assertEquals(listOf(c1, words), shown[2])
    }

    @Test
    fun `never keeps words whose saved line came first`() {
        val words = heard("h1")
        val shown = show(
            listOf(c1, words),
            // The database answered before the recorder dropped the words.
            listOf(c1, c2, words),
            listOf(c1, c2)
        )
        assertEquals(listOf(c1, c2, words), shown[1])
        assertEquals(listOf(c1, c2), shown[2])
    }

    @Test
    fun `new words replace the kept ones`() {
        val first = heard("h1")
        val next = heard("h2")
        val shown = show(listOf(c1, first), listOf(c1), listOf(c1, next))
        assertEquals(listOf(c1, next), shown[2])
    }

    @Test
    fun `keeps new words through the save of their own line`() {
        // The database answered first for the line before; the next words then end the usual way.
        val first = heard("h1")
        val next = heard("h2")
        val shown = show(
            listOf(c1, first),
            listOf(c1, c2, first),
            listOf(c1, c2, next),
            listOf(c1, c2),
            listOf(c1, c2, c3)
        )
        assertEquals(listOf(c1, c2, next), shown[3])
        assertEquals(listOf(c1, c2, c3), shown[4])
    }

    @Test
    fun `keeps nothing once recording has stopped`() {
        var lines = TranscriptLines.None.next(listOf(c1, heard("h1")), isRecording = true)
        lines = lines.next(listOf(c1), isRecording = false)
        assertEquals(listOf(c1), lines.lines)
    }

    @Test
    fun `the words being heard keep one key while they change`() {
        val first = TranscriptLines.keyOf(heard("h1"), isNewest = true)
        val next = TranscriptLines.keyOf(heard("h2"), isNewest = true)
        assertEquals(first, next)
        assertNotEquals(first, TranscriptLines.keyOf(c1, isNewest = true))
    }

    @Test
    fun `saved lines are keyed by their id`() {
        assertEquals("c1", TranscriptLines.keyOf(c1, isNewest = true))
        assertEquals("c1", TranscriptLines.keyOf(c1, isNewest = false))
    }

    @Test
    fun `an unfinished line that isn't the newest keeps its id`() {
        // Only one line can have the shared key, so two can never clash.
        assertEquals("h1", TranscriptLines.keyOf(heard("h1"), isNewest = false))
    }
}
