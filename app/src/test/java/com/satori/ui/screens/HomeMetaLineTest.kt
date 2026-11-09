package com.satori.ui.screens

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.FinishingStatus.Phase
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.finish.FinishStep
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line under a recording's title on home: "<date> · <step>" while it is being finished. */
class HomeMetaLineTest {

    private val named = RecordingTitle(name = "Weekly sync", categoryName = "Meeting", mode = RecordingMode.SHORT_MEETING)
    private val byCategory = RecordingTitle(name = null, categoryName = "Meeting", mode = RecordingMode.SHORT_MEETING)
    private val unnamed = RecordingTitle(name = null, categoryName = null, mode = RecordingMode.SIMPLE_LISTENING)

    private fun running(step: FinishStep?, transcript: TranscriptProgress? = null) =
        FinishingStatus("s1", Phase.RUNNING, step = step, transcript = transcript)

    @Test
    fun `a recording being finished says its step after the date, in place of its category`() {
        val model = SpeechModelId.COHERE_TRANSCRIBE
        val words = listOf(
            FinishingStatus("s1", Phase.WAITING, position = 2) to StatusRes(R.string.g_finish_waiting),
            FinishingStatus("s1", Phase.WAITS_FOR_RECORDING) to StatusRes(R.string.g_finish_waits_recording),
            running(step = null) to StatusRes(R.string.g_rec_finishing),
            running(FinishStep.Transcript) to StatusRes(R.string.g_speech_finishing_transcript),
            running(FinishStep.Transcript, TranscriptProgress(model, fraction = 0.5f)) to
                StatusRes(R.string.g_speech_finishing_transcript_percent, percent = 50),
            running(FinishStep.Transcript, TranscriptProgress(model, fraction = 0.5f, skipping = true)) to
                StatusRes(R.string.g_speech_finishing_skipping),
            running(FinishStep.Lines) to StatusRes(R.string.g_rec_finishing),
            running(FinishStep.Notes) to StatusRes(R.string.g_rec_notes_writing),
            running(FinishStep.Name) to StatusRes(R.string.g_rec_naming),
            running(FinishStep.Todos) to StatusRes(R.string.g_rec_todos),
            running(FinishStep.Map) to StatusRes(R.string.g_rec_mapping)
        )

        for ((status, said) in words) {
            for (title in listOf(named, byCategory, unnamed)) {
                val detail = metaDetail(title, status)
                assertEquals(MetaDetail.Finishing(status), detail)
                assertEquals(said, finishingStatusRes((detail as MetaDetail.Finishing).status))
            }
        }
    }

    @Test
    fun `a recording with too little text for notes says so after the date, from then on until it is finished`() {
        for (step in listOf(null, FinishStep.Lines, FinishStep.Name, FinishStep.Todos, FinishStep.Map)) {
            for (title in listOf(named, byCategory, unnamed)) {
                assertEquals(MetaDetail.NoNotes, metaDetail(title, running(step).copy(noNotes = true)))
            }
        }
        assertEquals(MetaDetail.Category("Meeting"), metaDetail(named, finishing = null))
    }

    @Test
    fun `once it is finished, a named recording says its category again and the others only the date`() {
        assertEquals(MetaDetail.Category("Meeting"), metaDetail(named, finishing = null))
        // Called by its category already.
        assertNull(metaDetail(byCategory, finishing = null))
        assertNull(metaDetail(unnamed, finishing = null))
    }
}
