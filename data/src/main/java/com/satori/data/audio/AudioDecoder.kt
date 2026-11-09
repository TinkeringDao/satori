package com.satori.data.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the compressed audio of a recording ([AudioCompressor]): how long it plays, and its
 * samples decoded into a WAV again, for the speech recognizer, which reads WAV files only.
 */
interface AudioDecoder {

    /** How long [m4a] plays, by what the file says of itself, or null if it can't be read. */
    fun durationMs(m4a: File): Long?

    /**
     * Decodes [m4a] into [wav], which it creates or replaces: a WAV as Satori writes them ([Wav]),
     * 16 kHz mono 16-bit, so the speech recognizer reads it as it reads a recording's own. Blocks
     * until it is written.
     *
     * @param cancelled Asked between two buffers; once it says so, decoding stops with a
     *   CancellationException
     * @throws IOException when [m4a] holds no audio Satori could have written, or the decoder or
     *   the storage fail; [wav] may then hold part of a file
     */
    fun decode(m4a: File, wav: File, cancelled: () -> Boolean = { false })
}

/** [AudioDecoder] on Android's media framework: MediaMetadataRetriever, MediaExtractor and MediaCodec. */
class MediaCodecAudioDecoder : AudioDecoder {

    override fun durationMs(m4a: File): Long? = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(m4a.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        // A file the retriever doesn't take: it can't be played either.
        null
    }

    override fun decode(m4a: File, wav: File, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(m4a.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("The file holds no audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                RandomAccessFile(wav, "rw").use { out ->
                    out.setLength(0)
                    out.write(Wav.header(0))
                    val dataBytes = Pump(extractor, codec, format, out, cancelled).run()
                    out.seek(0)
                    out.write(Wav.header(dataBytes))
                }
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Moves [extractor]'s frames through [codec] and its samples into [out], mono. */
    private class Pump(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        format: MediaFormat,
        private val out: RandomAccessFile,
        private val cancelled: () -> Boolean
    ) {
        private var channels = format.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        private var dataBytes = 0L
        private var samples = ShortArray(0)
        private var bytes = ByteArray(0)

        /** @return How many bytes of samples were written after the header */
        fun run(): Long {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (cancelled()) throw CancellationException("Decoding the audio was cancelled")
                var progressed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        progressed = true
                        val buffer = checkNotNull(codec.getInputBuffer(index)) { "The decoder gave no input buffer" }
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    takeFormat(codec.outputFormat)
                } else if (index >= 0) {
                    progressed = true
                    if (info.size > 0) {
                        val pcm = checkNotNull(codec.getOutputBuffer(index)) { "The decoder gave no output buffer" }
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        writeMono(pcm)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return dataBytes
                }
                idle = if (progressed) 0 else idle + 1
                if (idle > MAX_IDLE_ROUNDS) throw IOException("The decoder stopped giving out audio")
            }
        }

        /** The decoder's output: anything but 16-bit samples at 16 kHz is no audio Satori wrote. */
        private fun takeFormat(format: MediaFormat) {
            val rate = format.integer(MediaFormat.KEY_SAMPLE_RATE)
            if (rate != null && rate != Wav.SAMPLE_RATE_HZ) throw IOException("The audio is at $rate Hz, not ${Wav.SAMPLE_RATE_HZ}")
            val encoding = format.integer(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) throw IOException("The decoder gives out samples of another kind ($encoding)")
            channels = (format.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: channels).coerceAtLeast(1)
        }

        /** Writes the samples of [pcm], each frame of [channels] averaged into one. */
        private fun writeMono(pcm: ByteBuffer) {
            val interleaved = pcm.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = interleaved.remaining() / channels
            if (frames == 0) return
            if (dataBytes + frames * 2L > Wav.MAX_DATA_BYTES) throw IOException("The audio is longer than a WAV file holds")
            if (samples.size < frames) {
                samples = ShortArray(frames)
                bytes = ByteArray(frames * 2)
            }
            for (frame in 0 until frames) {
                var sum = 0
                for (channel in 0 until channels) sum += interleaved.get()
                samples[frame] = (sum / channels).toShort()
            }
            Wav.toBytes(samples, frames, bytes)
            out.write(bytes, 0, frames * 2)
            dataBytes += frames * 2L
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** Rounds without any buffer moving (10 ms waits each) before the decoder counts as stuck: about 10 s. */
        const val MAX_IDLE_ROUNDS = 1_000

        fun MediaFormat.integer(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    }
}
