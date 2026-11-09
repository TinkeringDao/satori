package com.satori.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.satori.ui.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

// How the app tells the user something: an ErrorNote for a problem, which stays until it is
// dismissed or fixed, and a notice for a short confirmation, which goes away by itself. Red is
// never used for either: it only means the microphone is live.

/**
 * A problem the user should know about, in the app's language, ending with what to do next.
 * The one error design of the app: the warning mark, an optional [title], the sentence, an
 * optional way forward ([action]) and an optional close button ([onDismiss]). It has no
 * background: put it in the flow of the screen, under what it is about. TalkBack announces
 * it politely when it appears or changes.
 *
 * Never pass an exception message: log it, and say what it means for the user.
 *
 * @param title a short heading for a failure that fills a screen, e.g. "Download failed".
 * @param action the way forward, e.g. "Download again"; a pill under the text.
 * @param actionKind [PillKind.Filled] when the action is the main way on, as it usually is;
 *   [PillKind.Outline] when the screen already has its primary action.
 * @param onDismiss shows a close button when the problem can just be acknowledged.
 * @param horizontalMargin the note's own side margin; 0 where its parent already has one
 *   (the cover recorder's column, an onboarding step).
 */
@Composable
fun ErrorNote(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: PillAction? = null,
    actionKind: PillKind = PillKind.Filled,
    onDismiss: (() -> Unit)? = null,
    dismissDescription: String = stringResource(R.string.g_dismiss),
    horizontalMargin: Dp = GrilleSpace.margin
) {
    // With a close button the first line sits on the button's centre line.
    val lineTop = if (onDismiss != null) (GrilleSpace.touch - LINE_HEIGHT) / 2 else 0.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalMargin,
                // The close button's icon lines up with the margin, not its 48 dp box.
                end = if (onDismiss != null) (horizontalMargin - 13.dp).coerceAtLeast(0.dp) else horizontalMargin,
                top = GrilleSpace.s,
                bottom = GrilleSpace.s
            ),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = GrilleIcons.Problem,
            contentDescription = null,
            tint = Grille.colors.ink,
            modifier = Modifier
                .padding(top = lineTop + (LINE_HEIGHT - ICON_SIZE) / 2)
                .size(ICON_SIZE)
        )
        Spacer(Modifier.width(GrilleSpace.m))
        Column(
            Modifier
                .weight(1f)
                .padding(top = lineTop)
        ) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                if (title != null) {
                    Text(text = title, style = Grille.type.heading, color = Grille.colors.ink)
                    Spacer(Modifier.height(GrilleSpace.xs))
                }
                Text(
                    text = text,
                    style = Grille.type.body,
                    color = if (title != null) Grille.colors.ash else Grille.colors.ink
                )
            }
            if (action != null) {
                Spacer(Modifier.height(GrilleSpace.m))
                PillButton(
                    text = action.label,
                    onClick = action.onClick,
                    kind = actionKind,
                    enabled = action.enabled
                )
            }
        }
        if (onDismiss != null) {
            Spacer(Modifier.width(GrilleSpace.s))
            CircleIconButton(icon = GrilleIcons.Close, contentDescription = dismissDescription, onClick = onDismiss)
        }
    }
}

/** How a notice ended. */
enum class NoticeResult { Dismissed, ActionPerformed }

/** One notice on screen; [NoticeHost] draws it, [NoticeState.show] creates it. */
@Stable
class Notice internal constructor(
    val text: String,
    val actionLabel: String?,
    private val result: CompletableDeferred<NoticeResult>
) {
    fun performAction() {
        result.complete(NoticeResult.ActionPerformed)
    }

    fun dismiss() {
        result.complete(NoticeResult.Dismissed)
    }

    internal suspend fun await(): NoticeResult = result.await()
}

/**
 * What a [NoticeHost] shows: at most one short confirmation at a time. A new notice replaces
 * the one on screen. Keep it in the screen, not in a ViewModel: `rememberNoticeState()`.
 */
@Stable
class NoticeState {
    var current: Notice? by mutableStateOf(null)
        private set

    /**
     * Shows [text], e.g. "File saved", until it times out, is replaced or its action is taken,
     * and returns how it ended. Call it from a coroutine of the screen
     * (`rememberCoroutineScope`).
     */
    suspend fun show(text: String, actionLabel: String? = null): NoticeResult {
        current?.dismiss()
        val notice = Notice(text, actionLabel, CompletableDeferred())
        current = notice
        try {
            return notice.await()
        } finally {
            if (current === notice) current = null
        }
    }
}

@Composable
fun rememberNoticeState(): NoticeState = remember { NoticeState() }

/**
 * Shows the notices of [state]: a one-line strip on graphite with a hairline above, at the
 * bottom of the screen. Put it in a Box over the screen's content, aligned to the bottom, so
 * it never pushes the content around:
 *
 * ```
 * val notices = rememberNoticeState()
 * Box(Modifier.fillMaxSize()) {
 *     Content()
 *     NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
 * }
 * scope.launch { notices.show(context.getString(R.string.g_details_saved)) }
 * ```
 *
 * TalkBack announces a notice politely and can dismiss it. It stays 4 s, or as long as the
 * accessibility settings ask for; it slides in and out unless animations are off.
 */
@Composable
fun NoticeHost(state: NoticeState, modifier: Modifier = Modifier) {
    val notice = state.current
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(
                accessibility?.calculateRecommendedTimeoutMillis(
                    NOTICE_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = notice.actionLabel != null
                ) ?: NOTICE_MS
            )
            notice.dismiss()
        }
    }
    val reducedMotion = rememberReducedMotion()
    AnimatedContent(
        targetState = notice,
        modifier = modifier,
        transitionSpec = {
            val transform = if (reducedMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInVertically(tween(NOTICE_MOTION_MS)) { it } + fadeIn(tween(NOTICE_MOTION_MS))) togetherWith
                    (slideOutVertically(tween(NOTICE_MOTION_MS)) { it } + fadeOut(tween(NOTICE_MOTION_MS)))
            }
            transform using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.BottomCenter,
        label = "notice"
    ) { shown ->
        if (shown != null) NoticeStrip(shown)
    }
}

@Composable
private fun NoticeStrip(notice: Notice) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Grille.colors.graphite)
    ) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GrilleSpace.touch)
                .padding(start = GrilleSpace.margin, end = if (notice.actionLabel != null) GrilleSpace.s else GrilleSpace.margin)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        notice.dismiss()
                        true
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = notice.text,
                style = Grille.type.body,
                color = Grille.colors.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            )
            notice.actionLabel?.let { label ->
                PillButton(
                    text = label,
                    onClick = notice::performAction,
                    kind = PillKind.Quiet,
                    contentPadding = PaddingValues(horizontal = GrilleSpace.m)
                )
            }
        }
    }
}

/** Line height of the body style, which the mark is centred on. */
private val LINE_HEIGHT = 24.dp

private val ICON_SIZE = 22.dp

/** How long a notice stays before it goes away by itself. */
private const val NOTICE_MS = 4_000L

private const val NOTICE_MOTION_MS = 150
