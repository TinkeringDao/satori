package com.satori.di

import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.category.EnsureStarterCategoriesUseCase
import com.satori.domain.usecase.finish.DeleteSilentRecordingsUseCase
import com.satori.domain.usecase.todo.RecheckFoundTodosUseCase
import com.satori.domain.usecase.todo.TodoChangeLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What MainActivity runs at start: the starter categories, and once the to-do recheck and the
 * deletion of the recordings in which nothing was said.
 */
@Module
@InstallIn(SingletonComponent::class)
object StarterCategoriesModule {

    @Provides
    fun provideEnsureStarterCategoriesUseCase(
        categoryRepository: CategoryRepository
    ): EnsureStarterCategoriesUseCase {
        return EnsureStarterCategoriesUseCase(categoryRepository)
    }

    @Provides
    fun provideRecheckFoundTodosUseCase(
        todoRepository: TodoRepository,
        transcriptionRepository: TranscriptionRepository,
        reminderScheduler: ReminderScheduler,
        changeLog: TodoChangeLog
    ): RecheckFoundTodosUseCase {
        return RecheckFoundTodosUseCase(todoRepository, transcriptionRepository, reminderScheduler, changeLog)
    }

    @Provides
    fun provideDeleteSilentRecordingsUseCase(
        transcriptionRepository: TranscriptionRepository,
        todoRepository: TodoRepository,
        audioRepository: RecordingAudioRepository
    ): DeleteSilentRecordingsUseCase {
        return DeleteSilentRecordingsUseCase(transcriptionRepository, todoRepository, audioRepository)
    }
}
