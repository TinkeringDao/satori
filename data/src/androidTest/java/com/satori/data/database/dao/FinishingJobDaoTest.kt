package com.satori.data.database.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.FinishingJobEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL of [FinishingJobDao] on an in-memory database: the queue's order, taking over the jobs
 * of a process that is gone, the attempts of a step, deferring, and the job going with its
 * recording.
 */
@RunWith(AndroidJUnit4::class)
class FinishingJobDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var jobs: FinishingJobDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        jobs = db.finishingJobDao()
        runBlocking { listOf("s1", "s2", "s3", "s4").forEach { db.transcriptionSessionDao().insert(session(it)) } }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun theQueueRunsRunningThenQueuedInOrderThenCapturing() = runBlocking {
        jobs.insert(job("s1", FinishingJobDao.CAPTURING, createdAt = 1))
        jobs.insert(job("s2", FinishingJobDao.QUEUED, queuedAt = 20, createdAt = 2))
        jobs.insert(job("s3", FinishingJobDao.QUEUED, queuedAt = 10, createdAt = 3))
        jobs.insert(job("s4", FinishingJobDao.RUNNING, queuedAt = 30, createdAt = 4))

        assertEquals(listOf("s4", "s3", "s2", "s1"), jobs.observeAll().first().map { it.sessionId })
    }

    @Test
    fun aCaptureAndAHandOffWriteTheJobOnlyForARecordingThatIsThere() = runBlocking {
        jobs.captureStarted("s1", owner = "a", now = 5)
        jobs.captureStarted("gone", owner = "a", now = 5)

        assertTrue(jobs.queue("s1", recognizerFailed = true, now = 6))
        assertFalse(jobs.queue("gone", recognizerFailed = false, now = 6))

        assertEquals(job("s1", FinishingJobDao.QUEUED, owner = "a", recognizerFailed = true, queuedAt = 6, createdAt = 5), jobs.get("s1"))
        assertNull(jobs.get("gone"))
    }

    @Test
    fun theJobsOfAProcessThatIsGoneAreQueuedAndItsCaptureMarkedRecovered() = runBlocking {
        jobs.insert(job("s1", FinishingJobDao.CAPTURING, owner = "gone", createdAt = 1))
        jobs.insert(job("s2", FinishingJobDao.RUNNING, owner = "gone", nextStep = "Todos", attempts = 1, queuedAt = 5, createdAt = 2))
        jobs.insert(job("s3", FinishingJobDao.CAPTURING, owner = "me", createdAt = 3))
        jobs.insert(job("s4", FinishingJobDao.QUEUED, owner = "gone", queuedAt = 7, createdAt = 4))

        assertEquals(2, jobs.recoverForeign(owner = "me", now = 100))

        assertEquals(job("s1", FinishingJobDao.QUEUED, owner = "gone", recovered = true, queuedAt = 100, createdAt = 1), jobs.get("s1"))
        assertEquals(
            job("s2", FinishingJobDao.QUEUED, owner = "gone", nextStep = "Todos", attempts = 1, queuedAt = 5, createdAt = 2),
            jobs.get("s2")
        )
        assertEquals(FinishingJobDao.CAPTURING, jobs.get("s3")?.state)
        assertEquals(job("s4", FinishingJobDao.QUEUED, owner = "gone", queuedAt = 7, createdAt = 4), jobs.get("s4"))
    }

    @Test
    fun aStepThatBeginsAgainCountsOnAndAnotherStartsAtOne() = runBlocking {
        jobs.insert(job("s1", FinishingJobDao.RUNNING, queuedAt = 1, createdAt = 1))

        assertEquals(1, jobs.beginStep("s1", "Notes"))
        assertEquals(1, jobs.beginStep("s1", "Notes"))
        assertEquals("Notes" to 2, jobs.get("s1")?.let { it.nextStep to it.attempts })
        jobs.beginStep("s1", "Name")
        assertEquals("Name" to 1, jobs.get("s1")?.let { it.nextStep to it.attempts })
        jobs.giveBackAttempt("s1")
        jobs.giveBackAttempt("s1")
        assertEquals(0, jobs.get("s1")?.attempts)

        assertEquals(0, jobs.beginStep("gone", "Notes"))
    }

    @Test
    fun aDeferredJobIsNotClaimedUntilReleasedAndARunningOneGoesBackToItsPlace() = runBlocking {
        jobs.insert(job("s1", FinishingJobDao.QUEUED, queuedAt = 10, createdAt = 1))
        jobs.insert(job("s2", FinishingJobDao.QUEUED, queuedAt = 20, createdAt = 2))
        jobs.insert(job("s3", FinishingJobDao.CAPTURING, createdAt = 3))

        assertEquals("s1", jobs.claimNext("me")?.sessionId)
        jobs.defer("s1")
        jobs.defer("s3")
        assertEquals(FinishingJobDao.CAPTURING, jobs.get("s3")?.state)

        val second = jobs.claimNext("me")
        assertEquals("s2", second?.sessionId)
        assertEquals(FinishingJobDao.RUNNING, jobs.get("s2")?.state)
        jobs.requeue("s2")
        assertEquals(job("s2", FinishingJobDao.QUEUED, owner = "me", queuedAt = 20, createdAt = 2), jobs.get("s2"))

        assertEquals(1, jobs.releaseDeferred())
        assertEquals("s1", jobs.claimNext("me")?.sessionId)
    }

    @Test
    fun oneDeferredJobIsReleasedAloneAndTheOthersStayDeferred() = runBlocking {
        // Two jobs wait, one for the reader and one for the AI model; the reader comes in.
        jobs.insert(job("s1", FinishingJobDao.QUEUED, queuedAt = 10, createdAt = 1))
        jobs.insert(job("s2", FinishingJobDao.QUEUED, queuedAt = 20, createdAt = 2))
        jobs.defer("s1")
        jobs.defer("s2")

        assertEquals(1, jobs.releaseDeferredJob("s2"))
        // Released already, or never deferred: nothing to release.
        assertEquals(0, jobs.releaseDeferredJob("s2"))
        assertEquals(0, jobs.releaseDeferredJob("gone"))

        assertEquals("s2", jobs.claimNext("me")?.sessionId)
        assertNull(jobs.claimNext("me"))
        assertEquals(true, jobs.get("s1")?.waitsForRecording)
    }

    @Test
    fun aRecordingMadeAgainIsClaimedAfterEveryOtherJobAndShownSo() = runBlocking {
        // The backfill queued s1 and s2 again; then s3 was recorded and handed off.
        jobs.insert(job("s1", FinishingJobDao.QUEUED, nextStep = "Reading", queuedAt = 10, createdAt = 10, redo = true))
        jobs.insert(job("s2", FinishingJobDao.QUEUED, nextStep = "Reading", queuedAt = 11, createdAt = 11, redo = true))
        jobs.insert(job("s3", FinishingJobDao.QUEUED, queuedAt = 30, createdAt = 20))

        assertEquals(listOf("s3", "s1", "s2"), jobs.observeAll().first().map { it.sessionId })
        assertEquals("s3", jobs.claimNext("me")?.sessionId)
        assertEquals("s1", jobs.claimNext("me")?.sessionId)
        assertEquals("s2", jobs.claimNext("me")?.sessionId)
        assertNull(jobs.claimNext("me"))
    }

    @Test
    fun aJobGoesWithItsRecording() = runBlocking {
        jobs.insert(job("s1", FinishingJobDao.QUEUED, queuedAt = 1, createdAt = 1))

        db.mindMapDao().deleteSessionAndTidyMap("s1")

        assertNull(jobs.get("s1"))
    }

    private fun session(id: String) = TranscriptionSessionEntity(id = id, name = null, createdAt = 1_000, lastModifiedAt = 1_000)

    private fun job(
        sessionId: String,
        state: String,
        owner: String = "",
        nextStep: String? = null,
        attempts: Int = 0,
        recognizerFailed: Boolean = false,
        recovered: Boolean = false,
        queuedAt: Long? = null,
        createdAt: Long,
        redo: Boolean = false
    ) = FinishingJobEntity(
        sessionId = sessionId, state = state, nextStep = nextStep, attempts = attempts, owner = owner,
        recognizerFailed = recognizerFailed, recovered = recovered, waitsForRecording = false,
        queuedAt = queuedAt, createdAt = createdAt, redo = redo
    )
}
