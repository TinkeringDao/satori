package com.satori.domain.usecase.stt

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.Todo
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.transcription.LanguagesStored
import com.satori.domain.usecase.transcription.LinesLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscribeAgainUseCaseTest {

    private val created = 5_000_000L
    // The capture began 1.5 s after the recording was created.
    private val audio = RecordingAudio(
        sessionId = "s1",
        path = "/files/audio/s1.wav",
        durationMs = 30_000,
        runs = listOf(AudioRun(startedAt = created + 1_500, startMs = 0))
    )
    private val oldLines = listOf(segment("I want to go on a Pike", at = created + 6_000))
    private val englishTalk = "I want to go on a hike with Anna and the kids on Sunday if the weather is good"
    private val germanTalk = "Wir wollen am Sonntag mit Anna und den Kindern wandern gehen, wenn das Wetter gut ist"
    private val notes = listOf(insight(title = "Plans", content = "A hike", at = created + 20_000))

    private val transcripts = FakeTranscriptionRepository(
        session = TranscriptionSession(id = "s1", name = "Weekend", createdAt = created, lastModifiedAt = created),
        segments = oldLines,
        insights = notes
    )
    private val audioStore = FakeAudioRepository(audio)
    private val speech = FakeSpeech()
    private val todos = FakeTodos()
    private var ids = 0
    private val logged = mutableListOf<String>()
    private val useCase = TranscribeAgainUseCase(
        speech, transcripts, todos, audioStore, newId = { "new${++ids}" }, linesLog = LinesLog { line, _ -> logged += line }
    )

    @Test
    fun `the new lines replace the old ones, stamped where their speech ended, and keep their places in the audio`() = runBlocking {
        speech.lines = listOf(
            SpokenLine("I want to go on a hike.", AudioSpan(startMs = 1_000, endMs = 3_000)),
            SpokenLine("The weather looks good.", AudioSpan(startMs = 4_000, endMs = 5_200))
        )

        val outcome = useCase("s1").getOrThrow()

        assertEquals(TranscribeAgainOutcome.Replaced, outcome)
        assertEquals(
            listOf(
                TranscriptionSegment("new1", "s1", "I want to go on a hike.", timestamp = created + 1_500 + 3_000, isComplete = true),
                TranscriptionSegment("new2", "s1", "The weather looks good.", timestamp = created + 1_500 + 5_200, isComplete = true)
            ),
            transcripts.segments
        )
        assertEquals(mapOf("new1" to AudioSpan(1_000, 3_000), "new2" to AudioSpan(4_000, 5_200)), audioStore.replacedLines)
        // Notes and name stay.
        assertEquals(notes, transcripts.insights)
        assertEquals("Weekend", transcripts.session?.name)
    }

    @Test
    fun `new lines cut mid-sentence are joined, playing from the first one's start`() = runBlocking {
        speech.lines = listOf(
            SpokenLine("I want to go on a hike", AudioSpan(startMs = 1_000, endMs = 3_000)),
            SpokenLine("on Sunday", AudioSpan(startMs = 3_300, endMs = 4_100))
        )

        useCase("s1").getOrThrow()

        assertEquals(
            listOf(TranscriptionSegment("new1", "s1", "I want to go on a hike on Sunday", timestamp = created + 1_500 + 4_100, isComplete = true)),
            transcripts.segments
        )
        assertEquals(mapOf("new1" to AudioSpan(1_000, 4_100)), audioStore.replacedLines)
        assertEquals(listOf("Lines 2->1 (open 1, lowercase 0, dangling 0, forced 0, continuer 0) in s1, transcribed again"), logged)
    }

    @Test
    fun `new lines of two captures stay apart, as the recording stood still between them`() = runBlocking {
        // Started again a minute after the error that ended the first capture at 5 s of audio
        audioStore.audio = audio.copy(runs = audio.runs + AudioRun(startedAt = created + 66_500, startMs = 5_000))
        speech.lines = listOf(
            SpokenLine("I want to go on a hike", AudioSpan(startMs = 1_000, endMs = 4_900)),
            SpokenLine("on Sunday", AudioSpan(startMs = 5_200, endMs = 6_000))
        )

        useCase("s1").getOrThrow()

        assertEquals(listOf("I want to go on a hike", "on Sunday"), transcripts.segments.map { it.text })
    }

    @Test
    fun `new lines of two notes stay apart, so each note keeps its own`() = runBlocking {
        val first = TranscriptionSegment("old1", "s1", "We walked to the lake", created + 6_000, isComplete = true)
        val second = TranscriptionSegment("old2", "s1", "and swam for an hour.", created + 8_000, isComplete = true)
        transcripts.segments = listOf(first, second)
        audioStore.audio = audio.copy(lines = mapOf("old1" to AudioSpan(2_000, 4_000), "old2" to AudioSpan(4_500, 6_000)))
        transcripts.insights = listOf(
            notes[0].copy(id = "walk", sourceSegmentIds = listOf("old1")),
            notes[0].copy(id = "swim", sourceSegmentIds = listOf("old2"))
        )
        speech.lines = listOf(
            SpokenLine("We walked to the lake", AudioSpan(startMs = 2_050, endMs = 3_900)),
            SpokenLine("and swam for an hour.", AudioSpan(startMs = 4_550, endMs = 5_900))
        )

        useCase("s1").getOrThrow()

        assertEquals(listOf("new1", "new2"), transcripts.segments.map { it.id })
        assertEquals(
            mapOf("walk" to listOf("new1"), "swim" to listOf("new2")),
            transcripts.insights.associate { it.id to it.sourceSegmentIds }
        )
    }

    @Test
    fun `the mind map's links point at the new lines said when their old ones were`() = runBlocking {
        transcripts.linkSources = mapOf("hike-topic" to listOf(oldLines[0].id), "other-topic" to listOf("of-another-transcript"))
        speech.lines = listOf(
            SpokenLine("I want to go on a hike.", AudioSpan(startMs = 1_000, endMs = 3_000)),
            SpokenLine("The weather looks good.", AudioSpan(startMs = 9_000, endMs = 9_800))
        )

        useCase("s1").getOrThrow()

        assertEquals(mapOf("hike-topic" to listOf("new1"), "other-topic" to listOf("of-another-transcript")), transcripts.linkSources)
    }

    @Test
    fun `lines of nothing but punctuation are no speech and leave the transcript as it was`() = runBlocking {
        speech.lines = listOf(SpokenLine(".", AudioSpan(1_000, 1_300)), SpokenLine(" . ", AudioSpan(4_000, 4_200)))

        assertEquals(TranscribeAgainOutcome.NoSpeech, useCase("s1").getOrThrow())

        assertEquals(oldLines, transcripts.segments)
        assertNull(audioStore.replacedLines)
        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `a line with no word left once its Malayalam is cleaned is no line, and the others are stored`() = runBlocking {
        // Made-up: the vowel sign aa and the anusvara from 0.2 s to 0.5 s, as the start buzz came
        // out, which the clean-up leaves out as no letter carries them; a zero width joiner alone;
        // and two lines of made-up syllables (ka aa la; a va).
        val kaLa = "\u0D15\u0D3E\u0D32"
        speech.lines = listOf(
            SpokenLine("\u0D3E\u0D02", AudioSpan(200, 500)),
            SpokenLine(kaLa, AudioSpan(1_000, 2_000)),
            SpokenLine("\u200D", AudioSpan(2_600, 2_900)),
            SpokenLine("\u0D05\u0D35", AudioSpan(4_000, 5_000))
        )

        assertEquals(TranscribeAgainOutcome.Replaced, useCase("s1", SpeechModelId.INDICCONFORMER_ML).getOrThrow())

        assertEquals(listOf("new1" to kaLa, "new2" to "\u0D05\u0D35"), transcripts.segments.map { it.id to it.text })
        // Nor do they have places in the audio.
        assertEquals(mapOf("new1" to AudioSpan(1_000, 2_000), "new2" to AudioSpan(4_000, 5_000)), audioStore.replacedLines)
    }

    @Test
    fun `lines of nothing but signs that no letter carries are no speech and leave the transcript as it was`() = runBlocking {
        speech.lines = listOf(SpokenLine("\u0D3E\u0D02", AudioSpan(200, 500)), SpokenLine("\u0D4D", AudioSpan(3_000, 3_300)))

        assertEquals(TranscribeAgainOutcome.NoSpeech, useCase("s1", SpeechModelId.INDICCONFORMER_ML).getOrThrow())

        assertEquals(oldLines, transcripts.segments)
        assertNull(audioStore.replacedLines)
    }

    @Test
    fun `notes and to-dos point at the new lines said when their old ones were`() = runBlocking {
        // The old line was said from 2 s to 4 s (and given out at 6 s, its stamp).
        audioStore.audio = audio.copy(lines = mapOf(oldLines[0].id to AudioSpan(2_000, 4_000)))
        val linked = notes[0].copy(sourceSegmentIds = listOf(oldLines[0].id))
        val summary = insight(title = "Summary", content = "All of it", at = created + 21_000).copy(id = "summary")
        transcripts.insights = listOf(linked, summary)
        todos.forSession = listOf(
            Todo(id = "todo-hike", sessionId = "s1", text = "Book the hike", sourceSegmentIds = listOf(oldLines[0].id), createdAt = 0, updatedAt = 0),
            Todo(id = "todo-hand", sessionId = "s1", text = "Added by hand", createdAt = 0, updatedAt = 0)
        )
        speech.lines = listOf(
            SpokenLine("I want to go on a hike", AudioSpan(startMs = 2_050, endMs = 3_900)),
            SpokenLine("on Sunday", AudioSpan(startMs = 9_000, endMs = 9_800))
        )

        useCase("s1").getOrThrow()

        assertEquals(listOf("new1"), transcripts.insights.single { it.id == linked.id }.sourceSegmentIds)
        // A note without sources is the recording's summary, and stays one.
        assertEquals(emptyList<String>(), transcripts.insights.single { it.id == "summary" }.sourceSegmentIds)
        assertEquals(mapOf("todo-hike" to listOf("new1")), transcripts.replacedTodoSources)
    }

    @Test
    fun `audio without speech leaves the transcript as it was`() = runBlocking {
        speech.lines = emptyList()

        assertEquals(TranscribeAgainOutcome.NoSpeech, useCase("s1").getOrThrow())
        assertEquals(oldLines, transcripts.segments)
        assertNull(audioStore.replacedLines)
    }

    @Test
    fun `a recording without audio is left alone and the speech model never runs`() = runBlocking {
        audioStore.audio = null

        assertEquals(TranscribeAgainOutcome.NoAudio, useCase("s1").getOrThrow())
        assertEquals(0, speech.calls)
        assertEquals(oldLines, transcripts.segments)
    }

    @Test
    fun `a speech model that fails leaves the transcript as it was`() = runBlocking {
        val failure = IllegalStateException("decode failed")
        speech.failure = failure

        assertSame(failure, useCase("s1").exceptionOrNull())
        assertEquals(oldLines, transcripts.segments)
        assertNull(audioStore.replacedLines)
    }

    @Test
    fun `a transcript that can't be stored is a failure and records no places`() = runBlocking {
        speech.lines = listOf(SpokenLine("hello", AudioSpan(0, 900)))
        transcripts.replaceSegmentsFailure = IllegalStateException("disk full")

        assertTrue(useCase("s1").isFailure)
        assertEquals(oldLines, transcripts.segments)
        assertNull(audioStore.replacedLines)
    }

    @Test
    fun `a recording deleted meanwhile is a failure`() = runBlocking {
        transcripts.session = null

        assertTrue(useCase("s1").isFailure)
        assertEquals(0, speech.calls)
    }

    @Test
    fun `cancelling is not a failure`() {
        speech.failure = CancellationException("left the page")

        try {
            runBlocking { useCase("s1") }
            fail("the cancel was swallowed")
        } catch (e: CancellationException) {
            assertEquals("left the page", e.message)
        }
        assertEquals(oldLines, transcripts.segments)
    }

    @Test
    fun `the transcript is stored as the chosen model's`() = runBlocking {
        speech.lines = listOf(SpokenLine("I want to go on a hike", AudioSpan(1_000, 3_000)))
        transcripts.segments = listOf(segment(englishTalk, at = created + 6_000))

        useCase("s1", SpeechModelId.PARAKEET_V2_EN).getOrThrow()

        assertEquals(SpeechModelId.PARAKEET_V2_EN, speech.model)
        assertEquals(SpeechModelId.PARAKEET_V2_EN.id, transcripts.session?.transcriptModel)
    }

    @Test
    fun `the live model is used unless another is chosen`() = runBlocking {
        speech.lines = listOf(SpokenLine("hello", AudioSpan(0, 900)))

        useCase("s1").getOrThrow()

        assertEquals(SpeechModelId.LIVE, speech.model)
        assertEquals(SpeechModelId.LIVE.id, transcripts.session?.transcriptModel)
    }

    @Test
    fun `a model that is told the language is told the one the transcript is in`() = runBlocking {
        speech.lines = listOf(SpokenLine("Wir gehen am Sonntag wandern", AudioSpan(1_000, 3_000)))
        transcripts.segments = listOf(segment(germanTalk, at = created + 6_000))

        useCase("s1", SpeechModelId.COHERE_TRANSCRIBE).getOrThrow()

        assertEquals("de", speech.language)
    }

    @Test
    fun `a model that doesn't know the language is refused before it runs`() = runBlocking {
        transcripts.segments = listOf(segment(germanTalk, at = created + 6_000))

        assertTrue(useCase("s1", SpeechModelId.PARAKEET_V2_EN).isFailure)
        // Too short to tell: the language the notes stored is no stand-in, nor a Translate
        // recording's target.
        transcripts.segments = oldLines
        transcripts.session = transcripts.session?.copy(outputLanguage = "en")
        assertTrue(useCase("s1", SpeechModelId.COHERE_TRANSCRIBE).isFailure)
        transcripts.session = transcripts.session?.copy(mode = RecordingMode.REAL_TIME_TRANSLATION)
        assertTrue(useCase("s1", SpeechModelId.COHERE_TRANSCRIBE).isFailure)

        assertEquals(0, speech.calls)
        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `the Malayalam model transcribes any recording in Malayalam, which it is then, and Parakeet makes it English or German again`() = runBlocking {
        val languages = LanguagesStored(transcripts)
        val transcribe = TranscribeAgainUseCase(speech, languages, todos, audioStore, newId = { "new${++ids}" })
        // Malayalam speech recorded in English or German: Parakeet v3 made an English-looking transcript of it.
        transcripts.segments = listOf(segment(englishTalk, at = created + 6_000))
        // Tomorrow morning call the doctor, then send the meeting's report.
        speech.lines = listOf(SpokenLine("നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ റിപ്പോർട്ട് അയക്കണം", AudioSpan(1_000, 3_000)))

        transcribe("s1", SpeechModelId.INDICCONFORMER_ML).getOrThrow()

        assertEquals(SpeechModelId.INDICCONFORMER_ML, speech.model)
        assertEquals("ml", speech.language)
        assertEquals(RecordingLanguage.MALAYALAM, transcripts.session?.language)
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, transcripts.session?.transcriptModel)

        // The way back: Parakeet v3 finds the language itself, which the Malayalam transcript can't tell.
        speech.lines = listOf(SpokenLine("I want to go on a hike", AudioSpan(1_000, 3_000)))
        transcribe("s1", SpeechModelId.PARAKEET_V3).getOrThrow()

        assertNull(speech.language)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, transcripts.session?.language)
        assertEquals(listOf(RecordingLanguage.MALAYALAM, RecordingLanguage.ENGLISH_OR_GERMAN), languages.languageWrites.map { it.second })
    }

    @Test
    fun `a Malayalam transcript made again is stored as its script writes it, also where its lines join`() = runBlocking {
        // Made-up syllables: ka, a virama out of place and aa, then la; then a word the voice
        // detector cut at its longest stretch of speech, whose chillu rr starts the next line,
        // which joins it.
        val kaLa = "\u0D15\u0D3E\u0D32"
        speech.lines = listOf(
            SpokenLine("\u0D15\u0D4D\u0D3E\u0D32", AudioSpan(1_000, 2_000)),
            SpokenLine("\u0D05\u0D35", AudioSpan(3_000, 22_750)),
            SpokenLine("\u0D7C $kaLa", AudioSpan(22_800, 23_000))
        )

        useCase("s1", SpeechModelId.INDICCONFORMER_ML).getOrThrow()

        assertEquals(listOf(kaLa, "\u0D05\u0D35\u0D7C $kaLa"), transcripts.segments.map { it.text })
    }

    @Test
    fun `a Malayalam transcript is for no model that must be told an English or German language`() = runBlocking {
        // Go to the shop this evening and buy milk and bread.
        transcripts.segments = listOf(segment("ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം", at = created + 6_000))

        assertTrue(useCase("s1", SpeechModelId.COHERE_TRANSCRIBE).isFailure)
        assertTrue(useCase("s1", SpeechModelId.PARAKEET_V2_EN).isFailure)
        assertEquals(0, speech.calls)
    }

    @Test
    fun `a Translate recording stays in English or German, so the Malayalam model is refused before it runs`() = runBlocking {
        transcripts.session = transcripts.session?.copy(mode = RecordingMode.REAL_TIME_TRANSLATION)
        transcripts.segments = listOf(segment(germanTalk, at = created + 6_000))

        assertTrue(useCase("s1", SpeechModelId.INDICCONFORMER_ML).isFailure)
        assertEquals(0, speech.calls)
        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `a recording in two languages is only for the model that finds the language itself`() = runBlocking {
        speech.lines = listOf(SpokenLine("I want to go on a hike", AudioSpan(1_000, 3_000)))
        transcripts.segments = listOf(
            segment("$englishTalk and then we will see what the others want to do", at = created + 6_000),
            segment("$germanTalk und dann sehen wir, was die anderen machen wollen", at = created + 12_000)
        )
        transcripts.session = transcripts.session?.copy(outputLanguage = "en")

        assertTrue(useCase("s1", SpeechModelId.PARAKEET_V2_EN).isFailure)
        assertTrue(useCase("s1", SpeechModelId.COHERE_TRANSCRIBE).isFailure)
        assertEquals(0, speech.calls)

        useCase("s1", SpeechModelId.PARAKEET_V3).getOrThrow()
        assertEquals(SpeechModelId.PARAKEET_V3, speech.model)
    }

    @Test
    fun `a cancel once the new transcript is complete still stores all of it`() = runBlocking {
        speech.lines = listOf(SpokenLine("I want to go on a hike", AudioSpan(1_000, 3_000)))
        transcripts.segments = listOf(segment(englishTalk, at = created + 6_000))
        lateinit var transcription: Job
        // The model is done as it reports the end; the cancel comes before anything is stored.
        transcription = launch {
            useCase("s1", SpeechModelId.PARAKEET_V2_EN) { fraction -> if (fraction == 1f) transcription.cancel() }
        }
        transcription.join()

        assertTrue(transcription.isCancelled)
        assertEquals(listOf("I want to go on a hike"), transcripts.segments.map { it.text })
        assertEquals(mapOf("new1" to AudioSpan(1_000, 3_000)), audioStore.replacedLines)
        assertEquals(SpeechModelId.PARAKEET_V2_EN.id, transcripts.session?.transcriptModel)
    }

    @Test
    fun `a transcript that stays keeps its model`() = runBlocking {
        speech.lines = emptyList()
        transcripts.segments = listOf(segment(englishTalk, at = created + 6_000))

        useCase("s1", SpeechModelId.PARAKEET_V2_EN).getOrThrow()

        assertNull(transcripts.session?.transcriptModel)
    }

    @Test
    fun `progress is passed on`() = runBlocking {
        speech.lines = listOf(SpokenLine("hello", AudioSpan(0, 900)))
        val seen = mutableListOf<Float>()

        useCase("s1") { seen += it }

        assertEquals(listOf(0.5f, 1f), seen)
    }
}
