package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.satori.domain.model.TranscriptionSegment
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * The transcript. A line that is still being heard is ash with a dot cursor and turns ink when
 * it is complete; completed lines never change colour again, not even when recording stops.
 *
 * The newest line is at the bottom and the list follows the speaker: while the newest line is in
 * view, each new word and each new line shows as it arrives, also when the line still being heard
 * grows taller than the list. Moving the list away from the newest line pauses this, so earlier
 * lines can be read while new ones gather below without moving them. The "Latest" pill then goes
 * back to the newest line, as does scrolling back down to it, and the list follows again. The
 * whole transcript stays reachable by scrolling; until the lines fill the space they start at the
 * top.
 *
 * @param segments The lines, oldest first; only the last one can still be being heard
 * @param emptyHint What to say while there are no lines yet, matching what is happening
 *   ("Listening", "Getting ready"); null says nothing, for when an error or a dialog does.
 * @param contentPadding Around the lines and the hint; the cover screen's column has margins of its own
 */
@Composable
fun TranscriptionSection(
    segments: List<TranscriptionSegment>,
    isRecording: Boolean,
    emptyHint: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
) {
    val listState = rememberLazyListState()
    // Whether the end of the newest line is in view. Derived, so scrolling recomposes this only
    // when it flips.
    val atNewest by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    // Outlives the pill, which leaves as soon as the list is back at the newest line.
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()

    // Following. On its own a lazy list keeps the line at its bottom in place when lines are added
    // below it, so a new line would arrive out of view. While the newest line is in view, every
    // change asks for it again here, after the change is composed and before the list measures
    // it, so the list never shows a frame without it. Never while the list moves, which would stop
    // a finger, TalkBack or the pill mid-scroll, and not before the list has been measured: until
    // then it can't tell where it is, and a place restored after the activity was recreated
    // (a font size change) would be lost.
    SideEffect {
        val measured = listState.layoutInfo.totalItemsCount > 0
        if (measured && segments.isNotEmpty() && !listState.canScrollBackward && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }

    Box(modifier = modifier) {
        if (segments.isEmpty()) {
            if (emptyHint != null) {
                Text(
                    text = emptyHint,
                    style = Grille.type.body,
                    color = Grille.colors.ash,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                // Item 0, the newest line, sits at the bottom, and a growing line grows upwards.
                reverseLayout = true,
                contentPadding = contentPadding
            ) {
                itemsIndexed(
                    items = segments.asReversed(),
                    key = { index, segment -> TranscriptLines.keyOf(segment, isNewest = index == 0) }
                ) { _, segment ->
                    TranscriptLine(segment, live = isRecording && !segment.isComplete)
                }
            }
            if (!atNewest) {
                LatestPill(
                    onClick = {
                        scope.launch {
                            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = GrilleSpace.s)
                )
            }
        }
    }
}

/** One line of the transcript; [live] while it is still being heard and recording goes on. */
@Composable
private fun TranscriptLine(segment: TranscriptionSegment, live: Boolean) {
    Text(
        text = buildAnnotatedString {
            append(segment.text)
            if (live) withStyle(SpanStyle(color = Grille.colors.ink)) { append("  ●") }
        },
        style = Grille.type.transcript,
        color = if (segment.isComplete) Grille.colors.ink else Grille.colors.ash,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // The cursor is only for the eye; a screen reader reads the words.
            .semantics { if (live) contentDescription = segment.text }
    )
}

/**
 * Brings back the newest line of a transcript that was moved away from it. Raised on graphite
 * like a notice, so the words under it don't show through, and at least 48 dp tall like every
 * pill, whatever the font size.
 */
@Composable
private fun LatestPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        text = stringResource(R.string.g_transcript_latest),
        onClick = onClick,
        modifier = modifier.background(Grille.colors.graphite, CircleShape),
        kind = PillKind.Outline,
        contentPadding = PaddingValues(horizontal = GrilleSpace.m),
        contentDescription = stringResource(R.string.g_transcript_latest_cd)
    )
}
