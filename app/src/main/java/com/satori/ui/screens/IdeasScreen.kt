package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.Todo
import com.satori.presentation.ideas.IdeaRecording
import com.satori.presentation.ideas.IdeasError
import com.satori.presentation.ideas.IdeasViewModel
import com.satori.presentation.ideas.ListedIdea
import com.satori.ui.R
import com.satori.ui.components.recordingTitleText
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.Hairline
import com.satori.ui.design.MenuRow
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.NoticeResult
import com.satori.ui.design.rememberNoticeState
import com.satori.ui.design.relativeDateTime
import kotlinx.coroutines.launch

/**
 * The Ideas page: the ideas found in recordings, grouped by recording, newest first. Each group is
 * headed by its recording's title and when it was made; the ideas of a deleted recording stay,
 * under "Without a recording". With none, one sentence says where ideas come from.
 *
 * Tapping an idea opens its sheet: make it a to-do, open its recording at the line it was said in
 * (left out once that recording is gone), or delete it. A delete shows a notice with Undo.
 *
 * @param onNavigateBack Leaves the page
 * @param onOpenRecording Opens the recording of an idea at the line it was said in, or at its
 *   top for null
 */
@Composable
fun IdeasScreen(
    onNavigateBack: () -> Unit,
    onOpenRecording: (sessionId: String, segmentId: String?) -> Unit,
    viewModel: IdeasViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val notices = rememberNoticeState()
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.g_idea_deleted)
    val undoText = stringResource(R.string.g_todo_undo)

    // Only the id is kept, so the sheet shows the idea as it is stored now. One that was made a
    // to-do or deleted meanwhile closes its sheet for good, also if it becomes an idea again.
    var openIdeaId by rememberSaveable { mutableStateOf<String?>(null) }
    val openIdea = uiState.idea(openIdeaId)
    LaunchedEffect(openIdea, uiState.isLoading) {
        if (openIdea == null && !uiState.isLoading) openIdeaId = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            GrilleTopBar(
                title = stringResource(R.string.g_ideas_title),
                onBack = onNavigateBack,
                backDescription = stringResource(R.string.g_back)
            )
            uiState.error?.let { error ->
                ErrorNote(
                    text = stringResource(
                        when (error) {
                            IdeasError.Load -> R.string.g_ideas_error_load
                            IdeasError.Change -> R.string.g_ideas_error_change
                        }
                    ),
                    onDismiss = viewModel::clearError
                )
            }
            when {
                uiState.isLoading -> Unit
                uiState.isEmpty -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.g_ideas_empty),
                        style = Grille.type.body,
                        color = Grille.colors.ash,
                        textAlign = TextAlign.Center
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = GrilleSpace.m)
                ) {
                    uiState.groups.forEach { group ->
                        item(key = group.recording?.let { "group:${it.sessionId}" } ?: "group-without-recording") {
                            GroupHeading(group.recording)
                        }
                        // Lines go between the ideas of a group; the next heading's space ends it.
                        itemsIndexed(group.ideas, key = { _, idea -> "idea:${idea.id}" }) { index, idea ->
                            IdeaRow(idea = idea, onClick = { openIdeaId = idea.id })
                            if (index < group.ideas.lastIndex) {
                                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                            }
                        }
                    }
                }
            }
        }
        NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }

    openIdea?.let { listed ->
        val id = listed.idea.id
        IdeaSheet(
            listed = listed,
            onMakeTodo = {
                openIdeaId = null
                viewModel.makeTodo(id)
            },
            onOpenRecording = listed.recording?.let { recording ->
                {
                    openIdeaId = null
                    onOpenRecording(recording.sessionId, listed.firstSourceSegment)
                }
            },
            onDelete = {
                openIdeaId = null
                viewModel.deleteIdea(id)
                scope.launch {
                    if (notices.show(deletedText, undoText) == NoticeResult.ActionPerformed) viewModel.restoreIdea(id)
                }
            },
            onDismiss = { openIdeaId = null }
        )
    }
}

/** The head of a group: its recording's title and when it was made, or that it has none. */
@Composable
private fun GroupHeading(recording: IdeaRecording?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.l, bottom = GrilleSpace.xs)
            .semantics(mergeDescendants = true) { heading() }
    ) {
        Text(
            text = recording?.let { recordingTitleText(it.title) } ?: stringResource(R.string.g_ideas_no_recording),
            style = Grille.type.heading,
            color = Grille.colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (recording != null) {
            Text(
                text = relativeDateTime(recording.createdAt),
                style = Grille.type.caption,
                color = Grille.colors.ash
            )
        }
    }
}

/** One idea: its words, which open its sheet. */
@Composable
private fun IdeaRow(idea: Todo, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = idea.text, style = Grille.type.body, color = Grille.colors.ink)
    }
}

/**
 * What can be done to one idea, under its words: make it a to-do, open the recording it was said
 * in, at that line, or delete it.
 *
 * @param onOpenRecording Null when the recording was deleted, which leaves the row out
 */
@Composable
private fun IdeaSheet(
    listed: ListedIdea,
    onMakeTodo: () -> Unit,
    onOpenRecording: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    GrilleSheet(onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                text = listed.idea.text,
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(GrilleSpace.s))
            MenuRow(text = stringResource(R.string.g_idea_make_todo), onClick = onMakeTodo)
            if (onOpenRecording != null) {
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                MenuRow(
                    text = stringResource(R.string.g_idea_open_recording),
                    supporting = listed.recording?.let { recordingTitleText(it.title) },
                    onClick = onOpenRecording
                )
            }
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            MenuRow(text = stringResource(R.string.g_idea_delete), onClick = onDelete)
        }
    }
}

/** At least this tall, so an idea is a 48 dp target with room around it, as a to-do is. */
private val ROW_HEIGHT = 56.dp
