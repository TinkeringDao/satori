package com.satori.service

/**
 * The ids of Satori's JobScheduler jobs, one per job. A persisted job outlives an update of the
 * app, so an id is never given to another job.
 */
object JobIds {

    /** FinishingWakeJob: keeps the finishing of recordings going without the AI's foreground service. */
    const val FINISHING_WAKE = 41001

    /** AudioRetentionJob, once a day while the phone charges. */
    const val AUDIO_RETENTION_DAILY = 41002

    /** AudioRetentionJob, once, after a setting for older audio changed. */
    const val AUDIO_RETENTION_NOW = 41003
}
