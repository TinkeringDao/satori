package com.satori.service

import androidx.annotation.StringRes
import com.satori.data.service.DownloadServiceRules
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.PauseReason
import com.satori.domain.model.SpeechModelId
import com.satori.ui.R

/**
 * What the download service's notification says (ModelDownloadService): what downloads, in the
 * words the first run and Settings use for it ([title]), and how it goes ([text]): the percent,
 * the files being checked, or why it is held. One download at a time is shown, the one that runs
 * before one that is held, a speech model's before the AI model's, and the AI model's before the
 * reader's (0.8.0), which goes by the AI model's name, as on home's status strip: it serves the AI
 * notes alone.
 *
 * @property percent The bar's percent; null for a bar that runs without one, while a file is
 *   checked or the size isn't known yet
 * @property args What [text] is formatted with
 */
internal data class DownloadNotificationText(
    @StringRes val title: Int,
    @StringRes val text: Int,
    val percent: Int?,
    val args: List<Any> = emptyList()
) {
    companion object {

        /** What the service shows for the moment it takes to read the downloads' states. */
        val STARTING = DownloadNotificationText(R.string.g_dl_channel, R.string.g_dl_percent, percent = null, args = listOf(0))

        /**
         * The notification for the speech download [speech] of [speechModel], the AI model
         * download [ai] of [aiModel] and the reader's download [reader]; null when none keeps the
         * service ([DownloadServiceRules.keepsServiceRunning]).
         */
        fun of(
            speech: DownloadState,
            speechModel: SpeechModelId?,
            ai: DownloadState,
            aiModel: LlmModelVariant?,
            reader: DownloadState = DownloadState.Idle
        ): DownloadNotificationText? {
            val shown = listOf(speech to speechTitle(speechModel), ai to aiTitle(aiModel), reader to R.string.g_dl_ai_model)
                .filter { (state, _) -> DownloadServiceRules.keepsServiceRunning(state) }
                .minByOrNull { (state, _) -> if (DownloadServiceRules.isRunning(state)) 0 else 1 }
                ?: return null
            val (state, title) = shown
            return when (state) {
                is DownloadState.Downloading -> {
                    val percent = state.progress.percentage.coerceIn(0, 100)
                    DownloadNotificationText(title, R.string.g_dl_percent, percent.takeIf { state.progress.totalBytes > 0L }, listOf(percent))
                }
                is DownloadState.Verifying -> DownloadNotificationText(title, R.string.g_dl_checking, percent = null)
                is DownloadState.Paused -> heldText(state.reason)?.let { DownloadNotificationText(title, it, state.progress?.percentage?.coerceIn(0, 100)) }
                DownloadState.Idle, DownloadState.Completed, is DownloadState.Error -> null
            }
        }

        @StringRes
        private fun speechTitle(model: SpeechModelId?): Int = when (model) {
            SpeechModelId.INDICCONFORMER_ML -> R.string.g_dl_malayalam
            SpeechModelId.PARAKEET_V2_EN, SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_dl_after_stop
            SpeechModelId.PARAKEET_V3, null -> R.string.g_dl_speech
        }

        @StringRes
        private fun aiTitle(variant: LlmModelVariant?): Int =
            if (variant?.writesLiveNotes == false) R.string.g_dl_bigger_model else R.string.g_dl_ai_model

        /** Why a held download waits; none for those that wait for a tap, which keep no service. */
        @StringRes
        private fun heldText(reason: PauseReason): Int? = when (reason) {
            PauseReason.RECORDING -> R.string.g_dl_paused_recording
            PauseReason.NO_NETWORK -> R.string.g_dl_no_connection
            PauseReason.WIFI_ONLY -> R.string.g_dl_waiting_wifi
            PauseReason.SERVER -> R.string.g_dl_server
            PauseReason.NOT_ENOUGH_STORAGE -> R.string.g_dl_space
            PauseReason.USER, PauseReason.BLOCKED -> null
        }
    }
}
