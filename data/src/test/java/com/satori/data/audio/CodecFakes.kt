package com.satori.data.audio

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Android's AAC encoder and decoder as the JVM tests have them: an "AAC" file holds the length it
 * plays as text, which both durations read back, and decoding it writes a WAV of silence that
 * long. Real codecs run in the instrumented AudioCodecRoundTripTest.
 *
 * @property extraMs How much longer than its WAV a copy plays, as the codec's own frames make it
 */
internal class FakeCodec(var extraMs: Long = 0L) : AudioCompressor, AudioDecoder {

    /** Set to make the encoder fail after it wrote part of a file. */
    var encodeFailure: Exception? = null

    /** Set to make the decoder fail after it wrote part of a file. */
    var decodeFailure: Exception? = null

    /** Called once the copy is written, before the encoder returns: what happens while one runs. */
    var onEncode: () -> Unit = {}

    /** Called once part of the decoded file is written, before the decoder asks whether to stop. */
    var onDecode: () -> Unit = {}

    /** The files that were decoded, in order. */
    val decoded = mutableListOf<File>()

    override fun encode(wav: File, dataBytes: Long, m4a: File) {
        encodeFailure?.let { failure ->
            m4a.writeText("part of a file")
            throw failure
        }
        m4a.writeText("$AAC${Wav.durationMs(dataBytes) + extraMs}")
        onEncode()
    }

    override fun durationMs(m4a: File): Long? =
        m4a.takeIf { it.isFile }?.readText()?.takeIf { it.startsWith(AAC) }?.removePrefix(AAC)?.toLongOrNull()

    override fun decode(m4a: File, wav: File, cancelled: () -> Boolean) {
        decoded += m4a
        decodeFailure?.let { failure ->
            wav.writeText("part of a file")
            throw failure
        }
        val ms = durationMs(m4a) ?: throw IOException("Not a compressed copy")
        wav.writeText("part of a file")
        onDecode()
        if (cancelled()) throw CancellationException("Decoding the audio was cancelled")
        val dataBytes = ms * Wav.BYTES_PER_MS
        wav.writeBytes(Wav.header(dataBytes) + ByteArray(dataBytes.toInt()))
    }

    private companion object {
        const val AAC = "aac "
    }
}
