package com.satori.presentation.di

import android.util.Log
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.TranscriptLinesStep
import com.satori.domain.usecase.transcription.JoinTranscriptLinesUseCase
import com.satori.domain.usecase.transcription.LinesLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The joining of transcript lines after Stop ([TranscriptLinesStep]) and where it logs. In
 * SingletonComponent, because the finishing of recordings, which runs app-wide, needs it.
 */
@Module
@InstallIn(SingletonComponent::class)
object LinesModule {

    private const val TAG = "SatoriLines"

    /**
     * The line counts of each join go to logcat, and from there to the field log: counts and
     * recording ids only.
     */
    @Provides
    fun provideLinesLog(): LinesLog = LinesLog { line, failure ->
        if (failure == null) Log.i(TAG, line) else Log.w(TAG, line, failure)
    }

    /** Joins the lines the speech recognizer cut mid-sentence ([JoinTranscriptLinesUseCase]). */
    @Provides
    fun provideTranscriptLinesStep(
        transcriptionRepository: TranscriptionRepository,
        todoRepository: TodoRepository,
        audioRepository: RecordingAudioRepository,
        settingsRepository: SettingsRepository,
        linesLog: LinesLog
    ): TranscriptLinesStep =
        JoinTranscriptLinesUseCase(transcriptionRepository, todoRepository, audioRepository, settingsRepository, linesLog)
}
