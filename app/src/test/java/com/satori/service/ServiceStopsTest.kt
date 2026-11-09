package com.satori.service

import com.satori.service.ServiceStops.ProcessingStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopsTest {

    @Test
    fun `a stop ends the recording of its own registration`() {
        assertTrue(ServiceStops.endsRecording(requested = 3, current = 3))
    }

    @Test
    fun `a late stop of an older recording leaves the newer one recording`() {
        // A stopped, its hand-off done; B started before A's stop reached the service.
        assertFalse(ServiceStops.endsRecording(requested = 3, current = 4))
    }

    @Test
    fun `a stop that names no registration doesn't end a recording`() {
        assertFalse(ServiceStops.endsRecording(requested = -1, current = 4))
    }

    @Test
    fun `a stop of the AI's service holds while no start came after it`() {
        assertTrue(ServiceStops.endsProcessing(stopGeneration = 2, lastStartGeneration = 2))
    }

    @Test
    fun `a stop that a newer start has overtaken is stale`() {
        assertFalse(ServiceStops.endsProcessing(stopGeneration = 1, lastStartGeneration = 2))
    }

    @Test
    fun `a stop that holds ends the AI's service, in the foreground or not`() {
        assertEquals(ProcessingStop.LEAVE, ServiceStops.processingStop(stopHolds = true, inForeground = true))
        assertEquals(ProcessingStop.LEAVE, ServiceStops.processingStop(stopHolds = true, inForeground = false))
    }

    @Test
    fun `a stale stop leaves the service in the foreground for the newer work`() {
        assertEquals(ProcessingStop.STAY, ServiceStops.processingStop(stopHolds = false, inForeground = true))
    }

    @Test
    fun `a stale stop ends an instance that runs no start, unless that start is on its way`() {
        // The newer START was refused, and the STOP itself created this instance: nothing else ends it.
        assertEquals(
            ProcessingStop.END_UNLESS_STARTED,
            ServiceStops.processingStop(stopHolds = false, inForeground = false)
        )
    }
}
