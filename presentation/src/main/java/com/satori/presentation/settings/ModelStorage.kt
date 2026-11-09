package com.satori.presentation.settings

import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.quickkey.QuickKeySettingsRepository
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.settings.CleanupPlan
import com.satori.domain.settings.ModelActivity
import com.satori.domain.settings.ModelCleanup
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.domain.settings.ModelSettingsFacts
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what Settings' Models and storage shows ([ModelCleanup]): the models with files on the
 * phone and what they take, the settings that use them, and what runs now that may hold one. Also
 * for home's one hint about unused models ([unusedPlan], [ModelCleanup.hintDue]), which the status
 * strip draws (part Q4). The files are read off the main thread.
 */
@Singleton
class ModelStorageReader @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val quickKeySettingsRepository: QuickKeySettingsRepository,
    private val finishingModelRepository: FinishingModelRepository,
    private val activeRecorder: ActiveRecorder,
    private val finishingQueue: FinishingQueue,
    private val processing: LlmProcessingServiceController
) {
    /**
     * The models with files on the phone, speech models first, each as the registry orders them. The
     * Malayalam model's entry holds the reader's files too (0.8.0, design R7: every build of it,
     * whole or part of a download), which read Malayalam alone and go with it; whole is the speech
     * model's.
     */
    suspend fun modelsOnPhone(): List<ModelOnPhone> = withContext(Dispatchers.IO) {
        val speech = SpeechModelId.entries.mapNotNull { model ->
            val reader = if (model == SpeechModelId.INDICCONFORMER_ML) ReaderModelId.entries.sumOf(modelDownloadManager::readerModelBytesOnPhone) else 0L
            val bytes = modelDownloadManager.speechModelBytesOnPhone(model) + reader
            ModelOnPhone(ModelRef.Speech(model), bytes, modelDownloadManager.isSttModelDownloaded(model)).takeIf { bytes > 0 }
        }
        val ai = LlmModelVariant.entries.mapNotNull { variant ->
            val filename = modelConfigForVariant(variant).llmFilename
            val path = modelDownloadManager.getLlmModelPath(filename)
            val bytes = path?.let { File(it).length() } ?: modelDownloadManager.llmPartialBytes(filename)
            ModelOnPhone(ModelRef.Ai(variant), bytes, whole = path != null).takeIf { bytes > 0 }
        }
        speech + ai
    }

    /** What the current settings say of the models ([ModelCleanup.usesOf]). */
    suspend fun facts(): ModelSettingsFacts = factsOf(
        settings = settingsRepository.getSettings().first(),
        quickKeyLanguage = quickKeySettingsRepository.get().language,
        finishingModelOn = finishingModelRepository.observeEnabled().first()
    )

    /**
     * What runs now that may hold a model ([ModelCleanup.mayHold]), with the downloads running
     * as [downloading].
     */
    fun activity(downloading: Set<ModelRef> = emptySet()): ModelActivity = activityOf(
        recorder = activeRecorder.current.value?.activity,
        finishing = finishingQueue.busy.value,
        holders = processing.heldBy.value,
        downloading = downloading
    )

    /**
     * What "Remove what isn't used" would remove now, with what waits: for home's one hint, which
     * is due once it all takes [ModelCleanup.HINT_BYTES] ([ModelCleanup.hintDue]).
     */
    suspend fun unusedPlan(): CleanupPlan = ModelCleanup.unused(modelsOnPhone(), facts(), activity())

    /**
     * Each change that can change what the screen shows, but for the downloads, which the screen
     * follows itself: a model deleted, the settings, the power key's language, the switch of AI for
     * finished recordings, and what runs (the recorder, the finishing, the holders of the AI work).
     */
    fun changes(): Flow<Unit> = combine(
        combine(modelDownloadManager.modelFilesChanged, settingsRepository.getSettings(), quickKeySettingsRepository.observe()) { _, _, _ -> },
        finishingModelRepository.observeEnabled(),
        activeRecorder.current,
        finishingQueue.busy,
        processing.heldBy
    ) { _, _, _, _, _ -> }

    companion object {
        /** The settings' facts for [ModelCleanup] ([facts]), pure for the tests. */
        fun factsOf(settings: AppSettings, quickKeyLanguage: RecordingLanguage, finishingModelOn: Boolean) = ModelSettingsFacts(
            aiNotesOn = settings.llmEnabled,
            chosenAi = settings.llmModelVariant,
            finishingModelOn = finishingModelOn,
            betterTranscript = settings.transcribeAfterStop,
            malayalamChosen = settings.homeLanguage == RecordingLanguage.MALAYALAM || quickKeyLanguage == RecordingLanguage.MALAYALAM
        )

        /**
         * What runs now ([activity]), pure for the tests: a recorder that isn't idle, the finishing
         * queue or its holders, a Transcribe again's holder, and the holders of other AI work
         * ([com.satori.domain.finish.ProcessingHolders]).
         */
        fun activityOf(
            recorder: ActiveRecorder.Activity?,
            finishing: Boolean,
            holders: Set<String>,
            downloading: Set<ModelRef> = emptySet()
        ): ModelActivity = ModelActivity(
            recording = recorder != null && recorder != ActiveRecorder.Activity.IDLE,
            finishing = finishing || holders.any { it.startsWith(FINISH) },
            transcribingAgain = holders.any { it.startsWith(TRANSCRIBE) },
            aiWork = holders.any { holder -> AI_WORK.any { holder.startsWith(it) } },
            downloading = downloading
        )

        /**
         * The models whose download runs now: the speech download's model and the AI download's
         * variant while their state is downloading or checking the files, and the Malayalam model
         * while the reader's download ([reader]) does, as its entry holds the reader.
         */
        fun downloadingOf(
            speech: DownloadState,
            speechModel: SpeechModelId?,
            ai: DownloadState,
            aiVariant: LlmModelVariant?,
            reader: DownloadState = DownloadState.Idle
        ): Set<ModelRef> = setOfNotNull(
            speechModel?.takeIf { speech.isActive() }?.let { ModelRef.Speech(it) },
            aiVariant?.takeIf { ai.isActive() }?.let { ModelRef.Ai(it) },
            ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML).takeIf { reader.isActive() }
        )

        private fun DownloadState.isActive(): Boolean = this is DownloadState.Downloading || this is DownloadState.Verifying

        private const val FINISH = "finish:"
        private const val TRANSCRIBE = "transcribe:"
        private val AI_WORK = listOf("rewrite:", "todos:", "map")
    }
}
