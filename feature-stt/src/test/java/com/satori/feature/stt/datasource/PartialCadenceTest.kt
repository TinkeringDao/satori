package com.satori.feature.stt.datasource

import com.k2fsa.sherpa.onnx.SpeechSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** A partial result decodes the whole utterance so far, so a long one gets them less often. */
class PartialCadenceTest {

    private companion object {
        const val SECOND = 16_000 // samples
        const val READ = 1_600 // 100 ms, as the microphone is read
    }

    /** Hears speech from the first window on and never its end: only the end of the audio completes it. */
    private class Talking : VoiceDetector {
        var fed = 0L
            private set
        override val minSilenceSamples = SECOND / 2
        override val maxSpeechSamples = Int.MAX_VALUE

        override fun acceptWaveform(samples: FloatArray) {
            fed += samples.size
        }

        override fun isSpeechDetected() = fed > 0
        override fun empty() = true
        override fun front(): SpeechSegment = error("no utterance ends")
        override fun pop() = Unit
        override fun reset() = Unit
    }

    /**
     * Runs [seconds] of speech through a recording's recognition, read 100 ms at a time, with the
     * clock running with the audio, as it does while a recording keeps up with its microphone.
     * Returns the lengths of audio the partial results and the complete utterances decoded.
     */
    private fun recognize(seconds: Int): Pair<List<Int>, List<Int>> = runBlocking {
        val detector = Talking()
        val partials = mutableListOf<Int>()
        val finals = mutableListOf<Int>()
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        repeat(seconds * SECOND / READ) { audio.send(FloatArray(READ)) }
        audio.close()
        SpeechRecognition(
            detector = detector,
            decode = { partials += it.size; "so far" },
            partials = true,
            strict = false,
            decodeFinal = { finals += it.size; "all of it" },
            clock = { detector.fed * 1_000 / SECOND },
            partialContext = Dispatchers.Unconfined
        ).run(audio) {}
        partials to finals
    }

    private fun seconds(samples: Int) = (samples * 10.0 / SECOND).roundToInt() / 10.0

    @Test
    fun `every one and a half seconds of new audio while the utterance is under 8 s`() {
        listOf(0, SECOND, 7 * SECOND, 8 * SECOND - 1).forEach { length ->
            assertEquals("at $length samples", 24_000, SpeechRecognition.partialStepSamples(openSegment = length))
        }
    }

    @Test
    fun `every 3 s from 8 s on, up to the 30 s a partial decodes at most`() {
        listOf(8 * SECOND, 12 * SECOND, 20 * SECOND, 30 * SECOND).forEach { length ->
            assertEquals("at $length samples", 48_000, SpeechRecognition.partialStepSamples(openSegment = length))
        }
    }

    @Test
    fun `a recording's 20 s utterance gets a partial result every 1_6 s until it is 8 s long, then every 3 s`() {
        val (partials, finals) = recognize(seconds = 20)

        // A partial decodes the utterance so far, so its length is where in it the partial came.
        // 1.6 s, not 1.5: the 100 ms reads and the detector's 32 ms windows get past 1.5 s only then.
        assertEquals(listOf(1.6, 3.2, 4.8, 6.4, 9.5, 12.5, 15.5, 18.5), partials.map { seconds(it) })
        // The partials are never timed for the field log, and the complete utterance is.
        assertEquals(listOf(20 * SECOND), finals)
    }
}
