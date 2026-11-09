package com.satori.presentation.main

/**
 * What Stop does once the capture has ended, its last words are saved and the speech model is
 * released, in this order:
 * 1. The silent rule: a recording in which nothing was said is deleted ([deleteIfSilent]).
 * 2. A kept recording is handed to the finisher ([handOff]), which queues it and takes its
 *    keep-alive, the AI's foreground service.
 * 3. Only then the recording service stops ([stopService]). Android lets an app in the background
 *    start a foreground service only while another one of its own runs, and a recording waiting to
 *    be finished must never be left without one. It stops also when a step fails or the plan is
 *    cancelled (the recorder closes): its wake lock and its notification must not outlive the
 *    recording.
 * 4. The recorder leaves ([leave], see [MainUiState.afterHandOff]), told whether the recording is
 *    gone. Not after a step that failed or a cancel: the recorder that closes deals with the
 *    recording itself then.
 *
 * Kept out of MainViewModel, which runs it, so that the order is tested.
 */
internal class HandOffPlan(
    private val deleteIfSilent: suspend () -> Boolean,
    private val handOff: suspend () -> Unit,
    private val stopService: () -> Unit,
    private val leave: (deleted: Boolean) -> Unit
) {
    suspend fun run() {
        val deleted: Boolean
        try {
            deleted = deleteIfSilent()
            if (!deleted) handOff()
        } finally {
            stopService()
        }
        leave(deleted)
    }
}
