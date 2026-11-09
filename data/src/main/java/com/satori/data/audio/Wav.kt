package com.satori.data.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The WAV files Satori keeps of its recordings: a canonical 44-byte header, then 16 kHz mono
 * 16-bit little-endian PCM, the samples the speech recognizer is given. Nothing else is in them,
 * so any player or audio tool reads them and the samples always start at byte [HEADER_BYTES].
 */
object Wav {
    const val SAMPLE_RATE_HZ = 16_000
    const val HEADER_BYTES = 44

    /** 16 samples of 2 bytes each. */
    const val BYTES_PER_MS = 32L

    /** The most the header's 32-bit sizes can say; the RIFF size counts 36 header bytes more. */
    const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36

    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val PCM = 1

    /** The header of a file that holds [dataBytes] of samples. */
    fun header(dataBytes: Long): ByteArray {
        require(dataBytes in 0..MAX_DATA_BYTES) { "Not a WAV data size: $dataBytes" }
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ascii("RIFF")).putInt((36 + dataBytes).toInt()).put(ascii("WAVE"))
            put(ascii("fmt ")).putInt(16).putShort(PCM.toShort()).putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE_HZ).putInt(SAMPLE_RATE_HZ * 2).putShort(2).putShort(BITS_PER_SAMPLE.toShort())
            put(ascii("data")).putInt(dataBytes.toInt())
        }.array()
    }

    /**
     * Whether [header], the first [HEADER_BYTES] of a file, starts a file Satori wrote: the format
     * and the layout of [header], whatever sizes it gives.
     */
    fun isOurs(header: ByteArray): Boolean {
        if (header.size < HEADER_BYTES) return false
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return text(header, 0) == "RIFF" && text(header, 8) == "WAVE" && text(header, 12) == "fmt " &&
            b.getInt(16) == 16 && b.getShort(20).toInt() == PCM && b.getShort(22).toInt() == CHANNELS &&
            b.getInt(24) == SAMPLE_RATE_HZ && b.getInt(28) == SAMPLE_RATE_HZ * 2 &&
            b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == BITS_PER_SAMPLE &&
            text(header, 36) == "data"
    }

    /**
     * How many bytes of samples [file] holds: whole samples after the header, as many as the
     * header can say. Null if it is not a file Satori wrote. Only reads.
     */
    fun dataBytes(file: File): Long? = RandomAccessFile(file, "r").use { raf -> dataBytesOf(raf) }

    /**
     * Makes the header of [file] say what the file holds and drops a half sample left at its end.
     * A capture cut short (the app killed while recording) leaves the sizes it last wrote, which
     * would hide the audio after them from a player.
     *
     * @return How many bytes of samples it holds, or null if it is not a file Satori wrote (it is
     *   then left as it is)
     */
    fun repair(file: File): Long? = RandomAccessFile(file, "rw").use { raf ->
        val dataBytes = dataBytesOf(raf) ?: return null
        if (raf.length() != HEADER_BYTES + dataBytes) raf.setLength(HEADER_BYTES + dataBytes)
        raf.seek(0)
        val header = ByteArray(HEADER_BYTES).also { raf.readFully(it) }
        val sizes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (sizes.getInt(4).toUInt().toLong() != 36 + dataBytes || sizes.getInt(40).toUInt().toLong() != dataBytes) {
            raf.seek(0)
            raf.write(header(dataBytes))
        }
        dataBytes
    }

    /** How long [dataBytes] of samples play. */
    fun durationMs(dataBytes: Long): Long = dataBytes / BYTES_PER_MS

    /** The first [count] of [samples] as little-endian bytes, into the start of [into]. */
    fun toBytes(samples: ShortArray, count: Int, into: ByteArray) {
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            into[2 * i] = sample.toByte()
            into[2 * i + 1] = (sample shr 8).toByte()
        }
    }

    /** [count] samples from the little-endian [bytes], in -1..1 as the recognizer takes them. */
    fun toFloats(bytes: ByteArray, count: Int): FloatArray = FloatArray(count) { i ->
        ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768.0f
    }

    private fun dataBytesOf(raf: RandomAccessFile): Long? {
        if (raf.length() < HEADER_BYTES) return null
        val header = ByteArray(HEADER_BYTES).also { raf.readFully(it) }
        if (!isOurs(header)) return null
        return minOf((raf.length() - HEADER_BYTES) and 1L.inv(), MAX_DATA_BYTES and 1L.inv())
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun text(bytes: ByteArray, offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
}
