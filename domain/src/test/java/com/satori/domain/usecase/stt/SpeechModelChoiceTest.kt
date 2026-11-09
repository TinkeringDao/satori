package com.satori.domain.usecase.stt

import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelId.COHERE_TRANSCRIBE
import com.satori.domain.model.SpeechModelId.INDICCONFORMER_ML
import com.satori.domain.model.SpeechModelId.PARAKEET_V2_EN
import com.satori.domain.model.SpeechModelId.PARAKEET_V3
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.finish.TranscribeAfterStopUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelChoiceTest {

    private val parakeetBytes = 670_478_772L
    private val cohereBytes = 2_888_052_036L
    private val malayalamBytes = 137_566_803L
    private val plenty = 8_000_000_000L

    /**
     * The razr's times in the foreground scheduling group (2026-10-13), where the better
     * transcript after Stop runs with the screen off: the load in ms and the recognition in s
     * per second of audio. Cohere Transcribe was timed there. Parakeet's are its top-app times
     * (3.0 s + 0.054 s) scaled by Cohere's foreground to top-app ratios, 1.9 for the load and
     * 1.4 for the recognition; v2's are v3's, as v2 took 0.95 times v3's time (2026-10-12).
     */
    private val foreground = mapOf(
        PARAKEET_V3 to (5_700L to 0.0756),
        PARAKEET_V2_EN to (5_700L to 0.0756),
        COHERE_TRANSCRIBE to (15_300L to 0.28)
    )

    /**
     * The Malayalam model's runs on the razr on 2026-10-14, not yet in the app: sherpa-onnx's own
     * tool on four threads made the recognizer in 2.142 s, and took at most 0.123 s per second of
     * audio (0.101 to 0.123 on a 30 s and a 29 s note and an 18-minute meeting).
     */
    private val malayalamOnPhone = 2_142L to 0.123

    /** How long a model takes in the foreground group for [audioMs] of audio, by [foreground]. */
    private fun foregroundMs(model: SpeechModelId, audioMs: Long): Double {
        val (loadMs, secondsPerSecond) = foreground.getValue(model)
        return loadMs + secondsPerSecond * audioMs
    }

    /**
     * The debug runs of 2026-10-12 in the background group (the screen off, no service, cores
     * 0-2), fitted on six recordings of 7 to 146 s and timed from the run's start to the stored
     * transcript: the load in ms and the recognition in s per second of audio.
     */
    private val background = mapOf(
        PARAKEET_V3 to (10_700L to 0.566),
        PARAKEET_V2_EN to (10_600L to 0.535),
        COHERE_TRANSCRIBE to (37_400L to 1.579)
    )

    /** Recordings as long as the phone's, and longer ones. */
    private val lengthsMs = listOf(6_700L, 18_000L, 43_600L, 49_100L, 146_400L, 300_000L, 600_000L, 1_800_000L)

    private fun size(model: SpeechModelId) = when (model) {
        COHERE_TRANSCRIBE -> cohereBytes
        INDICCONFORMER_ML -> malayalamBytes
        else -> parakeetBytes
    }

    private fun readiness(
        model: SpeechModelId,
        language: String? = "en",
        installed: Boolean = true,
        aiModelLoaded: Boolean = false,
        availableBytes: Long = plenty
    ) = SpeechModelChoice.readiness(model, language, installed, size(model), aiModelLoaded, availableBytes)

    @Test
    fun `only the model that may not run live is large`() {
        assertTrue(SpeechModelChoice.isLarge(COHERE_TRANSCRIBE))
        assertFalse(SpeechModelChoice.isLarge(PARAKEET_V3))
        assertFalse(SpeechModelChoice.isLarge(PARAKEET_V2_EN))
        assertFalse(SpeechModelChoice.isLarge(INDICCONFORMER_ML))
    }

    @Test
    fun `the Malayalam model fits only a Malayalam transcript, and runs next to the AI model`() {
        assertEquals(ModelReadiness.Ready, readiness(INDICCONFORMER_ML, language = "ml", aiModelLoaded = true, availableBytes = 0L))
        assertEquals(ModelReadiness.WrongLanguage, readiness(INDICCONFORMER_ML, language = "en"))
        assertEquals(ModelReadiness.WrongLanguage, readiness(INDICCONFORMER_ML, language = null))
        // A Malayalam transcript fits no other model.
        assertEquals(ModelReadiness.WrongLanguage, readiness(PARAKEET_V3, language = "ml"))
        assertEquals(ModelReadiness.WrongLanguage, readiness(COHERE_TRANSCRIBE, language = "ml"))
    }

    @Test
    fun `a model not on the phone can't run, whatever else holds`() {
        assertEquals(ModelReadiness.NotInstalled, readiness(PARAKEET_V3, installed = false))
        assertEquals(ModelReadiness.NotInstalled, readiness(COHERE_TRANSCRIBE, installed = false, language = null))
    }

    @Test
    fun `the language decides which models fit`() {
        assertEquals(ModelReadiness.Ready, readiness(PARAKEET_V2_EN, language = "en"))
        assertEquals(ModelReadiness.WrongLanguage, readiness(PARAKEET_V2_EN, language = "de"))
        assertEquals(ModelReadiness.Ready, readiness(COHERE_TRANSCRIBE, language = "de"))
        // Unknown: only the model that finds the language itself.
        assertEquals(ModelReadiness.Ready, readiness(PARAKEET_V3, language = null))
        assertEquals(ModelReadiness.WrongLanguage, readiness(PARAKEET_V2_EN, language = null))
        assertEquals(ModelReadiness.WrongLanguage, readiness(COHERE_TRANSCRIBE, language = null))
    }

    @Test
    fun `a large model needs the AI model out of memory`() {
        assertEquals(ModelReadiness.AiModelLoaded, readiness(COHERE_TRANSCRIBE, aiModelLoaded = true))
        // The live-sized ones run next to it, as Parakeet v3 does while recording.
        assertEquals(ModelReadiness.Ready, readiness(PARAKEET_V2_EN, aiModelLoaded = true, availableBytes = 0L))
        assertEquals(ModelReadiness.Ready, readiness(PARAKEET_V3, aiModelLoaded = true, availableBytes = 0L))
    }

    @Test
    fun `a large model needs its size and the margin free`() {
        val needed = cohereBytes + SpeechModelChoice.MEMORY_MARGIN_BYTES

        assertEquals(ModelReadiness.NotEnoughMemory, readiness(COHERE_TRANSCRIBE, availableBytes = needed - 1))
        assertEquals(ModelReadiness.Ready, readiness(COHERE_TRANSCRIBE, availableBytes = needed))
    }

    @Test
    fun `the margin is 600 MB`() {
        assertEquals(600L * 1024 * 1024, SpeechModelChoice.MEMORY_MARGIN_BYTES)
    }

    @Test
    fun `each model's estimate covers its time in the foreground group, rounded up by at most a quarter`() {
        // Every model has its numbers, and the times to check them against; the Malayalam one's
        // are its runs on the phone outside the app, below.
        assertEquals(SpeechModelId.entries.toSet() - INDICCONFORMER_ML, foreground.keys)

        for (model in foreground.keys) {
            for (audioMs in lengthsMs) {
                val estimate = SpeechModelChoice.estimatedMs(model, audioMs)
                val took = foregroundMs(model, audioMs)
                val said = "$model on $audioMs ms of audio: estimated $estimate ms, foreground $took ms"
                assertTrue(said, estimate >= took)
                assertTrue(said, estimate <= took * 1.25)
            }
        }
    }

    @Test
    fun `the Malayalam model's estimate is 3 s and 0_15 s per second, its runs on the phone to the safe side`() {
        assertEquals(TranscribeTime(fixedMs = 3_000L, msPerAudioSecond = 150L), SpeechModelChoice.transcribeTime(INDICCONFORMER_ML))
        val (loadMs, secondsPerSecond) = malayalamOnPhone
        for (audioMs in lengthsMs) {
            val estimate = SpeechModelChoice.estimatedMs(INDICCONFORMER_ML, audioMs)
            val took = loadMs + secondsPerSecond * audioMs
            val said = "on $audioMs ms of audio: estimated $estimate ms, on the phone $took ms"
            assertTrue(said, estimate >= took)
            // The fixed 3 s weighs most on a short note: 1.35 times its run at 6.7 s, 1.22 on long ones.
            assertTrue(said, estimate <= took * 1.4)
        }
        // 21 s for a 2-minute note (about 17 s on the phone), 2.75 minutes for an 18-minute meeting (about 2).
        assertEquals(21_000L, SpeechModelChoice.estimatedMs(INDICCONFORMER_ML, 120_000L))
        assertEquals(165_000L, SpeechModelChoice.estimatedMs(INDICCONFORMER_ML, 18 * 60_000L))
    }

    @Test
    fun `Cohere's better transcript after Stop, timed in a pocket, is within its estimate`() {
        // 2026-10-13: 43.6 s of audio, the screen off under the AI's service; the step took 28 s.
        val estimate = SpeechModelChoice.estimatedMs(COHERE_TRANSCRIBE, 43_600L)

        assertTrue("$estimate", estimate in 28_000L..35_000L)
    }

    @Test
    fun `with Satori in front a run takes about half the estimate`() {
        // 2026-10-13, top-app, a 49.1 s recording: Parakeet v3 took 5.9 s, Cohere Transcribe 18.5 s.
        val inFront = mapOf(PARAKEET_V3 to 5_900L, COHERE_TRANSCRIBE to 18_500L)

        for ((model, tookMs) in inFront) {
            val share = tookMs.toDouble() / SpeechModelChoice.estimatedMs(model, 49_100L)
            assertTrue("$model: $share", share in 0.4..0.7)
        }
    }

    @Test
    fun `three minutes are about half an hour of audio for Parakeet and eight minutes for Cohere`() {
        val limit = TranscribeAfterStopUseCase.MAX_ESTIMATED_MS
        // The longest audio within the limit, from each model's own numbers.
        val longest = SpeechModelId.entries.associateWith { model ->
            val time = SpeechModelChoice.transcribeTime(model)
            (limit - time.fixedMs) * 1_000 / time.msPerAudioSecond
        }

        for ((model, audioMs) in longest) {
            assertTrue("$model", SpeechModelChoice.estimatedMs(model, audioMs) <= limit)
            assertTrue("$model", SpeechModelChoice.estimatedMs(model, audioMs + 1) > limit)
        }
        // As the KDoc and the docs say: Cohere after Stop takes notes of up to about 8 minutes.
        assertTrue(longest.getValue(PARAKEET_V3) in 1_740_000L..1_980_000L)
        assertTrue(longest.getValue(PARAKEET_V2_EN) in 1_740_000L..1_980_000L)
        assertTrue(longest.getValue(COHERE_TRANSCRIBE) in 450_000L..510_000L)
    }

    @Test
    fun `the background group's estimate covers the debug runs of 2026-10-12, rounded up by at most 5 percent`() {
        // The Malayalam model never runs there: it makes no better transcript after Stop.
        assertEquals(SpeechModelId.entries.toSet() - INDICCONFORMER_ML, background.keys)

        for (model in background.keys) {
            val (loadMs, secondsPerSecond) = background.getValue(model)
            for (audioMs in lengthsMs) {
                val estimate = SpeechModelChoice.estimatedMs(model, audioMs, background = true)
                val took = loadMs + secondsPerSecond * audioMs
                val said = "$model on $audioMs ms of audio: estimated $estimate ms, background $took ms"
                assertTrue(said, estimate >= took)
                assertTrue(said, estimate <= took * 1.05)
            }
        }
    }

    @Test
    fun `on the little cores three minutes are about a minute and a half of audio for Cohere and five for Parakeet`() {
        val limit = TranscribeAfterStopUseCase.MAX_ESTIMATED_MS
        val longest = SpeechModelId.entries.associateWith { model ->
            val time = SpeechModelChoice.backgroundTranscribeTime(model)
            (limit - time.fixedMs) * 1_000 / time.msPerAudioSecond
        }

        for ((model, audioMs) in longest) {
            assertTrue("$model", SpeechModelChoice.estimatedMs(model, audioMs, background = true) <= limit)
            assertTrue("$model", SpeechModelChoice.estimatedMs(model, audioMs + 1, background = true) > limit)
        }
        // As TranscribeAfterStopUseCase.MAX_ESTIMATED_MS says for the finisher under its wake job alone.
        assertTrue(longest.getValue(COHERE_TRANSCRIBE) in 80_000L..95_000L)
        assertTrue(longest.getValue(PARAKEET_V3) in 280_000L..320_000L)
        assertTrue(longest.getValue(PARAKEET_V2_EN) in 280_000L..320_000L)
    }

    @Test
    fun `the Malayalam model has Parakeet v3's times on the little cores, more than its own work`() {
        assertEquals(
            SpeechModelChoice.backgroundTranscribeTime(PARAKEET_V3),
            SpeechModelChoice.backgroundTranscribeTime(INDICCONFORMER_ML)
        )
    }

    @Test
    fun `on recordings as long as the phone's, the little cores take about two to four and a half times the estimate`() {
        // As the KDoc and the docs say, for the 7 to 146 s the background runs were timed on.
        for (model in SpeechModelId.entries) {
            for (audioMs in lengthsMs.filter { it <= 146_400L }) {
                val times = SpeechModelChoice.estimatedMs(model, audioMs, background = true).toDouble() /
                    SpeechModelChoice.estimatedMs(model, audioMs)
                assertTrue("$model on $audioMs ms: $times", times in 1.9..4.7)
            }
        }
    }

    @Test
    fun `Cohere Transcribe takes almost four times as long per second of audio as Parakeet`() {
        // As SpeechModelId's KDoc says: 0.20 s against 0.054 s in top-app, as the research had.
        val cohere = SpeechModelChoice.transcribeTime(COHERE_TRANSCRIBE).msPerAudioSecond.toDouble()
        for (parakeet in listOf(PARAKEET_V3, PARAKEET_V2_EN)) {
            val times = cohere / SpeechModelChoice.transcribeTime(parakeet).msPerAudioSecond
            assertTrue("$parakeet: $times", times in 3.4..4.0)
        }
    }

    @Test
    fun `no audio costs the load and start alone`() {
        for (model in SpeechModelId.entries) {
            assertEquals(SpeechModelChoice.transcribeTime(model).fixedMs, SpeechModelChoice.estimatedMs(model, 0L))
            assertEquals(SpeechModelChoice.estimatedMs(model, 0L), SpeechModelChoice.estimatedMs(model, -1_000L))
        }
    }

    @Test
    fun `the sheet lists the models on the phone that know the language, the current one marked`() {
        val options = SpeechModelChoice.options(
            language = "en",
            installed = setOf(PARAKEET_V3, COHERE_TRANSCRIBE),
            sizeBytes = ::size,
            aiModelLoaded = false,
            availableBytes = plenty,
            audioMs = 60_000L,
            current = PARAKEET_V3
        )

        assertEquals(listOf(PARAKEET_V3, COHERE_TRANSCRIBE), options.map { it.model })
        assertEquals(listOf(true, false), options.map { it.madeTranscript })
        assertTrue(options.all { it.readiness == ModelReadiness.Ready })
        assertEquals(SpeechModelChoice.estimatedMs(COHERE_TRANSCRIBE, 60_000L), options[1].estimatedMs)
    }

    @Test
    fun `German leaves Parakeet v2 out, an unknown language all but v3, and the Malayalam model is there for each`() {
        val all = SpeechModelId.entries.toSet()
        fun listed(language: String?) = SpeechModelChoice.options(language, all, ::size, false, plenty, 1_000L, null)
            .map { it.model }

        assertEquals(listOf(PARAKEET_V3, COHERE_TRANSCRIBE, INDICCONFORMER_ML), listed("de"))
        assertEquals(listOf(PARAKEET_V3, PARAKEET_V2_EN, COHERE_TRANSCRIBE, INDICCONFORMER_ML), listed("en"))
        assertEquals(listOf(PARAKEET_V3, INDICCONFORMER_ML), listed(null))
        // A Malayalam transcript: Parakeet v3, which finds the language itself, is the way back.
        assertEquals(listOf(PARAKEET_V3, INDICCONFORMER_ML), listed("ml"))
    }

    @Test
    fun `the Malayalam model is offered for an English recording with its estimate, and makes its text again`() {
        val meeting = 18 * 60_000L
        val options = SpeechModelChoice.options(
            language = "en",
            installed = setOf(PARAKEET_V3, INDICCONFORMER_ML),
            sizeBytes = ::size,
            aiModelLoaded = true,
            availableBytes = 0L,
            audioMs = meeting,
            current = PARAKEET_V3
        )

        val malayalam = options.single { it.model == INDICCONFORMER_ML }
        // 3 s and 0.15 s for each of 1,080 s: under three minutes, and it runs beside the AI model.
        assertEquals(165_000L, malayalam.estimatedMs)
        assertEquals(ModelReadiness.Ready, malayalam.readiness)
        assertTrue(malayalam.makesTextAgain)
        assertTrue(malayalam.changesLanguage)
        assertFalse(options.single { it.model == PARAKEET_V3 }.makesTextAgain)
    }

    @Test
    fun `a Translate recording isn't offered the Malayalam model, as it stays in English or German`() {
        val options = SpeechModelChoice.options(
            language = "de",
            installed = SpeechModelId.entries.toSet(),
            sizeBytes = ::size,
            aiModelLoaded = false,
            availableBytes = plenty,
            audioMs = 60_000L,
            current = PARAKEET_V3,
            mode = RecordingMode.REAL_TIME_TRANSLATION
        )

        assertEquals(listOf(PARAKEET_V3, COHERE_TRANSCRIBE), options.map { it.model })
        assertFalse(SpeechModelChoice.mayTranscribe(INDICCONFORMER_ML, RecordingMode.REAL_TIME_TRANSLATION))
        assertTrue(SpeechModelChoice.mayTranscribe(INDICCONFORMER_ML, RecordingMode.SHORT_MEETING))
        assertTrue(SpeechModelChoice.mayTranscribe(PARAKEET_V3, RecordingMode.REAL_TIME_TRANSLATION))
    }

    @Test
    fun `the Malayalam model transcribes in Malayalam whatever the transcript reads as, the others in what it reads as but Malayalam`() {
        for (spoken in listOf("en", "de", "it", null, "ml")) {
            assertEquals("ml", SpeechModelChoice.languageFor(INDICCONFORMER_ML, spoken))
        }
        assertEquals("de", SpeechModelChoice.languageFor(COHERE_TRANSCRIBE, "de"))
        assertEquals("en", SpeechModelChoice.languageFor(PARAKEET_V2_EN, "en"))
        // A transcript in Malayalam script says nothing of what an English or German model would hear.
        assertEquals(null, SpeechModelChoice.languageFor(PARAKEET_V3, "ml"))
        assertEquals(null, SpeechModelChoice.languageFor(COHERE_TRANSCRIBE, "ml"))
    }

    @Test
    fun `a large model that can't run now is listed with the reason`() {
        val options = SpeechModelChoice.options(
            language = "de",
            installed = setOf(PARAKEET_V3, COHERE_TRANSCRIBE),
            sizeBytes = ::size,
            aiModelLoaded = true,
            availableBytes = plenty,
            audioMs = 60_000L,
            current = COHERE_TRANSCRIBE
        )

        assertEquals(listOf(ModelReadiness.Ready, ModelReadiness.AiModelLoaded), options.map { it.readiness })
        assertEquals(listOf(false, true), options.map { it.madeTranscript })
    }

    @Test
    fun `after Stop a recording in Malayalam gets the Malayalam model whatever is set, one in English or German what is set`() {
        for (set in listOf(null, PARAKEET_V2_EN, COHERE_TRANSCRIBE, INDICCONFORMER_ML)) {
            assertEquals(INDICCONFORMER_ML, SpeechModelChoice.afterStopModel(RecordingLanguage.MALAYALAM, set))
            assertEquals(set, SpeechModelChoice.afterStopModel(RecordingLanguage.ENGLISH_OR_GERMAN, set))
        }
    }

    @Test
    fun `a recording went back from Malayalam only when a line in Latin letters comes after one in Malayalam script`() {
        // Made up of code points: a line in Malayalam script, and lines in Latin letters.
        val malayalam = "\u0D2E\u0D32 \u0D15\u0D3E\u0D32"
        fun lines(vararg texts: String) = texts.mapIndexed { i, text ->
            TranscriptionSegment(id = "l$i", sessionId = "s1", text = text, timestamp = 1_000L * (i + 1), isComplete = true)
        }

        // Only in Malayalam, and switched to it after English or German: not back.
        assertFalse(SpeechModelChoice.leftMalayalam(lines(malayalam, malayalam)))
        assertFalse(SpeechModelChoice.leftMalayalam(lines("so this is a test", malayalam)))
        assertFalse(SpeechModelChoice.leftMalayalam(emptyList()))
        // Back to English or German, at the end or in the middle.
        assertTrue(SpeechModelChoice.leftMalayalam(lines(malayalam, "and the agenda goes out today")))
        assertTrue(SpeechModelChoice.leftMalayalam(lines("first", malayalam, "then English", malayalam)))
        // A line without a letter is neither; one not yet complete doesn't count.
        assertFalse(SpeechModelChoice.leftMalayalam(lines(malayalam, ".", "12", malayalam)))
        val partial = lines(malayalam).single().copy(id = "p", text = "the next one", timestamp = 9_000L, isComplete = false)
        assertFalse(SpeechModelChoice.leftMalayalam(lines(malayalam) + partial))
        // Read in the order they were said, not as listed.
        assertTrue(SpeechModelChoice.leftMalayalam(lines(malayalam, "and today").reversed()))
    }
}
