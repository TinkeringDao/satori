package com.satori.di

import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.service.RecordingServiceController
import com.satori.domain.time.RuleBasedTimeParser
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.stt.StopSttStreamingUseCase
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.service.RecordingServiceControllerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing domain layer use cases and services.
 * Changed to SingletonComponent to support service injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    /** With the field log of what its notes' answers lose (NotesLogModule), as after Stop. */
    @Provides
    fun provideSyncSttLlmUseCase(
        llmRepository: LlmRepository,
        settingsRepository: SettingsRepository,
        resourceProvider: ResourceProvider,
        echoLog: PromptEchoLog,
        notesLog: NotesLog
    ): SyncSttLlmUseCase {
        return SyncSttLlmUseCase(llmRepository, settingsRepository, resourceProvider, echoLog, notesLog)
    }

    @Provides
    fun provideStopSttStreamingUseCase(
        sttRepository: SttRepository
    ): StopSttStreamingUseCase {
        return StopSttStreamingUseCase(sttRepository)
    }

    @Provides
    fun provideInitializeLlmUseCase(
        llmRepository: LlmRepository,
        settingsRepository: SettingsRepository
    ): InitializeLlmUseCase {
        return InitializeLlmUseCase(llmRepository, settingsRepository)
    }

    /** The rule-based English and German parser; it keeps no state, so one is shared. */
    @Provides
    @Singleton
    fun provideTimeExpressionParser(): TimeExpressionParser {
        return RuleBasedTimeParser()
    }

    @Provides
    @Singleton
    fun provideRecordingServiceController(
        impl: RecordingServiceControllerImpl
    ): RecordingServiceController {
        return impl
    }
}
