package com.satori.presentation.di

import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.domain.usecase.transcription.SearchTranscriptionsUseCase
import com.satori.domain.usecase.transcription.RenameSessionUseCase
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import com.satori.domain.usecase.transcription.GetSessionDetailsUseCase
import com.satori.domain.usecase.transcription.SaveInsightUseCase
import com.satori.domain.usecase.transcription.SaveSegmentUseCase
import com.satori.domain.usecase.transcription.UpdateInsightContentUseCase
import com.satori.domain.usecase.transcription.UpdateSegmentTextUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/**
 * Hilt module providing use case dependencies.
 *
 * Use cases are scoped to ViewModel lifecycle to ensure they're recreated
 * with each ViewModel instance. The ones the background finishing of recordings runs are in
 * FinishingModule (SingletonComponent), where ViewModels get them too.
 */
@Module
@InstallIn(ViewModelComponent::class)
object UseCaseModule {

    @Provides
    @ViewModelScoped
    fun provideCreateSessionUseCase(
        repository: TranscriptionRepository
    ): CreateSessionUseCase {
        return CreateSessionUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideGetAllSessionsUseCase(
        repository: TranscriptionRepository
    ): GetAllSessionsUseCase {
        return GetAllSessionsUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideGetSessionDetailsUseCase(
        repository: TranscriptionRepository
    ): GetSessionDetailsUseCase {
        return GetSessionDetailsUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideSaveSegmentUseCase(
        repository: TranscriptionRepository
    ): SaveSegmentUseCase {
        return SaveSegmentUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideSaveInsightUseCase(
        repository: TranscriptionRepository
    ): SaveInsightUseCase {
        return SaveInsightUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideRenameSessionUseCase(
        repository: TranscriptionRepository
    ): RenameSessionUseCase {
        return RenameSessionUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideUpdateSegmentTextUseCase(
        repository: TranscriptionRepository
    ): UpdateSegmentTextUseCase {
        return UpdateSegmentTextUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideUpdateInsightContentUseCase(
        repository: TranscriptionRepository
    ): UpdateInsightContentUseCase {
        return UpdateInsightContentUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideSearchTranscriptionsUseCase(
        repository: TranscriptionRepository
    ): SearchTranscriptionsUseCase {
        return SearchTranscriptionsUseCase(repository)
    }

    @Provides
    @ViewModelScoped
    fun provideRewriteNotesUseCase(
        transcriptionRepository: TranscriptionRepository,
        settingsRepository: SettingsRepository,
        generateBatchInsightUseCase: GenerateBatchInsightUseCase,
        loadFinishingModelUseCase: LoadFinishingModelUseCase
    ): RewriteNotesUseCase {
        return RewriteNotesUseCase(
            transcriptionRepository,
            settingsRepository,
            generateBatchInsightUseCase,
            loadFinishingModelUseCase
        )
    }

    /** "Record as" while recording and "Change type" on the recording's page. */
    @Provides
    @ViewModelScoped
    fun provideChangeRecordingTypeUseCase(
        transcriptionRepository: TranscriptionRepository,
        categoryRepository: CategoryRepository
    ): ChangeRecordingTypeUseCase {
        return ChangeRecordingTypeUseCase(transcriptionRepository, categoryRepository)
    }

    /** The language row of "Record as" while recording and of "Change type" on the recording's page. */
    @Provides
    @ViewModelScoped
    fun provideChangeRecordingLanguageUseCase(
        transcriptionRepository: TranscriptionRepository,
        speechModelRepository: SpeechModelRepository
    ): ChangeRecordingLanguageUseCase {
        return ChangeRecordingLanguageUseCase(transcriptionRepository, speechModelRepository)
    }
}
