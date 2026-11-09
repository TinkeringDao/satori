package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.stt.FakeAudioRepository
import com.satori.domain.usecase.stt.FakeTodos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinTranscriptLinesUseCaseTest {

    private val created = 5_000_000L

    // Two sentences, each cut in two by the voice detector.
    private val lines = listOf(
        TranscriptionSegment("l1", "s1", "We could take the train", created + 2_500, isComplete = true),
        TranscriptionSegment("l2", "s1", "On Sunday morning.", created + 4_100, isComplete = true),
        TranscriptionSegment("l3", "s1", "The tickets are cheap.", created + 6_000, isComplete = true),
        TranscriptionSegment("l4", "s1", "because it is not the season.", created + 8_000, isComplete = true)
    )
    private val places = mapOf(
        "l1" to AudioSpan(0, 2_000),
        "l2" to AudioSpan(2_400, 3_600),
        "l3" to AudioSpan(4_000, 5_400),
        "l4" to AudioSpan(5_800, 7_500)
    )

    private val transcripts = FakeTranscriptionRepository(
        session = TranscriptionSession(id = "s1", name = null, createdAt = created, lastModifiedAt = created),
        segments = lines
    )
    private val audioStore = FakeAudioRepository(
        RecordingAudio("s1", "/files/audio/s1.wav", durationMs = 8_000, runs = listOf(AudioRun(created, 0)), lines = places)
    )
    private val todos = FakeTodos()
    private val settings = FakeSettingsRepository()
    private val logged = mutableListOf<String>()

    // The writes of the places ("places") and of the lines ("lines"), in order; [holdPlaces], when
    // set, runs before each write of the places, as a write that takes its time.
    private val writes = mutableListOf<String>()
    private var holdPlaces: (suspend () -> Unit)? = null
    private val audioWrites = object : RecordingAudioRepository by audioStore {
        override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>) {
            holdPlaces?.invoke()
            writes += "places"
            audioStore.replaceLines(sessionId, lines)
        }
    }
    private val lineWrites = object : TranscriptionRepository by transcripts {
        override suspend fun replaceSegments(
            sessionId: String,
            segments: List<TranscriptionSegment>,
            insightSources: Map<String, List<String>>,
            todoSources: Map<String, List<String>>,
            linkSources: Map<String, List<String>>
        ): Result<Unit> {
            writes += "lines"
            return transcripts.replaceSegments(sessionId, segments, insightSources, todoSources, linkSources)
        }
    }
    private val useCase = JoinTranscriptLinesUseCase(lineWrites, todos, audioWrites, settings, LinesLog { line, _ -> logged += line })

    /** The index once the lines are joined: the joined lines' places, the joined-away lines' kept. */
    private val joinedPlaces = mapOf(
        "l1" to AudioSpan(0, 3_600),
        "l2" to AudioSpan(2_400, 3_600),
        "l3" to AudioSpan(4_000, 7_500),
        "l4" to AudioSpan(5_800, 7_500)
    )
    private val joinedLines = listOf(
        TranscriptionSegment("l1", "s1", "We could take the train on Sunday morning.", created + 4_100, isComplete = true),
        TranscriptionSegment("l3", "s1", "The tickets are cheap because it is not the season.", created + 8_000, isComplete = true)
    )

    private fun note(id: String, vararg sources: String) =
        LlmInsight(id = id, sessionId = "s1", content = "A note", timestamp = created + 9_000, sourceSegmentIds = sources.toList())

    private fun todo(id: String, vararg sources: String) =
        Todo(id = id, sessionId = "s1", text = "A to-do", sourceSegmentIds = sources.toList(), createdAt = 0, updatedAt = 0)

    private fun join() = runBlocking { useCase.join("s1") }

    @Test
    fun `lines cut mid-sentence are joined, and everything that cites them points at the joined ones`() {
        transcripts.insights = listOf(note("n1", "l1", "l2"), note("n2", "l4", "l3"), note("summary"))
        todos.forSession = listOf(todo("t1", "l2"), todo("t2", "l3", "l4"), todo("t3"), todo("t4", "l1"))
        transcripts.linkSources = mapOf("k1" to listOf("l2", "l4"), "k2" to listOf("l1"))

        assertEquals(2, join())

        assertEquals(joinedLines, transcripts.segments)
        assertEquals(
            mapOf("n1" to listOf("l1"), "n2" to listOf("l3"), "summary" to emptyList()),
            transcripts.insights.associate { it.id to it.sourceSegmentIds }
        )
        // Only the to-dos whose sources change are written.
        assertEquals(mapOf("t1" to listOf("l1"), "t2" to listOf("l3")), transcripts.replacedTodoSources)
        assertEquals(mapOf("k1" to listOf("l1", "l3"), "k2" to listOf("l1")), transcripts.linkSources)
        // Tapping a joined line plays it from its first word to its last.
        assertEquals(joinedPlaces, audioStore.replacedLines)
        assertEquals(listOf("Lines 4->2 (open 1, lowercase 1, dangling 0, forced 0, continuer 0) in s1"), logged)
    }

    @Test
    fun `the places are written before the lines, and the lines joined away keep theirs`() {
        join()

        assertEquals(listOf("places", "lines"), writes)
        assertEquals(AudioSpan(2_400, 3_600), audioStore.replacedLines?.get("l2"))
    }

    @Test
    fun `a run after a death between the two writes joins the lines as the first would have`() {
        // The places were written, the lines not.
        audioStore.audio = audioStore.audio?.copy(lines = joinedPlaces)

        assertEquals(2, join())

        assertEquals(joinedLines, transcripts.segments)
        assertEquals(joinedPlaces, audioStore.replacedLines)
    }

    @Test
    fun `a run again reads where the voice detector cut a joined line, at a maximum below 20 s too`() {
        settings.settings = AppSettings(vadMaxSpeechDuration = 10.0F)
        transcripts.segments = listOf(
            TranscriptionSegment("a", "s1", "We talked about the plans for the new kitchen and the cupboards.", created + 10_000, isComplete = true),
            TranscriptionSegment("b", "s1", "They come next week.", created + 12_000, isComplete = true),
            TranscriptionSegment("c", "s1", "The shop is open on Monday.", created + 13_400, isComplete = true)
        )
        audioStore.audio = audioStore.audio?.copy(
            lines = mapOf("a" to AudioSpan(0, 9_800), "b" to AudioSpan(10_100, 11_600), "c" to AudioSpan(11_900, 13_000))
        )
        assertEquals(1, join())
        val once = transcripts.segments
        audioStore.audio = audioStore.audio?.copy(lines = checkNotNull(audioStore.replacedLines))
        writes.clear()

        // The joined line runs 11.6 s, but its last piece, which the index kept, only 1.5 s.
        assertEquals(0, join())

        assertEquals(once, transcripts.segments)
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `lines of two captures stay apart, however close their places in the audio`() {
        transcripts.segments = listOf(
            TranscriptionSegment("x", "s1", "Then the plan is fine.", created + 6_500, isComplete = true),
            TranscriptionSegment("y", "s1", "We should order the", created + 10_200, isComplete = true),
            TranscriptionSegment("z", "s1", "okay where was I.", created + 57_500, isComplete = true)
        )
        // The microphone was lost at 10 s, and the recording started again 45 s later.
        audioStore.audio = audioStore.audio?.copy(
            runs = listOf(AudioRun(created, 0), AudioRun(created + 55_000, 10_000)),
            lines = mapOf("x" to AudioSpan(4_000, 6_000), "y" to AudioSpan(7_000, 10_000), "z" to AudioSpan(10_400, 12_000))
        )

        assertEquals(0, join())

        assertEquals(listOf("x", "y", "z"), transcripts.segments.map { it.id })
    }

    @Test
    fun `the words that dangle in one language only are read in the transcript's`() {
        transcripts.segments = listOf(
            TranscriptionSegment("g1", "s1", "Wir treffen uns morgen früh am.", created + 2_500, isComplete = true),
            TranscriptionSegment("g2", "s1", "Bahnhof und fahren dann zusammen los.", created + 4_900, isComplete = true)
        )
        audioStore.audio = audioStore.audio?.copy(lines = mapOf("g1" to AudioSpan(0, 2_000), "g2" to AudioSpan(2_400, 4_400)))

        assertEquals(1, join())

        assertEquals(listOf("Wir treffen uns morgen früh am Bahnhof und fahren dann zusammen los."), transcripts.segments.map { it.text })
    }

    @Test
    fun `a cancel once the writes have begun still writes the lines with their places`() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        holdPlaces = {
            writing.complete(Unit)
            written.await()
        }
        val step = launch { useCase.join("s1") }
        writing.await()

        step.cancel()
        written.complete(Unit)
        step.join()

        assertEquals(listOf("places", "lines"), writes)
        assertEquals(joinedLines, transcripts.segments)
    }

    @Test
    fun `joining again joins nothing more and writes nothing`() {
        join()
        // As the audio's index keeps the joined lines' places
        audioStore.audio = audioStore.audio?.copy(lines = checkNotNull(audioStore.replacedLines))
        audioStore.replacedLines = null
        val joined = transcripts.segments
        transcripts.replaceSegmentsFailure = IllegalStateException("written again")

        assertEquals(0, join())

        assertEquals(joined, transcripts.segments)
        assertNull(audioStore.replacedLines)
        assertEquals("Lines 2->2 (open 0, lowercase 0, dangling 0, forced 0, continuer 0) in s1", logged.last())
    }

    @Test
    fun `the lines are joined with the AI off`() {
        settings.settings = AppSettings(llmEnabled = false, llmModelPath = "")

        assertEquals(2, join())
    }

    @Test
    fun `a transcript of whole sentences stays as it is`() {
        transcripts.segments = listOf(
            TranscriptionSegment("l1", "s1", "We could take the train.", created + 2_500, isComplete = true),
            TranscriptionSegment("l3", "s1", "The tickets are cheap.", created + 6_000, isComplete = true)
        )
        transcripts.replaceSegmentsFailure = IllegalStateException("written")

        assertEquals(0, join())

        assertNull(audioStore.replacedLines)
        assertEquals(listOf("Lines 2->2 (open 0, lowercase 0, dangling 0, forced 0, continuer 0) in s1"), logged)
    }

    @Test
    fun `the lines no note cites stay apart from the cited ones, for the last note`() {
        transcripts.insights = listOf(note("n1", "l1", "l2", "l3"))

        assertEquals(1, join())

        assertEquals(listOf("l1", "l3", "l4"), transcripts.segments.map { it.id })
    }

    @Test
    fun `a recording without audio is joined by its stamps and records no places`() {
        audioStore.audio = null

        assertEquals(2, join())

        assertEquals(listOf("l1", "l3"), transcripts.segments.map { it.id })
        assertNull(audioStore.replacedLines)
    }

    @Test
    fun `a write of the lines that fails leaves the transcript as it was and puts its places back`() {
        transcripts.replaceSegmentsFailure = IllegalStateException("disk full")

        assertEquals(0, join())

        assertEquals(lines, transcripts.segments)
        assertEquals(listOf("places", "lines", "places"), writes)
        assertEquals(places, audioStore.replacedLines)
        assertEquals(listOf("Lines of s1 not joined: IllegalStateException"), logged)
    }

    @Test
    fun `a line cut at the live maximum is read as cut, a line of a transcript made again is not`() {
        settings.settings = AppSettings(vadMaxSpeechDuration = 10.0F)
        transcripts.segments = listOf(
            TranscriptionSegment("a", "s1", "We talked about the plans for the new kitchen and the cupboards.", created + 10_000, isComplete = true),
            TranscriptionSegment("b", "s1", "They come next week.", created + 12_000, isComplete = true)
        )
        audioStore.audio = audioStore.audio?.copy(lines = mapOf("a" to AudioSpan(0, 9_800), "b" to AudioSpan(10_100, 11_600)))
        transcripts.session = transcripts.session?.copy(transcriptModel = SpeechModelId.PARAKEET_V2_EN.id)

        assertEquals(0, join())

        transcripts.session = transcripts.session?.copy(transcriptModel = SpeechModelId.LIVE.id)
        assertEquals(1, join())
        assertEquals(
            listOf("We talked about the plans for the new kitchen and the cupboards they come next week."),
            transcripts.segments.map { it.text }
        )
    }

    @Test
    fun `a Malayalam recording's live transcript was cut at the live maximum too, as the Malayalam model made it live`() {
        settings.settings = AppSettings(vadMaxSpeechDuration = 10.0F)
        transcripts.segments = listOf(
            TranscriptionSegment("a", "s1", "We talked about the plans for the new kitchen and the cupboards.", created + 10_000, isComplete = true),
            TranscriptionSegment("b", "s1", "They come next week.", created + 12_000, isComplete = true)
        )
        audioStore.audio = audioStore.audio?.copy(lines = mapOf("a" to AudioSpan(0, 9_800), "b" to AudioSpan(10_100, 11_600)))
        transcripts.session = transcripts.session?.copy(transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)

        assertEquals(1, join())
    }

    @Test
    fun `lines still being recognized are kept with their places`() {
        val partial = TranscriptionSegment("p", "s1", "and then", created + 8_500, isComplete = false)
        transcripts.segments = lines + partial
        audioStore.audio = audioStore.audio?.copy(lines = places + ("p" to AudioSpan(7_700, 8_000)))

        assertEquals(2, join())

        assertEquals(listOf("l1", "l3", "p"), transcripts.segments.map { it.id })
        assertEquals(AudioSpan(7_700, 8_000), audioStore.replacedLines?.get("p"))
    }

    @Test
    fun `a recording that is gone is left alone`() {
        transcripts.session = null

        assertEquals(0, join())

        assertEquals(lines, transcripts.segments)
        assertEquals(emptyList<String>(), logged)
    }
}
