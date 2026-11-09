package com.satori.presentation.di

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.stt.MakeTextAgainUseCase
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/**
 * What a recording's page uses after Transcribe again puts in a transcript in another language:
 * making its name, notes, to-dos and ideas again from the new one, each in place of what the old
 * transcript gave it once it is ready. With the page's Rewrite notes and the to-do actions it reuses.
 */
@Module
@InstallIn(ViewModelComponent::class)
object TextAgainModule {

    @Provides
    @ViewModelScoped
    fun provideMakeTextAgainUseCase(
        transcriptionRepository: TranscriptionRepository,
        rewriteNotesUseCase: RewriteNotesUseCase,
        autoNameSessionUseCase: AutoNameSessionUseCase,
        extractSessionTodosUseCase: ExtractSessionTodosUseCase,
        loadFinishingModelUseCase: LoadFinishingModelUseCase,
        notesLanguageResolver: NotesLanguageResolver,
        todoRepository: TodoRepository,
        todoActions: TodoActions
    ): MakeTextAgainUseCase {
        return MakeTextAgainUseCase(
            transcriptionRepository,
            rewriteNotesUseCase,
            autoNameSessionUseCase,
            extractSessionTodosUseCase,
            loadFinishingModelUseCase,
            notesLanguageResolver,
            todoRepository,
            todoActions
        )
    }
}
