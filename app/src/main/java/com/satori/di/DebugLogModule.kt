package com.satori.di

import android.content.Context
import com.satori.diagnostics.TodoAnswerFiles
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.ui.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * What only the debug build keeps for the main session to read: the model's raw answers for the
 * to-dos and ideas, the notes and the name ([TodoAnswerFiles], in files/debug/todo-answers). A
 * release build keeps none.
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugLogModule {

    @Provides
    @Singleton
    fun provideAnswerLog(@ApplicationContext context: Context): AnswerLog =
        if (BuildConfig.DEBUG) TodoAnswerFiles(File(context.filesDir, TodoAnswerFiles.DIR)) else AnswerLog.NONE
}
