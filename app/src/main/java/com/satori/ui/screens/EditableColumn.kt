package com.satori.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.satori.ui.R

/**
 * One row of a list, and one TalkBack stop, that opens its editor on a long press.
 *
 * Rows have no edit button: a pencil on every line was noise for an action that is rarely
 * needed. TalkBack gets the same editor as the action "Edit" in its actions menu, since it
 * has no long press of its own on a plain row.
 *
 * @param onEdit Opens the editor; null where the row can't be edited (the recorder's notes).
 * @param contentPadding The row's inner margins. They are inside the long-press area, so the
 *   whole row answers, not only its text.
 * @param onTap What a tap does, if anything: a transcript line with audio plays from there.
 *   TalkBack offers it as the row's click, named [tapLabel].
 */
@Composable
internal fun EditableColumn(
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onTap: (() -> Unit)? = null,
    tapLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val editLabel = stringResource(R.string.g_details_edit)
    val haptics = LocalHapticFeedback.current
    // The lambdas change on every recomposition; the gesture must not restart with them.
    val currentEdit by rememberUpdatedState(onEdit)
    val currentTap by rememberUpdatedState(onTap)
    val tappable = onTap != null
    val gestures = if (onEdit == null && !tappable) {
        Modifier
    } else {
        Modifier.pointerInput(tappable) {
            detectTapGestures(
                onTap = if (tappable) { _ -> currentTap?.invoke() } else null,
                onLongPress = {
                    currentEdit?.let { edit ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        edit()
                    }
                }
            )
        }
    }
    Column(
        modifier = modifier
            .then(gestures)
            .semantics(mergeDescendants = true) {
                if (onEdit != null) {
                    customActions = listOf(CustomAccessibilityAction(editLabel) {
                        onEdit()
                        true
                    })
                }
                if (onTap != null) {
                    onClick(label = tapLabel) {
                        onTap()
                        true
                    }
                }
            }
            .padding(contentPadding),
        content = content
    )
}
