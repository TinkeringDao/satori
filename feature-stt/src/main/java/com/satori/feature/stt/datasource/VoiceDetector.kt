package com.satori.feature.stt.datasource

import com.satori.domain.model.VadParameters
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad

/**
 * Builds a new Silero [Vad] that detects speech with [VadParameters]. Every capture and every
 * Transcribe again asks for one with the values of its profile, and owns it from then on.
 */
fun interface VadFactory {
    fun create(parameters: VadParameters): Vad
}

/**
 * The voice detection [SpeechRecognition] needs: the calls it makes on sherpa-onnx's [Vad], so
 * the recognition can be run in a unit test, where the native VAD can't load.
 */
internal interface VoiceDetector {
    /** The silence that ends an utterance, in samples: a segment comes out this long after it ends. */
    val minSilenceSamples: Int

    /** How long an utterance grows, in samples, before the detector cuts it at the next brief dip. */
    val maxSpeechSamples: Int

    fun acceptWaveform(samples: FloatArray)

    fun isSpeechDetected(): Boolean

    fun empty(): Boolean

    fun front(): SpeechSegment

    fun pop()

    fun reset()
}

/** The silence after a segment the detector cut at its maximum length: 0.1 s, in samples. */
private const val CUT_SILENCE_SAMPLES = SpeechRecognition.SAMPLE_RATE_HZ / 10

/**
 * How long after its end the detector gave out a segment of [size] samples: [minSilenceSamples],
 * or 0.1 s for one it cut because it reached [maxSpeechSamples]. Once an utterance is that long,
 * sherpa-onnx (voice-activity-detector.cc, v1.13.8) ends it at the first 0.1 s below a threshold
 * of 0.9; counting a whole pause back from there would place the line too early, by 0.4 s live
 * and 0.6 s for a file, so playing it would start in the line before and miss its last words.
 * The two kinds are told apart by length: a segment that ended at a pause is about that pause
 * shorter than the maximum, one that was cut at most 0.1 s.
 */
internal fun VoiceDetector.silenceAfter(size: Int): Int =
    if (size > maxSpeechSamples - CUT_SILENCE_SAMPLES) CUT_SILENCE_SAMPLES else minSilenceSamples

/** sherpa-onnx's Silero [Vad] as a [VoiceDetector]. */
internal class SherpaVoiceDetector(private val vad: Vad) : VoiceDetector {
    override val minSilenceSamples: Int =
        (vad.config.sileroVadModelConfig.minSilenceDuration * SpeechRecognition.SAMPLE_RATE_HZ).toInt()

    override val maxSpeechSamples: Int =
        (vad.config.sileroVadModelConfig.maxSpeechDuration * SpeechRecognition.SAMPLE_RATE_HZ).toInt()

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    override fun empty(): Boolean = vad.empty()

    override fun front(): SpeechSegment = vad.front()

    override fun pop() = vad.pop()

    override fun reset() = vad.reset()
}
