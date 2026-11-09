package com.satori.domain.place

/**
 * How a recording's place is named: after the place of the [Gazetteer] it is at, or else the one
 * it most likely belongs to, within [MAX_DISTANCE_METERS] ([choose]); with its country when that
 * is not the phone's.
 */
object PlaceNames {

    /** No place farther than this names a position: from farther off the name would mislead. */
    const val MAX_DISTANCE_METERS = 25_000.0

    /**
     * A position this close to a place is at that place: the nearest place names it, or a bigger
     * one right beside it ([SAME_SPOT_METERS]).
     */
    const val AT_PLACE_METERS = 1_000.0

    /**
     * Places this close to the nearest one count as the same spot, and the biggest of them gives
     * the name. GeoNames lists a city's districts beside the city ("Altstadt Nord" 800 m from the
     * centre of "Köln"), and a village grown into a town beside the town: the city or the town is
     * the name the owner would say.
     */
    const val SAME_SPOT_METERS = 2_000.0

    /** A position farther than this from its place's centre reads "Near Köln" rather than "Köln". */
    const val NEAR_BEYOND_METERS = 3_000.0

    /**
     * The place a position is named after, among [candidates]; null when none lies within
     * [MAX_DISTANCE_METERS].
     *
     * - Within [AT_PLACE_METERS] of the nearest place, the position is at it: that place names it
     *   or, if bigger ones lie within [SAME_SPOT_METERS] of it, the biggest of those (the nearer of
     *   two the same size). The cathedral is 270 m from the district "Altstadt Nord" and reads
     *   "Köln", whose centre is 800 m from that district.
     * - Farther from every place, the place with the most people for its distance names it: the
     *   highest population / distance², the nearer of two that score the same. GeoNames gives a
     *   city one point, often far from much of it, and lists only some of its districts, so the
     *   nearest place, or a bigger one beside it, is often a district or a village beside the
     *   city: the district "Nippes" for Ehrenfeld, the village "Kegaon" across the harbour, 10 km
     *   from Churchgate, while Mumbai's point lies 17 km away. A place covers more ground the more
     *   people live there, so this score says how far inside a place's reach a position lies; it
     *   names Ehrenfeld "Köln" and Churchgate "Mumbai", and keeps a town's own name in the town
     *   beside a city. Places whose population GeoNames doesn't know (0) come last.
     */
    fun choose(latitude: Double, longitude: Double, candidates: List<GazetteerPlace>): GazetteerPlace? {
        val inReach = candidates
            .map { it to Geo.distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= MAX_DISTANCE_METERS }
        val (nearest, nearestDistance) = inReach.minByOrNull { (_, distance) -> distance } ?: return null
        if (nearestDistance > AT_PLACE_METERS) {
            // Every candidate is farther than AT_PLACE_METERS, so no distance is zero.
            val byReach = compareBy<Pair<GazetteerPlace, Double>> { (place, distance) -> place.population / (distance * distance) }
                .thenByDescending { (_, distance) -> distance }
            return inReach.maxWithOrNull(byReach)?.first
        }
        // The biggest wins; between two the same size the nearer, which sorts higher descending.
        val bySize = compareBy<Pair<GazetteerPlace, Double>> { (place, _) -> place.population }
            .thenByDescending { (_, distance) -> distance }
        return inReach
            .filter { (place, _) ->
                Geo.distanceMeters(nearest.latitude, nearest.longitude, place.latitude, place.longitude) <= SAME_SPOT_METERS
            }
            .maxWithOrNull(bySize)
            ?.first
    }

    /**
     * The name stored for a recording made near [place]: its own name, followed by its country
     * code when that is not [phoneCountry] ("Vienna, AT" for a phone from Germany). Without a
     * known phone country it is the name alone.
     */
    fun storedName(place: GazetteerPlace, phoneCountry: String?): String =
        if (phoneCountry == null || place.countryCode.equals(phoneCountry, ignoreCase = true)) {
            place.name
        } else {
            "${place.name}, ${place.countryCode}"
        }

    /**
     * Whether [storedName] names [place], as [PlaceNames.storedName] writes it: the place's name
     * alone or followed by its country code. The phone's country may have changed since.
     */
    fun isNameOf(storedName: String, place: GazetteerPlace): Boolean =
        storedName == place.name || storedName == "${place.name}, ${place.countryCode}"
}
