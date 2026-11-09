package com.satori.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.device.DeviceTierDetector
import com.satori.data.quickkey.QuickKeySettings
import com.satori.data.quickkey.QuickKeySettingsRepository
import com.satori.data.service.DownloadStateManager
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelChoice
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.PauseReason
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.ThemeMode
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase
import com.satori.domain.usecase.audio.AudioRetentionScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * How much audio of recordings the phone keeps, for the line under the rules for older audio.
 *
 * @property recordings How many recordings have audio
 */
data class AudioStorage(val bytes: Long, val recordings: Int) {
    companion object {
        /** @param bytesByRecording What each recording's audio takes ([RecordingAudioRepository.storedBytes]) */
        fun of(bytesByRecording: Map<String, Long>) = AudioStorage(bytesByRecording.values.sum(), bytesByRecording.size)
    }
}

/**
 * Has the runs of the rules for older audio follow a change of the settings from [before] to
 * [after] ([AudioRetentionScheduler.rulesChanged]), when either rule changed: choosing the age
 * that is stored already starts no run.
 */
internal fun AudioRetentionScheduler.followRules(before: AppSettings, after: AppSettings) {
    if (after.audioCompressAfterDays != before.audioCompressAfterDays ||
        after.audioDeleteAfterDays != before.audioDeleteAfterDays
    ) {
        rulesChanged(after.audioCompressAfterDays, after.audioDeleteAfterDays)
    }
}

/**
 * What the app knows itself of whether the power key reaches it, beside what the phone's settings
 * say ([com.satori.domain.settings.QuickKeyBinding.of]).
 *
 * @property lastPressAtMs When a press last reached Satori, or null if none ever did
 * @property setUpByHand Whether the owner said the key is set up
 */
data class QuickKeyProof(val lastPressAtMs: Long? = null, val setUpByHand: Boolean = false)

/** What the AI notes row of Settings shows, by the AI models on the phone and the one on its way. */
enum class AiNotesModels {
    /** A model that writes live notes is on the phone: the switch works. */
    READY,

    /**
     * None is on the phone yet, but one that writes live notes is on its way: its download runs,
     * its files are checked, or it waits (for Wi-Fi, a recording, room), as the first run's does
     * after the first recording. The switch works as set, and the row says notes follow once it
     * is in; nothing to get.
     */
    ON_ITS_WAY,

    /** No AI model is on the phone: the switch is off, and the row offers the download. */
    MISSING,

    /**
     * Only Gemma 4 E2B is, which writes nothing while recording and works after Stop only beside a
     * model for live notes: the switch is off, the row says what is missing and offers it.
     */
    MISSING_BESIDE_FINISHING_MODEL;

    companion object {
        /**
         * @param installed The AI models whose file is on the phone
         * @param download The shared state of AI model downloads, about [downloadVariant]
         */
        fun of(
            installed: Set<LlmModelVariant>,
            download: DownloadState = DownloadState.Idle,
            downloadVariant: LlmModelVariant? = null
        ): AiNotesModels = when {
            installed.any { it.writesLiveNotes } -> READY
            downloadVariant?.writesLiveNotes == true && onItsWay(download) -> ON_ITS_WAY
            installed.isNotEmpty() -> MISSING_BESIDE_FINISHING_MODEL
            else -> MISSING
        }

        /** A download that runs, checks its files or waits by itself; not one the owner paused. */
        private fun onItsWay(download: DownloadState): Boolean = when (download) {
            is DownloadState.Downloading, is DownloadState.Verifying -> true
            is DownloadState.Paused -> download.reason != PauseReason.USER
            else -> false
        }
    }
}

/**
 * A delete age for older audio that would delete audio at once, waiting for the owner to say so.
 *
 * @property days The age chosen
 * @property recordings How many recordings lose their audio when it applies
 */
data class AudioDeleteConfirm(val days: Int, val recordings: Int)

/**
 * What choosing a delete age for older audio leads to: an age that would delete audio at once is
 * asked about first, with the count, as the deleted audio can't be brought back.
 */
internal sealed interface AudioDeleteChoice {
    /** The age is the one stored: nothing to do. */
    data object Unchanged : AudioDeleteChoice

    /** Never, or an age that deletes nothing now: stored at once. */
    data object Apply : AudioDeleteChoice

    /** An age that deletes audio at once: stored only once the owner confirms [confirm]. */
    data class Ask(val confirm: AudioDeleteConfirm) : AudioDeleteChoice

    companion object {
        /**
         * @param wouldDelete How many recordings would lose their audio at once with an age; asked
         *   only for a new age, as it is a dry run of the plan
         */
        suspend fun of(stored: Int?, chosen: Int?, wouldDelete: suspend (Int) -> Int): AudioDeleteChoice = when {
            chosen == stored -> Unchanged
            chosen == null -> Apply
            else -> wouldDelete(chosen).let { count -> if (count > 0) Ask(AudioDeleteConfirm(chosen, count)) else Apply }
        }
    }
}

/**
 * ViewModel for the Settings screen, and the theme MainActivity applies: the few things worth
 * changing every day, how recording feels among them. The technical ones (models, prompts, voice
 * detection) are in [AdvancedSettingsViewModel].
 *
 * At creation it also makes the stored settings point at an AI model that is on the phone, if
 * there is one (see [useInstalledModel]). What it reads of the audio, it reads only while the
 * screen shows it, so the copy MainActivity keeps for the theme reads none.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelDownloadManager: ModelDownloadManager,
    private val downloadStateManager: DownloadStateManager,
    private val deviceTierDetector: DeviceTierDetector,
    categoryRepository: CategoryRepository,
    private val quickKeySettingsRepository: QuickKeySettingsRepository,
    private val audioRepository: RecordingAudioRepository,
    private val applyAudioRetention: ApplyAudioRetentionUseCase,
    private val retentionScheduler: AudioRetentionScheduler
) : ViewModel() {

    private val editor = SettingsEditor(settingsRepository, viewModelScope)

    /** The settings, the defaults until the stored ones are read. */
    val settings: StateFlow<AppSettings> = settingsRepository.getSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * Whether an AI model that writes live notes is on the phone, so AI notes can be switched on,
     * and what the row says while none is ([AiNotesModels]). It follows downloads and deletions,
     * also those made on another screen.
     */
    val aiNotesModels: StateFlow<AiNotesModels> = combine(
        downloadStateManager.llmDownloadState,
        downloadStateManager.llmVariant,
        modelDownloadManager.modelFilesChanged
    ) { download, variant, _ -> AiNotesModels.of(modelDownloadManager.installedLlmVariants(), download, variant) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AiNotesModels.of(modelDownloadManager.installedLlmVariants())
        )

    /** The user's recording categories, or null while they load. */
    val categories: StateFlow<List<RecordingCategory>?> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The id of the category the power key records as, or null while it loads. */
    val quickKeyCategoryId: StateFlow<String?> = quickKeySettingsRepository.observe()
        .map { it.categoryId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The language the power key records in, as stored; the default while it loads. The sheet and
     * the row show the one it records in now ([RecordingLanguage.usableWith] with [malayalamOnPhone]).
     */
    val quickKeyLanguage: StateFlow<RecordingLanguage> = quickKeySettingsRepository.observe()
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordingLanguage.ENGLISH_OR_GERMAN)

    /**
     * Whether the Malayalam speech model is on the phone, whole, so the power key's sheet offers
     * Malayalam. It follows downloads and deletions, also those made on another screen.
     */
    val malayalamOnPhone: StateFlow<Boolean> = combine(
        downloadStateManager.sttDownloadState,
        modelDownloadManager.modelFilesChanged
    ) { _, _ -> modelDownloadManager.checkSttModel(SpeechModelId.INDICCONFORMER_ML) == ModelFileState.Intact }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * What the app itself knows of whether the power key reaches it: when a press last came, and
     * whether the owner said it is set up ([com.satori.domain.settings.QuickKeyBinding]); neither
     * while it loads.
     */
    val quickKeyProof: StateFlow<QuickKeyProof> = quickKeySettingsRepository.observe()
        .map { QuickKeyProof(it.lastPressAtMs, it.setUpByHand) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), QuickKeyProof())

    /** Whether a second press of the power key stops the recording; the default while it loads. */
    val quickKeyStopOnSecondPress: StateFlow<Boolean> = quickKeySettingsRepository.observe()
        .map { it.stopOnSecondPress }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            QuickKeySettings.DEFAULT_STOP_ON_SECOND_PRESS
        )

    /**
     * How much audio the phone keeps, or null until it is read. Read while the screen shows it,
     * and again each time audio is compressed or deleted meanwhile, as after a new rule's run;
     * by the files' sizes alone, so it costs little however many recordings have audio.
     */
    val audioStorage: StateFlow<AudioStorage?> = audioRepository.observeChanges()
        .onStart { emit(Unit) }
        .conflate()
        .map { AudioStorage.of(audioRepository.storedBytes()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _audioDeleteToConfirm = MutableStateFlow<AudioDeleteConfirm?>(null)

    /** A delete age that would delete audio at once, waiting for [confirmAudioDelete] or [dismissAudioDelete]. */
    val audioDeleteToConfirm: StateFlow<AudioDeleteConfirm?> = _audioDeleteToConfirm.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { useInstalledModel() }
    }

    /**
     * Points the settings at an AI model that is on the phone. A model copied over by hand, or
     * the one in use deleted, leaves the selection on a file that is not there while another
     * one is: the recorder would then report no AI model although one is installed. Also stores
     * the path of the selected model's file, which the details screen reads. Does nothing while
     * no model is on the phone, or the selection already works.
     */
    private suspend fun useInstalledModel() {
        val current = settingsRepository.getSettings().first()
        val variant = LlmModelChoice.pick(
            selected = current.llmModelVariant,
            recommended = deviceTierDetector.detectRecommendedVariant(),
            installed = modelDownloadManager.installedLlmVariants()
        ) ?: return
        val path = modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename) ?: return
        if (variant != current.llmModelVariant || path != current.llmModelPath) {
            editor.edit { it.copy(llmModelVariant = variant, llmModelPath = path) }
        }
    }

    /**
     * Turns AI notes on or off. When off, no AI model is loaded during recording sessions and no
     * notes are written, which saves memory and battery.
     */
    fun updateLlmEnabled(enabled: Boolean) {
        editor.edit { it.copy(llmEnabled = enabled) }
    }

    /**
     * Sets the language of the AI notes; [NotesLanguage.AUTO] follows what was said. Applies
     * from the next note.
     */
    fun updateNotesLanguage(language: NotesLanguage) {
        editor.edit { it.copy(notesLanguage = language) }
    }

    /** Sets the app theme: follow the system, always light, or always dark. */
    fun updateThemeMode(themeMode: ThemeMode) {
        editor.edit { it.copy(themeMode = themeMode) }
    }

    /**
     * Sets whether the phone buzzes as the microphone goes on and as it goes off. The recording
     * service reads it as each recording starts.
     */
    fun updateVibrateOnStartStop(enabled: Boolean) {
        editor.edit { it.copy(vibrateOnStartStop = enabled) }
    }

    /** Sets whether the recorder keeps the screen on while the microphone is live. */
    fun updateKeepScreenOnWhileRecording(enabled: Boolean) {
        editor.edit { it.copy(keepScreenOnWhileRecording = enabled) }
    }

    /**
     * Sets whether recordings keep their audio, from the next one started. Audio kept before
     * stays with its recording until that is deleted.
     */
    fun updateKeepAudio(keep: Boolean) {
        editor.edit { it.copy(keepAudio = keep) }
    }

    /** Sets after how many days older audio is compressed; null for never. */
    fun updateAudioCompressAfterDays(days: Int?) {
        toTheEnd { changeRetention { it.copy(audioCompressAfterDays = days) } }
    }

    /**
     * Sets after how many days older audio is deleted; null for never. An age under which some
     * audio would go at once is only stored once the owner confirms it ([audioDeleteToConfirm]).
     */
    fun chooseAudioDeleteAfterDays(days: Int?) {
        toTheEnd {
            val stored = settingsRepository.getSettings().first()
            val choice = AudioDeleteChoice.of(stored.audioDeleteAfterDays, days) { age ->
                // The dry run goes through every recording, and the lines of those losing audio.
                withContext(Dispatchers.Default) { applyAudioRetention.plan(stored.audioCompressAfterDays, age).audioDeleted }
            }
            when (choice) {
                AudioDeleteChoice.Unchanged -> Unit
                AudioDeleteChoice.Apply -> changeRetention { it.copy(audioDeleteAfterDays = days) }
                is AudioDeleteChoice.Ask -> _audioDeleteToConfirm.value = choice.confirm
            }
        }
    }

    /** The owner confirms the delete age that was asked about. */
    fun confirmAudioDelete() {
        val confirm = _audioDeleteToConfirm.value ?: return
        _audioDeleteToConfirm.value = null
        toTheEnd { changeRetention { it.copy(audioDeleteAfterDays = confirm.days) } }
    }

    /** The owner keeps the delete age as it was. */
    fun dismissAudioDelete() {
        _audioDeleteToConfirm.value = null
    }

    /**
     * Schedules the daily run of the rules for older audio again while either is on, in case it
     * was lost; one that is scheduled keeps its time. Called as the screen shows.
     */
    fun keepAudioRetentionScheduled() {
        viewModelScope.launch {
            val stored = settingsRepository.getSettings().first()
            if (stored.audioCompressAfterDays != null || stored.audioDeleteAfterDays != null) {
                withContext(Dispatchers.IO) { retentionScheduler.scheduleDaily() }
            }
        }
    }

    /** Stores a change to the rules for older audio, and has the runs follow it ([followRules]). */
    private suspend fun changeRetention(change: (AppSettings) -> AppSettings) {
        val before = settingsRepository.getSettings().first()
        editor.update(change)
        val after = settingsRepository.getSettings().first()
        withContext(Dispatchers.IO) { retentionScheduler.followRules(before, after) }
    }

    /**
     * Runs [block] to its end once the owner has chosen, so a choice is stored, and the runs follow
     * it, even if the screen closes meanwhile.
     */
    private fun toTheEnd(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(NonCancellable) { block() } }
    }

    /**
     * Sets whether recordings store where they start, from the next one started. The screen asks
     * for the location permission before it turns this on.
     */
    fun updateSavePlace(save: Boolean) {
        editor.edit { it.copy(savePlace = save) }
    }

    /** Files recordings made with the power key under the category [categoryId]. */
    fun setQuickKeyCategory(categoryId: String) {
        viewModelScope.launch { quickKeySettingsRepository.setCategoryId(categoryId) }
    }

    /** Sets whether pressing the power key again stops the recording it started. */
    fun setQuickKeyStopOnSecondPress(stop: Boolean) {
        viewModelScope.launch { quickKeySettingsRepository.setStopOnSecondPress(stop) }
    }

    /** Records recordings made with the power key in [language]. */
    fun setQuickKeyLanguage(language: RecordingLanguage) {
        viewModelScope.launch { quickKeySettingsRepository.setLanguage(language) }
    }

    /**
     * The owner says the power key is set up ("Already set up" in its setup, which the sheet of
     * part Q4 draws in debug builds): the row then says what two presses start
     * ([com.satori.domain.settings.QuickKeyBinding]) where the phone's settings can't be read.
     */
    fun markQuickKeySetUp() {
        viewModelScope.launch { quickKeySettingsRepository.markSetUp() }
    }

    private companion object {
        /** How long the flows keep running after the screen is gone, to survive a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
