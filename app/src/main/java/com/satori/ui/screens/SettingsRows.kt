package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.satori.ui.R
import com.satori.ui.design.ChoiceRow
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleSwitch
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.Hairline
import com.satori.ui.design.LocalSheetBandClearance
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.scrollCue

// The building blocks of the Settings, Advanced and Categories screens: hairline rows with a
// title, a quiet line under it and a value or control on the right.

/**
 * The frame of a settings screen: a top bar with Back, and the rows below it, scrolling, with the
 * line at the right edge that says the list goes on ([scrollCue]). Nothing has a fixed height, so
 * it works on the cover screen too.
 *
 * @param overlay drawn over the rows, e.g. a [com.satori.ui.design.NoticeHost] at the bottom.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            GrilleTopBar(
                title = title,
                onBack = onBack,
                backDescription = stringResource(R.string.g_back)
            )
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .scrollCue(scroll)
                    .verticalScroll(scroll)
                    .padding(bottom = GrilleSpace.xl),
                content = content
            )
        }
        overlay()
    }
}

/** The name of a group of rows. */
@Composable
internal fun SettingsHeading(text: String, topPadding: Dp = GrilleSpace.xl) {
    Text(
        text = text,
        style = Grille.type.heading,
        color = Grille.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = topPadding, bottom = GrilleSpace.s)
            .semantics { heading() }
    )
}

/** A smaller name inside a group, such as the kind of recording its rows are for. */
@Composable
internal fun SettingsGroupLabel(text: String) {
    Text(
        text = text,
        style = Grille.type.label,
        color = Grille.colors.ash,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.l)
            .semantics { heading() }
    )
}

/** A line of help in ash. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Grille.type.caption,
        color = Grille.colors.ash,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin)
            .padding(bottom = GrilleSpace.s)
    )
}

@Composable
internal fun SettingsDivider() {
    Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
}

/**
 * Title, an optional quiet line under it, an optional [value] in ash and an optional [trailing]
 * control on the right. The texts are one stop for TalkBack. Put `clickable` or `toggleable` on
 * [modifier] to make the whole row a button, one touch target; a button in [trailing] merges for
 * itself and stays a stop of its own.
 *
 * The value takes at most [VALUE_SHARE] of the row and wraps beyond it ([valueWidthCap]): measured
 * before the title, a long one ("Cohere Transcribe, English and German") left the title a column a
 * few letters wide, at font 1.3 none at all, and the row 2,215 px tall. A value that long belongs
 * under the title, as [supporting].
 *
 * @param description a second quiet line, under [supporting], for a row whose first line is its
 *   value (the microphone input, the power key's category) and still has to say what it changes.
 * @param horizontalPadding the margin at both sides; none inside a sheet that has its own.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    description: String? = null,
    horizontalPadding: Dp = GrilleSpace.margin,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GrilleSpace.touch)
            .padding(horizontal = horizontalPadding, vertical = GrilleSpace.m)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.m)
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Grille.type.body, color = Grille.colors.ink)
            if (supporting != null) {
                Text(text = supporting, style = Grille.type.caption, color = Grille.colors.ash)
            }
            if (description != null) {
                Text(text = description, style = Grille.type.caption, color = Grille.colors.ash)
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = Grille.type.body,
                color = Grille.colors.ash,
                textAlign = TextAlign.End,
                modifier = Modifier.layout { measurable, constraints ->
                    // Measured before the title: of all the row's width at most its share.
                    val cap = if (constraints.hasBoundedWidth) valueWidthCap(constraints.maxWidth) else constraints.maxWidth
                    val placeable = measurable.measure(constraints.copy(maxWidth = cap.coerceAtLeast(constraints.minWidth)))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            )
        }
        trailing?.invoke()
    }
}

/** The share of a settings row its value may take ([SettingsRow]). */
internal const val VALUE_SHARE = 0.4f

/** How wide the value of a row [rowWidth] wide may be, in the same unit ([VALUE_SHARE]). */
internal fun valueWidthCap(rowWidth: Int): Int = (rowWidth * VALUE_SHARE).toInt()

/**
 * A row that opens something: its sheet, or another screen. One without a value on the right
 * ends in a quiet chevron ([linkRowHasChevron]): Power key, Advanced, Licences and Add category
 * read as plain labels without it, while the rows with a value looked like settings.
 */
@Composable
internal fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    description: String? = null,
    onClickLabel: String = stringResource(R.string.g_settings_change)
) {
    SettingsRow(
        title = title,
        modifier = modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
        value = value,
        supporting = supporting,
        description = description,
        trailing = if (linkRowHasChevron(value)) {
            { LinkChevron() }
        } else {
            null
        }
    )
}

/** Whether a link row with [value] on its right ends in a chevron: only one without a value. */
internal fun linkRowHasChevron(value: String?): Boolean = value == null

/** The quiet cue at the end of a link row that it opens something. */
@Composable
private fun LinkChevron() {
    Icon(
        imageVector = GrilleIcons.Forward,
        contentDescription = null,
        tint = Grille.colors.ash,
        modifier = Modifier.size(20.dp)
    )
}

/**
 * The whole row toggles, for a full-height touch target and one TalkBack stop. A switch that is
 * not [enabled] is shown off and does not respond.
 *
 * @param description a second quiet line, as [SettingsRow]'s: why the switch can't be used now.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    description: String? = null,
    horizontalPadding: Dp = GrilleSpace.margin
) {
    SettingsRow(
        title = title,
        supporting = supporting,
        description = description,
        horizontalPadding = horizontalPadding,
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        ),
        trailing = {
            // The row carries the switch semantics; this is only its picture.
            Box(Modifier.clearAndSetSemantics {}) {
                GrilleSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        }
    )
}

/**
 * Two to four short options under a title. The pills have no room for what each option means, so
 * [supporting], under the title, says it for all of them in a line or two.
 */
@Composable
internal fun SettingsChoice(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    supporting: String? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        Text(text = title, style = Grille.type.body, color = Grille.colors.ink)
        if (supporting != null) {
            Text(text = supporting, style = Grille.type.caption, color = Grille.colors.ash)
        }
        Spacer(Modifier.height(GrilleSpace.m))
        SegmentedChoice(options = options, selectedIndex = selectedIndex, onSelect = onSelect)
    }
}

/**
 * A short list of choices in a sheet: one row each, the selected one filled, and under each its
 * own line of what picking it means. Choosing one applies it and the caller closes the sheet. The
 * choices scroll under the title where the sheet can't show them all, as on the cover screen, so
 * the last one stays within reach, with the line that says so ([scrollCue]); on the cover they
 * run on beside the cameras ([GrilleSheet]'s throughBand).
 *
 * @param supporting the line under each option, by its index; none where null or missing.
 * @param help one quiet line under the title, if the choices need it.
 */
@Composable
internal fun ChoiceSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    supporting: List<String?> = emptyList(),
    help: String? = null
) {
    GrilleSheet(onDismiss = onDismiss, throughBand = true) {
        Text(
            text = title,
            style = Grille.type.title,
            color = Grille.colors.ink,
            modifier = Modifier
                .padding(horizontal = GrilleSpace.margin)
                .semantics { heading() }
        )
        if (help != null) {
            Text(
                text = help,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
            )
        }
        Spacer(Modifier.height(GrilleSpace.s))
        val scroll = rememberScrollState()
        val clearance = LocalSheetBandClearance.current
        Column(
            Modifier
                .weight(1f, fill = false)
                .scrollCue(scroll, endInset = clearance)
                .verticalScroll(scroll)
                .padding(bottom = clearance)
                .selectableGroup()
        ) {
            options.forEachIndexed { index, option ->
                ChoiceRow(
                    title = option,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    supporting = supporting.getOrNull(index)
                )
                if (index < options.lastIndex) {
                    Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                }
            }
        }
    }
}
