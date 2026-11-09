package com.satori.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.device.DeviceTierDetector
import com.satori.data.service.DownloadStateManager
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.settings.CleanupPlan
import com.satori.domain.settings.ModelActivity
import com.satori.domain.settings.ModelCleanup
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.domain.settings.ModelSettingsFacts
import com.satori.domain.settings.ModelUse
import com.satori.domain.settings.RemoveRefusal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * One model of Models and storage.
 *
 * @property uses What in the settings uses it ([ModelCleanup.usesOf]); empty for one nothing uses
 * @property refusal Why it can't be removed now, or null when it can
 */
data class StoredModel(val model: ModelOnPhone, val uses: Set<ModelUse>, val refusal: RemoveRefusal?)

/**
 * What Models and storage shows.
 *
 * @property models The models with files on the phone, null until they are read
 * @property plan What "Remove what isn't used" removes now, and what waits
 * @property freeBytes The free space where the models are kept, null until read
 */
data class ModelStorageUiState(
    val models: List<StoredModel>? = null,
    val plan: CleanupPlan = CleanupPlan(emptyList()),
    val freeBytes: Long? = null
) {
    /** What the models on the phone take together. */
    val totalBytes: Long get() = models.orEmpty().sumOf { it.model.bytes }
}

/** What the screen says once a removal is done. */
sealed interface StorageNotice {
    /** [count] models went, freeing [bytes]; and what changed in the settings ([change]), if anything. */
    data class Removed(val count: Int, val bytes: Long, val change: SettingChange? = null) : StorageNotice

    /** Nothing went: something started meanwhile that may hold the model, or it is in use again. */
    data object InUseNow : StorageNotice
}

/**
 * ViewModel for Settings' Models and storage: every model on the phone with what it takes, what
 * it is for and whether a setting uses it; "Remove what isn't used", which asks once and removes
 * what it listed; and the removal of one model alone, which switches off the setting that names it
 * and says so ([ModelRemoval]). Nothing goes without the owner's tap, and a model that something
 * running may hold (a recording, a finishing, a Transcribe again, other AI work, its download)
 * stays until that ends ([ModelCleanup.refusal]): asked again at the tap, as what runs can change
 * while the question is open.
 */
@HiltViewModel
class ModelStorageViewModel @Inject constructor(
    private val reader: ModelStorageReader,
    private val modelDownloadManager: ModelDownloadManager,
    downloadStateManager: DownloadStateManager,
    settingsRepository: SettingsRepository,
    private val deviceTierDetector: DeviceTierDetector
) : ViewModel() {

    private val editor = SettingsEditor(settingsRepository, viewModelScope)
    private val removal = ModelRemoval(modelDownloadManager, settingsRepository, editor) { deviceTierDetector.detectRecommendedVariant() }

    // Read again after each removal; the rest follows the downloads and the reader's changes.
    private val removals = MutableStateFlow(0)

    private val notices = Channel<StorageNotice>(Channel.BUFFERED)

    /** What to say once a removal is done, each once. */
    val notice: Flow<StorageNotice> = notices.receiveAsFlow()

    /** The models whose download runs now, which can't be removed meanwhile. */
    private val downloads: StateFlow<Set<ModelRef>> = combine(
        downloadStateManager.sttDownloadState,
        downloadStateManager.sttModel,
        downloadStateManager.llmDownloadState,
        downloadStateManager.llmVariant,
        downloadStateManager.readerDownloadState
    ) { speech, speechModel, ai, aiVariant, reader -> ModelStorageReader.downloadingOf(speech, speechModel, ai, aiVariant, reader) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<ModelStorageUiState> = combine(downloads, reader.changes(), removals) { downloading, _, _ -> read(downloading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModelStorageUiState())

    private suspend fun read(downloading: Set<ModelRef>): ModelStorageUiState {
        val models = reader.modelsOnPhone()
        val facts = reader.facts()
        val activity = reader.activity(downloading)
        return ModelStorageUiState(
            models = storedModels(models, facts, activity),
            plan = ModelCleanup.unused(models, facts, activity),
            freeBytes = withContext(Dispatchers.IO) { nearestFolder(modelDownloadManager.speechModelDir())?.usableSpace }
        )
    }

    /**
     * Removes what "Remove what isn't used" listed in [plan], the plan the owner saw and said yes
     * to: each of its models only if it still isn't used and nothing running may hold it.
     */
    fun removeUnused(plan: CleanupPlan) {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                val now = ModelCleanup.unused(reader.modelsOnPhone(), reader.facts(), reader.activity(downloads.value))
                    .models.mapTo(mutableSetOf()) { it.ref }
                val going = plan.models.filter { it.ref in now }
                going.forEach { remove(it.ref) }
                val freed = going.sumOf { it.bytes }
                Log.i(TAG, "Models removed as unused: ${going.size} of ${plan.models.size}, ${freed / BYTES_PER_MB} MB")
                notices.send(if (going.isEmpty()) StorageNotice.InUseNow else StorageNotice.Removed(going.size, freed))
                removals.value++
            }
        }
    }

    /** Removes [model] alone, unless something running may hold it now. */
    fun remove(model: ModelOnPhone) {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                if (ModelCleanup.refusal(model.ref, reader.activity(downloads.value), reader.facts()) != null) {
                    notices.send(StorageNotice.InUseNow)
                    return@withContext
                }
                val change = remove(model.ref)
                Log.i(TAG, "Model removed alone: ${model.bytes / BYTES_PER_MB} MB")
                notices.send(StorageNotice.Removed(1, model.bytes, change))
                removals.value++
            }
        }
    }

    private suspend fun remove(ref: ModelRef): SettingChange? = when (ref) {
        is ModelRef.Speech -> removal.removeSpeech(ref.id)
        is ModelRef.Ai -> removal.removeAi(ref.variant)
    }

    companion object {
        private const val TAG = "SatoriStorage"
        private const val BYTES_PER_MB = 1_000_000L

        /** How long the flows keep running after the screen is gone, to survive a fold. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Each model with what uses it and whether it can go now. */
        fun storedModels(models: List<ModelOnPhone>, facts: ModelSettingsFacts, activity: ModelActivity): List<StoredModel> =
            models.map { StoredModel(it, ModelCleanup.usesOf(it.ref, facts), ModelCleanup.refusal(it.ref, activity, facts)) }

        /**
         * [dir] or its nearest folder that exists, whose volume's free space the screen shows: a
         * fresh install has no model folder yet.
         */
        internal fun nearestFolder(dir: File): File? = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }
    }
}
