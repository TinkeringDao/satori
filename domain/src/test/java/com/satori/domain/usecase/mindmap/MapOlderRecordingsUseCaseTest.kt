package com.satori.domain.usecase.mindmap

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase.Outcome
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase.Progress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class MapOlderRecordingsUseCaseTest {

    private companion object {
        /** How long a test waits for the run it holds to reach the model; a run that never does fails it. */
        const val WAIT_MS = 5_000L
    }

    private val rentTalk = "Our rent goes up by fifty euros in March."
    private val heatingTalk = "The heating repair is booked for Monday morning."

    /** Recordings, the map, the model and the service, with the use case over them. */
    private class Rig {
        val recordings = FakeRecordings()
        val map = FakeMindMapRepository(recordings)
        val llm = MindLlm(
            topics = { text ->
                when {
                    "rent" in text -> topicsAnswer("Rent" to listOf("It goes up."))
                    "heating" in text -> topicsAnswer("Heating repair" to listOf("It is booked."))
                    "shed" in text -> topicsAnswer("Garden shed" to listOf("It leaks."))
                    "kitchen" in text -> topicsAnswer("Kitchen" to listOf("It is new."))
                    else -> """{"topics": []}"""
                }
            }
        )
        val service = CountingProcessingService()
        val progress = mutableListOf<Progress>()
        private var ids = 0
        private val language = NotesLanguageResolver(FakeSettingsRepository(), recordings) { "en" }
        val useCase = MapOlderRecordingsUseCase(
            map,
            MapRecordingUseCase(recordings, map, llm, FakeResourceProvider(), language, now = { 100L }, newId = { "n${++ids}" }),
            RefreshThemeSummariesUseCase(map, llm, FakeResourceProvider(), language, now = { 200L }, zone = { ZoneOffset.UTC }),
            service
        )

        suspend fun run(shouldYield: () -> Boolean = { false }): Outcome = useCase(shouldYield) { progress += it }
    }

    @Test
    fun `it gives way between two recordings, with what it mapped, and the rest wait`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.recordings.add("r2", at = 2_000L, heatingTalk)
        rig.recordings.add("r3", at = 3_000L, "The garden shed leaks after every heavy rain.")
        // A recording is handed over to be finished while the second one is read.
        val outcome = runBlocking { rig.run(shouldYield = { rig.llm.topicCalls.size >= 2 }) }

        assertEquals(Outcome.Finished(mapped = 2, withoutTopics = 0, summaries = 0, yielded = true), outcome)
        assertEquals(listOf("The garden shed leaks after every heavy rain.", heatingTalk), rig.llm.topicCalls.map { it.text })
        assertTrue(rig.llm.summaryCalls.isEmpty())
        assertEquals(listOf(Progress.Linking(1, 3), Progress.Linking(2, 3)), rig.progress)
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)

        // The next run takes the one left, and writes the summaries the first one didn't.
        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 3), runBlocking { rig.run() })
    }

    @Test
    fun `it gives way between two summaries, with what it wrote, and the next run writes the rest`() {
        val rig = Rig()
        rig.recordings.add("a", at = 1_000L, rentTalk)
        rig.recordings.add("b", at = 2_000L, heatingTalk)
        rig.recordings.add("c", at = 3_000L, "The garden shed leaks after every heavy rain.")
        // A recording is handed over to be finished while the first summary is written.
        val outcome = runBlocking { rig.run(shouldYield = { rig.llm.summaryCalls.isNotEmpty() }) }

        assertEquals(Outcome.Finished(mapped = 3, withoutTopics = 0, summaries = 1, yielded = true), outcome)
        assertEquals(1, rig.llm.summaryCalls.size)
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)

        assertEquals(Outcome.Finished(mapped = 0, withoutTopics = 0, summaries = 2), runBlocking { rig.run() })
    }

    @Test
    fun `the recordings it is told to leave out, whose readings couldn't be made, aren't read and still wait`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.recordings.add("r2", at = 2_000L, heatingTalk)

        val outcome = runBlocking { rig.useCase(leaveOut = setOf("r2")) { rig.progress += it } }

        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 1), outcome)
        assertEquals(listOf(rentTalk), rig.llm.topicCalls.map { it.text })
        assertEquals(listOf(Progress.Linking(1, 1), Progress.Learning(1, 1)), rig.progress)
        assertEquals(listOf("r2"), runBlocking { rig.map.unmappedSessionIds().first() })
    }

    @Test
    fun `the first recording is read even when it would give way, the summaries wait`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)

        val outcome = runBlocking { rig.run(shouldYield = { true }) }

        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 0, yielded = true), outcome)
        assertEquals(1, rig.llm.topicCalls.size)
        assertTrue(rig.llm.summaryCalls.isEmpty())
        assertTrue(rig.map.nodes.values.filter { it.parentId == null }.all { it.summaryStale || it.summary == null })
    }

    @Test
    fun `the waiting recordings are mapped newest first, then each theme's summary is written once`() {
        val rig = Rig()
        rig.recordings.add("old", at = 500L, "The rent is due on the first of each month.")
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.recordings.add("r2", at = 2_000L, heatingTalk)
        rig.recordings.add("test", at = 3_000L, "Hello, is this on?")

        val outcome = runBlocking { rig.run() }

        assertEquals(Outcome.Finished(mapped = 3, withoutTopics = 0, summaries = 2), outcome)
        assertEquals(listOf(heatingTalk, rentTalk, "The rent is due on the first of each month."), rig.llm.topicCalls.map { it.text })
        assertEquals(listOf(false, false, false, true, true), rig.llm.calls.map { it.isSummary })
        assertEquals(
            listOf(Progress.Linking(1, 3), Progress.Linking(2, 3), Progress.Linking(3, 3), Progress.Learning(1, 2), Progress.Learning(2, 2)),
            rig.progress
        )
        assertTrue(rig.map.nodes.values.filter { it.parentId == null }.all { it.summary != null && !it.summaryStale })
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)
        assertEquals(listOf(ProcessingHolders.MAP_OLDER), rig.service.startedFor)
        assertTrue(rig.service.holders.isEmpty())
    }

    @Test
    fun `recordings without topics or an answer are counted, and one deleted meanwhile is left out`() {
        val rig = Rig()
        rig.recordings.add("gone", at = 500L, "This one is deleted before the AI reads it, sadly.")
        rig.recordings.add("quiet", at = 1_000L, "We talked about the weather for a long time today.")
        rig.recordings.add("garbled", at = 2_000L, heatingTalk)
        rig.recordings.add("rent", at = 3_000L, rentTalk)
        val topics = rig.llm.topics
        rig.llm.topics = { text ->
            when {
                "rent" in text -> topics(text).also { rig.recordings.sessions.remove("gone") }
                "heating" in text -> "I don't know."
                else -> topics(text)
            }
        }

        val outcome = runBlocking { rig.run() }

        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 2, summaries = 1), outcome)
        assertEquals(3, rig.llm.topicCalls.size)
    }

    @Test
    fun `summaries are written for every theme the run linked, with no limit`() {
        val rig = Rig()
        rig.recordings.add("a", at = 1_000L, rentTalk)
        rig.recordings.add("b", at = 2_000L, heatingTalk)
        rig.recordings.add("c", at = 3_000L, "The garden shed leaks after every heavy rain.")
        rig.recordings.add("d", at = 4_000L, "The new kitchen arrives on Friday afternoon.")

        val outcome = runBlocking { rig.run() }

        assertEquals(Outcome.Finished(mapped = 4, withoutTopics = 0, summaries = 4), outcome)
    }

    @Test
    fun `a model that can't be loaded ends the run and the service, and the next run may go`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.llm.reloadFailure = IllegalStateException("no model")

        assertEquals(Outcome.ModelFailed(mapped = 0), runBlocking { rig.run() })
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)

        rig.llm.reloadFailure = null
        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 1), runBlocking { rig.run() })
    }

    @Test
    fun `a database that can't be written ends the run before the next model call`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.recordings.add("r2", at = 2_000L, heatingTalk)
        rig.map.failOn = "link"

        assertEquals(Outcome.ModelFailed(mapped = 0), runBlocking { rig.run() })

        assertEquals(1, rig.llm.topicCalls.size)
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)
    }

    @Test
    fun `a model that fails on the summaries ends the run with what was mapped`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.llm.summary = { null }

        assertEquals(Outcome.ModelFailed(mapped = 1), runBlocking { rig.run() })
        assertEquals(listOf("Rent" to listOf("It goes up.")), rig.map.linksOf("r1"))
    }

    @Test
    fun `a run while another goes on, in any instance, returns at once`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        val gate = CompletableDeferred<Unit>()
        rig.llm.gate = gate

        runBlocking {
            val first = async { rig.run() }
            withTimeout(WAIT_MS) { rig.llm.called.await() }

            assertEquals(Outcome.AlreadyRunning, rig.useCase())
            assertEquals(Outcome.AlreadyRunning, Rig().useCase())

            gate.complete(Unit)
            assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 1), first.await())
        }
        assertEquals(1 to 1, rig.service.starts to rig.service.stops)
    }

    @Test
    fun `cancelling ends the run and the service, and lets the next run go`() {
        val rig = Rig()
        rig.recordings.add("r1", at = 1_000L, rentTalk)
        rig.llm.gate = CompletableDeferred()

        runBlocking {
            val run = launch { rig.run() }
            withTimeout(WAIT_MS) { rig.llm.called.await() }
            run.cancelAndJoin()
        }

        assertEquals(1 to 1, rig.service.starts to rig.service.stops)
        assertTrue(rig.map.linksOf("r1").isEmpty())
        rig.llm.gate = null
        assertEquals(Outcome.Finished(mapped = 1, withoutTopics = 0, summaries = 1), runBlocking { rig.run() })
    }
}
