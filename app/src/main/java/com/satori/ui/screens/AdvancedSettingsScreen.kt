package com.satori.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.MicSource
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.settings.NoteIntervals
import com.satori.domain.usecase.finish.TranscribeAfterStopUseCase
import com.satori.domain.usecase.stt.SpeechVocabulary
import com.satori.presentation.settings.AdvancedSettingsViewModel
import com.satori.presentation.settings.DeleteConsequence
import com.satori.ui.R
import com.satori.ui.design.AppLanguageCodes
import com.satori.ui.design.ChoiceRow
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.Hairline
import com.satori.ui.design.LocalSheetBandClearance
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.languageName
import com.satori.ui.design.modeLabel
import com.satori.ui.design.scrollCue

/** The kinds of recording whose notes the AI writes; translation has its own section. */
private val NoteModes = listOf(RecordingMode.SIMPLE_LISTENING, RecordingMode.SHORT_MEETING, RecordingMode.LONG_MEETING)

private val StrategyOptions = listOf(InsightStrategy.REAL_TIME, InsightStrategy.END_OF_SESSION)


private const val SECONDS_PER_MINUTE = 60

/**
 * The minutes the help of the better transcript after Stop names for its limit [limitMs]
 * ([TranscribeAfterStopUseCase.MAX_ESTIMATED_MS], three minutes today): whole minutes rounded
 * down, and at least one. Rounded down, whatever the Transcribe again sheet puts at that many
 * minutes or fewer (it rounds up, [estimateMinutes]) is within a limit of a minute or more, so
 * the help promises no better transcript the limit cuts: a limit of 90 s is "about 1 minute".
 * At least one, so a limit under a minute is never "about 0 minutes".
 */
internal fun afterStopLimitMinutes(limitMs: Long = TranscribeAfterStopUseCase.MAX_ESTIMATED_MS): Int =
    (limitMs / 1_000 / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)

/**
 * The technical settings, in plain words: the models on the phone (with the transcript after Stop
 * and the words to recognize), how and when the AI writes notes for each kind of recording,
 * translation, the microphone input and voice detection. Sliders write once, when the thumb is
 * let go. What the AI is told to write is not a setting: the app's own prompts are the only ones.
 */
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AdvancedSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val finishingModelEnabled by viewModel.finishingModelEnabled.collectAsStateWithLifecycle()
    val words by viewModel.words.collectAsStateWithLifecycle()

    // Asked to confirm: deleting this model or this speech model, or downloading Parakeet v3 again.
    var deleting by rememberSaveable { mutableStateOf<LlmModelVariant?>(null) }
    var deletingSpeech by rememberSaveable { mutableStateOf<SpeechModelId?>(null) }
    var redownloadingSpeech by rememberSaveable { mutableStateOf(false) }
    // Open sheets: the model for after Stop, the words to recognize, and the microphone input.
    var choosingAfterStop by rememberSaveable { mutableStateOf(false) }
    var editingWords by rememberSaveable { mutableStateOf(false) }
    var choosingMicInput by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.g_settings_advanced),
        onBack = onNavigateBack
    ) {
        ModelsSection(
            models = models,
            recommended = viewModel.recommendedVariant,
            finishingModelEnabled = finishingModelEnabled,
            afterStop = settings.transcribeAfterStop,
            words = words,
            actions = ModelActions(
                onDownloadSpeech = viewModel::downloadSpeech,
                onPauseSpeech = viewModel::pauseSpeechDownload,
                onAskDeleteSpeech = { deletingSpeech = it },
                onAskDownloadSpeechAgain = { redownloadingSpeech = true },
                onChooseAfterStop = { choosingAfterStop = true },
                onEditWords = { editingWords = true },
                onRetryVocabulary = viewModel::downloadVocabulary,
                onDownload = viewModel::downloadVariant,
                onResume = viewModel::resumeVariant,
                onPause = viewModel::pauseDownload,
                onSelect = viewModel::selectVariant,
                onAskDelete = { deleting = it },
                onFinishingModel = viewModel::setFinishingModelEnabled
            )
        )
        NotesSection(
            settings = settings,
            onInterval = viewModel::updateModeInterval,
            onStrategy = viewModel::updateModeDefaultStrategy
        )
        TranslationSection(
            settings = settings,
            onTargetLanguage = viewModel::updateTranslationTargetLanguage,
            onInterval = viewModel::updateModeInterval,
            onStrategy = viewModel::updateModeDefaultStrategy
        )
        MicrophoneSection(
            settings = settings,
            onChoose = { choosingMicInput = true }
        )
        VoiceDetectionSection(
            settings = settings,
            onMinSilence = viewModel::updateVadMinSilenceDuration,
            onMaxSpeech = viewModel::updateVadMaxSpeechDuration,
            onThreshold = viewModel::updateVadThreshold,
            onReset = viewModel::resetVadParameters
        )
    }

    deleting?.let { variant ->
        ConfirmSheet(
            title = stringResource(R.string.g_settings_model_delete_title),
            text = stringResource(deleteBody(models.deleteConsequence(variant))),
            confirmLabel = stringResource(R.string.g_settings_model_delete),
            onConfirm = {
                viewModel.deleteVariant(variant)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }

    if (redownloadingSpeech) {
        ConfirmSheet(
            title = stringResource(R.string.g_settings_speech_again_title),
            text = stringResource(R.string.g_settings_speech_again_body),
            confirmLabel = stringResource(R.string.g_settings_speech_again_confirm),
            onConfirm = {
                viewModel.downloadSpeechAgain()
                redownloadingSpeech = false
            },
            onDismiss = { redownloadingSpeech = false }
        )
    }

    deletingSpeech?.let { model ->
        ConfirmSheet(
            title = stringResource(R.string.g_speech_delete_title),
            text = stringResource(speechDeleteBody(models.deleteConsequence(model))),
            confirmLabel = stringResource(R.string.g_settings_model_delete),
            onConfirm = {
                viewModel.deleteSpeechModel(model)
                deletingSpeech = null
            },
            onDismiss = { deletingSpeech = null }
        )
    }

    if (choosingAfterStop) {
        // Off first, then the better models on the phone; choosing one applies it.
        val options = listOf<SpeechModelId?>(null) + models.afterStopChoices
        val limitMinutes = afterStopLimitMinutes()
        ChoiceSheet(
            title = stringResource(R.string.g_speech_after_stop),
            options = options.map { afterStopLabel(it) },
            selectedIndex = options.indexOf(settings.transcribeAfterStop).coerceAtLeast(0),
            onSelect = { index ->
                viewModel.setTranscribeAfterStop(options[index])
                choosingAfterStop = false
            },
            onDismiss = { choosingAfterStop = false },
            supporting = options.map { afterStopLine(it) },
            help = pluralStringResource(R.plurals.g_speech_after_stop_help, limitMinutes, limitMinutes)
        )
    }

    if (editingWords) {
        WordsSheet(
            words = words.words,
            onSave = { text ->
                viewModel.setWords(text)
                editingWords = false
            },
            onDismiss = { editingWords = false }
        )
    }

    if (choosingMicInput) {
        MicInputSheet(
            selected = settings.micSource,
            onSelect = { source ->
                viewModel.updateMicSource(source)
                choosingMicInput = false
            },
            onDismiss = { choosingMicInput = false }
        )
    }
}

/**
 * What the question to delete an AI model says: that the notes stay and it can come back, or, for
 * the only model that writes live notes ([DeleteConsequence.NO_NOTES]), that new recordings get no
 * notes until one is back.
 */
internal fun deleteBody(consequence: DeleteConsequence): Int = when (consequence) {
    DeleteConsequence.NO_NOTES -> R.string.g_settings_model_delete_last_body
    DeleteConsequence.NONE, DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN -> R.string.g_settings_model_delete_body
}

/**
 * What the question to delete a speech model says: that its transcripts stay and it can come back,
 * or, for the Malayalam model ([DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN]), that the power
 * key and Record as record in English or German until it is back. None names the notes' language.
 */
internal fun speechDeleteBody(consequence: DeleteConsequence): Int = when (consequence) {
    DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN -> R.string.g_speech_delete_ml_body
    DeleteConsequence.NONE, DeleteConsequence.NO_NOTES -> R.string.g_speech_delete_body
}

/**
 * The words to recognize, one per line, in a field that grows with them. Saving cleans them
 * ([SpeechVocabulary.clean]); the keyboard's Return starts a new line.
 */
@Composable
private fun WordsSheet(words: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(SpeechVocabulary.asText(words)) }
    FormSheet(
        title = stringResource(R.string.g_speech_words),
        confirmLabel = stringResource(R.string.g_speech_words_save),
        onConfirm = { onSave(draft) },
        onDismiss = onDismiss
    ) {
        Spacer(Modifier.height(GrilleSpace.s))
        Text(text = stringResource(R.string.g_speech_words_help), style = Grille.type.caption, color = Grille.colors.ash)
        Spacer(Modifier.height(GrilleSpace.m))
        GrilleTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            imeAction = ImeAction.Default,
            // Names are written as they should appear; the keyboard doesn't change them.
            capitalization = KeyboardCapitalization.None,
            autoFocus = true
        )
    }
}

@Composable
private fun NotesSection(
    settings: AppSettings,
    onInterval: (RecordingMode, Int) -> Unit,
    onStrategy: (RecordingMode, InsightStrategy) -> Unit
) {
    SettingsHeading(stringResource(R.string.g_settings_section_notes))
    SettingsNote(stringResource(R.string.g_settings_notes_help))
    NoteModes.forEach { mode ->
        SettingsGroupLabel(modeLabel(mode))
        ModeRows(mode, settings, onInterval, onStrategy)
        SettingsDivider()
    }
}

@Composable
private fun TranslationSection(
    settings: AppSettings,
    onTargetLanguage: (String) -> Unit,
    onInterval: (RecordingMode, Int) -> Unit,
    onStrategy: (RecordingMode, InsightStrategy) -> Unit
) {
    SettingsHeading(stringResource(R.string.g_settings_section_translation))
    SettingsChoice(
        title = stringResource(R.string.g_settings_translate_into),
        options = AppLanguageCodes.map { languageName(it) },
        // Older versions offered more languages; like the Record as sheet, treat them as English.
        selectedIndex = AppLanguageCodes.indexOf(settings.translationTargetLanguage).coerceAtLeast(0),
        onSelect = { onTargetLanguage(AppLanguageCodes[it]) },
        supporting = stringResource(R.string.g_settings_translate_into_desc)
    )
    SettingsDivider()
    ModeRows(RecordingMode.REAL_TIME_TRANSLATION, settings, onInterval, onStrategy)
    SettingsDivider()
}

/**
 * When and how often the AI writes for [mode], each with a line of what its choices trade: notes
 * written only after Stop leave the phone less to do while it records, and notes that follow what
 * is said sooner cost battery. The interval follows the timing, and shows only while it is "While
 * recording" ([showsInterval]): after recording no live job reads it, and a row that can be
 * changed to no effect said it mattered. Hidden, it keeps its value for when it is back.
 */
@Composable
private fun ModeRows(
    mode: RecordingMode,
    settings: AppSettings,
    onInterval: (RecordingMode, Int) -> Unit,
    onStrategy: (RecordingMode, InsightStrategy) -> Unit
) {
    val translation = mode == RecordingMode.REAL_TIME_TRANSLATION
    val strategy = strategyOf(settings, mode)
    SettingsChoice(
        title = stringResource(if (translation) R.string.g_settings_strategy_translate else R.string.g_settings_strategy),
        options = listOf(
            stringResource(R.string.g_settings_timing_live),
            stringResource(R.string.g_settings_timing_after)
        ),
        selectedIndex = StrategyOptions.indexOf(strategy),
        onSelect = { onStrategy(mode, StrategyOptions[it]) },
        supporting = stringResource(
            if (translation) R.string.g_settings_strategy_translate_desc else R.string.g_settings_strategy_desc
        )
    )
    if (showsInterval(strategy)) {
        val intervals = NoteIntervals.optionsFor(mode)
        SettingsDivider()
        SettingsChoice(
            title = stringResource(if (translation) R.string.g_settings_interval_translate else R.string.g_settings_interval),
            options = intervals.map { intervalLabel(mode, it) },
            selectedIndex = intervals.indexOf(NoteIntervals.nearest(intervals, intervalOf(settings, mode))),
            onSelect = { onInterval(mode, intervals[it]) },
            supporting = stringResource(
                if (translation) R.string.g_settings_interval_translate_desc else R.string.g_settings_interval_desc
            )
        )
    }
}

/** Whether the interval of a kind of recording shows: only while its notes are written while recording. */
internal fun showsInterval(strategy: InsightStrategy): Boolean = strategy == InsightStrategy.REAL_TIME

/**
 * Which microphone input speech is captured from: one row that names it and opens the sheet of
 * the inputs Settings offers ([MicInputSheet], [MicSource.OFFERED]): the recommended usual input
 * and the input for calls, to compare with it in loud places. The one for speech recognition, the
 * default from 0.4.0 to 0.5.2, is not offered: it recorded too quietly on the razr.
 */
@Composable
private fun MicrophoneSection(
    settings: AppSettings,
    onChoose: () -> Unit
) {
    SettingsHeading(stringResource(R.string.g_settings_section_mic))
    SettingsLinkRow(
        title = stringResource(R.string.g_settings_mic_input),
        // Under the title, not beside it: the inputs' names are long, in German longer still.
        supporting = micInputLabel(settings.micSource),
        description = stringResource(R.string.g_settings_mic_input_desc),
        onClick = onChoose
    )
    SettingsDivider()
}

/**
 * The microphone inputs Settings offers ([MicSource.OFFERED]), the one in use filled; choosing one
 * applies it from the next recording, as the line under the title says, and the caller closes the
 * sheet. The phone's usual input comes first, recommended. Then the input for calls. Each says
 * under its name what picking it means: the usual input that it works best here, and the one for
 * calls what its noise reduction can cost. Android's input for speech recognition records speech
 * on the razr about 20 dB lower, too quiet for the speech model (no automatic gain, 2026-10-13);
 * offered again ([MicSource.OFFERS_SPEECH_RECOGNITION_INPUT]), its line says so.
 */
@Composable
private fun MicInputSheet(
    selected: MicSource,
    onSelect: (MicSource) -> Unit,
    onDismiss: () -> Unit
) {
    GrilleSheet(onDismiss = onDismiss, throughBand = true) {
        // On the cover screen at a large font size it is taller than the screen.
        val scroll = rememberScrollState()
        val clearance = LocalSheetBandClearance.current
        Column(
            Modifier
                .weight(1f, fill = false)
                .scrollCue(scroll, endInset = clearance)
                .verticalScroll(scroll)
                .padding(bottom = clearance)
        ) {
            Text(
                text = stringResource(R.string.g_settings_mic_input),
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Text(
                text = stringResource(R.string.g_settings_mic_input_help),
                style = Grille.type.caption,
                color = Grille.colors.ash,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
            )
            Spacer(Modifier.height(GrilleSpace.s))
            Column(Modifier.selectableGroup()) {
                MicSource.OFFERED.forEachIndexed { index, source ->
                    if (index > 0) Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                    ChoiceRow(
                        title = micInputLabel(source),
                        selected = source == selected,
                        onClick = { onSelect(source) },
                        supporting = micInputLine(source)
                    )
                }
            }
        }
    }
}

/** What microphone input [source] is called in its row and its sheet. */
@Composable
private fun micInputLabel(source: MicSource): String = stringResource(
    when (source) {
        MicSource.VOICE_RECOGNITION -> R.string.g_settings_mic_recognition
        MicSource.MIC -> R.string.g_settings_mic_usual
        MicSource.VOICE_COMMUNICATION -> R.string.g_settings_mic_calls
    }
)

/** What picking microphone input [source] means, in the line under its name in the sheet. */
@Composable
private fun micInputLine(source: MicSource): String = stringResource(
    when (source) {
        MicSource.VOICE_RECOGNITION -> R.string.g_settings_mic_recognition_desc
        MicSource.MIC -> R.string.g_settings_mic_usual_desc
        MicSource.VOICE_COMMUNICATION -> R.string.g_settings_mic_calls_desc
    }
)

@Composable
private fun VoiceDetectionSection(
    settings: AppSettings,
    onMinSilence: (Float) -> Unit,
    onMaxSpeech: (Float) -> Unit,
    onThreshold: (Float) -> Unit,
    onReset: () -> Unit
) {
    val seconds = stringResource(R.string.g_settings_unit_s)
    SettingsHeading(stringResource(R.string.g_settings_section_vad))
    SettingsNote(stringResource(R.string.g_settings_vad_desc))
    SettingsSlider(
        title = stringResource(R.string.g_settings_vad_silence),
        supporting = stringResource(R.string.g_settings_vad_silence_desc),
        value = settings.vadMinSilenceDuration,
        valueRange = AdvancedSettingsViewModel.MIN_SILENCE_DURATION_MIN..AdvancedSettingsViewModel.MIN_SILENCE_DURATION_MAX,
        step = 0.1f,
        decimals = 1,
        unit = seconds,
        spokenFormat = R.string.g_settings_seconds_spoken,
        startLabel = stringResource(R.string.g_settings_vad_shorter),
        endLabel = stringResource(R.string.g_settings_vad_longer),
        onCommit = onMinSilence
    )
    SettingsDivider()
    SettingsSlider(
        title = stringResource(R.string.g_settings_vad_speech),
        supporting = stringResource(R.string.g_settings_vad_speech_desc),
        value = settings.vadMaxSpeechDuration,
        valueRange = AdvancedSettingsViewModel.MAX_SPEECH_DURATION_MIN..AdvancedSettingsViewModel.MAX_SPEECH_DURATION_MAX,
        step = 1f,
        decimals = 0,
        unit = seconds,
        spokenFormat = R.string.g_settings_seconds_spoken,
        startLabel = stringResource(R.string.g_settings_vad_shorter),
        endLabel = stringResource(R.string.g_settings_vad_longer),
        onCommit = onMaxSpeech
    )
    SettingsDivider()
    SettingsSlider(
        title = stringResource(R.string.g_settings_vad_threshold),
        supporting = stringResource(R.string.g_settings_vad_threshold_desc),
        value = settings.vadThreshold,
        valueRange = AdvancedSettingsViewModel.VAD_THRESHOLD_MIN..AdvancedSettingsViewModel.VAD_THRESHOLD_MAX,
        step = 0.05f,
        decimals = 2,
        unit = stringResource(R.string.g_settings_unit_percent),
        percent = true,
        spokenFormat = R.string.g_settings_percent_spoken,
        startLabel = stringResource(R.string.g_settings_vad_more),
        endLabel = stringResource(R.string.g_settings_vad_less),
        onCommit = onThreshold
    )
    SettingsDivider()
    // Offered only when there is something to reset.
    if (!usesDefaultVoiceDetection(settings)) {
        PillButton(
            text = stringResource(R.string.g_settings_vad_reset),
            onClick = onReset,
            modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m),
            kind = PillKind.Outline
        )
    }
}

private fun usesDefaultVoiceDetection(settings: AppSettings): Boolean =
    settings.vadMinSilenceDuration == AdvancedSettingsViewModel.MIN_SILENCE_DURATION_DEFAULT &&
        settings.vadMaxSpeechDuration == AdvancedSettingsViewModel.MAX_SPEECH_DURATION_DEFAULT &&
        settings.vadThreshold == AdvancedSettingsViewModel.VAD_THRESHOLD_DEFAULT

/** "30 s", "2 min": minutes for a long meeting and for whole minutes, else seconds. */
@Composable
private fun intervalLabel(mode: RecordingMode, value: Int): String = when {
    mode == RecordingMode.LONG_MEETING -> stringResource(R.string.g_unit_minutes, value)
    value % SECONDS_PER_MINUTE == 0 -> stringResource(R.string.g_unit_minutes, value / SECONDS_PER_MINUTE)
    else -> stringResource(R.string.g_unit_seconds, value)
}

/** The stored interval for [mode]: minutes for a long meeting, seconds for the others. */
private fun intervalOf(settings: AppSettings, mode: RecordingMode): Int = when (mode) {
    RecordingMode.SIMPLE_LISTENING -> settings.simpleListeningIntervalSeconds
    RecordingMode.SHORT_MEETING -> settings.shortMeetingIntervalSeconds
    RecordingMode.LONG_MEETING -> settings.longMeetingIntervalMinutes
    RecordingMode.REAL_TIME_TRANSLATION -> settings.translationIntervalSeconds
}

private fun strategyOf(settings: AppSettings, mode: RecordingMode): InsightStrategy = when (mode) {
    RecordingMode.SIMPLE_LISTENING -> settings.simpleListeningDefaultStrategy
    RecordingMode.SHORT_MEETING -> settings.shortMeetingDefaultStrategy
    RecordingMode.LONG_MEETING -> settings.longMeetingDefaultStrategy
    RecordingMode.REAL_TIME_TRANSLATION -> settings.translationDefaultStrategy
}
