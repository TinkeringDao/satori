package com.satori.presentation.di

import android.util.Log
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.domain.usecase.todo.TodoChangeLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The field log's line for each change of a to-do's status, under SatoriTodos
 * ([TodoChangeLog.line]): the to-do's id, the statuses and where the change came from, never its
 * text. Under the same tag, the found items a search for to-dos dropped ([FoundTodosLog.line]):
 * how many, and why; and since 0.8.0 how many of those it kept of lines read into English the gate
 * of Malayalam lines would have dropped ([FoundTodosLog.ungatedLine]).
 */
@Module
@InstallIn(SingletonComponent::class)
object TodoLogModule {

    private const val TAG = "SatoriTodos"

    @Provides
    fun provideTodoChangeLog(): TodoChangeLog = TodoChangeLog { id, from, to, source ->
        Log.i(TAG, TodoChangeLog.line(id, from, to, source))
    }

    @Provides
    fun provideFoundTodosLog(): FoundTodosLog = object : FoundTodosLog {
        override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) {
            Log.i(TAG, FoundTodosLog.line(sessionId, count, why))
        }

        override fun ungated(sessionId: String, kept: Int, wouldDrop: Int) {
            Log.i(TAG, FoundTodosLog.ungatedLine(sessionId, kept, wouldDrop))
        }
    }
}
