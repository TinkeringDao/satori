package com.satori.data.repository

import com.satori.data.database.dao.FinishingJobDao
import com.satori.data.database.mapper.toDomain
import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.usecase.finish.FinishStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [FinishingJobRepository] on Room ([FinishingJobDao]). Each call returns once its write is
 * stored; a step is stored by its name.
 */
class FinishingJobRepositoryImpl @Inject constructor(
    private val dao: FinishingJobDao
) : FinishingJobRepository {

    // Room re-runs the query on every write to the table, such as each step that begins; drop
    // the results that stayed the same.
    override fun observeAll(): Flow<List<FinishingJob>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun get(sessionId: String): FinishingJob? = dao.get(sessionId)?.toDomain()

    override suspend fun captureStarted(sessionId: String, owner: String, now: Long) {
        dao.captureStarted(sessionId, owner, now)
    }

    override suspend fun queue(sessionId: String, recognizerFailed: Boolean, now: Long): Boolean =
        dao.queue(sessionId, recognizerFailed, now)

    override suspend fun recoverForeign(owner: String, now: Long): Int = dao.recoverForeign(owner, now)

    override suspend fun claimNext(owner: String): FinishingJob? = dao.claimNext(owner)?.toDomain()

    override suspend fun beginStep(sessionId: String, step: FinishStep): Boolean = dao.beginStep(sessionId, step.name) > 0

    override suspend fun giveBackAttempt(sessionId: String) {
        dao.giveBackAttempt(sessionId)
    }

    override suspend fun requeue(sessionId: String) {
        dao.requeue(sessionId)
    }

    override suspend fun defer(sessionId: String) {
        dao.defer(sessionId)
    }

    override suspend fun releaseDeferred(): Int = dao.releaseDeferred()

    override suspend fun releaseDeferred(sessionId: String): Boolean = dao.releaseDeferredJob(sessionId) > 0

    /** One transaction ([FinishingJobDao.queueRedo]), at the Reading step. */
    override suspend fun queueRedo(sessionId: String, now: Long): Boolean = dao.queueRedo(sessionId, FinishStep.Reading.name, now)

    override suspend fun delete(sessionId: String) {
        dao.delete(sessionId)
    }
}
