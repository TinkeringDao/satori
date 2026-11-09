package com.satori.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.stt.TranscriptLanguage
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleType
import com.satori.ui.design.LocalGrilleType
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.WithDeliberateHold

/**
 * The recorder's language key: a pill that says the language the recording is made in, and a tap
 * on it switches the recording to the other one ([RecordingLanguage.other]), as the language row of
 * "Record as" does, without opening the sheet. It sits at the end of the inner screen's top bar
 * and, on the cover, after the Record as chip in the timer's row. "EN/DE", outlined, while English
 * or German isn't told apart yet; "EN" or "DE" once the transcript shows which ([languageKeyLabel]),
 * what it heard rather than a setting; and Malayalam filled, as its letter ma ("മ", never "ML").
 * TalkBack says the language in full and what a tap does ("Language: English or German. Switch to
 * Malayalam").
 *
 * Its width is that of its widest label at the font size set, so it neither moves nor changes its
 * size when the label does. The codes in Space Grotesk at the label size, Malayalam's letter set as
 * tall as their capitals ([labelStyle]), and never red: red means the microphone is live.
 *
 * @param holdToSwitch Whether only a hold of at least half a second switches, and a tap calls
 *   [onHoldHint] instead: over the lock screen, as the Record as chip opens and the grille stops
 *   only on a hold there ([languageKeyInput])
 */
@Composable
internal fun LanguageKey(
    label: LanguageKeyLabel,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
    holdToSwitch: Boolean = false,
    onHoldHint: () -> Unit = {}
) {
    val description = stringResource(R.string.g_language_key_cd, stringResource(label.language), stringResource(label.switchesTo))
    val type = Grille.type
    val width = keyWidth(type)
    val key: @Composable () -> Unit = {
        // The label in its own style: Malayalam's letter set as tall as the codes' capitals.
        CompositionLocalProvider(LocalGrilleType provides type.copy(label = labelStyle(label, type.label))) {
            PillButton(
                text = stringResource(label.text),
                onClick = if (holdToSwitch) onHoldHint else onSwitch,
                modifier = modifier.width(width),
                kind = if (label == LanguageKeyLabel.MALAYALAM) PillKind.Filled else PillKind.Outline,
                contentPadding = PaddingValues(horizontal = KEY_PADDING),
                contentDescription = description,
                onLongClick = if (holdToSwitch) onSwitch else null,
                maxLines = 1
            )
        }
    }
    if (holdToSwitch) WithDeliberateHold(key) else key()
}

/**
 * The style of [label]'s text: the label style ([base]), and for Malayalam's letter a larger and
 * heavier one. Android draws Malayalam in its UI font, which keeps the script within Latin line
 * heights: on its own the letter stood at about the height of a Latin x, 7.8 dp against the 9.9 dp
 * of the capitals of "EN" at 14 sp (the emulator's Noto Sans Malayalam UI, 2026-10-16), and with
 * thinner strokes. At [MALAYALAM_LETTER_SCALE] times the size, a step heavier, it stands as tall
 * and reads as firmly; the line height stays, so the key stays as tall.
 */
internal fun labelStyle(label: LanguageKeyLabel, base: TextStyle): TextStyle =
    if (label != LanguageKeyLabel.MALAYALAM) {
        base
    } else {
        base.copy(fontSize = base.fontSize * MALAYALAM_LETTER_SCALE, fontWeight = FontWeight((base.fontWeight ?: FontWeight.Medium).weight + 100))
    }

/** The key's width: its widest label at the font size set, each in its style, with its padding, and at least [KEY_MIN_WIDTH]. */
@Composable
private fun keyWidth(type: GrilleType): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = LanguageKeyLabel.entries.map { it to stringResource(it.text) }
    val widest = remember(measurer, type, density, labels) {
        labels.maxOf { (label, text) -> measurer.measure(text, labelStyle(label, type.label)).size.width }
    }
    return maxOf(KEY_MIN_WIDTH, with(density) { widest.toDp() } + KEY_PADDING * 2 + KEY_SLACK)
}

/**
 * What the language key says: its [text], the [language] TalkBack names, and the language a tap
 * [switchesTo].
 */
internal enum class LanguageKeyLabel(@StringRes val text: Int, @StringRes val language: Int, @StringRes val switchesTo: Int) {
    /** English or German, not told apart yet. */
    EN_DE(R.string.g_language_key_en_de, R.string.g_language_en_de, R.string.g_language_ml),

    /** English or German, and what is said is English. */
    EN(R.string.g_language_key_en, R.string.g_rec_language_en, R.string.g_language_ml),

    /** English or German, and what is said is German. */
    DE(R.string.g_language_key_de, R.string.g_rec_language_de, R.string.g_language_ml),

    /** Malayalam. */
    MALAYALAM(R.string.g_language_key_ml, R.string.g_language_ml, R.string.g_language_en_de)
}

/** How the language key takes input ([languageKeyInput]). */
internal enum class LanguageKeyInput {
    /** A tap switches the language. */
    TAP,

    /** Only a hold of at least half a second switches it, and a tap says so. */
    HOLD
}

/**
 * How the language key takes input: none while the recording can't change its language, when there
 * is no key ([com.satori.presentation.main.MainUiState.canChangeLanguage]: not while recording, not
 * for a translation, and not while the Malayalam model isn't on the phone, unless the recording is in
 * Malayalam); else a tap switches it, and over the lock screen ([holdToStop]) only a hold does, as for
 * the Record as chip: the screen stays on while recording, also in a pocket.
 */
internal fun languageKeyInput(canChangeLanguage: Boolean, holdToStop: Boolean): LanguageKeyInput? = when {
    !canChangeLanguage -> null
    holdToStop -> LanguageKeyInput.HOLD
    else -> LanguageKeyInput.TAP
}

/**
 * The key's label for a recording made in [language] whose complete [lines] are these: Malayalam's
 * for Malayalam; for English or German the one the newest lines are in ([heardLanguage]), and
 * "EN/DE" while they don't say which.
 */
internal fun languageKeyLabel(language: RecordingLanguage, lines: List<TranscriptionSegment>): LanguageKeyLabel = when (language) {
    RecordingLanguage.MALAYALAM -> LanguageKeyLabel.MALAYALAM
    RecordingLanguage.ENGLISH_OR_GERMAN -> when (heardLanguage(lines)) {
        LanguageDetector.ENGLISH -> LanguageKeyLabel.EN
        LanguageDetector.GERMAN -> LanguageKeyLabel.DE
        else -> LanguageKeyLabel.EN_DE
    }
}

/**
 * English or German ("en", "de"), as the newest complete [lines] in Latin letters say it, up to
 * [HEARD_WORDS] words back: what is spoken now, as a recording can go from one to the other. Null
 * while they don't say which ([LanguageDetector]: fewer than eight words, too little to go on, or
 * both evenly). Lines in Malayalam script, made before the recording went back from Malayalam, say
 * nothing of it.
 */
internal fun heardLanguage(lines: List<TranscriptionSegment>): String? {
    val heard = ArrayList<String>()
    var words = 0
    for (line in lines.filter { it.isComplete }.sortedByDescending { it.timestamp }) {
        val text = line.text.trim()
        if (text.isEmpty() || TranscriptLanguage.isIn(RecordingLanguage.MALAYALAM, listOf(line))) continue
        heard += text
        words += text.split(WHITESPACE).size
        if (words >= HEARD_WORDS) break
    }
    return LanguageDetector.detect(heard.asReversed().joinToString(" "))
        ?.takeIf { it == LanguageDetector.ENGLISH || it == LanguageDetector.GERMAN }
}

/** How many of the newest words the key's English or German is read from: about half a minute of speech. */
internal const val HEARD_WORDS = 60

private val WHITESPACE = Regex("\\s+")

/** The key's narrowest width, wider than the Record as chip's arrow and as tall a target as any. */
private val KEY_MIN_WIDTH = 72.dp

/** The space on each side of its label. */
private val KEY_PADDING = 12.dp

/** Room for the rounding of the measured width, so the widest label is never cut short. */
private val KEY_SLACK = 2.dp

/** How much larger Malayalam's letter is set than the codes, for its height to match their capitals ([labelStyle]). */
internal const val MALAYALAM_LETTER_SCALE = 1.3f
