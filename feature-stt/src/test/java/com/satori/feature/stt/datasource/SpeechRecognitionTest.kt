package com.satori.feature.stt.datasource

import com.satori.data.datasource.RecognitionResult
import com.satori.domain.model.AudioSpan
import com.k2fsa.sherpa.onnx.SpeechSegment
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechRecognitionTest {

    private companion object {
        const val READ = 1_600 // 100 ms, as the microphone and a file are read
        const val SILENCE = 8_000 // the 0.5 s of silence that ends an utterance
        const val CUT_SILENCE = 1_600 // the 0.1 s dip that ends one cut at the maximum length
    }

    /**
     * Hears speech in [utterances] (sample ranges, as fed) and gives each one out once [SILENCE]
     * more has been fed, as Silero does, or [CUT_SILENCE] more for one as long as
     * [maxSpeechSamples], which sherpa-onnx cuts at the first brief dip; counts the samples fed,
     * which a forced split's reset leaves running, as sherpa-onnx's buffer does.
     */
    private class FakeDetector(
        private val utterances: List<LongRange>,
        override val maxSpeechSamples: Int = Int.MAX_VALUE,
        private val failingWindows: Int = 0
    ) : VoiceDetector {
        override val minSilenceSamples = SILENCE
        private var fed = 0L
        private var next = 0
        private var failures = 0
        private val out = ArrayDeque<SpeechSegment>()

        private fun heardAfter(utterance: LongRange) =
            if (utterance.last + 1 - utterance.first >= maxSpeechSamples) CUT_SILENCE else SILENCE

        override fun acceptWaveform(samples: FloatArray) {
            if (failures < failingWindows) {
                failures++
                error("VAD failed")
            }
            fed += samples.size
            val utterance = utterances.getOrNull(next) ?: return
            if (fed >= utterance.last + 1 + heardAfter(utterance)) {
                out += SpeechSegment(utterance.first.toInt(), FloatArray((utterance.last + 1 - utterance.first).toInt()))
                next++
            }
        }

        override fun isSpeechDetected() = utterances.any { fed > it.first && fed <= it.last + 1 + heardAfter(it) }
        override fun empty() = out.isEmpty()
        override fun front() = out.first()
        override fun pop() {
            out.removeFirst()
        }

        override fun reset() = Unit
    }

    /** Runs [samples] samples of audio through, reads of [READ] at a time, and returns what came out. */
    private fun recognize(
        samples: Int,
        utterances: List<LongRange>,
        strict: Boolean = true,
        maxSpeech: Int = Int.MAX_VALUE,
        decodeFinal: (suspend (FloatArray) -> String?)? = null,
        evidence: SpeechEvidence? = null,
        detector: VoiceDetector = FakeDetector(utterances, maxSpeech),
        decode: suspend (FloatArray) -> String? = { "${it.size} samples" }
    ): List<RecognitionResult> = runBlocking {
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        var sent = 0
        while (sent < samples) {
            val size = minOf(READ, samples - sent)
            channel.send(FloatArray(size))
            sent += size
        }
        channel.close()
        val results = mutableListOf<RecognitionResult>()
        SpeechRecognition(
            detector, decode, partials = false, strict = strict, decodeFinal = decodeFinal ?: decode, evidence = evidence
        ).run(channel) { results += it }
        results
    }

    /** What [evidence] knows once a recording of [samples] samples has been recognized to its end. */
    private fun heardAfter(
        samples: Int,
        utterances: List<LongRange>,
        evidence: SpeechEvidence = SpeechEvidence(),
        detector: VoiceDetector = FakeDetector(utterances),
        decode: suspend (FloatArray) -> String?
    ): Boolean {
        recognize(samples, utterances, strict = false, evidence = evidence, detector = detector, decode = decode)
        return evidence.mayHaveHeardSpeech
    }

    @Test
    fun `an utterance is placed where it was said, to within a read`() {
        // Said from 1 s to 3 s.
        val results = recognize(samples = 6 * 16_000, utterances = listOf(16_000L until 48_000L))

        val line = results.single()
        assertTrue(line.isComplete)
        assertEquals("32000 samples", line.text)
        val span = line.span!!
        assertTrue("starts at ${span.startMs}", span.startMs in 1_000L..1_100L)
        assertEquals(2_000L, span.endMs - span.startMs)
    }

    @Test
    fun `utterances come out in order, never placed before the one before`() {
        val results = recognize(
            samples = 10 * 16_000,
            utterances = listOf(16_000L until 40_000L, 50_000L until 90_000L)
        )

        assertEquals(listOf("24000 samples", "40000 samples"), results.map { it.text })
        val (first, second) = results.map { it.span!! }
        assertTrue(second.startMs >= first.endMs)
        assertTrue("second starts at ${second.startMs}", second.startMs in 3_125L..3_225L)
    }

    @Test
    fun `speech that goes on for 30 s is cut there, and what is said after it ends the file`() {
        // Speech from the first window to beyond the end of the audio, 32.5 s.
        val results = recognize(samples = 520_000, utterances = listOf(0L until 600_000L))

        assertEquals(2, results.size)
        val (split, rest) = results.map { it.span!! }
        assertEquals(AudioSpan(startMs = 80, endMs = 30_080), split)
        assertEquals("480000 samples", results[0].text)
        // The rest starts where the split ended, though its audio reaches back 0.4 s before it,
        // and ends with the audio.
        assertEquals(AudioSpan(startMs = 30_080, endMs = 32_500), rest)
    }

    @Test
    fun `every complete utterance is decoded as one, the forced split and the end of the audio too`() {
        val finals = mutableListOf<Int>()
        val decodeFinal: suspend (FloatArray) -> String? = { audio -> finals += audio.size; "final" }

        val vadSegments = recognize(
            samples = 10 * 16_000,
            utterances = listOf(16_000L until 40_000L, 50_000L until 90_000L),
            decodeFinal = decodeFinal
        ) { error("a complete utterance was decoded as a partial one") }
        assertEquals(listOf("final", "final"), vadSegments.map { it.text })
        assertEquals(listOf(24_000, 40_000), finals)

        finals.clear()
        val splitAndEnd = recognize(samples = 520_000, utterances = listOf(0L until 600_000L), decodeFinal = decodeFinal) {
            error("a complete utterance was decoded as a partial one")
        }
        assertEquals(listOf("final", "final"), splitAndEnd.map { it.text })
        assertEquals(480_000, finals.first())
    }

    @Test
    fun `a file gives out complete utterances only`() {
        val results = recognize(samples = 20 * 16_000, utterances = listOf(16_000L until 280_000L))

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.isComplete && it.span != null })
    }

    @Test
    fun `a failing decode ends a strict recognition with its error`() {
        try {
            recognize(samples = 6 * 16_000, utterances = listOf(16_000L until 48_000L), strict = true) {
                throw IllegalStateException("model failed")
            }
            fail("the failure was swallowed")
        } catch (e: IllegalStateException) {
            // Coroutines may hand over a copy with the stack trace of the place it was rethrown.
            assertEquals("model failed", e.message)
        }
    }

    @Test
    fun `a recording loses only the utterance whose decode failed`() {
        var calls = 0
        val results = recognize(
            samples = 10 * 16_000,
            utterances = listOf(16_000L until 40_000L, 50_000L until 90_000L),
            strict = false
        ) { samples -> if (++calls == 1) error("model failed") else "${samples.size} samples" }

        assertEquals(listOf("40000 samples"), results.map { it.text })
    }

    @Test
    fun `a recording notes words it gave out, and a line without a letter or digit is none`() {
        val oneUtterance = listOf(16_000L until 48_000L)

        assertFalse(heardAfter(6 * 16_000, oneUtterance) { "." })
        assertFalse(heardAfter(6 * 16_000, oneUtterance) { null })
        assertTrue(heardAfter(6 * 16_000, oneUtterance) { "10" })
        // Said without a pause until the end: the flush gives it out, and with it ends the utterance.
        assertTrue(heardAfter(3 * 16_000, listOf(16_000L until 200_000L)) { "Call Anna" })
        assertFalse(heardAfter(3 * 16_000, listOf(16_000L until 200_000L)) { "." })
        assertFalse(heardAfter(60 * 16_000, emptyList()) { "never asked" })
    }

    @Test
    fun `a recording notes the words of its complete utterances, decoded apart from the partial ones`() {
        // A recording decodes its complete utterances with decodeFinal, which times them for the
        // field log; the words it gives out are what counts, not what the other decode would say.
        fun heardWith(final: String, partial: String): Boolean {
            val evidence = SpeechEvidence()
            recognize(
                samples = 6 * 16_000,
                utterances = listOf(16_000L until 48_000L),
                strict = false,
                decodeFinal = { final },
                evidence = evidence
            ) { partial }
            return evidence.mayHaveHeardSpeech
        }

        assertTrue(heardWith(final = "Buy milk", partial = "."))
        assertFalse(heardWith(final = ".", partial = "Buy milk"))
    }

    @Test
    fun `a recording notes audio that a failing step left untranscribed`() {
        // A decode that throws, for an utterance the voice detection ended and for the last one.
        assertTrue(heardAfter(6 * 16_000, listOf(16_000L until 48_000L)) { error("model failed") })
        assertTrue(heardAfter(3 * 16_000, listOf(16_000L until 200_000L)) { error("model failed") })
        // Only the 30 s cut of speech that goes on fails; the rest after it is noise.
        assertTrue(heardAfter(32 * 16_000, listOf(0L until 600_000L)) { if (it.size == 480_000) error("model failed") else "." })
        // The voice detection fails on a window and goes on.
        val detector = FakeDetector(listOf(16_000L until 48_000L), failingWindows = 1)
        assertTrue(heardAfter(6 * 16_000, listOf(16_000L until 48_000L), detector = detector) { "." })
    }

    @Test
    fun `a recording cut off within an utterance notes it, and one cut off in a pause does not`() = runBlocking {
        suspend fun cutOffAfter(seconds: Int, utterance: LongRange): Boolean {
            val evidence = SpeechEvidence()
            val channel = Channel<FloatArray>(Channel.UNLIMITED)
            repeat(seconds * 10) { channel.send(FloatArray(READ)) }
            // Runs until it waits for audio that never comes, as a capture whose recorder closes.
            val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
                SpeechRecognition(FakeDetector(listOf(utterance)), { "." }, partials = false, strict = false, evidence = evidence)
                    .run(channel) { }
            }
            recognition.cancelAndJoin()
            return evidence.mayHaveHeardSpeech
        }

        // "Call Anna" is being said: no pause has ended it yet.
        assertTrue(cutOffAfter(seconds = 1, utterance = 8_000L until 64_000L))
        // Its line, a lone noise, was out before the cut.
        assertFalse(cutOffAfter(seconds = 2, utterance = 8_000L until 16_000L))
    }

    @Test
    fun `a recording cut off before it heard all that was recorded notes the rest, beyond the read it keeps behind`() = runBlocking {
        // Nothing is said: only what the voice detection hasn't been over can hold speech.
        suspend fun cutOffWith(recorded: Int, heard: Int): Boolean {
            val evidence = SpeechEvidence()
            repeat(recorded) { evidence.audioRecorded(READ) }
            val channel = Channel<FloatArray>(Channel.UNLIMITED)
            repeat(heard) { channel.send(FloatArray(READ)) }
            // Runs until it waits for audio still to come, as a capture whose recorder closes.
            val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
                SpeechRecognition(FakeDetector(emptyList()), { "." }, partials = false, strict = false, evidence = evidence)
                    .run(channel) { }
            }
            recognition.cancelAndJoin()
            return evidence.mayHaveHeardSpeech
        }

        // The speech model loaded for 3 s, and the recognition had caught up on 1 s of it.
        assertTrue(cutOffWith(recorded = 30, heard = 10))
        assertTrue(cutOffWith(recorded = 32, heard = 30))
        // It had heard all of it, or all but the read it takes within milliseconds.
        assertFalse(cutOffWith(recorded = 30, heard = 30))
        assertFalse(cutOffWith(recorded = 31, heard = 30))
    }

    @Test
    fun `an utterance whose line comes out after the cut stays open, as the line reaches no one`() = runBlocking {
        val evidence = SpeechEvidence()
        val decoding = CompletableDeferred<Unit>()
        val decoded = CompletableDeferred<Unit>()
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        // 2 s: a noise from 0.5 s to 1 s, and the pause that ends it.
        repeat(20) { channel.send(FloatArray(READ)) }
        val recognition = launch(Dispatchers.Default) {
            SpeechRecognition(
                FakeDetector(listOf(8_000L until 16_000L)),
                // A decode in the native library runs to its end, whatever is cancelled.
                { withContext(NonCancellable) { decoding.complete(Unit); decoded.await(); "." } },
                partials = false,
                strict = false,
                evidence = evidence
            ).run(channel) { }
        }
        decoding.await()
        // The recorder closes while the line is decoded.
        recognition.cancel()
        decoded.complete(Unit)
        recognition.join()

        assertTrue(evidence.mayHaveHeardSpeech)
    }

    @Test
    fun `a partial result with words is noted though the final line has none`() = runBlocking {
        val evidence = SpeechEvidence()
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        val results = mutableListOf<RecognitionResult>()
        val recognition = launch {
            // The partial decodes the first 3.1 s, the flush all 5.1 s.
            SpeechRecognition(
                FakeDetector(listOf(0L until 200_000L)),
                { audio -> if (audio.size < 60_000) "Call" else "." },
                partials = true,
                strict = false,
                evidence = evidence
            ).run(channel) { synchronized(results) { results += it } }
        }
        repeat(30) { channel.send(FloatArray(READ)) }
        // Partials come at most every 200 ms of wall time.
        delay(300)
        channel.send(FloatArray(READ))
        repeat(20) { channel.send(FloatArray(READ)) }
        channel.close()
        recognition.join()

        // The final line holds no word, so it never comes out; the partial result did.
        assertEquals(listOf("Call" to false), results.map { it.text to it.isComplete })
        assertTrue(evidence.mayHaveHeardSpeech)
    }

    @Test
    fun `a noise decoded to no letter or digit, as the start buzz is, gives out no line, live or for a file`() {
        // 0.3 s from 0.2 s in, as the start buzz is heard. Made-up texts of no word: a lone ".", two
        // signs that no letter carries (the vowel sign aa and the anusvara), a zero width joiner,
        // and spaces.
        val buzz = listOf(3_200L until 8_000L)
        for (noWord in listOf(".", "\u0D3E\u0D02", "\u200D", "  ")) {
            for (strict in listOf(true, false)) {
                val results = recognize(samples = 3 * 16_000, utterances = buzz, strict = strict) { noWord }
                assertTrue("\"$noWord\", strict=$strict: $results", results.isEmpty())
            }
        }
        // A letter alone is a word: the Malayalam letter ka; so is a digit.
        for (word in listOf("\u0D15", "7")) {
            assertEquals(listOf(word), recognize(samples = 3 * 16_000, utterances = buzz) { word }.map { it.text })
        }
    }

    @Test
    fun `a partial result without a letter or digit is not given out`() = runBlocking {
        // Said from the start to beyond the end of the 5 s of audio: partial results as it goes on,
        // the flush at the end.
        val detector = Counting(FakeDetector(listOf(0L until 200_000L)))
        var partials = 0
        val results = mutableListOf<RecognitionResult>()

        SpeechRecognition(
            detector = detector,
            decode = { partials++; "\u0D3E\u0D02" },
            partials = true,
            strict = false,
            decodeFinal = { "Call Anna" },
            // The clock runs with the audio, as while a recording keeps up with its microphone.
            clock = { detector.fed * 1_000 / 16_000 },
            partialContext = Dispatchers.Unconfined
        ).run(audioOf(seconds = 5)) { results += it }

        assertTrue("partial results decoded: $partials", partials > 0)
        assertEquals(listOf("Call Anna" to true), results.map { it.text to it.isComplete })
    }

    @Test
    fun `nothing said, nothing given out`() {
        assertTrue(recognize(samples = 60 * 16_000, utterances = emptyList()).isEmpty())
    }

    @Test
    fun `an utterance under a tenth of a second never reaches the speech model, one of a tenth does`() {
        val decoded = mutableListOf<Int>()

        // 62.5 ms said at 1 s, and 0.1 s at 2 s.
        val results = recognize(
            samples = 3 * 16_000,
            utterances = listOf(16_000L until 17_000L, 32_000L until 33_600L),
            decode = { decoded += it.size; "${it.size} samples" }
        )

        assertEquals(listOf(SpeechRecognition.MIN_DECODE_SAMPLES), decoded)
        assertEquals(listOf("1600 samples"), results.map { it.text })
    }

    @Test
    fun `audio that ends under a tenth of a second into an utterance isn't decoded either`() {
        val decoded = mutableListOf<Int>()

        // The audio ends 62.5 ms in, speech heard from its first window on: the flush would be 32 ms.
        val results = recognize(samples = 1_000, utterances = listOf(0L until 100_000L), decode = { decoded += it.size; "words" })

        assertTrue(decoded.toString(), decoded.isEmpty())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `an utterance the detector cut at its maximum length is placed where it was said`() {
        // Said from 1 s for 20 s, the maximum: given out 0.1 s after it, not the 0.5 s of a pause.
        val results = recognize(samples = 24 * 16_000, utterances = listOf(16_000L until 336_000L), maxSpeech = 320_000)

        val span = results.single().span!!
        assertTrue("starts at ${span.startMs}", span.startMs in 1_000L..1_100L)
        assertEquals(20_000L, span.endMs - span.startMs)
    }

    @Test
    fun `a segment came out its pause after it ended, or 0_1 s after if it was cut at the maximum length`() {
        val detector = FakeDetector(emptyList(), maxSpeechSamples = 320_000)

        // Ended at a pause: it and the pause after it are at most one window over the maximum.
        assertEquals(SILENCE, detector.silenceAfter(320_000 + 512 - SILENCE))
        assertEquals(SILENCE, detector.silenceAfter(16_000))
        // Cut: it is at most the 0.1 s dip short of the maximum.
        assertEquals(CUT_SILENCE, detector.silenceAfter(320_000 - CUT_SILENCE + 1))
        assertEquals(CUT_SILENCE, detector.silenceAfter(320_000))
    }

    @Test
    fun `a VAD segment ends the silence before what the VAD has read`() {
        assertEquals(17_344L to 49_344L, SpeechRecognition.segmentSpan(readTo = 57_344, size = 32_000, trailingSilence = SILENCE))
        // One at the very start of the audio can't begin before it.
        assertEquals(0L to 4_000L, SpeechRecognition.segmentSpan(readTo = 6_000, size = 4_000, trailingSilence = SILENCE))
    }

    /** [detector], counting the samples it is fed, which a test's change of the speech model is due by. */
    private class Counting(private val detector: VoiceDetector) : VoiceDetector by detector {
        @Volatile var fed = 0L
            private set

        override fun acceptWaveform(samples: FloatArray) {
            fed += samples.size
            detector.acceptWaveform(samples)
        }
    }

    /**
     * A change from the "old" speech model to the "new" one, due once [dueNow] says so; it notes
     * where in the audio it came, and [events] when.
     */
    private class ToNewModel(
        private val dueNow: () -> Boolean,
        private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    ) : ModelSwitch {
        @Volatile var model = "old"
            private set
        val at: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun due() = model == "old" && dueNow()

        override suspend fun switch(atMs: Long) {
            events += "switch"
            at += atMs
            model = "new"
        }
    }

    /** [seconds] of audio, read 100 ms at a time, all of it there at once. */
    private suspend fun audioOf(seconds: Int): Channel<FloatArray> = Channel<FloatArray>(Channel.UNLIMITED).also { channel ->
        repeat(seconds * 16_000 / READ) { channel.send(FloatArray(READ)) }
        channel.close()
    }

    @Test
    fun `the speech model changes between two utterances, never within one`() = runBlocking {
        // Said from 1 s to 3 s and from 5 s to 7 s; the change is due from 2 s, within the first.
        val detector = Counting(FakeDetector(listOf(16_000L until 48_000L, 80_000L until 112_000L)))
        val switch = ToNewModel(dueNow = { detector.fed >= 32_000 })
        val results = mutableListOf<RecognitionResult>()

        SpeechRecognition(
            detector, decode = { "${switch.model}: ${it.size} samples" }, partials = false, strict = true, modelSwitch = switch
        ).run(audioOf(seconds = 10)) { results += it }

        // The first utterance is decoded by the model it began with, the second by the new one.
        assertEquals(listOf("old: 32000 samples", "new: 32000 samples"), results.map { it.text })
        // Once: as the read after the one that gave the first out begins, 3.5 s in (its end and its
        // pause) and the rest of that read.
        assertEquals(listOf(3_600L), switch.at.toList())
    }

    @Test
    fun `a change of the speech model due while nobody speaks comes at once`() = runBlocking {
        val detector = Counting(FakeDetector(listOf(48_000L until 80_000L)))
        val switch = ToNewModel(dueNow = { true })
        val results = mutableListOf<RecognitionResult>()

        SpeechRecognition(
            detector, decode = { "${switch.model}: ${it.size} samples" }, partials = false, strict = true, modelSwitch = switch
        ).run(audioOf(seconds = 6)) { results += it }

        assertEquals(listOf(0L), switch.at.toList())
        assertEquals(listOf("new: 32000 samples"), results.map { it.text })
    }

    @Test
    fun `a partial result still being decoded ends before the speech model changes`() = runBlocking {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        // Said from 0.5 s to 3 s; its partial results take 0.3 s each, and the change is due from its start.
        val detector = Counting(FakeDetector(listOf(8_000L until 48_000L)))
        val switch = ToNewModel(dueNow = { detector.fed >= 8_000 }, events = events)

        SpeechRecognition(
            detector = detector,
            decode = {
                events += "partial begins"
                delay(300)
                events += "partial ends"
                "so far"
            },
            partials = true,
            strict = false,
            decodeFinal = { "all of it" },
            // The clock runs with the audio, as while a recording keeps up with its microphone.
            clock = { detector.fed * 1_000 / 16_000 },
            partialContext = Dispatchers.Default,
            modelSwitch = switch
        ).run(audioOf(seconds = 5)) {}

        val switchAt = events.indexOf("switch")
        assertTrue("$events", switchAt > 0)
        val before = events.subList(0, switchAt)
        assertTrue("$events", "partial begins" in before)
        assertEquals("$events", before.count { it == "partial begins" }, before.count { it == "partial ends" })
    }

    @Test
    fun `no change of the speech model comes after the audio ends, nor without one asked for`() = runBlocking {
        val detector = Counting(FakeDetector(listOf(16_000L until 48_000L)))
        val switch = ToNewModel(dueNow = { false })
        val results = mutableListOf<RecognitionResult>()

        SpeechRecognition(
            detector, decode = { "${switch.model}: ${it.size} samples" }, partials = false, strict = true, modelSwitch = switch
        ).run(audioOf(seconds = 4)) { results += it }

        assertTrue(switch.at.isEmpty())
        assertEquals(listOf("old: 32000 samples"), results.map { it.text })
    }
}
