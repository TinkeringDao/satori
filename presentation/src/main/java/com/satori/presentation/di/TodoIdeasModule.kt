package com.satori.presentation.di

import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.todo.MoveFoundIdeasUseCase
import com.satori.domain.usecase.todo.TodoChangeLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What MainActivity runs once at start for the ideas: the one-time move of found ideas. */
@Module
@InstallIn(SingletonComponent::class)
object TodoIdeasModule {

    @Provides
    fun provideMoveFoundIdeasUseCase(
        todoRepository: TodoRepository,
        transcriptionRepository: TranscriptionRepository,
        reminderScheduler: ReminderScheduler,
        changeLog: TodoChangeLog
    ): MoveFoundIdeasUseCase {
        return MoveFoundIdeasUseCase(todoRepository, transcriptionRepository, reminderScheduler, changeLog)
    }
}
