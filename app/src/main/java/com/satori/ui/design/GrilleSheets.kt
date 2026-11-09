package com.satori.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.satori.ui.R

// Sheets and dialogs. The rule for their actions: one ActionRow, a quiet way out on the left
// and the filled primary on the right, pinned below the content that scrolls. Sheets can also
// be closed by swiping down, tapping the scrim or Back; TalkBack's name for the scrim is
// close_sheet, which values-de/strings_grille.xml corrects for German.

/** Five dots as the sheet's drag handle. */
@Composable
private fun DotHandle() {
    Row(
        modifier = Modifier.padding(vertical = GrilleSpace.m),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(5) {
            Box(Modifier.size(5.dp).background(Grille.colors.mesh, CircleShape))
        }
    }
}

/**
 * Whether the sheet drawn here is small ([GrilleSheet]): the keyboard is up on the cover held with
 * its cameras at the top, so the sheet has only the strip between the camera band and the
 * keyboard. Its content leaves out what it can then, as a [FormSheet] its title.
 */
private val LocalSmallSheet = compositionLocalOf { false }

/**
 * How much room the scrolling list of a sheet that runs through the cover's camera band
 * ([GrilleSheet] with throughBand) leaves at its end, inside what scrolls, so its last row can be
 * scrolled clear of the cameras: the band's depth beyond the navigation bar at the bottom. None in
 * every other sheet.
 */
val LocalSheetBandClearance = compositionLocalOf { 0.dp }

/**
 * Whether the keyboard is up while [band] runs along the top ([sheetIsSmall]). Read in the sheet's
 * own window, whose insets have the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun keyboardUnderBand(band: CameraBand?): Boolean = sheetIsSmall(band, keyboardUp = WindowInsets.isImeVisible)

/**
 * Whether a sheet is small ([GrilleSheet]): the keyboard is up while the camera [band] runs along
 * the top, the cover upside down, so the sheet has only the strip between the two, some 290 px on
 * the razr, about two rows of controls. Held upright the keyboard covers the band, beside one on a
 * side it leaves the sheet its height, and the inner screen has no band.
 */
internal fun sheetIsSmall(band: CameraBand?, keyboardUp: Boolean): Boolean = keyboardUp && band?.atTop == true

/**
 * Bottom sheet on graphite with a 24 dp top radius, over the theme's [GrilleColors.scrim].
 * [content] is a column that ends above the navigation bar; it brings its own margins. A sheet
 * as tall as the screen (the cover screen, a long list) stops below the status bar. For a
 * title, fields and an action row use [FormSheet]; for a question, [ConfirmSheet]; for a list
 * of actions, [MenuRow]s.
 *
 * On the cover held with its cameras at the top, the keyboard leaves a sheet only the strip
 * between the camera band and itself: the sheet is small then ([LocalSmallSheet]), without its
 * drag handle and with less space below its content, so a form's field and its actions fit (the
 * 0.6.1 phone review: Rename's field was cut through its letters, its title gone). Once the
 * keyboard is down it is as before; held the other way up, the keyboard covers the band and the
 * sheet has room.
 *
 * @param throughBand For a sheet that is a list and nothing else, with no pinned action (a list of
 *   choices or actions): on the cover held upright its list runs on into the camera band to the
 *   bottom of the screen, beside the cameras, instead of ending above a blank band, and its end
 *   is kept clear of the cameras by [LocalSheetBandClearance], inside what scrolls. On the phone
 *   on 2026-10-14 the recording page's More options ended 380 px above the bottom, its next row
 *   cut to 28 px with no text showing, and a swipe in the band did nothing. A sheet with an
 *   action row keeps it above the band, where the filled pill on the right would sit under the
 *   cameras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrilleSheet(
    onDismiss: () -> Unit,
    throughBand: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // On the cover in Motorola's full-screen mode the sheet's window takes in the camera band too.
    val band = cameraBand()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Material keeps only the content clear of the bottom bars; this keeps the sheet's top
        // clear of the status bar, or of the camera band where that reaches further, and a sheet
        // beside a band on a side of the cover clear of it. A shorter sheet sits where it did.
        modifier = Modifier.windowInsetsPadding(
            if (band != null && band.edge != BandEdge.BOTTOM) WindowInsets.statusBars.union(band.insets()) else WindowInsets.statusBars
        ),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Grille.colors.graphite,
        contentColor = Grille.colors.ink,
        tonalElevation = 0.dp,
        scrimColor = Grille.colors.scrim,
        dragHandle = { if (keyboardUnderBand(band)) Spacer(Modifier.height(GrilleSpace.s)) else DotHandle() }
    ) {
        val small = keyboardUnderBand(band)
        // Above the navigation bar, or above the camera band where that reaches further; the
        // keyboard, which covers the band, lifts the content only by what it reaches past both. A
        // list that runs through the band ends above the navigation bar and keeps the rest of the
        // band at the end of what scrolls.
        val bottomBand = band?.takeIf { it.edge == BandEdge.BOTTOM }
        val clearance = if (throughBand && bottomBand != null) sheetBandClearance(bottomBand) else 0.dp
        CompositionLocalProvider(LocalSmallSheet provides small, LocalSheetBandClearance provides clearance) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(
                        if (bottomBand != null && !throughBand) {
                            WindowInsets.navigationBars.union(bottomBand.insets())
                        } else {
                            WindowInsets.navigationBars
                        }
                    )
                    .padding(bottom = if (small) GrilleSpace.s else GrilleSpace.m),
                content = content
            )
        }
    }
}

/**
 * What a list running through the camera [band] at the bottom keeps clear at its end
 * ([LocalSheetBandClearance]): the band's depth past the navigation bar, which the sheet's content
 * stays above anyway.
 */
@Composable
private fun sheetBandClearance(band: CameraBand): Dp {
    val density = LocalDensity.current
    val navigationBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return sheetBandClearance(band.depth, navigationBar)
}

/** What is left of a band [band] deep once the [navigationBar] inside it is taken off. */
internal fun sheetBandClearance(band: Dp, navigationBar: Dp): Dp = (band - navigationBar).coerceAtLeast(0.dp)

/**
 * A sheet with a title and content that scrolls, and the [ActionRow] pinned below it, so the
 * action stays above the keyboard. Fields go in [content], each with [FormField]. In a small sheet
 * (the keyboard up on the cover held with its cameras at the top, [GrilleSheet]) the title is left
 * out and the actions come closer, so the field being typed in and the actions fit; the title is
 * back once the keyboard is down.
 *
 * @param dismissLabel the quiet way out, Cancel by default; null leaves the confirm pill alone
 *   at full width (the sheet still closes by swipe, scrim and Back).
 * @param keepsTitle Whether the title stays in a small sheet too: a question's, which is what it
 *   asks.
 */
@Composable
fun FormSheet(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = stringResource(R.string.g_cancel),
    keepsTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    GrilleSheet(onDismiss = onDismiss) {
        val small = LocalSmallSheet.current
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .scrollCue(scroll)
                .verticalScroll(scroll)
                .padding(horizontal = GrilleSpace.margin)
        ) {
            if (!small || keepsTitle) {
                Text(
                    text = title,
                    style = Grille.type.title,
                    color = Grille.colors.ink,
                    modifier = Modifier.semantics { heading() }
                )
            }
            content()
        }
        Spacer(Modifier.height(if (small) GrilleSpace.s else GrilleSpace.l))
        ActionRow(
            primary = PillAction(confirmLabel, enabled = confirmEnabled, onClick = onConfirm),
            secondary = dismissLabel?.let { PillAction(it, onClick = onDismiss) },
            modifier = Modifier.padding(horizontal = GrilleSpace.margin)
        )
    }
}

/** A labelled field inside a [FormSheet]: space, the label, then [content]. */
@Composable
fun FormField(label: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(GrilleSpace.l))
    FieldLabel(label)
    Spacer(Modifier.height(GrilleSpace.s))
    content()
}

/**
 * Asks before something happens that is hard to undo, such as deleting a recording: a
 * question, one plain sentence, and Cancel next to the filled [confirmLabel].
 */
@Composable
fun ConfirmSheet(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.g_cancel)
) {
    FormSheet(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = dismissLabel,
        keepsTitle = true
    ) {
        Spacer(Modifier.height(GrilleSpace.s))
        Text(text = text, style = Grille.type.body, color = Grille.colors.ash)
    }
}

/**
 * One action in a sheet that lists actions (the details menu, a list of modes): one line of
 * body text, 56 dp tall. Separate rows with a [Hairline] on the margins. [supporting] is for a
 * disabled row, to say why, or for a short fact about the choice (the language of a translation).
 *
 * @param quiet an action off to the side of the others, such as "Edit categories" under a list of
 *   categories: ash text, still tappable.
 * @param horizontalPadding the margins of its text: the screen's, or none inside a sheet whose
 *   content has them already (a form).
 */
@Composable
fun MenuRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    quiet: Boolean = false,
    horizontalPadding: Dp = GrilleSpace.margin
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = Grille.type.body,
            color = if (enabled && !quiet) Grille.colors.ink else Grille.colors.ash
        )
        if (supporting != null) {
            Text(text = supporting, style = Grille.type.caption, color = Grille.colors.ash)
        }
    }
}

/**
 * A centred question that must be answered before anything else, e.g. while recording. Prefer
 * [ConfirmSheet], which keeps the buttons under the thumb. The same [ActionRow] as the sheets:
 * [dismissLabel] quiet on the left, [confirmLabel] filled on the right; without
 * [dismissLabel] the confirm pill is alone at full width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrilleDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        // The window's dim matches the sheets' scrim.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val dim = Grille.colors.scrim.alpha
        SideEffect { window?.setDimAmount(dim) }
        // The dialog's window is centred on the screen; on the cover, room as deep as the camera
        // band on the band's side centres the dialog in the part of the screen beside the band.
        val band = cameraBand()
        Column(
            Modifier
                .windowInsetsPadding(band?.insets() ?: WindowInsets(0.dp))
                .background(Grille.colors.graphite, GrilleShape)
                .padding(GrilleSpace.l)
        ) {
            Text(
                text = title,
                style = Grille.type.heading,
                color = Grille.colors.ink,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(GrilleSpace.m))
            Text(text = text, style = Grille.type.body, color = Grille.colors.ash)
            Spacer(Modifier.height(GrilleSpace.l))
            ActionRow(
                primary = PillAction(confirmLabel, onClick = onConfirm),
                secondary = dismissLabel?.let { PillAction(it, onClick = onDismiss) }
            )
        }
    }
}
