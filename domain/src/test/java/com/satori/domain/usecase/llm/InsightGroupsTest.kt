package com.satori.domain.usecase.llm

import com.satori.domain.fakes.session
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which note is the summary, in what order the others follow, and which action items each lists. */
class InsightGroupsTest {

    private fun note(
        id: String,
        at: Long,
        sources: List<String> = emptyList(),
        items: List<String> = emptyList()
    ) = LlmInsight(
        id = id,
        sessionId = "s1",
        title = id,
        content = "Text of $id.",
        tasks = if (items.isEmpty()) null else items.joinToString(",", "[", "]") { "\"$it\"" },
        timestamp = at,
        sourceSegmentIds = sources
    )

    private fun recording(
        strategy: InsightStrategy,
        mode: RecordingMode = RecordingMode.SHORT_MEETING
    ): TranscriptionSession = session().copy(insightStrategy = strategy, mode = mode)

    private fun ids(notes: List<LlmInsight>) = notes.map { it.id }

    // ─── The summary is pinned ───────────────────────────────────────────────────

    @Test
    fun `live notes are snapshots, so nothing is pinned and the oldest comes first`() {
        val groups = groupInsights(
            listOf(note("later", at = 20), note("earlier", at = 10)),
            recording(InsightStrategy.REAL_TIME)
        )

        assertEquals(emptyList<String>(), ids(groups.pinned))
        assertEquals(listOf("earlier", "later"), ids(groups.rest))
    }

    @Test
    fun `the note without sources of a recording written in one pass is its summary`() {
        val notes = listOf(
            note("part 2", at = 20, sources = listOf("a")),
            note("summary", at = 90),
            note("part 1", at = 10, sources = listOf("b"))
        )
        val groups = groupInsights(notes, recording(InsightStrategy.END_OF_SESSION))

        assertEquals(listOf("summary"), ids(groups.pinned))
        assertEquals(listOf("part 1", "part 2"), ids(groups.rest))
        // The summary is read first, though it is the newest note.
        assertEquals(listOf("summary", "part 1", "part 2"), ids(groups.all))
    }

    @Test
    fun `a recording written in one pass whose notes all have sources has no summary`() {
        val groups = groupInsights(
            listOf(note("part 2", at = 20, sources = listOf("a")), note("part 1", at = 10, sources = listOf("b"))),
            recording(InsightStrategy.END_OF_SESSION)
        )

        assertEquals(emptyList<String>(), ids(groups.pinned))
        assertEquals(listOf("part 1", "part 2"), ids(groups.rest))
    }

    @Test
    fun `a translation is running text and is never pinned`() {
        val groups = groupInsights(
            listOf(note("second half", at = 20), note("first half", at = 10)),
            recording(InsightStrategy.END_OF_SESSION, RecordingMode.REAL_TIME_TRANSLATION)
        )

        assertEquals(emptyList<String>(), ids(groups.pinned))
        assertEquals(listOf("first half", "second half"), ids(groups.rest))
    }

    @Test
    fun `no notes group to nothing`() {
        val groups = groupInsights(emptyList(), recording(InsightStrategy.END_OF_SESSION))
        assertEquals(emptyList<LlmInsight>(), groups.all)
    }

    // ─── Action items are said once ──────────────────────────────────────────────

    private val summary = note("summary", at = 90, items = listOf("Plan the user interviews", "Assign owners"))
    private val part = note(
        "part", at = 10, sources = listOf("a"),
        items = listOf("plan the user interviews", "Book the venue")
    )

    @Test
    fun `a part leaves out the action items the summary lists`() {
        val groups = groupInsights(listOf(summary, part), recording(InsightStrategy.END_OF_SESSION))
        assertEquals(listOf("Book the venue"), groups.actionItems(part))
    }

    @Test
    fun `the summary keeps all of its own action items`() {
        val groups = groupInsights(listOf(summary, part), recording(InsightStrategy.END_OF_SESSION))
        assertEquals(listOf("Plan the user interviews", "Assign owners"), groups.actionItems(summary))
    }

    @Test
    fun `without a summary every note lists its own action items`() {
        val earlier = note("earlier", at = 10, items = listOf("Call Anna"))
        val later = note("later", at = 20, items = listOf("Call Anna", "Book the venue"))
        val groups = groupInsights(listOf(earlier, later), recording(InsightStrategy.REAL_TIME))

        assertEquals(listOf("Call Anna"), groups.actionItems(earlier))
        assertEquals(listOf("Call Anna", "Book the venue"), groups.actionItems(later))
    }

    @Test
    fun `a note without action items lists none`() {
        val plain = note("plain", at = 10, sources = listOf("a"))
        val groups = groupInsights(listOf(summary, plain), recording(InsightStrategy.END_OF_SESSION))
        assertEquals(emptyList<String>(), groups.actionItems(plain))
    }
}
