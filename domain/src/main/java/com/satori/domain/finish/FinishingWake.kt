package com.satori.domain.finish

/**
 * A persisted JobScheduler job (FinishingWakeJob) that keeps the finishing queue going when
 * Android refuses the AI's foreground service, and after a reboot. Implemented in the app.
 */
interface FinishingWake {

    /**
     * Schedules the job, to run as soon as Android lets it. A job that is scheduled or runs is
     * kept, as JobScheduler would stop a running one that is scheduled again.
     */
    fun schedule()

    /** Cancels the job, if it is scheduled or runs. */
    fun cancel()
}
