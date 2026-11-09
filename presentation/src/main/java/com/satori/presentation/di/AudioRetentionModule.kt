package com.satori.presentation.di

import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.repository.AudioUsage
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase
import com.satori.domain.usecase.transcription.DeleteSessionUseCase
import com.satori.presentation.main.ActiveRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * The compressing and deleting of older audio ([ApplyAudioRetentionUseCase]). In SingletonComponent,
 * because AudioRetentionJob runs it as well as the Settings screen.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioRetentionModule {

    /**
     * Audio counts as in use while it is read (played, transcribed again), while the open recorder
     * has its recording, and while the recording has a job in the finishing queue. The jobs are read
     * as stored rather than from the queue's statuses: the job runs in a process Android may have
     * started for it alone, where the queue may not have read them yet, and a capture cut short
     * (its job still capturing) is among them too.
     *
     * A recording deleted whole goes as the recording's page deletes one ([DeleteSessionUseCase]):
     * a job queued for it since the in-use check is taken out of the finishing queue first, so no
     * step stores its notes, to-dos or links after the delete.
     */
    @Provides
    fun provideApplyAudioRetentionUseCase(
        audioRepository: RecordingAudioRepository,
        transcriptionRepository: TranscriptionRepository,
        todoRepository: TodoRepository,
        settingsRepository: SettingsRepository,
        audioUsage: AudioUsage,
        activeRecorder: ActiveRecorder,
        finishingJobs: FinishingJobRepository,
        deleteSession: DeleteSessionUseCase
    ): ApplyAudioRetentionUseCase = ApplyAudioRetentionUseCase(
        audioRepository = audioRepository,
        transcriptionRepository = transcriptionRepository,
        todoRepository = todoRepository,
        settingsRepository = settingsRepository,
        inUse = {
            audioUsage.inUse() +
                listOfNotNull(activeRecorder.current.value?.sessionId) +
                finishingJobs.observeAll().first().map { it.sessionId }
        },
        deleteRecording = deleteSession::invoke
    )
}
