package com.satori.data.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioRun
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android's own AAC encoder, muxer and decoder on a 10 s tone, as compression and Transcribe again
 * use them: the copy plays as long as the WAV within the codec's frames ([CompressedLength]),
 * decodes to 16 kHz mono that still holds the tone, and holds it where the WAV does.
 */
@RunWith(AndroidJUnit4::class)
class AudioCodecRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun makeFolder() {
        dir = File(context.cacheDir, "codec-round-trip").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun removeFolder() {
        dir.deleteRecursively()
    }

    /**
     * [seconds] of a 440 Hz tone at a quarter of full scale after [silentMs] of silence, as a WAV
     * Satori writes. @return its data bytes
     */
    private fun tone(file: File, seconds: Int = 10, silentMs: Int = 0): Long {
        val silent = silentMs * Wav.SAMPLE_RATE_HZ / 1_000
        val samples = ShortArray(silent + seconds * Wav.SAMPLE_RATE_HZ) { i ->
            if (i < silent) 0.toShort() else (TONE_AMPLITUDE * sin(2 * PI * 440 * (i - silent) / Wav.SAMPLE_RATE_HZ)).toInt().toShort()
        }
        val bytes = ByteArray(samples.size * 2)
        Wav.toBytes(samples, samples.size, bytes)
        file.writeBytes(Wav.header(bytes.size.toLong()) + bytes)
        return bytes.size.toLong()
    }

    private fun samplesOf(wav: File): ShortArray {
        val bytes = wav.readBytes()
        val data = ByteBuffer.wrap(bytes, Wav.HEADER_BYTES, bytes.size - Wav.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - Wav.HEADER_BYTES) / 2) { data.getShort() }
    }

    private fun rms(samples: ShortArray, from: Int, count: Int): Double =
        sqrt((from until from + count).sumOf { samples[it].toDouble() * samples[it] } / count)

    /** Where the tone starts in [samples]: the first 5 ms at a quarter of the tone's level or more. */
    private fun onsetMs(samples: ShortArray): Long {
        val window = Wav.SAMPLE_RATE_HZ / 200
        val level = TONE_AMPLITUDE / sqrt(2.0) / 4
        val start = (0..samples.size - window step window).first { rms(samples, it, window) >= level }
        return start * 1_000L / Wav.SAMPLE_RATE_HZ
    }

    @Test
    fun compressedToneKeepsItsLength() {
        val wav = File(dir, "tone.wav")
        val dataBytes = tone(wav)
        val m4a = File(dir, "tone.m4a")
        val compressor = MediaCodecAudioCompressor()

        compressor.encode(wav, dataBytes, m4a)

        val wavMs = Wav.durationMs(dataBytes)
        val extracted = checkNotNull(compressor.durationMs(m4a))
        assertTrue("MediaExtractor: $extracted ms for $wavMs ms", CompressedLength.matches(wavMs, extracted))
        val retrieved = checkNotNull(MediaCodecAudioDecoder().durationMs(m4a))
        assertTrue("MediaMetadataRetriever: $retrieved ms for $wavMs ms", CompressedLength.matches(wavMs, retrieved))
        // About eight times smaller: 32 kb/s against 256 kb/s, plus the file's index of frames.
        assertTrue("${m4a.length()} bytes for ${wav.length()}", m4a.length() < wav.length() / 6)
    }

    @Test
    fun decodedToneIs16kHzMonoAndStillTheTone() {
        val wav = File(dir, "tone.wav")
        val dataBytes = tone(wav)
        val m4a = File(dir, "tone.m4a")
        MediaCodecAudioCompressor().encode(wav, dataBytes, m4a)
        val back = File(dir, "back.wav")

        MediaCodecAudioDecoder().decode(m4a, back)

        val header = back.readBytes().copyOf(Wav.HEADER_BYTES)
        assertTrue(Wav.isOurs(header))
        val backBytes = checkNotNull(Wav.dataBytes(back))
        assertArrayEquals(Wav.header(backBytes), header)
        val backMs = Wav.durationMs(backBytes)
        assertTrue("$backMs ms decoded from ${Wav.durationMs(dataBytes)} ms", CompressedLength.matches(Wav.durationMs(dataBytes), backMs))
        // The middle second holds the tone at about its level; lossy, so not to the sample.
        val original = samplesOf(wav)
        val decoded = samplesOf(back)
        val second = Wav.SAMPLE_RATE_HZ
        val level = rms(decoded, 4 * second, second) / rms(original, 4 * second, second)
        assertTrue("Level $level of the original", level in 0.7..1.3)
    }

    /**
     * The index places each line by the WAV's clock, and Transcribe again by the decoded copy's.
     * Should the encoder's own samples before the audio (its delay, often 2048 samples, 128 ms
     * at 16 kHz) stay in the copy, every line made from the WAV would play that much early on
     * it; the index would then have to be shifted by it as the audio is compressed.
     */
    @Test
    fun compressedAudioStartsWhereItsWavStarts() {
        val wav = File(dir, "onset.wav")
        val dataBytes = tone(wav, seconds = 2, silentMs = 1_000)
        val m4a = File(dir, "onset.m4a")
        MediaCodecAudioCompressor().encode(wav, dataBytes, m4a)
        val back = File(dir, "back.wav")

        MediaCodecAudioDecoder().decode(m4a, back)

        val wavOnset = onsetMs(samplesOf(wav))
        val shiftMs = onsetMs(samplesOf(back)) - wavOnset
        assertEquals(1_000L, wavOnset)
        assertTrue(
            "The tone starts $shiftMs ms later in the decoded copy than in its WAV",
            abs(shiftMs) <= ONSET_TOLERANCE_MS
        )
    }

    @Test
    fun theStoreCompressesInPlaceAndDecodesForTheRecognizer() = runBlocking<Unit> {
        val audioDir = File(dir, "audio").apply { mkdirs() }
        val store = RecordingAudioStore(
            dir = audioDir,
            decodedDir = File(dir, "transcribe"),
            compressor = MediaCodecAudioCompressor(),
            decoder = MediaCodecAudioDecoder(),
            info = {},
            warn = { message, e -> throw AssertionError(message, e) }
        )
        tone(File(audioDir, "rec-1.wav"))
        File(audioDir, "rec-1.idx").writeText(AudioIndex.run(AudioRun(1_000, 0)))

        assertTrue(store.compress("rec-1").getOrThrow() > 0)

        assertFalse(File(audioDir, "rec-1.wav").exists())
        val audio = checkNotNull(store.find("rec-1"))
        assertEquals(AudioEncoding.AAC, audio.encoding)
        assertTrue(CompressedLength.matches(10_000, audio.durationMs))
        val copy = store.decodedCopy(audio)
        assertTrue(Wav.isOurs(copy.readBytes().copyOf(Wav.HEADER_BYTES)))
        copy.delete()
    }

    private companion object {
        const val TONE_AMPLITUDE = 8_192.0

        /** Within this a line still starts as tap-to-play promises, about 100 ms, with room for the player. */
        const val ONSET_TOLERANCE_MS = 50L
    }
}
