package com.satori.domain.model

import com.satori.domain.fakes.segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingAudioTest {

    // A recording created at 10:00:00.000 (as epoch ms, any value does) whose capture began 2 s later.
    private val created = 1_000_000L
    private val firstRun = AudioRun(startedAt = created + 2_000, startMs = 0)

    private fun audio(
        durationMs: Long = 60_000,
        runs: List<AudioRun> = listOf(firstRun),
        lines: Map<String, AudioSpan> = emptyMap()
    ) = RecordingAudio(sessionId = "s1", path = "/audio/s1.wav", durationMs = durationMs, runs = runs, lines = lines)

    @Test
    fun `a line plays from where it was said, not from its stamp at its end`() {
        val line = segment("hello there", at = created + 9_000)
        val audio = audio(lines = mapOf(line.id to AudioSpan(startMs = 3_100, endMs = 6_400)))

        assertEquals(3_100, audio.startOf(line, created))
    }

    @Test
    fun `a line the index lost plays from a few seconds before its stamp, never before the start`() {
        val late = segment("late", at = created + 2_000 + 20_000)
        val early = segment("early", at = created + 2_000 + 1_000)

        assertEquals(20_000 - RecordingAudio.UNKNOWN_LINE_LEAD_MS, audio().startOf(late, created))
        assertEquals(0, audio().startOf(early, created))
    }

    @Test
    fun `a place past the end of the audio is held at its end`() {
        val line = segment("cut off by a crash", at = created + 90_000)
        val audio = audio(durationMs = 10_000, lines = mapOf(line.id to AudioSpan(12_000, 14_000)))

        assertEquals(10_000, audio.startOf(line, created))
    }

    @Test
    fun `a second capture plays after the first, and the time between them is held at the first one's end`() {
        // The first capture ran 30 s; after an error the second began at +40 s.
        val second = AudioRun(startedAt = created + 2_000 + 40_000, startMs = 30_000)
        val audio = audio(durationMs = 50_000, runs = listOf(firstRun, second))

        assertEquals(10_000, audio.positionAt(created + 12_000, created))
        assertEquals(30_000, audio.positionAt(created + 2_000 + 35_000, created))
        assertEquals(35_000, audio.positionAt(created + 2_000 + 45_000, created))
        assertEquals(0, audio.positionAt(created, created))
    }

    @Test
    fun `without an index the recording's start is the clock`() {
        val audio = audio(runs = emptyList())

        assertEquals(7_000, audio.positionAt(created + 7_000, created))
        assertEquals(created + 7_000, audio.wallClockAt(7_000, created))
    }

    @Test
    fun `a place in the audio reads on the clock of the capture it belongs to`() {
        val second = AudioRun(startedAt = created + 2_000 + 40_000, startMs = 30_000)
        val audio = audio(durationMs = 50_000, runs = listOf(firstRun, second))

        assertEquals(created + 2_000 + 10_000, audio.wallClockAt(10_000, created))
        assertEquals(created + 2_000 + 45_000, audio.wallClockAt(35_000, created))
    }

    @Test
    fun `the line playing is the last one started`() {
        val first = segment("one", at = created + 8_000)
        val second = segment("two", at = created + 15_000)
        val audio = audio(
            lines = mapOf(
                first.id to AudioSpan(2_000, 5_000),
                second.id to AudioSpan(9_000, 12_000)
            )
        )
        val lines = listOf(first, second)

        assertNull(audio.lineAt(1_999, lines, created))
        assertEquals(first.id, audio.lineAt(2_000, lines, created))
        // The pause after a line still shows that line.
        assertEquals(first.id, audio.lineAt(7_000, lines, created))
        assertEquals(second.id, audio.lineAt(9_000, lines, created))
    }
}
