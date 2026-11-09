package com.satori.presentation.sessions

import com.satori.presentation.main.ActiveRecorder.Activity
import com.satori.presentation.main.ActiveRecorder.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which recording home keeps listed as the one being made, from what the open recorder reports. */
class CapturingSessionIdTest {

    @Test
    fun `a recorder that records, or saves its last words after Stop, captures its recording`() {
        // A recorder goes idle, starting, recording, then saving.
        val capturing = Activity.entries.dropWhile { it != Activity.RECORDING }

        assertEquals(2, capturing.size)
        for (activity in capturing) {
            assertEquals(activity.name, "b", capturingSessionId(Snapshot("b", activity)))
        }
    }

    @Test
    fun `a recorder that only starts or is idle captures nothing, and neither does none`() {
        assertNull(capturingSessionId(Snapshot("b", Activity.STARTING)))
        assertNull(capturingSessionId(Snapshot("b", Activity.IDLE)))
        assertNull(capturingSessionId(null))
    }
}
