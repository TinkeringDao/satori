package com.satori.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadState
import com.satori.presentation.firstrun.FirstRunActions
import com.satori.presentation.firstrun.FirstRunQuiet
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.ui.R
import com.satori.ui.components.OnboardingPageIndicator
import com.satori.ui.components.rememberMicrophoneRequest
import com.satori.ui.design.DotProgress
import com.satori.ui.design.DotoNumber
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.TabularDigits
import com.satori.ui.design.rememberReducedMotion

/** Below this height (the razr cover screen) a step's spacing is tighter. */
private val COMPACT_STEP_HEIGHT = 560.dp

/** The row of the page dots at the top of a setup screen; on the cover's short screen a little less. */
private val PAGE_DOTS_ROW = 40.dp
private val PAGE_DOTS_ROW_COMPACT = 32.dp

/** Megabytes as the download screens have always counted them (10^6 bytes). */
private const val BYTES_PER_MB = 1_000_000L

private const val TAG = "SatoriFirstRun"

/**
 * The first run (design 3.4-3.6): Get ready, then Getting ready, which turns into Ready once the
 * files are checked. It draws [state] and nothing else: what it shows is the state's
 * ([FirstRunUiState]), what it says is [firstRunCopy]'s, and every tap goes to [actions], but for
 * what only a screen can do: Android's microphone window and the app's settings page (read again
 * whenever the screen comes back), Android's storage settings, and Record, [onRecord].
 *
 * There is no back arrow: Android's Back leaves the app, and the download goes on. While Getting
 * ready is watched during the download the screen stays on ([FirstRunUiState.keepScreenOn]). Get
 * ready and Getting ready cross-fade, the primary pill in its place; with animations off they
 * switch at once. The primary takes no tap before [FirstRunUiState.tapsFromMs] ([nowMs] is that
 * clock), after the screen changed by itself.
 *
 * @param onRecord Ready's Record: create the recording and open the recorder, which starts at once,
 *   as home's grille does
 * @param nowMs The clock [FirstRunUiState.tapsFromMs] is on: [SystemClock.uptimeMillis], the
 *   clock of Android's touch events
 */
@Composable
fun OnboardingScreen(
    state: FirstRunUiState,
    actions: FirstRunActions,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = SystemClock::uptimeMillis
) {
    val microphone = rememberMicrophoneRequest(actions::microphoneAnswered, actions::microphoneRechecked)
    KeepScreenOn(state.keepScreenOn)
    val reducedMotion = rememberReducedMotion()
    AnimatedContent(
        targetState = state,
        contentKey = { it.screen },
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None else fadeIn() togetherWith fadeOut()
        },
        label = "first_run_screen"
    ) { shown ->
        val acceptsTap = { shieldLets(shown, nowMs()) }
        when (shown.screen) {
            FirstRunScreen.GET_READY -> GetReadyScreen(shown, actions, acceptsTap)
            FirstRunScreen.GETTING_READY -> GettingReadyScreen(shown, actions, microphone, onRecord, acceptsTap)
            // The first run is over: the caller goes on to home.
            FirstRunScreen.HOME -> Unit
        }
    }
}

/** Whether a tap on the primary at [nowMs] counts; one the shield swallows is logged, in ms. */
private fun shieldLets(state: FirstRunUiState, nowMs: Long): Boolean {
    val accepts = state.acceptsTaps(nowMs)
    if (!accepts) Log.i(TAG, "Tap shielded: ${state.tapsFromMs - nowMs} ms of the shield left")
    return accepts
}

/**
 * Keeps the screen from timing out while [on]: View.keepScreenOn, which holds the window's
 * FLAG_KEEP_SCREEN_ON while this view is shown, so only while the screen is watched.
 */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

// ── Shared by the setup screens ─────────────────────────────────────────────────

/** What [this] says, formatted with its values. */
@Composable
internal fun CopyRes.text(): String =
    if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

/**
 * A setup screen's root: its test tag ([FirstRunTags]), and every test tag under it as a resource
 * id, so the emulator's checks find the screen, its primary and its quiet control by name.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.firstRunRoot(tag: String): Modifier =
    semantics { testTagsAsResourceId = true }.testTag(tag)

/** The page dots, top right: three dots, three steps, the third the first recording. */
@Composable
internal fun FirstRunPageDots(page: Int, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) PAGE_DOTS_ROW_COMPACT else PAGE_DOTS_ROW)
            .padding(horizontal = GrilleSpace.margin),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (page != FirstRunFlow.NO_PAGE) {
            OnboardingPageIndicator(totalPages = FirstRunFlow.PAGE_COUNT, currentPage = page)
        }
    }
}

/**
 * The one quiet control, a text in ash in the content (rule R7: never beside or under the
 * primary). Its pill reaches into the margin, so its text lines up with the lines above it: at the
 * start, or at the end of a row ([alignEnd]).
 */
@Composable
internal fun QuietLink(label: String, onClick: () -> Unit, alignEnd: Boolean = false) {
    PillButton(
        text = label,
        onClick = onClick,
        kind = PillKind.Quiet,
        contentPadding = PaddingValues(horizontal = GrilleSpace.l),
        modifier = Modifier
            .offset(x = if (alignEnd) GrilleSpace.l else -GrilleSpace.l)
            .testTag(FirstRunTags.QUIET)
    )
}

/** Android's storage settings, where space can be freed; its main settings where a phone has no such page. */
internal fun openStorageSettings(context: Context) {
    val storage = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(storage)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No settings app at all: the space is freed by hand.
        }
    }
}

/** What the quiet control [quiet] asks of the first run. */
internal fun quietTap(quiet: FirstRunQuiet?, actions: FirstRunActions) {
    when (quiet) {
        FirstRunQuiet.WAIT_FOR_WIFI -> actions.waitForWifi()
        FirstRunQuiet.PAUSE -> actions.pause()
        FirstRunQuiet.CONTINUE -> actions.resume()
        FirstRunQuiet.TRY_NOW -> actions.retry()
        FirstRunQuiet.LOOK_AROUND -> actions.lookAround()
        null -> Unit
    }
}

/**
 * How much room a step's content has: [compact] on the cover's short screen, and the [viewport] it
 * is drawn in, without scrolling.
 */
internal class StepSpace(val compact: Boolean, val viewport: Dp)

/**
 * The first of [variants] that fits [viewport] without scrolling, or the last when none does: a
 * setup screen gives way in steps where it would scroll (design 3.3, rule 9), so that on the
 * cover's short screen its one decision is not cut off under the fold. Each variant is measured
 * only until one fits, and only the one chosen is placed: the others are neither drawn nor heard
 * by TalkBack, nor seen by the emulator's checks.
 */
@Composable
internal fun FirstFit(viewport: Dp, variants: List<@Composable () -> Unit>) {
    Layout(contents = variants.map { variant -> { Column(Modifier.fillMaxWidth()) { variant() } } }) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val limit = viewport.roundToPx()
        var chosen: Placeable? = null
        for ((index, variant) in measurables.withIndex()) {
            val placeable = variant.single().measure(loose)
            chosen = placeable
            if (placeable.height <= limit || index == measurables.lastIndex) break
        }
        val shown = checkNotNull(chosen)
        layout(shown.width, shown.height) { shown.place(0, 0) }
    }
}

/**
 * Frame for a setup step, and for the AI model's own screen, on the void background.
 *
 * [content] scrolls with 24 dp margins under [header]; [footer] is docked at the bottom, so the
 * next action is always in the same place and reachable without scrolling, also on the cover. The
 * hairline above the footer only shows while there is more content below the fold. On the cover's
 * short screen ([StepSpace.compact]) the space around the content is tighter.
 *
 * @param footer The docked footer: the setup's caption and primary ([com.satori.ui.components.DockedFooter]),
 *   or the AI model screen's pills; null for none
 * @param header Drawn above the scrolling content, the page dots or a top bar; told whether the
 *   screen is short
 */
@Composable
internal fun OnboardingStepLayout(
    footer: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    header: @Composable (compact: Boolean) -> Unit = {},
    content: @Composable ColumnScope.(space: StepSpace) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        val compact = maxHeight < COMPACT_STEP_HEIGHT
        val scroll = rememberScrollState()
        val padding = if (compact) GrilleSpace.s else GrilleSpace.m
        Column(Modifier.fillMaxSize()) {
            header(compact)
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val space = StepSpace(compact, viewport = maxHeight - padding * 2)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = GrilleSpace.margin, vertical = padding)
                ) { content(space) }
            }
            if (footer != null) {
                // Read in the draw phase, so the line is right from the first frame.
                Hairline(Modifier.drawWithContent { if (scroll.canScrollForward) drawContent() })
                Box(
                    Modifier.padding(
                        horizontal = GrilleSpace.margin,
                        vertical = if (compact) 12.dp else GrilleSpace.m
                    )
                ) { footer() }
            }
        }
    }
}

// ── The AI model's own screen (Settings, a recording without a model) ────────────

/**
 * Tells the layouts of a download apart and stays the same while progress updates arrive. Used as
 * a `key` so each layout starts scrolled to the top.
 */
internal fun DownloadState.onboardingStage(): Int = when {
    this == DownloadState.Idle -> 0
    this is DownloadState.Downloading -> 1
    this == DownloadState.Completed -> 2
    else -> 3
}

/** A step title with an optional short explanation underneath. */
@Composable
internal fun OnboardingHeading(title: String, body: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(text = title, style = Grille.type.title, color = Grille.colors.ink)
        if (body != null) {
            Spacer(Modifier.height(GrilleSpace.s))
            Text(text = body, style = Grille.type.body, color = Grille.colors.ash)
        }
    }
}

/**
 * What a model download needs, in the step's body text: its size, and that it needs an internet
 * connection this once.
 */
@Composable
internal fun OnboardingDownloadNote(size: String) {
    Text(
        text = stringResource(R.string.g_onb_download_note, size),
        style = Grille.type.body,
        color = Grille.colors.ash
    )
}

/**
 * A running model download: title, Doto percentage, dot progress and megabytes. While the total
 * size is unknown the dots run and only the megabytes received so far are shown.
 *
 * @param showResumeNote show "Resuming from … MB" while a resumed download is still at 0 %.
 * @param note A line under it: the files being checked, or why the download waits
 */
@Composable
internal fun OnboardingDownloadProgress(
    title: String,
    state: DownloadState.Downloading,
    showResumeNote: Boolean = false,
    note: String? = null
) {
    val progress = state.progress
    val totalKnown = progress.totalBytes > 0
    val percent = progress.percentage.coerceIn(0, 100)

    Column(Modifier.fillMaxWidth()) {
        OnboardingHeading(title = title)
        Spacer(Modifier.height(GrilleSpace.l))
        if (totalKnown) {
            DotoNumber(
                text = stringResource(R.string.g_onb_percent, percent),
                style = Grille.type.displayNumber
            )
            Spacer(Modifier.height(GrilleSpace.s))
        }
        DotProgress(progress = if (totalKnown) percent / 100f else null)

        val megabytes = when {
            totalKnown -> stringResource(
                R.string.g_onb_megabytes,
                progress.bytesDownloaded / BYTES_PER_MB,
                progress.totalBytes / BYTES_PER_MB
            )
            progress.bytesDownloaded > 0 -> stringResource(
                R.string.g_onb_megabytes_so_far,
                progress.bytesDownloaded / BYTES_PER_MB
            )
            else -> null
        }
        if (megabytes != null) {
            Spacer(Modifier.height(GrilleSpace.m))
            // A size is a small readout: Space Grotesk, with digits that hold still as it counts.
            Text(
                text = megabytes,
                style = Grille.type.body.copy(fontFeatureSettings = TabularDigits),
                color = Grille.colors.ash
            )
        }

        Spacer(Modifier.height(GrilleSpace.m))
        if (note != null) {
            OnboardingCaption(note)
        } else if (showResumeNote && progress.percentage == 0 && progress.bytesDownloaded > 0) {
            OnboardingCaption(stringResource(R.string.g_onb_resuming, progress.bytesDownloaded / BYTES_PER_MB))
        }
    }
}

/**
 * A failed download, as an [ErrorNote] that names the cause and what to do about it; its actions
 * are the step's docked pills.
 *
 * @param size the download's size, for the not enough storage message.
 */
@Composable
internal fun OnboardingDownloadFailed(reason: DownloadFailure, size: String) {
    ErrorNote(
        title = reason.title(),
        text = reason.body(size),
        // The step's content already has its margins.
        horizontalMargin = 0.dp
    )
}

@Composable
private fun OnboardingCaption(text: String) {
    Text(text = text, style = Grille.type.caption, color = Grille.colors.ash)
}
