package com.satori.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.satori.domain.finish.FinishingQueue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The persisted job ([JobIds.FINISHING_WAKE], scheduled by [FinishingWakeScheduler]) under which
 * the finishing queue goes on when Android refuses the AI's foreground service, and after a
 * reboot: Android keeps the process up while the job runs, so the queue counts it as its
 * keep-alive. The queue takes up the jobs of a process that is gone, works while the job runs and
 * ends it once it is empty. Stopped by Android before that, the queue stops at the step under way,
 * and JobScheduler runs the job again later.
 *
 * The job runs with the app in the background, where Android refuses to start a foreground
 * service, so the queue resumes without one here. The queue may ask for the job again while it
 * runs, as its resume does in a process the job started; [FinishingWakeScheduler] then leaves it
 * running, as JobScheduler would stop a job scheduled again.
 */
@AndroidEntryPoint
class FinishingWakeJob : JobService() {

    @Inject
    lateinit var finishingQueue: FinishingQueue

    override fun onStartJob(params: JobParameters): Boolean {
        finishingQueue.resume(mayStartForeground = false)
        finishingQueue.runUnderWakeJob { jobFinished(params, false) }
        // Ongoing: the queue calls jobFinished once it is empty.
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        finishingQueue.wakeJobStopped()
        // Run again later, as the queue still holds the job that was stopped.
        return true
    }
}
