package com.satori.domain.usecase.audio

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.AudioFileInfo
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException

/**
 * Kept audio in memory, as the inventory lists it, and what compressing and deleting did to it.
 * Each compression takes [COMPRESS_MS] of the test's clock, so a cancel can come in the middle of
 * one. As in the store, audio kept by the end of its compression is left as it was.
 */
internal class StoredAudio(vararg files: AudioFileInfo) : RecordingAudioRepository {
    val files = files.toMutableList()
    val compressed = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var inventories = 0

    /** Recordings whose compression fails, as a codec error would make it. */
    var failingCompress = emptySet<String>()

    /** Recordings whose audio stays though it is deleted, as files the storage won't delete. */
    var undeletable = emptySet<String>()

    /** Called as each compression or delete starts, with its recording. */
    var onStart: (String) -> Unit = {}

    override suspend fun inventory(): List<AudioFileInfo> {
        inventories++
        return files.toList()
    }

    override suspend fun inventoryOf(sessionId: String): AudioFileInfo? = files.firstOrNull { it.sessionId == sessionId }

    override suspend fun compress(sessionId: String): Result<Long> {
        onStart(sessionId)
        delay(COMPRESS_MS)
        if (sessionId in failingCompress) return Result.failure(IOException("The encoder failed"))
        val index = files.indexOfFirst { it.sessionId == sessionId }
        if (index < 0 || files[index].kept || files[index].encoding != AudioEncoding.WAV) return Result.success(0L)
        files[index] = files[index].copy(encoding = AudioEncoding.AAC, bytes = files[index].bytes / 8)
        compressed += sessionId
        return Result.success(files[index].bytes * 7)
    }

    override suspend fun setKept(sessionId: String, kept: Boolean) = keep(sessionId, kept)

    /** [setKept] without suspending: the owner choosing "Keep this audio" while a run goes. */
    fun keep(sessionId: String, kept: Boolean = true) {
        val index = files.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) files[index] = files[index].copy(kept = kept)
    }

    override suspend fun delete(sessionId: String) {
        onStart(sessionId)
        deleted += sessionId
        if (sessionId !in undeletable) files.removeAll { it.sessionId == sessionId }
    }

    override suspend fun hasAudio(sessionId: String): Boolean = files.any { it.sessionId == sessionId }

    override suspend fun find(sessionId: String): RecordingAudio? = unused()
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>): Unit = unused()
    override suspend fun deleteAll(): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")

    companion object {
        const val COMPRESS_MS = 1_000L
    }
}

/**
 * Recordings in memory: their rows, the text of their lines and how many notes each has, as the
 * list query, the line and note reads and the delete see them. A deleted recording's audio goes
 * with it, as in the database.
 */
internal class StoredRecordings(
    private val audio: StoredAudio,
    vararg sessions: TranscriptionSession
) : TranscriptionRepository by FakeTranscriptionRepository(session = null) {
    val sessions = sessions.toMutableList()
    val lines = mutableMapOf<String, List<String>>()
    val notes = mutableMapOf<String, Int>()
    val lineReads = mutableListOf<String>()

    /** The recordings deleted whole, in order. */
    val deleted = mutableListOf<String>()

    /** Called as a recording is deleted, before it goes. */
    var onDelete: (String) -> Unit = {}

    override fun getAllSessions(): Flow<List<SessionListItem>> = flowOf(
        sessions.map { SessionListItem(it, hasTranscript = !lines[it.id].isNullOrEmpty(), hasNotes = (notes[it.id] ?: 0) > 0) }
    )

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> = flowOf(sessions.firstOrNull { it.id == sessionId })

    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> {
        lineReads += sessionId
        return flowOf(lines[sessionId].orEmpty().mapIndexed { i, text -> segment(text, at = i.toLong(), sessionId = sessionId) })
    }

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> =
        flowOf(List(notes[sessionId] ?: 0) { insight(title = null, content = "A note", at = it.toLong(), sessionId = sessionId) })

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        onDelete(sessionId)
        deleted += sessionId
        sessions.removeAll { it.id == sessionId }
        audio.files.removeAll { it.sessionId == sessionId }
        return Result.success(Unit)
    }
}
