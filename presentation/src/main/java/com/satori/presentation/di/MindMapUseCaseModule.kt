package com.satori.presentation.di

import com.satori.domain.repository.MindMapRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/**
 * The map screen's "Map N older recordings". MapRecordingUseCase and RefreshThemeSummariesUseCase,
 * which it runs, are in FinishingModule, since the background finishing runs them too. Scoped to
 * the ViewModel like the other use cases; MapOlderRecordingsUseCase keeps its once-at-a-time
 * guard in its class, so a second ViewModel can't start a second run.
 */
@Module
@InstallIn(ViewModelComponent::class)
object MindMapUseCaseModule {

    @Provides
    @ViewModelScoped
    fun provideMapOlderRecordingsUseCase(
        mindMapRepository: MindMapRepository,
        mapRecordingUseCase: MapRecordingUseCase,
        refreshThemeSummariesUseCase: RefreshThemeSummariesUseCase,
        llmProcessingServiceController: LlmProcessingServiceController
    ): MapOlderRecordingsUseCase {
        return MapOlderRecordingsUseCase(
            mindMapRepository,
            mapRecordingUseCase,
            refreshThemeSummariesUseCase,
            llmProcessingServiceController
        )
    }
}
