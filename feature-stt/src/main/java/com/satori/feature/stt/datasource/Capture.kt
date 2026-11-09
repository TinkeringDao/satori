package com.satori.feature.stt.datasource

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One capture of a recording, from startRecording until it ends, and the microphone it records
 * from. Stop (stopRecording, on another thread) can come at any point, also while the speech model
 * loads or the microphone opens, and the capture loop can end by itself (a microphone that stopped
 * delivering, a flow that was cancelled). Whichever comes first closes the microphone, exactly once,
 * and one attached after Stop is closed at once. Each capture has its own: one that a newer
 * capture interrupted stops reading and closes its own microphone, never the newer one's.
 *
 * @param close Stops and releases a microphone
 */
internal class Capture<T : Any>(private val close: (T) -> Unit) {
    /** Stop came: the capture reads no more audio. */
    @Volatile var isStopped = false
        private set

    private val microphone = AtomicReference<T?>(null)
    private val closed = AtomicBoolean(false)

    /**
     * Gives the capture its [opened] microphone. False when Stop came first, while it opened: it
     * is closed then, and nothing is to be read from it.
     */
    fun attach(opened: T): Boolean {
        // Set before reading isStopped, as stop() sets isStopped before reading this: whichever
        // runs second sees what the other did, so one of them closes it.
        microphone.set(opened)
        if (!isStopped) return true
        end()
        return false
    }

    /** Stop: the capture reads no more, and its microphone, if it has one, is closed. */
    fun stop() {
        isStopped = true
        end()
    }

    /** Closes the microphone, unless there is none yet or it is closed already. */
    fun end() {
        val opened = microphone.get() ?: return
        if (closed.compareAndSet(false, true)) close(opened)
    }
}
