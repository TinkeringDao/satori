package com.satori.domain.place

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distances on the earth, taken as a sphere. That is off by at most half a percent, far less than
 * naming a place needs, and the haversine formula used stays exact near the poles and across the
 * date line, where a flat approximation breaks.
 */
object Geo {

    /** The earth's mean radius (IUGG), in metres. */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Metres along one degree of latitude, which is the same everywhere on a sphere. */
    const val METERS_PER_DEGREE = EARTH_RADIUS_METERS * PI / 180.0

    /** The great-circle distance between two points given in degrees, in metres. */
    fun distanceMeters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val phi1 = Math.toRadians(latitude1)
        val phi2 = Math.toRadians(latitude2)
        val halfDeltaPhi = (phi2 - phi1) / 2
        val halfDeltaLambda = Math.toRadians(longitude2 - longitude1) / 2
        val h = sin(halfDeltaPhi) * sin(halfDeltaPhi) +
            cos(phi1) * cos(phi2) * sin(halfDeltaLambda) * sin(halfDeltaLambda)
        // Rounding can push h a hair above 1 for two points at opposite ends of the earth.
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }
}
