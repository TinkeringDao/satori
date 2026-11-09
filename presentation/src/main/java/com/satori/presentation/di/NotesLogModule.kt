package com.satori.presentation.di

import android.util.Log
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.mindmap.MindMapLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The field log's lines of a recording's notes and name, under SatoriNotes: the pieces of the
 * model's answers left out as echoes of their prompt's instructions ([PromptEchoLog.line]), and
 * those left out for other reasons, the second tries and where the name came from
 * ([NotesLog.line]); since 0.7.3 also what the mind map's answers lost ([MindMapLog.line]).
 * Counts, ids and exception classes, never a text.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotesLogModule {

    private const val TAG = "SatoriNotes"

    @Provides
    fun providePromptEchoLog(): PromptEchoLog = PromptEchoLog { sessionId, count, what ->
        Log.i(TAG, PromptEchoLog.line(sessionId, count, what))
    }

    @Provides
    fun provideNotesLog(): NotesLog = NotesLog { event -> Log.i(TAG, NotesLog.line(event)) }

    @Provides
    fun provideMindMapLog(): MindMapLog = MindMapLog { id, count, what, why -> Log.i(TAG, MindMapLog.line(id, count, what, why)) }
}
