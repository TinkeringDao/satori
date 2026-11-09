package com.satori.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.category.CategoryRules
import com.satori.domain.language.NotesLanguageChoice
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.ThemeMode
import com.satori.domain.settings.KeyRead
import com.satori.domain.settings.QuickKeyBinding
import com.satori.presentation.settings.AiNotesModels
import com.satori.presentation.settings.AudioStorage
import com.satori.presentation.settings.SettingsViewModel
import com.satori.quickkey.QuickKeyBindingReader
import com.satori.ui.BuildConfig
import com.satori.ui.R
import com.satori.ui.components.recordingLanguageName
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.languageName
import com.satori.ui.design.rememberNoticeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sheets the Settings screen opens over its rows. */
private enum class SettingsSheet { NOTES_LANGUAGE, THEME, QUICK_KEY, AUDIO_COMPRESS, AUDIO_DELETE }

/**
 * Settings: a short list. AI notes on or off, the language of the notes, categories, what the
 * power key does, reminders when notifications are blocked, the theme, what a recording keeps and
 * for how long, how it feels, and About. Everything technical is one level down in Advanced.
 *
 * @param onNavigateToLlmDownload opens the download of the AI model, offered while none is on
 *   the phone.
 * @param onNavigateToModels opens Models and storage ([MODELS_ROUTE]): the models on the phone,
 *   what they take and what uses them, and the cleanup.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToLlmDownload: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val aiNotesModels by viewModel.aiNotesModels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val quickKeyCategoryId by viewModel.quickKeyCategoryId.collectAsStateWithLifecycle()
    val quickKeyStopOnSecondPress by viewModel.quickKeyStopOnSecondPress.collectAsStateWithLifecycle()
    val quickKeyLanguage by viewModel.quickKeyLanguage.collectAsStateWithLifecycle()
    val malayalamOnPhone by viewModel.malayalamOnPhone.collectAsStateWithLifecycle()
    val quickKeyProof by viewModel.quickKeyProof.collectAsStateWithLifecycle()
    val quickKeyRead = rememberQuickKeyRead()
    val audioStorage by viewModel.audioStorage.collectAsStateWithLifecycle()
    val audioDeleteToConfirm by viewModel.audioDeleteToConfirm.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.keepAudioRetentionScheduled() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notices = rememberNoticeState()
    val notificationsEnabled = rememberNotificationsEnabled()
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }

    // The category the power key records as; the first one if the chosen one was deleted. And the
    // language it records in now: none to choose for a category that translates, and English or
    // German while the Malayalam model isn't on the phone.
    val quickKeyCategory = categories?.let { CategoryRules.chosen(it, quickKeyCategoryId) }
    val quickKeyRecordsIn = quickKeyCategory?.takeIf { it.mode != RecordingMode.REAL_TIME_TRANSLATION }
        ?.let { quickKeyLanguage.usableWith(malayalamOnPhone) }
    // Whether the key reaches Satori: what the phone's settings say, a press that came, or the
    // owner's word.
    val quickKeyBinding = QuickKeyBinding.of(quickKeyRead, quickKeyProof.lastPressAtMs, quickKeyProof.setUpByHand, context.packageName)
    val notesLanguages = NotesLanguage.entries
    val notesLanguageLabels = notesLanguages.map { notesLanguageLabel(it) }
    val themeModes = ThemeMode.entries
    val themeLabels = themeModes.map { themeLabel(it) }
    val compressChoices = AppSettings.AUDIO_COMPRESS_CHOICES
    val compressLabels = compressChoices.map { audioAgeLabel(it) }
    val deleteChoices = AppSettings.AUDIO_DELETE_CHOICES
    val deleteLabels = deleteChoices.map { audioAgeLabel(it) }
    val commandsCopied = stringResource(R.string.g_quickkey_copied)

    SettingsScaffold(
        title = stringResource(R.string.g_settings),
        onBack = onNavigateBack,
        overlay = { NoticeHost(notices, Modifier.align(Alignment.BottomCenter)) }
    ) {
        AiNotesRow(
            enabled = settings.llmEnabled,
            models = aiNotesModels,
            onEnabledChange = viewModel::updateLlmEnabled,
            onGetModel = onNavigateToLlmDownload
        )
        SettingsDivider()
        SettingsLinkRow(
            title = stringResource(R.string.g_settings_notes_language),
            value = notesLanguageLabels[notesLanguages.indexOf(settings.notesLanguage)],
            supporting = stringResource(R.string.g_settings_notes_language_desc),
            onClick = { sheet = SettingsSheet.NOTES_LANGUAGE }
        )
        SettingsDivider()
        SettingsLinkRow(
            title = stringResource(R.string.g_settings_categories),
            value = categories?.size?.toString(),
            supporting = stringResource(R.string.g_settings_categories_desc),
            onClick = onNavigateToCategories
        )
        SettingsDivider()
        SettingsLinkRow(
            title = stringResource(R.string.g_quickkey_title),
            supporting = quickKeyCategory?.let { category ->
                val malayalam = quickKeyRecordsIn == RecordingLanguage.MALAYALAM
                stringResource(
                    quickKeyRowLine(quickKeyBinding, malayalam),
                    category.name,
                    recordingLanguageName(RecordingLanguage.MALAYALAM)
                )
            },
            onClick = { sheet = SettingsSheet.QUICK_KEY }
        )
        SettingsDivider()
        if (!notificationsEnabled) {
            SettingsRow(
                title = stringResource(R.string.g_settings_reminders_off),
                supporting = stringResource(R.string.g_settings_reminders_off_desc),
                trailing = {
                    PillButton(
                        text = stringResource(R.string.g_settings_reminders_turn_on),
                        onClick = { context.startActivity(notificationSettingsIntent(context.packageName)) },
                        kind = PillKind.Outline
                    )
                }
            )
            SettingsDivider()
        }
        SettingsLinkRow(
            title = stringResource(R.string.g_settings_theme),
            value = themeLabels[themeModes.indexOf(settings.themeMode)],
            supporting = stringResource(R.string.g_settings_theme_desc),
            onClick = { sheet = SettingsSheet.THEME }
        )
        SettingsDivider()
        SettingsLinkRow(
            title = stringResource(R.string.g_settings_advanced),
            supporting = stringResource(R.string.g_settings_advanced_desc),
            onClick = onNavigateToAdvanced,
            onClickLabel = stringResource(R.string.g_settings_advanced)
        )
        SettingsDivider()
        SettingsLinkRow(
            title = stringResource(R.string.g_storage_title),
            supporting = stringResource(R.string.g_storage_row_desc),
            onClick = onNavigateToModels,
            onClickLabel = stringResource(R.string.g_storage_title)
        )
        SettingsDivider()
        RecordingSection(
            keepAudio = settings.keepAudio,
            compressAfter = audioAgeLabel(settings.audioCompressAfterDays),
            deleteAfter = audioAgeLabel(settings.audioDeleteAfterDays),
            audioStorage = audioStorage,
            savePlace = settings.savePlace,
            vibrate = settings.vibrateOnStartStop,
            keepScreenOn = settings.keepScreenOnWhileRecording,
            onKeepAudioChange = viewModel::updateKeepAudio,
            onCompressClick = { sheet = SettingsSheet.AUDIO_COMPRESS },
            onDeleteClick = { sheet = SettingsSheet.AUDIO_DELETE },
            onSavePlaceChange = viewModel::updateSavePlace,
            onVibrateChange = viewModel::updateVibrateOnStartStop,
            onTryVibrate = { SettingsHaptics.playStart(context) },
            onKeepScreenOnChange = viewModel::updateKeepScreenOnWhileRecording
        )
        AboutSection(onOpenLicences = onNavigateToLicenses)
    }

    audioDeleteToConfirm?.let { confirm ->
        ConfirmSheet(
            title = pluralStringResource(R.plurals.g_audio_delete_now_title, confirm.recordings, confirm.recordings),
            text = pluralStringResource(R.plurals.g_audio_delete_now_body, confirm.days, confirm.days),
            confirmLabel = stringResource(R.string.g_audio_delete_now_confirm),
            onConfirm = viewModel::confirmAudioDelete,
            onDismiss = viewModel::dismissAudioDelete
        )
    }

    when (sheet) {
        SettingsSheet.NOTES_LANGUAGE -> ChoiceSheet(
            title = stringResource(R.string.g_settings_notes_language),
            options = notesLanguageLabels,
            selectedIndex = notesLanguages.indexOf(settings.notesLanguage),
            onSelect = {
                viewModel.updateNotesLanguage(notesLanguages[it])
                sheet = null
            },
            onDismiss = { sheet = null },
            supporting = notesLanguages.map { notesLanguageLine(it) },
            help = stringResource(R.string.g_settings_notes_language_help)
        )
        SettingsSheet.THEME -> ChoiceSheet(
            title = stringResource(R.string.g_settings_theme),
            options = themeLabels,
            selectedIndex = themeModes.indexOf(settings.themeMode),
            onSelect = {
                viewModel.updateThemeMode(themeModes[it])
                sheet = null
            },
            onDismiss = { sheet = null },
            supporting = themeModes.map { themeLine(it) }
        )
        SettingsSheet.QUICK_KEY -> {
            QuickKeySheet(
                categories = categories.orEmpty(),
                selected = quickKeyCategory,
                language = quickKeyRecordsIn,
                malayalamOnPhone = malayalamOnPhone,
                stopOnSecondPress = quickKeyStopOnSecondPress,
                onSelectCategory = { viewModel.setQuickKeyCategory(it.id) },
                onSelectLanguage = viewModel::setQuickKeyLanguage,
                onGetMalayalam = {
                    sheet = null
                    onNavigateToAdvanced()
                },
                onStopOnSecondPressChange = viewModel::setQuickKeyStopOnSecondPress,
                onSetupCopied = {
                    // Android 13 and later confirm a copy themselves, and the sheet stays with the
                    // commands. Before that the notice says it, on the screen, so the sheet makes
                    // way for it.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        sheet = null
                        scope.launch { notices.show(commandsCopied) }
                    }
                },
                onDismiss = { sheet = null }
            )
        }
        SettingsSheet.AUDIO_COMPRESS -> ChoiceSheet(
            title = stringResource(R.string.g_audio_compress),
            options = compressLabels,
            selectedIndex = compressChoices.indexOf(settings.audioCompressAfterDays),
            onSelect = {
                viewModel.updateAudioCompressAfterDays(compressChoices[it])
                sheet = null
            },
            onDismiss = { sheet = null },
            supporting = compressChoices.map { audioCompressLine(it) },
            help = stringResource(R.string.g_audio_rules_kept)
        )
        SettingsSheet.AUDIO_DELETE -> ChoiceSheet(
            title = stringResource(R.string.g_audio_delete_older),
            options = deleteLabels,
            selectedIndex = deleteChoices.indexOf(settings.audioDeleteAfterDays),
            onSelect = {
                // An age that deletes audio at once is asked about first (audioDeleteToConfirm).
                viewModel.chooseAudioDeleteAfterDays(deleteChoices[it])
                sheet = null
            },
            onDismiss = { sheet = null },
            supporting = deleteChoices.map { audioDeleteLine(it) },
            help = stringResource(R.string.g_audio_rules_kept)
        )
        null -> Unit
    }
}

/**
 * The AI notes switch, with what it does and what turning it off saves. While no AI model for live
 * notes is on the phone it is off and cannot be turned on; a second line says why, also when only
 * Gemma 4 E2B is there, which Advanced lists as on the phone, and one action opens the download.
 * While one is on its way (the first run downloads it after the first recording) the switch works
 * as set, and a line says the notes follow, as home's strip and the recorder do.
 */
@Composable
private fun AiNotesRow(
    enabled: Boolean,
    models: AiNotesModels,
    onEnabledChange: (Boolean) -> Unit,
    onGetModel: () -> Unit
) {
    if (models == AiNotesModels.READY) {
        SettingsSwitchRow(
            title = stringResource(R.string.g_settings_ai_notes),
            supporting = stringResource(R.string.g_settings_ai_notes_desc),
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    } else if (models == AiNotesModels.ON_ITS_WAY) {
        SettingsSwitchRow(
            title = stringResource(R.string.g_settings_ai_notes),
            supporting = stringResource(R.string.g_settings_ai_notes_desc),
            description = stringResource(R.string.g_settings_ai_notes_coming).takeIf { enabled },
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            SettingsSwitchRow(
                title = stringResource(R.string.g_settings_ai_notes),
                supporting = stringResource(R.string.g_settings_ai_notes_desc),
                description = stringResource(
                    if (models == AiNotesModels.MISSING_BESIDE_FINISHING_MODEL) {
                        R.string.g_settings_ai_notes_missing_e2b
                    } else {
                        R.string.g_settings_ai_notes_missing
                    }
                ),
                checked = false,
                onCheckedChange = {},
                enabled = false
            )
            PillButton(
                text = stringResource(R.string.g_settings_ai_get_model),
                onClick = onGetModel,
                modifier = Modifier.padding(start = GrilleSpace.margin, bottom = GrilleSpace.m),
                kind = PillKind.Outline
            )
        }
    }
}

/**
 * What a recording keeps and how it feels: its audio, for how long and how much of it there is,
 * the place it starts, a buzz when the microphone goes on and off, and the screen kept on while it
 * is on. Under their own heading just above About, so the rows above, which have none, don't read
 * as part of it. The rules for older audio stay offered with Keep audio off, as audio kept before
 * stays.
 *
 * @param compressAfter The value of "Compress older audio", e.g. "After 7 days"
 * @param deleteAfter The value of "Delete older audio"
 * @param audioStorage How much audio there is, or null until it is read
 * @param onTryVibrate Plays the buzz of a start
 */
@Composable
private fun RecordingSection(
    keepAudio: Boolean,
    compressAfter: String,
    deleteAfter: String,
    audioStorage: AudioStorage?,
    savePlace: Boolean,
    vibrate: Boolean,
    keepScreenOn: Boolean,
    onKeepAudioChange: (Boolean) -> Unit,
    onCompressClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSavePlaceChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onTryVibrate: () -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit
) {
    SettingsHeading(stringResource(R.string.g_settings_section_recording))
    SettingsSwitchRow(
        title = stringResource(R.string.g_audio_keep),
        supporting = stringResource(R.string.g_audio_keep_desc),
        checked = keepAudio,
        onCheckedChange = onKeepAudioChange
    )
    SettingsDivider()
    SettingsLinkRow(
        title = stringResource(R.string.g_audio_compress),
        value = compressAfter,
        supporting = stringResource(R.string.g_audio_compress_desc),
        onClick = onCompressClick
    )
    SettingsDivider()
    SettingsLinkRow(
        title = stringResource(R.string.g_audio_delete_older),
        value = deleteAfter,
        supporting = stringResource(R.string.g_audio_delete_older_desc),
        onClick = onDeleteClick
    )
    // Nothing to say before it is read, or while no recording has audio.
    if (audioStorage != null && audioStorage.recordings > 0) {
        SettingsNote(
            pluralStringResource(
                R.plurals.g_audio_storage,
                audioStorage.recordings,
                Formatter.formatShortFileSize(LocalContext.current, audioStorage.bytes),
                audioStorage.recordings
            )
        )
    }
    SettingsDivider()
    SavePlaceRow(savePlace = savePlace, onSavePlaceChange = onSavePlaceChange)
    SettingsDivider()
    Column(Modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            title = stringResource(R.string.g_settings_vibrate),
            supporting = stringResource(R.string.g_settings_vibrate_desc),
            checked = vibrate,
            onCheckedChange = onVibrateChange
        )
        PillButton(
            text = stringResource(R.string.g_settings_vibrate_try),
            onClick = onTryVibrate,
            modifier = Modifier.padding(start = GrilleSpace.margin, bottom = GrilleSpace.m),
            kind = PillKind.Outline
        )
    }
    SettingsDivider()
    SettingsSwitchRow(
        title = stringResource(R.string.g_settings_keep_screen_on),
        supporting = stringResource(R.string.g_settings_keep_screen_on_desc),
        checked = keepScreenOn,
        onCheckedChange = onKeepScreenOnChange
    )
    SettingsDivider()
}

/** The app's name and version, what it does with the user's data, and the licences. */
@Composable
private fun AboutSection(onOpenLicences: () -> Unit) {
    SettingsHeading(stringResource(R.string.g_settings_section_about))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = Grille.type.body,
            color = Grille.colors.ink
        )
        Text(
            text = stringResource(R.string.g_settings_version, BuildConfig.VERSION_NAME),
            style = Grille.type.caption,
            color = Grille.colors.ash
        )
        Spacer(Modifier.height(GrilleSpace.m))
        Text(
            text = stringResource(R.string.g_settings_privacy_body),
            style = Grille.type.body,
            color = Grille.colors.ash
        )
        Spacer(Modifier.height(GrilleSpace.s))
    }
    SettingsDivider()
    SettingsLinkRow(
        title = stringResource(R.string.g_settings_licences),
        supporting = stringResource(R.string.g_settings_licences_desc),
        onClick = onOpenLicences,
        onClickLabel = stringResource(R.string.g_settings_licences)
    )
}

@Composable
private fun notesLanguageLabel(language: NotesLanguage): String = when (language) {
    NotesLanguage.AUTO -> stringResource(R.string.g_settings_notes_language_auto)
    NotesLanguage.EN -> languageName("en")
    NotesLanguage.DE -> languageName("de")
}

/** What picking [language] for the notes means, in the line under it in the sheet. */
@Composable
private fun notesLanguageLine(language: NotesLanguage): String = stringResource(notesLanguageLineRes(language))

/**
 * The line under [language] in the Notes language sheet. Auto's is true of every recording without
 * naming a language whose notes are English (the owner, 2026-10-15), and follows the rule for other
 * languages ([NotesLanguageChoice.OTHER_LANGUAGES_IN_ENGLISH]).
 */
internal fun notesLanguageLineRes(language: NotesLanguage, otherLanguagesInEnglish: Boolean = NotesLanguageChoice.OTHER_LANGUAGES_IN_ENGLISH): Int =
    when (language) {
        NotesLanguage.AUTO ->
            if (otherLanguagesInEnglish) R.string.g_settings_notes_language_auto_desc else R.string.g_settings_notes_language_auto_desc_spoken
        NotesLanguage.EN -> R.string.g_settings_notes_language_en_desc
        NotesLanguage.DE -> R.string.g_settings_notes_language_de_desc
    }

/** "Never" for null, else "After N days". */
@Composable
private fun audioAgeLabel(days: Int?): String =
    if (days == null) stringResource(R.string.g_audio_never) else pluralStringResource(R.plurals.g_audio_after_days, days, days)

/** What compressing audio after [days] means, null for never, in the line under it in the sheet. */
@Composable
private fun audioCompressLine(days: Int?): String =
    if (days == null) {
        stringResource(R.string.g_audio_compress_never_desc)
    } else {
        pluralStringResource(R.plurals.g_audio_compress_after_desc, days, days)
    }

/** What deleting audio after [days] means, null for never, in the line under it in the sheet. */
@Composable
private fun audioDeleteLine(days: Int?): String =
    if (days == null) {
        stringResource(R.string.g_audio_delete_never_desc)
    } else {
        pluralStringResource(R.plurals.g_audio_delete_after_desc, days, days)
    }

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.g_settings_theme_system
        ThemeMode.LIGHT -> R.string.g_settings_theme_light
        ThemeMode.DARK -> R.string.g_settings_theme_dark
    }
)

/** What picking the theme [mode] means, in the line under it in the sheet. */
@Composable
private fun themeLine(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.g_settings_theme_system_desc
        ThemeMode.LIGHT -> R.string.g_settings_theme_light_desc
        ThemeMode.DARK -> R.string.g_settings_theme_dark_desc
    }
)

/**
 * The line under the Power key row, by whether the key reaches Satori ([QuickKeyBinding]): set up,
 * what two presses start, with no word about a setup; not set up or bound to another app, that
 * alone; unknown, what the key records as. Each string takes the category's name and the
 * language's, the latter used only where it says [malayalam].
 */
internal fun quickKeyRowLine(binding: QuickKeyBinding, malayalam: Boolean): Int = when (binding) {
    QuickKeyBinding.SET_UP -> if (malayalam) R.string.g_quickkey_record_as_in else R.string.g_quickkey_record_as
    QuickKeyBinding.NOT_SET_UP -> R.string.g_quickkey_row_not_set_up
    QuickKeyBinding.OTHER_APP -> R.string.g_quickkey_row_other_app
    QuickKeyBinding.UNKNOWN -> if (malayalam) R.string.g_quickkey_row_unknown_in else R.string.g_quickkey_row_unknown
}

/**
 * What the phone's settings say of the power key ([QuickKeyBindingReader]), read off the main
 * thread each time the screen comes back: the key is bound on a computer meanwhile, or in the
 * phone's own settings. Null until the first read is done.
 */
@Composable
private fun rememberQuickKeyRead(): KeyRead? {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var read by remember { mutableStateOf<KeyRead?>(null) }
    LifecycleResumeEffect(resolver) {
        val job = scope.launch { read = withContext(Dispatchers.IO) { QuickKeyBindingReader.read(resolver) } }
        onPauseOrDispose { job.cancel() }
    }
    return read
}

/**
 * Whether the app may post notifications, read again each time the screen comes back: the
 * person turns them on in the system settings that "Turn on" opens.
 */
@Composable
private fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    return enabled
}

/** The system page where this app's notifications are switched on. */
private fun notificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
