package com.satori.domain.place

import com.satori.domain.model.RecordingPlace

/**
 * How a recording's place reads on its page, in its Markdown file and in a copy: the place's name
 * ("Köln"), "Near Köln" when [near], or, for a place without a name, its coordinates.
 *
 * @property near The position is more than [PlaceNames.NEAR_BEYOND_METERS] from the centre of the
 *   place it is named after; always false for a place without a name
 */
data class PlaceLabel(val place: RecordingPlace, val near: Boolean)
