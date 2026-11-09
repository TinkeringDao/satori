package com.satori.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.ui.R
import com.satori.ui.components.RecordingLanguageRow
import com.satori.ui.design.FormField
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.modeDescription

/**
 * What the power key does: one sentence, the category to record as and the language to record in
 * ("Record as: Meeting, Malayalam"), and whether a second press stops. Each choice has a line
 * under it of what it means. The choices apply as they are made. Satori cannot bind Motorola's
 * My Key itself (docs/QUICK_KEY.md): the binding is a few
 * commands run from a computer, which release builds never show or mention (the owner's decision
 * of 2026-10-15), so the sentence says what the key does once it is bound. Debug builds keep the
 * commands at the foot of the sheet, behind one quiet row ([QuickKeySetup], debug source set), to
 * copy or share them; the sheet stays open meanwhile.
 *
 * @param categories the categories to record as; [selected] is one of them.
 * @param language the language it records in now: Malayalam only while its model is on the phone
 *   ([malayalamOnPhone]), else English or German; without the model the row links to its download
 *   ([onGetMalayalam]). Null for a category that translates, which records in English or German
 *   alone: the sheet has no language row then.
 * @param onSetupCopied a debug build's setup commands are on the clipboard.
 */
@Composable
internal fun QuickKeySheet(
    categories: List<RecordingCategory>,
    selected: RecordingCategory?,
    language: RecordingLanguage?,
    malayalamOnPhone: Boolean,
    stopOnSecondPress: Boolean,
    onSelectCategory: (RecordingCategory) -> Unit,
    onSelectLanguage: (RecordingLanguage) -> Unit,
    onGetMalayalam: () -> Unit,
    onStopOnSecondPressChange: (Boolean) -> Unit,
    onSetupCopied: () -> Unit,
    onDismiss: () -> Unit
) {
    FormSheet(
        title = stringResource(R.string.g_quickkey_title),
        confirmLabel = stringResource(R.string.g_quickkey_done),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null
    ) {
        Spacer(Modifier.height(GrilleSpace.s))
        Text(
            text = stringResource(R.string.g_quickkey_explain),
            style = Grille.type.body,
            color = Grille.colors.ash
        )
        FormField(stringResource(R.string.g_quickkey_category)) {
            SegmentedChoice(
                options = categories.map { it.name },
                selectedIndex = categories.indexOf(selected),
                onSelect = { onSelectCategory(categories[it]) }
            )
        }
        // What the category chosen means for the recordings, as the category's own sheet says it.
        if (selected != null) {
            QuietLine(modeDescription(selected.mode))
        }
        if (language != null) {
            Spacer(Modifier.height(GrilleSpace.l))
            RecordingLanguageRow(
                current = language,
                malayalamOnPhone = malayalamOnPhone,
                onPick = onSelectLanguage,
                onGetMalayalam = onGetMalayalam,
                inset = 0.dp
            )
            QuietLine(stringResource(R.string.g_quickkey_language_desc))
        }
        Spacer(Modifier.height(GrilleSpace.s))
        SettingsSwitchRow(
            title = stringResource(R.string.g_quickkey_stop),
            supporting = stringResource(R.string.g_quickkey_stop_desc),
            checked = stopOnSecondPress,
            onCheckedChange = onStopOnSecondPressChange,
            horizontalPadding = 0.dp
        )
        QuickKeySetup(onCopied = onSetupCopied)
    }
}

/** A line in ash under a choice of the sheet, saying what the choice means. */
@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = Grille.type.caption,
        color = Grille.colors.ash,
        modifier = Modifier.padding(top = GrilleSpace.s)
    )
}
