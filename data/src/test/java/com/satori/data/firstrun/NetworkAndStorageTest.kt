package com.satori.data.firstrun

import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.SpaceShortfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The facts NetworkMonitor reads off a default network, and the storage check. */
class NetworkAndStorageTest {

    @Test
    fun `a network's facts come from its capabilities`() {
        // internet, validated, not metered, facts
        val rows = listOf(
            Triple(Triple(true, true, true), NetworkFacts.WIFI, "Wi-Fi"),
            Triple(Triple(true, true, false), NetworkFacts.MOBILE, "mobile data"),
            Triple(Triple(true, false, true), NetworkFacts(connected = true, validated = false, metered = false), "a test network or a login page"),
            // A network without internet is none, whatever else it says.
            Triple(Triple(false, true, true), NetworkFacts.NONE, "no internet")
        )
        for ((capabilities, facts, what) in rows) {
            val (internet, validated, notMetered) = capabilities
            assertEquals(what, facts, NetworkMonitor.facts(internet, validated, notMetered))
        }
    }

    @Test
    fun `a download fits when its bytes and the headroom are free`() {
        val payload = 808_000_000L
        val needed = payload + FirstRunPlan.HEADROOM_BYTES

        assertNull(StorageCheck.shortfall(freeBytes = needed, payloadBytes = payload))
        assertEquals(SpaceShortfall(needed, needed - 1), StorageCheck.shortfall(freeBytes = needed - 1, payloadBytes = payload))
        assertEquals(SpaceShortfall(needed, 600_000_000L), StorageCheck.shortfall(freeBytes = 600_000_000L, payloadBytes = payload))
    }
}
