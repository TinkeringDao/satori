package com.satori.feature.stt.datasource

import com.satori.feature.stt.datasource.SherpaOnnxDataSource.SampleBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleBufferTest {

    private companion object {
        // As in SherpaOnnxDataSource: 100 ms reads, 512-sample VAD windows, and the 3 s
        // (CONTEXT_CARRY_OVER_SAMPLES) the processing loop keeps while nobody speaks.
        const val CHUNK = 1_600
        const val VAD_WINDOW = 512
        const val KEEP = 48_000
    }

    /** A sample value that names its place in the stream, exact as a float. */
    private fun valueAt(index: Long) = (index % 1_000_000).toFloat()

    @Test
    fun `keeps everything until the VAD is more than twice the kept length in`() {
        val buffer = SampleBuffer(initialCapacity = 16)
        buffer.append(FloatArray(50) { it.toFloat() })

        assertEquals(40, buffer.trimBefore(position = 40, keep = 20))
        assertArrayEquals(FloatArray(50) { it.toFloat() }, buffer.getRange(0, buffer.size), 0f)
    }

    @Test
    fun `drops all but the kept samples before the VAD and keeps what it has yet to read`() {
        val buffer = SampleBuffer(initialCapacity = 16)
        buffer.append(FloatArray(100) { it.toFloat() })

        assertEquals(20, buffer.trimBefore(position = 70, keep = 20))
        // 50..69 are the kept lookback, 70..99 what the VAD hasn't read.
        assertArrayEquals(FloatArray(50) { 50f + it }, buffer.getRange(0, buffer.size), 0f)
    }

    @Test
    fun `an hour without speech stays within a few seconds and keeps what a speech start needs`() {
        val buffer = SampleBuffer()
        var appended = 0L
        var offset = 0
        var largest = 0
        repeat(36_000) { // an hour of 100 ms reads
            val first = appended
            buffer.append(FloatArray(CHUNK) { valueAt(first + it) })
            appended += CHUNK
            // What the processing loop does with each read while no speech is detected.
            while (offset + VAD_WINDOW <= buffer.size) offset += VAD_WINDOW
            offset = buffer.trimBefore(offset, KEEP)
            largest = maxOf(largest, buffer.size)
        }

        // About 6 s at most, within the 10 s the buffer starts with, so it never grows.
        assertTrue("largest was $largest", largest <= 2 * KEEP + VAD_WINDOW + CHUNK)
        // The 3 s before the VAD, which hold the 0.4 s lookback of a speech start, are there.
        assertTrue("offset was $offset", offset >= KEEP)
        // And the buffer is the end of the stream, unread samples included, in order.
        val first = appended - buffer.size
        assertArrayEquals(FloatArray(buffer.size) { valueAt(first + it) }, buffer.getRange(0, buffer.size), 0f)
    }
}
