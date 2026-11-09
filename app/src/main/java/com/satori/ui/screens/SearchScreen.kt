package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.SearchMatchSource
import com.satori.domain.usecase.transcription.SearchGroup
import com.satori.presentation.search.SearchViewModel
import com.satori.ui.R
import com.satori.ui.components.recordingTitleText
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.Hairline
import com.satori.ui.design.relativeDateTime

/**
 * Search across transcripts, AI notes, recording names and the places recordings were made.
 *
 * The top bar holds back, the search field (focused when the screen first opens) and a
 * clear button. Results are hairline rows, one for each recording: its title, the best snippet
 * (a line said or a note) in ash with the match in bold ink, and when it was made with how many
 * places matched. Titles wrap, so a row also fits the cover screen.
 *
 * @param onNavigateBack Navigate back to the calling screen.
 * @param onNavigateToSession Open [SessionDetailsScreen] for a session, with the matched segment or
 *   insight to highlight and the tab to open (0 transcript, 1 insights).
 * @param viewModel Injected [SearchViewModel].
 */
@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (sessionId: String, highlightId: String?, initialTab: Int) -> Unit = { _, _, _ -> },
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Focus once when the screen opens, not again when coming back from a result.
    var focusedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnOpen) {
            focusedOnOpen = true
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = GrilleSpace.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = GrilleIcons.Back,
                contentDescription = stringResource(R.string.g_back),
                onClick = onNavigateBack
            )
            // The Search key only closes the keyboard: results follow the typing. Nothing is
            // capitalised, as in the previous search field.
            GrilleTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.g_search_hint),
                imeAction = ImeAction.Search,
                capitalization = KeyboardCapitalization.None,
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GrilleSpace.s)
                    .focusRequester(focusRequester)
            )
            if (uiState.query.isNotEmpty()) {
                CircleIconButton(
                    icon = GrilleIcons.Close,
                    contentDescription = stringResource(R.string.g_search_clear),
                    onClick = {
                        viewModel.clearQuery()
                        focusRequester.requestFocus()
                    }
                )
            } else {
                // Holds the clear button's place so the underline keeps its length.
                Spacer(Modifier.size(GrilleSpace.touch))
            }
        }

        // imePadding keeps the last results above the keyboard, which matters on the cover screen.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
        ) {
            when {
                uiState.tooShort -> SearchNote(stringResource(R.string.g_search_prompt))

                uiState.hasSearched && uiState.groups.isEmpty() ->
                    SearchNote(stringResource(R.string.g_search_none))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = GrilleSpace.s, bottom = GrilleSpace.m)
                ) {
                    items(uiState.groups, key = { it.sessionId }) { group ->
                        SearchGroupRow(
                            group = group,
                            onClick = {
                                val best = group.best
                                val tab = if (best?.matchSource == SearchMatchSource.INSIGHT) 1 else 0
                                onNavigateToSession(group.sessionId, best?.highlightId, tab)
                            }
                        )
                        Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                    }
                }
            }
        }
    }
}

/**
 * One recording: title, the best snippet, and when it is from and how often it matched. A
 * recording found by the place it was made shows the place as its snippet ("Place: Köln"), with
 * the match marked in the place's name and never in the word before it.
 */
@Composable
private fun SearchGroupRow(group: SearchGroup, onClick: () -> Unit) {
    val ink = Grille.colors.ink
    val titleText = recordingTitleText(group.title)
    val title = remember(titleText, group.query, group.nameMatches, ink) {
        if (group.nameMatches) highlightMatches(titleText, group.query, ink) else AnnotatedString(titleText)
    }
    val placeLabel = stringResource(R.string.g_place_label)
    val snippet = group.best?.let { best ->
        remember(best.snippet, best.matchSource, group.query, ink, placeLabel) {
            val matches = highlightMatches(best.snippet, group.query, ink)
            if (best.matchSource == SearchMatchSource.PLACE) AnnotatedString("$placeLabel: ") + matches else matches
        }
    }
    val meta = stringResource(
        R.string.g_home_meta,
        relativeDateTime(group.createdAt),
        pluralStringResource(R.plurals.g_search_matches, group.matchCount, group.matchCount)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        Text(
            text = title,
            style = Grille.type.body,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (snippet != null) {
            Text(
                text = snippet,
                style = Grille.type.body,
                color = Grille.colors.ash,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = meta, style = Grille.type.caption, color = Grille.colors.ash)
    }
}

/** The hint before there is anything to search for, and the no-results sentence: one line each. */
@Composable
private fun SearchNote(text: String) {
    Text(
        text = text,
        style = Grille.type.body,
        color = Grille.colors.ash,
        modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    )
}

/**
 * Marks every case-insensitive occurrence of [query] in [snippet] bold and in [matchColor].
 * Matching works on the original string, so the indices stay valid for any character.
 */
private fun highlightMatches(snippet: String, query: String, matchColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(snippet)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < snippet.length) {
            val index = snippet.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (index < 0) {
                append(snippet.substring(cursor))
                break
            }
            append(snippet.substring(cursor, index))
            withStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.Bold)) {
                append(snippet.substring(index, index + query.length))
            }
            cursor = index + query.length
        }
    }
}
