package com.satori.presentation.mindmap

import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map's AI work gives way at its safe points to a recorder that leaves IDLE, and remembers it. */
class MapYieldTest {

    private val recorders = ActiveRecorder()
    private val recorder = Any()
    private val finishing = MutableStateFlow(false)
    private val giveWay = MapYield(recorders.current, finishing)

    @Test
    fun `it goes on while no recorder captures, also beside an open recorder that stays idle`() {
        assertFalse(giveWay.olderRun())
        recorders.open(recorder, "r", ActiveRecorder.Activity.IDLE)

        assertFalse(giveWay.olderRun())
        assertFalse(giveWay.toRecorder())
        assertFalse(giveWay.forRecording)
    }

    @Test
    fun `a recorder that leaves IDLE mid-run makes it give way at the next recording or summary, which is remembered`() {
        recorders.open(recorder, "r", ActiveRecorder.Activity.IDLE)
        // Between the first and the second recording of a run.
        assertFalse(giveWay.olderRun())

        recorders.report(recorder, "r", ActiveRecorder.Activity.STARTING)

        // Between the second and the third.
        assertTrue(giveWay.olderRun())
        assertTrue(giveWay.forRecording)
    }

    @Test
    fun `starting, recording and saving each make it give way`() {
        for (activity in listOf(ActiveRecorder.Activity.STARTING, ActiveRecorder.Activity.RECORDING, ActiveRecorder.Activity.SAVING)) {
            val fresh = ActiveRecorder()
            val yields = MapYield(fresh.current, finishing)
            fresh.open(recorder, "r", activity)

            assertTrue(activity.name, yields.toRecorder())
            assertTrue(activity.name, yields.forRecording)
        }
    }

    @Test
    fun `a recording waiting to be finished makes only a run of the older recordings give way, and not for a recorder`() {
        finishing.value = true

        assertTrue(giveWay.olderRun())
        assertFalse(giveWay.toRecorder())
        assertFalse(giveWay.forRecording)
    }

    @Test
    fun `a recorder that made it give way stays remembered once it is idle again`() {
        recorders.expect("r")
        assertTrue(giveWay.toRecorder())

        recorders.cancelStart("r")

        assertFalse(giveWay.toRecorder())
        assertTrue(giveWay.forRecording)
    }
}
