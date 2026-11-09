package com.satori.ui.screens.mindmap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.usecase.llm.groupInsights
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.presentation.mindmap.AiState
import com.satori.presentation.mindmap.MapJob
import com.satori.presentation.mindmap.MapPage
import com.satori.presentation.mindmap.MapTarget
import com.satori.presentation.mindmap.MindMapUiState
import com.satori.presentation.mindmap.RecordingPoints
import com.satori.presentation.mindmap.SummaryState
import com.satori.presentation.mindmap.ThemeFilter
import com.satori.ui.R
import com.satori.ui.components.recordingTitleText
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.DotProgress
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.Hairline
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.formatDuration
import com.satori.ui.design.relativeDateTime
import com.satori.ui.screens.DotBullet
import com.satori.ui.screens.FormattedInsightText
import com.satori.ui.screens.SettingsSwitchRow
import com.satori.ui.screens.summaryText

// The page of a view of the map: under the drawing as its reading pane on the inner screen, or on
// its own as the list form (TalkBack, or "Show as list", on either screen). Both are the same
// rows, so the two forms never say different things.

/** What the page's rows and buttons do; the screen decides how. */
@Immutable
internal class MapActions(
    val onOpen: (MapTarget) -> Unit,
    val onFilter: (ThemeFilter) -> Unit,
    val onQuery: (String) -> Unit,
    val onMapOlder: () -> Unit,
    val onCancelOlder: () -> Unit,
    val onPin: (Boolean) -> Unit,
    val onUpdate: () -> Unit,
    val onMapRecording: () -> Unit
)

/** The rows of [state]'s page. */
internal fun LazyListScope.mapPageItems(state: MindMapUiState, actions: MapActions) {
    when (val page = state.page) {
        is MapPage.Root -> rootItems(page, state, actions)
        is MapPage.Theme -> themeItems(page, state, actions)
        is MapPage.Topic -> topicItems(page, state, actions)
        is MapPage.Recording -> recordingItems(page, state, actions)
        MapPage.Loading, MapPage.Missing -> Unit
    }
}

// ─── The top level ───────────────────────────────────────────────────────────

/**
 * The chips and the search of the top level. Above the drawing in the map form of the inner
 * screen; the first rows of the list in the list form, where they scroll away with it. The cover
 * screen's drawing goes without them.
 */
@Composable
internal fun RootControls(page: MapPage.Root, actions: MapActions, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    // The field keeps what is typed itself: the filtered page comes back a moment later, and a
    // field fed from it would lose the letters typed meanwhile.
    var typed by rememberSaveable { mutableStateOf(page.query) }
    val type: (String) -> Unit = { text ->
        typed = text
        actions.onQuery(text)
    }
    Column(modifier.fillMaxWidth()) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.g_map_core),
                stringResource(R.string.g_map_recent),
                stringResource(R.string.g_map_all)
            ),
            selectedIndex = page.filter.ordinal,
            onSelect = { actions.onFilter(ThemeFilter.entries[it]) },
            modifier = Modifier.padding(horizontal = GrilleSpace.margin)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = GrilleSpace.margin, end = GrilleSpace.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The results follow the typing; the Search key only closes the keyboard.
            GrilleTextField(
                value = typed,
                onValueChange = type,
                placeholder = stringResource(R.string.g_map_search_hint),
                imeAction = ImeAction.Search,
                capitalization = KeyboardCapitalization.None,
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f)
            )
            if (typed.isNotEmpty()) {
                CircleIconButton(
                    icon = GrilleIcons.Close,
                    contentDescription = stringResource(R.string.g_search_clear),
                    onClick = { type("") }
                )
            } else {
                // Holds the clear button's place, so the underline keeps its length.
                Spacer(Modifier.size(GrilleSpace.touch))
            }
        }
    }
}

private fun LazyListScope.rootItems(page: MapPage.Root, state: MindMapUiState, actions: MapActions) {
    item(key = "older") { OlderBlock(page, state.ai, state.job as? MapJob.Older, actions) }
    when {
        page.totalThemes == 0 -> Unit
        // Core and All always hold a theme while the map has one; Recent can be empty.
        page.themes.isEmpty() && page.query.isBlank() && page.filter == ThemeFilter.RECENT -> item(key = "empty-filter") {
            PageNote(stringResource(R.string.g_map_empty_recent))
        }
        page.themes.isEmpty() && page.query.isNotBlank() -> item(key = "no-match") {
            Column {
                PageNote(stringResource(R.string.g_map_no_match))
                if (page.elsewhere > 0) {
                    QuietRow(
                        text = pluralStringResource(R.plurals.g_map_matches_in_all, page.elsewhere, page.elsewhere),
                        onClick = { actions.onFilter(ThemeFilter.ALL) }
                    )
                }
            }
        }
    }
    items(page.themes, key = { "theme:${it.node.id}" }) { item ->
        NodeRow(item, onClick = { actions.onOpen(MapTarget.Node(item.node.id)) })
        Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
    }
}

/**
 * The empty map's sentence, and "Map N older recordings" with its progress while it runs. Nothing
 * of the AI shows while it isn't available; while a recording is being made, one line says why
 * the offer waits.
 */
@Composable
private fun OlderBlock(page: MapPage.Root, ai: AiState, run: MapJob.Older?, actions: MapActions) {
    Column(Modifier.fillMaxWidth()) {
        if (page.totalThemes == 0) PageNote(stringResource(R.string.g_map_empty), ink = true)
        when {
            run != null -> OlderProgress(run, actions.onCancelOlder)
            page.unmapped == 0 || ai.available != true -> Unit
            ai.canRun -> PillButton(
                text = pluralStringResource(R.plurals.g_map_older, page.unmapped, page.unmapped),
                onClick = actions.onMapOlder,
                kind = if (page.totalThemes == 0) PillKind.Filled else PillKind.Outline,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s)
            )
            ai.recorderBusy -> PageNote(stringResource(R.string.g_map_ai_waits))
        }
    }
}

/** A run of the older recordings: where it is, the dots, and Cancel. */
@Composable
private fun OlderProgress(run: MapJob.Older, onCancel: () -> Unit) {
    val progress = run.progress
    val status = when {
        run.stopping -> stringResource(R.string.g_map_older_stopping)
        progress is MapOlderRecordingsUseCase.Progress.Linking ->
            stringResource(R.string.g_map_older_linking, progress.index, progress.total)
        progress is MapOlderRecordingsUseCase.Progress.Learning ->
            stringResource(R.string.g_map_older_learning, progress.index, progress.total)
        else -> stringResource(R.string.g_map_older_starting)
    }
    val fraction = when {
        run.stopping -> null
        progress is MapOlderRecordingsUseCase.Progress.Linking -> progress.index.toFloat() / progress.total.coerceAtLeast(1)
        progress is MapOlderRecordingsUseCase.Progress.Learning -> progress.index.toFloat() / progress.total.coerceAtLeast(1)
        else -> null
    }
    AiProgress(
        title = stringResource(R.string.g_map_older_running),
        status = status,
        fraction = fraction,
        onCancel = onCancel,
        cancelEnabled = !run.stopping
    )
}

// ─── A theme ─────────────────────────────────────────────────────────────────

private fun LazyListScope.themeItems(page: MapPage.Theme, state: MindMapUiState, actions: MapActions) {
    item(key = "header") {
        PageHeader(title = page.theme.label, meta = countsLine(page.recordings, page.lastAt))
    }
    item(key = "pin") {
        SettingsSwitchRow(
            title = stringResource(R.string.g_map_pin),
            supporting = stringResource(R.string.g_map_pin_hint),
            checked = page.theme.pinned,
            onCheckedChange = actions.onPin
        )
    }
    item(key = "summary") { SummaryBlock(page, state.ai, state.job == MapJob.Summary, actions.onUpdate) }
    if (page.topics.isNotEmpty()) {
        item(key = "topics") { SectionHeading(stringResource(R.string.g_map_topics)) }
        items(page.topics, key = { "topic:${it.node.id}" }) { topic ->
            NodeRow(topic, onClick = { actions.onOpen(MapTarget.Node(topic.node.id)) })
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
        }
    }
    recordingGroups(page.byRecording, state.titles, actions)
}

/**
 * What the AI learned about the theme, or that it hasn't yet, with Update while the summary is
 * missing or older than the theme's recordings and the AI can run.
 */
@Composable
private fun SummaryBlock(page: MapPage.Theme, ai: AiState, updating: Boolean, onUpdate: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.g_map_summary))
        if (updating) {
            AiProgress(title = null, status = stringResource(R.string.g_map_updating), fraction = null)
            return@Column
        }
        val summary = page.theme.summary
        if (page.summary == SummaryState.NONE || summary == null) {
            PageNote(stringResource(R.string.g_map_summary_none), ink = true)
        } else {
            Text(
                text = summary,
                style = Grille.type.body,
                color = Grille.colors.ink,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin)
            )
            val written = page.theme.summaryUpdatedAt
            val writtenLine = when {
                page.summary == SummaryState.STALE -> stringResource(R.string.g_map_summary_stale)
                written != null -> stringResource(R.string.g_map_summary_written, relativeDateTime(written))
                else -> null
            }
            if (writtenLine != null) {
                Text(
                    text = writtenLine,
                    style = Grille.type.caption,
                    color = Grille.colors.ash,
                    modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
                )
            }
        }
        if (page.summary != SummaryState.CURRENT) {
            AiAction(
                ai = ai,
                label = stringResource(R.string.g_map_update),
                onClick = onUpdate,
                unavailable = stringResource(R.string.g_map_summary_needs_ai)
            )
        }
    }
}

// ─── A topic ─────────────────────────────────────────────────────────────────

private fun LazyListScope.topicItems(page: MapPage.Topic, state: MindMapUiState, actions: MapActions) {
    item(key = "header") {
        PageHeader(title = page.topic.label, meta = countsLine(page.recordings, page.lastAt))
    }
    page.theme?.let { theme ->
        item(key = "theme") {
            QuietRow(
                text = stringResource(R.string.g_map_in_theme, theme.label),
                onClick = { actions.onOpen(MapTarget.Node(theme.id)) }
            )
        }
    }
    recordingGroups(page.byRecording, state.titles, actions)
}

/**
 * The points by recording, newest first: each recording's title and date, then its points under
 * the names of their topics. A tap opens the recording at the line its first topic came from.
 */
private fun LazyListScope.recordingGroups(
    groups: List<RecordingPoints>,
    titles: Map<String, RecordingTitle>,
    actions: MapActions
) {
    if (groups.isEmpty()) return
    item(key = "recordings") { SectionHeading(stringResource(R.string.g_map_recordings_heading)) }
    items(groups, key = { "recording:${it.sessionId}" }) { group ->
        RecordingGroup(
            group = group,
            title = titles[group.sessionId],
            onClick = {
                val segment = group.topics.firstNotNullOfOrNull { it.segmentIds.firstOrNull() }
                actions.onOpen(MapTarget.RecordingPage(group.sessionId, segment))
            }
        )
        Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
    }
}

@Composable
private fun RecordingGroup(group: RecordingPoints, title: RecordingTitle?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        if (title != null) {
            Text(
                text = recordingTitleText(title),
                style = Grille.type.body,
                color = Grille.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = relativeDateTime(group.recordedAt), style = Grille.type.caption, color = Grille.colors.ash)
        group.topics.forEach { topic ->
            topic.label?.let { label ->
                Spacer(Modifier.height(GrilleSpace.s))
                Text(text = label, style = Grille.type.label, color = Grille.colors.ash)
            }
            topic.points.forEach { point -> DotBullet(point) }
        }
    }
}

// ─── A recording ─────────────────────────────────────────────────────────────

private fun LazyListScope.recordingItems(page: MapPage.Recording, state: MindMapUiState, actions: MapActions) {
    val session = page.details.session
    item(key = "header") {
        val title = recordingTitleText(state.titles[session.id] ?: RecordingTitle.of(session.name, null, session.mode))
        val length = session.durationMs.takeIf { it > 0L }?.let { formatDuration(it) }
        val date = relativeDateTime(session.createdAt)
        PageHeader(title = title, meta = if (length != null) stringResource(R.string.g_home_meta, date, length) else date)
    }
    item(key = "open") {
        PillButton(
            text = stringResource(R.string.g_map_open_recording),
            onClick = { actions.onOpen(MapTarget.RecordingPage(session.id)) },
            kind = PillKind.Outline,
            modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s)
        )
    }
    if (page.topics.isEmpty()) {
        item(key = "unmapped") { UnmappedBlock(page, state.ai, state.job == MapJob.Recording, actions.onMapRecording) }
    }
    item(key = "notes") { RecordingNotes(page.details) }
    if (page.topics.isNotEmpty()) {
        item(key = "topics") { SectionHeading(stringResource(R.string.g_map_topics)) }
        items(page.topics, key = { "topic:${it.topic.id}" }) { topic ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { actions.onOpen(MapTarget.Node(topic.topic.id)) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
            ) {
                Text(text = topic.topic.label, style = Grille.type.body, color = Grille.colors.ink)
                Text(
                    text = stringResource(R.string.g_map_in_theme, topic.theme.label),
                    style = Grille.type.caption,
                    color = Grille.colors.ash
                )
                topic.link.points.forEach { point -> DotBullet(point) }
            }
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
        }
    }
}

/** A recording that isn't in the map: why, and "Map this recording" when the AI can run. */
@Composable
private fun UnmappedBlock(page: MapPage.Recording, ai: AiState, mapping: Boolean, onMap: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        when {
            mapping -> AiProgress(title = null, status = stringResource(R.string.g_map_mapping_this), fraction = null)
            !page.enoughText -> PageNote(stringResource(R.string.g_map_too_short), ink = true)
            else -> {
                PageNote(stringResource(R.string.g_map_not_mapped), ink = true)
                AiAction(
                    ai = ai,
                    label = stringResource(R.string.g_map_map_this),
                    onClick = onMap,
                    unavailable = stringResource(R.string.g_map_error_no_model)
                )
            }
        }
    }
}

/**
 * The recording's notes as the AI wrote them: its summary when it has one, else its notes in
 * order. The recording's page has the rest (to-dos, sources, editing).
 */
@Composable
private fun RecordingNotes(details: SessionWithDetails) {
    val groups = remember(details.insights, details.session) { groupInsights(details.insights, details.session) }
    val notes = groups.pinned.ifEmpty { groups.rest }
    if (notes.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.g_map_notes))
        notes.forEach { note ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s)
            ) {
                note.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(text = title, style = Grille.type.heading, color = Grille.colors.ink)
                    Spacer(Modifier.height(GrilleSpace.xs))
                }
                val text = remember(note.content) { summaryText(note.content) }
                if (text.isNotBlank()) FormattedInsightText(text)
            }
        }
    }
}

// ─── Building blocks ─────────────────────────────────────────────────────────

/**
 * An action of the AI: its pill while the AI can run, one line saying why while a recording is
 * being made, or [unavailable] when the AI is off or has no model. Nothing while another view of
 * the map runs AI work, which ends soon.
 */
@Composable
private fun AiAction(ai: AiState, label: String, onClick: () -> Unit, unavailable: String) {
    when {
        ai.canRun -> PillButton(
            text = label,
            onClick = onClick,
            kind = PillKind.Outline,
            modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s)
        )
        ai.available == false -> PageNote(unavailable)
        ai.available == true && ai.recorderBusy -> PageNote(stringResource(R.string.g_map_ai_waits))
    }
}

/** AI work under way: an optional heading, the dots, what is happening and, for a run, Cancel. */
@Composable
private fun AiProgress(
    title: String?,
    status: String,
    fraction: Float?,
    onCancel: (() -> Unit)? = null,
    cancelEnabled: Boolean = true
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        if (title != null) {
            Text(text = title, style = Grille.type.heading, color = Grille.colors.ink)
            Spacer(Modifier.height(GrilleSpace.m))
        }
        DotProgress(progress = fraction)
        Spacer(Modifier.height(GrilleSpace.s))
        Text(
            text = status,
            style = Grille.type.caption,
            color = Grille.colors.ash,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (onCancel != null) {
            Spacer(Modifier.height(GrilleSpace.m))
            // Outlined: standing alone, a quiet pill's text would sit in from the margin.
            PillButton(
                text = stringResource(R.string.g_cancel),
                onClick = onCancel,
                kind = PillKind.Outline,
                enabled = cancelEnabled
            )
        }
    }
}

/** The whole name of what is in the centre (the drawing cuts it), and how many recordings and when. */
@Composable
private fun PageHeader(title: String, meta: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.m, bottom = GrilleSpace.s)
    ) {
        Text(
            text = title,
            style = Grille.type.title,
            color = Grille.colors.ink,
            modifier = Modifier.semantics { heading() }
        )
        if (meta != null) Text(text = meta, style = Grille.type.caption, color = Grille.colors.ash)
    }
}

/** "3 recordings · Today, 09:30", or null before anything is linked. */
@Composable
private fun countsLine(recordings: Int, lastAt: Long?): String? {
    if (lastAt == null) return null
    return stringResource(
        R.string.g_home_meta,
        pluralStringResource(R.plurals.g_map_recordings, recordings, recordings),
        relativeDateTime(lastAt)
    )
}

/** A theme or topic in a list: its name, how many recordings and the last date, and Pinned. */
@Composable
private fun NodeRow(item: MindNodeItem, onClick: () -> Unit) {
    val counts = countsLine(item.recordingCount, item.lastRecordedAt).orEmpty()
    val meta = if (item.node.pinned) stringResource(R.string.g_home_meta, counts, stringResource(R.string.g_map_pinned)) else counts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        Text(
            text = item.node.label,
            style = Grille.type.body,
            color = Grille.colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = meta, style = Grille.type.caption, color = Grille.colors.ash)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = Grille.type.heading,
        color = Grille.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.l, bottom = GrilleSpace.xs)
            .semantics { heading() }
    )
}

/** One sentence; [ink] for what the page is about, ash for a side note. */
@Composable
internal fun PageNote(text: String, ink: Boolean = false) {
    Text(
        text = text,
        style = Grille.type.body,
        color = if (ink) Grille.colors.ink else Grille.colors.ash,
        modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    )
}

/** A row of ash text that leads somewhere: "Show 3 matches in All", "In Heating". */
@Composable
private fun QuietRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GrilleSpace.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GrilleSpace.margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(text = text, style = Grille.type.body, color = Grille.colors.ash)
    }
}
