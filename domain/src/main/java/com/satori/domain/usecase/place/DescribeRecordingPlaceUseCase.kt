package com.satori.domain.usecase.place

import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.Gazetteer
import com.satori.domain.place.Geo
import com.satori.domain.place.PlaceLabel
import com.satori.domain.place.PlaceNames
import kotlinx.coroutines.CancellationException

/**
 * Says how a stored place reads ([PlaceLabel]): whether its position is near the place it is
 * named after or farther than [PlaceNames.NEAR_BEYOND_METERS] from its centre.
 *
 * Only the name is stored, so the centre is looked up again in the [gazetteer]: the place of that
 * name nearest to the position. When none is found (a later list without it) or the list can't
 * be read, the name reads as it is, without "near".
 */
class DescribeRecordingPlaceUseCase(private val gazetteer: Gazetteer) {

    suspend operator fun invoke(place: RecordingPlace): PlaceLabel {
        val name = place.name ?: return PlaceLabel(place, near = false)
        val candidates = try {
            // A little beyond the distance names are given within, so a place right at the edge is found again.
            gazetteer.placesNear(place.latitude, place.longitude, PlaceNames.MAX_DISTANCE_METERS + SEARCH_MARGIN_METERS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PlaceLabel(place, near = false)
        }
        val toCentre = candidates
            .filter { PlaceNames.isNameOf(name, it) }
            .minOfOrNull { Geo.distanceMeters(place.latitude, place.longitude, it.latitude, it.longitude) }
        return PlaceLabel(place, near = toCentre != null && toCentre > PlaceNames.NEAR_BEYOND_METERS)
    }

    private companion object {
        const val SEARCH_MARGIN_METERS = 1_000.0
    }
}
