package com.satori.feature.stt.datasource

import com.k2fsa.sherpa.onnx.SpeechSegment
import com.satori.data.monitor.LoadSupersededException
import com.satori.domain.repository.SpeechLeftForAfterStopException
import com.satori.domain.model.AppSettings
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.MicSource
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.VadParameters
import com.satori.domain.model.VadProfile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording that starts in English or German and is switched to Malayalam before any of its
 * audio is decoded, as on the phone on 2026-10-16, where the switch came 2.5 s into Parakeet v3's
 * 2.8 s load: the real capture, the real capture's model and the shared live recognizer, with loads
 * that stand in for the native ones (Parakeet's files read in first, then its native load, which
 * nothing stops half way). A switch while the files are read in stops Parakeet's load, so it never
 * takes its memory; one during the native load frees Parakeet as soon as that ends, before the
 * recognition starts. Either way the two models are never in memory together, Parakeet decodes
 * nothing, and the capture records throughout.
 */
class LanguageSwitchAtStartTest {

    private companion object {
        const val READ = 1_600 // 100 ms, one microphone read
        const val READS = 20
        const val WAIT_MS = 10_000L
        val VAD: VadParameters = VadParameters.of(VadProfile.LIVE, AppSettings())
    }

    /** A recognizer of [model]. */
    private class Recognizer(val model: SpeechModelId)

    // What happened, in order: the loads and frees, the capture's microphone and the recognition's start.
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val summaries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val decodedBy: MutableList<SpeechModelId> = Collections.synchronizedList(mutableListOf())
    // Every change of the capture's state, in order.
    private val stateChanges: MutableList<CaptureState> = Collections.synchronizedList(mutableListOf())
    private val states = CaptureStates(onChange = { stateChanges += it })
    private val evidence = SpeechEvidence()

    // The recording's language as its recorder holds it, which the test switches.
    private val language = object : CaptureLanguage {
        @Volatile override var current: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN
    }

    // Recognizers in memory now, and the most there were at once.
    private val inMemory = AtomicInteger()
    private val mostInMemory = AtomicInteger()

    // Parakeet's files are read in until preReadEnds lets them, then its native load runs until nativeEnds does.
    private val preReading = CountDownLatch(1)
    private val preReadEnds = CountDownLatch(1)
    private val nativeLoading = CountDownLatch(1)
    private val nativeEnds = CountDownLatch(1)

    /**
     * The live recognizer the captures share, its loads as ModelLoadPacing's recorder load runs
     * them: the files read in first, asking at each piece whether the model is still wanted, and
     * once more before the native load, which then runs to its end.
     */
    private val shared = LiveRecognizer<SpeechModelId, Recognizer>(
        load = { model, stop ->
            events += "pre-read ${model.id}"
            if (model == SpeechModelId.PARAKEET_V3) {
                preReading.countDown()
                while (!preReadEnds.await(5, TimeUnit.MILLISECONDS)) {
                    stop()?.let { why ->
                        events += "stopped ${model.id}"
                        throw LoadSupersededException("${model.id} is no longer wanted", why)
                    }
                }
            }
            stop()?.let { why ->
                events += "not started ${model.id}"
                throw LoadSupersededException("${model.id} is no longer wanted", why)
            }
            events += "native ${model.id}"
            if (model == SpeechModelId.PARAKEET_V3) {
                nativeLoading.countDown()
                check(nativeEnds.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "the native load was never let end" }
            }
            mostInMemory.accumulateAndGet(inMemory.incrementAndGet()) { a, b -> maxOf(a, b) }
            events += "in ${model.id}"
            Recognizer(model)
        },
        free = { model, _ ->
            inMemory.decrementAndGet()
            events += "free ${model.id}"
        }
    )
    private val decodeLock = Mutex()

    /**
     * The capture's speech side as SherpaOnnxDataSource builds it: its own model over the shared
     * recognizer, which Stop stops before the native load when the recording [keepsAudio].
     */
    private fun speech(keepsAudio: Boolean = false): LiveSpeech = object : LiveSpeech {
        private val model = CaptureSpeechModel(
            shared, language, decodeLock, isNewest = { true }, io = Dispatchers.Default,
            warn = { message, _ -> events += "warn $message" }, log = { events += "log $it" },
            stopsAtStop = { keepsAudio && capture.isStopped }
        )

        override suspend fun load(): VoiceDetector {
            model.load()
            return Talking()
        }

        // As decodeShared: only with the capture's own model in memory.
        override suspend fun decode(samples: FloatArray, speed: DecodeSpeed?): String? = decodeLock.withLock {
            val recognizer = shared.loadedOrNull(model.model()) ?: return@withLock null
            decodedBy += recognizer.model
            "${samples.size} samples"
        }

        override fun hintPerformance() {
            events += "recognize"
        }

        override fun model(): SpeechModelId = model.model()

        override fun switchDue(): SpeechModelId? = model.switchDue()

        override suspend fun switchTo(model: SpeechModelId): Boolean = this.model.switchTo(model)
    }

    /** Hears speech from the first window on and never its end: only the end of the audio completes it. */
    private class Talking : VoiceDetector {
        private var fed = 0L
        override val minSilenceSamples = 8_000
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

    /** A microphone that gives [READS] reads of audio, then nothing until it is closed. */
    private inner class TwoSeconds : Microphone {
        val allQueued = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)
        private var next = 0

        override fun read(buffer: ShortArray): Int {
            if (next < READS && closed.count > 0) {
                buffer.fill((++next).toShort(), 0, READ)
                return READ
            }
            allQueued.complete(Unit)
            closed.await(WAIT_MS, TimeUnit.MILLISECONDS)
            return -3 // AudioRecord.ERROR_INVALID_OPERATION, as a stopped and released record gives
        }

        override fun close() {
            events += "close"
            closed.countDown()
        }

        override fun report(): InputReport = InputReport(effects = emptyList(), microphones = 1)
    }

    private val microphone = TwoSeconds()

    private val capture = LiveCapture(
        device = object : CaptureDevice {
            override fun takeFeed(): ShortArray? = null

            override fun open(source: MicSource, refusals: MutableList<String>, preferred: MicSwitches.Device): Microphone {
                events += "open"
                return microphone
            }
        },
        micInput = MicInput(),
        progress = states.begin(),
        evidence = evidence,
        audioLevel = MutableStateFlow(0f),
        library = { "1.13.8" },
        logSummary = { summaries += it },
        monotonicClock = { System.nanoTime() / 1_000_000 },
        audioPriority = object : AudioThreadPriority {
            override fun raise(): Int = 0

            override fun restore(previous: Int) = Unit
        },
        logLine = { lines += it }
    )

    private fun CoroutineScope.start(keepsAudio: Boolean = false): Deferred<Result<Unit>> = async(Dispatchers.Default) {
        runCatching { capture.run(MicSource.VOICE_RECOGNITION, VAD, speech(keepsAudio), audio = null, metrics = null, onStoppedWhileOpening = {}) {} }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(2 * WAIT_MS) { block() } }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `switched while Parakeet's files are read in, Parakeet never loads and the Malayalam model transcribes all of it`() = test {
        val run = start()
        assertTrue(preReading.await(WAIT_MS, TimeUnit.MILLISECONDS))
        // The capture records while the model's files are read in.
        until { states.flow.value == CaptureState.LISTENING }

        language.current = RecordingLanguage.MALAYALAM
        microphone.allQueued.await()
        until { "recognize" in events }
        capture.stop()

        assertTrue(run.await().isSuccess)
        assertTrue(decodedBy.isNotEmpty())
        assertEquals(
            listOf(
                "open",
                "pre-read parakeet-tdt-0.6b-v3-int8",
                "stopped parakeet-tdt-0.6b-v3-int8",
                "log Live speech model parakeet-tdt-0.6b-v3-int8 not loaded, as the recording switched to indicconformer-ml-int8 first",
                "pre-read indicconformer-ml-int8",
                "native indicconformer-ml-int8",
                "in indicconformer-ml-int8",
                "recognize",
                "close"
            ),
            events.toList()
        )
        assertEquals(1, mostInMemory.get())
        assertTrue("$decodedBy", decodedBy.all { it == SpeechModelId.INDICCONFORMER_ML })
        // No change of the model was needed: the right one loaded first.
        assertTrue(lines.toString(), lines.none { it.startsWith("Live speech model switched") })
        assertTrue(summaries.single(), summaries.single().contains(" model=indicconformer-ml-int8 "))
    }

    @Test
    fun `switched while Parakeet loads natively, Parakeet is freed as soon as its load ends, before any audio is decoded`() = test {
        val run = start()
        assertTrue(preReading.await(WAIT_MS, TimeUnit.MILLISECONDS))
        preReadEnds.countDown()
        assertTrue(nativeLoading.await(WAIT_MS, TimeUnit.MILLISECONDS))

        language.current = RecordingLanguage.MALAYALAM
        nativeEnds.countDown()
        microphone.allQueued.await()
        until { "recognize" in events }
        capture.stop()

        assertTrue(run.await().isSuccess)
        assertTrue(decodedBy.isNotEmpty())
        // Freed before the Malayalam model loads, and both before the recognition starts.
        assertEquals(
            listOf(
                "open",
                "pre-read parakeet-tdt-0.6b-v3-int8",
                "native parakeet-tdt-0.6b-v3-int8",
                "in parakeet-tdt-0.6b-v3-int8",
                "free parakeet-tdt-0.6b-v3-int8",
                "pre-read indicconformer-ml-int8",
                "native indicconformer-ml-int8",
                "in indicconformer-ml-int8",
                "recognize",
                "close"
            ),
            events.toList()
        )
        assertEquals(1, mostInMemory.get())
        assertTrue("$decodedBy", decodedBy.all { it == SpeechModelId.INDICCONFORMER_ML })
        val switched = lines.single { it.startsWith("Live speech model switched") }
        assertTrue(
            switched,
            Regex(
                "Live speech model switched to indicconformer-ml-int8 \\(was parakeet-tdt-0\\.6b-v3-int8\\) in \\d+ ms, " +
                    "at 0 ms of audio, with \\d+ ms of audio waiting"
            ).matches(switched)
        )
        // LISTENING until the Malayalam model is in, never TRANSCRIBING with Parakeet between: the
        // finisher and the status line read it so (review of 0.7.1).
        // (The data source's flow makes it IDLE as it ends; the capture alone doesn't.)
        assertEquals(listOf(CaptureState.OPENING, CaptureState.LISTENING, CaptureState.TRANSCRIBING), stateChanges.toList())
    }

    @Test
    fun `Stop while Parakeet's files are read in, in a recording that keeps its audio, ends the capture at once with nothing loaded`() = test {
        val run = start(keepsAudio = true)
        assertTrue(preReading.await(WAIT_MS, TimeUnit.MILLISECONDS))
        until { states.flow.value == CaptureState.LISTENING }
        microphone.allQueued.await()

        capture.stop()

        val failure = run.await().exceptionOrNull()
        assertTrue(failure.toString(), failure is SpeechLeftForAfterStopException)
        // The microphone closed at Stop, and the load stopped at its next piece; nothing came in.
        val happened = events.toList()
        assertEquals(happened.toString(), listOf("open", "pre-read parakeet-tdt-0.6b-v3-int8"), happened.take(2))
        assertEquals(happened.toString(), setOf("close", "stopped parakeet-tdt-0.6b-v3-int8"), happened.drop(2).toSet())
        assertEquals(happened.toString(), 4, happened.size)
        assertEquals(0, mostInMemory.get())
        assertTrue("$decodedBy", decodedBy.isEmpty())
        // The audio that waited counts as heard, so the recording is kept, and the summary says it was left for after Stop.
        assertTrue(evidence.mayHaveHeardSpeech)
        assertTrue(lines.toString(), lines.any { Regex("Stop came \\d+ ms into the load of parakeet-tdt-0\\.6b-v3-int8: nothing loaded, 2000 ms of audio kept untranscribed for after Stop").matches(it) })
        assertTrue(summaries.single(), summaries.single().endsWith(" ended=left"))
        // Never TRANSCRIBING: no model was in.
        assertEquals(listOf(CaptureState.OPENING, CaptureState.LISTENING), stateChanges.toList())
    }

    @Test
    fun `Stop while Parakeet's files are read in, in a recording that keeps no audio, waits for the model and transcribes what was recorded`() = test {
        val run = start(keepsAudio = false)
        assertTrue(preReading.await(WAIT_MS, TimeUnit.MILLISECONDS))
        until { states.flow.value == CaptureState.LISTENING }
        microphone.allQueued.await()

        capture.stop()
        delay(100)
        assertFalse("the capture ended without the only model that can transcribe its queue", run.isCompleted)
        preReadEnds.countDown()
        nativeEnds.countDown()

        assertTrue(run.await().isSuccess)
        assertTrue(decodedBy.isNotEmpty())
        assertTrue("$decodedBy", decodedBy.all { it == SpeechModelId.PARAKEET_V3 })
    }

    @Test
    fun `nothing switched, Parakeet's files are read in, then it loads, then the recognition starts`() = test {
        val run = start()
        assertTrue(preReading.await(WAIT_MS, TimeUnit.MILLISECONDS))
        preReadEnds.countDown()
        nativeEnds.countDown()
        microphone.allQueued.await()
        until { "recognize" in events }
        capture.stop()

        assertTrue(run.await().isSuccess)
        assertTrue(decodedBy.isNotEmpty())
        assertEquals(
            listOf(
                "open",
                "pre-read parakeet-tdt-0.6b-v3-int8",
                "native parakeet-tdt-0.6b-v3-int8",
                "in parakeet-tdt-0.6b-v3-int8",
                "recognize",
                "close"
            ),
            events.toList()
        )
        assertTrue("$decodedBy", decodedBy.all { it == SpeechModelId.PARAKEET_V3 })
        assertFalse(lines.toString(), lines.any { it.startsWith("Live speech model switched") })
    }
}
