package com.satori.domain.usecase.mindmap

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.mindmap.MapRecordingUseCase.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRecordingUseCaseTest {

    private val shedTalk = "The roof of the garden shed leaks again."
    private val rentTalk = "Our rent goes up by fifty euros in March."

    /**
     * Recordings, the map and the model, with the use case over them; node ids are n1, n2, ... The
     * map's calls show its transaction and its marks of recordings read without topics.
     */
    private class Rig(model: LlmRepository? = null) {
        val recordings = FakeRecordings()
        val map = FakeMindMapRepository(recordings)
        val llm = MindLlm()
        private var ids = 0
        val useCase = MapRecordingUseCase(
            recordings,
            TransactionalMindMap(map),
            model ?: llm,
            FakeResourceProvider(),
            NotesLanguageResolver(FakeSettingsRepository(), recordings) { "en" },
            now = { 100L },
            newId = { "n${++ids}" }
        )

        fun map(sessionId: String = "s1") = runBlocking { useCase(sessionId) }
    }

    @Test
    fun `each topic goes into a theme of its own, linked with its points and the segments it came from`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, rentTalk)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks."), "Rent" to listOf("It goes up in March.")) }

        assertEquals(Outcome.Mapped(listOf("n1", "n3")), rig.map())

        val shed = rig.map.nodes.getValue("n1")
        assertEquals(MindNodeKind.THEME, shed.kind)
        assertEquals("Garden shed" to "gard shed", shed.label to shed.matchKey)
        assertEquals("n1", rig.map.nodes.getValue("n2").parentId)
        assertEquals(listOf("Garden shed" to listOf("The roof leaks."), "Rent" to listOf("It goes up in March.")), rig.map.linksOf("s1"))
        val links = runBlocking { rig.map.nodesForSession("s1").first() }.map { it.link }
        assertEquals(listOf(listOf("s1-seg1"), listOf("s1-seg2")), links.map { it.segmentIds })
        assertEquals(listOf(100L, 100L), links.map { it.createdAt })
        assertTrue(rig.map.nodes.getValue("n1").summaryStale && rig.map.nodes.getValue("n3").summaryStale)
    }

    @Test
    fun `the old links go first, the map is tidied last, all in one transaction that reads the nodes once`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, rentTalk)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks."), "Rent" to emptyList()) }

        rig.map()

        assertEquals(
            listOf(
                "begin", "forget s1", "nodes", "upsert n1", "upsert n2", "upsert n3", "upsert n4",
                "link n2 s1", "link n4 s1", "stale [n1, n3]", "cleanup", "commit"
            ),
            rig.map.calls
        )
    }

    // The fake's rollback undoes nothing; that the database's does is MindMapRepositoryImplTest's.
    @Test
    fun `a failure half-way ends the store's transaction in a rollback`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, rentTalk)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks."), "Rent" to emptyList()) }
        rig.map.failOn = "link"

        assertEquals(Outcome.Failed, rig.map())

        assertEquals(
            listOf("begin", "forget s1", "nodes", "upsert n1", "upsert n2", "upsert n3", "upsert n4", "link n2 s1", "rollback"),
            rig.map.calls
        )
    }

    @Test
    fun `a Malayalam transcript without notes is read without topics at once, and the model isn't asked`() {
        val rig = Rig()
        // The roof of the garden shed leaks again; the repair has to be booked before winter.
        val malayalam = "തോട്ടത്തിലെ ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു, ശൈത്യകാലത്തിന് മുമ്പ് റിപ്പയർ ബുക്ക് ചെയ്യണം"
        rig.recordings.add("s1", at = 1_000L, malayalam)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks.")) }

        assertEquals(Outcome.Mapped(emptyList()), rig.map())

        assertTrue(rig.llm.calls.isEmpty())
        assertEquals(listOf("begin", "forget s1", "skip s1 ${malayalam.codePointCount(0, malayalam.length)}", "cleanup", "commit"), rig.map.calls)
    }

    @Test
    fun `a Malayalam recording is mapped from its English notes, its labels found in them`() {
        val rig = Rig()
        // The roof of the garden shed leaks again.
        rig.recordings.add("s1", at = 1_000L, "തോട്ടത്തിലെ ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു")
        rig.recordings.note("s1", "Garden shed", "- The roof of the garden shed leaks again.", at = 2_000L)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks.")) }

        assertEquals(Outcome.Mapped(listOf("n1")), rig.map())

        assertEquals("Garden shed: - The roof of the garden shed leaks again.", rig.llm.topicCalls.single().text)
        assertEquals(listOf("Garden shed" to listOf("The roof leaks.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a recording in which no topic holds up is marked as read without topics, with the length read`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { """{"topics": []}""" }

        assertEquals(Outcome.Mapped(emptyList()), rig.map())
        assertEquals(listOf("begin", "forget s1", "skip s1 ${rentTalk.length}", "cleanup", "commit"), rig.map.calls)

        rig.map.calls.clear()
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks.")) }
        assertEquals(Outcome.Mapped(emptyList()), rig.map())
        assertTrue("skip s1 ${rentTalk.length}" in rig.map.calls)
    }

    @Test
    fun `the mark holds the length the transcript had when it was read, not after an edit meanwhile`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, "  $rentTalk ")
        rig.llm.topics = {
            rig.recordings.segmentList[0] = rig.recordings.segmentList[0].copy(text = "$rentTalk And the heating too.")
            """{"topics": []}"""
        }

        rig.map()

        // Counted as the database counts it: the spaces at the ends of a segment don't count.
        assertTrue("skip s1 ${rentTalk.length}" in rig.map.calls)
    }

    @Test
    fun `an unreadable answer, a failed model or a short recording is not marked as read`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.recordings.add("short", at = 2_000L, "Testing.")

        rig.llm.topics = { "Sorry, I can't help with that." }
        assertEquals(Outcome.Unreadable, rig.map())
        rig.llm.topics = { null }
        assertEquals(Outcome.ModelFailed, rig.map())
        assertEquals(Outcome.Skipped, rig.map("short"))

        assertTrue(rig.map.calls.none { it.startsWith("skip") })
    }

    @Test
    fun `the model reads the transcript under the topics prompt and the grammar`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, rentTalk)

        rig.map()

        val call = rig.llm.topicCalls.single()
        assertEquals("$shedTalk $rentTalk", call.text)
        assertEquals("mind topics prompt (en)", call.systemPrompt)
        assertEquals(OutputGrammar.JsonSchema(MindTopics.SCHEMA), call.grammar)
    }

    @Test
    fun `two recordings get the same system prompt, each with its own text in the user message`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk)
        rig.recordings.add("s2", at = 2_000L, rentTalk)

        rig.map("s1")
        rig.map("s2")

        assertEquals(listOf("mind topics prompt (en)", "mind topics prompt (en)"), rig.llm.topicCalls.map { it.systemPrompt })
        assertEquals(listOf(shedTalk, rentTalk), rig.llm.topicCalls.map { it.text })
    }

    @Test
    fun `the model reads the notes when there are some, and a label may come from them`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, "We talked about money for a while, nothing more.")
        rig.recordings.note("s1", "Budget for 2027", "Spending stays flat next year.", at = 5_000L)
        rig.llm.topics = { topicsAnswer("Budget 2027" to listOf("Spending stays flat.")) }

        assertEquals(Outcome.Mapped(listOf("n1")), rig.map())

        assertEquals("Budget for 2027: Spending stays flat next year.", rig.llm.topicCalls.single().text)
        val link = runBlocking { rig.map.nodesForSession("s1").first() }.single().link
        assertTrue("no segment says it", link.segmentIds.isEmpty())
    }

    @Test
    fun `a topic of a later recording joins the theme it shares a rare word with`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, "The heating repair is booked for Monday morning.")
        rig.recordings.add("s2", at = 2_000L, "The heating costs went up a lot this winter.")
        rig.llm.topics = { text -> if (text.contains("repair")) topicsAnswer("Heating repair" to emptyList()) else topicsAnswer("Heating costs" to emptyList()) }

        rig.map("s1")
        assertEquals(Outcome.Mapped(listOf("n1")), rig.map("s2"))

        assertEquals("n1", rig.map.named("Heating costs", MindNodeKind.TOPIC).parentId)
        assertEquals(1, rig.map.nodes.values.count { it.kind == MindNodeKind.THEME })
    }

    @Test
    fun `the same topic in another recording goes onto the same topic node`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk)
        rig.recordings.add("s2", at = 2_000L, "Both garden sheds need paint before the winter.")
        rig.llm.topics = { text -> if (text.contains("roof")) topicsAnswer("Garden shed" to emptyList()) else topicsAnswer("garden sheds" to emptyList()) }

        rig.map("s1")
        rig.map("s2")

        val topic = rig.map.named("Garden shed", MindNodeKind.TOPIC)
        assertEquals(2, runBlocking { rig.map.links(topic.id).first() }.size)
        assertEquals(2, rig.map.nodes.size)
    }

    @Test
    fun `a topic the recording doesn't speak of is dropped`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Garden shed" to listOf("The roof leaks."), "Rent" to listOf("It goes up.")) }

        assertEquals(Outcome.Mapped(listOf("n1")), rig.map())

        assertEquals(listOf("Rent" to listOf("It goes up.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `two topics that land on one topic node make one link with the points of both`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, "The garden sheds are old and need paint.")
        rig.llm.topics = {
            topicsAnswer(
                "Garden shed" to listOf("The roof leaks.", "Boards cost 80 euros."),
                "The garden sheds" to listOf("the roof leaks", "Paint is needed.", "They are old.")
            )
        }

        assertEquals(Outcome.Mapped(listOf("n1")), rig.map())

        assertEquals(
            listOf("Garden shed" to listOf("The roof leaks.", "Boards cost 80 euros.", "Paint is needed.")),
            rig.map.linksOf("s1")
        )
    }

    @Test
    fun `mapping again replaces the links and keeps the theme with its pin and summary`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Rent" to listOf("It goes up.")) }
        rig.map()
        runBlocking {
            rig.map.setPinned("n1", true)
            rig.map.setSummary("n1", "The rent rises.", at = 50L)
        }

        rig.llm.topics = { topicsAnswer("Rent" to listOf("It goes up in March.")) }
        assertEquals(Outcome.Mapped(listOf("n1")), rig.map())

        val theme = rig.map.nodes.getValue("n1")
        assertTrue(theme.pinned)
        assertEquals("The rent rises.", theme.summary)
        assertTrue(theme.summaryStale)
        assertEquals(listOf("Rent" to listOf("It goes up in March.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a theme the recording no longer speaks of goes, unless another recording holds it`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, shedTalk, rentTalk)
        rig.recordings.add("s2", at = 2_000L, "The rent is due on the first of each month.")
        rig.llm.topics = { text ->
            if (text.contains("shed")) topicsAnswer("Garden shed" to emptyList(), "Rent" to emptyList()) else topicsAnswer("Rent" to emptyList())
        }
        rig.map("s1")
        rig.map("s2")
        runBlocking { rig.map.setSummary(rig.map.named("Rent").id, "Rent is due monthly.", at = 50L) }

        rig.llm.topics = { topicsAnswer("Garden shed" to emptyList()) }
        rig.map("s1")

        assertEquals(listOf("Garden shed" to emptyList<String>()), rig.map.linksOf("s1"))
        val rent = rig.map.named("Rent")
        assertTrue("it lost a recording", rent.summaryStale)
        rig.llm.topics = { topicsAnswer("Holiday" to emptyList()) }
        assertEquals(Outcome.Mapped(emptyList()), rig.map("s1"))
        assertTrue(rig.map.nodes.values.none { it.label == "Garden shed" })
        assertEquals(rent.id, rig.map.named("Rent").id)
    }

    @Test
    fun `an answer without a topic that holds up leaves the recording without links`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Rent" to emptyList()) }
        rig.map()

        rig.llm.topics = { """{"topics": []}""" }
        assertEquals(Outcome.Mapped(emptyList()), rig.map())

        assertTrue(rig.map.linksOf("s1").isEmpty())
        assertTrue(rig.map.nodes.isEmpty())
    }

    @Test
    fun `a recording with too little complete transcript is skipped without a model call`() {
        val rig = Rig()
        rig.recordings.add("short", at = 1_000L, "   ${"a".repeat(19)}  ", " ${"b".repeat(20)} ")
        rig.recordings.add("enough", at = 2_000L, "a".repeat(20), "b".repeat(20))
        rig.recordings.add("partial", at = 3_000L, "c".repeat(30))
        rig.recordings.segmentList += TranscriptionSegment("p2", "partial", "d".repeat(50), 3_500L, isComplete = false)

        assertEquals(Outcome.Skipped, rig.map("short"))
        assertEquals(Outcome.Skipped, rig.map("partial"))
        assertTrue(rig.llm.calls.isEmpty())
        assertEquals(Outcome.Mapped(emptyList()), rig.map("enough"))
        assertEquals(1, rig.llm.calls.size)
    }

    @Test
    fun `a recording that is gone is skipped`() {
        val rig = Rig()

        assertEquals(Outcome.Skipped, rig.map("gone"))
        assertTrue(rig.llm.calls.isEmpty())
        assertTrue(rig.map.calls.isEmpty())
    }

    @Test
    fun `German talk gets the German prompt, a Translate recording its target's`() {
        val rig = Rig()
        rig.recordings.add("de", at = 1_000L, "Die Miete steigt im März um fünfzig Euro, das ist nicht wenig.")
        rig.recordings.add("tr", at = 2_000L, rentTalk, mode = RecordingMode.REAL_TIME_TRANSLATION)
        rig.recordings.sessions["tr"] = rig.recordings.sessions.getValue("tr").copy(outputLanguage = "de")

        rig.map("de")
        rig.map("tr")

        assertEquals(listOf("mind topics prompt (de)", "mind topics prompt (de)"), rig.llm.calls.map { it.systemPrompt })
    }

    @Test
    fun `an answer that can't be read changes nothing`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Rent" to listOf("It goes up.")) }
        rig.map()
        rig.map.calls.clear()

        rig.llm.topics = { "Sorry, I can't help with that." }
        assertEquals(Outcome.Unreadable, rig.map())

        assertTrue(rig.map.calls.isEmpty())
        assertEquals(listOf("Rent" to listOf("It goes up.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a model that can't be loaded or run changes nothing`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Rent" to listOf("It goes up.")) }
        rig.map()
        rig.map.calls.clear()

        rig.llm.reloadFailure = IllegalStateException("no model")
        assertEquals(Outcome.ModelFailed, rig.map())
        assertEquals(1, rig.llm.calls.size)

        rig.llm.reloadFailure = null
        rig.llm.topics = { null }
        assertEquals(Outcome.ModelFailed, rig.map())

        assertTrue(rig.map.calls.isEmpty())
        assertEquals(listOf("Rent" to listOf("It goes up.")), rig.map.linksOf("s1"))
    }

    @Test
    fun `a database error is reported, not thrown`() {
        val rig = Rig()
        rig.recordings.add("s1", at = 1_000L, rentTalk)
        rig.llm.topics = { topicsAnswer("Rent" to emptyList()) }

        rig.map.failOn = "link"
        assertEquals(Outcome.Failed, rig.map())

        rig.map.failOn = null
        rig.recordings.readFailure = IllegalStateException("database closed")
        assertEquals(Outcome.Failed, rig.map())
    }

    @Test
    fun `cancellation is passed on and nothing is stored`() {
        val cancelled = object : LlmRepository by MindLlm() {
            override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
                flow { throw CancellationException("the screen is gone") }
        }
        val rig = Rig(cancelled)
        rig.recordings.add("s1", at = 1_000L, rentTalk)

        assertThrows(CancellationException::class.java) { rig.map() }

        assertTrue(rig.map.calls.isEmpty())
        assertTrue(rig.map.nodes.isEmpty())
    }
}
