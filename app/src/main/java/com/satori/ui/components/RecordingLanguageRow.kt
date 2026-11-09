package com.satori.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.satori.domain.model.RecordingLanguage
import com.satori.ui.R
import com.satori.ui.design.FieldLabel
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.SegmentedChoice

/**
 * The language row of "Record as" and "Change type": the language the recording is made in,
 * English or German (the categories' languages) or Malayalam, the one it is in ([current]) filled.
 * Picking the other calls [onPick]. Home's "Record as" and the page's "Change type" stay open, so
 * the category can be chosen too; the recorder's closes, so the notice of the switch is seen.
 *
 * Malayalam is offered only while its speech model is on the phone ([malayalamOnPhone]): a
 * recording can't be transcribed in it otherwise. Without it the row has an outline pill that says
 * where to get it ([onGetMalayalam], to Advanced settings): quiet text under the one choice read
 * as a caption, not as something to tap. It keeps the Malayalam choice only for a recording that
 * is in Malayalam already, so the row never says another language than its own.
 * The recorder's row has no link ([onGetMalayalam] null): it shows only with a choice to make, and
 * Settings opened over the lock screen would hide the live recorder.
 *
 * @param inset Its margins: the screen's in a sheet of rows, none in a form, whose content has them
 */
@Composable
internal fun RecordingLanguageRow(
    current: RecordingLanguage,
    malayalamOnPhone: Boolean,
    onPick: (RecordingLanguage) -> Unit,
    onGetMalayalam: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inset: Dp = GrilleSpace.margin
) {
    val choices = languageChoices(current, malayalamOnPhone)
    val labels = choices.map { recordingLanguageChoice(it) }
    Column(modifier) {
        FieldLabel(stringResource(R.string.g_language_title), Modifier.padding(horizontal = inset))
        Spacer(Modifier.height(GrilleSpace.s))
        SegmentedChoice(
            options = labels,
            selectedIndex = choices.indexOf(current),
            onSelect = { index -> choices[index].takeIf { it != current }?.let(onPick) },
            modifier = Modifier.padding(horizontal = inset)
        )
        if (!malayalamOnPhone && onGetMalayalam != null) {
            PillButton(
                text = stringResource(R.string.g_language_get_ml),
                onClick = onGetMalayalam,
                modifier = Modifier.padding(start = inset, end = inset, top = GrilleSpace.m),
                kind = PillKind.Outline
            )
        }
    }
}

/**
 * The languages the row offers to a recording in [current]: both while the Malayalam model is on
 * the phone; without it English or German, and Malayalam only for a recording that is in it.
 */
internal fun languageChoices(current: RecordingLanguage, malayalamOnPhone: Boolean): List<RecordingLanguage> =
    if (malayalamOnPhone || current == RecordingLanguage.MALAYALAM) {
        RecordingLanguage.entries
    } else {
        listOf(RecordingLanguage.ENGLISH_OR_GERMAN)
    }

/** How the row's choice of [language] reads: "English or German", "Malayalam · മലയാളം". */
@Composable
internal fun recordingLanguageChoice(language: RecordingLanguage): String = when (language) {
    RecordingLanguage.ENGLISH_OR_GERMAN -> stringResource(R.string.g_language_en_de)
    RecordingLanguage.MALAYALAM -> stringResource(
        R.string.g_language_pair,
        stringResource(R.string.g_language_ml),
        stringResource(R.string.g_language_ml_own)
    )
}

/** The name of [language] as a notice or a line says it: "English or German", "Malayalam". */
@Composable
internal fun recordingLanguageName(language: RecordingLanguage): String = stringResource(recordingLanguageNameRes(language))

/** [recordingLanguageName]'s string, for a caller that formats it outside composition. */
internal fun recordingLanguageNameRes(language: RecordingLanguage): Int = when (language) {
    RecordingLanguage.ENGLISH_OR_GERMAN -> R.string.g_language_en_de
    RecordingLanguage.MALAYALAM -> R.string.g_language_ml
}
