package com.satori.data.place

import com.satori.domain.place.GazetteerPlace
import com.satori.domain.place.Geo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The offline list of places in memory, as [PlaceFile] reads it: sorted into latitude bands of
 * 1/[bandsPerDegree] degree and, inside a band, by longitude. The places near a point are then a
 * binary search away in each of the two or three bands the search circle reaches; a whole scan
 * of the 78,000 places is never needed.
 *
 * Positions are whole degrees x [SCALE] (about a metre). Each column holds one value per place,
 * in the same order; [nameStarts] has one more entry, the end of the last name in [names].
 *
 * @throws IllegalArgumentException when the columns don't fit together or the places are not in
 *   band and longitude order
 */
internal class PlaceIndex(
    private val bandsPerDegree: Int,
    private val latitudesE5: IntArray,
    private val longitudesE5: IntArray,
    private val populations: IntArray,
    private val countries: ByteArray,
    private val countryCodes: List<String>,
    private val nameStarts: IntArray,
    private val names: ByteArray
) {

    /** How many places there are. */
    val size: Int = latitudesE5.size

    private val bandCount = 180 * bandsPerDegree

    /** Band b holds the places from bandStarts[b] up to bandStarts[b + 1]. */
    private val bandStarts: IntArray

    init {
        require(bandsPerDegree > 0) { "No latitude bands" }
        require(listOf(longitudesE5.size, populations.size, countries.size).all { it == size }) { "Columns of different lengths" }
        require(nameStarts.size == size + 1 && nameStarts.last() == names.size) { "Names don't fit the places" }
        val counts = IntArray(bandCount)
        for (i in 0 until size) {
            require(abs(latitudesE5[i]) <= 90 * SCALE && abs(longitudesE5[i]) <= 180 * SCALE) { "Place $i is not on the earth" }
            require((countries[i].toInt() and 0xFF) < countryCodes.size) { "Place $i has no country" }
            // The build script writes no empty name; one would be stored as no name at all.
            require(nameStarts[i + 1] > nameStarts[i]) { "Place $i has no name" }
            val band = bandOfE5(latitudesE5[i])
            if (i > 0) {
                val before = bandOfE5(latitudesE5[i - 1])
                require(band > before || (band == before && longitudesE5[i] >= longitudesE5[i - 1])) {
                    "Place $i is out of order"
                }
            }
            counts[band]++
        }
        bandStarts = IntArray(bandCount + 1)
        for (band in 0 until bandCount) bandStarts[band + 1] = bandStarts[band] + counts[band]
    }

    /**
     * The places within [radiusMeters] of a point, by great-circle distance. None for a point
     * that is not on the earth. Near a pole, where the circle takes in every longitude, the
     * bands it reaches are read whole; across the date line both ends of each band are read.
     */
    fun placesNear(latitude: Double, longitude: Double, radiusMeters: Double): List<GazetteerPlace> {
        // Written so that NaN fails every test.
        if (!(latitude in -90.0..90.0 && longitude in -180.0..180.0 && radiusMeters >= 0.0)) return emptyList()
        val degreesNorthSouth = radiusMeters / Geo.METERS_PER_DEGREE
        val south = max(-90.0, latitude - degreesNorthSouth)
        val north = min(90.0, latitude + degreesNorthSouth)
        // A degree of longitude is shortest at the edge of the circle nearest a pole, so the
        // circle spans at most this many degrees east and west there; at a pole it spans all.
        val poleward = Math.toRadians(max(abs(south), abs(north)))
        val degreesEastWest = degreesNorthSouth / cos(poleward)
        val found = mutableListOf<GazetteerPlace>()
        for (band in bandOf(south)..bandOf(north)) {
            val west = longitude - degreesEastWest
            val east = longitude + degreesEastWest
            when {
                !(degreesEastWest < 180.0) -> collect(band, -180.0, 180.0, latitude, longitude, radiusMeters, found)
                west < -180.0 -> {
                    collect(band, west + 360.0, 180.0, latitude, longitude, radiusMeters, found)
                    collect(band, -180.0, east, latitude, longitude, radiusMeters, found)
                }
                east > 180.0 -> {
                    collect(band, west, 180.0, latitude, longitude, radiusMeters, found)
                    collect(band, -180.0, east - 360.0, latitude, longitude, radiusMeters, found)
                }
                else -> collect(band, west, east, latitude, longitude, radiusMeters, found)
            }
        }
        return found
    }

    /** Adds the places of [band] between [west] and [east] that lie within [radiusMeters]. */
    private fun collect(
        band: Int,
        west: Double,
        east: Double,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        found: MutableList<GazetteerPlace>
    ) {
        val westE5 = floor(west * SCALE).toInt()
        val eastE5 = ceil(east * SCALE).toInt()
        var i = firstAtOrEastOf(westE5, bandStarts[band], bandStarts[band + 1])
        while (i < bandStarts[band + 1] && longitudesE5[i] <= eastE5) {
            val placeLatitude = latitudesE5[i] / SCALE.toDouble()
            val placeLongitude = longitudesE5[i] / SCALE.toDouble()
            if (Geo.distanceMeters(latitude, longitude, placeLatitude, placeLongitude) <= radiusMeters) {
                found += GazetteerPlace(
                    name = String(names, nameStarts[i], nameStarts[i + 1] - nameStarts[i], Charsets.UTF_8),
                    countryCode = countryCodes[countries[i].toInt() and 0xFF],
                    latitude = placeLatitude,
                    longitude = placeLongitude,
                    population = populations[i]
                )
            }
            i++
        }
    }

    /** The first place from [from] (inclusive) to [to] (exclusive) at or east of [longitudeE5]; [to] if none. */
    private fun firstAtOrEastOf(longitudeE5: Int, from: Int, to: Int): Int {
        var low = from
        var high = to
        while (low < high) {
            val middle = (low + high) ushr 1
            if (longitudesE5[middle] < longitudeE5) low = middle + 1 else high = middle
        }
        return low
    }

    /** The band of a latitude in degrees; the north pole is in the last band. */
    private fun bandOf(latitude: Double): Int = min(((latitude + 90.0) * bandsPerDegree).toInt(), bandCount - 1)

    /** The band of a latitude in degrees x [SCALE], as the build script sorts by it. */
    private fun bandOfE5(latitudeE5: Int): Int =
        min(((latitudeE5 + 90L * SCALE) * bandsPerDegree / SCALE).toInt(), bandCount - 1)

    companion object {
        /** Positions are stored as degrees times this. */
        const val SCALE = 100_000
    }
}
