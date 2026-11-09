package com.satori.ui.design

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * A number in dot-matrix digits: "3:32", "1:24:10", "42%", "42 %".
 *
 * Only the digits 0-9 are drawn in Doto. Every other character (':', '.', ',', '%', '/',
 * spaces, units) is drawn in Space Grotesk Light, because Doto turns ':' and '.' into
 * five-dot crosses and "3:32" would read as "3‡32". The colon is raised to sit centred on the
 * digits, as on a clock, which makes a number with a colon about 0.1 em taller.
 *
 * Use it for every number set in a Doto style, at 28 sp or more: [GrilleType.timer],
 * [GrilleType.timerCompact], [GrilleType.displayNumber], [GrilleType.listNumber]. Format
 * lengths with [formatDuration]. Small readouts (slider values, sizes in MB) are not Doto:
 * set them in a Space Grotesk style with [TabularDigits].
 *
 * @param contentDescription what TalkBack says instead of the digits, e.g. "Length 3:32"; a
 *   bare "3:32" reads as a time of day. Null leaves the number as it is.
 */
@Composable
fun DotoNumber(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Grille.type.listNumber,
    color: Color = Grille.colors.ink,
    contentDescription: String? = null
) {
    val styled = remember(text) { dotoNumberText(text) }
    Text(
        text = styled,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = if (contentDescription != null) {
            modifier.semantics { this.contentDescription = contentDescription }
        } else {
            modifier
        }
    )
}

/**
 * [text] with every run of characters other than 0-9 in Space Grotesk Light, for a Doto
 * text style. For [DotoNumber]; exposed for places that build their own [AnnotatedString].
 */
fun dotoNumberText(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    while (start < text.length) {
        val kind = text[start].numberPart()
        var end = start + 1
        while (end < text.length && text[end].numberPart() == kind) end++
        val run = text.substring(start, end)
        when (kind) {
            NumberPart.Digit -> append(run)
            NumberPart.Colon -> withStyle(DotoColonStyle) { append(run) }
            NumberPart.Other -> withStyle(DotoSeparatorStyle) { append(run) }
        }
        start = end
    }
}

private enum class NumberPart { Digit, Colon, Other }

/** Doto has only ASCII digits, so other scripts' digits count as separators. */
private fun Char.numberPart(): NumberPart = when (this) {
    in '0'..'9' -> NumberPart.Digit
    ':' -> NumberPart.Colon
    else -> NumberPart.Other
}

/** Plain round dots that sit well beside the dot-matrix digits. */
private val DotoSeparatorStyle = SpanStyle(
    fontFamily = SpaceGroteskFamily,
    fontWeight = FontWeight(300),
    letterSpacing = 0.05.em
)

/**
 * The colon of a length, raised so its two dots sit evenly around the middle of the digits,
 * as on a clock; Space Grotesk puts the lower dot on the baseline. '.' and ',' stay there.
 */
private val DotoColonStyle = DotoSeparatorStyle.copy(baselineShift = BaselineShift(0.1f))
