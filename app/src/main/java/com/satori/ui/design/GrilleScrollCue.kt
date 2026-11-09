package com.satori.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The cue that a list goes on: a thin ash line at the right edge of a list or sheet that scrolls,
 * as long as its thumb, where the thumb of a scroll bar would be. Grille allows no fade or gradient,
 * so before this the only cue was a row cut by the bottom edge, and nearly half of the stops of the
 * settings audit's sweeps cut none (its SCROLL-CUE; the owner said yes to a scroll cue on
 * 2026-10-15).
 *
 * It shows while more of the list follows below, while it moves, and for [LINGER_MS] after; at the
 * end of a list it goes once the list is still. A list that fits shows none.
 */
object ScrollCue {

    /** How long the line stays once the list stops at its end. */
    const val LINGER_MS = 1_000L

    /** The line's width. */
    val Width: Dp = 2.dp

    /** Its distance from the right edge. */
    val Inset: Dp = 3.dp

    /** The shortest the line gets on a long list, so it stays visible. */
    val MinLength: Dp = 24.dp

    /**
     * Whether the line shows.
     *
     * @param canScrollForward More of the list follows below
     * @param canScrollBackward Some of it is above
     * @param moving The list is being scrolled or flung
     * @param recentlyMoved It moved within the last [LINGER_MS]
     */
    fun visible(canScrollForward: Boolean, canScrollBackward: Boolean, moving: Boolean, recentlyMoved: Boolean): Boolean =
        (canScrollForward || canScrollBackward) && (canScrollForward || moving || recentlyMoved)

    /**
     * The line's place along a [viewport] that shows [offset] of [content], all in pixels, as a
     * scroll bar's thumb: as long as the share of the list that shows, at least [minLength], and
     * as far down as the list is scrolled. Null when the list fits.
     */
    fun thumb(viewport: Float, content: Float, offset: Float, minLength: Float): Thumb? {
        if (viewport <= 0f || content <= viewport) return null
        val length = (viewport * viewport / content).coerceIn(minOf(minLength, viewport), viewport)
        val travel = viewport - length
        val scrolled = (offset / (content - viewport)).coerceIn(0f, 1f)
        return Thumb(top = travel * scrolled, length = length)
    }

    /** Where the line is drawn: [top] from the top of the list, [length] long, in pixels. */
    data class Thumb(val top: Float, val length: Float)
}

/**
 * Draws the [ScrollCue] of [state] over what it is applied to. Put it before the verticalScroll
 * of the same [state], so it is drawn in the list's own frame and doesn't move with the rows.
 *
 * @param endInset How much of the list's bottom the line keeps clear of: the cover's camera band
 *   under a sheet whose list runs through it.
 */
fun Modifier.scrollCue(state: ScrollState, endInset: Dp = 0.dp): Modifier = composed {
    val moving = state.isScrollInProgress
    val alpha = cueAlpha(state.canScrollForward, state.canScrollBackward, moving)
    val color = Grille.colors.ash
    drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val inset = endInset.toPx().coerceIn(0f, size.height)
        val viewport = size.height - inset
        val content = size.height + state.maxValue - inset
        val thumb = ScrollCue.thumb(viewport, content, state.value.toFloat(), ScrollCue.MinLength.toPx()) ?: return@drawWithContent
        drawCueLine(color.copy(alpha = color.alpha * alpha), thumb)
    }
}

/**
 * The [ScrollCue] of a lazy list, whose length is read from the rows it shows: the average of
 * those rows stands in for the ones off screen.
 */
fun Modifier.scrollCue(state: LazyListState): Modifier = composed {
    val moving = state.isScrollInProgress
    val alpha = cueAlpha(state.canScrollForward, state.canScrollBackward, moving)
    val color = Grille.colors.ash
    val extent by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val shown = info.visibleItemsInfo
            if (shown.isEmpty()) {
                null
            } else {
                val average = shown.sumOf { it.size }.toFloat() / shown.size
                val content = average * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding
                val offset = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
                content to offset
            }
        }
    }
    drawWithContent {
        drawContent()
        val (content, offset) = extent ?: return@drawWithContent
        if (alpha <= 0f) return@drawWithContent
        val thumb = ScrollCue.thumb(size.height, content, offset, ScrollCue.MinLength.toPx()) ?: return@drawWithContent
        drawCueLine(color.copy(alpha = color.alpha * alpha), thumb)
    }
}

/** How strongly the line shows now, fading in and out over a short time. */
@Composable
private fun cueAlpha(canScrollForward: Boolean, canScrollBackward: Boolean, moving: Boolean): Float {
    var recentlyMoved by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) {
            recentlyMoved = true
        } else if (recentlyMoved) {
            delay(ScrollCue.LINGER_MS)
            recentlyMoved = false
        }
    }
    val shows = ScrollCue.visible(canScrollForward, canScrollBackward, moving, recentlyMoved)
    val alpha by animateFloatAsState(
        targetValue = if (shows) 1f else 0f,
        animationSpec = tween(if (rememberReducedMotion()) 0 else CUE_FADE_MS),
        label = "scroll_cue"
    )
    return alpha
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCueLine(color: androidx.compose.ui.graphics.Color, thumb: ScrollCue.Thumb) {
    val width = ScrollCue.Width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width - ScrollCue.Inset.toPx(), thumb.top),
        size = Size(width, thumb.length),
        cornerRadius = CornerRadius(width / 2, width / 2)
    )
}

private const val CUE_FADE_MS = 150
