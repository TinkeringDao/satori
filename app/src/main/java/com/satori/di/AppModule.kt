package com.satori.di

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.ReleasePurge
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.SttRepository
import com.satori.feature.reader.ReaderDataSource
import com.satori.feature.reader.ReaderNatives
import com.satori.feature.reader.ReaderRepositoryImpl
import com.satori.ui.SimulateStreamingAsr
import com.satori.feature.stt.datasource.VadFactory
import com.satori.service.RecordingNotificationManager
import com.satori.service.RecordingSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Builds the VADs speech recognition asks for: a new one for each recording, with the voice
     * detection settings it read as it started (VadParameters.of), and one for each Transcribe
     * again, with the values for files (VadParameters.FILE).
     */
    @Provides
    fun provideVadFactory(@ApplicationContext context: Context): VadFactory =
        VadFactory { parameters -> SimulateStreamingAsr.createVad(context.assets, parameters) }

    @Provides
    @Singleton
    fun provideInferenceEngine(
        @ApplicationContext context: Context
    ): InferenceEngine {
        return com.arm.aichat.AiChat.getInferenceEngine(context)
    }

    /**
     * The reader (0.8.0): loaded at the speech models' pace for a file, never beside a capture,
     * the live speech model or an AI model, and released with the native heap purged, as a speech
     * model is. Its memory lines, in a debug build, come under SatoriReader.
     */
    @Provides
    @Singleton
    fun provideReaderDataSource(
        @ApplicationContext context: Context,
        downloads: ModelDownloadManager,
        pacing: ModelLoadPacing,
        sttRepository: SttRepository,
        llmRepository: LlmRepository,
        heap: NativeHeap
    ): ReaderDataSource = readerDataSource(context, downloads, pacing, sttRepository, llmRepository, heap)

    /**
     * The reader as the finishing reads with it (0.8.0, R5): the int8 build at beam 4, on the app's
     * data source, the native heap purged before each load as after each release.
     */
    @Provides
    @Singleton
    fun provideReaderRepository(dataSource: ReaderDataSource, heap: NativeHeap): ReaderRepository =
        ReaderRepositoryImpl(dataSource, heap)

    @Provides
    @Singleton
    fun provideRecordingSessionManager(): RecordingSessionManager {
        return RecordingSessionManager()
    }

    @Provides
    @Singleton
    fun provideRecordingNotificationManager(
        @ApplicationContext context: Context
    ): RecordingNotificationManager {
        return RecordingNotificationManager(context)
    }
}

/**
 * The reader as the app has it ([AppModule.provideReaderDataSource]), on lib-reader-onnx's native
 * calls ([natives]). A debug build's reader selftest makes one more for a stop it makes itself,
 * whose native calls it watches (DebugReaderViewModel); all else is wired here, once, so that
 * reader is the app's. Its lines, and its memory lines, go to [log] and [warn], under SatoriReader;
 * a test gives its own.
 */
internal fun readerDataSource(
    context: Context,
    downloads: ModelDownloadManager,
    pacing: SpeechModelLoadPacing,
    sttRepository: SttRepository,
    llmRepository: LlmRepository,
    heap: NativeHeap,
    natives: ReaderNatives = ReaderNatives.DEVICE,
    log: (String) -> Unit = { Log.i(ReaderDataSource.TAG, it) },
    warn: (String) -> Unit = { Log.w(ReaderDataSource.TAG, it) }
): ReaderDataSource {
    val flags = context.applicationInfo.flags
    return ReaderDataSource(
        downloads = downloads,
        pacing = pacing,
        memoryLines = { sink ->
            ModelMemoryLines.forFlags(flags, log = { line ->
                log(line)
                sink?.invoke(line)
            })
        },
        releasePurge = ReleasePurge.forApp(context, heap),
        captureState = sttRepository.captureState,
        speechModelLoaded = sttRepository.liveModelLoaded,
        aiModelInMemory = { llmRepository.isModelInMemory },
        natives = natives,
        log = log,
        warn = warn
    )
}
