package com.satori.domain.usecase.place

import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.PlaceLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * A stored place reads "near" its name when the position is more than 3 km from the centre of
 * the place of that name, found again in the list of places.
 */
class DescribeRecordingPlaceUseCaseTest {

    private val cologne = placeAt("Köln", northKm = 0.0, population = 1_024_621)
    private val gazetteer = FakeGazetteer(listOf(cologne))
    private val describe = DescribeRecordingPlaceUseCase(gazetteer)

    /** A stored place [northKm] north of the centre of Köln. */
    private fun storedAt(northKm: Double, name: String? = "Köln"): RecordingPlace {
        val point = placeAt("", northKm = northKm)
        return RecordingPlace(point.latitude, point.longitude, 10F, name)
    }

    @Test
    fun `within 3 km of its centre the place reads as its name`() = runBlocking {
        val place = storedAt(northKm = 2.9)

        assertEquals(PlaceLabel(place, near = false), describe(place))
    }

    @Test
    fun `farther than 3 km from its centre the place reads near it`() = runBlocking {
        val place = storedAt(northKm = 3.1)

        assertEquals(PlaceLabel(place, near = true), describe(place))
    }

    @Test
    fun `a place abroad is found again by its name and country code`() = runBlocking {
        gazetteer.places = listOf(placeAt("Vienna", northKm = 0.0, country = "AT"))
        val place = storedAt(northKm = 5.0, name = "Vienna, AT")

        assertEquals(PlaceLabel(place, near = true), describe(place))
    }

    @Test
    fun `of two places with the name the nearer one is its centre`() = runBlocking {
        gazetteer.places = listOf(placeAt("Neustadt", northKm = 10.0), placeAt("Neustadt", northKm = -1.0))
        val place = storedAt(northKm = 0.0, name = "Neustadt")

        assertEquals(PlaceLabel(place, near = false), describe(place))
    }

    @Test
    fun `a place without a name has no near and needs no lookup`() = runBlocking {
        val place = storedAt(northKm = 4.0, name = null)

        assertEquals(PlaceLabel(place, near = false), describe(place))
        assertEquals(0, gazetteer.lookups)
    }

    @Test
    fun `a name the list no longer has reads as it is`() = runBlocking {
        val place = storedAt(northKm = 5.0, name = "Cologne")

        assertEquals(PlaceLabel(place, near = false), describe(place))
    }

    @Test
    fun `when the list can't be read the name reads as it is`() = runBlocking {
        gazetteer.failure = IOException("asset missing")
        val place = storedAt(northKm = 5.0)

        assertEquals(PlaceLabel(place, near = false), describe(place))
    }
}
