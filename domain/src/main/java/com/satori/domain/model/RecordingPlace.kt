package com.satori.domain.model

/**
 * Where a recording was made: the phone's position when it started, read by GPS on the phone,
 * and the name of the place it is named after, from the app's own offline list of places
 * ([com.satori.domain.place.PlaceNames]). Stored only while [AppSettings.savePlace] is
 * on; nothing of it leaves the phone.
 *
 * @property latitude Degrees north of the equator, WGS 84 (-90..90)
 * @property longitude Degrees east of Greenwich, WGS 84 (-180..180)
 * @property accuracyMeters How far off the position may be, in metres, as Android reports a fix's
 *   horizontal accuracy; null when the fix gave none
 * @property name The name of the place it is named after, with its country code when that is not
 *   the phone's country; null when no place is near enough or none was looked up yet
 */
data class RecordingPlace(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val name: String?
) {
    /**
     * Whether [latitude] and [longitude] are a point on the earth: both in range and neither NaN.
     * Only such a place is stored, so a map or a distance never has to deal with another.
     */
    val isOnEarth: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0
}
