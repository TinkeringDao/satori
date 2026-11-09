package com.satori.domain.usecase.stt

import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TodoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// The speech model, the to-dos and the kept audio of one recording, for the tests of Transcribe
// again and of the transcript after Stop.

/**
 * Answers with [lines], reporting half way and the end, or throws [failure]; keeps the model and
 * the language it was asked for. With [hold] set it waits for it first, as a model that is still
 * decoding: a cancel then ends the wait.
 */
internal class FakeSpeech : SttRepository {
    var lines: List<SpokenLine> = emptyList()
    var failure: Exception? = null
    var hold: CompletableDeferred<Unit>? = null
    var calls = 0
    var model: SpeechModelId? = null
    var language: String? = null

    override suspend fun transcribeAudio(
        audio: RecordingAudio,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<SpokenLine> {
        calls++
        this.model = model
        this.language = language
        failure?.let { throw it }
        hold?.await()
        onProgress(0.5f)
        onProgress(1f)
        return lines
    }

    override val audioLevel: StateFlow<Float> = MutableStateFlow(0f)
    /** No capture runs until a test says otherwise. */
    override val captureState: MutableStateFlow<CaptureState> = MutableStateFlow(CaptureState.IDLE)
    override suspend fun initialize(): Result<Unit> = unused()
    override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> = unused()
    override fun mayHaveHeardSpeech(): Boolean = unused()
    override suspend fun stopStreaming(): Unit = unused()
    override suspend fun releaseModel(): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

/** The to-dos of the recording, [forSession]; nothing else is asked of it. */
internal class FakeTodos : TodoRepository {
    var forSession: List<Todo> = emptyList()

    override fun observeForSession(sessionId: String): Flow<List<Todo>> = flowOf(forSession.filter { it.sessionId == sessionId })

    override fun observeOpen(): Flow<List<Todo>> = unused()
    override fun observeSuggested(): Flow<List<Todo>> = unused()
    override fun observeIdeas(): Flow<List<Todo>> = unused()
    override suspend fun get(id: String): Todo? = unused()
    override suspend fun upsert(todo: Todo): Unit = unused()
    override suspend fun setStatus(id: String, status: TodoStatus, completedAt: Long?): Unit = unused()
    override suspend fun setRemindAt(id: String, remindAt: Long?): Unit = unused()
    override suspend fun delete(id: String): Unit = unused()
    override suspend fun dueReminders(atMs: Long): List<Todo> = unused()
    override suspend fun markReminderShown(id: String, atMs: Long): Unit = unused()
    override suspend fun nextReminderAfter(afterMs: Long): Todo? = unused()

    private fun unused(): Nothing = error("not used by these tests")
}

/** Holds the audio of one recording and records what [replaceLines] was given. */
internal class FakeAudioRepository(var audio: RecordingAudio?) : RecordingAudioRepository {
    var replacedLines: Map<String, AudioSpan>? = null

    override suspend fun find(sessionId: String): RecordingAudio? = audio?.takeIf { it.sessionId == sessionId }
    override suspend fun hasAudio(sessionId: String): Boolean = error("not used by these tests")
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>) {
        replacedLines = lines
    }

    override suspend fun setKept(sessionId: String, kept: Boolean): Unit = error("not used by these tests")

    override suspend fun delete(sessionId: String): Unit = error("not used by these tests")
    override suspend fun deleteAll(): Unit = error("not used by these tests")
}
