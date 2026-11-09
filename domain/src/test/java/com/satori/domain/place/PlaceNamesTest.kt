package com.satori.domain.place

import com.satori.domain.usecase.place.ORIGIN_LATITUDE
import com.satori.domain.usecase.place.ORIGIN_LONGITUDE
import com.satori.domain.usecase.place.placeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A position within 1 km of a place is named after it, or after the biggest place within 2 km of
 * it; farther from every place, after the place with the highest population / distance² within
 * 25 km. The name carries the country code only abroad.
 */
class PlaceNamesTest {

    private fun choose(candidates: List<GazetteerPlace>) = PlaceNames.choose(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, candidates)

    @Test
    fun `away from every place, of two the same size the nearer names it`() {
        val farther = placeAt("Ehrenfeld", northKm = 0.0, eastKm = -4.0)
        val nearer = placeAt("Deutz", northKm = 0.0, eastKm = 3.0)

        assertEquals(nearer, choose(listOf(farther, nearer)))
    }

    @Test
    fun `away from every place, a big city farther off names it rather than a village nearer`() {
        // Churchgate: GeoNames has no point in South Mumbai, and Mumbai's own lies 17 km north.
        val village = placeAt("Kegaon", northKm = -10.0, population = 7_485)
        val city = placeAt("Mumbai", northKm = 16.7, population = 12_691_836)

        assertEquals(city, choose(listOf(village, city)))
    }

    @Test
    fun `away from every place, a town near the position keeps its name against a city farther off`() {
        val town = placeAt("Frechen", northKm = 1.5, population = 52_309)
        val city = placeAt("Köln", northKm = -9.0, population = 1_024_621)

        assertEquals(town, choose(listOf(town, city)))
    }

    @Test
    fun `within 1 km of a place the position is at it, however big a city farther off is`() {
        val city = placeAt("Köln", northKm = -10.0, population = 1_024_621)
        val at = placeAt("Dorf", northKm = 0.9, population = 600)
        val beyond = placeAt("Weiler", northKm = 1.1, population = 600)

        assertEquals(at, choose(listOf(at, city)))
        assertEquals(city, choose(listOf(beyond, city)))
    }

    @Test
    fun `places whose population is unknown come last, and the nearest of them when all are`() {
        val unknownNear = placeAt("Sitz", northKm = 1.5, population = 0)
        val unknownFar = placeAt("Amt", northKm = -5.0, population = 0)
        val village = placeAt("Dorf", northKm = 20.0, population = 600)

        assertEquals(village, choose(listOf(unknownNear, unknownFar, village)))
        assertEquals(unknownNear, choose(listOf(unknownFar, unknownNear)))
    }

    @Test
    fun `no place farther than 25 km names it`() {
        val beyond = placeAt("Bonn", northKm = -25.2, population = 300_000)
        val inside = placeAt("Leverkusen", northKm = 24.8, population = 160_000)

        assertNull(choose(listOf(beyond)))
        assertEquals(inside, choose(listOf(beyond, inside)))
        assertNull(choose(emptyList()))
    }

    @Test
    fun `a bigger place within 2 km of the nearest one names it`() {
        // The cathedral: a district lies nearer than the centre of the city it belongs to.
        val district = placeAt("Altstadt Nord", northKm = 0.2, population = 17_922)
        val city = placeAt("Köln", northKm = -1.0, population = 1_024_621)

        assertEquals(city, choose(listOf(district, city)))
    }

    @Test
    fun `a bigger place within 2 km of the nearest one names it also when it is farther from the position`() {
        val nearest = placeAt("Dorf", northKm = 0.8, population = 1_000)
        // 1.8 km from the nearest place, 2.6 km from the position.
        val town = placeAt("Stadt", northKm = 2.6, population = 50_000)

        assertEquals(town, choose(listOf(nearest, town)))
    }

    @Test
    fun `a bigger place farther than 2 km from the nearest one does not`() {
        val village = placeAt("Dorf", northKm = 0.5, population = 600)
        val town = placeAt("Stadt", northKm = -2.1, population = 50_000)

        assertEquals(village, choose(listOf(village, town)))
    }

    @Test
    fun `a bigger place beside the nearest one but beyond 25 km is not taken`() {
        val nearest = placeAt("Rand", northKm = 24.5, population = 800)
        val beyond = placeAt("Jenseits", northKm = 25.5, population = 90_000)

        assertEquals(nearest, choose(listOf(nearest, beyond)))
    }

    @Test
    fun `of two places the same size beside each other the nearer names it`() {
        val nearer = placeAt("Eins", northKm = 0.3, population = 5_000)
        val farther = placeAt("Zwei", northKm = 1.5, population = 5_000)

        assertEquals(nearer, choose(listOf(farther, nearer)))
    }

    @Test
    fun `the name carries the country code only for a place abroad`() {
        val cologne = placeAt("Köln", northKm = 0.0, country = "DE")
        val vienna = placeAt("Vienna", northKm = 0.0, country = "AT")

        assertEquals("Köln", PlaceNames.storedName(cologne, "DE"))
        assertEquals("Köln", PlaceNames.storedName(cologne, "de"))
        assertEquals("Vienna, AT", PlaceNames.storedName(vienna, "DE"))
        assertEquals("Vienna", PlaceNames.storedName(vienna, null))
    }

    @Test
    fun `a stored name is recognised with or without its country code`() {
        val vienna = placeAt("Vienna", northKm = 0.0, country = "AT")

        assertTrue(PlaceNames.isNameOf("Vienna", vienna))
        assertTrue(PlaceNames.isNameOf("Vienna, AT", vienna))
        assertFalse(PlaceNames.isNameOf("Vienna, US", vienna))
        assertFalse(PlaceNames.isNameOf("Vienn", vienna))
    }
}
