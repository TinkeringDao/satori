package com.satori.domain.finish

import com.satori.domain.usecase.finish.FinishStep
import kotlinx.coroutines.flow.Flow

/**
 * The finishing queue as stored (table finishing_jobs): one [FinishingJob] per recording, deleted
 * with its recording. Each call returns once its write is stored, so the queue a process leaves
 * behind is the one the next process finds.
 */
interface FinishingJobRepository {

    /**
     * Every job: the running ones first, then the queued ones in queue order ([FinishingJob.queuedAt]),
     * then the capturing ones; ties by [FinishingJob.createdAt].
     */
    fun observeAll(): Flow<List<FinishingJob>>

    suspend fun get(sessionId: String): FinishingJob?

    /**
     * The capture of [sessionId] has started in the process [owner]: a new job in
     * [FinishingJobState.CAPTURING], or the job of the recording set to it with this owner. Nothing
     * is written for a recording that is gone.
     */
    suspend fun captureStarted(sessionId: String, owner: String, now: Long)

    /**
     * The last words of [sessionId] are saved: its job is [FinishingJobState.QUEUED] with
     * [FinishingJob.queuedAt] = [now], written new if there was none.
     *
     * @return false when the recording is gone, and nothing was written
     */
    suspend fun queue(sessionId: String, recognizerFailed: Boolean, now: Long): Boolean

    /**
     * Takes over the jobs a process that is gone left capturing or running (those of another
     * owner than [owner]): they are queued, a capturing one marked [FinishingJob.recovered], and
     * one without a place in the queue gets [now].
     *
     * @return How many jobs it took over
     */
    suspend fun recoverForeign(owner: String, now: Long): Int

    /**
     * The first queued job that doesn't wait for a recording to end, now
     * [FinishingJobState.RUNNING] for [owner]; null when there is none.
     */
    suspend fun claimNext(owner: String): FinishingJob?

    /**
     * [step] of the job of [sessionId] begins: it becomes [FinishingJob.nextStep], and
     * [FinishingJob.attempts] counts on if it was that step already, or starts again at 1.
     *
     * @return false when the job is gone (its recording was deleted, or it was cancelled)
     */
    suspend fun beginStep(sessionId: String, step: FinishStep): Boolean

    /** The step that began last didn't count as an attempt (it was stopped from outside); never below 0. */
    suspend fun giveBackAttempt(sessionId: String)

    /** A running job goes back to the queue, at its old place. */
    suspend fun requeue(sessionId: String)

    /** The job waits in the queue until no capture is live ([releaseDeferred]). */
    suspend fun defer(sessionId: String)

    /**
     * Every job that waited for a recording to end may be taken again.
     *
     * @return How many there were
     */
    suspend fun releaseDeferred(): Int

    /**
     * The job of [sessionId] alone, deferred, may be taken again ([defer]), the other deferred jobs
     * left as they are: one whose wait for the reader is over while others still wait.
     *
     * The default lets every deferred job go, as [releaseDeferred] does, for a repository that can't
     * tell them apart.
     *
     * @return Whether it was deferred
     */
    suspend fun releaseDeferred(sessionId: String): Boolean = releaseDeferred() > 0

    /**
     * The finished recording [sessionId] is queued again, [FinishingJob.redo], from its Reading step
     * (0.8.0, design R9: the backfill of readings), at [now], with no attempt counted. Nothing is
     * written for a recording that has a job, its capture's or one that waits or runs, or that is
     * gone. The default writes nothing, for a repository that keeps no queue.
     *
     * @return whether it was queued
     */
    suspend fun queueRedo(sessionId: String, now: Long): Boolean = false

    suspend fun delete(sessionId: String)
}
