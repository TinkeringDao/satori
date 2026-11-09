package com.satori.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.usecase.transcription.SearchGroup
import com.satori.domain.usecase.transcription.SearchGroups
import com.satori.domain.usecase.transcription.SearchTranscriptionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the search screen.
 *
 * The query is trimmed before it is searched: keyboards add a space after a suggestion, and
 * "budget " would miss "budget," and "budget.". The field keeps what was typed.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchTranscriptionsUseCase: SearchTranscriptionsUseCase
) : ViewModel() {

    private val _query = MutableStateFlow("")

    // Debounced results stream — DB is only queried 300 ms after the user stops typing
    private val _searchResults: Flow<Pair<List<SearchGroup>, Boolean>> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            if (q.length < MIN_QUERY_LENGTH) {
                flowOf(Pair(emptyList(), false))
            } else {
                searchTranscriptionsUseCase(q).map { results -> Pair(SearchGroups.of(results), true) }
            }
        }

    // Combine raw query (updates immediately on every keystroke) with debounced results so
    // the TextField always reflects what the user typed without lag.
    val uiState: StateFlow<SearchUiState> = combine(_query, _searchResults) { q, (groups, hasSearched) ->
        SearchUiState(
            query = q,
            groups = groups,
            tooShort = q.trim().length < MIN_QUERY_LENGTH,
            hasSearched = hasSearched
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState()
        )

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun clearQuery() {
        _query.value = ""
    }

    companion object {
        // Debounce delay to avoid firing a DB query on every keystroke
        private const val SEARCH_DEBOUNCE_MS = 300L

        // Minimum query length to avoid broad full-table scans
        private const val MIN_QUERY_LENGTH = 2
    }
}
