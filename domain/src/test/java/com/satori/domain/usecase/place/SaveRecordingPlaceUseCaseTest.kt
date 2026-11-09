package com.satori.domain.usecase.place

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.PhoneCountry
import com.satori.domain.usecase.place.SaveRecordingPlaceUseCase.Outcome
import com.satori.domain.usecase.place.TakeLocationFixUseCase.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A recording's place is stored with the name of the place it is near, with the country code
 * abroad, without a name when none is near or the list can't be read, and never over a place the
 * recording has.
 */
class SaveRecordingPlaceUseCaseTest {

    private val source = FakeLocationSource()
    private val gazetteer = FakeGazetteer(
        listOf(
            placeAt("Köln", northKm = -1.0, population = 1_024_621),
            placeAt("Vienna", northKm = 600.0, eastKm = 600.0, population = 1_691_468, country = "AT")
        )
    )
    private val repository = FakeTranscriptionRepository(session("rec"))
    private var phoneCountry: String? = "DE"
    private val save = SaveRecordingPlaceUseCase(
        takeLocationFix = TakeLocationFixUseCase(source, currentFixTimeoutMs = 100L),
        gazetteer = gazetteer,
        transcriptionRepository = repository,
        phoneCountry = PhoneCountry { phoneCountry }
    )

    private val cathedral = fix(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, accuracy = 8F)

    private suspend fun saveFrom(fix: LocationFix): Outcome {
        source.current = { fix }
        return save("rec", source.now)
    }

    @Test
    fun `the position is stored with the name of the place it is near`() = runBlocking {
        val outcome = saveFrom(cathedral)

        assertEquals(Outcome.Saved(Step.CURRENT_GPS, accuracyMeters = 8F, named = true), outcome)
        assertEquals(RecordingPlace(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, 8F, "Köln"), repository.session?.place)
    }

    @Test
    fun `a place abroad carries its country code`() = runBlocking {
        val vienna = gazetteer.places[1]
        saveFrom(fix(vienna.latitude + 0.01, vienna.longitude))

        assertEquals("Vienna, AT", repository.session?.place?.name)
    }

    @Test
    fun `a position with no place within 25 km is stored without a name`() = runBlocking {
        val outcome = saveFrom(fix(ORIGIN_LATITUDE + 1.0, ORIGIN_LONGITUDE, accuracy = null))

        assertEquals(Outcome.Saved(Step.CURRENT_GPS, accuracyMeters = null, named = false), outcome)
        assertEquals(RecordingPlace(ORIGIN_LATITUDE + 1.0, ORIGIN_LONGITUDE, null, null), repository.session?.place)
    }

    @Test
    fun `when the list of places can't be read the position is stored without a name`() = runBlocking {
        val broken = IOException("asset missing")
        gazetteer.failure = broken

        val outcome = saveFrom(cathedral)

        assertEquals(Outcome.Saved(Step.CURRENT_GPS, accuracyMeters = 8F, named = false, listFailure = broken), outcome)
        assertEquals(RecordingPlace(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, 8F, null), repository.session?.place)
    }

    @Test
    fun `a position that comes some seconds later is still stored`() = runBlocking {
        source.current = {
            delay(30)
            cathedral
        }

        assertTrue(save("rec", source.now) is Outcome.Saved)
        assertEquals("Köln", repository.session?.place?.name)
    }

    @Test
    fun `a recording that has a place keeps it and no position is asked for`() = runBlocking {
        val earlier = RecordingPlace(48.1374, 11.5755, 5F, "Munich")
        repository.session = repository.session?.copy(place = earlier)

        assertEquals(Outcome.Kept, saveFrom(cathedral))
        assertEquals(earlier, repository.session?.place)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `a recording that is gone asks for no position`() = runBlocking {
        repository.session = null

        assertEquals(Outcome.Gone, saveFrom(cathedral))
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `a recording discarded while its position was awaited is gone, not saved`() = runBlocking {
        // Stopped at once with nothing in it: the recorder deletes it before the fix comes.
        source.current = {
            repository.session = null
            cathedral
        }

        assertEquals(Outcome.Gone, save("rec", source.now))
        assertEquals(0, gazetteer.lookups)
    }

    @Test
    fun `without a position nothing is stored`() = runBlocking {
        source.current = { null }

        assertEquals(Outcome.NoFix, save("rec", source.now))
        assertNull(repository.session?.place)
        assertEquals(0, gazetteer.lookups)
    }

    @Test
    fun `a last satellite fix taken just before is used as it is`() = runBlocking {
        source.last[LocationProvider.GPS] = fix(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, secondsAgo = 30, accuracy = 4F)

        assertEquals(Outcome.Saved(Step.LAST_GPS, accuracyMeters = 4F, named = true), save("rec", source.now))
    }

    @Test
    fun `a position off the earth is not stored`() = runBlocking {
        val outcome = saveFrom(fix(91.0, 0.0))

        assertTrue(outcome is Outcome.NotSaved)
        assertNull(repository.session?.place)
    }
}
