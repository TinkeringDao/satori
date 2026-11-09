package com.satori.ui.screens

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.FinishingStatus.Phase
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishingStatusTextTest {

    private fun running(step: FinishStep?, transcript: TranscriptProgress? = null) =
        FinishingStatus("s1", Phase.RUNNING, step = step, transcript = transcript)

    @Test
    fun `a recording in the queue says it waits, one deferred that it waits for the recording, one held for the AI model that it waits for that`() {
        assertEquals(StatusRes(R.string.g_finish_waiting), finishingStatusRes(FinishingStatus("s1", Phase.WAITING, position = 2)))
        assertEquals(StatusRes(R.string.g_finish_waits_recording), finishingStatusRes(FinishingStatus("s1", Phase.WAITS_FOR_RECORDING)))
        assertEquals(StatusRes(R.string.g_finish_waits_model), finishingStatusRes(FinishingStatus("s1", Phase.WAITS_FOR_MODEL)))
    }

    @Test
    fun `each step says what the recorder said for it`() {
        assertEquals(StatusRes(R.string.g_rec_finishing), finishingStatusRes(running(FinishStep.Lines)))
        assertEquals(StatusRes(R.string.g_rec_notes_writing), finishingStatusRes(running(FinishStep.Notes)))
        assertEquals(StatusRes(R.string.g_rec_naming), finishingStatusRes(running(FinishStep.Name)))
        assertEquals(StatusRes(R.string.g_rec_todos), finishingStatusRes(running(FinishStep.Todos)))
        assertEquals(StatusRes(R.string.g_rec_mapping), finishingStatusRes(running(FinishStep.Map)))
    }

    @Test
    fun `a job before its first step is finishing up`() {
        assertEquals(StatusRes(R.string.g_rec_finishing), finishingStatusRes(running(step = null)))
    }

    @Test
    fun `the better transcript says how far it read once it reads, and that it stops after a Skip`() {
        val model = SpeechModelId.COHERE_TRANSCRIBE

        assertEquals(StatusRes(R.string.g_speech_finishing_transcript), finishingStatusRes(running(FinishStep.Transcript)))
        assertEquals(
            StatusRes(R.string.g_speech_finishing_transcript),
            finishingStatusRes(running(FinishStep.Transcript, TranscriptProgress(model)))
        )
        assertEquals(
            StatusRes(R.string.g_speech_finishing_transcript_percent, percent = 42),
            finishingStatusRes(running(FinishStep.Transcript, TranscriptProgress(model, fraction = 0.425f)))
        )
        assertEquals(
            StatusRes(R.string.g_speech_finishing_skipping),
            finishingStatusRes(running(FinishStep.Transcript, TranscriptProgress(model, fraction = 0.5f, skipping = true)))
        )
    }
}
