package com.satori.domain.usecase.transcription

import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SearchMatchSource
import com.satori.domain.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGroupsTest {

    private fun hit(
        sessionId: String,
        source: SearchMatchSource,
        snippet: String,
        createdAt: Long = 1_000L,
        name: String? = "Name of $sessionId",
        category: String? = null,
        highlightId: String? = null,
        query: String = "budget"
    ) = SearchResult(
        sessionId = sessionId,
        sessionName = name,
        mode = RecordingMode.SIMPLE_LISTENING,
        createdAt = createdAt,
        snippet = snippet,
        matchQuery = query,
        matchSource = source,
        highlightId = highlightId,
        categoryName = category
    )

    private fun line(sessionId: String, snippet: String, id: String = "$sessionId-${snippet.hashCode()}", at: Long = 1_000L) =
        hit(sessionId, SearchMatchSource.TRANSCRIPTION, snippet, createdAt = at, highlightId = id)

    private fun note(sessionId: String, snippet: String, id: String = "$sessionId-n", at: Long = 1_000L) =
        hit(sessionId, SearchMatchSource.INSIGHT, snippet, createdAt = at, highlightId = id)

    private fun nameHit(sessionId: String, at: Long = 1_000L) =
        hit(sessionId, SearchMatchSource.SESSION_NAME, "Name of $sessionId", createdAt = at)

    @Test
    fun `the hits of one recording become one row`() {
        val groups = SearchGroups.of(
            listOf(
                line("a", "the budget for Q4"),
                note("a", "Q4 budget status"),
                nameHit("a"),
                line("b", "no budget left")
            )
        )

        assertEquals(setOf("a", "b"), groups.map { it.sessionId }.toSet())
        assertEquals(3, groups.first { it.sessionId == "a" }.matchCount)
        assertEquals(1, groups.first { it.sessionId == "b" }.matchCount)
    }

    @Test
    fun `a name match is flagged and shows no snippet of its own`() {
        val group = SearchGroups.of(listOf(nameHit("a"))).single()

        assertTrue(group.nameMatches)
        assertNull(group.best)
        assertEquals(1, group.matchCount)
    }

    @Test
    fun `the row shows the snippet with the most matches`() {
        val group = SearchGroups.of(
            listOf(
                line("a", "the budget is fine", id = "one"),
                line("a", "budget here, budget there", id = "two")
            )
        ).single()

        assertEquals("two", group.best?.highlightId)
    }

    @Test
    fun `between equal snippets a line said comes before a note, then the first returned`() {
        val group = SearchGroups.of(
            listOf(
                note("a", "budget note", id = "note"),
                line("a", "budget line one", id = "first"),
                line("a", "budget line two", id = "second")
            )
        ).single()

        assertEquals("first", group.best?.highlightId)
    }

    @Test
    fun `a note is shown when no line matched`() {
        val group = SearchGroups.of(listOf(note("a", "Q4 budget status", id = "note"), nameHit("a"))).single()

        assertEquals("note", group.best?.highlightId)
    }

    @Test
    fun `recordings with a name match come first, then more matches, then the newer`() {
        val groups = SearchGroups.of(
            listOf(
                line("many-old", "budget one", at = 100L),
                line("many-old", "budget two", at = 100L),
                line("many-old", "budget three", at = 100L),
                line("few-new", "budget", at = 900L),
                line("two-new", "budget one", at = 900L),
                line("two-new", "budget two", at = 900L),
                line("two-old", "budget one", at = 200L),
                line("two-old", "budget two", at = 200L),
                nameHit("named", at = 50L)
            )
        )

        assertEquals(listOf("named", "many-old", "two-new", "two-old", "few-new"), groups.map { it.sessionId })
    }

    @Test
    fun `recordings that tie on everything keep a stable order`() {
        val groups = SearchGroups.of(listOf(line("b", "budget", at = 5L), line("a", "budget", at = 5L)))

        assertEquals(listOf("a", "b"), groups.map { it.sessionId })
    }

    @Test
    fun `the title is the name, else the category, else the mode`() {
        val named = SearchGroups.of(listOf(line("a", "budget"))).single()
        val inCategory = SearchGroups.of(listOf(hit("b", SearchMatchSource.TRANSCRIPTION, "budget", name = " ", category = "Meeting"))).single()
        val plain = SearchGroups.of(listOf(hit("c", SearchMatchSource.TRANSCRIPTION, "budget", name = null))).single()

        assertEquals(RecordingTitle("Name of a", null, RecordingMode.SIMPLE_LISTENING), named.title)
        assertEquals(RecordingTitle(null, "Meeting", RecordingMode.SIMPLE_LISTENING), inCategory.title)
        assertEquals(RecordingTitle(null, null, RecordingMode.SIMPLE_LISTENING), plain.title)
        assertFalse(plain.nameMatches)
    }

    @Test
    fun `no hits give no rows`() {
        assertTrue(SearchGroups.of(emptyList()).isEmpty())
    }

    @Test
    fun `a recording found by the place it was made shows the place`() {
        val group = SearchGroups.of(listOf(hit("a", SearchMatchSource.PLACE, "Köln", query = "Köln"))).single()

        assertEquals(SearchMatchSource.PLACE, group.best?.matchSource)
        assertEquals("Köln", group.best?.snippet)
        assertFalse(group.nameMatches)
        assertEquals(1, group.matchCount)
    }

    @Test
    fun `a line said or a note naming the place comes before the place itself`() {
        val place = hit("a", SearchMatchSource.PLACE, "Köln", query = "Köln")
        val withNote = SearchGroups.of(listOf(place, hit("a", SearchMatchSource.INSIGHT, "Trip to Köln", highlightId = "note", query = "Köln"))).single()
        val withLine = SearchGroups.of(listOf(place, hit("a", SearchMatchSource.TRANSCRIPTION, "back in Köln", highlightId = "line", query = "Köln"))).single()

        assertEquals("note", withNote.best?.highlightId)
        assertEquals("line", withLine.best?.highlightId)
        assertEquals(2, withLine.matchCount)
    }
}
