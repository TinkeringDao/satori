package com.satori.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.satori.ui.R

// Both families are variable fonts under the SIL OFL, bundled in res/font (no downloads).

/** Doto at full roundness (ROND 100) gives round dots, like a dot-matrix display. */
@OptIn(ExperimentalTextApi::class)
private fun doto(weight: Int) = Font(
    R.font.doto,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", 100f)
    )
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/**
 * Digits at 28 sp or larger, and the wordmark. Never running text. Doto draws ':' '.' ','
 * and '%' as five-dot crosses, so numbers are set with [DotoNumber], which keeps those in
 * Space Grotesk.
 */
val DotoFamily = FontFamily(doto(500), doto(600), doto(700), doto(800))

/** Everything that is read: sentence case, left-aligned. */
val SpaceGroteskFamily = FontFamily(grotesk(300), grotesk(400), grotesk(500), grotesk(700))

/**
 * The type roles. The four Doto styles ([timer], [timerCompact], [displayNumber],
 * [listNumber]) are for [DotoNumber]; small readouts such as slider values and sizes use the
 * Space Grotesk styles instead.
 */
@Immutable
data class GrilleType(
    /** Tape-counter timer on the recording screen. */
    val timer: TextStyle,
    /** Timer on the cover screen. */
    val timerCompact: TextStyle,
    /** Large numbers: a length, a download percentage. */
    val displayNumber: TextStyle,
    /** Lengths in lists and next to a title. */
    val listNumber: TextStyle,
    /** The app name on home. */
    val wordmark: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val transcript: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
)

/** OpenType tabular figures: every digit has the same width, so a counting number holds still. */
const val TabularDigits = "tnum"

val DefaultGrilleType = GrilleType(
    timer = TextStyle(
        fontFamily = DotoFamily, fontWeight = FontWeight(700),
        fontSize = 64.sp, lineHeight = 64.sp, fontFeatureSettings = TabularDigits
    ),
    timerCompact = TextStyle(
        fontFamily = DotoFamily, fontWeight = FontWeight(700),
        fontSize = 52.sp, lineHeight = 52.sp, fontFeatureSettings = TabularDigits
    ),
    displayNumber = TextStyle(
        fontFamily = DotoFamily, fontWeight = FontWeight(700),
        fontSize = 36.sp, lineHeight = 40.sp, fontFeatureSettings = TabularDigits
    ),
    // 800: at 600 the 28 sp dots were sparse and read grey, above all on white.
    listNumber = TextStyle(
        fontFamily = DotoFamily, fontWeight = FontWeight(800),
        fontSize = 28.sp, lineHeight = 32.sp, fontFeatureSettings = TabularDigits
    ),
    wordmark = TextStyle(
        fontFamily = DotoFamily, fontWeight = FontWeight(800),
        fontSize = 32.sp, lineHeight = 36.sp
    ),
    title = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(500),
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    heading = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(500),
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    transcript = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(400),
        fontSize = 17.sp, lineHeight = 26.sp
    ),
    body = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(400),
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    label = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(500),
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    caption = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(400),
        fontSize = 13.sp, lineHeight = 18.sp
    )
)

internal val LocalGrilleType = staticCompositionLocalOf { DefaultGrilleType }
