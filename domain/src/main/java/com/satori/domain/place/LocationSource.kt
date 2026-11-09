package com.satori.domain.place

/** Where Android gets a position from. */
enum class LocationProvider {
    /** Satellites: needs no network, so it also works in airplane mode. */
    GPS,

    /** Mobile and Wi-Fi networks, usually less accurate. */
    NETWORK,

    /** No source of its own: the positions any app asked the others for. */
    PASSIVE
}

/**
 * A position the phone reported.
 *
 * @property accuracyMeters How far off it may be, in metres, as Android reports a fix's horizontal
 *   accuracy; null when it gave none
 * @property elapsedRealtimeNanos When it was taken, on the clock of
 *   [LocationSource.elapsedRealtimeNanos], which keeps counting while the phone sleeps (unlike
 *   wall-clock time, which a time zone or clock change moves)
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val elapsedRealtimeNanos: Long
)

/** The phone's positions, as the app may read them. */
interface LocationSource {

    /** Whether the app may read the location now, precisely or only approximately. */
    fun isAllowed(): Boolean

    /** Now, on the clock of [LocationFix.elapsedRealtimeNanos]. */
    fun elapsedRealtimeNanos(): Long

    /**
     * The last position [provider] has, without waiting: null when it has none, is off or
     * missing, or the app may not read it.
     */
    fun lastFix(provider: LocationProvider): LocationFix?

    /**
     * A new position from [provider]. Suspends until one comes; null at once when the provider is
     * off or missing or the app may not read it, and null when the provider gives up. Cancelling
     * the call ends the request.
     */
    suspend fun currentFix(provider: LocationProvider): LocationFix?
}

/** The country the phone belongs to, to tell a trip abroad from a recording at home. */
fun interface PhoneCountry {

    /** Its ISO 3166 code ("DE"), or null when the phone doesn't say. */
    fun code(): String?
}
