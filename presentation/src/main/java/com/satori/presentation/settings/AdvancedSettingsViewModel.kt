package com.satori.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.device.DeviceTierDetector
import com.satori.data.service.AndroidDownloadManager
import com.satori.data.service.DownloadStateManager
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadState
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.MicSource
import com.satori.domain.model.ModelStatusRules
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelStatus
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechVocabularyRepository
import com.satori.domain.usecase.stt.SpeechVocabulary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the Advanced settings: the models on the phone and how they are downloaded,
 * paused, chosen and deleted, how and when the AI writes notes for each kind of recording, the
 * translation, the microphone input and voice detection.
 *
 * The AI model in use is the one that is selected and on the phone. A download that finishes
 * becomes the one in use (see AndroidDownloadManager); selecting is only possible for a model
 * that is there. Gemma 4 E2B is never selected: while it is on the phone a switch decides whether
 * it does the work after Stop ([finishingModelEnabled]).
 *
 * Every recording in English or German transcribes with Parakeet v3, and one in Malayalam with the
 * Malayalam model. The other speech models make a better transcript after Stop
 * ([AppSettings.transcribeAfterStop]) or with Transcribe again; one speech model downloads at a
 * time. A whole Parakeet v3 can't be deleted, and is downloaded again only when its files are
 * damaged ([ModelsUiState.repairOffered]). The words to recognize ([words]) are the owner's names and terms, which
 * Parakeet v3 listens for once the vocabulary they need is on the phone.
 */
@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelDownloadManager: ModelDownloadManager,
    private val androidDownloadManager: AndroidDownloadManager,
    private val downloadStateManager: DownloadStateManager,
    private val deviceTierDetector: DeviceTierDetector,
    private val finishingModelRepository: FinishingModelRepository,
    private val vocabularyRepository: SpeechVocabularyRepository
) : ViewModel() {

    private val editor = SettingsEditor(settingsRepository, viewModelScope)

    private val removal = ModelRemoval(modelDownloadManager, settingsRepository, editor) { recommendedVariant }

    /** The settings, the defaults until the stored ones are read. */
    val settings: StateFlow<AppSettings> = settingsRepository.getSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether Gemma 4 E2B does the work after Stop while it is on the phone; on until switched off. */
    val finishingModelEnabled: StateFlow<Boolean> = finishingModelRepository.observeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** The AI model that suits this phone, which is tagged as recommended. */
    val recommendedVariant: LlmModelVariant by lazy { deviceTierDetector.detectRecommendedVariant() }

    /**
     * Which models are on the phone and what their downloads are doing. The file checks run off
     * the main thread and again whenever a download changes state or a model is deleted; the
     * check of Parakeet v3's files, which decides whether it is offered again, only there.
     */
    val models: StateFlow<ModelsUiState> = combine(
        settings.map { it.llmModelVariant }.distinctUntilChanged(),
        downloadStateManager.llmDownloadState,
        downloadStateManager.llmVariant,
        combine(downloadStateManager.sttDownloadState, downloadStateManager.sttModel, ::SpeechDownload),
        modelDownloadManager.modelFilesChanged
    ) { selected, llm, llmVariant, stt, _ -> modelsState(selected, llm, llmVariant, stt, checkLiveModel = true) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            modelsState(
                settings.value.llmModelVariant,
                downloadStateManager.llmDownloadState.value,
                downloadStateManager.llmVariant.value,
                SpeechDownload(downloadStateManager.sttDownloadState.value, downloadStateManager.sttModel.value),
                checkLiveModel = false
            )
        )

    /** The shared speech download state and the model it is about. */
    private data class SpeechDownload(val state: DownloadState, val model: SpeechModelId?)

    // Whether the last change of the words couldn't be stored; the row says so until one is.
    private val wordsSaveFailed = MutableStateFlow(false)

    /**
     * The words to recognize, and whether what they need is on the phone. The vocabulary is read
     * again whenever its download, a speech download or a deletion changes something.
     */
    val words: StateFlow<WordsUiState> = combine(
        vocabularyRepository.observe(),
        downloadStateManager.vocabularyDownloadState,
        downloadStateManager.sttDownloadState,
        modelDownloadManager.modelFilesChanged,
        wordsSaveFailed
    ) { words, vocabulary, _, _, saveFailed -> wordsState(words, vocabulary, saveFailed) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WordsUiState())

    private fun wordsState(words: List<String>, download: DownloadState, saveFailed: Boolean): WordsUiState = WordsUiState(
        words = words,
        vocabulary = when {
            modelDownloadManager.speechVocabularyFile() != null -> VocabularyStatus.OnPhone
            download is DownloadState.Downloading -> VocabularyStatus.Downloading
            download is DownloadState.Error -> VocabularyStatus.Failed(download.reason)
            else -> VocabularyStatus.Missing
        },
        liveModelOnPhone = modelDownloadManager.isSttModelDownloaded(SpeechModelId.LIVE),
        saveFailed = saveFailed
    )

    /**
     * @param checkLiveModel Whether to read Parakeet v3's files for [ModelsUiState.liveModelFiles],
     *   which reads their headers and metadata: off the main thread only
     */
    private fun modelsState(
        selected: LlmModelVariant,
        llm: DownloadState,
        llmVariant: LlmModelVariant?,
        stt: SpeechDownload,
        checkLiveModel: Boolean
    ): ModelsUiState {
        val installed = modelDownloadManager.installedLlmVariants()
        val ai = LlmModelVariant.entries.map { variant ->
            val status = ModelStatusRules.ai(
                variant = variant,
                selected = selected,
                installed = variant in installed,
                download = llm,
                downloadVariant = llmVariant,
                partialBytes = modelDownloadManager.llmPartialBytes(modelConfigForVariant(variant).llmFilename)
            )
            AiModelState(variant, status)
        }
        val speech = SpeechModelId.entries.map { model ->
            val status = ModelStatusRules.speechModel(
                model = model,
                installed = modelDownloadManager.isSttModelDownloaded(model),
                download = stt.state,
                downloadModel = stt.model,
                downloadedBytes = modelDownloadManager.speechModelBytesOnPhone(model)
            )
            SpeechModelState(model, status)
        }
        val liveInstalled = speech.any { it.model == SpeechModelId.LIVE && it.status == SpeechModelStatus.Installed }
        val liveFiles = if (checkLiveModel && liveInstalled) modelDownloadManager.checkSttModel(SpeechModelId.LIVE) else ModelFileState.Intact
        return ModelsUiState(speech, ai, liveFiles)
    }

    // Models

    /** Starts downloading [variant]; an earlier unfinished download of it is continued. */
    fun downloadVariant(variant: LlmModelVariant) {
        androidDownloadManager.startLlmDownload(variant)
    }

    /** Continues a paused or failed download of [variant] from the bytes already on the phone. */
    fun resumeVariant(variant: LlmModelVariant) {
        androidDownloadManager.resumeLlmDownload(variant)
    }

    /** Pauses the AI model download that is running. What was downloaded stays on the phone. */
    fun pauseDownload() {
        androidDownloadManager.cancelLlmDownload()
    }

    /**
     * Makes [variant], which must be on the phone, the model that writes the notes. A model that
     * can't write live notes is never selected: its row offers no choice, and this refuses it too.
     */
    fun selectVariant(variant: LlmModelVariant) {
        if (!variant.writesLiveNotes) return
        val path = modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename) ?: return
        editor.edit { it.copy(llmModelVariant = variant, llmModelPath = path) }
    }

    /** Sets whether Gemma 4 E2B does the work after Stop. */
    fun setFinishingModelEnabled(enabled: Boolean) {
        viewModelScope.launch { finishingModelRepository.setEnabled(enabled) }
    }

    /**
     * Deletes [variant] with what is left of its download. If it was the model in use, another
     * one on the phone takes its place. Deleting the last model leaves AI notes without one and
     * forgets the stored path, so readers that only check it for blank report no model
     * ([ModelRemoval]). The file and the settings change together, also when the screen is left
     * meanwhile: half done, the settings would name a file that is gone.
     */
    fun deleteVariant(variant: LlmModelVariant) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) { removal.removeAi(variant) }
        }
    }

    /**
     * Downloads the speech model [model], or continues an unfinished download of it. One speech
     * model downloads at a time: the screen offers no other start while one runs.
     */
    fun downloadSpeech(model: SpeechModelId) {
        androidDownloadManager.startSttDownload(model)
    }

    /** Pauses the speech model download that is running. What was downloaded stays on the phone. */
    fun pauseSpeechDownload() {
        androidDownloadManager.cancelSttDownload()
    }

    /**
     * Downloads Parakeet v3 again from scratch, for when the files on the phone are damaged or
     * missing ([ModelsUiState.repairOffered]), the only time it is offered. They are deleted first,
     * because a damaged file that isn't empty would count as downloaded.
     */
    fun downloadSpeechAgain() {
        if (!models.value.repairOffered) return
        viewModelScope.launch(Dispatchers.IO) {
            modelDownloadManager.deleteSttModel(SpeechModelId.LIVE)
            androidDownloadManager.startSttDownload(SpeechModelId.LIVE)
        }
    }

    /**
     * Deletes the speech model [model] with what is left of its download, if it may go
     * ([ModelsUiState.mayDelete]). The transcript after Stop no longer names it once it is gone.
     * File and setting change together, also when the screen is left meanwhile.
     */
    fun deleteSpeechModel(model: SpeechModelId) {
        if (!models.value.mayDelete(model)) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) { removal.removeSpeech(model) }
        }
    }

    /** Sets the speech model that makes a better transcript after Stop; null switches it off. */
    fun setTranscribeAfterStop(model: SpeechModelId?) {
        editor.edit { it.copy(transcribeAfterStop = model) }
    }

    /**
     * Stores the words to recognize in [text], one per line ([SpeechVocabulary.clean]). The first
     * words fetch the vocabulary they need, 117 KB, when it is not on the phone yet. Words that
     * can't be stored leave the ones from before, and the row says so ([WordsUiState.saveFailed]).
     */
    fun setWords(text: String) {
        viewModelScope.launch {
            val words = SpeechVocabulary.clean(text)
            val stored = vocabularyRepository.set(words).isSuccess
            wordsSaveFailed.value = !stored
            val needsVocabulary = stored && words.isNotEmpty() &&
                withContext(Dispatchers.IO) { modelDownloadManager.speechVocabularyFile() == null }
            if (needsVocabulary) androidDownloadManager.startVocabularyDownload()
        }
    }

    /** Downloads the vocabulary of the words to recognize again, after a failed download. */
    fun downloadVocabulary() {
        androidDownloadManager.startVocabularyDownload()
    }

    // Notes

    /** Sets how often notes are written for [mode]: minutes for a long meeting, seconds otherwise. */
    fun updateModeInterval(mode: RecordingMode, interval: Int) {
        editor.edit { settings ->
            when (mode) {
                RecordingMode.SIMPLE_LISTENING -> settings.copy(simpleListeningIntervalSeconds = interval)
                RecordingMode.SHORT_MEETING -> settings.copy(shortMeetingIntervalSeconds = interval)
                RecordingMode.LONG_MEETING -> settings.copy(longMeetingIntervalMinutes = interval)
                RecordingMode.REAL_TIME_TRANSLATION -> settings.copy(translationIntervalSeconds = interval)
            }
        }
    }

    /** Sets whether notes for [mode] are written while recording or after it. */
    fun updateModeDefaultStrategy(mode: RecordingMode, strategy: InsightStrategy) {
        editor.edit { settings ->
            when (mode) {
                RecordingMode.SIMPLE_LISTENING -> settings.copy(simpleListeningDefaultStrategy = strategy)
                RecordingMode.SHORT_MEETING -> settings.copy(shortMeetingDefaultStrategy = strategy)
                RecordingMode.LONG_MEETING -> settings.copy(longMeetingDefaultStrategy = strategy)
                RecordingMode.REAL_TIME_TRANSLATION -> settings.copy(translationDefaultStrategy = strategy)
            }
        }
    }

    /** Sets the language translations are written in, as a BCP-47 code. */
    fun updateTranslationTargetLanguage(language: String) {
        editor.edit { it.copy(translationTargetLanguage = language) }
    }

    // Microphone. Each recording opens the input set here when it starts.

    /** Sets the microphone input recordings capture speech from. */
    fun updateMicSource(source: MicSource) {
        editor.edit { it.copy(micSource = source) }
    }

    // Voice detection. Each recording starts with a detector built from these values.

    /** Sets how long a pause has to be to end a line, in seconds (clamped to the valid range). */
    fun updateVadMinSilenceDuration(duration: Float) {
        val clamped = duration.coerceIn(MIN_SILENCE_DURATION_MIN, MIN_SILENCE_DURATION_MAX)
        editor.edit { it.copy(vadMinSilenceDuration = clamped) }
    }

    /** Sets the longest stretch of speech before it is split, in seconds (clamped to the valid range). */
    fun updateVadMaxSpeechDuration(duration: Float) {
        val clamped = duration.coerceIn(MAX_SPEECH_DURATION_MIN, MAX_SPEECH_DURATION_MAX)
        editor.edit { it.copy(vadMaxSpeechDuration = clamped) }
    }

    /** Sets the detection threshold: lower picks up quieter speech (clamped to the valid range). */
    fun updateVadThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(VAD_THRESHOLD_MIN, VAD_THRESHOLD_MAX)
        editor.edit { it.copy(vadThreshold = clamped) }
    }

    /** Sets all three voice detection values back to their defaults. */
    fun resetVadParameters() {
        editor.edit {
            it.copy(
                vadMinSilenceDuration = MIN_SILENCE_DURATION_DEFAULT,
                vadMaxSpeechDuration = MAX_SPEECH_DURATION_DEFAULT,
                vadThreshold = VAD_THRESHOLD_DEFAULT
            )
        }
    }

    companion object {
        const val MIN_SILENCE_DURATION_MIN = 0.1F
        const val MIN_SILENCE_DURATION_MAX = 2.0F
        const val MIN_SILENCE_DURATION_DEFAULT = 0.5F

        const val MAX_SPEECH_DURATION_MIN = 3.0F
        const val MAX_SPEECH_DURATION_MAX = 30.0F
        const val MAX_SPEECH_DURATION_DEFAULT = 20.0F

        const val VAD_THRESHOLD_MIN = 0.2F
        const val VAD_THRESHOLD_MAX = 0.9F
        const val VAD_THRESHOLD_DEFAULT = 0.4F

        /** How long the flows keep running after the screen is gone, to survive a fold. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
