package com.satori.presentation.search

import com.satori.domain.usecase.transcription.SearchGroup

/**
 * UI state for the search screen.
 *
 * @param query      Current text in the search bar, as typed.
 * @param groups     One row per recording that matches the trimmed query, best first.
 * @param tooShort   The trimmed query is too short to search; the screen shows its hint.
 * @param hasSearched True once a search for the current query has come back (distinguishes
 *                    "still searching" from "searched but nothing found").
 */
data class SearchUiState(
    val query: String = "",
    val groups: List<SearchGroup> = emptyList(),
    val tooShort: Boolean = true,
    val hasSearched: Boolean = false
)
