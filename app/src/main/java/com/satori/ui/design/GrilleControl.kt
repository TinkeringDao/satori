package com.satori.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** What the record grille shows. */
enum class GrilleState {
    /** Ready: a red disc in the centre, the record symbol, invites a tap. */
    Idle,
    /** Models are loading: dots sweep around the ring. Not tappable: the recording starts by itself. */
    Preparing,
    /** The microphone is live: dots light with the voice, the centre is a red square. */
    Recording,
    /** Recording stopped, its last words are being saved: a steady ring, not tappable. */
    Saving,
    /** Recording isn't possible right now. */
    Disabled
}

/**
 * The record control: a circular grille of dots, like a microphone mesh, with its action word
 * underneath ("Record", "Stop").
 *
 * Grille and word are one touch target and one TalkBack stop that says [label] once, as a
 * button. It takes taps only in [GrilleState.Idle] and [GrilleState.Recording]; while it is
 * preparing, saving or disabled it is a disabled button.
 *
 * With [holdToStop] a recording stops only on a long press of at least [HOLD_TO_STOP_MS], and a
 * tap calls [onHoldHint] instead: over the lock screen the screen stays on in a pocket, where a
 * brush would otherwise end the recording. TalkBack offers Stop as an action of its own then.
 *
 * @param label the action, spoken and shown under the grille.
 * @param level live audio level in 0..1, only used while [state] is [GrilleState.Recording].
 * @param size diameter of the dots; the word adds about 28 dp below it.
 * @param showLabel false hides the word but keeps its space, so the layout doesn't move
 *   between states, and keeps [label] for TalkBack. For states where the status line
 *   already says what is happening (getting ready, saving).
 * @param holdToStop whether a recording stops only on a long press; only used while [state] is
 *   [GrilleState.Recording].
 * @param onHoldHint called for a tap while [holdToStop] holds, to say how to stop.
 */
// combinedClickable with an interaction source of its own, which the press scale reads.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordGrille(
    state: GrilleState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    size: Dp = 112.dp,
    showLabel: Boolean = true,
    holdToStop: Boolean = false,
    onHoldHint: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = state == GrilleState.Idle || state == GrilleState.Recording
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = tween(if (rememberReducedMotion()) 0 else 90),
        label = "grille_press"
    )
    val act: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
    val input = if (holdToStop && state == GrilleState.Recording) {
        Modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onHoldHint,
                onLongClick = act
            )
            .clearAndSetSemantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(label) {
                        act()
                        true
                    }
                )
            }
    } else {
        Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = act
            )
            // Clears the word below, which says the label already, and keeps the click. A plain
            // description is never spoken while the word still has semantics of its own.
            .clearAndSetSemantics { contentDescription = label }
    }

    // A long press here takes at least HOLD_TO_STOP_MS, whatever the system's timeout.
    WithDeliberateHold {
        RecordGrilleContent(
            state = state,
            label = label,
            level = level,
            size = size,
            showLabel = showLabel,
            enabled = enabled,
            pressScale = pressScale,
            modifier = modifier.then(input)
        )
    }
}

/** The dots and the word of [RecordGrille], with its [modifier] holding the input. */
@Composable
private fun RecordGrilleContent(
    state: GrilleState,
    label: String,
    level: Float,
    size: Dp,
    showLabel: Boolean,
    enabled: Boolean,
    pressScale: Float,
    modifier: Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        GrilleDots(
            state = state,
            level = level,
            modifier = Modifier
                .size(size)
                .scale(pressScale)
        )
        Text(
            text = label,
            style = Grille.type.label,
            color = if (enabled) Grille.colors.ink else Grille.colors.ash,
            modifier = Modifier.alpha(if (showLabel) 1f else 0f)
        )
    }
}

/** The dots of [RecordGrille]: drawing only, no input or semantics. */
@Composable
private fun GrilleDots(state: GrilleState, level: Float, modifier: Modifier) {
    val colors = Grille.colors
    val reducedMotion = rememberReducedMotion()
    val smoothedLevel by animateFloatAsState(
        targetValue = if (state == GrilleState.Recording) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(if (reducedMotion) 0 else 110, easing = LinearEasing),
        label = "grille_level"
    )
    val sweep = if (state == GrilleState.Preparing && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "grille_sweep")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "grille_sweep_angle"
        )
        angle
    } else {
        null
    }
    // Half way between off and lit, in both themes: the steady ring, the soft edge of the level.
    val half = lerp(colors.mesh, colors.ink, 0.5f)

    Canvas(modifier) {
        val n = GRID
        val spacing = this.size.minDimension / n
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val unlitRadius = spacing * 0.27f
        val litRadius = spacing * 0.34f

        for (i in 0 until n) {
            for (j in 0 until n) {
                val dot = Offset((i + 0.5f) * spacing, (j + 0.5f) * spacing)
                val dx = dot.x - center.x
                val dy = dot.y - center.y
                val distance = hypot(dx, dy)
                if (distance > radius - spacing * 0.25f) continue
                val t = distance / radius
                val inCentreSquare = abs(dx) <= spacing * 1.01f && abs(dy) <= spacing * 1.01f

                val (color, lit) = when (state) {
                    GrilleState.Idle ->
                        if (t <= RECORD_DISC) colors.rec to true else colors.mesh to false
                    GrilleState.Preparing -> when {
                        t <= CENTRE_DISC -> half to false
                        sweep == null -> (if (t > RING) half else colors.mesh) to false
                        else -> {
                            val angular = angularDistance(atan2(dy, dx), sweep)
                            if (t > 0.4f && angular < 0.7f) {
                                // The tail fades into the mesh, not into the background.
                                lerp(colors.mesh, colors.ink, 1f - angular / 0.7f) to true
                            } else {
                                colors.mesh to false
                            }
                        }
                    }
                    GrilleState.Recording -> when {
                        inCentreSquare -> colors.rec to true
                        else -> {
                            val reach = CENTRE_DISC + (1f - CENTRE_DISC) * smoothedLevel
                            when {
                                t <= reach - 0.08f -> colors.ink to true
                                t <= reach -> half to true
                                else -> colors.mesh to false
                            }
                        }
                    }
                    GrilleState.Saving ->
                        (if (t > RING) half else colors.mesh) to false
                    GrilleState.Disabled -> colors.mesh to false
                }
                drawCircle(color = color, radius = if (lit) litRadius else unlitRadius, center = dot)
            }
        }
    }
}

/**
 * Tape-counter timer: m:ss, h:mm:ss from one hour on, as every length in the app
 * ([formatDuration]). It holds still while recording and only the seconds move: at this size a
 * blinking colon reads as flicker. [RecIndicator]'s red dot says the mic is live. Tabular
 * digits keep the width steady except when a digit is added (10:00, 1:00:00).
 */
@Composable
fun RecTimer(
    millis: Long,
    live: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = Grille.type.timer
) {
    val colors = Grille.colors
    DotoNumber(
        text = formatDuration(millis),
        style = style,
        color = if (live || millis > 0) colors.ink else colors.ash,
        modifier = modifier
    )
}

/**
 * A dot and a short state text, e.g. "Recording". The dot is red only while [live]. TalkBack
 * announces a new text politely, so the automatic start and the end of finishing are heard.
 */
@Composable
fun RecIndicator(text: String, live: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (live) Grille.colors.rec else Grille.colors.mesh, CircleShape)
        )
        Text(text = text, style = Grille.type.label, color = if (live) Grille.colors.ink else Grille.colors.ash)
    }
}

/**
 * [content] with a long press of at least [HOLD_TO_STOP_MS], whatever the system's timeout: over the
 * lock screen, where the screen stays on in a pocket, [RecordGrille]'s hold to stop and the
 * recorder's Record as chip act only on a hold that long.
 */
@Composable
internal fun WithDeliberateHold(content: @Composable () -> Unit) {
    val viewConfiguration = LocalViewConfiguration.current
    val holdConfiguration = remember(viewConfiguration) { HoldConfiguration(viewConfiguration) }
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration, content = content)
}

/**
 * The system's view configuration with a long press of at least [HOLD_TO_STOP_MS], for
 * [WithDeliberateHold]: the system's timeout is a setting of the phone and can be shorter, short
 * enough for a hand in a pocket to reach by chance.
 */
private class HoldConfiguration(private val system: ViewConfiguration) : ViewConfiguration by system {
    override val longPressTimeoutMillis: Long
        get() = maxOf(system.longPressTimeoutMillis, HOLD_TO_STOP_MS)
}

/** The shortest long press that stops a recording, or switches it, over the lock screen. */
private const val HOLD_TO_STOP_MS = 500L

/** Grid size of the grille. 11 keeps dots legible from 88 dp to 140 dp. */
private const val GRID = 11

/** Share of the radius covered by the centre disc. */
private const val CENTRE_DISC = 0.3f

/**
 * Share of the radius covered by the red record disc when idle. Wider than [CENTRE_DISC], whose
 * nine dots read as a square (a stop symbol) at this grid size; these 21 read as a circle.
 */
private const val RECORD_DISC = 0.42f

/** Dots beyond this share of the radius form the outer ring. */
private const val RING = 0.78f

private fun angularDistance(a: Float, b: Float): Float {
    val twoPi = (2 * PI).toFloat()
    var d = abs(a - b) % twoPi
    if (d > PI) d = twoPi - d
    return d
}
