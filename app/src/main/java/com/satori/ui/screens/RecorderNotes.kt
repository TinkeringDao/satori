package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.satori.domain.model.InsightStrategy
import com.satori.presentation.main.LlmStatus
import com.satori.presentation.main.MainUiState
import com.satori.presentation.main.RecorderPhase
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/**
 * The recorder's notes tab. Once there are notes they are shown by [InsightsSection], the same
 * way every other screen shows them. Until then it says one thing, matching what happens:
 * when notes will come, that they wait for an older recording to be finished, or why none will
 * be and how to change that. Notes after Stop are written in the background, once the recorder
 * has gone home; a recording in Malayalam gets no live notes, and says that its notes come then,
 * in English. A Translate recording's notes are its translation, of each finished sentence. While
 * the first run downloads the AI model, the notes follow once it is in, with nothing to tap.
 */
@Composable
internal fun RecorderNotes(
    uiState: MainUiState,
    onAction: (RecorderAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val problem = uiState.aiProblem
    when {
        uiState.insights.isNotEmpty() -> InsightsSection(
            insights = uiState.sortedInsights,
            segments = uiState.completedSegments,
            modifier = modifier,
            // The name is in the top bar; a note with the same title doesn't say it again.
            recordingTitle = uiState.sessionName
        )
        // Nothing to add while saving, and a recorder that can't record has an error to read,
        // where "notes appear as you talk" would be wrong.
        uiState.isFinalizingSession || uiState.phase == RecorderPhase.Blocked -> Box(modifier)
        uiState.aiModelComing -> NotesHint(AiComingCopy.notes, modifier)
        problem != null -> NotesProblem(problem, onAction, modifier)
        uiState.liveNotesOff -> NotesHint(R.string.g_rec_notes_after_ml, modifier)
        uiState.insightStrategy == InsightStrategy.END_OF_SESSION -> NotesHint(R.string.g_rec_notes_after, modifier)
        uiState.translatesInto != null -> NotesHint(R.string.g_rec_translation_live, modifier)
        uiState.notesWaiting -> NotesHint(R.string.g_rec_notes_waiting, modifier)
        else -> NotesHint(R.string.g_rec_notes_live, modifier)
    }
}

@Composable
private fun NotesHint(@StringRes text: Int, modifier: Modifier) {
    Text(
        text = stringResource(text),
        style = Grille.type.body,
        color = Grille.colors.ash,
        modifier = modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    )
}

/** One sentence on why no notes are written, and the one action that can change it. */
@Composable
private fun NotesProblem(problem: LlmStatus, onAction: (RecorderAction) -> Unit, modifier: Modifier) {
    val copy = aiProblemCopy(problem)
    Column(
        modifier = modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l),
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.m)
    ) {
        Text(text = stringResource(copy.notes), style = Grille.type.body, color = Grille.colors.ash)
        PillButton(
            text = stringResource(copy.action),
            onClick = { onAction(copy.onAction) },
            kind = PillKind.Outline
        )
    }
}
