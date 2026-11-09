package com.satori.presentation.mindmap

import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase

/**
 * What the screen says when a piece of the map's AI work ends: an error that stays until it is
 * dismissed, or a notice that goes by itself. Kept apart from the ViewModel so each way a run can
 * end is unit-tested.
 */
internal object MindMapOutcomes {

    /** At most one of the two is set. */
    data class Report(val error: MindMapError? = null, val notice: MindMapNotice? = null)

    /**
     * A run of the older recordings: how many were mapped and how many gave no topic, that another
     * run was going on, or that the model stopped and after how many.
     */
    fun older(outcome: MapOlderRecordingsUseCase.Outcome): Report = when (outcome) {
        is MapOlderRecordingsUseCase.Outcome.Finished -> Report(notice = MindMapNotice.OlderDone(outcome.mapped, outcome.withoutTopics))
        MapOlderRecordingsUseCase.Outcome.AlreadyRunning -> Report(notice = MindMapNotice.AlreadyRunning)
        is MapOlderRecordingsUseCase.Outcome.ModelFailed -> Report(error = MindMapError.OlderStopped(outcome.mapped))
    }

    /**
     * The Update of one theme's summary: nothing to say when it was written (the page shows it),
     * else that the answer couldn't be read or the model couldn't run.
     */
    fun summary(outcome: RefreshThemeSummariesUseCase.Outcome): Report = when (outcome) {
        is RefreshThemeSummariesUseCase.Outcome.Written ->
            if (outcome.count > 0) Report() else Report(error = MindMapError.SummaryUnreadable)
        is RefreshThemeSummariesUseCase.Outcome.ModelFailed -> Report(error = MindMapError.ModelFailed)
    }

    /** Mapping one recording: to how many themes, that no topic held up, or why it didn't map. */
    fun recording(outcome: MapRecordingUseCase.Outcome): Report = when (outcome) {
        MapRecordingUseCase.Outcome.Skipped -> Report(error = MindMapError.TooShort)
        MapRecordingUseCase.Outcome.Unreadable -> Report(error = MindMapError.MapUnreadable)
        MapRecordingUseCase.Outcome.ModelFailed -> Report(error = MindMapError.ModelFailed)
        MapRecordingUseCase.Outcome.Failed -> Report(error = MindMapError.Save)
        is MapRecordingUseCase.Outcome.Mapped -> Report(
            notice = if (outcome.themeIds.isEmpty()) {
                MindMapNotice.NothingFound
            } else {
                MindMapNotice.RecordingMapped(outcome.themeIds.size)
            }
        )
    }
}
