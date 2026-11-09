package com.satori.presentation.di

import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.todo.TakenAwayTodos
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoChangeLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/** Use cases of the home screen's to-do list. */
@Module
@InstallIn(ViewModelComponent::class)
object HomeUseCaseModule {

    @Provides
    @ViewModelScoped
    fun provideTodoActions(
        todoRepository: TodoRepository,
        reminderScheduler: ReminderScheduler,
        changeLog: TodoChangeLog,
        takenAway: TakenAwayTodos
    ): TodoActions {
        return TodoActions(todoRepository, reminderScheduler, log = changeLog, takenAway = takenAway)
    }
}
