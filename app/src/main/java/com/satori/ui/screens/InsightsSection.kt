package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.ui.material3.RichText
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.llm.NoteText
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/**
 * The AI notes for the current recording: hairline-separated blocks, newest last.
 *
 * @param recordingTitle The recording's name; a note titled the same shows no title, as on the
 *   details page.
 */
@Composable
fun InsightsSection(
    insights: List<LlmInsight>,
    segments: List<TranscriptionSegment>,
    modifier: Modifier = Modifier,
    recordingTitle: String? = null,
    isLlmEnabled: Boolean = true,
    isLlmAvailable: Boolean = true,
    isRecording: Boolean = false,
    insightStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
    onDownloadLlm: () -> Unit = {}
) {
    val segmentMap = remember(segments) { segments.associateBy { it.id } }
    Box(modifier = modifier) {
        when {
            !isLlmEnabled -> Note(stringResource(R.string.g_insights_off))

            !isLlmAvailable -> Column(
                modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l),
                verticalArrangement = Arrangement.spacedBy(GrilleSpace.m)
            ) {
                Text(stringResource(R.string.g_insights_missing), style = Grille.type.body, color = Grille.colors.ash)
                PillButton(
                    text = stringResource(R.string.g_insights_install),
                    onClick = onDownloadLlm,
                    kind = PillKind.Outline,
                    // Loading a ~1 GB model next to the running STT model risks running out of memory.
                    enabled = !isRecording
                )
                if (isRecording) {
                    Text(
                        stringResource(R.string.g_insights_install_busy),
                        style = Grille.type.caption,
                        color = Grille.colors.ash
                    )
                }
            }

            insightStrategy == InsightStrategy.END_OF_SESSION && isRecording && insights.isEmpty() ->
                Note(stringResource(R.string.g_insights_after))

            insights.isEmpty() -> Note(stringResource(R.string.g_insights_live_empty))

            else -> LazyColumn(contentPadding = PaddingValues(bottom = GrilleSpace.l)) {
                items(insights, key = { it.id }) { insight ->
                    InsightBlock(insight, segmentMap, recordingTitle = recordingTitle)
                    Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = Grille.type.body,
        color = Grille.colors.ash,
        modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    )
}

/**
 * One note, as the recorder and the details page both show it: a title, the text as Markdown,
 * action items and, where the note has sources, "Show what was said". The one place notes are
 * drawn, so a change to how they read is made once.
 *
 * It shows no clock time (when the AI wrote a note says nothing to the reader) and leaves out
 * a title that is blank or only repeats the recording's name.
 *
 * @param segmentMap The recording's transcript lines by id, where "Show what was said" finds
 *   its sources.
 * @param pinned Whether this is the recording's overall summary; it is labelled "Summary".
 * @param recordingTitle The recording's name as the page shows it; a note titled the same shows
 *   no title. Null where it has none yet (the recorder before the AI has named it).
 * @param actionItems The items to list; all of the note's own by default.
 * @param onEdit Opens the editor on a long press; null where notes can't be edited.
 */
@Composable
internal fun InsightBlock(
    insight: LlmInsight,
    segmentMap: Map<String, TranscriptionSegment>,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    recordingTitle: String? = null,
    actionItems: List<String> = remember(insight.tasks) { NoteText.actionItems(insight.tasks) },
    onEdit: (() -> Unit)? = null
) {
    val title = NoteText.visibleTitle(insight.title, recordingTitle)
    val text = remember(insight.content) { summaryText(insight.content) }
    val hasSources = insight.sourceSegmentIds.isNotEmpty()

    Column(modifier.fillMaxWidth()) {
        EditableColumn(
            onEdit = onEdit,
            modifier = Modifier.fillMaxWidth(),
            // The toggle below brings 48 dp of its own; without one the text needs more room.
            contentPadding = PaddingValues(
                start = GrilleSpace.margin,
                end = GrilleSpace.margin,
                top = GrilleSpace.l,
                bottom = if (hasSources) GrilleSpace.s else GrilleSpace.l
            )
        ) {
            if (pinned) {
                Text(
                    text = stringResource(R.string.g_details_overall),
                    style = Grille.type.label,
                    color = Grille.colors.ash
                )
            }
            if (title != null) {
                Text(text = title, style = Grille.type.heading, color = Grille.colors.ink)
            }
            if (text.isNotBlank()) {
                if (pinned || title != null) Spacer(Modifier.height(GrilleSpace.s))
                FormattedInsightText(text)
            }
            if (actionItems.isNotEmpty()) {
                Spacer(Modifier.height(GrilleSpace.m))
                Text(
                    text = stringResource(R.string.g_details_action_items),
                    style = Grille.type.label,
                    color = Grille.colors.ash
                )
                Spacer(Modifier.height(GrilleSpace.s))
                actionItems.forEach { item -> DotBullet(item) }
            }
        }
        if (hasSources) {
            SourceToggle(
                insight = insight,
                segmentMap = segmentMap,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin)
            )
        }
    }
}

/** "Show what was said": the lines of the transcript the note was written from. */
@Composable
private fun SourceToggle(
    insight: LlmInsight,
    segmentMap: Map<String, TranscriptionSegment>,
    modifier: Modifier = Modifier
) {
    var showSource by rememberSaveable(insight.id) { mutableStateOf(false) }
    Column(modifier) {
        Box(
            modifier = Modifier
                .heightIn(min = GrilleSpace.touch)
                .clickable(role = Role.Button) { showSource = !showSource }
                // TalkBack announces expanded or collapsed from which of the two actions is offered.
                .semantics {
                    if (showSource) {
                        collapse {
                            showSource = false
                            true
                        }
                    } else {
                        expand {
                            showSource = true
                            true
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = stringResource(if (showSource) R.string.g_source_hide else R.string.g_source_show),
                style = Grille.type.label,
                color = Grille.colors.ash
            )
        }
        if (showSource) {
            val sources = remember(insight.sourceSegmentIds, segmentMap) {
                insight.sourceSegmentIds.mapNotNull { segmentMap[it] }
            }
            Text(
                text = if (sources.isNotEmpty()) sources.joinToString(" ") { it.text }
                else stringResource(R.string.g_source_missing),
                style = Grille.type.body,
                color = Grille.colors.ash,
                modifier = Modifier.padding(bottom = GrilleSpace.l)
            )
        }
    }
}

/** An action item with a dot as its bullet. */
@Composable
internal fun DotBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = GrilleSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .background(Grille.colors.ink, CircleShape)
        )
        Text(text = text, style = Grille.type.body, color = Grille.colors.ink)
    }
}

/**
 * Renders insight text as Markdown (headings, lists, emphasis) with theme typography. Images and
 * links are shown as their text ([offlineMarkdown]).
 *
 * The text is parsed while composing, not a frame later as the library's `Markdown(content)`
 * does, so a note has its final height the first time it is measured. The details page places a
 * searched-for note by the heights of the rows above it; a body that grew afterwards would push
 * the note away from the spot it was placed at.
 */
@Composable
internal fun FormattedInsightText(content: String) {
    val ast = remember(content) { CommonmarkAstNodeParser().parse(offlineMarkdown(content)) }
    RichText(modifier = Modifier.fillMaxWidth()) {
        BasicMarkdown(ast)
    }
}
