package com.satori.presentation.mindmap

import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.capturing
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a piece of the mind map's AI work gives way ([MindMapViewModel]): only at its safe points,
 * before it begins and between two recordings or two summaries, never inside a model call, so what
 * is mapped stays and the AI is free within one model call. It gives way to a recorder that has
 * left IDLE (the recorder wins), and a run of the older recordings also to a recording waiting to
 * be finished, which then gets the AI. It remembers whether a recorder made it give way, which the
 * view says once.
 *
 * @param recorder The open recorder ([ActiveRecorder.current])
 * @param finishingBusy Whether a recording waits to be finished or is being finished
 */
internal class MapYield(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val finishingBusy: StateFlow<Boolean>
) {
    /** Whether a recorder that left IDLE made the work give way. */
    var forRecording = false
        private set

    /** Whether the work gives way to a recorder here: one has left IDLE. A yes is remembered. */
    fun toRecorder(): Boolean = recorder.value.capturing.also { if (it) forRecording = true }

    /**
     * A recorder that left IDLE stopped the work where it doesn't wait for a safe point: the
     * readings it makes before its model loads (0.8.0). Remembered as [toRecorder] remembers a yes,
     * also when that recorder is IDLE again by now.
     */
    fun stoppedByRecorder() {
        forRecording = true
    }

    /**
     * Whether a run of the older recordings gives way here: to a recorder, or to a recording
     * waiting to be finished.
     */
    fun olderRun(): Boolean = toRecorder() || finishingBusy.value
}
