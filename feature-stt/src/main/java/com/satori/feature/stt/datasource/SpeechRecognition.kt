package com.satori.feature.stt.datasource

import android.util.Log
import com.satori.data.datasource.RecognitionResult
import com.satori.domain.model.AudioSpan
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.feature.stt.datasource.SherpaOnnxDataSource.SampleBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The speech recognition of a capture: the VAD cuts the audio into utterances and each one is
 * decoded. A recording ([SherpaOnnxDataSource.startRecording]) and a recording transcribed again
 * from its audio ([SherpaOnnxDataSource.transcribeFile]) both run it, so the same audio is heard
 * the same way and speech models can be compared on it.
 *
 * Besides the text, each complete utterance says where it was said ([RecognitionResult.span]),
 * counted from the first sample [run] is given, so a transcript line can be played from its start.
 * A decode whose text holds no letter or digit gives out nothing, as one with no text
 * ([decodeLine]).
 *
 * @param detector The capture's own voice detector
 * @param decode Turns a stretch of audio into text: null for none, or when the model is gone
 *   (Stop released it first)
 * @param partials Whether to give out what is being said while it is said, as a recording does,
 *   or only complete utterances, as a file transcribed again does
 * @param strict Whether a failing decode or VAD step ends [run] with that error, so a transcript
 *   with an utterance missing never replaces a complete one (a file), or only loses that stretch
 *   while the capture goes on (a recording)
 * @param metrics Where a recording counts its work for the field log; null for a file
 * @param decodeFinal [decode] for a complete utterance, which a caller can time for the field
 *   log's summary line ([DecodeSpeed]) apart from the partial results
 * @param clock The wall clock in milliseconds, which keeps partial results at least 200 ms apart
 *   and times the decodes for [metrics]; a test runs it with the audio, as a recording does
 * @param partialContext Where partial results are decoded while the recognition goes on; a test
 *   decodes them in place ([kotlinx.coroutines.Dispatchers.Unconfined]), so they come in a fixed order
 * @param evidence Where a recording notes the speech its lines may lack ([SpeechEvidence]): words
 *   it gave out, an utterance it heard begin, stretches of audio a failed step dropped, and how
 *   much of the audio the voice detection has been over; null for a file, which fails instead
 * @param modelSwitch Where a recording changes its speech model between two utterances, after a
 *   switch of its language ([ModelSwitch]); null for a file, and for a recording that never
 *   switches
 */
internal class SpeechRecognition(
    private val detector: VoiceDetector,
    private val decode: suspend (FloatArray) -> String?,
    private val partials: Boolean,
    private val strict: Boolean,
    private val metrics: AudioMetrics? = null,
    private val decodeFinal: suspend (FloatArray) -> String? = decode,
    private val clock: () -> Long = System::currentTimeMillis,
    private val partialContext: CoroutineContext = Dispatchers.Default,
    private val evidence: SpeechEvidence? = null,
    private val modelSwitch: ModelSwitch? = null
) {

    /**
     * Recognizes [samples] until the channel is closed, handing each result to [emit]: complete
     * utterances in order, and with [partials] the partial ones in between, from a coroutine of
     * their own. Returns once the last of them is out.
     */
    suspend fun run(samples: ReceiveChannel<FloatArray>, emit: suspend (RecognitionResult) -> Unit) = coroutineScope {
        val buffer = SampleBuffer()
        // How many samples have come in: where the buffer ends in the audio. It starts this less
        // its size, however much the trims below have dropped from its front.
        var received = 0L
        var offset = 0
        var isSpeechStarted = false
        var startTime = clock()
        var speechStartOffset = 0
        var lastInferenceOffset = 0
        // Where the last complete utterance ended, so the next one is never placed before it.
        var lastEnd = 0L
        // Guard against a slow device queuing multiple concurrent partial inferences.
        // When the previous partial call is still running we skip the interval and let
        // audio accumulate, so the next call processes a larger (but fresher) chunk.
        // The decode lambda serializes the actual decode() calls (SherpaOnnxDataSource's
        // decodeMutex), so the async partial inference child never races the synchronous ones.
        val isPartialInferenceBusy = AtomicBoolean(false)
        // The last partial result's decode, which a change of the speech model waits for.
        var partialJob: Job? = null

        fun position(bufferIndex: Int): Long = received - buffer.size + bufferIndex

        // The audio decoded for an utterance can begin before the last one ended (the lookback
        // after a forced split); its place starts no earlier than that end.
        suspend fun emitComplete(text: String, start: Long, end: Long) {
            evidence?.givingOut(text)
            val from = maxOf(start, lastEnd)
            val to = maxOf(end, from)
            emit(RecognitionResult(text, isComplete = true, span = AudioSpan(from / SAMPLES_PER_MS, to / SAMPLES_PER_MS)))
            lastEnd = to
        }

        for (chunk in samples) {
            // Between two utterances, with none open, the speech model may change: the recording's
            // language was switched. A partial result of the last one that still decodes ends
            // first, so no decode runs on the model that goes. Meanwhile the audio waits in the
            // queue, where the microphone goes on putting it; the voice detection hears it once the
            // new model is in, so no stretch is lost or heard twice.
            if (!isSpeechStarted && modelSwitch?.due() == true) {
                partialJob?.join()
                modelSwitch.switch(atMs = received / SAMPLES_PER_MS)
            }
            buffer.append(chunk)
            received += chunk.size

            // VAD processing with fixed window size
            while (offset + VAD_WINDOW_SIZE <= buffer.size) {
                val vadWindow = buffer.getRange(offset, offset + VAD_WINDOW_SIZE)
                if (vadWindow.all { it.isFinite() }) {
                    try {
                        detector.acceptWaveform(vadWindow)
                        metrics?.recordVadProcessing(success = true)
                    } catch (e: Exception) {
                        if (strict) throw e
                        evidence?.audioDropped()
                        Log.e(TAG, "VAD acceptWaveform failed", e)
                        metrics?.recordVadProcessing(success = false)
                    }
                }
                offset += VAD_WINDOW_SIZE

                if (!isSpeechStarted && detector.isSpeechDetected()) {
                    isSpeechStarted = true
                    evidence?.utteranceBegan()
                    speechStartOffset = maxOf(0, offset - SPEECH_START_LOOKBACK_SAMPLES)
                    startTime = clock()
                    lastInferenceOffset = speechStartOffset
                    metrics?.recordSpeechSegment()
                    Log.d(TAG, "Speech detected at offset $offset")
                }
            }
            // Speech in this audio has begun an utterance by now, so it no longer counts as speech
            // waiting to be heard; what is short of a window waits only for the next read.
            evidence?.audioHeard(chunk.size)

            // Nobody is speaking, so only the last few seconds can still be needed: the
            // lookback of the next speech start and what the VAD hasn't read yet
            // (speechStartOffset and lastInferenceOffset are set afresh when speech starts).
            // Without this a long quiet stretch grows the buffer by 64 KB a second until the
            // app runs out of memory, which ends the recording.
            if (!isSpeechStarted) offset = buffer.trimBefore(offset, CONTEXT_CARRY_OVER_SAMPLES)

            // Force a segment split when speech has been ongoing for too long without
            // VAD silence detection (e.g. continuous monologue with no 0.5s pause).
            // Without this, sherpa-onnx's internal circular buffer doubles every ~60s,
            // causing native memory growth and all audio flushing as one giant segment
            // only when recording stops.
            //
            // This block must come BEFORE the partial inference check below. On slow
            // devices the partial inference takes longer than MIN_NEW_AUDIO_SAMPLES worth
            // of audio, so isPartialInferenceBusy is almost always true in the same
            // iteration where the 30s threshold is first crossed — meaning the forced split
            // would never fire if evaluated after the partial inference check. By evaluating
            // it first we guarantee it wins the scheduling window.
            //
            // decode() must not run while an in-flight partial inference decodes:
            // OfflineRecognizer.decode() is NOT thread-safe for concurrent calls (streams
            // are independent objects but the underlying ONNX InferenceSession has shared
            // internal state that causes SIGSEGV, fault addr 0x0/0x8 in libonnxruntime, when
            // two decode() calls run simultaneously). The recorder's decode lambda takes the
            // decodeMutex for that.
            val speechElapsedSamples = offset - speechStartOffset
            if (isSpeechStarted && speechElapsedSamples >= MAX_INFERENCE_AUDIO_SAMPLES) {
                val cappedStart = maxOf(speechStartOffset, offset - MAX_INFERENCE_AUDIO_SAMPLES)
                val audioSegment = buffer.getRange(cappedStart, offset)
                val start = position(cappedStart)
                val end = position(offset)
                try {
                    if (decodable(audioSegment)) {
                        val trimmedText = decodeLine(audioSegment)
                        if (trimmedText != null) {
                            emitComplete(trimmedText, start, end)
                            Log.d(TAG, "Forced split after ${speechElapsedSamples / SAMPLE_RATE_HZ}s: ${trimmedText.length} chars")
                        }
                    }
                } catch (e: Exception) {
                    if (strict) throw e
                    evidence?.audioDropped()
                    Log.e(TAG, "Forced split inference failed", e)
                }
                isSpeechStarted = false
                detector.reset()
                buffer.keepTail(CONTEXT_CARRY_OVER_SAMPLES)
                offset = buffer.size
                speechStartOffset = 0
                lastInferenceOffset = offset
                startTime = clock()
            }

            // Trigger partial inference only when enough new audio has accumulated.
            // Runs in a child coroutine so VAD segment detection (end-of-speech) is
            // never blocked on slow devices. If the previous partial inference is still
            // running we skip this interval; audio keeps accumulating and will be picked
            // up in the next eligible cycle. isSpeechStarted is false after a forced
            // split above, so this block is a no-op in the same iteration.
            val elapsed = clock() - startTime
            val newAudioSamples = offset - lastInferenceOffset
            // What a partial result decodes: the utterance so far, at most its last 30 s.
            val cappedStart = maxOf(speechStartOffset, offset - MAX_INFERENCE_AUDIO_SAMPLES)
            if (partials && isSpeechStarted && elapsed > MIN_INFERENCE_INTERVAL_MS
                && newAudioSamples >= partialStepSamples(openSegment = offset - cappedStart)
                && isPartialInferenceBusy.compareAndSet(false, true)
            ) {
                // Snapshot audio before launching — getRange returns a new FloatArray copy.
                val audioSnapshot = buffer.getRange(cappedStart, offset)
                val snapshotNewSamples = newAudioSamples
                // Advance offset trackers immediately so the next iteration measures
                // new audio relative to this snapshot, not the previous one.
                lastInferenceOffset = offset
                startTime = clock()

                partialJob = launch(partialContext) {
                    val inferenceStart = clock()
                    try {
                        if (decodable(audioSnapshot)) {
                            val trimmedText = decodePartial(audioSnapshot)
                            val inferenceLatency = clock() - inferenceStart
                            metrics?.recordInference(inferenceLatency, success = trimmedText != null)
                            if (trimmedText != null) {
                                evidence?.givingOut(trimmedText)
                                emit(RecognitionResult(trimmedText, isComplete = false))
                                Log.d(TAG, "Partial result (${inferenceLatency}ms, " +
                                        "$snapshotNewSamples new samples): ${trimmedText.length} chars")
                            }
                        } else {
                            val inferenceLatency = clock() - inferenceStart
                            metrics?.recordInference(inferenceLatency, success = false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ASR partial inference failed", e)
                        val inferenceLatency = clock() - inferenceStart
                        metrics?.recordInference(inferenceLatency, success = false)
                    } finally {
                        isPartialInferenceBusy.set(false)
                    }
                }
            }

            // Process VAD segments (speech end detection).
            // Kept synchronous: vad.front() / vad.pop() are not thread-safe and
            // buffer.keepTail() must happen before the next audio chunk is processed.
            while (!detector.empty()) {
                val segmentStart = clock()
                try {
                    val vadSegment = detector.front()
                    if (decodable(vadSegment.samples)) {
                        val trimmedText = decodeLine(vadSegment.samples)
                        val segmentLatency = clock() - segmentStart
                        metrics?.recordInference(segmentLatency, success = trimmedText != null)
                        if (trimmedText != null) {
                            val (start, end) = segmentSpan(
                                readTo = position(offset),
                                size = vadSegment.samples.size,
                                trailingSilence = detector.silenceAfter(vadSegment.samples.size)
                            )
                            emitComplete(trimmedText, start, end)
                            Log.d(TAG, "Final segment (${segmentLatency}ms): ${trimmedText.length} chars")
                        }
                    }
                    detector.pop()
                } catch (e: Exception) {
                    if (strict) throw e
                    evidence?.audioDropped()
                    Log.e(TAG, "VAD segment processing failed", e)
                    val segmentLatency = clock() - segmentStart
                    metrics?.recordInference(segmentLatency, success = false)

                    try {
                        detector.pop()
                    } catch (popError: Exception) {
                        Log.e(TAG, "Failed to pop VAD segment", popError)
                        break
                    }
                }

                isSpeechStarted = false
                evidence?.utteranceEnded(running = isActive)
                buffer.keepTail(CONTEXT_CARRY_OVER_SAMPLES)
                offset = buffer.size
                lastInferenceOffset = offset
            }
        }

        // Flush: the audio ended while speech was active (no end-of-speech silence).
        // Run one final inference on the remaining buffered audio and emit it as a
        // complete segment so the content is not silently discarded.
        if (isSpeechStarted && offset > speechStartOffset) {
            val cappedStart = maxOf(speechStartOffset, offset - MAX_INFERENCE_AUDIO_SAMPLES)
            val audioSegment = buffer.getRange(cappedStart, offset)
            if (decodable(audioSegment)) {
                try {
                    val trimmedText = decodeLine(audioSegment)
                    if (trimmedText != null) {
                        emitComplete(trimmedText, position(cappedStart), position(offset))
                        Log.d(TAG, "Flush segment on stop: ${trimmedText.length} chars")
                    }
                } catch (e: Exception) {
                    if (strict) throw e
                    evidence?.audioDropped()
                    Log.e(TAG, "Flush segment inference failed", e)
                }
            }
        }
        // The audio ended and all of it was heard: no utterance is left open.
        evidence?.utteranceEnded(running = isActive)
    }

    /**
     * Whether [samples] go to the speech model: at least [MIN_DECODE_SAMPLES] of them, all
     * numbers. A shorter stretch holds no word, and sherpa-onnx models have been seen to end the
     * whole process on one (Omnilingual ASR on under 400 samples, sherpa-onnx issue #4042; untested
     * for the others), so it is passed over, with a line in the log.
     */
    private fun decodable(samples: FloatArray): Boolean {
        if (samples.size < MIN_DECODE_SAMPLES) {
            if (samples.isNotEmpty()) Log.d(TAG, "${samples.size / SAMPLES_PER_MS} ms of audio not decoded: under $MIN_DECODE_MS ms")
            return false
        }
        return samples.all { it.isFinite() }
    }

    /**
     * [samples] decoded as a complete utterance ([decodeFinal]): its text, or null for none, and
     * also for a text without a letter or a digit ([DiscardSilentRecordingUseCase.holdsSpeech]),
     * which says no word. A speech model writes one for a noise, such as the start buzz the
     * microphone picks up: Parakeet a lone ".", the Malayalam model a vowel sign or two that no
     * letter carries, which the clean-up of a line's Malayalam leaves out
     * ([com.satori.domain.language.MalayalamText]); in 0.7.0 such a line was stored empty at the
     * start of a transcript. No line is given out for it, live or for a file.
     */
    private suspend fun decodeLine(samples: FloatArray): String? {
        val text = decodeFinal(samples) ?: return null
        if (DiscardSilentRecordingUseCase.holdsSpeech(text)) return text
        Log.d(TAG, "${samples.size / SAMPLES_PER_MS} ms of audio decoded to no word (${text.length} chars): no line")
        return null
    }

    /** [samples] decoded as a partial result ([decode]): its text, or null for none or none with a word in it, as [decodeLine]. */
    private suspend fun decodePartial(samples: FloatArray): String? =
        decode(samples)?.takeIf { DiscardSilentRecordingUseCase.holdsSpeech(it) }

    companion object {
        // The recognizer's tag, which the field log is read by.
        private const val TAG = "SherpaOnnxDataSource"

        const val SAMPLE_RATE_HZ = 16000
        private const val SAMPLES_PER_MS = SAMPLE_RATE_HZ / 1000

        /** The shortest stretch of audio the speech model is given: 0.1 s ([decodable]). */
        internal const val MIN_DECODE_MS = 100
        internal const val MIN_DECODE_SAMPLES = MIN_DECODE_MS * SAMPLES_PER_MS

        private const val VAD_WINDOW_SIZE = 512
        private const val SPEECH_START_LOOKBACK_SAMPLES = 6400 // 0.4s
        private const val MIN_INFERENCE_INTERVAL_MS = 200L
        // Minimum new audio samples since last inference before triggering again.
        // The offline Parakeet TDT model produces better results with more audio context.
        // This threshold ensures consistent accuracy regardless of device inference speed.
        // 24000 samples = 1.5s at 16kHz
        private const val MIN_NEW_AUDIO_SAMPLES = 24000
        // The same once the utterance a partial result decodes is LONG_SEGMENT_SAMPLES or longer.
        // 48000 samples = 3s at 16kHz; 128000 samples = 8s
        private const val MIN_NEW_AUDIO_SAMPLES_LONG = 48000
        private const val LONG_SEGMENT_SAMPLES = 128000
        // Maximum audio samples passed to a single inference call.
        // The offline model re-decodes the entire buffer from speechStartOffset each call.
        // Without a cap, inference time grows linearly with segment length (O(n) per call,
        // O(n²) total), reaching 9s+ on 60s segments. Capping at 30s keeps inference
        // bounded while providing enough context for accurate partial results.
        // The final VAD segment uses its own buffer and is NOT affected by this cap.
        // 480000 samples = 30s at 16kHz
        private const val MAX_INFERENCE_AUDIO_SAMPLES = 480000
        // Audio samples to carry over from the end of a VAD segment into the next.
        // This provides context so the model doesn't start from zero after a silence gap.
        // It is also all the buffer keeps before the VAD while nobody speaks, which covers
        // SPEECH_START_LOOKBACK_SAMPLES.
        // 48000 samples = 3s at 16kHz
        private const val CONTEXT_CARRY_OVER_SAMPLES = 48000

        /**
         * Where a VAD segment of [size] samples was said, in samples. The VAD gives a segment out
         * once it has heard [trailingSilence] after it, so it ends that much before [readTo], how
         * far the VAD had read: the segment can have ended a few windows earlier, which only
         * moves it by milliseconds. Never before the start of the audio, nor past [readTo].
         */
        internal fun segmentSpan(readTo: Long, size: Int, trailingSilence: Int): Pair<Long, Long> {
            val start = maxOf(readTo - trailingSilence - size, 0L)
            return start to minOf(start + size, readTo)
        }

        /**
         * How much new audio, in samples, the next partial result waits for: 1.5 s while the
         * [openSegment] it decodes is under 8 s, then 3 s. Each partial decodes the whole
         * utterance so far again, so its cost grows with it: at Parakeet v3's real-time factor on
         * the razr (about 0.075 with 2 threads) an 8 s utterance takes about 0.6 s per partial and
         * a 20 s one, as long as the live voice detection lets one grow, about 1.5 s. Every 1.5 s
         * that would keep the decoder busy the whole time; every 3 s it is busy about half of it,
         * which leaves the complete utterances and the AI model their share.
         */
        internal fun partialStepSamples(openSegment: Int): Int =
            if (openSegment < LONG_SEGMENT_SAMPLES) MIN_NEW_AUDIO_SAMPLES else MIN_NEW_AUDIO_SAMPLES_LONG
    }
}

/**
 * A change of a recording's speech model between two utterances ([SpeechRecognition.run]): its
 * language was switched while it records ("Record as"), and the live model of the new one takes the
 * old one's place. The recognition waits while it runs, and the audio waits in its queue.
 */
internal interface ModelSwitch {
    /** Whether the model is to be changed now. Asked before each chunk while no utterance is open, so it must be quick. */
    fun due(): Boolean

    /**
     * Changes it, which takes seconds; [atMs] is how much of the audio the recognition has been
     * given. Throws when the recording can't go on: no speech model loads.
     */
    suspend fun switch(atMs: Long)
}
