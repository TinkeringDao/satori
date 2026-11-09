package com.satori.domain.finish

import kotlinx.coroutines.flow.StateFlow

/**
 * The background queue that finishes recordings after Stop, one at a time, while the owner may
 * already record the next one: the notes, the name, the to-dos and the map of each. Implemented by
 * RecordingFinisher. Main-safe, and no call throws.
 */
interface FinishingQueue {

    /** The recordings to be finished, in queue order; a capture that still runs is not among them. */
    val statuses: StateFlow<List<FinishingStatus>>

    /** Any recording waits, waits for a recording to end, or is being finished. */
    val busy: StateFlow<Boolean>

    /** The capture of [sessionId] has started: its job is stored, so a capture cut off is still finished. */
    suspend fun captureStarted(sessionId: String)

    /**
     * The last words are saved: the job is queued and the processing holder
     * [ProcessingHolders.finishing] of [sessionId] taken. Call before stopping RecordingService,
     * so the process stays in the foreground.
     */
    suspend fun handOff(sessionId: String, recognizerFailed: Boolean)

    /**
     * The finished recording [sessionId] is queued again from its Reading step, its AI steps made
     * again over its readings ([FinishingJob.redo]; 0.8.0, design R9: the backfill of readings). Its
     * job runs in its place in the queue under the queue's rules, as a hand-off's does: never beside
     * a capture, its reading stopped by one that opens. Nothing is queued for a recording that has a
     * job. The default queues nothing.
     *
     * @return whether it was queued
     */
    suspend fun redo(sessionId: String): Boolean = false

    /**
     * A waiting job is removed; a running one is cancelled and joined first. Either way its row and
     * its holder are gone when this returns.
     */
    suspend fun cancel(sessionId: String)

    /** The owner doesn't wait for the better transcript of [sessionId]; nothing happens when none runs. */
    fun skipBetterTranscript(sessionId: String)

    /**
     * Takes up the jobs a process that is gone left behind. Once per process: later calls do
     * nothing.
     *
     * @param mayStartForeground False where Android bars starting a foreground service, as for
     *   BOOT_COMPLETED: the queue then waits for FinishingWakeJob
     */
    fun resume(mayStartForeground: Boolean)

    /** FinishingWakeJob runs: the queue may work under it, and calls [done] once it is empty. */
    fun runUnderWakeJob(done: () -> Unit)

    /** Android stops FinishingWakeJob: the queue stops at the current step, which runs again later. */
    fun wakeJobStopped()
}
