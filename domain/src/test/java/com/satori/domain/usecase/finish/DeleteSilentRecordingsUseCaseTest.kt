package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.todo.RecordedTodos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeleteSilentRecordingsUseCaseTest {

    private val now = 10 * HOUR

    /** One recording as it is stored: its row, the text of its lines, how many notes it has. */
    private class Stored(val session: TranscriptionSession, val lines: List<String> = emptyList(), val notes: Int = 0)

    /** The recordings in memory, answering the list query, their lines and deleting them. */
    private class Recordings(vararg stored: Stored) : TranscriptionRepository by FakeTranscriptionRepository(session = null) {
        val left = stored.toMutableList()
        var deleteFailure: Exception? = null

        val ids: List<String> get() = left.map { it.session.id }

        override fun getAllSessions(): Flow<List<SessionListItem>> = flowOf(
            left.sortedByDescending { it.session.createdAt }.map {
                SessionListItem(it.session, hasTranscript = it.lines.isNotEmpty(), hasNotes = it.notes > 0)
            }
        )

        override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> = flowOf(
            left.firstOrNull { it.session.id == sessionId }?.lines.orEmpty()
                .mapIndexed { i, text -> segment(text, at = i.toLong(), sessionId = sessionId) }
        )

        override suspend fun deleteSession(sessionId: String): Result<Unit> {
            deleteFailure?.let { return Result.failure(it) }
            left.removeAll { it.session.id == sessionId }
            return Result.success(Unit)
        }
    }

    /** A recording that started [startedAgo] before now and ran [lengthMs]. */
    private fun recording(id: String, startedAgo: Long = 3 * HOUR, lengthMs: Long = 54_000L): TranscriptionSession {
        val start = now - startedAgo
        return session(id).copy(createdAt = start, lastModifiedAt = start, durationMs = lengthMs)
    }

    private fun todo(sessionId: String, status: TodoStatus) =
        Todo(id = "todo-$sessionId", sessionId = sessionId, text = "Call the bank", status = status, createdAt = 0L, updatedAt = 0L)

    private fun deleteSilent(
        recordings: Recordings,
        todos: RecordedTodos = RecordedTodos(),
        audio: RecordingAudioRepository = KeptAudio()
    ) = runBlocking { DeleteSilentRecordingsUseCase(recordings, todos, audio, now = { now })() }

    @Test
    fun `old recordings that hold nothing at all go, and the count says how many`() {
        val recordings = Recordings(
            Stored(recording("no-line")),
            Stored(recording("blank-lines"), lines = listOf("", " . ", "?")),
            Stored(recording("spoken"), lines = listOf("Remind me to call the bank."))
        )

        assertEquals(2, deleteSilent(recordings))

        assertEquals(listOf("spoken"), recordings.ids)
    }

    @Test
    fun `a line of digits is speech`() {
        val recordings = Recordings(Stored(recording("number"), lines = listOf(".", "10")))

        assertEquals(0, deleteSilent(recordings))

        assertEquals(listOf("number"), recordings.ids)
    }

    @Test
    fun `only recordings that ended more than an hour ago are looked at`() {
        val recordings = Recordings(
            // Started an hour and half a minute ago and ran 54 seconds: it ended within the hour.
            Stored(recording("recent", startedAgo = HOUR + 30_000L)),
            // Started three hours ago and still recording: the recorder saved its length 20 s ago.
            Stored(recording("being-made", startedAgo = 3 * HOUR, lengthMs = 3 * HOUR - 20_000L)),
            // A line was saved ten minutes ago.
            Stored(recording("changed").copy(lastModifiedAt = now - 10 * MINUTE), lines = listOf(".")),
            Stored(recording("old", startedAgo = 2 * HOUR))
        )

        assertEquals(1, deleteSilent(recordings))

        assertEquals(listOf("recent", "being-made", "changed"), recordings.ids)
    }

    @Test
    fun `a recording with a note stays`() {
        val recordings = Recordings(Stored(recording("noted"), notes = 1))

        assertEquals(0, deleteSilent(recordings))

        assertEquals(listOf("noted"), recordings.ids)
    }

    @Test
    fun `a recording with a to-do stays, also one that was dismissed`() {
        val recordings = Recordings(Stored(recording("open")), Stored(recording("dismissed")))
        val todos = RecordedTodos(todo("open", TodoStatus.OPEN), todo("dismissed", TodoStatus.DISMISSED))

        assertEquals(0, deleteSilent(recordings, todos))

        assertEquals(listOf("open", "dismissed"), recordings.ids)
    }

    @Test
    fun `a silent recording with kept audio stays, as its audio may hold speech`() {
        val recordings = Recordings(Stored(recording("with-audio")), Stored(recording("without-audio")))

        assertEquals(1, deleteSilent(recordings, audio = KeptAudio("with-audio")))

        assertEquals(listOf("with-audio"), recordings.ids)
    }

    @Test
    fun `audio that can't be read at the time still keeps it`() {
        val recordings = Recordings(Stored(recording("unreadable")))

        assertEquals(0, deleteSilent(recordings, audio = KeptAudio("unreadable", readable = false)))

        assertEquals(listOf("unreadable"), recordings.ids)
    }

    @Test
    fun `a recording that can't be deleted ends the run, which then runs again`() {
        val recordings = Recordings(Stored(recording("silent"))).apply { deleteFailure = IllegalStateException("disk full") }

        assertThrows(IllegalStateException::class.java) { deleteSilent(recordings) }

        assertEquals(listOf("silent"), recordings.ids)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
