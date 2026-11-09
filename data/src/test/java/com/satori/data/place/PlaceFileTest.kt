package com.satori.data.place

import com.satori.domain.place.PlaceNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The list of places bundled with the app reads whole, and names the places the owner records in
 * as the details page will show them; the date line and the poles don't break a lookup. Bytes
 * that are not a list of places are refused.
 */
class PlaceFileTest {

    private fun nameAt(latitude: Double, longitude: Double, phoneCountry: String = "DE"): String? {
        val candidates = bundled.placesNear(latitude, longitude, PlaceNames.MAX_DISTANCE_METERS)
        return PlaceNames.choose(latitude, longitude, candidates)?.let { PlaceNames.storedName(it, phoneCountry) }
    }

    @Test
    fun `the bundled list reads whole`() {
        assertTrue("${bundled.size} places", bundled.size in 70_000..90_000)
    }

    @Test
    fun `the places the owner records in are named`() {
        // The cathedral lies in the district Altstadt Nord, which is within 2 km of the centre of Köln.
        assertEquals("Köln", nameAt(50.9413, 6.9583))
        assertEquals("Munich", nameAt(48.1374, 11.5755))
        assertEquals("Vienna, AT", nameAt(48.2085, 16.3721))
        assertEquals("Mumbai, IN", nameAt(19.0728, 72.8826))
        assertEquals("Mumbai", nameAt(19.0728, 72.8826, phoneCountry = "IN"))
    }

    @Test
    fun `addresses away from every listed point are named after the city they are in`() {
        // The nearest place, or a bigger one beside it, would name them Nippes, Frechen, Poll
        // across the Rhine, Kegaon across the harbour, Dommasandra and Kukatpally.
        assertEquals("Köln", nameAt(50.9480, 6.9150)) // Ehrenfeld
        assertEquals("Köln", nameAt(50.9290, 6.8580)) // Junkersdorf
        assertEquals("Köln", nameAt(50.9070, 6.9440)) // Zollstock
        assertEquals("Mumbai, IN", nameAt(18.9322, 72.8264)) // Churchgate
        assertEquals("Bengaluru, IN", nameAt(12.9698, 77.7500)) // Whitefield
        assertEquals("Hyderabad, IN", nameAt(17.4435, 78.3772)) // HITEC City
    }

    @Test
    fun `towns beside a city keep their own names`() {
        assertEquals("Frechen", nameAt(50.9122, 6.8105))
        assertEquals("Hürth", nameAt(50.8781, 6.8761))
        assertEquals("Leverkusen", nameAt(51.0333, 6.9833))
        assertEquals("Bergisch Gladbach", nameAt(50.9918, 7.1367))
        assertEquals("Bonn", nameAt(50.7339, 7.0997))
    }

    @Test
    fun `the open sea has no name`() {
        assertNull(nameAt(55.0, 4.0))
    }

    @Test
    fun `the poles and the date line don't break a lookup`() {
        for ((latitude, longitude) in listOf(90.0 to 0.0, -90.0 to 0.0, 89.99 to -180.0, -16.8 to 180.0, -16.8 to -180.0, 0.0 to 180.0)) {
            bundled.placesNear(latitude, longitude, PlaceNames.MAX_DISTANCE_METERS)
        }
    }

    @Test
    fun `bytes that are not a list of places are refused`() {
        val whole = asset.readBytes()

        assertThrows(IllegalArgumentException::class.java) { PlaceFile.read("Köln".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { PlaceFile.read(whole.copyOf(whole.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { PlaceFile.read(whole + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { PlaceFile.read(whole.copyOf().also { it[4] = 2 }) }
    }

    private companion object {
        // Gradle runs the tests in the module's folder.
        val asset = File("src/main/assets/${PlaceFile.ASSET}")

        // Read once for all the tests: the list is about 2 MB.
        val bundled: PlaceIndex by lazy { PlaceFile.read(asset.readBytes()) }
    }
}
