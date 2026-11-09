package com.satori.ui.export

import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.RecordingAudio
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared audio goes out as the file it is kept in: a WAV, or an MPEG-4 audio file once compressed. */
class AudioShareTest {

    private val wav = RecordingAudio("rec-1", path = "/data/audio/rec-1.wav", durationMs = 54_000L)
    private val compressed = wav.copy(path = "/data/audio/rec-1.m4a", encoding = AudioEncoding.AAC)

    @Test
    fun `a WAV is shared as a WAV`() {
        assertEquals("audio/wav", AudioShare.mimeType(wav))
        assertEquals("2026-10-09 22-39 Budget review.wav", AudioShare.fileName("2026-10-09 22-39 Budget review", wav))
    }

    @Test
    fun `compressed audio is shared as an m4a file`() {
        assertEquals("audio/mp4", AudioShare.mimeType(compressed))
        assertEquals("2026-10-09 22-39 Budget review.m4a", AudioShare.fileName("2026-10-09 22-39 Budget review", compressed))
    }
}
