package com.satori.domain.usecase.audio

/**
 * When [ApplyAudioRetentionUseCase] runs: once a day while the phone charges, for as long as a
 * rule for older audio is on, and once as soon as Android allows after a rule changed, so a new
 * rule applies without waiting for the night. Implemented on JobScheduler by the app, whose jobs
 * outlive the process and a reboot; nothing else (no screen, no recording) starts a run.
 */
interface AudioRetentionScheduler {

    /** The daily run is to be scheduled. One that is scheduled already keeps its time. */
    fun scheduleDaily()

    /** Both rules are off: the daily run, and one asked for at once that hasn't run yet, are cancelled. */
    fun cancel()

    /** One run as soon as Android allows. */
    fun runNow()

    /**
     * The rules are now [compressAfterDays] and [deleteAfterDays] (null: never). While either is
     * on, the daily run stays scheduled and one runs at once, so the new rule applies now; with
     * both off nothing is left to run.
     */
    fun rulesChanged(compressAfterDays: Int?, deleteAfterDays: Int?) {
        if (compressAfterDays == null && deleteAfterDays == null) {
            cancel()
        } else {
            scheduleDaily()
            runNow()
        }
    }
}
