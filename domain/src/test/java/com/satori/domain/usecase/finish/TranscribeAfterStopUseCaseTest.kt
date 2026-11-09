package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.usecase.stt.FakeAudioRepository
import com.satori.domain.usecase.stt.FakeSpeech
import com.satori.domain.usecase.stt.FakeTodos
import com.satori.domain.usecase.stt.SpeechModelChoice
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript after Stop runs only with its model on the phone, the audio kept, one language
 * the model knows, the models this recorder's and a recording short enough; a large model only
 * with the AI model out and enough memory free. The owner can skip it while it runs. Whatever
 * stops it leaves the live transcript. A recording in Malayalam is transcribed again whole by the
 * Malayalam model, whatever is set, with the AI model out, unless it went back to English or
 * German; its Malayalam lines are made up of code points.
 */
class TranscribeAfterStopUseCaseTest {

    private val created = 5_000_000L
    private val english = "I want to go on a hike with Anna and the kids on Sunday if the weather is good"
    private val german = "Wir wollen am Sonntag mit Anna und den Kindern wandern gehen, wenn das Wetter gut ist"
    private val moreEnglish = "and then we will see what the others want to do after the hike on that day"
    private val moreGerman = "und dann sehen wir, was die anderen danach machen wollen, wenn sie nicht zu müde sind"
    private val cohereBytes = 2_888_052_036L

    private val settings = FakeSettingsRepository(AppSettings(transcribeAfterStop = SpeechModelId.COHERE_TRANSCRIBE))
    private val models = FakeSpeechModels(installed = SpeechModelId.entries.toMutableSet())
    private val audio = FakeAudioRepository(
        RecordingAudio("s1", "/files/audio/s1.wav", durationMs = 30_000, runs = listOf(AudioRun(created, 0)))
    )
    private val transcripts = FakeTranscriptionRepository(
        session = TranscriptionSession(id = "s1", name = null, createdAt = created, lastModifiedAt = created),
        segments = listOf(segment(english, at = created + 6_000))
    )
    private val llm = UnloadingLlm { speech.calls }
    private var freeBytes = 4_500_000_000L

    // The AI's service in the foreground, as when the finisher runs after a Stop.
    private var serviceInForeground = true
    private val speech = FakeSpeech().apply { lines = listOf(SpokenLine("I want to go on a hike", AudioSpan(1_000, 3_000))) }
    private val step = TranscribeAfterStopUseCase(
        settings,
        models,
        audio,
        transcripts,
        llm,
        { freeBytes },
        TranscribeAgainUseCase(speech, transcripts, FakeTodos(), audio, newId = { "new" }),
        serviceInForeground = { serviceInForeground }
    )
    private val runs = mutableListOf<BetterTranscriptRun>()
    private val starts get() = runs.size

    private fun run(mayChangeModel: Boolean = true) = runBlocking {
        step.run("s1", mayChangeModel, onStart = { runs += it }, onProgress = {})
    }

    @Test
    fun `Cohere transcribes an English recording in English, with the AI model unloaded first`() {
        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())

        assertEquals(listOf("cleanup before 0 transcriptions"), llm.log)
        assertEquals(1, speech.calls)
        assertEquals(SpeechModelId.COHERE_TRANSCRIBE, speech.model)
        assertEquals("en", speech.language)
        assertEquals(listOf(SpeechModelId.COHERE_TRANSCRIBE), runs.map { it.model })
        assertEquals(SpeechModelId.COHERE_TRANSCRIBE.id, transcripts.session?.transcriptModel)
    }

    @Test
    fun `and a German one in German`() {
        transcripts.segments = listOf(segment(german, at = created + 6_000))

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())

        assertEquals("de", speech.language)
    }

    @Test
    fun `a transcript the set model made already counts as made, and the model doesn't run again`() {
        // A finishing a process death cut short after the new transcript was stored.
        transcripts.session = transcripts.session?.copy(transcriptModel = SpeechModelId.COHERE_TRANSCRIBE.id)

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())
        assertNothingRan()
    }

    @Test
    fun `a transcript another better model made is transcribed again with the one set`() {
        transcripts.session = transcripts.session?.copy(transcriptModel = SpeechModelId.PARAKEET_V2_EN.id)

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())
        assertEquals(1, speech.calls)
    }

    @Test
    fun `nothing set is nothing to do`() {
        settings.settings = AppSettings()

        assertEquals(BetterTranscript.Skipped(null, BetterTranscript.Reason.Off), run())
        assertNothingRan()
    }

    @Test
    fun `a model that is not on the phone is skipped`() {
        models.installed -= SpeechModelId.COHERE_TRANSCRIBE

        assertEquals(skipped(BetterTranscript.Reason.NotInstalled), run())
        assertNothingRan()
    }

    @Test
    fun `a recording without audio is skipped`() {
        audio.audio = null

        assertEquals(skipped(BetterTranscript.Reason.NoAudio), run())
        assertNothingRan()
    }

    @Test
    fun `Parakeet v2 skips a German recording, and one too short to tell`() {
        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN)
        transcripts.segments = listOf(segment(german, at = created + 6_000))

        assertEquals(BetterTranscript.Skipped(SpeechModelId.PARAKEET_V2_EN, BetterTranscript.Reason.WrongLanguage), run())

        // The language the notes stored is no stand-in.
        transcripts.session = transcripts.session?.copy(outputLanguage = "en")
        transcripts.segments = listOf(segment("Okay then", at = created + 6_000))
        assertEquals(BetterTranscript.Skipped(SpeechModelId.PARAKEET_V2_EN, BetterTranscript.Reason.WrongLanguage), run())
        assertNothingRan()
    }

    @Test
    fun `a recording in two languages keeps the transcript of the model that finds the language of each line`() {
        transcripts.session = transcripts.session?.copy(outputLanguage = "en")
        transcripts.segments = listOf(
            segment("$english $moreEnglish", at = created + 6_000),
            segment("$german $moreGerman", at = created + 12_000)
        )

        assertEquals(skipped(BetterTranscript.Reason.WrongLanguage), run())
        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN)
        assertEquals(BetterTranscript.Skipped(SpeechModelId.PARAKEET_V2_EN, BetterTranscript.Reason.WrongLanguage), run())
        assertNothingRan()
    }

    @Test
    fun `a recording a millisecond longer than the model's three minutes cover is left to Transcribe again`() {
        for (model in afterStopModels) {
            settings.settings = AppSettings(transcribeAfterStop = model)
            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(model) + 1)

            assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.TooLong), run())
        }
        assertNothingRan()
    }

    @Test
    fun `the longest recording the three minutes cover runs`() {
        for (model in afterStopModels) {
            settings.settings = AppSettings(transcribeAfterStop = model)
            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(model))
            // The live transcript again: each run replaces it with its own, a line too short to tell.
            transcripts.session = transcripts.session?.copy(transcriptModel = null)
            transcripts.segments = listOf(segment(english, at = created + 6_000))

            assertEquals(BetterTranscript.Replaced(model), run())
        }
        assertEquals(afterStopModels.size, speech.calls)
    }

    @Test
    fun `the after-Stop models are Parakeet v2 and Cohere Transcribe, and the Malayalam model never runs on an English note`() {
        assertEquals(listOf(SpeechModelId.PARAKEET_V2_EN, SpeechModelId.COHERE_TRANSCRIBE), afterStopModels)

        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.INDICCONFORMER_ML)

        assertEquals(BetterTranscript.Skipped(SpeechModelId.INDICCONFORMER_ML, BetterTranscript.Reason.WrongLanguage), run())
        assertNothingRan()
    }

    @Test
    fun `a nine-minute note is too long for Cohere Transcribe, and Parakeet v2 takes it`() {
        audio.audio = audio.audio?.copy(durationMs = 9 * 60_000L)

        assertEquals(skipped(BetterTranscript.Reason.TooLong), run())
        assertNothingRan()

        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN)
        assertEquals(BetterTranscript.Replaced(SpeechModelId.PARAKEET_V2_EN), run())
    }

    @Test
    fun `an eight-minute note gets Cohere Transcribe's better transcript`() {
        audio.audio = audio.audio?.copy(durationMs = 8 * 60_000L)

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())
    }

    @Test
    fun `with only the wake job keeping the app the cut goes by the little cores' times`() {
        // After a reboot: no AI service in the foreground, so Android keeps the threads on cores 0-2.
        serviceInForeground = false
        audio.audio = audio.audio?.copy(durationMs = 2 * 60_000L)

        // Cohere on two minutes of audio: about 58 s under the service, 230 s on the little cores.
        assertEquals(skipped(BetterTranscript.Reason.TooLong), run())
        assertNothingRan()

        serviceInForeground = true
        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())
    }

    @Test
    fun `on the little cores the longest recording the three minutes cover runs, and a millisecond more doesn't`() {
        serviceInForeground = false
        for (model in afterStopModels) {
            settings.settings = AppSettings(transcribeAfterStop = model)
            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(model, background = true) + 1)

            assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.TooLong), run())
        }
        assertNothingRan()

        for (model in afterStopModels) {
            settings.settings = AppSettings(transcribeAfterStop = model)
            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(model, background = true))
            // The live transcript again: each run replaces it with its own, a line too short to tell.
            transcripts.session = transcripts.session?.copy(transcriptModel = null)
            transcripts.segments = listOf(segment(english, at = created + 6_000))

            assertEquals(BetterTranscript.Replaced(model), run())
        }
        assertEquals(afterStopModels.size, speech.calls)
    }

    @Test
    fun `a newer recorder keeps its models and the processor`() {
        assertEquals(skipped(BetterTranscript.Reason.NewerRecorder), run(mayChangeModel = false))
        assertNothingRan()
    }

    @Test
    fun `a large model with too little memory free is skipped, after the AI model was unloaded`() {
        freeBytes = cohereBytes + SpeechModelChoice.MEMORY_MARGIN_BYTES - 1

        assertEquals(skipped(BetterTranscript.Reason.NotEnoughMemory), run())
        assertEquals(listOf("cleanup before 0 transcriptions"), llm.log)
        assertEquals(0, speech.calls)
    }

    @Test
    fun `just enough memory is enough`() {
        freeBytes = cohereBytes + SpeechModelChoice.MEMORY_MARGIN_BYTES

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), run())
    }

    @Test
    fun `an AI model that stays in memory keeps a large model out`() {
        llm.staysLoaded = true

        assertEquals(skipped(BetterTranscript.Reason.AiModelLoaded), run())
        assertEquals(0, speech.calls)
    }

    @Test
    fun `Parakeet v2 runs next to the AI model, without a memory check`() {
        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN)
        freeBytes = 0L

        assertEquals(BetterTranscript.Replaced(SpeechModelId.PARAKEET_V2_EN), run())
        assertTrue(llm.log.isEmpty())
    }

    @Test
    fun `no speech in the audio leaves the transcript`() {
        speech.lines = emptyList()

        assertEquals(skipped(BetterTranscript.Reason.NoSpeech), run())
        assertEquals(english, transcripts.segments.single().text)
        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `a model that fails leaves the transcript`() {
        val failure = IllegalStateException("decode failed")
        speech.failure = failure

        assertEquals(BetterTranscript.Failed(SpeechModelId.COHERE_TRANSCRIBE, failure), run())
        assertEquals(english, transcripts.segments.single().text)
    }

    @Test
    fun `a cancel is passed on`() {
        speech.failure = CancellationException("the screen is gone")

        assertThrows(CancellationException::class.java) { run() }
    }

    @Test
    fun `whatever fails on the way, unloading the AI model say, is the step's failure`() {
        val failure = IllegalStateException("Cannot unload model in ModelLoading")
        llm.cleanupFailure = failure

        assertEquals(BetterTranscript.Failed(SpeechModelId.COHERE_TRANSCRIBE, failure), run())
        assertEquals(0, speech.calls)
        assertEquals(english, transcripts.segments.single().text)
    }

    // ─── A recording in Malayalam ────────────────────────────────────────────

    // Made up of code points, as the Malayalam model writes: two lines of words in its script.
    private val malayalam = "\u0D2E\u0D32 \u0D15\u0D3E\u0D32 \u0D35\u0D30"
    private val moreMalayalam = "\u0D15\u0D32 \u0D2E\u0D3E\u0D32"

    /**
     * A recording stored in Malayalam whose first line Parakeet v3 made before the switch to it
     * ([before]), then the Malayalam model the rest, as its live transcript says.
     */
    private fun malayalamRecording(before: String? = english) {
        transcripts.session = transcripts.session?.copy(
            language = RecordingLanguage.MALAYALAM,
            transcriptModel = SpeechModelId.INDICCONFORMER_ML.id
        )
        transcripts.segments = listOfNotNull(
            before?.let { segment(it, at = created + 6_000) },
            segment(malayalam, at = created + 15_000),
            segment(moreMalayalam, at = created + 22_000)
        )
        speech.lines = listOf(
            SpokenLine(malayalam, AudioSpan(500, 12_000)),
            SpokenLine(moreMalayalam, AudioSpan(14_000, 22_000))
        )
    }

    private fun skippedMalayalam(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(SpeechModelId.INDICCONFORMER_ML, reason)

    @Test
    fun `a recording in Malayalam is transcribed again whole by the Malayalam model, whatever is set for after Stop`() {
        for (set in listOf(null, SpeechModelId.PARAKEET_V2_EN, SpeechModelId.COHERE_TRANSCRIBE)) {
            settings.settings = AppSettings(transcribeAfterStop = set)
            malayalamRecording()

            assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())

            assertEquals(SpeechModelId.INDICCONFORMER_ML, speech.model)
            assertEquals("ml", speech.language)
            // What Parakeet v3 made of the Malayalam said before the switch is gone: the whole
            // audio is in the new transcript.
            assertEquals(listOf(malayalam, moreMalayalam), transcripts.segments.map { it.text })
            assertEquals(RecordingLanguage.MALAYALAM, transcripts.session?.language)
            assertEquals(SpeechModelId.INDICCONFORMER_ML.id, transcripts.session?.transcriptModel)
        }
        assertEquals(3, speech.calls)
        assertEquals(listOf(SpeechModelId.INDICCONFORMER_ML), runs.map { it.model }.distinct())
    }

    @Test
    fun `its live transcript is the same model's and doesn't count as made, also without a line before the switch`() {
        malayalamRecording(before = null)

        assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())
        assertEquals(1, speech.calls)
    }

    @Test
    fun `the AI model in memory, Gemma 4 E2B say, is unloaded before the Malayalam model loads`() {
        malayalamRecording()

        assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())

        assertEquals(listOf("cleanup before 0 transcriptions"), llm.log)
        // However little memory there is: the Malayalam model is no large one.
        freeBytes = 0L
        assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())
        // Nothing left to unload the second time.
        assertEquals(listOf("cleanup before 0 transcriptions"), llm.log)
    }

    @Test
    fun `an AI model whose load keeps it in memory keeps the Malayalam model out`() {
        malayalamRecording()
        llm.staysLoaded = true

        assertEquals(skippedMalayalam(BetterTranscript.Reason.AiModelLoaded), run())
        assertEquals(0, speech.calls)
        assertEquals(english, transcripts.segments.first().text)
    }

    @Test
    fun `a recording that went back from Malayalam to English or German isn't transcribed again in Malayalam`() {
        // Malayalam, then English or German, then Malayalam again to the end.
        malayalamRecording(before = null)
        transcripts.segments = transcripts.segments + segment(moreEnglish, at = created + 18_000)

        assertEquals(skippedMalayalam(BetterTranscript.Reason.LeftMalayalam), run())
        assertNothingRan()

        // Ended in English or German: no Malayalam recording, whatever its lines.
        transcripts.session = transcripts.session?.copy(language = RecordingLanguage.ENGLISH_OR_GERMAN)
        settings.settings = AppSettings()
        assertEquals(BetterTranscript.Skipped(null, BetterTranscript.Reason.Off), run())
        assertNothingRan()
    }

    @Test
    fun `the Malayalam model is skipped while a newer recorder owns the models, when it isn't on the phone, and without audio`() {
        malayalamRecording()
        assertEquals(skippedMalayalam(BetterTranscript.Reason.NewerRecorder), run(mayChangeModel = false))

        models.installed -= SpeechModelId.INDICCONFORMER_ML
        assertEquals(skippedMalayalam(BetterTranscript.Reason.NotInstalled), run())

        models.installed += SpeechModelId.INDICCONFORMER_ML
        audio.audio = null
        assertEquals(skippedMalayalam(BetterTranscript.Reason.NoAudio), run())
        assertNothingRan()
    }

    @Test
    fun `the three minutes cover the Malayalam model's longest recording, in front and on the little cores`() {
        for (background in listOf(false, true)) {
            serviceInForeground = !background
            malayalamRecording()
            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(SpeechModelId.INDICCONFORMER_ML, background) + 1)
            assertEquals(skippedMalayalam(BetterTranscript.Reason.TooLong), run())

            audio.audio = audio.audio?.copy(durationMs = longestWithinLimit(SpeechModelId.INDICCONFORMER_ML, background))
            assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())
        }
        assertEquals(2, speech.calls)
    }

    @Test
    fun `an 18-minute Malayalam meeting fits the three minutes under the AI's service`() {
        malayalamRecording()
        audio.audio = audio.audio?.copy(durationMs = 18 * 60_000L)

        assertEquals(BetterTranscript.Replaced(SpeechModelId.INDICCONFORMER_ML), run())
    }

    @Test
    fun `the owner can skip the Malayalam model too, and the live transcript stays`() = runBlocking {
        malayalamRecording()
        speech.hold = CompletableDeferred()
        val result = async { step.run("s1", mayChangeModel = true, onStart = { runs += it }, onProgress = {}) }
        while (speech.calls == 0) yield()

        runs.single().skip()

        assertEquals(skippedMalayalam(BetterTranscript.Reason.SkippedByOwner), result.await())
        assertEquals(listOf(english, malayalam, moreMalayalam), transcripts.segments.map { it.text })
    }

    // ─── Skip ────────────────────────────────────────────────────────────────

    @Test
    fun `the owner can skip it while the model runs, and the live transcript stays`() = runBlocking {
        speech.hold = CompletableDeferred()
        val result = async { step.run("s1", mayChangeModel = true, onStart = { runs += it }, onProgress = {}) }
        while (speech.calls == 0) yield()

        runs.single().skip()

        assertEquals(skipped(BetterTranscript.Reason.SkippedByOwner), result.await())
        assertEquals(english, transcripts.segments.single().text)
        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `a skip right as it starts runs no model`() {
        val result = runBlocking { step.run("s1", mayChangeModel = true, onStart = { it.skip() }, onProgress = {}) }

        assertEquals(skipped(BetterTranscript.Reason.SkippedByOwner), result)
        assertEquals(0, speech.calls)
    }

    @Test
    fun `a skip once the new transcript is complete changes nothing`() {
        val result = runBlocking {
            step.run("s1", mayChangeModel = true, onStart = { runs += it }, onProgress = { if (it == 1f) runs.single().skip() })
        }

        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), result)
        assertEquals("I want to go on a hike", transcripts.segments.single().text)
        assertEquals(SpeechModelId.COHERE_TRANSCRIBE.id, transcripts.session?.transcriptModel)
    }

    @Test
    fun `a cancel of the finishing while the model runs is passed on, not taken for a skip`() = runBlocking {
        speech.hold = CompletableDeferred()
        val result = async { step.run("s1", mayChangeModel = true, onStart = { runs += it }, onProgress = {}) }
        while (speech.calls == 0) yield()

        result.cancel()

        val thrown = runCatching { result.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(result.isCancelled)
        assertEquals(english, transcripts.segments.single().text)
    }

    private fun skipped(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(SpeechModelId.COHERE_TRANSCRIBE, reason)

    // --- A live transcript that may lack speech (review of 0.7.1): the live model fills it ---------

    /** The step for a recording whose live recognizer left audio untranscribed. */
    private fun runLacking(mayChangeModel: Boolean = true) = runBlocking {
        step.run("s1", mayChangeModel, onStart = { runs += it }, onProgress = {}, mayLackSpeech = true)
    }

    private fun skippedLive(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(SpeechModelId.PARAKEET_V3, reason)

    @Test
    fun `an English recording stopped before its speech model was in is transcribed whole by Parakeet v3, with nothing set`() {
        settings.settings = AppSettings()
        // Stop came while the model was read in: no live line at all.
        transcripts.segments = emptyList()

        assertEquals(BetterTranscript.Replaced(SpeechModelId.PARAKEET_V3), runLacking())

        assertEquals(SpeechModelId.PARAKEET_V3, speech.model)
        assertEquals(listOf("I want to go on a hike"), transcripts.segments.map { it.text })
        assertEquals(SpeechModelId.PARAKEET_V3.id, transcripts.session?.transcriptModel)
        // Never beside the AI model.
        assertEquals(listOf("cleanup before 0 transcriptions"), llm.log)
        assertEquals(listOf(SpeechModelId.PARAKEET_V3), runs.map { it.model })
    }

    @Test
    fun `a model set for after Stop that transcribes it leaves nothing to fill, one that can't leaves it to Parakeet v3`() {
        // Cohere, set, with the language known from the live lines.
        assertEquals(BetterTranscript.Replaced(SpeechModelId.COHERE_TRANSCRIBE), runLacking())
        assertEquals(1, speech.calls)

        // Parakeet v2, set, can't tell the language of a recording with no line: Parakeet v3 finds it itself.
        settings.settings = AppSettings(transcribeAfterStop = SpeechModelId.PARAKEET_V2_EN)
        transcripts.segments = emptyList()
        assertEquals(BetterTranscript.Replaced(SpeechModelId.PARAKEET_V3), runLacking())
        assertEquals(SpeechModelId.PARAKEET_V3, speech.model)
        assertEquals(2, speech.calls)
    }

    @Test
    fun `the live model leaves a recording with Malayalam lines as it is, waits for a newer recorder, and says when it heard nothing`() {
        settings.settings = AppSettings()
        transcripts.segments = listOf(segment(malayalam, at = created + 6_000), segment(english, at = created + 15_000))
        assertEquals(skippedLive(BetterTranscript.Reason.LeftMalayalam), runLacking())

        transcripts.segments = emptyList()
        assertEquals(skippedLive(BetterTranscript.Reason.NewerRecorder), runLacking(mayChangeModel = false))
        assertEquals(0, speech.calls)

        speech.lines = emptyList()
        assertEquals(skippedLive(BetterTranscript.Reason.NoSpeech), runLacking())
        assertTrue(transcripts.segments.isEmpty())
    }

    @Test
    fun `a transcript that lacks nothing is left to the set model alone`() {
        settings.settings = AppSettings()

        assertEquals(BetterTranscript.Skipped(null, BetterTranscript.Reason.Off), run())
        assertNothingRan()
    }

    /**
     * The models Advanced settings offers for after Stop (ModelsUiState.afterStopChoices): those
     * that know English or German, but the live one. Not the Malayalam model, which knows neither.
     */
    private val afterStopModels = SpeechModelId.entries
        .filter { it != SpeechModelId.LIVE && (it.understands("en") || it.understands("de")) }

    /**
     * The longest audio [model] is expected to transcribe within
     * [TranscribeAfterStopUseCase.MAX_ESTIMATED_MS], from its own numbers: those of the
     * foreground group, or of the [background] group.
     */
    private fun longestWithinLimit(model: SpeechModelId, background: Boolean = false): Long {
        val time = if (background) SpeechModelChoice.backgroundTranscribeTime(model) else SpeechModelChoice.transcribeTime(model)
        return (TranscribeAfterStopUseCase.MAX_ESTIMATED_MS - time.fixedMs) * 1_000 / time.msPerAudioSecond
    }

    private fun assertNothingRan() {
        assertEquals(0, speech.calls)
        assertEquals(0, starts)
        assertTrue(llm.log.isEmpty())
    }

    /** The models in [installed] are on the phone; Cohere has its real size. */
    private inner class FakeSpeechModels(val installed: MutableSet<SpeechModelId>) : SpeechModelRepository {
        override suspend fun isInstalled(model: SpeechModelId): Boolean = model in installed
        override fun sizeBytes(model: SpeechModelId): Long =
            if (model == SpeechModelId.COHERE_TRANSCRIBE) cohereBytes else 670_478_772L
    }

    /**
     * An AI model in memory until [cleanup], unless it [staysLoaded] (a load still under way) or
     * the unload throws [cleanupFailure]; the log says how many [transcriptions] had run by then.
     */
    private class UnloadingLlm(private val transcriptions: () -> Int) : LlmRepository by FakeLlmRepository() {
        val log = mutableListOf<String>()
        var staysLoaded = false
        var cleanupFailure: Exception? = null
        private var loaded = true

        override val isModelInMemory: Boolean get() = loaded

        override suspend fun cleanup() {
            log += "cleanup before ${transcriptions()} transcriptions"
            cleanupFailure?.let { throw it }
            if (!staysLoaded) loaded = false
        }
    }
}
