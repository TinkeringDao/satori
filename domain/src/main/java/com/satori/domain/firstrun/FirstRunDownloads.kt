package com.satori.domain.firstrun

import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import kotlinx.coroutines.flow.Flow

/**
 * The downloads of the first run's parts, as the app's download manager runs them: one speech
 * model and one AI model at a time, shared with Settings, which downloads the same way. A part's
 * state is what that download reports while it is about the part's model.
 */
interface FirstRunDownloads {

    /** Emits whenever a download's state, its progress, or the model it is about changes. */
    val changes: Flow<Unit>

    /** The state of [part]'s download; Idle while no download is about its model. */
    fun state(part: FirstRunPart): DownloadState

    /**
     * Whether another model's download runs where [part]'s would (a speech model of Settings, an
     * AI model chosen there): [part] waits for it rather than stop it.
     */
    fun busyWithOther(part: FirstRunPart): Boolean

    /**
     * Downloads [part], going on from what an earlier download of it left. Held while a recorder
     * is busy ([holdForRecording]): it starts once the recording is saved.
     */
    fun start(part: FirstRunPart)

    /** Stops [part]'s download; what it fetched stays, so [start] goes on from there. */
    fun stop(part: FirstRunPart)

    /**
     * Says that [part] is held for [reason], where its download's state shows ([DownloadState.Paused]
     * with [progress]); nothing runs.
     */
    fun hold(part: FirstRunPart, reason: PauseReason, progress: DownloadProgress?)

    /**
     * Every model download, the first run's and Settings', waits while a recorder starts, records
     * or saves ([busy]), and goes on once it doesn't: no download competes with a live capture.
     */
    fun holdForRecording(busy: Boolean)

    /**
     * Brings the download service back to the front for a download that runs without it, after
     * Android refused it in the background or took it away for the day: call it while the app is
     * in front.
     */
    fun keepInForeground()
}
