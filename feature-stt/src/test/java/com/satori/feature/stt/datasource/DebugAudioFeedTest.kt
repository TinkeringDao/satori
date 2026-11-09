package com.satori.feature.stt.datasource

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DebugAudioFeedTest {

    private fun wav(rate: Int = 16_000, channels: Int = 1, bits: Int = 16, samples: ShortArray, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 3 + 1 else 0
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(12 + 24 + extra + 8 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(4 + 24 + extra + 8 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk) buffer.put("FLLR".toByteArray()).putInt(3).put(byteArrayOf(0, 0, 0, 0))
        buffer.put("data".toByteArray()).putInt(data)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    @Test
    fun `reads 16 kHz mono 16-bit PCM`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(samples, DebugAudioFeed.parse(wav(samples = samples)))
    }

    @Test
    fun `skips other chunks, odd-sized ones padded`() {
        val samples = shortArrayOf(5, 6, 7)
        assertArrayEquals(samples, DebugAudioFeed.parse(wav(samples = samples, extraChunk = true)))
    }

    @Test
    fun `refuses other formats and junk`() {
        assertNull(DebugAudioFeed.parse(wav(rate = 44_100, samples = shortArrayOf(1))))
        assertNull(DebugAudioFeed.parse(wav(channels = 2, samples = shortArrayOf(1, 2))))
        assertNull(DebugAudioFeed.parse(wav(bits = 8, samples = shortArrayOf(1))))
        assertNull(DebugAudioFeed.parse(ByteArray(0)))
        assertNull(DebugAudioFeed.parse("not a wave file at all".toByteArray()))
    }
}
