package com.satori.data.repository

import com.satori.data.database.dao.FinishingJobDao
import com.satori.data.database.entity.FinishingJobEntity
import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobState
import com.satori.domain.usecase.finish.FinishStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FinishingJobRepositoryImpl] and the Kotlin transactions of [FinishingJobDao] on the JVM, with
 * the DAO's single statements answered in memory as their SQL reads ([InMemoryJobs]): what a
 * capture, a hand-off and a claim write, the attempts of a step through the stored step names,
 * and how a stored row reads. The SQL itself runs in the instrumented FinishingJobDaoTest.
 */
class FinishingJobRepositoryImplTest {

    private val dao = InMemoryJobs(sessions = setOf("s1", "s2", "s3"))
    private val repository = FinishingJobRepositoryImpl(dao)

    @Test
    fun `a capture writes a capturing job of its owner, and one of a recording that is gone writes nothing`() = runBlocking {
        repository.captureStarted("s1", owner = "process-a", now = 10L)
        repository.captureStarted("deleted", owner = "process-a", now = 11L)

        assertEquals(
            FinishingJob(
                sessionId = "s1", state = FinishingJobState.CAPTURING, nextStep = null, attempts = 0,
                owner = "process-a", recognizerFailed = false, recovered = false, waitsForRecording = false,
                queuedAt = null, createdAt = 10L
            ),
            repository.get("s1")
        )
        assertEquals(setOf("s1"), dao.rows.keys)
    }

    @Test
    fun `a capture of a recording that has a job makes it capturing for the new owner and keeps the rest`() = runBlocking {
        dao.rows["s1"] = job("s1", FinishingJobDao.RUNNING, owner = "process-a", nextStep = "Todos", attempts = 1, queuedAt = 5L, createdAt = 3L)

        repository.captureStarted("s1", owner = "process-b", now = 20L)

        assertEquals(
            job("s1", FinishingJobDao.CAPTURING, owner = "process-b", nextStep = "Todos", attempts = 1, queuedAt = 5L, createdAt = 3L),
            dao.rows["s1"]
        )
    }

    @Test
    fun `the hand-off queues the capture's job, writes one when there is none, and fails for a recording that is gone`() = runBlocking {
        repository.captureStarted("s1", owner = "process-a", now = 10L)

        assertTrue(repository.queue("s1", recognizerFailed = true, now = 30L))
        assertTrue(repository.queue("s2", recognizerFailed = false, now = 31L))
        assertFalse(repository.queue("deleted", recognizerFailed = false, now = 32L))

        assertEquals(
            job("s1", FinishingJobDao.QUEUED, owner = "process-a", recognizerFailed = true, queuedAt = 30L, createdAt = 10L),
            dao.rows["s1"]
        )
        assertEquals(job("s2", FinishingJobDao.QUEUED, owner = FinishingJobDao.NO_OWNER, queuedAt = 31L, createdAt = 31L), dao.rows["s2"])
        assertEquals(setOf("s1", "s2"), dao.rows.keys)
    }

    @Test
    fun `a claim takes the first queued job that doesn't wait, running for its claimer`() = runBlocking {
        dao.rows["s1"] = job("s1", FinishingJobDao.QUEUED, queuedAt = 20L, createdAt = 1L)
        dao.rows["s2"] = job("s2", FinishingJobDao.QUEUED, queuedAt = 10L, createdAt = 2L, waitsForRecording = true)
        dao.rows["s3"] = job("s3", FinishingJobDao.QUEUED, queuedAt = 20L, createdAt = 0L)

        val first = repository.claimNext("process-b")

        assertEquals("s3", first?.sessionId)
        assertEquals(FinishingJobState.RUNNING, first?.state)
        assertEquals("process-b", first?.owner)
        assertEquals(first, repository.get("s3"))
        assertEquals("s1", repository.claimNext("process-b")?.sessionId)
        assertNull(repository.claimNext("process-b"))
    }

    @Test
    fun `a step that begins again counts on, another starts at 1, and a job that is gone says so`() = runBlocking {
        repository.queue("s1", recognizerFailed = false, now = 1L)

        assertTrue(repository.beginStep("s1", FinishStep.Notes))
        assertEquals(FinishStep.Notes to 1, repository.get("s1")?.let { it.nextStep to it.attempts })
        assertTrue(repository.beginStep("s1", FinishStep.Notes))
        assertEquals(FinishStep.Notes to 2, repository.get("s1")?.let { it.nextStep to it.attempts })
        assertTrue(repository.beginStep("s1", FinishStep.Name))
        assertEquals(FinishStep.Name to 1, repository.get("s1")?.let { it.nextStep to it.attempts })

        repository.delete("s1")

        assertFalse(repository.beginStep("s1", FinishStep.Todos))
    }

    @Test
    fun `an attempt given back never goes below 0`() = runBlocking {
        repository.queue("s1", recognizerFailed = false, now = 1L)
        repository.beginStep("s1", FinishStep.Lines)

        repository.giveBackAttempt("s1")
        repository.giveBackAttempt("s1")

        assertEquals(FinishStep.Lines to 0, repository.get("s1")?.let { it.nextStep to it.attempts })
    }

    @Test
    fun `a stored job reads with every field, a state or step this version doesn't know as capturing and none`() = runBlocking {
        dao.rows["s1"] = FinishingJobEntity(
            sessionId = "s1", state = FinishingJobDao.QUEUED, nextStep = "Map", attempts = 2, owner = "process-a",
            recognizerFailed = true, recovered = true, waitsForRecording = true, queuedAt = 7L, createdAt = 6L
        )
        dao.rows["s2"] = job("s2", state = "PAUSED", nextStep = "Summarise")

        assertEquals(
            listOf(
                FinishingJob(
                    sessionId = "s1", state = FinishingJobState.QUEUED, nextStep = FinishStep.Map, attempts = 2,
                    owner = "process-a", recognizerFailed = true, recovered = true, waitsForRecording = true,
                    queuedAt = 7L, createdAt = 6L
                ),
                FinishingJob(
                    sessionId = "s2", state = FinishingJobState.CAPTURING, nextStep = null, attempts = 0,
                    owner = "", recognizerFailed = false, recovered = false, waitsForRecording = false,
                    queuedAt = null, createdAt = 0L
                )
            ),
            repository.observeAll().first()
        )
    }

    @Test
    fun `a redo is a new job queued at the Reading step, made again, and none for a recording with a job or one that is gone`() = runBlocking {
        repository.captureStarted("s1", owner = "process-a", now = 10L)

        assertTrue(repository.queueRedo("s2", now = 40L))
        assertFalse(repository.queueRedo("s2", now = 41L))
        assertFalse(repository.queueRedo("s1", now = 42L))
        assertFalse(repository.queueRedo("deleted", now = 43L))

        assertEquals(
            FinishingJob(
                sessionId = "s2", state = FinishingJobState.QUEUED, nextStep = FinishStep.Reading, attempts = 0,
                owner = FinishingJobDao.NO_OWNER, recognizerFailed = false, recovered = false, waitsForRecording = false,
                queuedAt = 40L, createdAt = 40L, redo = true
            ),
            repository.get("s2")
        )
        // The capture's job is as it was.
        assertEquals(FinishingJobState.CAPTURING, repository.get("s1")?.state)
        assertFalse(repository.get("s1")!!.redo)
        assertEquals(setOf("s1", "s2"), dao.rows.keys)
    }

    @Test
    fun `the stored states are the domain's names`() {
        assertEquals(
            FinishingJobState.entries.map { it.name },
            listOf(FinishingJobDao.CAPTURING, FinishingJobDao.QUEUED, FinishingJobDao.RUNNING)
        )
    }

    private fun job(
        sessionId: String,
        state: String,
        owner: String = "",
        nextStep: String? = null,
        attempts: Int = 0,
        recognizerFailed: Boolean = false,
        waitsForRecording: Boolean = false,
        queuedAt: Long? = null,
        createdAt: Long = 0L
    ) = FinishingJobEntity(
        sessionId = sessionId, state = state, nextStep = nextStep, attempts = attempts, owner = owner,
        recognizerFailed = recognizerFailed, recovered = false, waitsForRecording = waitsForRecording,
        queuedAt = queuedAt, createdAt = createdAt
    )

    /**
     * The single statements of [FinishingJobDao] on a map, each as its SQL reads, for the
     * recordings [sessions]; the transactions are the DAO's own. An insert of a job that is there,
     * or of one whose recording is gone, fails as the primary key and the foreign key would.
     */
    private class InMemoryJobs(private val sessions: Set<String>) : FinishingJobDao() {
        val rows = linkedMapOf<String, FinishingJobEntity>()

        override fun observeAll(): Flow<List<FinishingJobEntity>> = flow {
            emit(rows.values.sortedWith(compareBy({ rank(it.state) }, { it.queuedAt ?: Long.MIN_VALUE }, { it.createdAt }, { it.sessionId })))
        }

        override suspend fun get(sessionId: String): FinishingJobEntity? = rows[sessionId]

        override suspend fun sessionExists(sessionId: String): Boolean = sessionId in sessions

        override suspend fun insert(job: FinishingJobEntity) {
            check(job.sessionId !in rows) { "UNIQUE constraint failed" }
            check(job.sessionId in sessions) { "FOREIGN KEY constraint failed" }
            rows[job.sessionId] = job
        }

        override suspend fun setCapturing(sessionId: String, owner: String): Int =
            update(sessionId) { it.copy(state = CAPTURING, owner = owner) }

        override suspend fun setQueued(sessionId: String, recognizerFailed: Boolean, now: Long): Int =
            update(sessionId) { it.copy(state = QUEUED, queuedAt = now, recognizerFailed = recognizerFailed) }

        override suspend fun recoverForeign(owner: String, now: Long): Int = error("SQL only: FinishingJobDaoTest")

        override suspend fun firstQueued(): FinishingJobEntity? = rows.values
            .filter { it.state == QUEUED && !it.waitsForRecording }
            .minWithOrNull(compareBy({ it.redo }, { it.queuedAt ?: Long.MIN_VALUE }, { it.createdAt }, { it.sessionId }))

        override suspend fun setRunning(sessionId: String, owner: String) {
            update(sessionId) { it.copy(state = RUNNING, owner = owner) }
        }

        override suspend fun beginStep(sessionId: String, step: String): Int =
            update(sessionId) { it.copy(attempts = if (it.nextStep == step) it.attempts + 1 else 1, nextStep = step) }

        override suspend fun giveBackAttempt(sessionId: String) {
            update(sessionId) { it.copy(attempts = maxOf(it.attempts - 1, 0)) }
        }

        override suspend fun requeue(sessionId: String): Unit = error("SQL only: FinishingJobDaoTest")

        override suspend fun defer(sessionId: String): Unit = error("SQL only: FinishingJobDaoTest")

        override suspend fun releaseDeferred(): Int = error("SQL only: FinishingJobDaoTest")

        override suspend fun releaseDeferredJob(sessionId: String): Int = error("SQL only: FinishingJobDaoTest")

        override suspend fun delete(sessionId: String) {
            rows.remove(sessionId)
        }

        private fun update(sessionId: String, change: (FinishingJobEntity) -> FinishingJobEntity): Int {
            val row = rows[sessionId] ?: return 0
            rows[sessionId] = change(row)
            return 1
        }

        private fun rank(state: String): Int = when (state) {
            RUNNING -> 0
            QUEUED -> 1
            else -> 2
        }
    }
}
