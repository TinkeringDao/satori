package com.satori.domain.usecase.audio

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.AudioFileInfo
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase.Action
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase.FileDone
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase.Outcome
import com.satori.domain.usecase.audio.AudioRetentionRules.DAY_MS
import com.satori.domain.usecase.todo.RecordedTodos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyAudioRetentionUseCaseTest {

    private val now = 400 * DAY_MS
    private val audio = StoredAudio()
    private val recordings = StoredRecordings(audio)
    private val todos = RecordedTodos()
    private val inUse = mutableSetOf<String>()
    private val settings = FakeSettingsRepository()

    /** A recording with [bytes] of audio, whose capture ended [endedAgo] before now; spoken unless told otherwise. */
    private fun recorded(id: String, endedAgo: Long, bytes: Long = 8_000_000L, words: Boolean = true): AudioFileInfo {
        val ended = now - endedAgo
        recordings.sessions += session(id).copy(createdAt = ended - 60_000L, lastModifiedAt = ended, durationMs = 60_000L)
        recordings.lines[id] = if (words) listOf("Call the plumber about the leak.") else listOf(".")
        return AudioFileInfo(id, bytes, AudioEncoding.WAV, lastCaptureEndMs = ended, kept = false).also { audio.files += it }
    }

    /** The use case, with the rules stored as given; a test may store others while it runs. */
    private fun useCase(compressAfterDays: Int? = 7, deleteAfterDays: Int? = 30): ApplyAudioRetentionUseCase {
        settings.settings = AppSettings(audioCompressAfterDays = compressAfterDays, audioDeleteAfterDays = deleteAfterDays)
        return ApplyAudioRetentionUseCase(
            audioRepository = audio,
            transcriptionRepository = recordings,
            todoRepository = todos,
            settingsRepository = settings,
            inUse = { inUse.toSet() },
            now = { now },
            elapsedMs = { 0L }
        )
    }

    @Test
    fun `a run deletes first, then compresses, and counts what it did`() = runTest {
        recorded("compress-me", endedAgo = 10 * DAY_MS)
        recorded("delete-my-audio", endedAgo = 40 * DAY_MS, bytes = 5_000_000L)
        recorded("delete-me", endedAgo = 40 * DAY_MS, bytes = 1_000_000L, words = false)
        recorded("young", endedAgo = 2 * DAY_MS)
        val done = mutableListOf<FileDone>()

        val outcome = useCase()(onFile = { done += it })

        assertEquals(
            listOf("delete-me" to Action.DELETE_RECORDING, "delete-my-audio" to Action.DELETE_AUDIO, "compress-me" to Action.COMPRESS),
            done.map { it.sessionId to it.action }
        )
        assertEquals(Outcome(compressed = 1, audioDeleted = 1, recordingsDeleted = 1, freedBytes = 7_000_000L + 5_000_000L + 1_000_000L), outcome)
        assertEquals(listOf("compress-me", "delete-my-audio", "young"), recordings.sessions.map { it.id })
        assertEquals(listOf("compress-me", "young"), audio.files.map { it.sessionId })
        assertEquals(AudioEncoding.AAC, audio.files.first().encoding)
    }

    @Test
    fun `a failed compress keeps the WAV and goes on with the next file`() = runTest {
        recorded("broken", endedAgo = 10 * DAY_MS)
        recorded("fine", endedAgo = 10 * DAY_MS)
        audio.failingCompress = setOf("broken")
        val done = mutableListOf<FileDone>()

        val outcome = useCase()(onFile = { done += it })

        assertEquals(listOf("fine"), audio.compressed)
        assertEquals(AudioEncoding.WAV, audio.files.first { it.sessionId == "broken" }.encoding)
        assertEquals(1, outcome.failed)
        assertEquals(1, outcome.compressed)
        assertNotNull(done.first { it.sessionId == "broken" }.error)
        assertEquals(0L, done.first { it.sessionId == "broken" }.freedBytes)
        assertNull(done.first { it.sessionId == "fine" }.error)
    }

    @Test
    fun `a cancel takes effect between two files, never inside one`() = runTest {
        recorded("first", endedAgo = 10 * DAY_MS)
        recorded("second", endedAgo = 10 * DAY_MS)
        lateinit var run: Job
        audio.onStart = { id -> if (id == "first") run.cancel() }

        run = launch { useCase()() }
        run.join()

        assertTrue(run.isCancelled)
        // The compression that was running when the cancel came ran to its end.
        assertEquals(listOf("first"), audio.compressed)
        assertEquals(AudioEncoding.AAC, audio.files.first { it.sessionId == "first" }.encoding)
        assertEquals(AudioEncoding.WAV, audio.files.first { it.sessionId == "second" }.encoding)
    }

    @Test
    fun `audio that comes into use after the plan is skipped as its turn comes`() = runTest {
        recorded("first", endedAgo = 40 * DAY_MS)
        recorded("played-meanwhile", endedAgo = 40 * DAY_MS)
        audio.onStart = { inUse += "played-meanwhile" }

        val outcome = useCase()()

        assertEquals(listOf("first"), audio.deleted)
        assertEquals(1, outcome.skippedInUse)
        assertTrue(audio.files.any { it.sessionId == "played-meanwhile" })
    }

    @Test
    fun `a recording that holds nothing goes whole, and one that got something meanwhile only loses its audio`() = runTest {
        recorded("empty", endedAgo = 40 * DAY_MS, words = false)
        recorded("noted-meanwhile", endedAgo = 40 * DAY_MS, words = false)
        // As the first goes, a note is written for the second.
        recordings.onDelete = { recordings.notes["noted-meanwhile"] = 1 }

        val outcome = useCase()()

        assertEquals(listOf("noted-meanwhile"), recordings.sessions.map { it.id })
        assertTrue(audio.files.isEmpty())
        assertEquals(1, outcome.recordingsDeleted)
        assertEquals(1, outcome.audioDeleted)
    }

    @Test
    fun `a to-do or an idea keeps a recording without words, which only loses its audio`() = runTest {
        recorded("with-idea", endedAgo = 40 * DAY_MS, words = false)
        todos.stored += Todo(
            id = "idea-1", sessionId = "with-idea", text = "Option to delete old recordings",
            status = TodoStatus.IDEA, createdAt = 0L, updatedAt = 0L
        )

        val outcome = useCase()()

        assertEquals(listOf("with-idea"), recordings.sessions.map { it.id })
        assertEquals(1, outcome.audioDeleted)
        assertEquals(0, outcome.recordingsDeleted)
    }

    @Test
    fun `audio that stays though it was deleted counts as failed`() = runTest {
        recorded("stuck", endedAgo = 40 * DAY_MS)
        audio.undeletable = setOf("stuck")

        val outcome = useCase()()

        assertEquals(Outcome(failed = 1), outcome)
    }

    @Test
    fun `only the recordings whose audio goes are read for what else they hold`() = runTest {
        recorded("compress-me", endedAgo = 10 * DAY_MS)
        recorded("delete-my-audio", endedAgo = 40 * DAY_MS)
        recorded("young", endedAgo = 2 * DAY_MS)

        val plan = useCase().plan(compressAfterDays = 7, deleteAfterDays = 30)

        assertEquals(listOf("delete-my-audio"), plan.deleteAudio.map { it.sessionId })
        assertEquals(listOf("delete-my-audio"), recordings.lineReads)
    }

    @Test
    fun `a plan for a later day changes nothing`() = runTest {
        recorded("today", endedAgo = DAY_MS)
        recorded("last-week", endedAgo = 7 * DAY_MS)

        val plan = useCase().plan(compressAfterDays = 7, deleteAfterDays = 30, atMs = now + 30 * DAY_MS)

        assertEquals(listOf("today", "last-week"), plan.deleteAudio.map { it.sessionId })
        assertEquals(2, audio.files.size)
        assertTrue(audio.deleted.isEmpty())
        assertTrue(audio.compressed.isEmpty())
    }

    @Test
    fun `with both rules on never nothing is even read`() = runTest {
        recorded("old", endedAgo = 300 * DAY_MS)

        val outcome = useCase(compressAfterDays = null, deleteAfterDays = null)()

        assertEquals(Outcome(), outcome)
        assertEquals(0, audio.inventories)
    }

    @Test
    fun `audio the owner keeps while a run goes is neither deleted nor compressed when its turn comes`() = runTest {
        recorded("delete-first", endedAgo = 40 * DAY_MS)
        recorded("delete-kept", endedAgo = 40 * DAY_MS)
        recorded("compress-first", endedAgo = 10 * DAY_MS)
        recorded("compress-kept", endedAgo = 10 * DAY_MS)
        // "Keep this audio" is chosen for two of them as the run deletes the first.
        audio.onStart = { id ->
            if (id == "delete-first") {
                audio.keep("delete-kept")
                audio.keep("compress-kept")
            }
        }

        val outcome = useCase()()

        assertEquals(listOf("delete-first"), audio.deleted)
        assertEquals(listOf("compress-first"), audio.compressed)
        assertEquals(Outcome(compressed = 1, audioDeleted = 1, noLongerDue = 2, freedBytes = 7_000_000L + 8_000_000L), outcome)
        assertEquals(AudioEncoding.WAV, audio.files.first { it.sessionId == "compress-kept" }.encoding)
    }

    @Test
    fun `audio kept while it is being compressed is no longer due, and not told as compressed`() = runTest {
        recorded("kept-meanwhile", endedAgo = 10 * DAY_MS)
        audio.onStart = { id -> audio.keep(id) }
        val done = mutableListOf<FileDone>()

        val outcome = useCase()(onFile = { done += it })

        assertTrue(audio.compressed.isEmpty())
        assertEquals(Outcome(noLongerDue = 1), outcome)
        assertTrue(done.isEmpty())
    }

    @Test
    fun `rules turned off while a run goes leave the files after it as they are`() = runTest {
        recorded("first", endedAgo = 40 * DAY_MS)
        recorded("second", endedAgo = 40 * DAY_MS)
        recorded("to-compress", endedAgo = 10 * DAY_MS)
        val useCase = useCase()
        // Both rules set to Never as the first is deleted.
        audio.onStart = { settings.settings = AppSettings() }

        val outcome = useCase()

        assertEquals(listOf("first"), audio.deleted)
        assertTrue(audio.compressed.isEmpty())
        assertEquals(Outcome(audioDeleted = 1, noLongerDue = 2, freedBytes = 8_000_000L), outcome)
    }

    @Test
    fun `audio planned to go whose delete rule is turned off meanwhile is compressed as the other rule says`() = runTest {
        recorded("first", endedAgo = 40 * DAY_MS)
        recorded("second", endedAgo = 40 * DAY_MS)
        val useCase = useCase()
        audio.onStart = { settings.settings = settings.settings.copy(audioDeleteAfterDays = null) }

        val outcome = useCase()

        assertEquals(listOf("first"), audio.deleted)
        assertEquals(listOf("second"), audio.compressed)
        assertEquals(1, outcome.audioDeleted)
        assertEquals(1, outcome.compressed)
    }

    @Test
    fun `a file never gets more than was planned, though a rule asks for more by its turn`() = runTest {
        recorded("first", endedAgo = 40 * DAY_MS)
        recorded("second", endedAgo = 40 * DAY_MS)
        val useCase = useCase(compressAfterDays = 7, deleteAfterDays = null)
        // A delete age is chosen as the first is compressed: the run that follows the change deletes.
        audio.onStart = { settings.settings = settings.settings.copy(audioDeleteAfterDays = 30) }

        val outcome = useCase()

        assertEquals(listOf("first"), audio.compressed)
        assertTrue(audio.deleted.isEmpty())
        assertEquals(1, outcome.noLongerDue)
    }

    @Test
    fun `audio deleted since the plan is skipped, and frees nothing`() = runTest {
        recorded("first", endedAgo = 10 * DAY_MS)
        recorded("deleted-meanwhile", endedAgo = 10 * DAY_MS)
        // The owner deletes the second's audio as the first is compressed.
        audio.onStart = { id -> if (id == "first") audio.files.removeAll { it.sessionId == "deleted-meanwhile" } }

        val outcome = useCase()()

        assertEquals(Outcome(compressed = 1, noLongerDue = 1, freedBytes = 7_000_000L), outcome)
    }

    @Test
    fun `a recording planned to go whole whose row is gone by its turn only loses its audio`() = runTest {
        recorded("empty", endedAgo = 40 * DAY_MS, words = false)
        recorded("row-gone", endedAgo = 40 * DAY_MS, words = false)
        // As the first goes, the second's row goes too and its audio stays: a delete cut short.
        recordings.onDelete = { id -> if (id == "empty") recordings.sessions.removeAll { it.id == "row-gone" } }

        val outcome = useCase()()

        assertEquals(listOf("empty"), recordings.deleted)
        assertEquals(listOf("row-gone"), audio.deleted)
        assertEquals(1, outcome.recordingsDeleted)
        assertEquals(1, outcome.audioDeleted)
        assertTrue(audio.files.isEmpty())
    }

    @Test
    fun `audio in use when the plan is made is not in it`() = runTest {
        recorded("recording-now", endedAgo = 40 * DAY_MS)
        inUse += "recording-now"

        assertTrue(useCase().plan(compressAfterDays = 7, deleteAfterDays = 30).isEmpty)
    }
}
