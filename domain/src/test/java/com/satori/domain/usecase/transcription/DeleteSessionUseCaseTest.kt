package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.finish.LoggingFinishingQueue
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recording being finished is taken out of the queue before it goes; what was kept of it goes after it. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteSessionUseCaseTest {

    private val log = mutableListOf<String>()
    private val stored = FakeTranscriptionRepository(session())
    private val recordings = object : TranscriptionRepository by stored {
        override suspend fun deleteSession(sessionId: String): Result<Unit> {
            // As Room does: the write of a caller that is cancelled doesn't begin.
            currentCoroutineContext().ensureActive()
            log += "delete $sessionId"
            return stored.deleteSession(sessionId)
        }
    }
    private val queue = LoggingFinishingQueue(log)
    private val answers = object : AnswerLog {
        override fun todoAnswer(sessionId: String, window: Int, answer: String) = Unit

        override fun forget(sessionId: String) {
            log += "forget answers $sessionId"
        }
    }
    private var cacheFailure: Exception? = null
    private val engine = object : LlmRepository by FakeLlmRepository() {
        override fun forgetPromptCache() {
            log += "forget prompt cache"
            cacheFailure?.let { throw it }
        }
    }
    private val useCase = DeleteSessionUseCase(recordings, queue, engine, answers)

    @Test
    fun `the finishing of the recording is cancelled first, then the recording is deleted, its raw to-do answers and the prompt cache after it`() = runBlocking {
        assertTrue(useCase("s1").isSuccess)

        assertEquals(listOf("cancel s1", "delete s1", "forget answers s1", "forget prompt cache"), log)
        assertNull(stored.session)
    }

    @Test
    fun `a prompt cache that can't be cleared doesn't fail the delete`() = runBlocking {
        cacheFailure = IllegalStateException("cache dir gone")

        assertTrue(useCase("s1").isSuccess)

        assertEquals(listOf("cancel s1", "delete s1", "forget answers s1", "forget prompt cache"), log)
        assertNull(stored.session)
    }

    @Test
    fun `a delete that fails is reported, after the finishing was cancelled all the same, and keeps the answers and the cache`() = runBlocking {
        stored.deleteFailure = IllegalStateException("disk full")

        assertTrue(useCase("s1").isFailure)

        assertEquals(listOf("cancel s1", "delete s1"), log)
    }

    @Test
    fun `a delete whose caller goes away while the finishing step ends still deletes the recording`() = runTest {
        val ending = CompletableDeferred<Unit>()
        queue.cancelling = ending

        val caller = launch { useCase("s1") }
        runCurrent()
        // The owner leaves the page while the step's model load ends.
        caller.cancel()
        ending.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("cancel s1", "delete s1", "forget answers s1", "forget prompt cache"), log)
        assertNull(stored.session)
    }

    @Test
    fun `a delete that fails gives a recording that was being finished back to the queue`() = runBlocking {
        queue.statuses.value = listOf(FinishingStatus("s1", FinishingStatus.Phase.RUNNING, FinishStep.Notes))
        stored.deleteFailure = IllegalStateException("disk full")

        assertTrue(useCase("s1").isFailure)

        assertEquals(listOf("cancel s1", "delete s1", "hand-off s1"), log)
    }
}
