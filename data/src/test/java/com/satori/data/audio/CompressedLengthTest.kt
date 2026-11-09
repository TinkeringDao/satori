package com.satori.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedLengthTest {

    @Test
    fun `three AAC frames at 16 kHz are 192 ms`() {
        assertEquals(192L, CompressedLength.FRAMING_MS)
    }

    @Test
    fun `a copy as long as its WAV or longer by the codec's frames holds it whole`() {
        assertTrue(CompressedLength.matches(wavMs = 10_000, aacMs = 10_000))
        assertTrue(CompressedLength.matches(wavMs = 10_000, aacMs = 10_128))
        assertTrue(CompressedLength.matches(wavMs = 10_000, aacMs = 10_000 + 192 + 100))
    }

    @Test
    fun `a copy shorter by more than 100 ms was cut short`() {
        assertTrue(CompressedLength.matches(wavMs = 10_000, aacMs = 9_900))
        assertFalse(CompressedLength.matches(wavMs = 10_000, aacMs = 9_899))
        assertFalse(CompressedLength.matches(wavMs = 3_600_000, aacMs = 1_800_000))
    }

    @Test
    fun `a copy longer than the codec's frames allow has wrong times`() {
        assertFalse(CompressedLength.matches(wavMs = 10_000, aacMs = 10_000 + 192 + 101))
        assertFalse(CompressedLength.matches(wavMs = 10_000, aacMs = 20_000))
    }
}
