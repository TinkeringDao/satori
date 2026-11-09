package com.satori.feature.stt.datasource

import com.satori.domain.model.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [CaptureState] of a data source's captures. A capture can begin while the one before it
 * still ends (a new recording cuts the old one short), so only the newest capture moves the state:
 * the calls of an older one change nothing, and its end doesn't make the state IDLE under the
 * newer one. A capture is TRANSCRIBING once its audio came and its recognizer and VAD are ready,
 * in whichever order those happen, and LISTENING again while its speech model is changed.
 *
 * @param onChange Hears each change of the state, in order, as it is made: for a test that the
 *   state never passes through one it must not have
 */
internal class CaptureStates(private val onChange: (CaptureState) -> Unit = {}) {

    private val state = MutableStateFlow(CaptureState.IDLE)

    // Every change goes through here, inside the lock, so onChange hears them in order.
    private fun set(value: CaptureState) {
        if (state.value == value) return
        state.value = value
        onChange(value)
    }

    /** The newest capture's state; IDLE when none runs. */
    val flow: StateFlow<CaptureState> = state.asStateFlow()

    // The newest capture, or null once it has ended.
    private var current: Progress? = null

    /** A capture begins: it is the newest from now on, and OPENING. */
    @Synchronized
    fun begin(): Progress = Progress().also { progress ->
        current = progress
        set(CaptureState.OPENING)
    }

    /** One capture's way through the states, from [begin] to [end]. */
    inner class Progress internal constructor() {

        // Read without the lock first: the capture loop reports every chunk it reads.
        @Volatile private var heard = false
        private var ready = false

        /** The capture's first audio came: LISTENING, and TRANSCRIBING if it is [ready] already. */
        fun heard() {
            if (heard) return
            synchronized(this@CaptureStates) {
                heard = true
                if (current !== this) return
                set(CaptureState.LISTENING)
                if (ready) set(CaptureState.TRANSCRIBING)
            }
        }

        /** The capture's recognizer and VAD are ready: TRANSCRIBING once it has [heard] its first audio. */
        fun ready() {
            synchronized(this@CaptureStates) {
                ready = true
                if (current === this && heard) set(CaptureState.TRANSCRIBING)
            }
        }

        /**
         * The capture's speech model is changed (a switch of the recording's language): LISTENING
         * again, the microphone live and the model loading, until [ready].
         */
        fun reloading() {
            synchronized(this@CaptureStates) {
                ready = false
                if (current === this && heard) set(CaptureState.LISTENING)
            }
        }

        /** The capture's flow has ended: IDLE, unless a newer capture has begun meanwhile. */
        fun end() {
            synchronized(this@CaptureStates) {
                if (current !== this) return
                current = null
                set(CaptureState.IDLE)
            }
        }
    }
}
