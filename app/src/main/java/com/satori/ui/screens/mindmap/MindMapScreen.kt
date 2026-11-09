package com.satori.ui.screens.mindmap

import android.content.res.Resources
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.presentation.mindmap.CrumbView
import com.satori.presentation.mindmap.MapForm
import com.satori.presentation.mindmap.MapJob
import com.satori.presentation.mindmap.MapLabel
import com.satori.presentation.mindmap.MapNode
import com.satori.presentation.mindmap.MapNodeKind
import com.satori.presentation.mindmap.MapPage
import com.satori.presentation.mindmap.MapScene
import com.satori.presentation.mindmap.MapTarget
import com.satori.presentation.mindmap.MindMapError
import com.satori.presentation.mindmap.MindMapNotice
import com.satori.presentation.mindmap.MindMapRules
import com.satori.presentation.mindmap.MindMapUiState
import com.satori.presentation.mindmap.MindMapViewModel
import com.satori.ui.R
import com.satori.ui.components.recordingTitleText
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.DotProgress
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.PillAction
import com.satori.ui.design.rememberNoticeState
import com.satori.ui.design.relativeDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One view of the mind map, the map of thoughts the AI builds from the recordings: the top level
 * ("You" and the themes), a theme or a topic, or a recording's own map. Each view is a screen of
 * its own, so going into a node is a navigation and Back comes out of it; the breadcrumb row in
 * the top bar goes back up any number of steps.
 *
 * The map opens as the drawing ([MindMapCanvas]), on the cover screen as on the inner one, and as
 * the page alone, a list, with TalkBack on; the top bar's button switches between the two on every
 * screen, and the choice holds for every view on both ([MapForm.of]). On the inner screen the
 * drawing takes the top and a reading pane the rest: the page of what is in the centre (its
 * summary, its points with the recordings they came from). Below 560 dp of height (the cover
 * screen) the drawing takes the whole view under the top bar, and the page, with the top level's
 * chips, search and "Map N older recordings", is the list form's. A view with nothing to draw
 * around its centre shows its page.
 *
 * Leaving the top level while older recordings are being mapped asks first: the run ends with the
 * screen, and what was mapped so far stays.
 *
 * @param onOpenNode Opens the view of a theme or topic, with the trail for its breadcrumb
 * @param onOpenRecordingMap Opens a recording's own map, with the trail for its breadcrumb
 * @param onOpenTopLevel Opens the top level as a new screen, for a view opened from outside it
 * @param onGoBack Goes back the given number of screens, for a crumb
 * @param onOpenRecording Opens a recording's page, at the line with the given id when not null
 */
@Composable
fun MindMapScreen(
    onNavigateBack: () -> Unit,
    onOpenNode: (nodeId: String, trail: String) -> Unit,
    onOpenRecordingMap: (sessionId: String, trail: String) -> Unit,
    onOpenTopLevel: () -> Unit,
    onGoBack: (screens: Int) -> Unit,
    onOpenRecording: (sessionId: String, segmentId: String?) -> Unit,
    viewModel: MindMapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val choice by viewModel.listChoice.collectAsStateWithLifecycle()
    val notices = rememberNoticeState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    // Settings and the model download change what the AI can do; look again on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAi() }

    // Notices wait in the ViewModel while the view is off screen, and show when it comes back.
    LaunchedEffect(viewModel, resources) {
        viewModel.notices.collect { notice -> scope.launch { notices.show(noticeText(resources, notice)) } }
    }

    val mapping = state.job is MapJob.Older
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mapping) { if (!mapping) confirmLeave = false }
    BackHandler(enabled = mapping) { confirmLeave = true }
    val leave = { if (mapping) confirmLeave = true else onNavigateBack() }

    val open: (MapTarget) -> Unit = { target ->
        when (target) {
            is MapTarget.Node -> onOpenNode(target.id, state.childTrail)
            is MapTarget.RecordingMap -> onOpenRecordingMap(target.sessionId, state.childTrail)
            is MapTarget.RecordingPage -> onOpenRecording(target.sessionId, target.segmentId)
        }
    }
    val actions = MapActions(
        onOpen = open,
        onFilter = viewModel::setFilter,
        onQuery = viewModel::setQuery,
        onMapOlder = viewModel::mapOlder,
        onCancelOlder = viewModel::cancelOlder,
        onPin = viewModel::setPinned,
        onUpdate = viewModel::updateSummary,
        onMapRecording = viewModel::mapThisRecording
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        val canDraw = MindMapRules.hasDrawing(state.page, state.scene)
        val form = MapForm.of(
            chosen = choice,
            touchExploration = rememberTouchExploration(),
            compact = maxHeight < COMPACT_HEIGHT,
            canDraw = canDraw
        )
        val list = form == MapForm.LIST
        Column(Modifier.fillMaxSize()) {
            MapTopBar(
                state = state,
                list = list,
                showToggle = canDraw,
                onBack = leave,
                onCrumb = { crumb -> crumb.back?.let(onGoBack) ?: onOpenTopLevel() },
                onToggle = { viewModel.chooseList(!list) }
            )
            state.error?.let { error ->
                ErrorNote(text = errorText(error), onDismiss = viewModel::clearError)
            }
            val page = state.page
            val scene = state.scene
            when {
                page == MapPage.Loading -> LoadingDots()
                page == MapPage.Missing || scene == null -> ErrorNote(
                    text = stringResource(R.string.g_map_missing),
                    action = PillAction(stringResource(R.string.g_back), onClick = onNavigateBack),
                    modifier = Modifier.padding(vertical = GrilleSpace.m)
                )
                form == MapForm.LIST -> MapList(state, actions, Modifier.weight(1f))
                form == MapForm.DRAWING -> MapDrawing(scene, actions, Modifier.weight(1f))
                else -> MapWithPane(state, scene, actions, Modifier.weight(1f))
            }
        }
        // Over the content and anchored to the bottom: a confirmation never moves what is read.
        NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmLeave) {
        ConfirmSheet(
            title = stringResource(R.string.g_map_stop_title),
            text = stringResource(R.string.g_map_stop_body),
            confirmLabel = stringResource(R.string.g_map_stop_confirm),
            dismissLabel = stringResource(R.string.g_map_stop_keep),
            onConfirm = {
                confirmLeave = false
                onNavigateBack()
            },
            onDismiss = { confirmLeave = false }
        )
    }
}

/** The page alone: the list form. The top level's chips and search scroll with it. */
@Composable
private fun MapList(state: MindMapUiState, actions: MapActions, modifier: Modifier = Modifier) {
    val root = state.page as? MapPage.Root
    LazyColumn(
        // The keyboard of the search must not cover the themes it finds, on the cover screen above all.
        modifier = modifier.fillMaxWidth().imePadding(),
        contentPadding = PaddingValues(bottom = GrilleSpace.xl)
    ) {
        if (root != null && root.totalThemes > 0) {
            item(key = "controls") { RootControls(root, actions, Modifier.padding(top = GrilleSpace.s)) }
        }
        mapPageItems(state, actions)
    }
}

/**
 * The drawing alone, on a compact height: the cover screen's view is too low for a drawing and a
 * pane, and the drawing is what the map is opened for there.
 */
@Composable
private fun MapDrawing(scene: MapScene, actions: MapActions, modifier: Modifier = Modifier) {
    MindMapCanvas(
        scene = scene,
        centre = canvasNode(scene.centre, centre = true),
        children = scene.children.map { canvasNode(it, centre = false) },
        onOpen = actions.onOpen,
        modifier = modifier.fillMaxWidth()
    )
}

/** The drawing on top and the reading pane below it. */
@Composable
private fun MapWithPane(state: MindMapUiState, scene: MapScene, actions: MapActions, modifier: Modifier = Modifier) {
    val root = state.page as? MapPage.Root
    Column(modifier.fillMaxWidth()) {
        if (root != null) RootControls(root, actions, Modifier.padding(top = GrilleSpace.s))
        val centre = canvasNode(scene.centre, centre = true)
        val children = scene.children.map { canvasNode(it, centre = false) }
        MindMapCanvas(
            scene = scene,
            centre = centre,
            children = children,
            onOpen = actions.onOpen,
            modifier = Modifier
                .weight(CANVAS_WEIGHT)
                .fillMaxWidth()
        )
        Hairline()
        LazyColumn(
            modifier = Modifier
                .weight(PANE_WEIGHT)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = GrilleSpace.xl)
        ) {
            mapPageItems(state, actions)
        }
    }
}

/**
 * Back, then where the view is: "Mind map" at the top level, else the breadcrumb, the way back
 * up ending in what is in the centre. The last button switches between the map and the list,
 * where the view has a drawing.
 */
@Composable
private fun MapTopBar(
    state: MindMapUiState,
    list: Boolean,
    showToggle: Boolean,
    onBack: () -> Unit,
    onCrumb: (CrumbView) -> Unit,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = GrilleSpace.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIconButton(icon = GrilleIcons.Back, contentDescription = stringResource(R.string.g_back), onClick = onBack)
        val current = state.scene?.centre?.label
        if (state.crumbs.isEmpty() || current == null) {
            Text(
                text = stringResource(R.string.g_map_title),
                style = Grille.type.label,
                color = Grille.colors.ink,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GrilleSpace.s)
                    .semantics { heading() }
            )
        } else {
            Breadcrumbs(state.crumbs, current, onCrumb, Modifier.weight(1f))
        }
        if (showToggle) {
            CircleIconButton(
                icon = if (list) GrilleIcons.MindMap else GrilleIcons.List,
                contentDescription = stringResource(if (list) R.string.g_map_show_map else R.string.g_map_show_list),
                onClick = onToggle
            )
        }
    }
}

/**
 * The way back up, in ash, each crumb a 48 dp button, and what is in the centre last, in ink. It
 * scrolls sideways when it is long and starts at its end, where the user is.
 */
@Composable
private fun Breadcrumbs(crumbs: List<CrumbView>, current: MapLabel, onCrumb: (CrumbView) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val action = stringResource(R.string.g_map_crumb_action)
    Row(
        modifier = modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEach { crumb ->
            Box(
                modifier = Modifier
                    .heightIn(min = GrilleSpace.touch)
                    .clickable(onClickLabel = action, role = Role.Button) { onCrumb(crumb) }
                    .padding(horizontal = GrilleSpace.s),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = MindMapRules.cutLabel(mapLabelText(crumb.label)),
                    style = Grille.type.label,
                    color = Grille.colors.ash,
                    maxLines = 1
                )
            }
            Text(
                text = CRUMB_SEPARATOR,
                style = Grille.type.label,
                color = Grille.colors.ash,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
        Text(
            text = mapLabelText(current),
            style = Grille.type.label,
            color = Grille.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = CURRENT_CRUMB_MAX)
                .padding(horizontal = GrilleSpace.s)
                .semantics { heading() }
        )
        Spacer(Modifier.width(GrilleSpace.xs))
    }
}

/** What a label says: "You", the AI's words, or a recording's title as everywhere else. */
@Composable
internal fun mapLabelText(label: MapLabel): String = when (label) {
    MapLabel.You -> stringResource(R.string.g_map_you)
    is MapLabel.Text -> label.text
    is MapLabel.Recording -> recordingTitleText(label.title)
}

/** A node with its words, and its points when it has some. */
@Composable
private fun canvasNode(node: MapNode, centre: Boolean): CanvasNode {
    val label = mapLabelText(node.label)
    val described = nodeDescription(node, label)
    return CanvasNode(
        node = node,
        label = label,
        description = if (centre && node.kind != MapNodeKind.YOU) {
            stringResource(R.string.g_map_cd_centre, described)
        } else {
            described
        },
        number = if (node.kind == MapNodeKind.YOU) node.recordings.toString() else null,
        children = node.children.map { canvasNode(it, centre = false) }
    )
}

/** What TalkBack says for a node: what it is, its name, how many recordings and when. */
@Composable
private fun nodeDescription(node: MapNode, label: String): String {
    val lastAt = node.lastAt
    val counts = if (lastAt != null) {
        stringResource(
            R.string.g_map_cd_counts,
            pluralStringResource(R.plurals.g_map_recordings, node.recordings, node.recordings),
            relativeDateTime(lastAt)
        )
    } else {
        ""
    }
    return when (node.kind) {
        MapNodeKind.YOU -> pluralStringResource(R.plurals.g_map_you_cd, node.recordings, node.recordings)
        MapNodeKind.THEME -> stringResource(
            if (node.pinned) R.string.g_map_cd_theme_pinned else R.string.g_map_cd_theme,
            label,
            counts
        )
        MapNodeKind.TOPIC -> stringResource(R.string.g_map_cd_topic, label, counts)
        MapNodeKind.RECORDING -> stringResource(
            R.string.g_map_cd_recording,
            label,
            if (lastAt != null) relativeDateTime(lastAt) else ""
        )
        MapNodeKind.POINT -> stringResource(R.string.g_map_cd_point, label)
    }
}

/** Says what went wrong and what to do, in the app's language. */
@Composable
private fun errorText(error: MindMapError): String = when (error) {
    MindMapError.Load -> stringResource(R.string.g_map_error_load)
    MindMapError.Pin -> stringResource(R.string.g_map_error_pin)
    MindMapError.NoModel -> stringResource(R.string.g_map_error_no_model)
    MindMapError.ModelFailed -> stringResource(R.string.g_map_error_model)
    is MindMapError.OlderStopped -> pluralStringResource(R.plurals.g_map_older_stopped, error.mapped, error.mapped)
    MindMapError.SummaryUnreadable -> stringResource(R.string.g_map_error_summary)
    MindMapError.MapUnreadable -> stringResource(R.string.g_map_error_topics)
    MindMapError.TooShort -> stringResource(R.string.g_map_too_short)
    MindMapError.Save -> stringResource(R.string.g_map_error_save)
}

/** The words of a notice; read outside composition, as it shows from a coroutine. */
private fun noticeText(resources: Resources, notice: MindMapNotice): String = when (notice) {
    is MindMapNotice.OlderDone -> {
        val done = resources.getQuantityString(R.plurals.g_map_older_done, notice.mapped, notice.mapped)
        if (notice.withoutTopics > 0) {
            "$done " + resources.getQuantityString(R.plurals.g_map_older_without, notice.withoutTopics, notice.withoutTopics)
        } else {
            done
        }
    }
    MindMapNotice.AlreadyRunning -> resources.getString(R.string.g_map_older_already)
    is MindMapNotice.RecordingMapped ->
        resources.getQuantityString(R.plurals.g_map_recording_mapped, notice.themes, notice.themes)
    MindMapNotice.NothingFound -> resources.getString(R.string.g_map_nothing_found)
    MindMapNotice.MappingStopped -> resources.getString(R.string.g_map_stopped_recording)
    MindMapNotice.UpdateStopped -> resources.getString(R.string.g_map_update_stopped_recording)
}

/**
 * Whether TalkBack, or another service that explores by touch, is on: the map then opens as a
 * list, which reads in order, unless the user chose the map ([MapForm.of]).
 */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** The database usually answers within a frame or two; the dots only appear if it takes longer. */
@Composable
private fun LoadingDots() {
    val visible by produceState(initialValue = false) {
        delay(LOADING_DOTS_DELAY_MS)
        value = true
    }
    if (visible) {
        val description = stringResource(R.string.g_details_loading)
        DotProgress(
            progress = null,
            modifier = Modifier
                .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
                .semantics { contentDescription = description }
        )
    }
}

/** Below this height (dp) the view is the cover screen's: the drawing takes it all, with no pane. */
private val COMPACT_HEIGHT = 560.dp

private val TOP_BAR_HEIGHT = 64.dp

/** What is in the centre may take this much of the breadcrumb row; the crumbs before it scroll. */
private val CURRENT_CRUMB_MAX = 200.dp

/** The drawing's share of the height against the reading pane's. */
private const val CANVAS_WEIGHT = 1.15f
private const val PANE_WEIGHT = 1f

private const val CRUMB_SEPARATOR = "›"

private const val LOADING_DOTS_DELAY_MS = 300L
