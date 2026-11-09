package com.satori.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.satori.domain.model.RecordingCategory
import com.satori.ui.design.ChoiceRow
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline

/**
 * What a recording is recorded as: one row for each category it can be switched to ([choices],
 * which leave Translate out), the one it is recorded as ([currentId]) filled. Picking another
 * calls [onPick]; picking the filled one only closes the sheet. The caller closes it either way.
 * Above them, the [language] it is made in ([RecordingLanguageRow]), when the recording can change
 * it: the row's caller decides whether a pick there closes the sheet (the recorder's does, the
 * page's doesn't).
 *
 * The recorder titles it "Record as", the recording's page "Change type", with a [caption] on what
 * the change does there. The four starter categories and the language fit on the cover screen
 * without scrolling while the Malayalam model is on the phone, and the recorder has the language
 * row only then (or for a recording in Malayalam); the page's link to the download, more categories
 * or a large font scroll.
 */
@Composable
fun RecordingTypeSheet(
    title: String,
    choices: List<RecordingCategory>,
    currentId: String?,
    onPick: (RecordingCategory) -> Unit,
    onDismiss: () -> Unit,
    caption: String? = null,
    language: (@Composable () -> Unit)? = null
) {
    GrilleSheet(onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                text = title,
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = Grille.type.caption,
                    color = Grille.colors.ash,
                    modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
                )
            }
            Spacer(Modifier.height(GrilleSpace.s))
            if (language != null) {
                language()
                Spacer(Modifier.height(GrilleSpace.s))
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
            Column(Modifier.selectableGroup()) {
                choices.forEachIndexed { index, category ->
                    if (index > 0) Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                    ChoiceRow(
                        title = category.name,
                        selected = category.id == currentId,
                        onClick = { if (category.id == currentId) onDismiss() else onPick(category) }
                    )
                }
            }
        }
    }
}
