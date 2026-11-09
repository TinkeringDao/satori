package com.satori.data.firstrun

import com.satori.data.service.AndroidDownloadManager
import com.satori.data.service.DownloadServiceRules
import com.satori.data.service.DownloadStateManager
import com.satori.domain.firstrun.FirstRunDownloads
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run's downloads ([FirstRunDownloads]) as [AndroidDownloadManager] runs them, in the
 * one speech and the one AI model download it shares with Settings, and the reader's own (0.8.0).
 */
@Singleton
class FirstRunModelDownloads @Inject constructor(
    private val manager: AndroidDownloadManager,
    private val states: DownloadStateManager,
    private val models: FirstRunModels
) : FirstRunDownloads {

    override val changes: Flow<Unit> = combine(
        states.sttDownloadState, states.sttModel, states.llmDownloadState, states.llmVariant,
        combine(states.readerDownloadState, states.readerModel) { _, _ -> }
    ) { _, _, _, _, _ -> }

    override fun state(part: FirstRunPart): DownloadState {
        models.speechModel(part)?.let { model ->
            return if (states.sttModel.value == model) states.sttDownloadState.value else DownloadState.Idle
        }
        models.readerModel(part)?.let { model ->
            return if (states.readerModel.value == model) states.readerDownloadState.value else DownloadState.Idle
        }
        val variant = models.aiModel(part)
        return if (variant != null && states.llmVariant.value == variant) states.llmDownloadState.value else DownloadState.Idle
    }

    /**
     * Another model's download runs, or waits for a recording, where [part]'s would. The first
     * run's queue stops its own part before it moves to the next, so this is a download another
     * screen started: Settings' Gemma 4 E2B as much as its Parakeet v2.
     */
    override fun busyWithOther(part: FirstRunPart): Boolean {
        val speech = models.speechModel(part)
        val reader = models.readerModel(part)
        val (state, aboutOther) = when {
            speech != null -> states.sttDownloadState.value to states.sttModel.value.let { it != null && it != speech }
            reader != null -> states.readerDownloadState.value to states.readerModel.value.let { it != null && it != reader }
            else -> states.llmDownloadState.value to states.llmVariant.value.let { it != null && it != models.aiModel(part) }
        }
        return aboutOther && (DownloadServiceRules.isRunning(state) || (state as? DownloadState.Paused)?.reason == PauseReason.RECORDING)
    }

    override fun start(part: FirstRunPart) {
        models.speechModel(part)?.let { return manager.startSttDownload(it) }
        models.readerModel(part)?.let { return manager.startReaderDownload(it) }
        manager.resumeLlmDownload(checkNotNull(models.aiModel(part)))
    }

    override fun stop(part: FirstRunPart) {
        models.speechModel(part)?.let { model ->
            if (states.sttModel.value == model) manager.cancelSttDownload()
            return
        }
        models.readerModel(part)?.let { model ->
            if (states.readerModel.value == model) manager.cancelReaderDownload()
            return
        }
        if (states.llmVariant.value == models.aiModel(part)) manager.cancelLlmDownload()
    }

    override fun hold(part: FirstRunPart, reason: PauseReason, progress: DownloadProgress?) {
        models.speechModel(part)?.let { return manager.holdSttDownload(it, reason, progress) }
        models.readerModel(part)?.let { return manager.holdReaderDownload(it, reason, progress) }
        manager.holdLlmDownload(checkNotNull(models.aiModel(part)), reason, progress)
    }

    override fun holdForRecording(busy: Boolean) = manager.holdForRecording(busy)

    override fun keepInForeground() = manager.keepInForeground()
}
