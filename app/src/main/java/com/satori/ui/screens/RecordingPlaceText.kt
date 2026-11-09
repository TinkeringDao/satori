package com.satori.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.PlaceLabel
import com.satori.ui.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How a recording's place is written in its Markdown file, in a copy and for a maps app. Plain
 * functions without Android, so they are tested on the JVM; the words come from the caller, in
 * the app's language.
 */
internal object PlaceText {

    /**
     * "50.93750, 6.96030": decimal degrees to about a metre, south and west negative, the way maps
     * apps take coordinates in any language.
     */
    fun coordinates(place: RecordingPlace): String =
        String.format(Locale.ROOT, "%.5f, %.5f", place.latitude, place.longitude)

    /**
     * How the place of a recording whose stored place is [stored] shows: as [looked], once it has
     * been looked up in the list of places ("Near Köln"), and as the stored place until then
     * ("Köln"). The line under the date is then there from the first frame, and the page doesn't
     * move down when the lookup comes, which the first time reads the whole list. A lookup of a
     * place that is no longer stored (removed meanwhile) doesn't show.
     */
    fun shown(stored: RecordingPlace?, looked: PlaceLabel?): PlaceLabel? =
        looked?.takeIf { it.place == stored } ?: stored?.let { PlaceLabel(it, near = false) }

    /** The place's line in a Markdown file, under the date and the length: "- Place: Near Köln". */
    fun markdownItem(label: String, place: String): String = "- $label: $place"

    /**
     * A copy as it is pasted: the place first ("Place: Near Köln"), a blank line, then [text].
     * Without a place, [text] alone.
     */
    fun withPlace(label: String, place: String?, text: String): String =
        if (place == null) text else "$label: $place\n\n$text"

    /**
     * A geo: URI that maps apps open with a pin on the place, named [label]:
     * "geo:50.937500,6.960300?q=50.937500,6.960300(K%C3%B6ln)". The name is percent-encoded, so
     * a bracket or a comma in it can't cut the query short.
     */
    fun geoUri(place: RecordingPlace, label: String): String {
        val point = String.format(Locale.ROOT, "%.6f,%.6f", place.latitude, place.longitude)
        return "geo:$point?q=$point(${percentEncoded(label)})"
    }

    /** [text] as UTF-8 with everything but letters, digits and `-._~` written %XX. */
    private fun percentEncoded(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
                append(char)
            } else {
                append(String.format(Locale.ROOT, "%%%02X", code))
            }
        }
    }
}

/**
 * How [label] reads in the app's language: the place's name ("Köln"), "Near Köln" when the
 * recording started more than 3 km from its centre, or the coordinates of a place without a name.
 */
@Composable
internal fun placeText(label: PlaceLabel): String {
    val name = label.place.name
    return when {
        name == null -> PlaceText.coordinates(label.place)
        label.near -> stringResource(R.string.g_place_near, name)
        else -> name
    }
}

/** How far off a position may be: "About 12 m" up to a kilometre, then "About 2.5 km". */
@Composable
internal fun placeAccuracyText(meters: Float): String =
    if (meters < 1_000F) {
        stringResource(R.string.g_place_accuracy_m, meters.roundToInt().coerceAtLeast(1))
    } else {
        val kilometres = String.format(LocalConfiguration.current.locales[0], "%.1f", meters / 1_000F)
        stringResource(R.string.g_place_accuracy_km, kilometres)
    }
