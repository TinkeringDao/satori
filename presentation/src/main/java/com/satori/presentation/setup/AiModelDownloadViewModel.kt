package com.satori.presentation.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import com.satori.data.device.DeviceTierDetector
import com.satori.data.service.AndroidDownloadManager
import com.satori.data.service.DownloadStateManager
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The standalone AI model screen, "Get the AI model" from Settings and from a recording's notes:
 * the model that suits this phone, downloaded with a tap on a button that shows its size. A
 * download it finishes becomes the model in use (AndroidDownloadManager). The first run's
 * coordinator lets a download started here run, also before the first recording.
 */
@HiltViewModel
class AiModelDownloadViewModel @Inject constructor(
    private val androidDownloadManager: AndroidDownloadManager,
    private val downloadStateManager: DownloadStateManager,
    private val deviceTierDetector: DeviceTierDetector
) : ViewModel() {

    /** The AI model download's state, whichever screen started it. */
    val llmDownloadState: StateFlow<DownloadState> = downloadStateManager.llmDownloadState

    /** The AI model the screen offers: the one that suits this phone. */
    val recommendedLlmVariant: LlmModelVariant by lazy { deviceTierDetector.detectRecommendedVariant() }

    /** Starts the download of the model that suits this phone, or goes on with an unfinished one. */
    fun startLlmDownload() {
        val variant = recommendedLlmVariant
        Log.i(TAG, "Starting LLM download for recommended variant: $variant")
        androidDownloadManager.startLlmDownload(variant)
    }

    /** Tries the failed download again, from what it left on the phone. */
    fun retryLlmDownload() {
        val variant = downloadStateManager.llmVariant.value ?: recommendedLlmVariant
        Log.i(TAG, "Retrying LLM download for variant: $variant")
        androidDownloadManager.resumeLlmDownload(variant)
    }

    private companion object {
        const val TAG = "AiModelDownload"
    }
}
