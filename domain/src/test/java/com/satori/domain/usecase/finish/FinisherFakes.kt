package com.satori.domain.usecase.finish

import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// The doubles of Part A's domain tests: the memory margin of a switch, and the finishing queue.

/**
 * [TwoModelLlmRepository] that measures the memory a switch with a margin asks for: Gemma 4 E2B
 * fits beside at most [roomBytes] of margin (below nothing, that much is missing even for its
 * file), and a larger one fails with [NotEnoughMemoryException] and leaves no model in memory.
 * [margins] are the margins asked for, in call order, and [onMeasure] runs as each is measured. A
 * switch without one goes to the two models as before, unless [measuresPlainSwitch]: then it is
 * measured as a margin of 0, as the app's repository measures it.
 */
class RoomLlmRepository(
    private val models: TwoModelLlmRepository,
    var roomBytes: Long = Long.MAX_VALUE,
    private val measuresPlainSwitch: Boolean = false
) : LlmRepository by models {
    val margins = mutableListOf<Long>()
    var onMeasure: () -> Unit = {}

    override suspend fun switchModel(modelPath: String): Result<Unit> =
        if (measuresPlainSwitch) switchModel(modelPath, 0L) else models.switchModel(modelPath)

    override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> {
        margins += extraFreeBytes
        onMeasure()
        if (extraFreeBytes <= roomBytes) return models.switchModel(modelPath)
        models.log += "no room for $modelPath"
        models.cleanup()
        return Result.failure(NotEnoughMemoryException(availableBytes = FILE_BYTES + roomBytes, neededBytes = FILE_BYTES + extraFreeBytes))
    }

    private companion object {
        const val FILE_BYTES = 3_000_000_000L
    }
}

/**
 * A finishing queue that logs what it is asked to [log]; nothing else is asked of it. A cancel
 * waits for [cancelling] while it is set, as one whose step has a model load to end first, and
 * runs to the end as the queue's own does.
 */
class LoggingFinishingQueue(private val log: MutableList<String>) : FinishingQueue {
    override val statuses = MutableStateFlow(emptyList<FinishingStatus>())
    override val busy = MutableStateFlow(false)
    var cancelling: CompletableDeferred<Unit>? = null

    override suspend fun cancel(sessionId: String) {
        log += "cancel $sessionId"
        cancelling?.let { ending -> withContext(NonCancellable) { ending.await() } }
    }

    override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) {
        log += "hand-off $sessionId"
    }

    override suspend fun captureStarted(sessionId: String): Unit = unused()
    override fun skipBetterTranscript(sessionId: String): Unit = unused()
    override fun resume(mayStartForeground: Boolean): Unit = unused()
    override fun runUnderWakeJob(done: () -> Unit): Unit = unused()
    override fun wakeJobStopped(): Unit = unused()

    private fun unused(): Nothing = error("not used by these tests")
}
