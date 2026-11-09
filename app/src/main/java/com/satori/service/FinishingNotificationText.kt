package com.satori.service

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R
import com.satori.ui.screens.finishingStatusRes

/**
 * What the AI's notification says while recordings are being finished in the background
 * (LlmProcessingServiceController.showFinishing): how many, as its title ([title] for [count]),
 * and the step under way as its text ([stepText]), in the words home and the recording's page use
 * for that step (finishingStatusRes). Between steps it has no text. It never holds a name or any
 * of what was said: the notification also reaches the lock screen and the cover screen.
 *
 * @property count How many recordings wait or are being finished, at least 1
 * @property stepText The words of the step under way; null between steps
 */
internal data class FinishingNotificationText(val count: Int, @StringRes val stepText: Int?) {

    /** The title, a plurals resource for [count]: "Finishing a recording", "Finishing 2 recordings". */
    @get:PluralsRes
    val title: Int
        get() = R.plurals.g_notif_finishing

    companion object {

        /**
         * The text for [recordings] being finished, the one under way at [step].
         *
         * @return null when no recording is being finished: other AI work alone keeps the
         *   service's generic text
         */
        fun of(recordings: Int, step: FinishStep?): FinishingNotificationText? =
            if (recordings <= 0) null else FinishingNotificationText(recordings, step?.let(::stepWords))

        /** The words of [step], those of a recording at that step (its id plays no part in them). */
        @StringRes
        private fun stepWords(step: FinishStep): Int =
            finishingStatusRes(FinishingStatus(sessionId = "", phase = FinishingStatus.Phase.RUNNING, step = step)).id
    }
}
