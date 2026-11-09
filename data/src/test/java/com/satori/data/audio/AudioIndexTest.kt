package com.satori.data.audio

import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioIndexTest {

    private val first = AudioRun(startedAt = 1_700_000_000_000, startMs = 0)
    private val second = AudioRun(startedAt = 1_700_000_900_000, startMs = 61_250)

    @Test
    fun `entries read back as they were written`() {
        val text = AudioIndex.run(first) +
            AudioIndex.line("3f2a-11", AudioSpan(400, 2_100)) +
            AudioIndex.run(second) +
            AudioIndex.line("9c0d-22", AudioSpan(61_300, 64_000))

        val entries = AudioIndex.parse(text)

        assertEquals(listOf(first, second), entries.runs)
        assertEquals(mapOf("3f2a-11" to AudioSpan(400, 2_100), "9c0d-22" to AudioSpan(61_300, 64_000)), entries.lines)
    }

    @Test
    fun `an entry cut short by a crash costs only itself`() {
        val text = AudioIndex.run(first) + AudioIndex.line("a", AudioSpan(0, 900)) + "line b 1200"

        val entries = AudioIndex.parse(text)

        assertEquals(listOf(first), entries.runs)
        assertEquals(mapOf("a" to AudioSpan(0, 900)), entries.lines)
    }

    @Test
    fun `of two entries for one line the later counts`() {
        val text = AudioIndex.line("a", AudioSpan(0, 900)) + AudioIndex.line("a", AudioSpan(100, 950))

        assertEquals(mapOf("a" to AudioSpan(100, 950)), AudioIndex.parse(text).lines)
    }

    @Test
    fun `junk and impossible places are skipped`() {
        val text = "hello\n\nrun soon 0\nrun 5 -3\nline backwards 500 100\nline early -5 10\nline  spaced   10   20  \n"

        val entries = AudioIndex.parse(text)

        assertEquals(emptyList<AudioRun>(), entries.runs)
        assertEquals(mapOf("spaced" to AudioSpan(10, 20)), entries.lines)
    }

    @Test
    fun `a line id that couldn't be read back isn't written`() {
        assertNull(AudioIndex.line("two words", AudioSpan(0, 1)))
        assertNull(AudioIndex.line("", AudioSpan(0, 1)))
    }

    @Test
    fun `a whole index keeps its captures in order and its lines`() {
        val lines = mapOf("x" to AudioSpan(10, 20), "y" to AudioSpan(61_400, 62_000))

        val entries = AudioIndex.parse(AudioIndex.format(listOf(second, first), lines, kept = false))

        assertEquals(listOf(first, second), entries.runs)
        assertEquals(lines, entries.lines)
    }

    @Test
    fun `audio is kept by the last keep entry, and without one it is not`() {
        assertFalse(AudioIndex.parse(AudioIndex.run(first)).kept)
        assertTrue(AudioIndex.parse(AudioIndex.run(first) + AudioIndex.keep(true)).kept)
        assertFalse(AudioIndex.parse(AudioIndex.keep(true) + AudioIndex.keep(false)).kept)
        assertTrue(AudioIndex.parse(AudioIndex.keep(false) + AudioIndex.keep(true) + AudioIndex.line("a", AudioSpan(0, 9))).kept)
    }

    @Test
    fun `a keep entry that can't be read counts for nothing`() {
        assertTrue(AudioIndex.parse(AudioIndex.keep(true) + "keep\nkeep 2\nkeep yes\nkeep 0 1\n").kept)
    }

    @Test
    fun `a whole index keeps the audio only when it is kept`() {
        val lines = mapOf("x" to AudioSpan(10, 20))

        val kept = AudioIndex.format(listOf(first), lines, kept = true)
        val notKept = AudioIndex.format(listOf(first), lines, kept = false)

        assertTrue(AudioIndex.parse(kept).kept)
        assertFalse(AudioIndex.parse(notKept).kept)
        assertFalse("keep" in notKept)
        assertEquals(lines, AudioIndex.parse(kept).lines)
    }

    @Test
    fun `the keep entry survives an index written anew with a new transcript's lines`() {
        // What a capture, two later keep switches and the old lines leave, read and written again
        // as replaceLines does.
        val old = AudioIndex.parse(
            AudioIndex.run(first) + AudioIndex.line("old", AudioSpan(0, 900)) + AudioIndex.keep(false) + AudioIndex.keep(true)
        )
        val newLines = mapOf("new-1" to AudioSpan(10, 400), "new-2" to AudioSpan(500, 900))

        val rewritten = AudioIndex.parse(AudioIndex.format(old.runs, newLines, old.kept))

        assertTrue(rewritten.kept)
        assertEquals(newLines, rewritten.lines)
        assertEquals(listOf(first), rewritten.runs)
    }

    @Test
    fun `the last capture ends where the audio after the last run ends`() {
        val entries = AudioIndex.parse(AudioIndex.run(second) + AudioIndex.run(first))

        assertEquals(second.startedAt + (90_000 - second.startMs), entries.lastCaptureEndMs(durationMs = 90_000))
        // Audio shorter than its last run says (a file cut short) ends as that run starts.
        assertEquals(second.startedAt, entries.lastCaptureEndMs(durationMs = second.startMs - 1))
        assertNull(AudioIndex.parse("").lastCaptureEndMs(durationMs = 90_000))
    }
}
