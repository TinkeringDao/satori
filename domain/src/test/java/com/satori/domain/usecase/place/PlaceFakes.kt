package com.satori.domain.usecase.place

import com.satori.domain.place.Gazetteer
import com.satori.domain.place.GazetteerPlace
import com.satori.domain.place.Geo
import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.LocationSource
import kotlinx.coroutines.awaitCancellation

const val SECOND_NANOS = 1_000_000_000L

/**
 * The phone's positions as a test sets them: the last fix of each provider, and what a request
 * for a new one does (by default it never answers, as satellites out of sight).
 */
class FakeLocationSource(var now: Long = 3_600 * SECOND_NANOS) : LocationSource {
    var allowed = true
    val last = mutableMapOf<LocationProvider, LocationFix>()
    var current: suspend (LocationProvider) -> LocationFix? = { awaitCancellation() }

    /** The providers asked for a new fix, in order. */
    val asked = mutableListOf<LocationProvider>()

    override fun isAllowed() = allowed
    override fun elapsedRealtimeNanos() = now
    override fun lastFix(provider: LocationProvider): LocationFix? = last[provider]

    override suspend fun currentFix(provider: LocationProvider): LocationFix? {
        asked += provider
        return current(provider)
    }
}

/** A fix at a point, taken [secondsAgo] before [FakeLocationSource.now]'s default. */
fun fix(latitude: Double, longitude: Double, secondsAgo: Long = 0, accuracy: Float? = 5F, now: Long = 3_600 * SECOND_NANOS) =
    LocationFix(latitude, longitude, accuracy, now - secondsAgo * SECOND_NANOS)

/** The list of places as a test gives it, answering by distance as the app's list does. */
class FakeGazetteer(var places: List<GazetteerPlace> = emptyList()) : Gazetteer {
    var failure: Exception? = null
    var lookups = 0

    override suspend fun placesNear(latitude: Double, longitude: Double, radiusMeters: Double): List<GazetteerPlace> {
        lookups++
        failure?.let { throw it }
        return places.filter { Geo.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radiusMeters }
    }
}

/** A place [northKm] north and [eastKm] east of a point near Cologne cathedral. */
fun placeAt(name: String, northKm: Double, eastKm: Double = 0.0, population: Int = 1_000, country: String = "DE") =
    GazetteerPlace(
        name = name,
        countryCode = country,
        latitude = ORIGIN_LATITUDE + northKm * 1_000 / Geo.METERS_PER_DEGREE,
        longitude = ORIGIN_LONGITUDE + eastKm * 1_000 / (Geo.METERS_PER_DEGREE * Math.cos(Math.toRadians(ORIGIN_LATITUDE))),
        population = population
    )

const val ORIGIN_LATITUDE = 50.9413
const val ORIGIN_LONGITUDE = 6.9583
