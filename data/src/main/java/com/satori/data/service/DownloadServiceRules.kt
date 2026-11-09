package com.satori.data.service

import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason

/** What a model download's state means for the download service and the recorder's gate. */
object DownloadServiceRules {

    /** A download that runs now: bytes come in, or a file is being checked. */
    fun isRunning(state: DownloadState): Boolean =
        state is DownloadState.Downloading || state is DownloadState.Verifying

    /**
     * Whether [state] keeps the download service in the foreground: a download that runs, or one
     * held for a reason that clears by itself (a recording, the network, Wi-Fi, the server, the
     * space), so the app stays alive to go on when it does. Not one that waits for a tap (Pause,
     * a refused connection), nor a finished, failed or idle one.
     */
    fun keepsServiceRunning(state: DownloadState): Boolean = isRunning(state) ||
        (state is DownloadState.Paused && state.reason != PauseReason.USER && state.reason != PauseReason.BLOCKED)

    /** Where [state]'s download stands, when it says; null for idle, finished and failed ones. */
    fun progressOf(state: DownloadState): DownloadProgress? = when (state) {
        is DownloadState.Downloading -> state.progress
        is DownloadState.Verifying -> state.progress
        is DownloadState.Paused -> state.progress
        DownloadState.Idle, DownloadState.Completed, is DownloadState.Error -> null
    }
}
