package com.satori.presentation.di

import android.util.Log
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.monitor.ThermalMonitor
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.finish.FinishRecordingUseCase
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.domain.usecase.finish.ReadingsLog
import com.satori.domain.usecase.finish.TranscribeAfterStopUseCase
import com.satori.domain.usecase.finish.TranscriptLinesStep
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.MindMapLog
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.domain.usecase.todo.TakenAwayTodos
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoChangeLog
import com.satori.domain.usecase.todo.UntouchedFinds
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import com.satori.domain.usecase.transcription.DeleteSessionUseCase
import com.satori.domain.usecase.transcription.LinesLog
import com.satori.domain.usecase.transcription.UpdateSessionDurationUseCase
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.AiModelPaths
import com.satori.presentation.main.InstalledAiModelPaths
import com.satori.presentation.main.RecorderAwareBetterTranscript
import com.satori.presentation.main.RecordingFinisher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The background finishing of recordings, for the whole app: the recorder hands its recording
 * over, and screens, the notification and the jobs that keep the queue alive see the same queue
 * ([RecordingFinisher]). In SingletonComponent, since a JobService and a receiver use it too.
 *
 * The use cases the finishing runs are here as well, and ViewModels get them from here: a
 * singleton can't take a ViewModel-scoped binding, and Hilt refuses one bound in both. None of
 * them keeps state between calls, so one shared by all callers, or a new one for each, works the
 * same; they are unscoped. The one with state, [TakenAwayTodos], is one for the app.
 */
@Module
@InstallIn(SingletonComponent::class)
object FinishingModule {

    @Provides
    fun provideFinishingQueue(finisher: RecordingFinisher): FinishingQueue = finisher

    @Provides
    fun provideAiModelPaths(paths: InstalledAiModelPaths): AiModelPaths = paths

    @Provides
    fun provideFinishRecordingUseCase(
        transcriptionRepository: TranscriptionRepository,
        generateFinalInsightUseCase: GenerateFinalInsightUseCase,
        generateBatchInsightUseCase: GenerateBatchInsightUseCase,
        autoNameSessionUseCase: AutoNameSessionUseCase,
        extractSessionTodosUseCase: ExtractSessionTodosUseCase,
        mapRecordingUseCase: MapRecordingUseCase,
        refreshThemeSummariesUseCase: RefreshThemeSummariesUseCase,
        notesLanguageResolver: NotesLanguageResolver,
        loadFinishingModelUseCase: LoadFinishingModelUseCase,
        betterTranscript: RecorderAwareBetterTranscript,
        transcriptLinesStep: TranscriptLinesStep,
        discardSilentRecordingUseCase: DiscardSilentRecordingUseCase,
        readingStep: ReadingStep,
        untouchedFinds: UntouchedFinds
    ): FinishRecordingUseCase {
        return FinishRecordingUseCase(
            transcriptionRepository,
            generateFinalInsightUseCase,
            generateBatchInsightUseCase,
            autoNameSessionUseCase,
            extractSessionTodosUseCase,
            mapRecordingUseCase,
            refreshThemeSummariesUseCase,
            notesLanguageResolver,
            loadFinishingModelUseCase,
            betterTranscript,
            transcriptLinesStep,
            discardSilentRecordingUseCase,
            readingStep,
            untouchedFinds
        )
    }

    /**
     * The to-dos and ideas the app found that the owner hasn't touched, which a finishing made again
     * over a recording's readings replaces (0.8.0, design R9); each taken away goes to the to-dos'
     * field log, with its reminder, as from [com.satori.domain.usecase.todo.TodoSource.READ_AGAIN].
     */
    @Provides
    fun provideUntouchedFinds(
        todoRepository: TodoRepository,
        reminderScheduler: ReminderScheduler,
        changeLog: TodoChangeLog,
        takenAway: TakenAwayTodos
    ): UntouchedFinds {
        return UntouchedFinds(todoRepository, TodoActions(todoRepository, reminderScheduler, log = changeLog, takenAway = takenAway))
    }

    /**
     * The to-dos a finishing made again took away in this process, one for the app: an edit the
     * owner saves on one of them, its sheet open as it went, brings it back ([TodoActions.edit]).
     */
    @Provides
    @Singleton
    fun provideTakenAwayTodos(): TakenAwayTodos = TakenAwayTodos()

    /**
     * The Reading step (0.8.0): the lines only the reader reads, read into English before the AI
     * steps, on the app's reader (AppModule's ReaderRepository). Shared with whatever else reads
     * readings under its rules; it keeps no state between calls.
     */
    @Provides
    fun provideReadingStep(
        transcriptionRepository: TranscriptionRepository,
        readerRepository: ReaderRepository,
        llmRepository: LlmRepository
    ): ReadingStep {
        return ReadingStep(transcriptionRepository, readerRepository, llmRepository)
    }

    /**
     * The readings the AI work outside the finishing makes first (0.8.0, design R6): Rewrite notes,
     * Find to-dos and ideas again, the text made again after Transcribe again, the map's work and
     * the debug rerun, by the Reading step's own rules.
     */
    @Provides
    fun provideEnsureReadingsUseCase(readingStep: ReadingStep, readingsLog: ReadingsLog): EnsureReadingsUseCase {
        return EnsureReadingsUseCase(readingStep, readingsLog)
    }

    /** Their field log's lines, under SatoriFinishing beside the finishing's own: counts and ids, never a text. */
    @Provides
    fun provideReadingsLog(): ReadingsLog = ReadingsLog { event ->
        val line = ReadingsLog.line(event)
        if (event.warns) Log.w(FINISHING_TAG, line) else Log.i(FINISHING_TAG, line)
    }

    private const val FINISHING_TAG = "SatoriFinishing"

    /** The better transcript after Stop, which the finishing never runs beside a live capture. */
    @Provides
    fun provideRecorderAwareBetterTranscript(
        transcribeAfterStopUseCase: TranscribeAfterStopUseCase,
        activeRecorder: ActiveRecorder
    ): RecorderAwareBetterTranscript {
        return RecorderAwareBetterTranscript(transcribeAfterStopUseCase, activeRecorder.current)
    }

    @Provides
    fun provideTranscribeAfterStopUseCase(
        settingsRepository: SettingsRepository,
        speechModelRepository: SpeechModelRepository,
        audioRepository: RecordingAudioRepository,
        transcriptionRepository: TranscriptionRepository,
        llmRepository: LlmRepository,
        deviceMemory: DeviceMemory,
        transcribeAgainUseCase: TranscribeAgainUseCase,
        processingService: LlmProcessingServiceController
    ): TranscribeAfterStopUseCase {
        return TranscribeAfterStopUseCase(
            settingsRepository,
            speechModelRepository,
            audioRepository,
            transcriptionRepository,
            llmRepository,
            deviceMemory,
            transcribeAgainUseCase,
            serviceInForeground = { processingService.protected.value }
        )
    }

    /** With the field log of the lines it joins (LinesModule), as the finishing's own Lines step has. */
    @Provides
    fun provideTranscribeAgainUseCase(
        sttRepository: SttRepository,
        transcriptionRepository: TranscriptionRepository,
        todoRepository: TodoRepository,
        audioRepository: RecordingAudioRepository,
        linesLog: LinesLog
    ): TranscribeAgainUseCase {
        return TranscribeAgainUseCase(sttRepository, transcriptionRepository, todoRepository, audioRepository, linesLog = linesLog)
    }

    /** With the field log of what its answers lose (NotesLogModule) and, in a debug build, its raw answers (DebugLogModule). */
    @Provides
    fun provideGenerateFinalInsightUseCase(
        llmRepository: LlmRepository,
        settingsRepository: SettingsRepository,
        resourceProvider: ResourceProvider,
        echoLog: PromptEchoLog,
        notesLog: NotesLog,
        answerLog: AnswerLog
    ): GenerateFinalInsightUseCase {
        return GenerateFinalInsightUseCase(llmRepository, settingsRepository, resourceProvider, echoLog, notesLog, answerLog)
    }

    /** As the final note: the field log of what its answers lose and, in a debug build, its raw answers. */
    @Provides
    fun provideGenerateBatchInsightUseCase(
        llmRepository: LlmRepository,
        settingsRepository: SettingsRepository,
        resourceProvider: ResourceProvider,
        thermalMonitor: ThermalMonitor,
        echoLog: PromptEchoLog,
        notesLog: NotesLog,
        answerLog: AnswerLog
    ): GenerateBatchInsightUseCase {
        return GenerateBatchInsightUseCase(llmRepository, settingsRepository, resourceProvider, thermalMonitor, echoLog, notesLog, answerLog)
    }

    @Provides
    fun provideLoadFinishingModelUseCase(
        llmRepository: LlmRepository,
        finishingModelRepository: FinishingModelRepository
    ): LoadFinishingModelUseCase {
        return LoadFinishingModelUseCase(llmRepository, finishingModelRepository)
    }

    @Provides
    fun provideGenerateSessionTitleUseCase(
        llmRepository: LlmRepository,
        resourceProvider: ResourceProvider,
        echoLog: PromptEchoLog,
        notesLog: NotesLog,
        answerLog: AnswerLog
    ): GenerateSessionTitleUseCase {
        return GenerateSessionTitleUseCase(llmRepository, resourceProvider, echoLog, notesLog, answerLog)
    }

    /** With the field log of where the name came from, or that the default name shows. */
    @Provides
    fun provideAutoNameSessionUseCase(
        transcriptionRepository: TranscriptionRepository,
        generateSessionTitleUseCase: GenerateSessionTitleUseCase,
        notesLog: NotesLog,
        answerLog: AnswerLog
    ): AutoNameSessionUseCase {
        return AutoNameSessionUseCase(transcriptionRepository, generateSessionTitleUseCase, notesLog, answerLog)
    }

    @Provides
    fun provideNotesLanguageResolver(
        settingsRepository: SettingsRepository,
        transcriptionRepository: TranscriptionRepository
    ): NotesLanguageResolver {
        return NotesLanguageResolver(settingsRepository, transcriptionRepository)
    }

    @Provides
    fun provideExtractTodosUseCase(
        llmRepository: LlmRepository,
        resourceProvider: ResourceProvider,
        timeParser: TimeExpressionParser,
        answerLog: AnswerLog,
        foundLog: FoundTodosLog
    ): ExtractTodosUseCase {
        return ExtractTodosUseCase(llmRepository, resourceProvider, timeParser, answerLog = answerLog, foundLog = foundLog)
    }

    @Provides
    fun provideExtractSessionTodosUseCase(
        settingsRepository: SettingsRepository,
        transcriptionRepository: TranscriptionRepository,
        todoRepository: TodoRepository,
        extractTodosUseCase: ExtractTodosUseCase,
        reminderScheduler: ReminderScheduler,
        notesLanguageResolver: NotesLanguageResolver
    ): ExtractSessionTodosUseCase {
        return ExtractSessionTodosUseCase(
            settingsRepository,
            transcriptionRepository,
            todoRepository,
            extractTodosUseCase,
            reminderScheduler,
            notesLanguageResolver
        )
    }

    @Provides
    fun provideDiscardSilentRecordingUseCase(
        transcriptionRepository: TranscriptionRepository,
        audioRepository: RecordingAudioRepository
    ): DiscardSilentRecordingUseCase {
        return DiscardSilentRecordingUseCase(transcriptionRepository, audioRepository)
    }

    /** With the field log of what its answers lose (NotesLogModule). */
    @Provides
    fun provideMapRecordingUseCase(
        transcriptionRepository: TranscriptionRepository,
        mindMapRepository: MindMapRepository,
        llmRepository: LlmRepository,
        resourceProvider: ResourceProvider,
        notesLanguageResolver: NotesLanguageResolver,
        mapLog: MindMapLog
    ): MapRecordingUseCase {
        return MapRecordingUseCase(
            transcriptionRepository,
            mindMapRepository,
            llmRepository,
            resourceProvider,
            notesLanguageResolver,
            mapLog = mapLog
        )
    }

    /** With the field log of what its answers lose (NotesLogModule). */
    @Provides
    fun provideRefreshThemeSummariesUseCase(
        mindMapRepository: MindMapRepository,
        llmRepository: LlmRepository,
        resourceProvider: ResourceProvider,
        notesLanguageResolver: NotesLanguageResolver,
        mapLog: MindMapLog
    ): RefreshThemeSummariesUseCase {
        return RefreshThemeSummariesUseCase(mindMapRepository, llmRepository, resourceProvider, notesLanguageResolver, mapLog = mapLog)
    }

    /**
     * Takes the recording out of the finishing queue before it deletes it, and its raw to-do
     * answers and the AI's prompt cache after.
     */
    @Provides
    fun provideDeleteSessionUseCase(
        transcriptionRepository: TranscriptionRepository,
        finishingQueue: FinishingQueue,
        llmRepository: LlmRepository,
        answerLog: AnswerLog
    ): DeleteSessionUseCase {
        return DeleteSessionUseCase(transcriptionRepository, finishingQueue, llmRepository, answerLog)
    }

    /** The recorder saves the length as it records; the finishing restores that of a capture cut off. */
    @Provides
    fun provideUpdateSessionDurationUseCase(
        transcriptionRepository: TranscriptionRepository
    ): UpdateSessionDurationUseCase {
        return UpdateSessionDurationUseCase(transcriptionRepository)
    }
}
