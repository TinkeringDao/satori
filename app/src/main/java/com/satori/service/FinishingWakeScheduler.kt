package com.satori.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.satori.domain.finish.FinishingWake
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FinishingWake] on JobScheduler: [FinishingWakeJob] under [JobIds.FINISHING_WAKE], persisted so
 * it outlives a reboot (RECEIVE_BOOT_COMPLETED is declared), and without constraints, so it runs as
 * soon as Android lets it.
 *
 * A job that is scheduled or runs is left as it is. JobScheduler stops a running job that is
 * scheduled again ("job rescheduled by app"), and the queue asks for the job while the job runs
 * it: FinishingWakeJob's own resume() in a process the job started, a hand-off whose service
 * start Android refused. The job is the same every time, so the one there loses nothing.
 */
@Singleton
class FinishingWakeScheduler internal constructor(private val jobs: Jobs) : FinishingWake {

    @Inject
    constructor(@ApplicationContext context: Context) : this(AndroidJobs(context))

    /** What the scheduler asks of JobScheduler about [JobIds.FINISHING_WAKE]. */
    internal interface Jobs {

        /** Whether the job is scheduled or runs; JobScheduler.getPendingJob returns it in both cases. */
        fun exists(): Boolean

        /** Schedules the job, replacing one that is scheduled and stopping one that runs. */
        fun schedule()

        /** Cancels the job, if it is scheduled or runs. */
        fun cancel()
    }

    @Synchronized
    override fun schedule() {
        if (!jobs.exists()) jobs.schedule()
    }

    @Synchronized
    override fun cancel() = jobs.cancel()
}

/** [FinishingWakeScheduler.Jobs] on Android's JobScheduler. */
private class AndroidJobs(private val context: Context) : FinishingWakeScheduler.Jobs {

    private val scheduler: JobScheduler?
        get() = context.getSystemService(JobScheduler::class.java)

    override fun exists(): Boolean = try {
        scheduler?.getPendingJob(JobIds.FINISHING_WAKE) != null
    } catch (e: RuntimeException) {
        // The job is scheduled then, which at worst replaces one.
        Log.w(TAG, "Finishing wake job not looked up", e)
        false
    }

    override fun schedule() {
        val job = JobInfo.Builder(JobIds.FINISHING_WAKE, ComponentName(context, FinishingWakeJob::class.java))
            .setPersisted(true)
            .build()
        val result = try {
            scheduler?.schedule(job)
        } catch (e: RuntimeException) {
            // Too many jobs scheduled, or the job service not declared as it must be.
            Log.w(TAG, "Finishing wake job not scheduled", e)
            return
        }
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "Finishing wake job not scheduled (result $result)")
    }

    override fun cancel() {
        scheduler?.cancel(JobIds.FINISHING_WAKE)
    }

    private companion object {
        const val TAG = "SatoriFinishing"
    }
}
