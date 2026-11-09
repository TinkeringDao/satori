package com.satori.domain.firstrun

import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.PauseReason

/**
 * The default network as Android reports it, from the default network's capabilities (VALIDATED,
 * NOT_METERED), never from its transport: with an always-on VPN the default network is the VPN,
 * whatever carries it, and a check for Wi-Fi would misread it.
 *
 * @property connected Whether there is a default network at all
 * @property validated Whether Android found that it reaches the internet
 * @property metered Whether data on it may cost money: mobile data, a hotspot
 */
data class NetworkFacts(val connected: Boolean, val validated: Boolean, val metered: Boolean) {
    companion object {
        val NONE = NetworkFacts(connected = false, validated = false, metered = false)
        val WIFI = NetworkFacts(connected = true, validated = true, metered = false)
        val MOBILE = NetworkFacts(connected = true, validated = true, metered = true)
    }
}

/** Whether a download may run on the network there is ([NetworkPolicy.verdict]). */
enum class NetworkVerdict {
    GO,

    /** No network: wait for one, trying again now and then. */
    WAIT_FOR_NETWORK,

    /** Only a metered network, which this download may not use. */
    WAIT_FOR_WIFI;

    /** The reason a download held for this verdict gives; null for [GO]. */
    val pauseReason: PauseReason?
        get() = when (this) {
            GO -> null
            WAIT_FOR_NETWORK -> PauseReason.NO_NETWORK
            WAIT_FOR_WIFI -> PauseReason.WIFI_ONLY
        }
}

/**
 * Which network may carry which download, and how the first run tells a firewall from no signal.
 * Downloads go over Wi-Fi only; mobile data only after a tap on a button that showed the size.
 */
object NetworkPolicy {

    /**
     * Connection failures in a row, without a byte between them, on a network Android validated
     * after which they read as [DownloadFailure.BLOCKED], not as no connection. Two: a firewall
     * that drops what the app sends makes the read under way stall (15 s), then the retry 5 s
     * later fail to connect (5 s), so the screen says the app is blocked within 30 s (design C4)
     * instead of a minute; a network that is truly gone loses its validation, and its failures
     * stay no connection.
     */
    const val BLOCKED_AFTER = 2

    /**
     * How long a part waits after the server answered with an error, whatever the attempt:
     * "Satori tries again in a few minutes". A server that fails now mostly still fails in a
     * minute, and asking it every few seconds helps no one.
     */
    const val SERVER_RETRY_MS = 180_000L

    /** The waits before the first retries; after the last, every minute. */
    private val RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

    /**
     * Whether a download may run on [network] now. A network Android hasn't validated is tried
     * all the same: a test server on a closed network is never validated, and a login page instead
     * of the file fails the download's length check, which reads as no connection.
     *
     * @param mobileDataAllowed Whether this download may use a metered network ([mayUseMobileData])
     */
    fun verdict(network: NetworkFacts, mobileDataAllowed: Boolean): NetworkVerdict = when {
        !network.connected -> NetworkVerdict.WAIT_FOR_NETWORK
        network.metered && !mobileDataAllowed -> NetworkVerdict.WAIT_FOR_WIFI
        else -> NetworkVerdict.GO
    }

    /**
     * Whether [part] may use mobile data: only after a tap on a button that showed its size. Get
     * ready's Download, tapped on mobile data ([downloadTappedOnMobile]), is that tap for the setup;
     * Use mobile data is that tap for the part it was tapped for ([useMobileDataTapped]). The AI
     * models never take the setup's tap.
     */
    fun mayUseMobileData(
        part: FirstRunPart,
        downloadTappedOnMobile: Boolean,
        useMobileDataTapped: Set<FirstRunPart>
    ): Boolean = part in useMobileDataTapped || (part.beforeFirstRecording && downloadTappedOnMobile)

    /**
     * What a [failure] means on [network]: no connection on a validated network, [connectFailures]
     * times in a row, is an app that is not allowed out ([DownloadFailure.BLOCKED]). Android can't
     * tell a firewall from no signal, but it can tell that the phone itself is online.
     */
    fun failure(failure: DownloadFailure, network: NetworkFacts, connectFailures: Int): DownloadFailure =
        if (failure == DownloadFailure.NO_CONNECTION && network.validated && connectFailures >= BLOCKED_AFTER) {
            DownloadFailure.BLOCKED
        } else {
            failure
        }

    /**
     * How long to wait before retry [attempt] after a connection failure (1 for the first): 5 s,
     * 10 s, 30 s, then every minute. A network Android reports in the meantime retries at once.
     */
    fun retryDelayMs(attempt: Int): Long = RETRY_DELAYS_MS[(attempt - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
}
