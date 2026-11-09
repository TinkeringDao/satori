package com.satori.data.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Makes the compressed copy of a recording's audio ([RecordingAudioStore.compress]): AAC-LC at
 * 32 kb/s, 16 kHz mono, in an MPEG-4 file (.m4a), about eight times smaller than the WAV's
 * 256 kb/s. It still plays, and the speech recognizer can read it once it is decoded again
 * ([AudioDecoder]); AAC is lossy, so it can't be turned back into the WAV it was made from.
 */
interface AudioCompressor {

    /**
     * Encodes the [dataBytes] of samples of [wav], a WAV Satori wrote ([Wav]), into [m4a], which
     * it creates or replaces. Blocks until the file is complete.
     *
     * @throws Exception (an IOException, or the codec's or the muxer's IllegalStateException) when
     *   either fails; [m4a] may then hold part of a file
     */
    fun encode(wav: File, dataBytes: Long, m4a: File)

    /**
     * How long the audio track of [m4a] plays, as MediaExtractor reads it from the file: the check
     * that the muxer wrote the file whole. Null without an audio track that can be read.
     */
    fun durationMs(m4a: File): Long?
}

/**
 * Whether a compressed copy holds the whole of its WAV, by how long each plays. AAC codes 1024
 * samples to a frame, 64 ms at 16 kHz, and an encoder adds samples of its own: before the audio
 * (its delay) and to fill the last frame. Where the file doesn't say to leave them out, they count
 * in how long it plays. So a whole copy plays as long as its WAV or up to [FRAMING_MS] longer,
 * give or take [TOLERANCE_MS]; one cut short (an encoder or a muxer that stopped early) plays
 * shorter, and one with wrong timestamps far longer. The log line of each compression gives both
 * lengths, for the phone to show how much the encoder adds.
 */
internal object CompressedLength {

    /** How far the lengths may be off past the codec's own frames. */
    const val TOLERANCE_MS = 100L

    /** Three AAC frames: the encoder's delay and the padding of the last frame. */
    const val FRAMING_MS = 3 * AAC_FRAME_SAMPLES * 1_000L / Wav.SAMPLE_RATE_HZ

    fun matches(wavMs: Long, aacMs: Long): Boolean =
        aacMs in (wavMs - TOLERANCE_MS)..(wavMs + FRAMING_MS + TOLERANCE_MS)
}

/** Samples in one AAC-LC frame. */
private const val AAC_FRAME_SAMPLES = 1024

/** [AudioCompressor] on Android's AAC encoder (MediaCodec) and its MPEG-4 muxer (MediaMuxer). */
class MediaCodecAudioCompressor : AudioCompressor {

    override fun encode(wav: File, dataBytes: Long, m4a: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                RandomAccessFile(wav, "r").use { input ->
                    input.seek(Wav.HEADER_BYTES.toLong())
                    pump(codec, muxer, input, dataBytes)
                }
                // Writes the file's index of frames; until then the file doesn't play.
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            codec.release()
        }
    }

    override fun durationMs(m4a: File): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(m4a.path)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC && it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.let { it / 1_000 }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // A file the extractor doesn't take is no copy to keep.
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Feeds the samples of [input] to [codec] and the frames it gives out to [muxer], until the
     * encoder has given out its last frame. Each input buffer is stamped with the place of its
     * first sample, which the encoder carries over to its frames.
     */
    private fun pump(codec: MediaCodec, muxer: MediaMuxer, input: RandomAccessFile, dataBytes: Long) {
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(MAX_INPUT_BYTES)
        var left = dataBytes
        var samples = 0L
        var inputDone = false
        var track = -1
        var idle = 0
        while (true) {
            var progressed = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    progressed = true
                    val buffer = checkNotNull(codec.getInputBuffer(index)) { "The encoder gave no input buffer" }
                    // Whole samples only: two bytes each.
                    val want = minOf(left, buffer.remaining().toLong(), chunk.size.toLong()).toInt() and 1.inv()
                    val read = if (want > 0) readUpTo(input, chunk, want) else 0
                    val timeUs = samples * 1_000_000L / Wav.SAMPLE_RATE_HZ
                    if (read <= 0) {
                        codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.put(chunk, 0, read)
                        codec.queueInputBuffer(index, 0, read, timeUs, 0)
                        left -= read
                        samples += read / 2
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                progressed = true
                check(track < 0) { "The encoder changed its format twice" }
                // The format holds the codec's setup (csd-0), which the muxer writes once.
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (out >= 0) {
                progressed = true
                val frame = checkNotNull(codec.getOutputBuffer(out)) { "The encoder gave no output buffer" }
                val setup = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!setup && info.size > 0) {
                    check(track >= 0) { "The encoder gave out audio before its format" }
                    frame.position(info.offset)
                    frame.limit(info.offset + info.size)
                    muxer.writeSampleData(track, frame, info)
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
            idle = if (progressed) 0 else idle + 1
            if (idle > MAX_IDLE_ROUNDS) throw IOException("The encoder stopped giving out audio")
        }
    }

    private fun aacFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.SAMPLE_RATE_HZ, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }

    /** Reads up to [count] bytes into the start of [into]. @return How many; fewer only at the end of the file */
    private fun readUpTo(input: RandomAccessFile, into: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val read = input.read(into, total, count - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private companion object {
        /** 32 kb/s, an eighth of the WAV's 256 kb/s. */
        const val BIT_RATE = 32_000

        /** Half a second of samples at most in one input buffer. */
        const val MAX_INPUT_BYTES = 16_384

        const val TIMEOUT_US = 10_000L

        /** Rounds without any buffer moving (10 ms waits each) before the encoder counts as stuck: about 10 s. */
        const val MAX_IDLE_ROUNDS = 1_000
    }
}
