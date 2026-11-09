package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R

/**
 * A status line as a string resource, and the percent it puts in for a better transcript that is
 * reading: what a status says, without a Context.
 */
data class StatusRes(@StringRes val id: Int, val percent: Int? = null)

/**
 * What a recording being finished in the background says, the same on home, on its page and in
 * the notification: that it waits, that it waits for the recording being made to end or for the
 * AI model (also when it is the reader it waits for: no words of its own), or the step under way,
 * in the words the recorder used for it. Joining lines, the reader's reading of the lines, and the
 * moment before the first step, read as finishing up.
 */
fun finishingStatusRes(status: FinishingStatus): StatusRes = when (status.phase) {
    FinishingStatus.Phase.WAITING -> StatusRes(R.string.g_finish_waiting)
    FinishingStatus.Phase.WAITS_FOR_RECORDING -> StatusRes(R.string.g_finish_waits_recording)
    FinishingStatus.Phase.WAITS_FOR_MODEL -> StatusRes(R.string.g_finish_waits_model)
    FinishingStatus.Phase.RUNNING -> when (status.step) {
        FinishStep.Transcript -> transcriptStatusRes(status.transcript)
        FinishStep.Lines, FinishStep.Reading, null -> StatusRes(R.string.g_rec_finishing)
        FinishStep.Notes -> StatusRes(R.string.g_rec_notes_writing)
        FinishStep.Name -> StatusRes(R.string.g_rec_naming)
        FinishStep.Todos -> StatusRes(R.string.g_rec_todos)
        FinishStep.Map -> StatusRes(R.string.g_rec_mapping)
    }
}

/**
 * The better transcript: how much of the audio it has read once it reads (the model loads first,
 * for seconds), or that it stops after a Skip.
 */
private fun transcriptStatusRes(progress: TranscriptProgress?): StatusRes = when {
    progress?.skipping == true -> StatusRes(R.string.g_speech_finishing_skipping)
    progress != null && progress.fraction > 0f ->
        StatusRes(R.string.g_speech_finishing_transcript_percent, percent = (progress.fraction * 100).toInt())
    else -> StatusRes(R.string.g_speech_finishing_transcript)
}

/** [finishingStatusRes] in words. */
@Composable
fun finishingStatusText(status: FinishingStatus): String {
    val res = finishingStatusRes(status)
    return if (res.percent != null) stringResource(res.id, res.percent) else stringResource(res.id)
}
