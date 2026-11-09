package com.satori.di

import com.satori.domain.finish.FinishingWake
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.service.ReminderScheduler
import com.satori.service.FinishingWakeScheduler
import com.satori.service.LlmProcessingServiceControllerImpl
import com.satori.service.reminders.AndroidReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding service-layer domain interfaces to their Android implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindLlmProcessingServiceController(
        impl: LlmProcessingServiceControllerImpl
    ): LlmProcessingServiceController

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(
        impl: AndroidReminderScheduler
    ): ReminderScheduler

    @Binds
    @Singleton
    abstract fun bindFinishingWake(
        impl: FinishingWakeScheduler
    ): FinishingWake
}
