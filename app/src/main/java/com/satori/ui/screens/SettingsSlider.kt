package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.satori.ui.design.DotSlider
import com.satori.ui.design.DotSliderThumbSize
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.TabularDigits
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A dot slider under its title, with its value on the right. While the thumb moves only this
 * row changes; the setting is written once, when the thumb is let go, so dragging doesn't
 * write to storage on every step.
 *
 * @param decimals digits after the decimal point in the value shown and stored.
 * @param percent show the value times 100, as a whole number, e.g. 0.5 as "50".
 * @param spokenFormat what TalkBack says for the value, with the number as its argument, e.g.
 *   "%1$s seconds" where the screen shows "0.5 s".
 */
@Composable
internal fun SettingsSlider(
    title: String,
    supporting: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    onCommit: (Float) -> Unit,
    unit: String? = null,
    percent: Boolean = false,
    @StringRes spokenFormat: Int? = null,
    startLabel: String? = null,
    endLabel: String? = null
) {
    val locale = LocalConfiguration.current.locales[0]
    // Follows the thumb while dragging; starts over whenever the stored value changes.
    var shown by remember(value) { mutableFloatStateOf(value.coerceIn(valueRange)) }
    val number = if (percent) {
        (shown * 100).roundToInt().toString()
    } else {
        formatSettingValue(shown, decimals, locale)
    }
    val spoken = if (spokenFormat != null) stringResource(spokenFormat, number) else number

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = GrilleSpace.m)
    ) {
        Row(Modifier.padding(horizontal = GrilleSpace.margin)) {
            Column(
                Modifier
                    .weight(1f)
                    .alignByBaseline()
            ) {
                Text(text = title, style = Grille.type.body, color = Grille.colors.ink)
                if (supporting != null) {
                    Text(text = supporting, style = Grille.type.caption, color = Grille.colors.ash)
                }
            }
            Spacer(Modifier.width(GrilleSpace.m))
            ValueReadout(number = number, unit = unit, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(GrilleSpace.xs))
        DotSlider(
            value = shown,
            onValueChange = { shown = it },
            valueRange = valueRange,
            steps = sliderStepCount(valueRange, step),
            onValueChangeFinished = { onCommit(shown.roundToDecimals(decimals)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SliderInset)
                .semantics {
                    contentDescription = title
                    stateDescription = spoken
                }
        )
        if (startLabel != null && endLabel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GrilleSpace.margin)
            ) {
                Text(
                    text = startLabel,
                    style = Grille.type.caption,
                    color = Grille.colors.ash,
                    modifier = Modifier.weight(1f)
                )
                Text(text = endLabel, style = Grille.type.caption, color = Grille.colors.ash)
            }
        }
    }
}

/**
 * The value in Space Grotesk with tabular digits, the unit smaller beside it. Not Doto: its
 * '.' and ',' are five-dot crosses, so "0.5" would read "0+5". The slider speaks the value.
 */
@Composable
private fun ValueReadout(number: String, unit: String?, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics {}) {
        Text(
            text = number,
            style = ReadoutStyle,
            color = Grille.colors.ink,
            modifier = Modifier.alignByBaseline()
        )
        if (unit != null) {
            Text(
                text = unit,
                style = Grille.type.label,
                color = Grille.colors.ash,
                modifier = Modifier
                    .padding(start = GrilleSpace.xs)
                    .alignByBaseline()
            )
        }
    }
}

/** Slider steps between the ends of [range], for values [step] apart. */
private fun sliderStepCount(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)

private fun formatSettingValue(value: Float, decimals: Int, locale: Locale): String =
    "%.${decimals}f".format(locale, value)

/** Drops float noise such as 0.30000001 before a value is stored. */
private fun Float.roundToDecimals(decimals: Int): Float {
    val factor = 10f.pow(decimals)
    return (this * factor).roundToInt() / factor
}

/** Lines the slider's first and last dots up with text on the 24 dp margin. */
private val SliderInset: Dp = GrilleSpace.margin - DotSliderThumbSize / 2

/** Slider values: title size, and digits of one width so the number holds still while dragging. */
private val ReadoutStyle: TextStyle
    @Composable get() = Grille.type.title.copy(fontFeatureSettings = TabularDigits)
