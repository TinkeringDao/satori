package com.satori.feature.stt.datasource

import com.satori.data.audio.CaptureAudioWriter
import com.satori.data.audio.RecordingAudioFiles
import com.satori.data.audio.Wav
import com.satori.data.datasource.RecognitionResult
import com.satori.domain.model.AppSettings
import com.satori.domain.model.CaptureState
import com.satori.domain.model.MicSource
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.VadParameters
import com.satori.domain.model.VadProfile
import com.satori.domain.repository.MicrophoneLostException
import com.satori.domain.repository.SpeechModelLoadException
import com.k2fsa.sherpa.onnx.SpeechSegment
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A capture opens the microphone first and loads the speech model only then, while the audio
 * waits: nothing said during the load is lost, a Stop then still has it transcribed, and a load
 * that fails keeps the audio and says so. The microphone, the device and the speech model are
 * fakes; the queue, the capture loop and the recognition are the real ones.
 */
class LiveCaptureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private companion object {
        const val READ = 1_600 // 100 ms, one microphone read
        // 3.2 s: a whole number of the detector's 512-sample windows, so the recognition decodes all of it.
        const val READS = 32
        // What a read that a Stop cut short had: one window of the detector.
        const val CUT_SHORT = 512
        // What reading a stopped and released AudioRecord returns (ERROR_INVALID_OPERATION).
        const val ERROR_INVALID_OPERATION = -3
        // What reading a dead AudioRecord returns (ERROR_DEAD_OBJECT).
        const val ERROR_DEAD_OBJECT = -6
        // The priority a thread of the coroutine pool has before the capture loop raises it.
        const val POOL_PRIORITY = 10
        const val WAIT_MS = 10_000L
        // How long a capture may take to end by itself after a failure: far more than it needs.
        const val ENDS_BY_ITSELF_MS = 3_000L
        val VAD: VadParameters = VadParameters.of(VadProfile.LIVE, AppSettings())
    }

    // What happened, in order, across the device, the microphone, the speech model and the run.
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val states = CaptureStates()
    private val evidence = SpeechEvidence()
    private val priority = FakePriority()
    private val summaries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    // The run's end has begun: the summary line is the first thing it does.
    private val summaryLogged = CompletableDeferred<Unit>()
    private val results: MutableList<RecognitionResult> = Collections.synchronizedList(mutableListOf())
    // The lines of where the audio came from: the start, each change, the automatic gain's moves.
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Read [i] of the audio: each of its samples holds i + 1, so decoded audio shows the order it came in. */
    private fun read(i: Int) = ShortArray(READ) { (i + 1).toShort() }

    private val recorded: List<ShortArray> = List(READS) { read(it) }

    private val recordedSamples: List<Int> = recorded.flatMap { reads -> reads.map(Short::toInt) }

    /** Audio as the recognizer is given it, back as the microphone's samples. */
    private fun FloatArray.asSamples(): List<Int> = map { (it * 32768).roundToInt() }

    /**
     * A microphone that gives [reads], then nothing until it is closed, as a read waits until
     * there is audio; once closed, a read returns at once with the error of a released
     * AudioRecord. With [thenFails] it dies after them instead, as a dead AudioRecord does.
     * [allQueued] says that the capture loop asked for more after the last of [reads], so all of
     * them are in the queue. Each report the capture asks for is the next of [reports], the last
     * one again once they are used up; [asked] counts them.
     */
    private inner class FakeMicrophone(
        private val reads: List<ShortArray>,
        private val reports: List<InputReport> = listOf(InputReport(effects = emptyList(), microphones = 1)),
        private val thenFails: Boolean = false
    ) : Microphone {
        val closes = AtomicInteger()
        val allQueued = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)
        private var next = 0

        @Volatile var asked = 0
            private set

        override fun read(buffer: ShortArray): Int {
            if (closes.get() == 0 && next < reads.size) {
                reads[next++].copyInto(buffer)
                return READ
            }
            allQueued.complete(Unit)
            if (thenFails && closes.get() == 0) return ERROR_DEAD_OBJECT
            closed.await(WAIT_MS, TimeUnit.MILLISECONDS)
            return ERROR_INVALID_OPERATION
        }

        override fun close() {
            events += "close"
            closes.incrementAndGet()
            closed.countDown()
        }

        override fun report(): InputReport = reports[minOf(asked++, reports.lastIndex)]
    }

    /**
     * A microphone as an AudioRecord is one: it gives [reads], then waits for audio. A Stop cuts
     * that read short with the [CUT_SHORT] samples it had, and asking the released record what the
     * phone does with it throws, as AudioRecord.getActiveMicrophones() does; [asksAfterRelease]
     * counts those asks. Before, the audio server hasn't listed the recording yet: the effects are
     * unknown.
     */
    private inner class ReleasedOnStop(private val reads: List<ShortArray>) : Microphone {
        val allQueued = CompletableDeferred<Unit>()
        val asksAfterRelease = AtomicInteger()
        @Volatile private var released = false
        private val closed = CountDownLatch(1)
        private val cutShort = this@LiveCaptureTest.read(reads.size)
        private var next = 0

        override fun read(buffer: ShortArray): Int {
            if (next < reads.size) {
                reads[next++].copyInto(buffer)
                return READ
            }
            if (released) return ERROR_INVALID_OPERATION
            allQueued.complete(Unit)
            closed.await(WAIT_MS, TimeUnit.MILLISECONDS)
            cutShort.copyInto(buffer, endIndex = CUT_SHORT)
            return CUT_SHORT
        }

        override fun close() {
            events += "close"
            released = true
            closed.countDown()
        }

        override fun report(): InputReport {
            if (released) {
                asksAfterRelease.incrementAndGet()
                throw IllegalStateException("Unable to retrieve AudioRecord pointer for getActiveMicrophones()")
            }
            return InputReport(effects = null, microphones = 1)
        }
    }

    /**
     * A microphone someone speaks into: a read brings audio every [paceMs] until it is closed, so
     * the capture loop ends only if it is stopped or cancelled. [gave] completes after
     * [signalAfter] reads.
     */
    private inner class StreamingMicrophone(private val signalAfter: Int, private val paceMs: Long = 20) : Microphone {
        val closes = AtomicInteger()
        val gave = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)

        /** How many reads brought audio. */
        @Volatile var reads = 0
            private set

        override fun read(buffer: ShortArray): Int {
            if (closed.await(paceMs, TimeUnit.MILLISECONDS)) return ERROR_INVALID_OPERATION
            this@LiveCaptureTest.read(reads % READS).copyInto(buffer)
            if (++reads == signalAfter) gave.complete(Unit)
            return READ
        }

        override fun close() {
            events += "close"
            closes.incrementAndGet()
            closed.countDown()
        }

        override fun report() = InputReport(effects = emptyList(), microphones = 1)
    }

    /**
     * [microphone] as Android routes it: from [routed] at first (null: not yet said), and anew at
     * each [reroute], which calls back as Android does.
     */
    private inner class Routed(private val microphone: Microphone, @Volatile private var routed: String?) : Microphone by microphone {
        @Volatile private var watcher: ((String?) -> Unit)? = null

        override fun routedDevice(): String? = routed

        override fun watchRoute(onChange: (String?) -> Unit) {
            watcher = onChange
        }

        fun reroute(device: String) {
            routed = device
            watcher?.invoke(device)
        }
    }

    /** The phone folded as [now] says, and folded anew at each [refold], which calls back. */
    private class FakePosture(@Volatile private var now: String) : PhonePosture {
        @Volatile private var watcher: ((String) -> Unit)? = null
        val unwatched = AtomicBoolean(false)

        override fun fold(): String = now

        override fun watchFold(onChange: (String) -> Unit): AutoCloseable {
            watcher = onChange
            return AutoCloseable { unwatched.set(true) }
        }

        fun refold(to: String) {
            now = to
            watcher?.invoke(to)
        }
    }

    /** The phone: [inputs] open, any other input is refused. [whileOpening] runs as an input opens. */
    private inner class FakeDevice(
        private val inputs: Map<MicSource, Microphone>,
        private val feed: ShortArray? = null,
        private val whileOpening: () -> Unit = {}
    ) : CaptureDevice {
        override fun takeFeed(): ShortArray? = feed

        override fun open(source: MicSource, refusals: MutableList<String>, preferred: MicSwitches.Device): Microphone? {
            events += if (preferred == MicSwitches.Device.AUTO) "open $source" else "open $source at ${preferred.value}"
            whileOpening()
            val input = inputs[source]
            if (input == null) refusals += "$source: refused"
            return input
        }
    }

    /**
     * The speech model: [load] waits until the test lets it in ([letLoad]), then fails with
     * [failure] or gives a [detector], by default one that hears speech in all of the audio, so the
     * whole capture is one utterance that the end of the audio completes. The audio of each
     * complete utterance is kept in [decoded], and the model that decoded it in [decodedBy];
     * partial results give no text. [loading] and [recognizing] say the capture's state as the
     * load and the recognition start; with [recognitionFailure], the recognition fails as it starts.
     *
     * It loads the model of the recording's language, [wanted], which the test can switch while
     * the capture runs, or which is switched to [wantedOnceLoaded] as the load ends; [switchTo] then
     * fails with [switchFailure] (neither model loads), or with [refuses] goes back to the model it
     * had, as one that didn't load does. [switching] completes as the first change begins.
     */
    private inner class FakeSpeech(
        private val failure: Throwable? = null,
        private val recognitionFailure: Throwable? = null,
        private val detector: () -> VoiceDetector = ::Talking,
        private val switchFailure: Throwable? = null,
        private val refuses: Boolean = false,
        private val wantedOnceLoaded: SpeechModelId? = null
    ) : LiveSpeech {
        val loading = CompletableDeferred<CaptureState>()
        val recognizing = CompletableDeferred<CaptureState>()
        val switching = CompletableDeferred<Unit>()
        val decoded: MutableList<FloatArray> = Collections.synchronizedList(mutableListOf())
        val decodedBy: MutableList<SpeechModelId?> = Collections.synchronizedList(mutableListOf())
        private val letIn = CountDownLatch(1)

        @Volatile var wanted: SpeechModelId = SpeechModelId.PARAKEET_V3
        @Volatile private var inUse: SpeechModelId? = null
        @Volatile private var refused: SpeechModelId? = null

        /** The capture's state as the first change of the model ran. */
        @Volatile var stateWhileSwitching: CaptureState? = null
            private set

        fun letLoad() = letIn.countDown()

        override suspend fun load(): VoiceDetector {
            events += "load"
            loading.complete(states.flow.value)
            letIn.await(WAIT_MS, TimeUnit.MILLISECONDS)
            if (failure != null) {
                events += "load failed"
                throw failure
            }
            events += "loaded"
            inUse = wanted
            wantedOnceLoaded?.let { wanted = it }
            return detector()
        }

        override suspend fun decode(samples: FloatArray, speed: DecodeSpeed?): String? {
            if (speed == null) return null
            decoded += samples
            decodedBy += inUse
            return "${samples.size} samples"
        }

        override fun hintPerformance() {
            recognizing.complete(states.flow.value)
            if (recognitionFailure != null) throw recognitionFailure
        }

        override fun model(): SpeechModelId = inUse ?: wanted

        override fun switchDue(): SpeechModelId? {
            val model = inUse ?: return null
            return wanted.takeIf { it != model && it != refused }
        }

        override suspend fun switchTo(model: SpeechModelId): Boolean {
            events += "switch to ${model.id}"
            if (stateWhileSwitching == null) stateWhileSwitching = states.flow.value
            switching.complete(Unit)
            if (switchFailure != null) throw switchFailure
            if (refuses) {
                refused = model
                return false
            }
            inUse = model
            return true
        }
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

    /** Hears no speech at all: a capture in which nothing is said. */
    private class Silent : VoiceDetector {
        override val minSilenceSamples = 8_000
        override val maxSpeechSamples = Int.MAX_VALUE
        override fun acceptWaveform(samples: FloatArray) = Unit
        override fun isSpeechDetected() = false
        override fun empty() = true
        override fun front(): SpeechSegment = error("no utterance")
        override fun pop() = Unit
        override fun reset() = Unit
    }

    /**
     * Hears speech in [speech] windows of 512 samples, then a pause of [pause] windows, over and
     * over, and gives each utterance out with its audio at the end of its pause, as Silero does: a
     * capture of short sentences with pauses between them. By default each round is 25 windows, 8
     * reads: an utterance comes out just as a read ends, so the recognition skips none of the pause
     * after it (it skips what of a read is left past the last whole window when it takes one out).
     */
    private class Pauses(private val speech: Int = 12, private val pause: Int = 13) : VoiceDetector {
        private val round = speech + pause
        private var windows = 0
        private val heard = mutableListOf<FloatArray>()
        private val out = ArrayDeque<SpeechSegment>()
        override val minSilenceSamples = pause * WINDOW
        override val maxSpeechSamples = Int.MAX_VALUE

        /** How many samples it was fed, and how many utterances it gave out. */
        @Volatile var fed = 0L
            private set
        @Volatile var givenOut = 0
            private set

        override fun acceptWaveform(samples: FloatArray) {
            fed += samples.size
            if (windows % round < speech) heard += samples.copyOf()
            windows++
            if (windows % round == 0) {
                givenOut++
                val utterance = FloatArray(heard.sumOf { it.size })
                var at = 0
                heard.forEach { window ->
                    window.copyInto(utterance, at)
                    at += window.size
                }
                heard.clear()
                out += SpeechSegment(0, utterance)
            }
        }

        override fun isSpeechDetected() = windows > 0 && (windows - 1) % round < speech
        override fun empty() = out.isEmpty()
        override fun front(): SpeechSegment = out.first()
        override fun pop() {
            out.removeFirst()
        }

        override fun reset() = Unit

        private companion object {
            const val WINDOW = 512
        }
    }

    /** The capture thread's priority: each raise and restore, with the thread it came on. */
    private class FakePriority : AudioThreadPriority {
        val calls: MutableList<Pair<String, Thread>> = Collections.synchronizedList(mutableListOf())

        override fun raise(): Int {
            calls += "raise" to Thread.currentThread()
            return POOL_PRIORITY
        }

        override fun restore(previous: Int) {
            calls += "restore $previous" to Thread.currentThread()
        }
    }

    private fun capture(
        device: CaptureDevice,
        progress: CaptureStates.Progress = states.begin(),
        monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
        library: () -> String = { "1.13.8" },
        posture: PhonePosture = PhonePosture.Unknown
    ) = LiveCapture(
        device = device,
        micInput = MicInput(),
        progress = progress,
        evidence = evidence,
        audioLevel = MutableStateFlow(0f),
        library = library,
        logSummary = {
            summaries += it
            summaryLogged.complete(Unit)
        },
        monotonicClock = monotonicClock,
        audioPriority = priority,
        posture = posture,
        logLine = { lines += it }
    )

    /** Runs [capture] in the background, as the recorder collects its flow: what it gave, or what it threw. */
    private fun CoroutineScope.start(
        capture: LiveCapture,
        speech: LiveSpeech,
        micSource: MicSource = MicSource.VOICE_RECOGNITION,
        audio: RecordingAudioFiles? = null,
        onStoppedWhileOpening: () -> Unit = {},
        switches: MicSwitches = MicSwitches.DEFAULT
    ): Deferred<Result<Unit>> = async(Dispatchers.Default) {
        runCatching {
            capture.run(micSource, VAD, speech, audio, metrics = null, onStoppedWhileOpening, switches) { results += it }
        }.also { if (it.isFailure) events += "run failed" }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(2 * WAIT_MS) { block() } }

    private fun microphoneOnly(microphone: Microphone, source: MicSource = MicSource.VOICE_RECOGNITION) =
        FakeDevice(mapOf(source to microphone))

    @Test
    fun `the microphone opens before the speech model loads`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        speech.loading.await()
        assertEquals(listOf("open VOICE_RECOGNITION", "load"), events.toList())

        speech.letLoad()
        microphone.allQueued.await()
        live.stop()
        assertTrue(run.await().isSuccess)
    }

    @Test
    fun `what the microphone records while the speech model loads is transcribed once it is in, in order`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        // All 3.2 s are recorded while the speech model loads, and none of it is decoded meanwhile.
        microphone.allQueued.await()
        assertTrue(speech.decoded.isEmpty())
        speech.letLoad()
        speech.recognizing.await()
        live.stop()

        assertTrue(run.await().isSuccess)
        val line = results.single { it.isComplete }
        assertEquals("${READS * READ} samples", line.text)
        assertEquals(recordedSamples, speech.decoded.single().asSamples())
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `a Stop while the speech model loads closes the microphone once, and what it recorded still comes out`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        speech.loading.await()
        microphone.allQueued.await()
        live.stop()
        // Closed at once, while the speech model still loads.
        assertEquals(1, microphone.closes.get())
        speech.letLoad()

        assertTrue(run.await().isSuccess)
        assertEquals(listOf("open VOICE_RECOGNITION", "load", "close", "loaded"), events.toList())
        assertEquals("${READS * READ} samples", results.single { it.isComplete }.text)
        assertEquals(recordedSamples, speech.decoded.single().asSamples())
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `a Stop before the phone said what it does with the input still has all that was recorded transcribed`() = test {
        // 1.6 s: the phone is still asked at every read, as the audio server hasn't listed the
        // recording yet.
        val reads = recorded.take(16)
        val microphone = ReleasedOnStop(reads)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        microphone.allQueued.await()
        // Releases the record while a read waits, which then comes back with what it had.
        live.stop()
        speech.letLoad()

        val outcome = run.await()
        assertTrue("failed with ${outcome.exceptionOrNull()}", outcome.isSuccess)
        val heard = reads.flatMap { read -> read.map(Short::toInt) } + List(CUT_SHORT) { reads.size + 1 }
        assertEquals(heard, speech.decoded.single().asSamples())
        assertEquals(0, microphone.asksAfterRelease.get())
        val summary = summaries.single()
        assertTrue(summary, summary.contains(" mic=VOICE_RECOGNITION effects=? mics=1 "))
        assertTrue(summary, summary.endsWith(" ended=done"))
    }

    @Test
    fun `a phone that fails to say what it does with the input leaves that unknown, and the capture goes on`() = test {
        val inner = FakeMicrophone(recorded)
        val microphone = object : Microphone by inner {
            override fun report(): InputReport = throw IllegalStateException("The audio server didn't answer")
        }
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        inner.allQueued.await()
        live.stop()

        assertTrue(run.await().isSuccess)
        assertEquals(recordedSamples, speech.decoded.single().asSamples())
        assertTrue(summaries.single(), summaries.single().contains(" mic=VOICE_RECOGNITION effects=? mics=? "))
    }

    @Test
    fun `a speech model that fails to load ends the capture once the microphone is closed, and the audio keeps what was recorded`() = test {
        val files = RecordingAudioFiles(File(folder.root, "recording.wav"), File(folder.root, "recording.idx"))
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech(failure = IllegalStateException("The speech model files are damaged or incomplete"))
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech, audio = files)

        microphone.allQueued.await()
        speech.letLoad()
        val error = run.await().exceptionOrNull()

        assertTrue("failed with $error", error is SpeechModelLoadException)
        // Coroutines may hand over a copy, whose cause is the original; the load's failure is under it.
        val causes = generateSequence(error) { it.cause }.map { it.message }.toList()
        assertTrue("$causes", "The speech model files are damaged or incomplete" in causes)
        assertEquals(listOf("open VOICE_RECOGNITION", "load", "load failed", "close", "run failed"), events.toList())
        assertEquals(1, microphone.closes.get())
        assertTrue(results.isEmpty())
        // Nothing transcribed what was recorded, so the recording isn't taken for a silent one.
        assertTrue(evidence.mayHaveHeardSpeech)
        val kept = files.wav.readBytes()
        assertEquals(READS * READ * 2L, Wav.dataBytes(files.wav))
        assertEquals(recordedSamples, Wav.toFloats(kept.copyOfRange(Wav.HEADER_BYTES, kept.size), READS * READ).asSamples())
        assertTrue(summaries.single(), summaries.single().endsWith(" ended=failed"))
    }

    @Test
    fun `a speech model whose native library doesn't load fails the same way, without waiting for a Stop`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech(failure = UnsatisfiedLinkError("dlopen failed: library \"libsherpa-onnx-jni.so\" not found"))
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        microphone.allQueued.await()
        speech.letLoad()
        val outcome = withTimeoutOrNull(ENDS_BY_ITSELF_MS) { run.await() }
        live.stop()

        assertNotNull("the microphone recorded on after the load failed", outcome)
        val error = outcome?.exceptionOrNull()
        assertTrue("failed with $error", error is SpeechModelLoadException)
        assertTrue(generateSequence(error) { it.cause }.any { it is UnsatisfiedLinkError })
        assertEquals(1, microphone.closes.get())
        assertTrue(evidence.mayHaveHeardSpeech)
    }

    @Test
    fun `a speech model that fails to load before any audio came leaves nothing taken for speech`() = test {
        val microphone = FakeMicrophone(reads = emptyList())
        val speech = FakeSpeech(failure = IllegalStateException("The speech model files are damaged or incomplete"))
            .also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))

        val error = start(live, speech).await().exceptionOrNull()

        assertTrue("failed with $error", error is SpeechModelLoadException)
        assertEquals(1, microphone.closes.get())
        assertFalse(evidence.mayHaveHeardSpeech)
    }

    @Test
    fun `a recognition that fails ends the capture and closes the microphone, without waiting for a Stop`() = test {
        val microphone = StreamingMicrophone(signalAfter = 10)
        val speech = FakeSpeech(recognitionFailure = IllegalStateException("The recognition failed to start"))
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        // A second of audio waits for the speech model; the recognition then fails as it starts.
        microphone.gave.await()
        speech.letLoad()
        val outcome = withTimeoutOrNull(ENDS_BY_ITSELF_MS) { run.await() }
        live.stop()

        assertNotNull("the microphone recorded on after the recognition failed", outcome)
        assertTrue("failed with ${outcome?.exceptionOrNull()}", outcome?.exceptionOrNull() is IllegalStateException)
        assertEquals(1, microphone.closes.get())
        // That second was never heard.
        assertTrue(evidence.mayHaveHeardSpeech)
        assertTrue(summaries.single(), summaries.single().endsWith(" ended=failed"))
    }

    @Test
    fun `audio that waits for the speech model counts as speech, also for a capture cut off before it was heard`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech(detector = ::Silent)
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)
        microphone.allQueued.await()

        // The recorder closes during the load: its scope is cancelled and it stops the capture.
        run.cancel()
        live.stop()
        // It reads the evidence right then, while the capture still ends: here its load still runs.
        assertTrue(evidence.mayHaveHeardSpeech)
        speech.letLoad()
        run.join()

        assertTrue(speech.decoded.isEmpty())
        assertTrue(evidence.mayHaveHeardSpeech)
        assertEquals(1, microphone.closes.get())
        assertTrue(summaries.single(), summaries.single().endsWith(" ended=cancelled"))
    }

    @Test
    fun `audio the recognition has heard counts no longer, so a capture in which nothing is said has no speech`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech(detector = ::Silent)
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)
        microphone.allQueued.await()
        assertTrue(evidence.mayHaveHeardSpeech)

        speech.letLoad()
        live.stop()

        assertTrue(run.await().isSuccess)
        assertTrue(results.isEmpty())
        assertFalse(evidence.mayHaveHeardSpeech)
    }

    @Test
    fun `a capture opens, listens while the speech model loads, transcribes once it is in, and is idle at its end`() = test {
        val seen: MutableList<CaptureState> = Collections.synchronizedList(mutableListOf())
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val progress = states.begin()
        val device = FakeDevice(mapOf(MicSource.VOICE_RECOGNITION to microphone), whileOpening = { seen += states.flow.value })
        val live = capture(device, progress)
        val run = start(live, speech)

        microphone.allQueued.await()
        seen += states.flow.value
        speech.letLoad()
        seen += speech.recognizing.await()
        live.stop()
        run.await()
        // As the flow of SherpaOnnxDataSource.startRecording ends.
        progress.end()
        seen += states.flow.value

        assertEquals(
            listOf(CaptureState.OPENING, CaptureState.LISTENING, CaptureState.TRANSCRIBING, CaptureState.IDLE),
            seen.toList()
        )
    }

    @Test
    fun `a capture listens from its first read that brings audio, not from the opening of the microphone`() = test {
        val seen: MutableList<CaptureState> = Collections.synchronizedList(mutableListOf())
        val inner = FakeMicrophone(recorded)
        var reads = 0
        // Its first read brings no audio yet.
        val microphone = object : Microphone by inner {
            override fun read(buffer: ShortArray): Int {
                seen += states.flow.value
                return if (reads++ == 0) 0 else inner.read(buffer)
            }
        }
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        inner.allQueued.await()
        speech.letLoad()
        live.stop()
        assertTrue(run.await().isSuccess)

        // As the first read starts, after it brought nothing, and after the first audio.
        assertEquals(listOf(CaptureState.OPENING, CaptureState.OPENING, CaptureState.LISTENING), seen.take(3))
    }

    @Test
    fun `a capture cut short by a newer one leaves the state to that one`() = test {
        val older = states.begin()
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone), older)
        val run = start(live, speech)
        microphone.allQueued.await()

        // A newer capture begins and stops this one, as SherpaOnnxDataSource does; this one's
        // speech model comes in only after that.
        val newer = states.begin()
        live.stop()
        speech.letLoad()
        assertTrue(run.await().isSuccess)
        older.end()

        assertEquals(CaptureState.OPENING, states.flow.value)
        // Its last words still came out: they are its own.
        assertEquals(1, results.count { it.isComplete })
        newer.end()
        assertEquals(CaptureState.IDLE, states.flow.value)
    }

    @Test
    fun `when no input opens nothing loads, and the capture fails as a lost microphone`() = test {
        val speech = FakeSpeech()
        val live = capture(FakeDevice(inputs = emptyMap()))

        val error = start(live, speech).await().exceptionOrNull()

        assertTrue("failed with $error", error is MicrophoneLostException)
        assertEquals(listOf("open VOICE_RECOGNITION", "open MIC", "run failed"), events.toList())
        assertFalse(speech.loading.isCompleted)
        assertFalse(evidence.mayHaveHeardSpeech)
        assertTrue(summaries.single(), summaries.single().contains(" mic=none effects=none mics=0 "))
        // The thread's priority is given back on this way out too.
        assertEquals(listOf("raise", "restore $POOL_PRIORITY"), priority.calls.map { it.first })
    }

    @Test
    fun `a Stop while the microphone opens closes it as it opens, and nothing loads`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val stoppedWhileOpening = AtomicBoolean(false)
        lateinit var live: LiveCapture
        live = capture(FakeDevice(mapOf(MicSource.VOICE_RECOGNITION to microphone), whileOpening = { live.stop() }))

        val outcome = start(live, speech, onStoppedWhileOpening = { stoppedWhileOpening.set(true) }).await()

        assertTrue(outcome.isSuccess)
        assertTrue(stoppedWhileOpening.get())
        assertEquals(listOf("open VOICE_RECOGNITION", "close"), events.toList())
        assertEquals(1, microphone.closes.get())
        assertFalse(speech.loading.isCompleted)
        assertTrue(results.isEmpty())
        assertEquals(0L, live.startedAtMs)
    }

    @Test
    fun `a capture cancelled while its microphone opens still finishes the audio file it began`() = test {
        val files = RecordingAudioFiles(File(folder.root, "recording.wav"), File(folder.root, "recording.idx"))
        val microphone = FakeMicrophone(recorded)
        val opening = CompletableDeferred<Unit>()
        val letOpen = CountDownLatch(1)
        val device = FakeDevice(mapOf(MicSource.VOICE_RECOGNITION to microphone), whileOpening = {
            opening.complete(Unit)
            letOpen.await(WAIT_MS, TimeUnit.MILLISECONDS)
        })
        val live = capture(device)
        val run = start(live, FakeSpeech(), audio = files)

        // The recorder closes while the microphone opens, and the capture loop makes its writer
        // only after the run has begun to end.
        opening.await()
        run.cancel()
        summaryLogged.await()
        letOpen.countDown()
        run.join()

        // The next capture of the recording writes at once: no writer of it is left open, which it
        // would wait 5 s for and then give up on, dropping its audio.
        val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val next = CaptureAudioWriter(files, startedAt = 0L, warn = { message, _ -> warnings += message })
        next.write(read(0))
        next.close()
        assertNotNull("the next writer waited", withTimeoutOrNull(2_000L) { next.join() })
        assertTrue("$warnings", warnings.isEmpty())
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `a microphone that stops delivering ends the capture as a lost microphone, after its last words are out`() = test {
        val microphone = FakeMicrophone(recorded, thenFails = true)
        val speech = FakeSpeech().also { it.letLoad() }
        // Each look at the clock is a second later, so the failed reads add up to the 5 s that end the capture.
        val now = AtomicLong()
        val live = capture(microphoneOnly(microphone), monotonicClock = { now.addAndGet(1_000) })

        val error = start(live, speech).await().exceptionOrNull()

        assertTrue("failed with $error", error is MicrophoneLostException)
        assertEquals(recordedSamples, speech.decoded.single().asSamples())
        assertEquals(1, results.count { it.isComplete })
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `the summary line says what the phone did with the input, once the phone has said it`() = test {
        val microphone = FakeMicrophone(
            recorded,
            reports = listOf(
                InputReport.UNKNOWN,
                InputReport(effects = listOf("NS", "AEC"), microphones = null),
                InputReport(effects = null, microphones = 2)
            )
        )
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone, MicSource.VOICE_COMMUNICATION))
        val run = start(live, speech, micSource = MicSource.VOICE_COMMUNICATION)

        microphone.allQueued.await()
        live.stop()
        assertTrue(run.await().isSuccess)

        val summary = summaries.single()
        assertTrue(summary, summary.contains(" mic=VOICE_COMMUNICATION effects=AEC+NS mics=2 "))
        assertTrue(summary, summary.endsWith(" ended=done"))
        // Not asked again once it had said both.
        assertEquals(3, microphone.asked)
    }

    @Test
    fun `a phone that never says what it does with the input is asked at the first 20 reads only`() = test {
        val microphone = FakeMicrophone(recorded, reports = listOf(InputReport.UNKNOWN))
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        microphone.allQueued.await()
        live.stop()
        assertTrue(run.await().isSuccess)

        assertEquals(20, microphone.asked)
        assertTrue(summaries.single(), summaries.single().contains(" mic=VOICE_RECOGNITION effects=? mics=? "))
    }

    @Test
    fun `the input for calls that doesn't open falls back to the general one, which the summary line names`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone, MicSource.MIC))
        val run = start(live, speech, micSource = MicSource.VOICE_COMMUNICATION)

        microphone.allQueued.await()
        live.stop()
        assertTrue(run.await().isSuccess)

        assertEquals(listOf("open VOICE_COMMUNICATION", "open MIC"), events.take(2))
        assertTrue(summaries.single(), summaries.single().contains(" mic=MIC(fallback) effects=none mics=1 "))
    }

    @Test
    fun `the microphone loop records at the audio priority and gives its thread the old one back as it ends`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)

        microphone.allQueued.await()
        assertEquals(listOf("raise"), priority.calls.map { it.first })
        live.stop()
        assertTrue(run.await().isSuccess)

        assertEquals(listOf("raise", "restore $POOL_PRIORITY"), priority.calls.map { it.first })
        // The loop never suspends, so it ends on the thread it raised, which goes back to the pool.
        val (raised, restored) = priority.calls.map { it.second }
        assertSame(raised, restored)
    }

    @Test
    fun `the library's version is read off the thread the capture runs on, as the capture ends`() = test {
        val caller = Thread.currentThread()
        val readOn = CompletableDeferred<Thread>()
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech().also { it.letLoad() }
        val live = capture(microphoneOnly(microphone), library = {
            readOn.complete(Thread.currentThread())
            "1.13.8"
        })
        // On one thread, as the recorder collects the capture's flow on the main thread.
        val run = launch {
            live.run(MicSource.VOICE_RECOGNITION, VAD, speech, audio = null, metrics = null, onStoppedWhileOpening = {}) {
                results += it
            }
        }

        microphone.allQueued.await()
        assertFalse(readOn.isCompleted)
        live.stop()
        run.join()

        assertNotEquals(caller, readOn.await())
        assertTrue(summaries.single(), summaries.single().contains(" sherpa-onnx=1.13.8 "))
    }

    @Test
    fun `a debug feed is listening as it starts, and is transcribed once the speech model is in`() = test {
        val feed = ShortArray(3 * READ) { 7 }
        val speech = FakeSpeech()
        val live = capture(FakeDevice(inputs = emptyMap(), feed = feed))
        val run = start(live, speech)

        assertEquals(CaptureState.LISTENING, speech.loading.await())
        speech.letLoad()
        speech.recognizing.await()
        // The feed plays at real-time pace; it has played through, and then some silence, by now.
        delay(700)
        live.stop()

        assertTrue(run.await().isSuccess)
        val heard = speech.decoded.single().asSamples()
        assertEquals(List(3 * READ) { 7 }, heard.take(3 * READ))
        assertTrue(heard.drop(3 * READ).all { it == 0 })
        assertFalse(events.any { it.startsWith("open") })
        assertTrue(summaries.single(), summaries.single().contains(" mic=feed effects=none mics=0 "))
        // Its delay() resumes on other threads, so it runs at the pool's priority.
        assertTrue(priority.calls.isEmpty())
        assertEquals(listOf("Capture start: source=feed channels=1 device=none fold=unknown asked=auto gain=off"), lines.toList())
        assertTrue(summaries.single(), summaries.single().contains(" device=none fold=unknown gain=off "))
    }

    @Test
    fun `a capture says where its audio comes from as it starts, each change as it comes, and all of it in its summary`() = test {
        val microphone = FakeMicrophone(recorded)
        val routed = Routed(microphone, routed = null)
        val posture = FakePosture("closed")
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(routed), posture = posture)
        val run = start(live, speech, switches = MicSwitches(MicSwitches.Device.BACK, MicSwitches.Gain.Fixed(12.0)))

        speech.loading.await()
        // Android routes the input once it records, and the owner opens the phone.
        routed.reroute("builtin_mic/back")
        posture.refold("open")
        speech.letLoad()
        microphone.allQueued.await()
        live.stop()

        assertTrue(run.await().isSuccess)
        assertEquals("open VOICE_RECOGNITION at back", events.first())
        assertEquals(
            listOf(
                "Capture start: source=VOICE_RECOGNITION channels=1 device=pending fold=closed asked=back gain=+12.0dB",
                "Capture route: device=builtin_mic/back (was pending)",
                "Capture fold: open (was closed)"
            ),
            lines.toList()
        )
        val summary = summaries.single()
        assertTrue(summary, summary.contains(" mics=1 device=builtin_mic/back fold=closed>open gain=+12.0dB speechLevel="))
        assertTrue(posture.unwatched.get())
    }

    @Test
    fun `a device known as the capture starts is in its start line, and a route to the same one is no change`() = test {
        val microphone = FakeMicrophone(recorded)
        val routed = Routed(microphone, routed = "builtin_mic/bottom")
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(routed), posture = FakePosture("open"))
        val run = start(live, speech)

        speech.loading.await()
        routed.reroute("builtin_mic/bottom")
        speech.letLoad()
        microphone.allQueued.await()
        live.stop()

        assertTrue(run.await().isSuccess)
        assertEquals(
            listOf("Capture start: source=VOICE_RECOGNITION channels=1 device=builtin_mic/bottom fold=open asked=auto gain=off"),
            lines.toList()
        )
        assertTrue(summaries.single(), summaries.single().contains(" device=builtin_mic/bottom fold=open gain=off "))
    }

    @Test
    fun `the gain is on what the recognizer hears and never on the kept audio`() = test {
        val files = RecordingAudioFiles(File(folder.root, "recording.wav"), File(folder.root, "recording.idx"))
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech()
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech, audio = files, switches = MicSwitches(gain = MicSwitches.Gain.Fixed(12.0)))

        microphone.allQueued.await()
        speech.letLoad()
        speech.recognizing.await()
        live.stop()

        assertTrue(run.await().isSuccess)
        // 12 dB: each sample about 3.98 times as large.
        val factor = 10.0.pow(12.0 / 20)
        val heard = speech.decoded.single().asSamples()
        assertEquals(recordedSamples.size, heard.size)
        assertTrue(recordedSamples.zip(heard).all { (raw, gained) -> abs(raw * factor - gained) <= 1.0 })
        val kept = files.wav.readBytes()
        assertEquals(recordedSamples, Wav.toFloats(kept.copyOfRange(Wav.HEADER_BYTES, kept.size), READS * READ).asSamples())
        assertTrue(summaries.single(), summaries.single().contains(" gain=+12.0dB "))
    }

    /** Waits until [condition] holds, at most [WAIT_MS]. */
    private suspend fun until(condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `a switch of the language changes the speech model between utterances, and the capture goes on untouched`() = test {
        val files = RecordingAudioFiles(File(folder.root, "recording.wav"), File(folder.root, "recording.idx"))
        val microphone = StreamingMicrophone(signalAfter = 10, paceMs = 5)
        val pauses = Pauses()
        val speech = FakeSpeech(detector = { pauses }).also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech, audio = files)
        microphone.gave.await()
        until { speech.decodedBy.size >= 2 }

        speech.wanted = SpeechModelId.INDICCONFORMER_ML
        until { speech.decodedBy.count { it == SpeechModelId.INDICCONFORMER_ML } >= 2 }
        // Listening while the model changed, as at the start, and transcribing again once it was in.
        assertEquals(CaptureState.LISTENING, speech.stateWhileSwitching)
        assertEquals(CaptureState.TRANSCRIBING, states.flow.value)
        live.stop()

        assertTrue(run.await().isSuccess)
        // One microphone, one load, one change of the model, and the microphone closed by Stop only.
        assertEquals(
            listOf("open VOICE_RECOGNITION", "load", "loaded", "switch to indicconformer-ml-int8", "close"),
            events.toList()
        )
        assertEquals(1, microphone.closes.get())
        // Parakeet decoded what was said before the change, the Malayalam model what was said after it.
        val firstMalayalam = speech.decodedBy.indexOf(SpeechModelId.INDICCONFORMER_ML)
        assertTrue("${speech.decodedBy}", speech.decodedBy.take(firstMalayalam).all { it == SpeechModelId.PARAKEET_V3 })
        assertTrue("${speech.decodedBy}", speech.decodedBy.drop(firstMalayalam).all { it == SpeechModelId.INDICCONFORMER_ML })
        // Nothing dropped: every utterance given out became a line (and the one open at Stop one more), and
        // the voice detection heard all of the audio, also what was recorded while the model changed, but
        // the end of the last read short of a window.
        assertTrue(results.count { it.isComplete } - pauses.givenOut in 0..1)
        val recordedSamples = microphone.reads.toLong() * READ
        assertTrue("fed ${pauses.fed} of $recordedSamples", pauses.fed in (recordedSamples - 512 + 1)..recordedSamples)
        // The audio file has every read, with no gap.
        assertEquals(recordedSamples * 2, Wav.dataBytes(files.wav))
        assertTrue(summaries.single(), summaries.single().contains(" model=parakeet-tdt-0.6b-v3-int8>indicconformer-ml-int8 "))
        val switched = lines.single { it.startsWith("Live speech model switched") }
        assertTrue(switched, switched.startsWith("Live speech model switched to indicconformer-ml-int8 (was parakeet-tdt-0.6b-v3-int8) in "))
    }

    @Test
    fun `a model that doesn't load after a switch leaves the capture on the one it had, which goes on transcribing`() = test {
        val microphone = StreamingMicrophone(signalAfter = 10, paceMs = 5)
        val pauses = Pauses()
        val speech = FakeSpeech(detector = { pauses }, refuses = true).also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)
        microphone.gave.await()
        until { speech.decodedBy.isNotEmpty() }

        speech.wanted = SpeechModelId.INDICCONFORMER_ML
        speech.switching.await()
        val before = speech.decodedBy.size
        until { speech.decodedBy.size >= before + 2 }
        assertEquals(CaptureState.TRANSCRIBING, states.flow.value)
        live.stop()

        assertTrue(run.await().isSuccess)
        // Tried once, not at every read after it.
        assertEquals(listOf("open VOICE_RECOGNITION", "load", "loaded", "switch to indicconformer-ml-int8", "close"), events.toList())
        assertTrue("${speech.decodedBy}", speech.decodedBy.all { it == SpeechModelId.PARAKEET_V3 })
        assertTrue(results.count { it.isComplete } - pauses.givenOut in 0..1)
        assertTrue(summaries.single(), summaries.single().contains(" model=parakeet-tdt-0.6b-v3-int8 "))
    }

    @Test
    fun `when neither model loads after a switch, the capture ends as a failed load does, and the audio keeps what was recorded`() = test {
        val files = RecordingAudioFiles(File(folder.root, "recording.wav"), File(folder.root, "recording.idx"))
        val microphone = StreamingMicrophone(signalAfter = 10, paceMs = 5)
        val speech = FakeSpeech(detector = { Pauses() }, switchFailure = IllegalStateException("Out of memory")).also { it.letLoad() }
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech, audio = files)
        microphone.gave.await()
        until { speech.decodedBy.isNotEmpty() }

        speech.wanted = SpeechModelId.INDICCONFORMER_ML
        val outcome = withTimeoutOrNull(ENDS_BY_ITSELF_MS) { run.await() }
        live.stop()

        assertNotNull("the microphone recorded on after no model loaded", outcome)
        val error = outcome?.exceptionOrNull()
        assertTrue("failed with $error", error is SpeechModelLoadException)
        assertEquals(1, microphone.closes.get())
        assertTrue(evidence.mayHaveHeardSpeech)
        assertEquals(microphone.reads.toLong() * READ * 2, Wav.dataBytes(files.wav))
        assertTrue(summaries.single(), summaries.single().endsWith(" ended=failed"))
    }

    @Test
    fun `a switch while the model loads changes it before the first utterance`() = test {
        val microphone = FakeMicrophone(recorded)
        val speech = FakeSpeech(detector = { Pauses() }, wantedOnceLoaded = SpeechModelId.INDICCONFORMER_ML)
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)
        microphone.allQueued.await()
        speech.letLoad()
        until { speech.decodedBy.size >= 2 }
        live.stop()

        assertTrue(run.await().isSuccess)
        assertEquals(listOf("open VOICE_RECOGNITION", "load", "loaded", "switch to indicconformer-ml-int8", "close"), events.toList())
        assertTrue("${speech.decodedBy}", speech.decodedBy.all { it == SpeechModelId.INDICCONFORMER_ML })
    }

    @Test
    fun `no switch comes after Stop, the last words go to the model they began with`() = test {
        val microphone = FakeMicrophone(recorded)
        // Switched as the model is in, which is after Stop here.
        val speech = FakeSpeech(detector = { Pauses() }, wantedOnceLoaded = SpeechModelId.INDICCONFORMER_ML)
        val live = capture(microphoneOnly(microphone))
        val run = start(live, speech)
        // All of it is recorded while the model loads, and the recording is stopped before it is in.
        microphone.allQueued.await()
        live.stop()
        speech.letLoad()

        assertTrue(run.await().isSuccess)
        assertTrue("$events", events.none { it.startsWith("switch") })
        assertTrue(speech.decodedBy.isNotEmpty())
        assertTrue("${speech.decodedBy}", speech.decodedBy.all { it == SpeechModelId.PARAKEET_V3 })
    }
}
