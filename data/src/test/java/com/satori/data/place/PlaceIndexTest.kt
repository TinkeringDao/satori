package com.satori.data.place

import com.satori.domain.place.GazetteerPlace
import com.satori.domain.place.PlaceNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The index finds the places within a radius by great-circle distance, in the bands and at the
 * longitudes it reaches: across a band edge, across the date line, at the poles, and nothing for
 * a point that is not on the earth.
 */
class PlaceIndexTest {

    private fun place(name: String, latitude: Double, longitude: Double, population: Int = 1_000, country: String = "DE") =
        GazetteerPlace(name, country, latitude, longitude, population)

    /** An index of [places], in the order the build script writes them. */
    private fun indexOf(vararg places: GazetteerPlace): PlaceIndex {
        val scale = PlaceIndex.SCALE.toDouble()
        val sorted = places.sortedWith(
            compareBy<GazetteerPlace>({ minOf(((it.latitude + 90) * BANDS).toInt(), 180 * BANDS - 1) }, { it.longitude })
        )
        val codes = sorted.map { it.countryCode }.distinct()
        val names = sorted.map { it.name.toByteArray(Charsets.UTF_8) }
        val starts = IntArray(sorted.size + 1)
        names.forEachIndexed { i, bytes -> starts[i + 1] = starts[i] + bytes.size }
        return PlaceIndex(
            bandsPerDegree = BANDS,
            latitudesE5 = IntArray(sorted.size) { (sorted[it].latitude * scale).roundToInt() },
            longitudesE5 = IntArray(sorted.size) { (sorted[it].longitude * scale).roundToInt() },
            populations = IntArray(sorted.size) { sorted[it].population },
            countries = ByteArray(sorted.size) { codes.indexOf(sorted[it].countryCode).toByte() },
            countryCodes = codes,
            nameStarts = starts,
            names = names.fold(ByteArray(0)) { all, bytes -> all + bytes }
        )
    }

    private fun PlaceIndex.namesNear(latitude: Double, longitude: Double, radiusMeters: Double = 25_000.0) =
        placesNear(latitude, longitude, radiusMeters).map { it.name }.toSet()

    @Test
    fun `the places within the radius are found, the others not`() {
        val index = indexOf(
            place("Köln", 50.93333, 6.95, 1_024_621),
            place("Leverkusen", 51.0303, 6.98432, 163_729),
            place("Bonn", 50.73438, 7.09549, 330_579),
            place("Düsseldorf", 51.22172, 6.77616, 620_523)
        )

        // From the cathedral: Leverkusen is 10.1 km away, Bonn 24.9 km, Düsseldorf 34 km.
        assertEquals(setOf("Köln", "Leverkusen", "Bonn"), index.namesNear(50.9413, 6.9583))
        assertEquals(setOf("Köln", "Leverkusen"), index.namesNear(50.9413, 6.9583, radiusMeters = 20_000.0))
    }

    @Test
    fun `a found place keeps its name, country and population`() {
        val vienna = place("Vienna", 48.20849, 16.37208, 1_691_468, country = "AT")
        val index = indexOf(place("Köln", 50.93333, 6.95), vienna)

        assertEquals(listOf(vienna), index.placesNear(48.2, 16.37, 5_000.0))
    }

    @Test
    fun `places in the next band are found across its edge`() {
        // Bands are a quarter degree: 51.0 is the edge between two of them.
        val south = place("Süd", 50.99, 7.0)
        val north = place("Nord", 51.01, 7.0)

        assertEquals(setOf("Süd", "Nord"), indexOf(south, north).namesNear(51.0, 7.0, 3_000.0))
    }

    @Test
    fun `across the date line places on both sides are found`() {
        val index = indexOf(
            place("East of the line", -16.8, 179.98, country = "FJ"),
            place("West of the line", -16.8, -179.97, country = "FJ"),
            place("Far west", -16.8, -179.5, country = "FJ")
        )

        assertEquals(setOf("East of the line", "West of the line"), index.namesNear(-16.8, 179.995, 10_000.0))
        assertEquals(setOf("East of the line", "West of the line"), index.namesNear(-16.8, -179.995, 10_000.0))
        assertEquals("West of the line", PlaceNames.choose(-16.8, -179.99, index.placesNear(-16.8, -179.99, 25_000.0))?.name)
    }

    @Test
    fun `at the poles every longitude is searched and nothing breaks`() {
        val index = indexOf(
            place("Polar station", 89.95, 120.0, population = 0, country = "XX"),
            place("Other side", 89.9, -60.0, population = 0, country = "XX"),
            place("Longyearbyen", 78.22334, 15.64689, 2_417, country = "SJ"),
            place("Southern station", -89.99, 0.0, population = 0, country = "AQ")
        )

        assertEquals(setOf("Polar station", "Other side"), index.namesNear(90.0, 0.0))
        assertEquals(setOf("Southern station"), index.namesNear(-90.0, 45.0))
        assertEquals(setOf("Polar station", "Other side"), index.namesNear(89.99, 180.0))
        assertEquals(emptySet<String>(), index.namesNear(-89.9, -180.0, 1_000.0))
    }

    @Test
    fun `a point not on the earth finds nothing`() {
        val index = indexOf(place("Köln", 50.93333, 6.95))

        assertTrue(index.placesNear(Double.NaN, 6.95, 25_000.0).isEmpty())
        assertTrue(index.placesNear(50.9, Double.NaN, 25_000.0).isEmpty())
        assertTrue(index.placesNear(91.0, 6.95, 25_000.0).isEmpty())
        assertTrue(index.placesNear(50.9, 181.0, 25_000.0).isEmpty())
        assertTrue(index.placesNear(50.9, 6.95, Double.NaN).isEmpty())
        assertTrue(indexOf().placesNear(50.9, 6.95, 25_000.0).isEmpty())
    }

    @Test
    fun `places out of band and longitude order are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaceIndex(
                bandsPerDegree = BANDS,
                latitudesE5 = intArrayOf(5_100_000, 5_000_000),
                longitudesE5 = intArrayOf(700_000, 700_000),
                populations = intArrayOf(1, 1),
                countries = byteArrayOf(0, 0),
                countryCodes = listOf("DE"),
                nameStarts = intArrayOf(0, 1, 2),
                names = "AB".toByteArray()
            )
        }
    }

    @Test
    fun `a place without a name is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaceIndex(
                bandsPerDegree = BANDS,
                latitudesE5 = intArrayOf(5_000_000, 5_100_000),
                longitudesE5 = intArrayOf(700_000, 700_000),
                populations = intArrayOf(1, 1),
                countries = byteArrayOf(0, 0),
                countryCodes = listOf("DE"),
                nameStarts = intArrayOf(0, 0, 1),
                names = "B".toByteArray()
            )
        }
    }

    private companion object {
        const val BANDS = 4
    }
}
