package com.satori.domain.usecase.mindmap

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.mindmap.MapRecordingUseCase.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/**
 * The mind map's answers leave out what gives back the rules of their prompts or is in another
 * script than the Latin one (0.7.3), with the app's own topics and summary prompts (res/values): a
 * topic whose label is a rule goes with its points, a point of the rules goes from a topic that
 * stays, and a summary's sentences of the rules go, or the whole summary when nothing else is
 * left. MindLlm tells a summary call by its prompt's start, so the summary prompt is the app's own
 * under that start. The texts are made up.
 */
class MapRuleEchoesTest {

    private val shipped = ShippedPrompts()
    private val prompts = object : ResourceProvider by shipped {
        override fun getMindSummaryPrompt(localeCode: String) = "mind summary prompt\n" + shipped.getMindSummaryPrompt(localeCode)
    }

    private class Log : MindMapLog {
        val lines = mutableListOf<String>()
        override fun dropped(id: String, count: Int, what: MindMapLog.What, why: AnswerDrop) {
            lines += MindMapLog.line(id, count, what, why)
        }
    }

    private val log = Log()
    private val recordings = FakeRecordings()
    private val map = FakeMindMapRepository(recordings)
    private val llm = MindLlm()
    private val language = NotesLanguageResolver(FakeSettingsRepository(), recordings) { "en" }
    private var ids = 0

    @Test
    fun `a topic of the rules goes with its points, and a point of the rules goes from a real topic`() {
        // The label's words are the notes', as a 1B note of the rules would have them.
        recordings.add("s1", at = 1_000L, "The roof of the garden shed leaks again, and new boards cost eighty euros.")
        recordings.note("s1", "Plain statements", "- Plain statements, one idea per point.", at = 2_000L)
        llm.topics = {
            topicsAnswer(
                "Plain statements" to listOf("One idea per point."),
                "Garden shed" to listOf("The roof leaks again.", "Use only what the text says.")
            )
        }
        val useCase = MapRecordingUseCase(recordings, map, llm, prompts, language, now = { 100L }, newId = { "n${++ids}" }, mapLog = log)

        val outcome = runBlocking { useCase("s1") }

        assertEquals(Outcome.Mapped(listOf("n1")), outcome)
        assertEquals(listOf("Garden shed" to listOf("The roof leaks again.")), map.linksOf("s1"))
        assertEquals(
            listOf(
                "Dropped 1 map topics that repeat the rules of their prompt in s1",
                "Dropped 1 map points that repeat the rules of their prompt in s1"
            ),
            log.lines
        )
    }

    @Test
    fun `a topic in another script goes with its points, and a point in another script goes from a real topic`() {
        // Made up: the notes of an earlier version in Telugu script ("Garden shed"), and in Latin letters.
        recordings.add("s1", at = 1_000L, "The roof of the garden shed leaks again, and new boards cost eighty euros.")
        recordings.note("s1", "తోట షెడ్", "- పైకప్పు కారుతోంది", at = 2_000L)
        llm.topics = {
            topicsAnswer("తోట షెడ్" to listOf("పైకప్పు కారుతోంది"), "Garden shed" to listOf("The roof leaks again.", "పైకప్పు కారుతోంది"))
        }
        val useCase = MapRecordingUseCase(recordings, map, llm, prompts, language, now = { 100L }, newId = { "n${++ids}" }, mapLog = log)

        assertEquals(Outcome.Mapped(listOf("n1")), runBlocking { useCase("s1") })
        assertEquals(listOf("Garden shed" to listOf("The roof leaks again.")), map.linksOf("s1"))
        assertEquals(listOf("Dropped 1 map topics in another script in s1", "Dropped 1 map points in another script in s1"), log.lines)
    }

    @Test
    fun `a topic named with the words of the text a recording says itself stays (the review of 0_7_3)`() {
        recordings.add("s1", at = 1_000L, "We sorted the letters from the bank and the letters from school, and the bank letters go to the folder.")
        llm.topics = { topicsAnswer("Letters" to listOf("The bank letters go to the folder.")) }
        val useCase = MapRecordingUseCase(recordings, map, llm, prompts, language, now = { 100L }, newId = { "n${++ids}" }, mapLog = log)

        assertEquals(Outcome.Mapped(listOf("n1")), runBlocking { useCase("s1") })
        assertEquals(listOf("Letters" to listOf("The bank letters go to the folder.")), map.linksOf("s1"))
        assertEquals(emptyList<String>(), log.lines)
    }

    private fun theme(summary: String): RefreshThemeSummariesUseCase {
        recordings.add("r1", at = 1_000L)
        runBlocking {
            map.upsertNode(MindNode("t1", MindNodeKind.THEME, "Garden shed", "gard shed", createdAt = 0L, updatedAt = 0L))
            map.upsertNode(MindNode("t1-topic", MindNodeKind.TOPIC, "Garden shed", "gard shed", parentId = "t1", createdAt = 0L, updatedAt = 0L))
            map.link("t1-topic", "r1", listOf("The roof leaks again."), emptyList(), at = 0L)
        }
        llm.summary = { summary }
        return RefreshThemeSummariesUseCase(map, llm, prompts, language, now = { 500L }, zone = { ZoneOffset.UTC }, mapLog = log)
    }

    @Test
    fun `a summary loses its sentences of the rules and keeps the rest`() {
        val useCase = theme("""{"summary": "The roof of the shed leaks again. No lists, no headings."}""")

        runBlocking { useCase(listOf("t1"), limit = 1) }

        assertEquals("The roof of the shed leaks again.", map.nodes.getValue("t1").summary)
        assertEquals(listOf("Dropped 1 summary sentences that repeat the rules of their prompt in theme t1"), log.lines)
    }

    @Test
    fun `a summary of nothing but the rules is no summary`() {
        val useCase = theme("""{"summary": "Never report on the recordings. Keep times as they were said."}""")

        val outcome = runBlocking { useCase(listOf("t1"), limit = 1) }

        assertEquals(RefreshThemeSummariesUseCase.Outcome.Written(0), outcome)
        assertNull(map.nodes.getValue("t1").summary)
        assertEquals(listOf("Dropped 2 summary sentences that repeat the rules of their prompt in theme t1"), log.lines)
    }
}
