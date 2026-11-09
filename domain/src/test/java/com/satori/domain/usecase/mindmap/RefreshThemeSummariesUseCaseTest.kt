package com.satori.domain.usecase.mindmap

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class RefreshThemeSummariesUseCaseTest {

    // 10 Oct 2026 12:00 and 12 Oct 2026 09:00, UTC
    private val tenth = 1_791_633_600_000L
    private val twelfth = 1_791_795_600_000L

    /**
     * A map whose themes are named by their ids, with the use case over it. The model answers
     * "About <the theme's label>."
     */
    private class Rig(model: LlmRepository? = null) {
        val recordings = FakeRecordings()
        val map = FakeMindMapRepository(recordings)
        val llm = MindLlm(summary = { text -> """{"summary": "About ${text.lines().first()}."}""" })
        val progress = mutableListOf<Pair<Int, Int>>()
        val useCase = RefreshThemeSummariesUseCase(
            map,
            model ?: llm,
            FakeResourceProvider(),
            NotesLanguageResolver(FakeSettingsRepository(), recordings) { "en" },
            now = { 500L },
            zone = { ZoneOffset.UTC }
        )

        /** The recordings [ids], made at 1000, 2000, ... in that order. */
        fun recordings(vararg ids: String) = ids.forEachIndexed { index, id -> recordings.add(id, at = (index + 1) * 1_000L) }

        /** The theme [id] with a topic of its own, linked to each recording with its points. */
        fun theme(id: String, vararg links: Pair<String, List<String>>, summary: String? = null, stale: Boolean = false) =
            runBlocking {
                map.upsertNode(MindNode(id, MindNodeKind.THEME, id, id, createdAt = 0L, updatedAt = 0L))
                map.upsertNode(MindNode("$id-topic", MindNodeKind.TOPIC, id, id, parentId = id, createdAt = 0L, updatedAt = 0L))
                for ((sessionId, points) in links) map.link("$id-topic", sessionId, points, emptyList(), at = 0L)
                summary?.let { map.setSummary(id, it, at = 0L) }
                if (stale) map.markStale(listOf(id))
                map.calls.clear()
            }

        fun refresh(themeIds: List<String> = emptyList(), limit: Int = RefreshThemeSummariesUseCase.PER_FINISH) =
            runBlocking { useCase(themeIds, limit) { index, total -> progress += index to total } }

        fun summaryOf(id: String) = map.nodes.getValue(id).summary
    }

    @Test
    fun `the themes asked for come first, then the others that need a summary, up to the limit`() {
        val rig = Rig()
        rig.recordings("r1", "r2", "r3", "r4")
        rig.theme("A", "r1" to listOf("a"), "r2" to listOf("a"), summary = "Old A.")
        rig.theme("B", "r4" to listOf("b"), summary = "Old B.", stale = true)
        rig.theme("C", "r3" to listOf("c"))
        rig.theme("D", "r1" to listOf("d"), summary = "Old D.")

        assertEquals(Outcome.Written(2), rig.refresh(themeIds = listOf("D"), limit = 2))

        assertEquals("About D.", rig.summaryOf("D"))
        assertEquals("About B.", rig.summaryOf("B"))
        assertNull("past the limit", rig.summaryOf("C"))
        assertEquals("Old A.", rig.summaryOf("A"))
        assertEquals(listOf(1 to 2, 2 to 2), rig.progress)
    }

    @Test
    fun `the model reads the label, then a line per recording, newest first, under the summary prompt and grammar`() {
        val rig = Rig()
        rig.recordings.add("r1", at = tenth)
        rig.recordings.add("r2", at = twelfth)
        rig.theme("Garden shed", "r1" to listOf("The shed is old."), "r2" to listOf("The roof leaks.", "Boards cost 80 euros."))
        runBlocking {
            rig.map.upsertNode(MindNode("paint", MindNodeKind.TOPIC, "Shed paint", "paint shed", parentId = "Garden shed", createdAt = 0L, updatedAt = 0L))
            rig.map.link("paint", "r2", listOf("the roof leaks", "Paint is needed."), emptyList(), at = 0L)
        }

        assertEquals(Outcome.Written(1), rig.refresh(listOf("Garden shed")))

        val call = rig.llm.summaryCalls.single()
        assertEquals(
            "Garden shed\n12 Oct 2026: The roof leaks. Boards cost 80 euros. Paint is needed.\n10 Oct 2026: The shed is old.",
            call.text
        )
        assertEquals("mind summary prompt (en)", call.systemPrompt)
        assertEquals(OutputGrammar.JsonSchema(ThemeSummaryPrompt.SCHEMA), call.grammar)
    }

    @Test
    fun `two themes get the same system prompt, each with its label and points in the user message`() {
        val rig = Rig()
        rig.recordings.add("r1", at = tenth)
        rig.recordings.add("r2", at = twelfth)
        rig.theme("Garden shed", "r1" to listOf("The roof leaks."))
        rig.theme("Rent", "r2" to listOf("It goes up in March."))

        assertEquals(Outcome.Written(2), rig.refresh(listOf("Garden shed", "Rent")))

        assertEquals(listOf("mind summary prompt (en)", "mind summary prompt (en)"), rig.llm.summaryCalls.map { it.systemPrompt })
        assertEquals(
            listOf("Garden shed\n10 Oct 2026: The roof leaks.", "Rent\n12 Oct 2026: It goes up in March."),
            rig.llm.summaryCalls.map { it.text }
        )
    }

    @Test
    fun `a theme whose recordings hold no points is not written and costs no model call`() {
        val rig = Rig()
        rig.recordings("r1")
        rig.theme("Quiet", "r1" to emptyList())

        assertEquals(Outcome.Written(0), rig.refresh(listOf("Quiet")))

        assertTrue(rig.llm.calls.isEmpty())
        assertTrue(rig.progress.isEmpty())
    }

    @Test
    fun `a theme with nothing to sum up takes no place within the limit`() {
        val rig = Rig()
        rig.recordings("r1", "r2")
        rig.theme("Quiet", "r2" to emptyList())
        rig.theme("Stale", "r1" to listOf("It changed."), summary = "Old.", stale = true)

        assertEquals(Outcome.Written(1), rig.refresh(limit = 1))

        assertEquals("About Stale.", rig.summaryOf("Stale"))
        assertEquals(listOf(1 to 1), rig.progress)
    }

    @Test
    fun `the newest recording's own points choose the language, and only it may get the language stored`() {
        val rig = Rig()
        rig.recordings.add("older", at = tenth)
        rig.recordings.add("newer", at = twelfth)
        rig.theme(
            "Miete",
            "older" to listOf("The rent goes up by fifty euros in March, which is quite a lot for us."),
            "newer" to listOf("Die Miete steigt im März um fünfzig Euro.", "Das ist nicht wenig für die Wohnung.")
        )

        rig.refresh()

        assertEquals("mind summary prompt (de)", rig.llm.summaryCalls.single().systemPrompt)
        assertEquals("de", rig.recordings.sessions.getValue("newer").outputLanguage)
        assertNull(rig.recordings.sessions.getValue("older").outputLanguage)
    }

    @Test
    fun `the summary is stored tidied, with the time, and its stale mark is cleared`() {
        val rig = Rig()
        rig.recordings("r1")
        rig.theme("Shed", "r1" to listOf("The roof leaks."), summary = "Old.", stale = true)
        rig.llm.summary = { """{"summary": "**The roof leaks.**\n\nIt gets fixed in spring."}""" }

        assertEquals(Outcome.Written(1), rig.refresh())

        val theme = rig.map.nodes.getValue("Shed")
        assertEquals("The roof leaks. It gets fixed in spring.", theme.summary)
        assertEquals(500L to 500L, theme.summaryUpdatedAt to theme.updatedAt)
        assertFalse(theme.summaryStale)
    }

    @Test
    fun `an answer that can't be read leaves its theme stale, and the next one is still written`() {
        val rig = Rig()
        rig.recordings("r1", "r2")
        rig.theme("A", "r2" to listOf("a"), summary = "Old A.", stale = true)
        rig.theme("B", "r1" to listOf("b"))
        rig.llm.summary = { text -> if (text.startsWith("A")) """{"summary": "cut o""" else """{"summary": "About B."}""" }

        assertEquals(Outcome.Written(1), rig.refresh())

        assertEquals("Old A.", rig.summaryOf("A"))
        assertTrue(rig.map.nodes.getValue("A").summaryStale)
        assertEquals("About B.", rig.summaryOf("B"))
    }

    @Test
    fun `a model that can't be loaded ends the call`() {
        val rig = Rig()
        rig.recordings("r1")
        rig.theme("A", "r1" to listOf("a"))
        rig.llm.reloadFailure = IllegalStateException("no model")

        assertEquals(Outcome.ModelFailed(0), rig.refresh())

        assertTrue(rig.llm.calls.isEmpty())
    }

    @Test
    fun `a model that fails while answering ends the call with the summaries written so far`() {
        val rig = Rig()
        rig.recordings("r1", "r2", "r3")
        rig.theme("A", "r3" to listOf("a"))
        rig.theme("B", "r2" to listOf("b"))
        rig.theme("C", "r1" to listOf("c"))
        rig.llm.summary = { text -> if (text.startsWith("B")) null else """{"summary": "Fine."}""" }

        assertEquals(Outcome.ModelFailed(1), rig.refresh())

        assertEquals("Fine.", rig.summaryOf("A"))
        assertNull(rig.summaryOf("B"))
        assertEquals(2, rig.llm.calls.size)
    }

    @Test
    fun `nothing is written past a limit of none, nor for a theme without recordings or an unknown one`() {
        val rig = Rig()
        rig.recordings("r1")
        rig.theme("A", "r1" to listOf("a"))
        rig.theme("Empty")

        assertEquals(Outcome.Written(0), rig.refresh(limit = 0))
        assertEquals(Outcome.Written(1), rig.refresh(themeIds = listOf("unknown", "Empty"), limit = 5))

        assertEquals(listOf(1 to 1), rig.progress)
        assertNull(rig.summaryOf("Empty"))
    }

    @Test
    fun `a stop is asked for before each summary but the first, and ends the call with what was written`() {
        val rig = Rig()
        rig.recordings("r1", "r2", "r3")
        rig.theme("A", "r1" to listOf("a"))
        rig.theme("B", "r2" to listOf("b"))
        rig.theme("C", "r3" to listOf("c"))
        var asked = 0

        val outcome = runBlocking { rig.useCase(limit = Int.MAX_VALUE, shouldStop = { ++asked == 2 }) }

        assertEquals(Outcome.Written(2), outcome)
        assertEquals(2, asked)
        assertEquals("the theme left stays without a summary", 1, listOf("A", "B", "C").count { rig.summaryOf(it) == null })
        // The first summary is written even when the call would stop at once.
        assertEquals(Outcome.Written(1), runBlocking { rig.useCase(limit = Int.MAX_VALUE, shouldStop = { true }) })
        assertTrue(listOf("A", "B", "C").none { rig.summaryOf(it) == null })
    }

    @Test
    fun `with no limit every theme that needs one is written`() {
        val rig = Rig()
        rig.recordings("r1", "r2", "r3", "r4", "r5")
        for (id in listOf("A", "B", "C", "D", "E")) rig.theme(id, "r1" to listOf(id))

        assertEquals(Outcome.Written(5), rig.refresh(limit = Int.MAX_VALUE))
    }

    @Test
    fun `a database error ends the call like a failed model`() {
        val rig = Rig()
        rig.recordings("r1")
        rig.theme("A", "r1" to listOf("a"))
        rig.map.failOn = "summary"

        assertEquals(Outcome.ModelFailed(0), rig.refresh())
    }

    @Test
    fun `cancellation is passed on and nothing is stored`() {
        val cancelled = object : LlmRepository by MindLlm() {
            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
                flow { throw CancellationException("the screen is gone") }
        }
        val rig = Rig(cancelled)
        rig.recordings("r1")
        rig.theme("A", "r1" to listOf("a"))

        assertThrows(CancellationException::class.java) { rig.refresh() }

        assertNull(rig.summaryOf("A"))
    }
}
