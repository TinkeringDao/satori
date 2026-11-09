package com.satori.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.settings.CleanupPlan
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.domain.settings.ModelUse
import com.satori.domain.settings.RemoveRefusal
import com.satori.presentation.settings.ModelStorageViewModel
import com.satori.presentation.settings.SettingChange
import com.satori.presentation.settings.StorageNotice
import com.satori.presentation.settings.StoredModel
import com.satori.ui.R
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.rememberNoticeState
import java.util.Locale

/** The route of Models and storage, under Settings; home's hint about unused models opens it too. */
const val MODELS_ROUTE = "settings/models"

/**
 * Settings' Models and storage (the owner's feedback of 2026-10-14: "a lot of models are
 * downloaded which are unnecessary"): what the models take and the free space, "Remove what isn't
 * used" with what it frees, and every model on the phone with what it is for, its size, whether a
 * setting uses it, and Remove. Each removal asks first and says what it costs; afterwards a notice
 * says what was freed and what changed. Parakeet v3 has no Remove: every recording needs it. A model
 * something running may hold says so and has no Remove until that ends.
 */
@Composable
fun ModelStorageScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelStorageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notices = rememberNoticeState()
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    // Asked to confirm: everything unused, or the model of this key ([refKey]).
    var askingUnused by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.notice.collect { notice -> notices.show(noticeText(context, notice, locale)) }
    }

    SettingsScaffold(
        title = stringResource(R.string.g_storage_title),
        onBack = onNavigateBack,
        overlay = { NoticeHost(notices, Modifier.align(Alignment.BottomCenter)) }
    ) {
        state.models?.let { models ->
            StorageList(
                models = models,
                plan = state.plan,
                totalBytes = state.totalBytes,
                freeBytes = state.freeBytes,
                locale = locale,
                onRemoveUnused = { askingUnused = true },
                onRemove = { asking = refKey(it) }
            )
        }
    }

    if (askingUnused) {
        val plan = state.plan
        val names = plan.models.map { "${storedName(it.ref)}, ${size(it.bytes, locale)}" }
        ConfirmSheet(
            title = pluralStringResource(R.plurals.g_storage_unused_title, plan.models.size, plan.models.size),
            text = stringResource(R.string.g_storage_unused_body, names.joinToString("\n"), size(plan.bytes, locale)),
            confirmLabel = stringResource(R.string.g_storage_remove),
            onConfirm = {
                viewModel.removeUnused(plan)
                askingUnused = false
            },
            onDismiss = { askingUnused = false }
        )
    }

    asking?.let { key ->
        val stored = state.models.orEmpty().firstOrNull { refKey(it.model.ref) == key } ?: return@let
        val others = state.models.orEmpty().map { it.model }.filter { it.ref != stored.model.ref }
        ConfirmSheet(
            title = stringResource(R.string.g_storage_remove_title, storedName(stored.model.ref)),
            text = stringResource(R.string.g_storage_remove_body, removalCost(stored, others), size(stored.model.bytes, locale)),
            confirmLabel = stringResource(R.string.g_storage_remove),
            onConfirm = {
                viewModel.remove(stored.model)
                asking = null
            },
            onDismiss = { asking = null }
        )
    }
}

/**
 * What the models take and the free space, "Remove what isn't used" or why there is nothing to
 * remove, and each model.
 */
@Composable
private fun StorageList(
    models: List<StoredModel>,
    plan: CleanupPlan,
    totalBytes: Long,
    freeBytes: Long?,
    locale: Locale,
    onRemoveUnused: () -> Unit,
    onRemove: (ModelRef) -> Unit
) {
    SettingsNote(
        text = if (models.isEmpty()) {
            stringResource(R.string.g_storage_summary_none)
        } else {
            stringResource(R.string.g_storage_summary, size(totalBytes, locale), size(freeBytes ?: 0L, locale))
        },
        modifier = Modifier.padding(top = GrilleSpace.s)
    )
    if (plan.models.isNotEmpty()) {
        SettingsLinkRow(
            title = stringResource(R.string.g_storage_unused),
            supporting = pluralStringResource(R.plurals.g_storage_unused_desc, plan.models.size, plan.models.size, size(plan.bytes, locale)),
            onClick = onRemoveUnused,
            onClickLabel = stringResource(R.string.g_storage_unused)
        )
        SettingsDivider()
    } else if (models.isNotEmpty() && plan.waiting.isEmpty()) {
        SettingsNote(stringResource(R.string.g_storage_all_used))
    }
    if (plan.waiting.isNotEmpty()) SettingsNote(stringResource(R.string.g_storage_unused_waiting))
    if (models.isNotEmpty()) SettingsHeading(stringResource(R.string.g_storage_on_phone))
    models.forEach { stored ->
        StoredModelRow(stored, locale, onRemove = { onRemove(stored.model.ref) })
        SettingsDivider()
    }
}

/**
 * One model: what it is for, its name and size, whether a setting uses it, and Remove, but for
 * Parakeet v3 and a model something running may hold now, which say why instead.
 */
@Composable
private fun StoredModelRow(stored: StoredModel, locale: Locale, onRemove: () -> Unit) {
    val model = stored.model
    val name = storedName(model.ref)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        // The texts are one stop for TalkBack; the pill is the next.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(text = stringResource(purposeOf(model.ref)), style = Grille.type.body, color = Grille.colors.ink)
            Text(
                text = stringResource(R.string.g_storage_model_size, name, size(model.bytes, locale)),
                style = Grille.type.caption,
                color = Grille.colors.ash
            )
            Text(text = stringResource(useLineOf(stored)), style = Grille.type.caption, color = Grille.colors.ink)
            if (stored.refusal == RemoveRefusal.IN_USE_NOW) {
                Text(text = stringResource(R.string.g_storage_in_use_now), style = Grille.type.caption, color = Grille.colors.ash)
            }
        }
        if (stored.refusal == null) {
            PillButton(
                text = stringResource(R.string.g_storage_remove),
                onClick = onRemove,
                modifier = Modifier.padding(top = GrilleSpace.s),
                kind = PillKind.Outline,
                contentDescription = stringResource(R.string.g_storage_remove_cd, name)
            )
        }
    }
}

/** What [ref] is for, in the words of the screen. */
internal fun purposeOf(ref: ModelRef): Int = when (ref) {
    is ModelRef.Speech -> when (ref.id) {
        SpeechModelId.PARAKEET_V3 -> R.string.g_storage_for_speech
        SpeechModelId.INDICCONFORMER_ML -> R.string.g_storage_for_malayalam
        SpeechModelId.PARAKEET_V2_EN -> R.string.g_storage_for_better_en
        SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_storage_for_better_en_de
    }
    is ModelRef.Ai -> if (ref.variant.writesLiveNotes) R.string.g_storage_for_ai_notes else R.string.g_storage_for_finishing
}

/**
 * Whether a setting uses the model, in a line: for every recording, the setting that uses it, or
 * none; a download that stopped half way says so first.
 */
internal fun useLineOf(stored: StoredModel): Int {
    val use = stored.uses.firstOrNull()
    return when {
        use == ModelUse.RECORDING -> R.string.g_storage_use_recording
        !stored.model.whole -> R.string.g_storage_paused
        use == ModelUse.MALAYALAM -> R.string.g_storage_use_malayalam
        use == ModelUse.LANGUAGE_KEY -> R.string.g_storage_use_language_key
        use == ModelUse.BETTER_TRANSCRIPT -> R.string.g_storage_use_better
        use == ModelUse.AI_NOTES -> R.string.g_storage_use_ai_notes
        use == ModelUse.FINISHING -> R.string.g_storage_use_finishing
        else -> R.string.g_storage_use_none
    }
}

/**
 * What removing [stored] costs, for its question: what goes off or falls back, or nothing; for the
 * AI model chosen for the notes, which model on the phone ([others]) writes them then, if any.
 */
@Composable
private fun removalCost(stored: StoredModel, others: List<ModelOnPhone>): String {
    if (!stored.model.whole) return stringResource(R.string.g_storage_cost_paused)
    return when (stored.uses.firstOrNull()) {
        ModelUse.BETTER_TRANSCRIPT -> stringResource(R.string.g_storage_cost_better)
        // The power key and Record as fall back, and the recorder's language key goes.
        ModelUse.MALAYALAM -> stringResource(R.string.g_storage_cost_malayalam) + " " + stringResource(R.string.g_storage_cost_language_key)
        ModelUse.LANGUAGE_KEY -> stringResource(R.string.g_storage_cost_language_key)
        ModelUse.FINISHING -> stringResource(R.string.g_storage_cost_finishing)
        ModelUse.AI_NOTES -> {
            val next = nextNotesModel(others)
            if (next != null) stringResource(R.string.g_storage_cost_notes_by, modelName(next)) else stringResource(R.string.g_storage_cost_no_notes)
        }
        ModelUse.RECORDING, null -> stringResource(R.string.g_storage_cost_none)
    }
}

/** The model for live notes on the phone that writes them once the chosen one is gone, if any. */
internal fun nextNotesModel(others: List<ModelOnPhone>): LlmModelVariant? =
    LlmModelVariant.forLiveNotes.firstOrNull { variant -> others.any { it.whole && it.ref == ModelRef.Ai(variant) } }

/** The model's own name, as Advanced settings names it. */
@Composable
private fun storedName(ref: ModelRef): String = when (ref) {
    is ModelRef.Speech -> speechModelName(ref.id)
    is ModelRef.Ai -> modelName(ref.variant)
}

/** A key of [ref] that survives a fold or a recreation. */
private fun refKey(ref: ModelRef): String = when (ref) {
    is ModelRef.Speech -> "speech:${ref.id.id}"
    is ModelRef.Ai -> "ai:${ref.variant.name}"
}

private fun size(bytes: Long, locale: Locale): String = modelSizeLabel(bytes, locale)

/** What the notice says once a removal is done ([StorageNotice]). */
private fun noticeText(context: android.content.Context, notice: StorageNotice, locale: Locale): String = when (notice) {
    StorageNotice.InUseNow -> context.getString(R.string.g_storage_not_removed)
    is StorageNotice.Removed -> when (val change = notice.change) {
        SettingChange.BetterTranscriptOff -> context.getString(R.string.g_storage_removed_better_off)
        SettingChange.NoNotes -> context.getString(R.string.g_storage_removed_no_notes)
        is SettingChange.NotesBy -> context.getString(R.string.g_storage_removed_notes_by, context.getString(modelNameRes(change.variant)))
        null -> context.getString(R.string.g_storage_removed, modelSizeLabel(notice.bytes, locale))
    }
}

/** [modelName]'s string, for a caller outside composition. */
private fun modelNameRes(variant: LlmModelVariant): Int = when (variant) {
    LlmModelVariant.Q8_0 -> R.string.g_settings_model_q8
    LlmModelVariant.IQ4_NL -> R.string.g_settings_model_iq4
    LlmModelVariant.QWEN3_5_Q8_0 -> R.string.g_settings_model_qwen
    LlmModelVariant.GEMMA4_E2B_Q4_0 -> R.string.g_settings_model_e2b
}
