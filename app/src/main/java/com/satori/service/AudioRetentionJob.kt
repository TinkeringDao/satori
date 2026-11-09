package com.satori.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.SystemClock
import android.util.Log
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase.Action
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase.FileDone
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The job that compresses and deletes older audio as the settings say ([JobIds.AUDIO_RETENTION_DAILY],
 * [JobIds.AUDIO_RETENTION_NOW], scheduled by [AudioRetentionSchedulerImpl]): it runs
 * [ApplyAudioRetentionUseCase] and is done. When Android stops it (the charger is pulled, its time
 * is up) the run stops after the file it is on, and the job is scheduled again. The daily and the
 * at-once job never run at the same time: the second waits for the first.
 *
 * Logs each file and the run's counts under SatoriRetention: ids, counts, MB and ms only.
 */
@AndroidEntryPoint
class AudioRetentionJob : JobService() {

    @Inject lateinit var applyAudioRetention: ApplyAudioRetentionUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The run of each job that is running, by job id.
    private val runs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val which = if (params.jobId == JobIds.AUDIO_RETENTION_NOW) "at once" else "daily"
        // Started once it is in [runs], so its end always finds its entry there.
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runLock.withLock { run(which) }
                jobFinished(params, false)
            } catch (e: CancellationException) {
                // Stopped by Android, which runs it again as onStopJob asked.
                throw e
            } catch (e: Exception) {
                // A database that can't be read, say: the next run tries again.
                Log.w(TAG, "Retention run ($which) failed", e)
                jobFinished(params, false)
            } finally {
                // Only this run's entry: a stopped job may have started again meanwhile.
                runs.remove(params.jobId, coroutineContext.job)
            }
        }
        runs[params.jobId] = run
        run.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        Log.i(TAG, "Retention run stopped by Android (reason ${params.stopReason}); it runs again later")
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(which: String) {
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "Retention run ($which) starts")
        val outcome = applyAudioRetention(onFile = ::log)
        Log.i(
            TAG,
            "Retention run ($which) done in ${SystemClock.elapsedRealtime() - started} ms: " +
                "compressed ${outcome.compressed}, audio deleted ${outcome.audioDeleted}, " +
                "recordings deleted ${outcome.recordingsDeleted}, failed ${outcome.failed}, " +
                "skipped in use ${outcome.skippedInUse}, no longer due ${outcome.noLongerDue}, " +
                "freed ${mb(outcome.freedBytes)} MB"
        )
    }

    private fun log(done: FileDone) {
        val what = when (done.action) {
            Action.COMPRESS -> "Compress"
            Action.DELETE_AUDIO -> "Delete audio"
            Action.DELETE_RECORDING -> "Delete recording"
        }
        if (done.error == null) {
            Log.i(TAG, "$what ${done.sessionId}: freed ${mb(done.freedBytes)} MB in ${done.ms} ms")
        } else {
            Log.w(TAG, "$what ${done.sessionId} failed after ${done.ms} ms; its audio stays", done.error)
        }
    }

    private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)

    private companion object {
        const val TAG = "SatoriRetention"

        // For the process, not the service: Android may create the service anew for each job.
        val runLock = Mutex()
    }
}
