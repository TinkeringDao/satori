package com.satori.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.satori.ui.R
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.WithDeliberateHold

/**
 * The pill that names what is recorded as [label] and opens "Record as": under the grille on home,
 * where it picks what the next recording is, and at the top of the recorder while it records,
 * where it switches the recording itself. Outlined, with the arrow of a choice; a long category
 * name wraps inside it instead of stretching it, or, [singleLine], ends in an ellipsis before the
 * arrow (the recorder's, which shares its row with the timer). TalkBack says "Record as Meeting,
 * change".
 *
 * @param holdToOpen Whether only a hold of at least half a second opens it, and a tap calls
 *   [onHoldHint] instead: over the lock screen, where the screen stays on in a pocket, as the
 *   grille stops a recording there only on a hold
 */
@Composable
fun RecordAsChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    holdToOpen: Boolean = false,
    onHoldHint: () -> Unit = {},
    singleLine: Boolean = false
) {
    val chip: @Composable () -> Unit = {
        PillButton(
            text = label,
            onClick = if (holdToOpen) onHoldHint else onClick,
            modifier = modifier.widthIn(max = CHIP_MAX_WIDTH),
            kind = PillKind.Outline,
            trailingIcon = GrilleIcons.Expand,
            contentPadding = PaddingValues(start = GrilleSpace.m, end = 12.dp),
            contentDescription = stringResource(R.string.g_record_as_chip_cd, label),
            onLongClick = if (holdToOpen) onClick else null,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE
        )
    }
    if (holdToOpen) WithDeliberateHold(chip) else chip()
}

/** A long category name wraps inside the chip instead of stretching it across the screen. */
private val CHIP_MAX_WIDTH = 200.dp
