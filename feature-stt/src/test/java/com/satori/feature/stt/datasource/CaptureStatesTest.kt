package com.satori.feature.stt.datasource

import com.satori.domain.model.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStatesTest {

    private val states = CaptureStates()

    /** Every state the flow holds, as each step of a capture leaves it. */
    private val seen = mutableListOf<CaptureState>()

    private fun record() {
        seen += states.flow.value
    }

    @Test
    fun `no capture is idle`() {
        assertEquals(CaptureState.IDLE, states.flow.value)
    }

    @Test
    fun `a capture whose recognizer is ready before the microphone opens transcribes at its first audio`() {
        val capture = states.begin().also { record() }

        capture.ready().also { record() }
        capture.heard().also { record() }
        capture.heard().also { record() }
        capture.end().also { record() }

        assertEquals(
            listOf(CaptureState.OPENING, CaptureState.OPENING, CaptureState.TRANSCRIBING, CaptureState.TRANSCRIBING, CaptureState.IDLE),
            seen
        )
    }

    @Test
    fun `a capture that hears before its recognizer is ready listens until it is`() {
        val capture = states.begin().also { record() }

        capture.heard().also { record() }
        capture.ready().also { record() }
        capture.end().also { record() }

        assertEquals(listOf(CaptureState.OPENING, CaptureState.LISTENING, CaptureState.TRANSCRIBING, CaptureState.IDLE), seen)
    }

    @Test
    fun `a capture whose speech model changes listens again until the new one is in`() {
        val capture = states.begin()
        capture.heard()
        capture.ready()

        capture.reloading().also { record() }
        capture.ready().also { record() }

        assertEquals(listOf(CaptureState.LISTENING, CaptureState.TRANSCRIBING), seen)
    }

    @Test
    fun `an old capture whose model changes after a newer one began changes nothing`() {
        val old = states.begin()
        old.heard()
        old.ready()
        val new = states.begin()
        new.heard()
        new.ready()

        old.reloading()

        assertEquals(CaptureState.TRANSCRIBING, states.flow.value)
    }

    @Test
    fun `a capture that ends before it heard anything goes back to idle`() {
        val capture = states.begin()
        capture.ready()

        capture.end()

        assertEquals(CaptureState.IDLE, states.flow.value)
    }

    @Test
    fun `a capture cut short by a newer one leaves the state to that one`() {
        val old = states.begin()
        old.ready()
        old.heard()
        val new = states.begin()

        old.end()
        assertEquals(CaptureState.OPENING, states.flow.value)
        old.heard()
        old.ready()
        assertEquals(CaptureState.OPENING, states.flow.value)

        new.ready()
        new.heard()
        assertEquals(CaptureState.TRANSCRIBING, states.flow.value)
        new.end()
        assertEquals(CaptureState.IDLE, states.flow.value)
    }

    @Test
    fun `an old capture that hears after a newer one began changes nothing`() {
        val old = states.begin()
        old.ready()
        states.begin()

        old.heard()

        assertEquals(CaptureState.OPENING, states.flow.value)
    }
}
