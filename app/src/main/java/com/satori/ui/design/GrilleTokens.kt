package com.satori.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Grille palette.
 *
 * Black is true black for the OLED panel; there are no tints, gradients or shadows.
 * [rec] means the microphone is live and is used for nothing else, so the recording
 * state can never be confused with decoration or an error.
 *
 * Contrast, dark / light: [ash] on [void] is 6.4:1 / 5.3:1 and on [graphite] 5.6:1 / 4.8:1,
 * so it carries secondary text and the edges of outlined controls (3:1 is the minimum for
 * an edge). [mesh] on [void] is about 3:1 in light; in dark the dots read against true black.
 */
@Immutable
data class GrilleColors(
    /** Background. */
    val void: Color,
    /** Primary text, lit dots, filled buttons. */
    val ink: Color,
    /** Raised surfaces: sheets, fields, notices. */
    val graphite: Color,
    /** Secondary text and icons, and the edge of outlined controls (pills, chips, the switch). */
    val ash: Color,
    /** Hairlines and disabled controls. Never used for text. */
    val unlit: Color,
    /**
     * Dots that are off: the record grille's mesh, the unfilled part of dot progress and dot
     * sliders, page dots, the sheet handle. Equal to [unlit] in dark; darker in light, where
     * [unlit] would almost vanish on white. Never used for text.
     */
    val mesh: Color,
    /** The microphone is live. */
    val rec: Color,
    /** Dims the screen behind a sheet or dialog: strong on black, light on white. */
    val scrim: Color,
    val isDark: Boolean
)

val DarkGrilleColors = GrilleColors(
    void = Color(0xFF000000),
    ink = Color(0xFFFFFFFF),
    graphite = Color(0xFF141414),
    ash = Color(0xFF8E8E8E),
    unlit = Color(0xFF2E2E2E),
    mesh = Color(0xFF2E2E2E),
    rec = Color(0xFFD71921),
    // Background text drops below ash, so the sheet's own secondary text stays brighter.
    scrim = Color.Black.copy(alpha = 0.6f),
    isDark = true
)

val LightGrilleColors = GrilleColors(
    void = Color(0xFFFFFFFF),
    ink = Color(0xFF000000),
    graphite = Color(0xFFF2F2F2),
    ash = Color(0xFF6B6B6B),
    unlit = Color(0xFFD6D6D6),
    mesh = Color(0xFF949494),
    rec = Color(0xFFD71921),
    // Material's 32 % turned a white screen mid-grey behind every sheet.
    scrim = Color.Black.copy(alpha = 0.12f),
    isDark = false
)

internal val LocalGrilleColors = staticCompositionLocalOf { DarkGrilleColors }

/** 8 dp grid. Screen content uses [margin] on both sides. */
object GrilleSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val margin = 24.dp

    /** Minimum touch target, and the minimum height of every pill and chip. */
    val touch = 48.dp
}
