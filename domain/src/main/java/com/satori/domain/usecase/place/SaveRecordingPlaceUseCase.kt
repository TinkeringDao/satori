package com.satori.domain.usecase.place

import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.Gazetteer
import com.satori.domain.place.LocationFix
import com.satori.domain.place.PhoneCountry
import com.satori.domain.place.PlaceNames
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Stores where a recording started: the position [takeLocationFix] finds, named after the nearest
 * place of the [gazetteer] ([PlaceNames]). A position with no place near enough, or found while
 * the list can't be read, is stored without a name: the coordinates are still worth keeping.
 *
 * A recording that already has a place keeps it: one started again after an error is still where
 * it began.
 */
class SaveRecordingPlaceUseCase(
    private val takeLocationFix: TakeLocationFixUseCase,
    private val gazetteer: Gazetteer,
    private val transcriptionRepository: TranscriptionRepository,
    private val phoneCountry: PhoneCountry
) {

    /** How it went, for the field log. Nothing in it says where. */
    sealed interface Outcome {
        /**
         * The recording no longer exists, so nothing is stored: it was discarded before the
         * position was asked for, or while it was awaited (a recording stopped at once with
         * nothing in it).
         */
        data object Gone : Outcome

        /** The recording has a place already and keeps it. */
        data object Kept : Outcome

        /** No position could be found ([TakeLocationFixUseCase]). */
        data object NoFix : Outcome

        /**
         * The place is stored.
         *
         * @property named Whether it has a name
         * @property listFailure Why the list of places couldn't be read, when that left it unnamed
         */
        data class Saved(
            val step: TakeLocationFixUseCase.Step,
            val accuracyMeters: Float?,
            val named: Boolean,
            val listFailure: Throwable? = null
        ) : Outcome

        /** The place couldn't be stored. */
        data class NotSaved(val cause: Throwable) : Outcome
    }

    /**
     * @param startedAtNanos When the recording started, on the location source's clock
     */
    suspend operator fun invoke(sessionId: String, startedAtNanos: Long): Outcome {
        val session = transcriptionRepository.getSession(sessionId).first() ?: return Outcome.Gone
        if (session.place != null) return Outcome.Kept
        val found = takeLocationFix(startedAtNanos) ?: return Outcome.NoFix
        // The position can take half a minute. Storing it on a recording discarded meanwhile does
        // nothing and succeeds, which would read as saved.
        transcriptionRepository.getSession(sessionId).first() ?: return Outcome.Gone
        val fix = found.fix
        var listFailure: Throwable? = null
        val name = try {
            nameOf(fix)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listFailure = e
            null
        }
        val place = RecordingPlace(fix.latitude, fix.longitude, fix.accuracyMeters, name)
        return transcriptionRepository.setPlace(sessionId, place).fold(
            onSuccess = { Outcome.Saved(found.step, fix.accuracyMeters, named = name != null, listFailure = listFailure) },
            onFailure = { Outcome.NotSaved(it) }
        )
    }

    /** The stored name of the place [fix] is named after, or null when none is near enough. */
    private suspend fun nameOf(fix: LocationFix): String? {
        val candidates = gazetteer.placesNear(fix.latitude, fix.longitude, PlaceNames.MAX_DISTANCE_METERS)
        val place = PlaceNames.choose(fix.latitude, fix.longitude, candidates) ?: return null
        return PlaceNames.storedName(place, phoneCountry.code())
    }
}
