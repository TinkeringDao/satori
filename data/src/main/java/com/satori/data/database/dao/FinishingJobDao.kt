package com.satori.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.satori.data.database.entity.FinishingJobEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the finishing queue (finishing_jobs, see the domain `FinishingJobRepository`). Each
 * function is one statement, or one transaction where it says so, so the queue a process leaves
 * behind is always one it wrote whole.
 *
 * States and steps are stored by name: [CAPTURING], [QUEUED] and [RUNNING] are the domain
 * `FinishingJobState` names, a step the domain `FinishStep` name.
 */
@Dao
abstract class FinishingJobDao {

    /**
     * Every job: running ones first, then the queued ones in queue order ([firstQueued]'s: the
     * recordings made again after the others, then by queued_at), then the capturing ones; ties by
     * created_at, then by session_id, so the order never wavers.
     */
    @Query(
        """
        SELECT * FROM finishing_jobs
        ORDER  BY CASE state WHEN '$RUNNING' THEN 0 WHEN '$QUEUED' THEN 1 ELSE 2 END,
                  redo, queued_at, created_at, session_id
        """
    )
    abstract fun observeAll(): Flow<List<FinishingJobEntity>>

    @Query("SELECT * FROM finishing_jobs WHERE session_id = :sessionId")
    abstract suspend fun get(sessionId: String): FinishingJobEntity?

    /** Part of [captureStarted] and [queue]. */
    @Query("SELECT EXISTS(SELECT 1 FROM transcription_sessions WHERE id = :sessionId)")
    abstract suspend fun sessionExists(sessionId: String): Boolean

    /** Part of [captureStarted] and [queue], which insert only a job that isn't there. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(job: FinishingJobEntity)

    /**
     * Part of [captureStarted].
     *
     * @return 1 if the job was there, 0 if not
     */
    @Query("UPDATE finishing_jobs SET state = '$CAPTURING', owner = :owner WHERE session_id = :sessionId")
    abstract suspend fun setCapturing(sessionId: String, owner: String): Int

    /**
     * The capture of [sessionId] has started in the process [owner], in one transaction: its job
     * is set to capturing for this owner, the rest of the row as it was; or a new job is written,
     * capturing from [now]. Nothing is written for a recording that is gone, where the new row
     * would fail on its foreign key.
     */
    @Transaction
    open suspend fun captureStarted(sessionId: String, owner: String, now: Long) {
        if (setCapturing(sessionId, owner) > 0 || !sessionExists(sessionId)) return
        insert(
            FinishingJobEntity(
                sessionId = sessionId,
                state = CAPTURING,
                nextStep = null,
                attempts = 0,
                owner = owner,
                recognizerFailed = false,
                recovered = false,
                waitsForRecording = false,
                queuedAt = null,
                createdAt = now
            )
        )
    }

    /**
     * Part of [queue].
     *
     * @return 1 if the job was there, 0 if not
     */
    @Query(
        """
        UPDATE finishing_jobs
        SET    state = '$QUEUED', queued_at = :now, recognizer_failed = :recognizerFailed
        WHERE  session_id = :sessionId
        """
    )
    abstract suspend fun setQueued(sessionId: String, recognizerFailed: Boolean, now: Long): Int

    /**
     * The last words of [sessionId] are saved, in one transaction: its job is queued at [now], or
     * written new, queued and without an owner ([NO_OWNER]), when its capture left none.
     *
     * @return false when the recording is gone, and nothing was written
     */
    @Transaction
    open suspend fun queue(sessionId: String, recognizerFailed: Boolean, now: Long): Boolean {
        if (setQueued(sessionId, recognizerFailed, now) > 0) return true
        if (!sessionExists(sessionId)) return false
        insert(
            FinishingJobEntity(
                sessionId = sessionId,
                state = QUEUED,
                nextStep = null,
                attempts = 0,
                owner = NO_OWNER,
                recognizerFailed = recognizerFailed,
                recovered = false,
                waitsForRecording = false,
                queuedAt = now,
                createdAt = now
            )
        )
        return true
    }

    /**
     * The finished recording [sessionId] is queued again for its AI steps from the step [fromStep]
     * (a `FinishStep` name, the Reading step's), the backfill of readings's job (0.8.0, design R9),
     * in one transaction: a new job, queued at [now], without an owner ([NO_OWNER]), at that step
     * with no attempt counted, and redo set. Nothing is written for a recording that has a job (its
     * capture runs, or it waits or is being finished) or that is gone.
     *
     * @return whether it was queued
     */
    @Transaction
    open suspend fun queueRedo(sessionId: String, fromStep: String, now: Long): Boolean {
        if (get(sessionId) != null || !sessionExists(sessionId)) return false
        insert(
            FinishingJobEntity(
                sessionId = sessionId,
                state = QUEUED,
                nextStep = fromStep,
                attempts = 0,
                owner = NO_OWNER,
                recognizerFailed = false,
                recovered = false,
                waitsForRecording = false,
                queuedAt = now,
                createdAt = now,
                redo = true
            )
        )
        return true
    }

    /**
     * The jobs that a process that is gone left capturing or running (another owner than [owner])
     * are queued; a capturing one is marked recovered, as its capture ended without a hand-off,
     * and one without a place in the queue gets [now]. SQLite reads every right-hand side from
     * the row as it was, so the CASE sees the old state.
     *
     * @return How many jobs it queued
     */
    @Query(
        """
        UPDATE finishing_jobs
        SET    recovered = CASE WHEN state = '$CAPTURING' THEN 1 ELSE recovered END,
               state = '$QUEUED',
               queued_at = COALESCE(queued_at, :now)
        WHERE  state IN ('$CAPTURING', '$RUNNING') AND owner != :owner
        """
    )
    abstract suspend fun recoverForeign(owner: String, now: Long): Int

    /**
     * Part of [claimNext]: the first queued job that doesn't wait for a recording to end. A recording
     * made again over its readings (redo, the backfill's) comes after every other: nobody waits for
     * its notes, and a recording made after the backfill queued it gets its own first.
     */
    @Query(
        """
        SELECT * FROM finishing_jobs
        WHERE  state = '$QUEUED' AND waits_for_recording = 0
        ORDER  BY redo, queued_at, created_at, session_id
        LIMIT  1
        """
    )
    abstract suspend fun firstQueued(): FinishingJobEntity?

    /** Part of [claimNext]. */
    @Query("UPDATE finishing_jobs SET state = '$RUNNING', owner = :owner WHERE session_id = :sessionId")
    abstract suspend fun setRunning(sessionId: String, owner: String)

    /**
     * The first queued job that doesn't wait for a recording to end, set running for [owner], in
     * one transaction, so two claims never take the same job.
     *
     * @return The job as it is now, or null when there is none
     */
    @Transaction
    open suspend fun claimNext(owner: String): FinishingJobEntity? {
        val next = firstQueued() ?: return null
        setRunning(next.sessionId, owner)
        return next.copy(state = RUNNING, owner = owner)
    }

    /**
     * [step] of the job of [sessionId] begins: attempts counts on when the step that began last
     * was this one, and starts again at 1 when it was another or none (NULL compares as false).
     *
     * @return 1, or 0 when the job is gone
     */
    @Query(
        """
        UPDATE finishing_jobs
        SET    attempts = CASE WHEN next_step = :step THEN attempts + 1 ELSE 1 END,
               next_step = :step
        WHERE  session_id = :sessionId
        """
    )
    abstract suspend fun beginStep(sessionId: String, step: String): Int

    /** The step that began last doesn't count as an attempt; never below 0. */
    @Query("UPDATE finishing_jobs SET attempts = MAX(attempts - 1, 0) WHERE session_id = :sessionId")
    abstract suspend fun giveBackAttempt(sessionId: String)

    /** A running job goes back to the queue; its place (queued_at) stays. */
    @Query("UPDATE finishing_jobs SET state = '$QUEUED' WHERE session_id = :sessionId AND state = '$RUNNING'")
    abstract suspend fun requeue(sessionId: String)

    /** A queued or running job waits in the queue until no capture is live; a capturing one is left alone. */
    @Query(
        """
        UPDATE finishing_jobs SET state = '$QUEUED', waits_for_recording = 1
        WHERE  session_id = :sessionId AND state IN ('$QUEUED', '$RUNNING')
        """
    )
    abstract suspend fun defer(sessionId: String)

    /**
     * Every job that waited for a recording to end may be claimed again.
     *
     * @return How many there were
     */
    @Query("UPDATE finishing_jobs SET waits_for_recording = 0 WHERE waits_for_recording = 1")
    abstract suspend fun releaseDeferred(): Int

    /**
     * The job of [sessionId], if deferred, may be claimed again; the other deferred jobs stay so.
     *
     * @return 1 if it was deferred, 0 if not
     */
    @Query("UPDATE finishing_jobs SET waits_for_recording = 0 WHERE session_id = :sessionId AND waits_for_recording = 1")
    abstract suspend fun releaseDeferredJob(sessionId: String): Int

    @Query("DELETE FROM finishing_jobs WHERE session_id = :sessionId")
    abstract suspend fun delete(sessionId: String)

    companion object {
        /** finishing_jobs.state of a job whose capture runs (the domain `FinishingJobState.CAPTURING`). */
        const val CAPTURING = "CAPTURING"

        /** finishing_jobs.state of a job in the queue (the domain `FinishingJobState.QUEUED`). */
        const val QUEUED = "QUEUED"

        /** finishing_jobs.state of a job being finished (the domain `FinishingJobState.RUNNING`). */
        const val RUNNING = "RUNNING"

        /** finishing_jobs.owner of a job queued without a capture row before it: no process wrote it. */
        const val NO_OWNER = ""
    }
}
