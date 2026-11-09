package com.satori.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun samples(count: Int) = ShortArray(count) { (it * 37 - 1_000).toShort() }

    private fun bytesOf(samples: ShortArray) = ByteArray(samples.size * 2).also { Wav.toBytes(samples, samples.size, it) }

    private fun sizesOf(header: ByteArray): Pair<Long, Long> {
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(4).toUInt().toLong() to b.getInt(40).toUInt().toLong()
    }

    @Test
    fun `the header says 16 kHz mono 16-bit PCM and its sizes`() {
        val header = Wav.header(32_000)

        assertEquals(Wav.HEADER_BYTES, header.size)
        assertTrue(Wav.isOurs(header))
        assertEquals(36L + 32_000 to 32_000L, sizesOf(header))
        assertEquals(1_000, Wav.durationMs(32_000))
    }

    @Test
    fun `sizes past 2 GB are written as the unsigned numbers WAV reads`() {
        assertEquals(Wav.MAX_DATA_BYTES + 36 to Wav.MAX_DATA_BYTES, sizesOf(Wav.header(Wav.MAX_DATA_BYTES)))
    }

    @Test
    fun `other formats are not ours`() {
        val stereo = Wav.header(0).also { it[22] = 2 }
        val cdRate = Wav.header(0).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 44_100) }

        assertFalse(Wav.isOurs(stereo))
        assertFalse(Wav.isOurs(cdRate))
        assertFalse(Wav.isOurs(ByteArray(Wav.HEADER_BYTES)))
        assertFalse(Wav.isOurs(Wav.header(0).copyOf(20)))
    }

    @Test
    fun `repair gives a file cut short by a crash the sizes of what it holds`() {
        val file = tmp.newFile("crashed.wav")
        val audio = bytesOf(samples(1_600))
        file.writeBytes(Wav.header(0) + audio)

        assertEquals(3_200L, Wav.repair(file))

        val repaired = file.readBytes()
        assertEquals(36L + 3_200 to 3_200L, sizesOf(repaired.copyOf(Wav.HEADER_BYTES)))
        assertArrayEquals(audio, repaired.copyOfRange(Wav.HEADER_BYTES, repaired.size))
    }

    @Test
    fun `repair drops half a sample left at the end`() {
        val file = tmp.newFile("odd.wav")
        file.writeBytes(Wav.header(0) + bytesOf(samples(10)) + byteArrayOf(7))

        assertEquals(20L, Wav.repair(file))
        assertEquals(Wav.HEADER_BYTES + 20L, file.length())
    }

    @Test
    fun `a file Satori didn't write is refused and left as it was`() {
        val stereo = tmp.newFile("stereo.wav")
        val content = Wav.header(4).also { it[22] = 2 } + byteArrayOf(1, 2, 3, 4)
        stereo.writeBytes(content)
        val tiny = tmp.newFile("tiny.wav").apply { writeBytes(ByteArray(10)) }

        assertNull(Wav.repair(stereo))
        assertArrayEquals(content, stereo.readBytes())
        assertNull(Wav.repair(tiny))
        assertNull(Wav.dataBytes(tiny))
    }

    @Test
    fun `dataBytes reads what the file holds and changes nothing`() {
        val file = tmp.newFile("open.wav")
        val content = Wav.header(0) + bytesOf(samples(5))
        file.writeBytes(content)

        assertEquals(10L, Wav.dataBytes(file))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `samples go to little-endian bytes and back as the floats the recognizer takes`() {
        val samples = shortArrayOf(0, 1, -1, 256, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = bytesOf(samples)

        assertArrayEquals(byteArrayOf(1, 0), bytes.copyOfRange(2, 4))
        assertArrayEquals(byteArrayOf(0, 1), bytes.copyOfRange(6, 8))
        assertArrayEquals(FloatArray(samples.size) { samples[it] / 32768.0f }, Wav.toFloats(bytes, samples.size), 0f)
    }
}
