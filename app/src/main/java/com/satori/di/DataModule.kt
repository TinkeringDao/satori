package com.satori.di

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import com.arm.aichat.InferenceEngine
import com.satori.data.audio.RecordingAudioStore
import com.satori.data.datasource.LlmDataSource
import com.satori.data.config.DefaultModelConfig
import com.satori.data.config.ModelConfig
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.SttDataSource
import com.satori.data.monitor.AndroidThermalMonitor
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.data.monitor.ReleasePurge
import com.satori.data.provider.AndroidResourceProvider
import com.satori.data.repository.LlmRepositoryImpl
import com.satori.data.repository.SettingsRepositoryImpl
import com.satori.data.repository.SttRepositoryImpl
import com.satori.domain.monitor.ThermalMonitor
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SttRepository
import com.satori.feature.llm.datasource.LlamaAndroidDataSource
import com.satori.feature.stt.datasource.SherpaOnnxDataSource
import com.satori.feature.stt.datasource.SpeechRecognizerFactory
import com.satori.feature.stt.datasource.VadFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing data layer dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    // The native heap each speech model's and the reader's release purges after its free
    // (ReleasePurge), through the one JNI call in libai-chat.so: ONNX Runtime and sherpa-onnx
    // allocate from the same bionic malloc, and bring no allocator of their own.
    @Provides
    @Singleton
    fun provideNativeHeap(): NativeHeap = NativeHeap {
        when (com.arm.aichat.NativeHeap.purge()) {
            2 -> Purge.ALL
            1 -> Purge.SOME
            else -> Purge.NONE
        }
    }

    @Provides
    @Singleton
    fun provideSttDataSource(
        @ApplicationContext context: Context,
        recognizers: SpeechRecognizerFactory,
        vadFactory: VadFactory,
        settingsRepository: SettingsRepository,
        heap: NativeHeap
    ): SttDataSource {
        val audioManager = context.getSystemService(AudioManager::class.java)
        return SherpaOnnxDataSource(
            context, recognizers, vadFactory, settingsRepository, audioManager,
            releasePurge = ReleasePurge.forApp(context, heap)
        )
    }

    @Provides
    @Singleton
    fun provideLlmDataSource(
        inferenceEngine: InferenceEngine,
        pacing: ModelLoadPacing
    ): LlmDataSource {
        return LlamaAndroidDataSource(inferenceEngine, pacing)
    }

    @Provides
    @Singleton
    fun provideSttRepository(
        dataSource: SttDataSource,
        audioStore: RecordingAudioStore
    ): SttRepository {
        return SttRepositoryImpl(dataSource, audioStore)
    }

    // The first answer after each load waits on the floor of the paced loads (AnswerFloor).
    @Provides
    @Singleton
    fun provideLlmRepository(
        dataSource: LlmDataSource,
        settingsRepository: SettingsRepository,
        @ApplicationContext context: Context,
        pacing: ModelLoadPacing
    ): LlmRepository {
        return LlmRepositoryImpl(dataSource, settingsRepository, context, pacing)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        resourceProvider: ResourceProvider
    ): SettingsRepository {
        return SettingsRepositoryImpl(context, resourceProvider)
    }

    @Provides
    @Singleton
    fun provideModelConfig(): ModelConfig = DefaultModelConfig.INSTANCE

    @Provides
    @Singleton
    fun provideModelDownloadManager(
        @ApplicationContext context: Context,
        modelConfig: ModelConfig
    ): ModelDownloadManager {
        return ModelDownloadManager(context, modelConfig)
    }

    @Provides
    @Singleton
    fun provideResourceProvider(
        @ApplicationContext context: Context
    ): ResourceProvider {
        return AndroidResourceProvider(context)
    }

    @Provides
    @Singleton
    fun provideThermalMonitor(
        @ApplicationContext context: Context
    ): ThermalMonitor {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return AndroidThermalMonitor(powerManager)
    }
}
