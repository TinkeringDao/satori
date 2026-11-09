package com.satori.data.service

import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for sharing download state between DownloadService and ViewModels.
 *
 * Architecture:
 * - AndroidDownloadManager updates the state as download progresses
 * - The first run's coordinator, Settings and the recorder observe it, and the download
 *   service shows it in its notification
 * - Thread-safe via StateFlow
 *
 * Usage:
 * - AndroidDownloadManager: Call updateSttState(), updateLlmState() or updateReaderState() to update progress
 * - ViewModel: Observe sttDownloadState, llmDownloadState or readerDownloadState
 */
@Singleton
class DownloadStateManager @Inject constructor() {

    private val _sttDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val sttDownloadState: StateFlow<DownloadState> = _sttDownloadState.asStateFlow()

    private val _sttModel = MutableStateFlow<SpeechModelId?>(null)

    /**
     * The speech model [sttDownloadState] is about: set when a download starts and kept for its
     * result, null after a reset. One speech model downloads at a time, like one AI model.
     */
    val sttModel: StateFlow<SpeechModelId?> = _sttModel.asStateFlow()

    private val _vocabularyDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /**
     * The download of the vocabulary for words to recognize (117 KB, beside Parakeet v3). Apart
     * from [sttDownloadState], so it never stops or shows on a model's download.
     */
    val vocabularyDownloadState: StateFlow<DownloadState> = _vocabularyDownloadState.asStateFlow()

    private val _llmDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val llmDownloadState: StateFlow<DownloadState> = _llmDownloadState.asStateFlow()

    private val _llmVariant = MutableStateFlow<LlmModelVariant?>(null)

    /**
     * The model [llmDownloadState] is about: set when a download starts and kept for its result,
     * null after a reset. Whoever shows the progress, a failure or a retry button learns here
     * which model it belongs to, whichever screen started the download.
     */
    val llmVariant: StateFlow<LlmModelVariant?> = _llmVariant.asStateFlow()

    private val _readerDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /**
     * The reader's download (0.8.0): the first run's READER part, in a place of its own beside the
     * speech and AI model downloads, so it neither waits for nor stops one of theirs.
     */
    val readerDownloadState: StateFlow<DownloadState> = _readerDownloadState.asStateFlow()

    private val _readerModel = MutableStateFlow<ReaderModelId?>(null)

    /** The reader's build [readerDownloadState] is about: set when a download starts, null after a reset. */
    val readerModel: StateFlow<ReaderModelId?> = _readerModel.asStateFlow()

    /**
     * Update STT download state.
     * Called by DownloadService during STT download.
     */
    fun updateSttState(state: DownloadState) {
        _sttDownloadState.value = state
    }

    /** Names the speech model that the STT download state is about; see [sttModel]. */
    fun setSttModel(model: SpeechModelId) {
        _sttModel.value = model
    }

    /** Update the state of the vocabulary download; see [vocabularyDownloadState]. */
    fun updateVocabularyState(state: DownloadState) {
        _vocabularyDownloadState.value = state
    }

    /**
     * Update LLM download state.
     * Called by DownloadService during LLM download.
     */
    fun updateLlmState(state: DownloadState) {
        _llmDownloadState.value = state
    }

    /** Names the model that the LLM download state is about; see [llmVariant]. */
    fun setLlmVariant(variant: LlmModelVariant) {
        _llmVariant.value = variant
    }

    /** Update the reader's download state; see [readerDownloadState]. */
    fun updateReaderState(state: DownloadState) {
        _readerDownloadState.value = state
    }

    /** Names the reader's build that the reader's download state is about; see [readerModel]. */
    fun setReaderModel(model: ReaderModelId) {
        _readerModel.value = model
    }

    /** The reader's download state back to Idle, about no build. */
    fun resetReaderState() {
        _readerDownloadState.value = DownloadState.Idle
        _readerModel.value = null
    }

    /**
     * Reset STT state to Idle.
     */
    fun resetSttState() {
        _sttDownloadState.value = DownloadState.Idle
        _sttModel.value = null
    }

    /**
     * Reset LLM state to Idle.
     */
    fun resetLlmState() {
        _llmDownloadState.value = DownloadState.Idle
        _llmVariant.value = null
    }

    /**
     * Reset all states to Idle.
     */
    fun resetAll() {
        resetSttState()
        resetLlmState()
        resetReaderState()
    }
}
