package com.satori.domain.usecase.place

import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.LocationSource
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds where the phone was when a recording started, in this order:
 *
 * 1. The last satellite fix, if it is at most [FRESH_GPS_NANOS] older than the start: the phone
 *    has hardly moved since, and it costs nothing.
 * 2. A new satellite fix, waited for at most [currentFixTimeoutMs]. Satellites need no network, so
 *    this works in airplane mode; the first fix of the day can take that long.
 * 3. Else the newest last position of any provider (satellites, networks, or what other apps
 *    asked for), if it is at most [FALLBACK_NANOS] older than the start.
 *
 * Ages are counted from the start, so a fix another app got while this waited counts as fresh.
 * The last positions are read as the recording starts, while its screen is on, and again after
 * the wait: Android may refuse them to an app that has left the screen meanwhile, and the second
 * read finds a position another app got while this waited.
 *
 * @param currentFixTimeoutMs How long a new satellite fix may take; [CURRENT_FIX_TIMEOUT_MS] in the app
 */
class TakeLocationFixUseCase(
    private val source: LocationSource,
    private val currentFixTimeoutMs: Long = CURRENT_FIX_TIMEOUT_MS
) {

    /** Which of the three steps gave the fix, for the field log. */
    enum class Step { LAST_GPS, CURRENT_GPS, LAST_KNOWN }

    /** A fix and the step that gave it. */
    data class Found(val fix: LocationFix, val step: Step)

    /**
     * @param startedAtNanos When the recording started, on [LocationSource.elapsedRealtimeNanos]'s clock
     * @return The fix, or null when none of the three steps gave one
     */
    suspend operator fun invoke(startedAtNanos: Long): Found? {
        val atStart = LocationProvider.entries.associateWith { source.lastFix(it) }
        atStart[LocationProvider.GPS]
            ?.takeIf { it.ageAt(startedAtNanos) <= FRESH_GPS_NANOS }
            ?.let { return Found(it, Step.LAST_GPS) }

        withTimeoutOrNull(currentFixTimeoutMs) { source.currentFix(LocationProvider.GPS) }
            ?.let { return Found(it, Step.CURRENT_GPS) }

        val afterWait = LocationProvider.entries.map { source.lastFix(it) }
        return (atStart.values + afterWait)
            .filterNotNull()
            .filter { it.ageAt(startedAtNanos) <= FALLBACK_NANOS }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { Found(it, Step.LAST_KNOWN) }
    }

    /** How long before [startedAtNanos] this was taken; negative when it was taken after. */
    private fun LocationFix.ageAt(startedAtNanos: Long): Long = startedAtNanos - elapsedRealtimeNanos

    companion object {
        /** A satellite fix this old at the start is used as it is. */
        const val FRESH_GPS_NANOS = 2 * 60 * 1_000_000_000L

        /** How long a new satellite fix may take. */
        const val CURRENT_FIX_TIMEOUT_MS = 30_000L

        /** A last position this old at the start is still taken when no new fix came. */
        const val FALLBACK_NANOS = 10 * 60 * 1_000_000_000L
    }
}
