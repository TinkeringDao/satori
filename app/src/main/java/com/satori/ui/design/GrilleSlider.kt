package com.satori.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Diameter of the [DotSlider] thumb. The first and last dots sit half of it inside the
 * slider's edges, so inset the slider by `margin - DotSliderThumbSize / 2` to line them up
 * with text on the margin.
 */
val DotSliderThumbSize = 20.dp

/**
 * A dot-step slider: a row of dots, ink up to the value and [GrilleColors.mesh] after it, with
 * an ink dot as the thumb.
 *
 * It is a Material slider underneath, so dragging, tapping, keyboard keys and accessibility
 * (the value range and TalkBack's adjust actions) work as usual. Give it a
 * `contentDescription` (what it sets) and a `stateDescription` (the value as people read it)
 * through [modifier]. The slider is 48 dp tall, so the whole strip is the touch target.
 *
 * With [steps], every position gets its own dot when the dots can be at least 10 dp apart;
 * otherwise the dots are spaced evenly at that pitch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    // Material3 1.3: the overload with thumb and track slots (experimental, like SliderState).
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        steps = steps,
        thumb = { SliderThumb(enabled = enabled) },
        track = { sliderState ->
            SliderDots(
                positions = if (sliderState.steps > 0) sliderState.steps + 2 else 0,
                enabled = enabled,
                // Read while drawing, so a moving thumb redraws the dots without recomposing.
                fraction = {
                    val start = sliderState.valueRange.start
                    val span = sliderState.valueRange.endInclusive - start
                    if (span > 0f) ((sliderState.value - start) / span).coerceIn(0f, 1f) else 0f
                }
            )
        },
        valueRange = valueRange
    )
}

@Composable
private fun SliderThumb(enabled: Boolean) {
    Box(
        Modifier
            .size(DotSliderThumbSize)
            .background(if (enabled) Grille.colors.ink else Grille.colors.ash, CircleShape)
    )
}

/**
 * The slider lays the track out between the thumb's centre at the start and at the end of the
 * range, so the dot for fraction f sits at f of the track width (mirrored right to left).
 */
@Composable
private fun SliderDots(positions: Int, enabled: Boolean, fraction: () -> Float) {
    val lit = if (enabled) Grille.colors.ink else Grille.colors.ash
    val off = Grille.colors.mesh
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SliderTrackHeight)
    ) {
        val width = size.width
        val pitch = SliderDotPitch.toPx()
        val count = if (positions >= 2 && width / (positions - 1) >= pitch) {
            positions
        } else {
            (width / pitch).toInt() + 1
        }
        if (count < 2) return@Canvas

        val radius = SliderDotSize.toPx() / 2f
        val litUpTo = fraction() + SLIDER_LIT_TOLERANCE
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (i in 0 until count) {
            val position = i.toFloat() / (count - 1)
            val x = width * (if (rtl) 1f - position else position)
            drawCircle(
                color = if (position <= litUpTo) lit else off,
                radius = radius,
                center = Offset(x, center.y)
            )
        }
    }
}

/** Track height; the slider takes the taller of track and thumb, so this is the touch target. */
private val SliderTrackHeight = GrilleSpace.touch

/** Same dot as [DotProgress]. */
private val SliderDotSize = 5.dp

private val SliderDotPitch = 10.dp

/** Absorbs float error so the dot under the thumb counts as lit. */
private const val SLIDER_LIT_TOLERANCE = 0.001f
