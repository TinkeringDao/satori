package com.satori.domain.place

/**
 * A place of the app's offline list, the one a recording's place is named after. The list comes
 * from GeoNames (tools/gazetteer in the repository).
 *
 * @property name The place's GeoNames name, UTF-8, as GeoNames gives it: often the local name
 *   ("Köln"), for some big cities the English one ("Munich")
 * @property countryCode The ISO 3166 code of its country, e.g. "DE"
 * @property latitude Its centre, degrees north (WGS 84)
 * @property longitude Its centre, degrees east (WGS 84)
 * @property population How many people live there, as GeoNames counts them; 0 when unknown
 */
data class GazetteerPlace(
    val name: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val population: Int
)

/** The app's offline list of places, bundled with it: names for positions without a network. */
interface Gazetteer {

    /**
     * The places within [radiusMeters] of a point, in no particular order; none for a point that
     * is not on the earth. The first call reads the list, which takes a moment; call it off the
     * main thread.
     *
     * @throws java.io.IOException if the list can't be read
     */
    suspend fun placesNear(latitude: Double, longitude: Double, radiusMeters: Double): List<GazetteerPlace>
}
