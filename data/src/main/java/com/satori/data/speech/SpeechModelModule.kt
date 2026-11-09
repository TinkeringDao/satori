package com.satori.data.speech

import com.satori.data.monitor.AndroidDeviceMemory
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.SpeechVocabularyRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings of the speech models on the phone, the words to recognize and the free memory. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechModelModule {

    @Binds
    abstract fun bindSpeechModelRepository(impl: InstalledSpeechModels): SpeechModelRepository

    @Binds
    abstract fun bindSpeechVocabularyRepository(impl: SpeechVocabularyStore): SpeechVocabularyRepository

    @Binds
    abstract fun bindDeviceMemory(impl: AndroidDeviceMemory): DeviceMemory
}
