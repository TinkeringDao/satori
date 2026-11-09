package com.satori.presentation.mindmap

import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapOutcomesTest {

    private fun error(error: MindMapError) = MindMapOutcomes.Report(error = error)
    private fun notice(notice: MindMapNotice) = MindMapOutcomes.Report(notice = notice)

    @Test
    fun `a finished run says how many were mapped and how many had no topics`() {
        assertEquals(
            notice(MindMapNotice.OlderDone(mapped = 9, withoutTopics = 2)),
            MindMapOutcomes.older(MapOlderRecordingsUseCase.Outcome.Finished(mapped = 9, withoutTopics = 2, summaries = 4))
        )
    }

    @Test
    fun `a run that found another going on says so`() {
        assertEquals(
            notice(MindMapNotice.AlreadyRunning),
            MindMapOutcomes.older(MapOlderRecordingsUseCase.Outcome.AlreadyRunning)
        )
    }

    @Test
    fun `a run the model stopped is an error with what was mapped before`() {
        assertEquals(
            error(MindMapError.OlderStopped(3)),
            MindMapOutcomes.older(MapOlderRecordingsUseCase.Outcome.ModelFailed(mapped = 3))
        )
    }

    @Test
    fun `a written summary needs no word, one that couldn't be read does`() {
        assertEquals(MindMapOutcomes.Report(), MindMapOutcomes.summary(RefreshThemeSummariesUseCase.Outcome.Written(1)))
        assertEquals(
            error(MindMapError.SummaryUnreadable),
            MindMapOutcomes.summary(RefreshThemeSummariesUseCase.Outcome.Written(0))
        )
        assertEquals(
            error(MindMapError.ModelFailed),
            MindMapOutcomes.summary(RefreshThemeSummariesUseCase.Outcome.ModelFailed(0))
        )
    }

    @Test
    fun `mapping a recording says to how many themes, or that it found none`() {
        assertEquals(
            notice(MindMapNotice.RecordingMapped(2)),
            MindMapOutcomes.recording(MapRecordingUseCase.Outcome.Mapped(listOf("a", "b")))
        )
        assertEquals(
            notice(MindMapNotice.NothingFound),
            MindMapOutcomes.recording(MapRecordingUseCase.Outcome.Mapped(emptyList()))
        )
    }

    @Test
    fun `a recording that couldn't be mapped says why`() {
        assertEquals(error(MindMapError.TooShort), MindMapOutcomes.recording(MapRecordingUseCase.Outcome.Skipped))
        assertEquals(error(MindMapError.MapUnreadable), MindMapOutcomes.recording(MapRecordingUseCase.Outcome.Unreadable))
        assertEquals(error(MindMapError.ModelFailed), MindMapOutcomes.recording(MapRecordingUseCase.Outcome.ModelFailed))
        assertEquals(error(MindMapError.Save), MindMapOutcomes.recording(MapRecordingUseCase.Outcome.Failed))
    }

    @Test
    fun `AI work starts only with the AI available, no recorder busy and no other view at work`() {
        assertTrue(AiState(available = true).canRun)
        assertFalse(AiState(available = null).canRun)
        assertFalse(AiState(available = false).canRun)
        assertFalse(AiState(available = true, recorderBusy = true).canRun)
        assertFalse(AiState(available = true, otherJob = true).canRun)
    }
}
