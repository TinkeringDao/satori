package com.satori.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.satori.domain.model.RecordTarget
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.MenuRow
import com.satori.ui.design.languageName

/**
 * "Record as": the language to record in, one row per category, then Translate, then a quiet way to
 * edit the categories. Choosing a row starts a recording at once, as that category and in the
 * language chosen above it, and makes both what the one-tap Record on the home screen does from
 * then on; there is nothing else to decide. The language starts as the one-tap Record's and is
 * changed in the sheet only; Malayalam is offered while its model is on the phone, else the row
 * links to its download ([RecordingLanguageRow]). Translate records in English or German alone, so
 * it is off while Malayalam is chosen, saying why.
 *
 * @param translateTarget The language Translate translates into, shown under its row
 * @param language The language the one-tap Record records in, which the sheet starts with
 * @param onPick A category, or Translate, was chosen, with the language to record in; the caller
 *   closes the sheet
 * @param onGetMalayalam The link to the Malayalam model's download was tapped
 */
@Composable
fun RecordAsSheet(
    categories: List<RecordingCategory>,
    translateTarget: String,
    language: RecordingLanguage,
    malayalamOnPhone: Boolean,
    onPick: (RecordTarget, RecordingLanguage) -> Unit,
    onEditCategories: () -> Unit,
    onGetMalayalam: () -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by rememberSaveable { mutableStateOf(language) }
    GrilleSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.g_record_as_title),
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(GrilleSpace.s))
            RecordingLanguageRow(
                current = chosen,
                malayalamOnPhone = malayalamOnPhone,
                onPick = { chosen = it },
                onGetMalayalam = onGetMalayalam
            )
            Spacer(Modifier.height(GrilleSpace.s))
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            categories.forEach { category ->
                MenuRow(text = category.name, onClick = { onPick(RecordTarget.Category(category), chosen) })
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
            val translates = chosen == RecordingLanguage.ENGLISH_OR_GERMAN
            MenuRow(
                text = stringResource(R.string.g_mode_translate),
                supporting = if (translates) {
                    stringResource(R.string.g_record_as_translate_into, languageName(translateTarget))
                } else {
                    stringResource(R.string.g_record_as_translate_en_de)
                },
                enabled = translates,
                onClick = { onPick(RecordTarget.Translate, RecordingLanguage.ENGLISH_OR_GERMAN) }
            )
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            MenuRow(
                text = stringResource(R.string.g_record_as_edit),
                onClick = onEditCategories,
                quiet = true
            )
        }
    }
}
