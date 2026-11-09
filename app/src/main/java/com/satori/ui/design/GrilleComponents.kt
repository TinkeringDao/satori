package com.satori.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Outlined icons used across the app; tinted by the caller. */
object GrilleIcons {
    val Back: ImageVector get() = Icons.AutoMirrored.Outlined.ArrowBack
    val Search: ImageVector get() = Icons.Outlined.Search
    val Settings: ImageVector get() = Icons.Outlined.Settings
    val Close: ImageVector get() = Icons.Outlined.Close
    val More: ImageVector get() = Icons.Outlined.MoreVert
    val Edit: ImageVector get() = Icons.Outlined.Edit
    val Delete: ImageVector get() = Icons.Outlined.Delete
    val Share: ImageVector get() = Icons.Outlined.Share
    val Expand: ImageVector get() = Icons.Outlined.KeyboardArrowDown
    val Check: ImageVector get() = Icons.Outlined.Check
    val Play: ImageVector get() = Icons.Outlined.PlayArrow

    /** Two bars, the width of [Play]'s triangle: the core icon set has no pause. */
    val Pause: ImageVector by lazy {
        materialIcon(name = "Grille.Pause") {
            materialPath {
                moveTo(6f, 5f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(-4f)
                close()
                moveTo(14f, 5f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(-4f)
                close()
            }
        }
    }

    /** After the text of a pill that opens another screen, such as the recording a to-do came from. */
    val Forward: ImageVector get() = Icons.AutoMirrored.Outlined.KeyboardArrowRight

    /** The mind map shown as a list: rows of themes. */
    val List: ImageVector get() = Icons.AutoMirrored.Outlined.List

    /**
     * The mind map: a ring in the middle and three dots around it, joined by hairlines, as the
     * map draws a focus and its children. The core icon set has no map of this kind, and the
     * extended set would add some 11,000 classes for one icon.
     */
    val MindMap: ImageVector by lazy {
        ImageVector.Builder(
            name = "Grille.MindMap",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // The focus: a ring a little below the middle, so the whole mark sits centred in the box.
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
                moveTo(9f, 13.9f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                close()
            }
            // The lines from the ring to the dots at 12, 4 and 8 o'clock.
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 10.1f)
                lineTo(12f, 7.3f)
                moveTo(15.3f, 15.8f)
                lineTo(17.7f, 17.2f)
                moveTo(8.7f, 15.8f)
                lineTo(6.3f, 17.2f)
            }
            // The three children, filled like the map's nodes.
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 5.1f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
                moveTo(17.8f, 18.5f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
                moveTo(2.2f, 18.5f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
            }
        }.build()
    }

    /**
     * The mark of an [ErrorNote]: a warning triangle drawn in outline like the other icons.
     * Not a dot-matrix "!", which reads as the overflow icon, and not the core set's Warning,
     * which is a solid triangle as loud as the grille.
     */
    val Problem: ImageVector by lazy {
        materialIcon(name = "Grille.Problem") {
            materialPath {
                // The triangle's edge: an outer triangle with an inner one cut out of it.
                moveTo(12f, 5.99f)
                lineTo(19.53f, 19f)
                horizontalLineTo(4.47f)
                close()
                moveTo(12f, 2f)
                lineTo(1f, 21f)
                horizontalLineToRelative(22f)
                close()
                // The "!": its dot, then its bar.
                moveToRelative(1f, 14f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                close()
                moveToRelative(0f, -6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(2f)
                close()
            }
        }
    }
}

/**
 * - [Filled]: the one primary action on a screen or sheet.
 * - [Outline]: a real alternative that is not the main way forward.
 * - [Quiet]: a way out (Cancel, Skip), in ash with no edge.
 */
enum class PillKind { Filled, Outline, Quiet }

/**
 * The only button shape: a pill, at least 48 dp tall.
 *
 * Disabled pills lose their fill and keep only an unlit edge with ash text, so they never look
 * tappable. Where an action can't run, prefer hiding it and saying why in one line.
 *
 * @param trailingIcon an optional small icon after the text, e.g. [GrilleIcons.Expand] on a
 *   pill that opens a choice.
 * @param contentDescription what TalkBack says instead of [text], for a pill whose text alone
 *   doesn't say enough ("Keep: Call the bank"). Pass it here: a description added to [modifier]
 *   is never spoken, because TalkBack skips it on a button that merges its text.
 * @param onLongClick what a long press does, for a pill that acts only on a hold (with
 *   [onClick] saying so); its length is the system's unless the caller sets one
 *   ([WithDeliberateHold])
 * @param maxLines how many lines [text] may take; with fewer than it needs it ends in an ellipsis
 *   and [trailingIcon] keeps its place
 */
// combinedClickable, for a pill that acts on a hold.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Filled,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = GrilleSpace.l),
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val colors = Grille.colors
    val filled = kind == PillKind.Filled && enabled
    val edge: Color? = when {
        kind == PillKind.Quiet || filled -> null
        !enabled -> colors.unlit
        else -> colors.ash
    }
    val content = when {
        filled -> colors.void
        kind == PillKind.Quiet -> if (enabled) colors.ash else colors.ash.copy(alpha = DISABLED_QUIET_ALPHA)
        enabled -> colors.ink
        else -> colors.ash
    }
    Row(
        modifier = modifier
            .heightIn(min = GrilleSpace.touch)
            .background(if (filled) colors.ink else Color.Transparent, CircleShape)
            .then(if (edge != null) Modifier.border(1.dp, edge, CircleShape) else Modifier)
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                }
            )
            // After the click, so the click action stays; it clears the semantics of the text inside.
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = Grille.type.label,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            // Within what the icon leaves, so a text cut short never pushes the icon out.
            modifier = if (maxLines == Int.MAX_VALUE) Modifier else Modifier.weight(1f, fill = false)
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = if (kind == PillKind.Outline && enabled) colors.ash else content,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** One labelled action, for [ActionRow], [ErrorNote] and the onboarding steps. */
class PillAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The one action row of sheets, dialogs and steps: a quiet way out on the left, the filled
 * primary on the right, each half the width. When either label doesn't fit its half (long
 * German labels, large fonts) the two stack at full width, primary on top. With only one of
 * them it takes the full width. Fills the width it is given; the caller adds the margins.
 *
 * @param secondaryKind [PillKind.Quiet] for Cancel or Skip; [PillKind.Outline] only where the
 *   secondary is a real alternative rather than a way out.
 */
@Composable
fun ActionRow(
    primary: PillAction?,
    modifier: Modifier = Modifier,
    secondary: PillAction? = null,
    secondaryKind: PillKind = PillKind.Quiet
) {
    if (primary == null && secondary == null) return
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            if (secondary != null) {
                PillButton(
                    text = secondary.label,
                    onClick = secondary.onClick,
                    kind = secondaryKind,
                    enabled = secondary.enabled
                )
            }
            if (primary != null) {
                PillButton(text = primary.label, onClick = primary.onClick, enabled = primary.enabled)
            }
        }
    ) { measurables, constraints ->
        val gap = GrilleSpace.s.roundToPx()
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gap * (measurables.size - 1)
        }
        if (measurables.size == 1) {
            val only = measurables[0].measure(Constraints.fixedWidth(width))
            return@Layout layout(width, only.height) { only.place(0, 0) }
        }
        // Children are [secondary, primary].
        val half = (width - gap) / 2
        if (measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= half }) {
            val (left, right) = measurables.map { it.measure(Constraints.fixedWidth(half)) }
            val height = maxOf(left.height, right.height)
            layout(width, height) {
                left.place(0, (height - left.height) / 2)
                right.place(width - half, (height - right.height) / 2)
            }
        } else {
            val below = measurables[0].measure(Constraints.fixedWidth(width))
            val top = measurables[1].measure(Constraints.fixedWidth(width))
            layout(width, top.height + gap + below.height) {
                top.place(0, 0)
                below.place(0, top.height + gap)
            }
        }
    }
}

/** A 48 dp round icon button. Always pass a [contentDescription]. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Grille.colors.ink
) {
    Box(
        modifier = modifier
            .size(GrilleSpace.touch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Grille.colors.unlit,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * A separator between list rows: rows, not cards. Exactly one physical pixel, so it shows at
 * the full [color] instead of being smeared over two pixel rows at half strength.
 */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Grille.colors.unlit) {
    val onePixel = with(LocalDensity.current) { 1f.toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(onePixel)
            .background(color)
    )
}

/**
 * Top bar: optional back button, a title, and trailing actions. Insets are the caller's job.
 *
 * @param titleContent drawn where the title goes, in place of [title]: a control that names what
 *   the screen is about and changes it, such as the recorder's Record as chip.
 */
@Composable
fun GrilleTopBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = "",
    backEnabled: Boolean = true,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = GrilleSpace.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            CircleIconButton(
                icon = GrilleIcons.Back,
                contentDescription = backDescription,
                onClick = onBack,
                enabled = backEnabled
            )
        } else {
            Spacer(Modifier.width(GrilleSpace.m))
        }
        when {
            titleContent != null -> Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GrilleSpace.s),
                contentAlignment = Alignment.CenterStart
            ) { titleContent() }
            title != null -> Text(
                text = title,
                style = Grille.type.label,
                color = Grille.colors.ink,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GrilleSpace.s)
            )
            else -> Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** Text tabs. The selected tab is ink with a dot under it; the rest are ash. */
@Composable
fun TextTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = GrilleSpace.margin),
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.l)
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .heightIn(min = GrilleSpace.touch)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tab,
                    style = Grille.type.body,
                    color = if (selected) Grille.colors.ink else Grille.colors.ash
                )
                Spacer(Modifier.height(GrilleSpace.xs))
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (selected) Grille.colors.ink else Color.Transparent, CircleShape)
                )
            }
        }
    }
}

/** Hardware-style switch: an outlined slot when off, a filled slot when on. */
@Composable
fun GrilleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = Grille.colors
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = tween(if (rememberReducedMotion()) 0 else 120),
        label = "switch_knob"
    )
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (checked && enabled) colors.ink else Color.Transparent, CircleShape)
            .border(
                1.dp,
                when {
                    !enabled -> colors.unlit
                    checked -> colors.ink
                    else -> colors.ash
                },
                CircleShape
            )
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    ) {
        Box(
            modifier = Modifier
                // Read in the layout phase, so the knob's slide does not recompose the switch.
                .offset { IntOffset(knobOffset.roundToPx(), 0) }
                .align(Alignment.CenterStart)
                .size(24.dp)
                .background(
                    when {
                        !enabled -> colors.unlit
                        checked -> colors.void
                        else -> colors.ash
                    },
                    CircleShape
                )
        )
    }
}

/** A row for one choice in a list, at least 48 dp tall; the dot on the left fills when selected. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = GrilleSpace.touch)
            .padding(horizontal = GrilleSpace.margin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.m)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(if (selected) Grille.colors.ink else Color.Transparent, CircleShape)
                .border(1.dp, if (selected) Grille.colors.ink else Grille.colors.ash, CircleShape)
        )
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Grille.type.body, color = Grille.colors.ink)
            if (supporting != null) {
                Text(text = supporting, style = Grille.type.caption, color = Grille.colors.ash)
            }
        }
    }
}

/**
 * Two to four short options as 48 dp pills; the selected one is filled. It is a selectable
 * group for TalkBack. Pills that don't fit the row move to the next one instead of being
 * squeezed (long German labels, large fonts).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val colors = Grille.colors
            Box(
                modifier = Modifier
                    .heightIn(min = GrilleSpace.touch)
                    .clip(CircleShape)
                    .background(if (selected) colors.ink else Color.Transparent, CircleShape)
                    .border(1.dp, if (selected) colors.ink else colors.ash, CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = GrilleSpace.m),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = Grille.type.label,
                    color = if (selected) colors.void else colors.ink
                )
            }
        }
    }
}

/** Small caption above a group of controls, in sentence case. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = Grille.type.caption, color = Grille.colors.ash, modifier = modifier)
}

/**
 * Text input with an underline instead of a box: ash, ink while focused, so a field shows as
 * a field even without its placeholder. 48 dp tall for one line.
 *
 * @param autoFocus focus the field and open the keyboard when it first appears, e.g. in an
 *   edit sheet. To also place the cursor or select the text, use the [TextFieldValue] overload.
 * @param capitalization [KeyboardCapitalization.None] for search; sentences everywhere else.
 * @param keyboardActions e.g. what the Search key does when [imeAction] is [ImeAction.Search].
 */
@Composable
fun GrilleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = Grille.type.body,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    autoFocus: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focusRequester = rememberAutoFocus(autoFocus)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = Grille.colors.ink),
        cursorBrush = SolidColor(Grille.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            FieldDecoration(
                empty = value.isEmpty(),
                placeholder = placeholder,
                singleLine = singleLine,
                textStyle = textStyle,
                interaction = interaction,
                inner = inner
            )
        }
    )
}

/**
 * [GrilleTextField] for a [TextFieldValue], which carries the selection: open an edit sheet with
 * the whole name selected (`TextRange(0, name.length)`), or the cursor at the end of a line.
 */
@Composable
fun GrilleTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = Grille.type.body,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    autoFocus: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focusRequester = rememberAutoFocus(autoFocus)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = Grille.colors.ink),
        cursorBrush = SolidColor(Grille.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            FieldDecoration(
                empty = value.text.isEmpty(),
                placeholder = placeholder,
                singleLine = singleLine,
                textStyle = textStyle,
                interaction = interaction,
                inner = inner
            )
        }
    )
}

/** A requester for the field; with [autoFocus] it takes focus and opens the keyboard once. */
@Composable
private fun rememberAutoFocus(autoFocus: Boolean): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
            // A field in a sheet gets focus before its window does; ask for the keyboard too.
            keyboard?.show()
        }
    }
    return focusRequester
}

@Composable
private fun FieldDecoration(
    empty: Boolean,
    placeholder: String?,
    singleLine: Boolean,
    textStyle: TextStyle,
    interaction: MutableInteractionSource,
    inner: @Composable () -> Unit
) {
    val focused by interaction.collectIsFocusedAsState()
    Column {
        Box(Modifier.padding(vertical = 12.dp)) {
            if (empty && placeholder != null) {
                Text(
                    text = placeholder,
                    style = textStyle,
                    color = Grille.colors.ash,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
            inner()
        }
        Hairline(color = if (focused) Grille.colors.ink else Grille.colors.ash)
    }
}

/**
 * A row of dots as a progress bar. [progress] in 0..1 fills from the left; null shows a
 * moving window (static with reduced motion).
 */
@Composable
fun DotProgress(progress: Float?, modifier: Modifier = Modifier, dots: Int = 24) {
    val colors = Grille.colors
    val reducedMotion = rememberReducedMotion()
    val window = if (progress == null && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "dot_progress")
        val position by transition.animateFloat(
            initialValue = 0f,
            targetValue = dots.toFloat(),
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "dot_progress_position"
        )
        position
    } else {
        null
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(dots) { index ->
            val lit = when {
                progress != null -> index < (progress.coerceIn(0f, 1f) * dots).toInt()
                window != null -> index >= window - 4 && index <= window
                else -> index < dots / 2
            }
            Box(Modifier.size(5.dp).background(if (lit) colors.ink else colors.mesh, CircleShape))
        }
    }
}

/** A disabled quiet pill is already ash, so it fades instead. */
private const val DISABLED_QUIET_ALPHA = 0.5f
