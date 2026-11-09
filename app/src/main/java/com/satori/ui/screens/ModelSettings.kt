package com.satori.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import com.satori.domain.model.AiModelStatus
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelStatus
import com.satori.presentation.settings.AiModelState
import com.satori.presentation.settings.ModelsUiState
import com.satori.presentation.settings.SpeechModelState
import com.satori.presentation.settings.VocabularyStatus
import com.satori.presentation.settings.WordsUiState
import com.satori.ui.R
import com.satori.ui.design.DotProgress
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillAction
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/** What the buttons of the Models section do. */
@Immutable
internal class ModelActions(
    val onDownloadSpeech: (SpeechModelId) -> Unit,
    val onPauseSpeech: () -> Unit,
    val onAskDeleteSpeech: (SpeechModelId) -> Unit,
    val onAskDownloadSpeechAgain: () -> Unit,
    val onChooseAfterStop: () -> Unit,
    val onEditWords: () -> Unit,
    val onRetryVocabulary: () -> Unit,
    val onDownload: (LlmModelVariant) -> Unit,
    val onResume: (LlmModelVariant) -> Unit,
    val onPause: () -> Unit,
    val onSelect: (LlmModelVariant) -> Unit,
    val onAskDelete: (LlmModelVariant) -> Unit,
    val onFinishingModel: (Boolean) -> Unit
)

/**
 * The models on the phone. The speech models first: each says what it is good for and whether it
 * is on the phone. One that is not there has Download; one that is downloading has Pause; a
 * paused one has Resume; one on the phone can be deleted while another speech model is there. A
 * whole Parakeet v3, which every recording in English or German needs, has no button at all: it
 * is tagged damaged and offers Download again only when the check of its files says so
 * ([ModelsUiState.repairOffered]). Under them, once a better model is on the phone, the model for a
 * better transcript after Stop ([afterStop]), and the words to recognize ([words]).
 *
 * The AI models: each says whether it is on the phone, and is chosen by tapping it once it is.
 * A model that is not there has Download; one that is downloading has Pause; a paused one has
 * Resume; one on the phone can be deleted. The experimental model says so.
 *
 * A Delete alone under a model is an outline pill, as Download is: in quiet ash with no edge it
 * read as a label or as a switched-off button. Beside Resume it stays quiet, the lesser of two.
 *
 * "AI model" is named like "Speech recognition" above it, as the two are the same kind of thing.
 * While no AI model is on the phone there is nothing to pick: the line under the name says to
 * download one, and the rows keep no room for selection dots.
 *
 * Gemma 4 E2B is listed with them but can't be chosen for the notes while recording: with its file
 * on the phone, the switch under the list ("AI for finished recordings", [finishingModelEnabled])
 * decides whether it does the work after Stop.
 */
@Composable
internal fun ModelsSection(
    models: ModelsUiState,
    recommended: LlmModelVariant,
    finishingModelEnabled: Boolean,
    afterStop: SpeechModelId?,
    words: WordsUiState,
    actions: ModelActions
) {
    SettingsHeading(stringResource(R.string.g_settings_section_models), topPadding = GrilleSpace.s)
    ModelGroupTitle(stringResource(R.string.g_settings_speech))
    // One speech model downloads at a time, like one AI model.
    val speechBusy = models.speechDownloading
    SettingsNote(stringResource(if (speechBusy) R.string.g_speech_busy else R.string.g_speech_help))
    models.speech.forEach { state ->
        SpeechModelRow(
            state = state,
            canStart = !speechBusy,
            mayDelete = models.mayDelete(state.model),
            repair = state.model == SpeechModelId.LIVE && models.repairOffered,
            actions = actions
        )
        SettingsDivider()
    }
    val choices = models.afterStopChoices
    if (choices.isNotEmpty()) {
        // The choice under the title, as the microphone input's: "Cohere Transcribe, English and
        // German" beside it squeezed the title into a column a few letters wide.
        SettingsLinkRow(
            title = stringResource(R.string.g_speech_after_stop),
            supporting = afterStopLabel(afterStop?.takeIf { it in choices }),
            description = stringResource(R.string.g_speech_after_stop_desc),
            onClick = actions.onChooseAfterStop
        )
        SettingsDivider()
    }
    WordsRow(words, actions)
    SettingsDivider()
    ModelGroupTitle(stringResource(R.string.g_settings_ai_model))
    // One AI model downloads at a time; starting another would cancel the running one. While one
    // runs, the line under the name says why the other buttons are off.
    val busy = models.ai.any { it.status is AiModelStatus.Downloading }
    // Only a model that writes live notes can be picked; Gemma 4 E2B alone leaves nothing to pick.
    val anyInstalled = models.liveNotesModelOnPhone
    SettingsNote(
        text = stringResource(
            when {
                busy -> R.string.g_settings_ai_model_busy
                anyInstalled -> R.string.g_settings_ai_model_help
                else -> R.string.g_settings_ai_model_none
            }
        )
    )
    Column(Modifier.selectableGroup()) {
        models.ai.forEach { state ->
            AiModelRow(
                state = state,
                recommended = state.variant == recommended,
                canStart = !busy,
                dotRoom = anyInstalled,
                actions = actions
            )
            SettingsDivider()
        }
    }
    if (models.finishingModelOnPhone) {
        SettingsSwitchRow(
            title = stringResource(R.string.g_settings_finishing_model),
            supporting = stringResource(R.string.g_settings_finishing_model_desc),
            checked = finishingModelEnabled,
            onCheckedChange = actions.onFinishingModel
        )
        SettingsDivider()
    }
}

/** The name of a group of models, "Speech recognition" or "AI model": a heading in body type. */
@Composable
private fun ModelGroupTitle(text: String) {
    Text(
        text = text,
        style = Grille.type.body,
        color = Grille.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.m)
            .semantics { heading() }
    )
}

/**
 * One speech model: its name, what it is good for and its size, whether it is on the phone, and
 * the buttons for where it stands.
 *
 * @param canStart Whether a download may start: no other speech model downloads
 * @param mayDelete Whether it may be deleted ([ModelsUiState.mayDelete])
 * @param repair Whether its files are damaged and it is offered again ([ModelsUiState.repairOffered])
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechModelRow(
    state: SpeechModelState,
    canStart: Boolean,
    mayDelete: Boolean,
    repair: Boolean,
    actions: ModelActions
) {
    val model = state.model
    val status = state.status
    val name = speechModelName(model)
    val delete = stringResource(R.string.g_settings_model_delete_cd, name)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        // The name and facts are one stop; a button in the row merges for itself and is the next.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(text = name, style = Grille.type.body, color = Grille.colors.ink)
            Text(text = speechModelDescription(model), style = Grille.type.caption, color = Grille.colors.ash)
            when {
                status == SpeechModelStatus.Installed && repair -> ModelTag(stringResource(R.string.g_settings_speech_damaged))
                status == SpeechModelStatus.Installed -> ModelTag(stringResource(R.string.g_settings_model_on_phone))
                // Without it nothing records: worth a word, unlike a better model not downloaded.
                status == SpeechModelStatus.NotInstalled && model == SpeechModelId.LIVE ->
                    ModelTag(stringResource(R.string.g_settings_speech_missing))
                status == SpeechModelStatus.Paused -> Text(
                    text = stringResource(R.string.g_settings_model_paused),
                    style = Grille.type.caption,
                    color = Grille.colors.ash
                )
            }
        }
        when (status) {
            SpeechModelStatus.Installed -> if (repair || mayDelete) {
                FlowRow(
                    modifier = Modifier.padding(top = GrilleSpace.s),
                    horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                    verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
                ) {
                    // Each alone under its model: the outline pill, as Download.
                    if (repair) {
                        PillButton(
                            text = stringResource(R.string.g_settings_speech_again),
                            onClick = actions.onAskDownloadSpeechAgain,
                            kind = PillKind.Outline,
                            enabled = canStart,
                            contentDescription = stringResource(R.string.g_settings_speech_again_cd)
                        )
                    }
                    if (mayDelete) {
                        PillButton(
                            text = stringResource(R.string.g_settings_model_delete),
                            onClick = { actions.onAskDeleteSpeech(model) },
                            kind = PillKind.Outline,
                            contentDescription = delete
                        )
                    }
                }
            }
            is SpeechModelStatus.Downloading -> {
                DownloadProgressLine(status.percent)
                PillButton(
                    text = stringResource(R.string.g_settings_model_pause),
                    onClick = actions.onPauseSpeech,
                    modifier = Modifier.padding(top = GrilleSpace.s),
                    kind = PillKind.Outline,
                    contentDescription = stringResource(R.string.g_settings_model_pause_cd, name)
                )
            }
            SpeechModelStatus.Paused -> FlowRow(
                modifier = Modifier.padding(top = GrilleSpace.s),
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
            ) {
                PillButton(
                    text = stringResource(R.string.g_settings_model_resume),
                    onClick = { actions.onDownloadSpeech(model) },
                    kind = PillKind.Outline,
                    enabled = canStart,
                    contentDescription = stringResource(R.string.g_settings_model_resume_cd, name)
                )
                if (mayDelete) {
                    PillButton(
                        text = stringResource(R.string.g_settings_model_delete),
                        onClick = { actions.onAskDeleteSpeech(model) },
                        kind = PillKind.Quiet,
                        contentDescription = delete
                    )
                }
            }
            is SpeechModelStatus.Failed -> ErrorNote(
                text = status.reason.title(),
                action = PillAction(
                    label = stringResource(R.string.g_settings_model_retry),
                    enabled = canStart,
                    onClick = { actions.onDownloadSpeech(model) }
                ),
                actionKind = PillKind.Outline,
                horizontalMargin = 0.dp,
                modifier = Modifier.padding(top = GrilleSpace.s)
            )
            SpeechModelStatus.NotInstalled -> PillButton(
                text = stringResource(R.string.g_settings_model_download),
                onClick = { actions.onDownloadSpeech(model) },
                modifier = Modifier.padding(top = GrilleSpace.s),
                kind = PillKind.Outline,
                enabled = canStart,
                contentDescription = stringResource(R.string.g_settings_model_download_cd, name)
            )
        }
    }
}

/**
 * What "Better transcript after Stop" is set to, in a few words; [model] null is Off. Parakeet v3
 * made the live transcript already, and the Malayalam model makes a Malayalam recording's and knows
 * no other language, so neither is offered ([ModelsUiState.afterStopChoices]): Off too.
 */
@Composable
internal fun afterStopLabel(model: SpeechModelId?): String = stringResource(
    when (model) {
        null, SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML -> R.string.g_speech_after_stop_off
        SpeechModelId.PARAKEET_V2_EN -> R.string.g_speech_after_stop_v2
        SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_speech_after_stop_cohere
    }
)

/**
 * What "Better transcript after Stop" set to [model] means, null for Off, in the line under it in
 * the sheet: what the transcript gains and what the notes wait for it.
 */
@Composable
internal fun afterStopLine(model: SpeechModelId?): String = stringResource(
    when (model) {
        null, SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML -> R.string.g_speech_after_stop_off_desc
        SpeechModelId.PARAKEET_V2_EN -> R.string.g_speech_after_stop_v2_desc
        SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_speech_after_stop_cohere_desc
    }
)

/**
 * The words to recognize: how many there are, and what they still need. They need the small
 * vocabulary file, downloaded with the first words; a failed download has Try again. Words that
 * couldn't be saved come first: the count is then that of the words from before. With nothing
 * to say about them the line says what they are for.
 */
@Composable
private fun WordsRow(words: WordsUiState, actions: ModelActions) {
    val count = words.words.size
    val vocabulary = words.vocabulary.takeIf { count > 0 && words.liveModelOnPhone }
    val needs = when {
        words.saveFailed -> stringResource(R.string.g_speech_words_not_saved)
        count == 0 -> null
        !words.liveModelOnPhone -> stringResource(R.string.g_speech_words_needs_v3)
        vocabulary == VocabularyStatus.Downloading -> stringResource(R.string.g_speech_words_vocabulary_downloading)
        vocabulary == VocabularyStatus.Missing -> stringResource(R.string.g_speech_words_vocabulary_missing)
        vocabulary is VocabularyStatus.Failed -> stringResource(R.string.g_speech_words_vocabulary_failed)
        else -> null
    } ?: stringResource(R.string.g_speech_words_desc)
    SettingsLinkRow(
        title = stringResource(R.string.g_speech_words),
        value = if (count == 0) {
            stringResource(R.string.g_speech_words_none)
        } else {
            pluralStringResource(R.plurals.g_speech_words_count, count, count)
        },
        supporting = needs,
        onClick = actions.onEditWords
    )
    // The file is fetched with the first words; this is for a download that didn't happen.
    if (vocabulary == VocabularyStatus.Missing || vocabulary is VocabularyStatus.Failed) {
        PillButton(
            text = stringResource(
                if (vocabulary == VocabularyStatus.Missing) R.string.g_settings_model_download else R.string.g_settings_model_retry
            ),
            onClick = actions.onRetryVocabulary,
            modifier = Modifier.padding(start = GrilleSpace.margin, end = GrilleSpace.margin, bottom = GrilleSpace.m),
            kind = PillKind.Outline
        )
    }
}

/**
 * One AI model.
 *
 * @param dotRoom Whether the rows keep room for a selection dot: some model is on the phone, and
 *   the names line up whether a row has a dot or not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelRow(
    state: AiModelState,
    recommended: Boolean,
    canStart: Boolean,
    dotRoom: Boolean,
    actions: ModelActions
) {
    val variant = state.variant
    val status = state.status
    val name = modelName(variant)
    val installed = status as? AiModelStatus.Installed
    // Only a model that can write live notes is a choice here; Gemma 4 E2B has its own switch.
    val choice = installed?.takeIf { variant.writesLiveNotes }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = GrilleSpace.m)
    ) {
        // The name and facts are the choice; the buttons below are not part of it. A model that is
        // not on the phone cannot be chosen yet, but its name and facts are still one stop.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (choice != null) {
                        Modifier.selectable(
                            selected = choice.inUse,
                            role = Role.RadioButton,
                            onClick = { actions.onSelect(variant) }
                        )
                    } else {
                        Modifier.semantics(mergeDescendants = true) {}
                    }
                )
                .padding(horizontal = GrilleSpace.margin),
            horizontalArrangement = Arrangement.spacedBy(GrilleSpace.m)
        ) {
            if (choice != null) {
                // Centred on the first line of the name (24 sp line height).
                ModelSelectionDot(selected = choice.inUse, modifier = Modifier.padding(top = 6.dp))
            } else if (dotRoom) {
                Spacer(Modifier.size(ModelDotSize))
            }
            Column(Modifier.weight(1f)) {
                Text(text = name, style = Grille.type.body, color = Grille.colors.ink)
                Text(text = modelDescription(variant), style = Grille.type.caption, color = Grille.colors.ash)
                if (installed != null) ModelTag(stringResource(R.string.g_settings_model_on_phone))
                if (recommended) ModelTag(stringResource(R.string.g_settings_model_recommended))
                if (variant == LlmModelVariant.QWEN3_5_Q8_0) {
                    ModelTag(stringResource(R.string.g_settings_model_experimental))
                }
            }
        }
        Column(
            Modifier.padding(
                start = GrilleSpace.margin + if (dotRoom) ModelDotSize + GrilleSpace.m else 0.dp,
                end = GrilleSpace.margin,
                top = GrilleSpace.s
            )
        ) {
            when (status) {
                is AiModelStatus.Installed -> {
                    // Alone under its model: the outline pill, as Download.
                    PillButton(
                        text = stringResource(R.string.g_settings_model_delete),
                        onClick = { actions.onAskDelete(variant) },
                        kind = PillKind.Outline,
                        contentDescription = stringResource(R.string.g_settings_model_delete_cd, name)
                    )
                }
                is AiModelStatus.Downloading -> {
                    DownloadProgressLine(status.percent)
                    PillButton(
                        text = stringResource(R.string.g_settings_model_pause),
                        onClick = actions.onPause,
                        modifier = Modifier.padding(top = GrilleSpace.s),
                        kind = PillKind.Outline,
                        contentDescription = stringResource(R.string.g_settings_model_pause_cd, name)
                    )
                }
                AiModelStatus.Paused -> {
                    Text(
                        text = stringResource(R.string.g_settings_model_paused),
                        style = Grille.type.caption,
                        color = Grille.colors.ash
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = GrilleSpace.s),
                        horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
                    ) {
                        PillButton(
                            text = stringResource(R.string.g_settings_model_resume),
                            onClick = { actions.onResume(variant) },
                            kind = PillKind.Outline,
                            enabled = canStart,
                            contentDescription = stringResource(R.string.g_settings_model_resume_cd, name)
                        )
                        PillButton(
                            text = stringResource(R.string.g_settings_model_delete),
                            onClick = { actions.onAskDelete(variant) },
                            kind = PillKind.Quiet,
                            contentDescription = stringResource(R.string.g_settings_model_delete_cd, name)
                        )
                    }
                }
                is AiModelStatus.Failed -> ErrorNote(
                    text = status.reason.title(),
                    action = PillAction(
                        label = stringResource(R.string.g_settings_model_retry),
                        enabled = canStart,
                        onClick = { actions.onResume(variant) }
                    ),
                    actionKind = PillKind.Outline,
                    horizontalMargin = 0.dp
                )
                AiModelStatus.NotInstalled -> {
                    PillButton(
                        text = stringResource(R.string.g_settings_model_download),
                        onClick = { actions.onDownload(variant) },
                        kind = PillKind.Outline,
                        enabled = canStart,
                        contentDescription = stringResource(R.string.g_settings_model_download_cd, name)
                    )
                }
            }
        }
    }
}

/** Dot progress with what it shows in words; moving dots while the size is not known yet. */
@Composable
private fun DownloadProgressLine(percent: Int) {
    Column(
        modifier = Modifier.padding(top = GrilleSpace.s),
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        DotProgress(progress = if (percent > 0) percent / 100f else null)
        Text(
            text = if (percent > 0) {
                stringResource(R.string.g_settings_model_downloading_percent, percent)
            } else {
                stringResource(R.string.g_settings_model_downloading)
            },
            style = Grille.type.caption,
            color = Grille.colors.ash
        )
    }
}

/** Same dot as [com.satori.ui.design.ChoiceRow]: filled when selected. */
@Composable
private fun ModelSelectionDot(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = Grille.colors
    Box(
        modifier = modifier
            .size(ModelDotSize)
            .background(if (selected) colors.ink else Color.Transparent, CircleShape)
            .border(1.dp, if (selected) colors.ink else colors.ash, CircleShape)
    )
}

/** A short status under a model, with a dot as its bullet. */
@Composable
private fun ModelTag(text: String) {
    Row(
        modifier = Modifier.padding(top = GrilleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(Grille.colors.ink, CircleShape)
        )
        Text(text = text, style = Grille.type.caption, color = Grille.colors.ink)
    }
}

private val ModelDotSize = 12.dp
