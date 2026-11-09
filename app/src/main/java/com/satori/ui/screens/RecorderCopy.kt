package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.main.LlmStatus
import com.satori.presentation.main.RecorderError
import com.satori.ui.R
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillAction
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/** What the recorder's messages can ask the screen to do; [MainScreen] carries them out. */
internal enum class RecorderAction {
    /** Try the failed step again. */
    TryAgain,

    /** Go to the settings where models are downloaded. */
    OpenSettings,

    /** Switch AI notes on. */
    TurnOnAi,

    /** Open Android's settings for this app, to allow the microphone. */
    OpenAppSettings,

    /** Ask Android for the microphone: its window, which can still ask. */
    AllowMicrophone,

    /** Leave the recorder. */
    Leave
}

/**
 * The sentence for an error and the one thing to do about it ([way]). Without one the record
 * button is the way on, and the sentence says so: a pill next to it would be a second control
 * for the same thing.
 */
private class ErrorCopy(@StringRes val text: Int, val way: ErrorWay?)

/** A pill under an error: its [label] and what it asks the screen to do. */
private class ErrorWay(@StringRes val label: Int, val action: RecorderAction)

private fun errorCopy(error: RecorderError): ErrorCopy = when (error) {
    RecorderError.SpeechModelMissing ->
        ErrorCopy(R.string.g_rec_error_no_model, ErrorWay(R.string.g_rec_action_settings, RecorderAction.OpenSettings))
    RecorderError.SpeechModelDamaged ->
        ErrorCopy(R.string.g_rec_error_damaged, ErrorWay(R.string.g_rec_action_settings, RecorderAction.OpenSettings))
    RecorderError.SpeechModelFailed ->
        ErrorCopy(R.string.g_rec_error_model, ErrorWay(R.string.g_rec_action_retry, RecorderAction.TryAgain))
    RecorderError.ServiceRefused -> ErrorCopy(R.string.g_rec_error_service, way = null)
    RecorderError.TranscriptionFailed ->
        ErrorCopy(R.string.g_rec_error_stopped, ErrorWay(R.string.g_rec_action_retry, RecorderAction.TryAgain))
}

/**
 * What the recorder says when the AI can't write notes: a short caption under the record
 * button, the sentence on the notes tab, and the one way to fix it.
 */
internal class AiProblemCopy(
    @StringRes val caption: Int,
    @StringRes val notes: Int,
    @StringRes val action: Int,
    val onAction: RecorderAction
)

internal fun aiProblemCopy(problem: LlmStatus): AiProblemCopy = when (problem) {
    LlmStatus.Off ->
        AiProblemCopy(R.string.g_rec_ai_off, R.string.g_rec_notes_off, R.string.g_rec_action_turn_on, RecorderAction.TurnOnAi)
    LlmStatus.Missing ->
        AiProblemCopy(R.string.g_rec_ai_missing, R.string.g_rec_notes_missing, R.string.g_rec_action_get_model, RecorderAction.OpenSettings)
    else ->
        AiProblemCopy(R.string.g_rec_ai_failed, R.string.g_rec_notes_failed, R.string.g_rec_action_settings, RecorderAction.OpenSettings)
}

/**
 * What the recorder says while the missing AI model is on its way
 * ([com.satori.presentation.main.MainUiState.aiModelComing]), in place of [aiProblemCopy]'s: the
 * caption under the record button and the sentence on the notes tab. No action: the model comes
 * by itself.
 */
internal object AiComingCopy {
    @StringRes val caption: Int = R.string.g_rec_ai_downloading

    @StringRes val notes: Int = R.string.g_rec_notes_downloading
}

/**
 * What the recorder says while the microphone isn't allowed, the words of Getting ready's footer
 * (design 3.5): why it is needed, or that it was refused; which option of Android's window to pick,
 * or where to allow it once Android no longer asks; and the one way on.
 */
internal class MicrophoneCopy(@StringRes val lines: List<Int>, @StringRes val action: Int, val onAction: RecorderAction)

/** [MicrophoneCopy] for [microphone]; null while it is allowed. */
internal fun microphoneCopy(microphone: MicrophoneState): MicrophoneCopy? = when (microphone) {
    MicrophoneState.ALLOWED -> null
    MicrophoneState.NOT_ALLOWED ->
        MicrophoneCopy(listOf(R.string.g_fr_mic_why, R.string.g_fr_mic_how), R.string.g_fr_mic_action, RecorderAction.AllowMicrophone)
    MicrophoneState.REFUSED_ONCE ->
        MicrophoneCopy(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_how), R.string.g_fr_mic_action, RecorderAction.AllowMicrophone)
    MicrophoneState.REFUSED_FOR_GOOD ->
        MicrophoneCopy(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_settings), R.string.g_rec_action_settings, RecorderAction.OpenAppSettings)
}

/** The recorder's one error, in the app's language, with its way forward. */
@Composable
internal fun RecorderErrorNote(
    error: RecorderError,
    onAction: (RecorderAction) -> Unit,
    horizontalMargin: Dp = GrilleSpace.margin
) {
    val copy = errorCopy(error)
    ErrorNote(
        text = stringResource(copy.text),
        action = copy.way?.let { way -> PillAction(stringResource(way.label), onClick = { onAction(way.action) }) },
        horizontalMargin = horizontalMargin
    )
}

/**
 * The microphone isn't allowed: the recorder says why and offers the one way on, in place of
 * Android's window popping up unasked or a dialog that closes the recorder ([microphoneCopy]).
 * Nothing while it is allowed.
 */
@Composable
internal fun MicrophoneNote(
    microphone: MicrophoneState,
    onAction: (RecorderAction) -> Unit,
    horizontalMargin: Dp = GrilleSpace.margin
) {
    val copy = microphoneCopy(microphone) ?: return
    ErrorNote(
        text = copy.lines.map { stringResource(it) }.joinToString(" "),
        action = PillAction(stringResource(copy.action), onClick = { onAction(copy.onAction) }),
        horizontalMargin = horizontalMargin
    )
}

/**
 * The one line under the record button when the AI can't write notes, with its one action.
 * Shown only then. While the missing model is on its way ([coming]) it says so, with no action.
 */
@Composable
internal fun AiCaption(
    problem: LlmStatus,
    onAction: (RecorderAction) -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    coming: Boolean = false
) {
    val copy = aiProblemCopy(problem)
    Column(modifier = modifier, horizontalAlignment = alignment) {
        Text(
            text = stringResource(if (coming) AiComingCopy.caption else copy.caption),
            style = Grille.type.caption,
            color = Grille.colors.ash,
            textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
        )
        if (!coming) {
            PillButton(
                text = stringResource(copy.action),
                onClick = { onAction(copy.onAction) },
                kind = PillKind.Quiet
            )
        }
    }
}

/**
 * Keeps the room [AiCaption] takes (a caption line and a pill) around [content], whether there
 * is a caption to show or not. The record button sits above it, and a button that moves when a
 * caption comes or goes can be missed, or another button hit where it was. The caption starts a
 * gap below the button's word, so the two don't read as one label.
 */
@Composable
internal fun AiCaptionSlot(content: @Composable () -> Unit) {
    val captionLine = with(LocalDensity.current) { Grille.type.caption.lineHeight.toDp() }
    Box(
        Modifier
            .heightIn(min = CAPTION_GAP + captionLine + GrilleSpace.touch)
            .padding(top = CAPTION_GAP),
        contentAlignment = Alignment.TopCenter
    ) {
        content()
    }
}

/** Between the record button's word and the AI caption under it. */
private val CAPTION_GAP = GrilleSpace.m
