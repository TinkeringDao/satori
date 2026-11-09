package com.satori.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.category.CategoryRules
import com.satori.domain.category.CategoryRules.NameProblem
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.presentation.categories.CategoriesViewModel
import com.satori.ui.R
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.FormField
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.modeDescription
import com.satori.ui.design.modeLabel

/**
 * The user's recording categories. Each row shows the name and, under it, what recordings in it
 * get ("Short notes and to-dos"): the type's own name would often only repeat the category's
 * ("Meeting / Meeting"). A tap opens a sheet to rename it, change its type or delete it, and the
 * last row adds one. Reachable from Settings and from the home screen's "Edit categories".
 */
@Composable
fun CategoriesScreen(
    onNavigateBack: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val list = categories.orEmpty()

    // The sheet that is open: a new category, or the category with this id. Then the one being
    // deleted, which has its own question.
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.g_cat_title),
        onBack = onNavigateBack
    ) {
        list.forEach { category ->
            SettingsLinkRow(
                title = category.name,
                supporting = modeDescription(category.mode),
                onClick = { editingId = category.id }
            )
            SettingsDivider()
        }
        if (categories != null) {
            SettingsLinkRow(
                title = stringResource(R.string.g_cat_add),
                onClick = { adding = true },
                onClickLabel = stringResource(R.string.g_cat_add)
            )
        }
    }

    if (adding) {
        CategorySheet(
            category = null,
            otherNames = list.map { it.name },
            canDelete = false,
            onSave = { name, mode ->
                viewModel.add(name, mode)
                adding = false
            },
            onDelete = {},
            onDismiss = { adding = false }
        )
    }

    editingId?.let { id ->
        val category = list.firstOrNull { it.id == id } ?: return@let
        CategorySheet(
            category = category,
            otherNames = list.filter { it.id != id }.map { it.name },
            // The last category is kept: the home screen and the power key always need one.
            canDelete = list.size > 1,
            onSave = { name, mode ->
                viewModel.update(id, name, mode)
                editingId = null
            },
            onDelete = {
                editingId = null
                deletingId = id
            },
            onDismiss = { editingId = null }
        )
    }

    deletingId?.let { id ->
        ConfirmSheet(
            title = stringResource(R.string.g_cat_delete_title),
            text = stringResource(R.string.g_cat_delete_body),
            confirmLabel = stringResource(R.string.g_cat_delete_confirm),
            onConfirm = {
                viewModel.delete(id)
                deletingId = null
            },
            onDismiss = { deletingId = null }
        )
    }
}

/**
 * Adds a category (`category` null) or edits one: the type with a line on what it does, then the
 * name, with the reason when it can't be used. Save is offered once the name is acceptable and
 * something changed. Delete, for an existing category, asks first on the caller's side.
 *
 * The type comes first: it needs no keyboard. On the cover the keyboard takes half the window and
 * left the sheet some 60 px between the name and the action row, the four types thin slivers that
 * could be neither read nor tapped. So on a window under [NAME_FOCUS_MIN_HEIGHT_DP] the name gets
 * focus only when tapped ([focusesNameAtOnce]); the inner screen keeps the keyboard up at once.
 *
 * @param otherNames the names of the other categories, which this one may not take.
 * @param canDelete false for the only category left, which says so instead of offering Delete.
 */
@Composable
private fun CategorySheet(
    category: RecordingCategory?,
    otherNames: List<String>,
    canDelete: Boolean,
    onSave: (name: String, mode: RecordingMode) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialName = category?.name.orEmpty()
    // Opens with the whole name selected, so typing replaces it.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    var mode by rememberSaveable { mutableStateOf(category?.mode ?: RecordingMode.SIMPLE_LISTENING) }
    // An empty name only gets its message once the person has typed in the field.
    var touched by rememberSaveable { mutableStateOf(false) }

    val check = CategoryRules.checkName(name.text, otherNames)
    val canSave = check.isValid && (category == null || check.name != category.name || mode != category.mode)
    val problem = when (check.problem) {
        NameProblem.EMPTY -> if (touched) stringResource(R.string.g_cat_name_empty) else null
        NameProblem.TOO_LONG -> pluralStringResource(
            R.plurals.g_cat_name_long,
            CategoryRules.MAX_NAME_LENGTH,
            CategoryRules.MAX_NAME_LENGTH
        )
        NameProblem.DUPLICATE -> stringResource(R.string.g_cat_name_taken)
        null -> null
    }

    val focusName = focusesNameAtOnce(LocalConfiguration.current.screenHeightDp)
    FormSheet(
        title = stringResource(if (category == null) R.string.g_cat_new_title else R.string.g_cat_edit_title),
        confirmLabel = stringResource(if (category == null) R.string.g_cat_create else R.string.g_cat_save),
        onConfirm = { onSave(check.name, mode) },
        onDismiss = onDismiss,
        confirmEnabled = canSave
    ) {
        FormField(stringResource(R.string.g_cat_type)) {
            SegmentedChoice(
                options = RecordingMode.entries.map { modeLabel(it) },
                selectedIndex = RecordingMode.entries.indexOf(mode),
                onSelect = { mode = RecordingMode.entries[it] }
            )
        }
        Text(
            text = modeDescription(mode),
            style = Grille.type.caption,
            color = Grille.colors.ash,
            modifier = Modifier.padding(top = GrilleSpace.s)
        )
        FormField(stringResource(R.string.g_cat_name)) {
            GrilleTextField(
                value = name,
                onValueChange = {
                    name = it
                    touched = true
                },
                keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(check.name, mode) }),
                autoFocus = focusName
            )
        }
        if (problem != null) {
            Text(
                text = problem,
                style = Grille.type.caption,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(top = GrilleSpace.s)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (category != null) {
            Spacer(Modifier.height(GrilleSpace.l))
            if (canDelete) {
                PillButton(
                    text = stringResource(R.string.g_cat_delete),
                    onClick = onDelete,
                    kind = PillKind.Quiet,
                    // The text lines up with the fields above it, not 24 dp in from the margin.
                    contentPadding = PaddingValues(start = 0.dp, end = GrilleSpace.l)
                )
            } else {
                Text(
                    text = stringResource(R.string.g_cat_last),
                    style = Grille.type.caption,
                    color = Grille.colors.ash
                )
            }
        }
    }
}

/** The least window height, in dp, on which a category's name takes focus as its sheet opens. */
internal const val NAME_FOCUS_MIN_HEIGHT_DP = 700

/**
 * Whether the name field of a category's sheet takes focus, and the keyboard comes up, as the sheet
 * opens in a window [windowHeightDp] tall: on the inner screen (about 1,000 dp), not on the cover
 * (665 dp, 483 dp above the cameras), where the keyboard would hide the types.
 */
internal fun focusesNameAtOnce(windowHeightDp: Int): Boolean = windowHeightDp >= NAME_FOCUS_MIN_HEIGHT_DP
