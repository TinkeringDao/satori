package com.satori.domain.firstrun

import com.satori.domain.firstrun.FirstRunPart.AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.BIGGER_AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.MALAYALAM
import com.satori.domain.firstrun.FirstRunPart.SPEECH
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private val unvalidated = NetworkFacts(connected = true, validated = false, metered = false)

    @Test
    fun `the network matrix`() {
        // network, mobile data allowed, verdict
        val rows = listOf(
            Triple(NetworkFacts.WIFI, false, NetworkVerdict.GO),
            Triple(NetworkFacts.WIFI, true, NetworkVerdict.GO),
            Triple(NetworkFacts.MOBILE, false, NetworkVerdict.WAIT_FOR_WIFI),
            Triple(NetworkFacts.MOBILE, true, NetworkVerdict.GO),
            Triple(NetworkFacts.NONE, false, NetworkVerdict.WAIT_FOR_NETWORK),
            Triple(NetworkFacts.NONE, true, NetworkVerdict.WAIT_FOR_NETWORK),
            // Not validated is tried all the same: a test server on a closed network, a login page.
            Triple(unvalidated, false, NetworkVerdict.GO)
        )
        for ((network, allowed, verdict) in rows) {
            assertEquals("$network, mobile data allowed $allowed", verdict, NetworkPolicy.verdict(network, allowed))
        }
    }

    @Test
    fun `a held download gives the reason of its verdict`() {
        assertNull(NetworkVerdict.GO.pauseReason)
        assertEquals(PauseReason.NO_NETWORK, NetworkVerdict.WAIT_FOR_NETWORK.pauseReason)
        assertEquals(PauseReason.WIFI_ONLY, NetworkVerdict.WAIT_FOR_WIFI.pauseReason)
    }

    @Test
    fun `the setup may use mobile data once Download was tapped on it`() {
        assertTrue(NetworkPolicy.mayUseMobileData(SPEECH, downloadTappedOnMobile = true, useMobileDataTapped = emptySet()))
        assertTrue(NetworkPolicy.mayUseMobileData(MALAYALAM, downloadTappedOnMobile = true, useMobileDataTapped = emptySet()))
        assertFalse(NetworkPolicy.mayUseMobileData(SPEECH, downloadTappedOnMobile = false, useMobileDataTapped = emptySet()))
    }

    @Test
    fun `the AI models never take the setup's tap, only their own`() {
        for (part in listOf(AI_MODEL, BIGGER_AI_MODEL)) {
            assertFalse(NetworkPolicy.mayUseMobileData(part, downloadTappedOnMobile = true, useMobileDataTapped = emptySet()))
            assertTrue(NetworkPolicy.mayUseMobileData(part, downloadTappedOnMobile = false, useMobileDataTapped = setOf(part)))
        }
        assertFalse(NetworkPolicy.mayUseMobileData(BIGGER_AI_MODEL, downloadTappedOnMobile = false, useMobileDataTapped = setOf(AI_MODEL)))
    }

    @Test
    fun `failed connects on a validated network read as blocked from the second in a row`() {
        val failure = DownloadFailure.NO_CONNECTION

        assertEquals(failure, NetworkPolicy.failure(failure, NetworkFacts.WIFI, connectFailures = 1))
        assertEquals(DownloadFailure.BLOCKED, NetworkPolicy.failure(failure, NetworkFacts.WIFI, connectFailures = 2))
        assertEquals(DownloadFailure.BLOCKED, NetworkPolicy.failure(failure, NetworkFacts.MOBILE, connectFailures = 9))
    }

    @Test
    fun `a firewall that drops what the app sends reads as blocked within 30 s`() {
        // The read under way stalls for 15 s (the downloads' read timeout), the retry comes 5 s
        // later and its connect fails after 5 s (their connect timeout): the second failure.
        val stalledRead = 15_000L
        val failedConnect = 5_000L
        var at = stalledRead
        var failures = 1
        assertEquals(DownloadFailure.NO_CONNECTION, NetworkPolicy.failure(DownloadFailure.NO_CONNECTION, NetworkFacts.WIFI, failures))
        while (NetworkPolicy.failure(DownloadFailure.NO_CONNECTION, NetworkFacts.WIFI, failures) != DownloadFailure.BLOCKED) {
            at += NetworkPolicy.retryDelayMs(failures) + failedConnect
            failures++
        }

        assertEquals(25_000L, at)
    }

    @Test
    fun `without a validated network a failed connect stays no connection`() {
        assertEquals(DownloadFailure.NO_CONNECTION, NetworkPolicy.failure(DownloadFailure.NO_CONNECTION, unvalidated, connectFailures = 9))
        assertEquals(DownloadFailure.NO_CONNECTION, NetworkPolicy.failure(DownloadFailure.NO_CONNECTION, NetworkFacts.NONE, connectFailures = 9))
    }

    @Test
    fun `other failures are what they are`() {
        for (failure in DownloadFailure.entries - DownloadFailure.NO_CONNECTION) {
            assertEquals(failure, NetworkPolicy.failure(failure, NetworkFacts.WIFI, connectFailures = 9))
        }
    }

    @Test
    fun `retries wait 5, 10 and 30 s, then a minute each`() {
        assertEquals(
            listOf(5_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L),
            (1..6).map(NetworkPolicy::retryDelayMs)
        )
        assertEquals(5_000L, NetworkPolicy.retryDelayMs(0))
    }
}
