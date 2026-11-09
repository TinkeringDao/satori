package com.satori.data.audio

import com.satori.domain.repository.AudioUsage
import com.satori.domain.repository.RecordingAudioRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings for the audio kept of recordings, and which of it is being read. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    abstract fun bindRecordingAudioRepository(impl: RecordingAudioStore): RecordingAudioRepository

    @Binds
    abstract fun bindAudioUsage(impl: AudioInUse): AudioUsage
}
