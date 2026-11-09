package com.satori.ui.screens

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.FinishingStatus.Phase
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenFinishingStatusTest {

    private val model = SpeechModelId.COHERE_TRANSCRIBE

    private fun running(step: FinishStep?, transcript: TranscriptProgress? = null) =
        FinishingStatus("s1", Phase.RUNNING, step = step, transcript = transcript)

    private fun reading(fraction: Float) = running(FinishStep.Transcript, TranscriptProgress(model, fraction = fraction))

    @Test
    fun `TalkBack says the better transcript's step, not each percent it reads`() {
        assertEquals(StatusRes(R.string.g_speech_finishing_transcript_percent, percent = 42), finishingStatusRes(reading(0.42f)))
        assertEquals(StatusRes(R.string.g_speech_finishing_transcript), finishingStatusRes(reading(0.42f).spoken()))
        // The same at every percent: the live region has nothing new to say until the step changes.
        assertEquals(finishingStatusRes(reading(0.42f).spoken()), finishingStatusRes(reading(0.43f).spoken()))
    }

    @Test
    fun `a Skip, a wait and every other step are said as they are shown`() {
        val skipping = running(FinishStep.Transcript, TranscriptProgress(model, fraction = 0.5f, skipping = true))
        val waiting = FinishingStatus("s1", Phase.WAITING, position = 2)
        val deferred = FinishingStatus("s1", Phase.WAITS_FOR_RECORDING)
        val steps = listOf(null, FinishStep.Lines, FinishStep.Notes, FinishStep.Name, FinishStep.Todos, FinishStep.Map).map { running(it) }

        (listOf(skipping, waiting, deferred) + steps).forEach { status ->
            assertEquals("$status", finishingStatusRes(status), finishingStatusRes(status.spoken()))
        }
    }
}
