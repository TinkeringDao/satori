package com.satori.domain.usecase.transcription

import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SearchMatchSource
import com.satori.domain.model.SearchResult

/**
 * One recording in the search results: where the query matched in it, boiled down to a row.
 *
 * @property nameMatches The query is in the recording's name
 * @property matchCount How many places matched: lines said, notes, the name and the name of the
 *   place it was made
 * @property best The hit whose snippet to show (a line said, a note or the place it was made),
 *   or null when only the name matched
 */
data class SearchGroup(
    val sessionId: String,
    val title: RecordingTitle,
    val createdAt: Long,
    val query: String,
    val nameMatches: Boolean,
    val matchCount: Int,
    val best: SearchResult?
)

/** Turns the hits of a search into one row per recording, best first. */
object SearchGroups {

    /**
     * One [SearchGroup] per recording in [results], ordered by relevance, then by date: a
     * recording whose name matches comes first, then the one with more matches, then the newer.
     */
    fun of(results: List<SearchResult>): List<SearchGroup> =
        results.groupBy { it.sessionId }
            .map { (sessionId, hits) -> group(sessionId, hits) }
            .sortedWith(
                compareByDescending<SearchGroup> { it.nameMatches }
                    .thenByDescending { it.matchCount }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.sessionId }
            )

    private fun group(sessionId: String, hits: List<SearchResult>): SearchGroup {
        val first = hits.first()
        return SearchGroup(
            sessionId = sessionId,
            title = RecordingTitle.of(first.sessionName, first.categoryName, first.mode),
            createdAt = first.createdAt,
            query = first.matchQuery,
            nameMatches = hits.any { it.matchSource == SearchMatchSource.SESSION_NAME },
            matchCount = hits.size,
            best = best(hits)
        )
    }

    /**
     * The snippet that shows the query most often; between equals, a line said before a note and
     * a note before the place the recording was made (which says the least of what is in it),
     * then the one the search returned first (the earliest line of the recording).
     */
    private fun best(hits: List<SearchResult>): SearchResult? =
        hits.filter { it.matchSource != SearchMatchSource.SESSION_NAME }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<SearchResult>> { occurrences(it.value) }
                    .thenBy { SNIPPET_ORDER.indexOf(it.value.matchSource) }
                    .thenBy { it.index }
            )
            .firstOrNull()
            ?.value

    /** The sources of a snippet, preferred first; a name match has none. */
    private val SNIPPET_ORDER = listOf(SearchMatchSource.TRANSCRIPTION, SearchMatchSource.INSIGHT, SearchMatchSource.PLACE)

    private fun occurrences(hit: SearchResult): Int {
        if (hit.matchQuery.isEmpty()) return 0
        var count = 0
        var from = hit.snippet.indexOf(hit.matchQuery, ignoreCase = true)
        while (from >= 0) {
            count++
            from = hit.snippet.indexOf(hit.matchQuery, from + hit.matchQuery.length, ignoreCase = true)
        }
        return count
    }
}
