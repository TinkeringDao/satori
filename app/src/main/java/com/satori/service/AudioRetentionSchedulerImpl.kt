package com.satori.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.satori.domain.usecase.audio.AudioRetentionRules
import com.satori.domain.usecase.audio.AudioRetentionScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioRetentionScheduler] on JobScheduler, both runs on [AudioRetentionJob]:
 * - [JobIds.AUDIO_RETENTION_DAILY]: every 24 hours while the phone charges, so a compression never
 *   costs battery, persisted so it outlives a reboot (RECEIVE_BOOT_COMPLETED is declared).
 * - [JobIds.AUDIO_RETENTION_NOW]: once, without constraints, so it runs as soon as Android lets it;
 *   scheduling it again replaces the one waiting.
 */
@Singleton
class AudioRetentionSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioRetentionScheduler {

    private val component get() = ComponentName(context, AudioRetentionJob::class.java)

    override fun scheduleDaily() {
        val scheduler = jobScheduler() ?: return
        // Scheduling it again would start its 24 hours anew, so a screen shown every day would
        // put the run off for ever.
        if (scheduler.getPendingJob(JobIds.AUDIO_RETENTION_DAILY) != null) return
        schedule(
            scheduler,
            JobInfo.Builder(JobIds.AUDIO_RETENTION_DAILY, component)
                .setPeriodic(AudioRetentionRules.DAY_MS)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build(),
            "Daily"
        )
    }

    override fun cancel() {
        val scheduler = jobScheduler() ?: return
        scheduler.cancel(JobIds.AUDIO_RETENTION_DAILY)
        scheduler.cancel(JobIds.AUDIO_RETENTION_NOW)
    }

    override fun runNow() {
        val scheduler = jobScheduler() ?: return
        schedule(scheduler, JobInfo.Builder(JobIds.AUDIO_RETENTION_NOW, component).build(), "At once")
    }

    private fun jobScheduler(): JobScheduler? = context.getSystemService(JobScheduler::class.java)

    private fun schedule(scheduler: JobScheduler, job: JobInfo, which: String) {
        val result = try {
            scheduler.schedule(job)
        } catch (e: RuntimeException) {
            // Too many jobs scheduled, or the job service not declared as it must be.
            Log.w(TAG, "$which retention run not scheduled", e)
            return
        }
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "$which retention run not scheduled (result $result)")
    }

    private companion object {
        const val TAG = "SatoriRetention"
    }
}

/** Binds [AudioRetentionScheduler] to [AudioRetentionSchedulerImpl], which only the app can build. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioRetentionSchedulerModule {

    @Binds
    abstract fun bindAudioRetentionScheduler(impl: AudioRetentionSchedulerImpl): AudioRetentionScheduler
}
