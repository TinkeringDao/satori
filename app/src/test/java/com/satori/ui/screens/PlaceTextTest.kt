package com.satori.ui.screens

import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.PlaceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * How the place shows before and after its lookup, the place line of the Markdown file and of a
 * copy, the coordinates of a place without a name, and the address a maps app opens.
 */
class PlaceTextTest {

    private val cologne = RecordingPlace(latitude = 50.9375, longitude = 6.9603, accuracyMeters = 12F, name = "Köln")

    @Test
    fun `the stored place shows at once, and as looked up once that is done`() {
        val near = PlaceLabel(cologne, near = true)

        assertEquals(PlaceLabel(cologne, near = false), PlaceText.shown(cologne, looked = null))
        assertEquals(near, PlaceText.shown(cologne, looked = near))
    }

    @Test
    fun `a lookup of a place no longer stored does not show`() {
        val other = RecordingPlace(latitude = 48.1374, longitude = 11.5755, accuracyMeters = 5F, name = "Munich")
        val lookedUp = PlaceLabel(cologne, near = true)

        assertNull(PlaceText.shown(null, lookedUp))
        assertNull(PlaceText.shown(null, null))
        assertEquals(PlaceLabel(other, near = false), PlaceText.shown(other, lookedUp))
    }

    @Test
    fun `the Markdown file has the place under the date and the length`() {
        assertEquals("- Place: Köln", PlaceText.markdownItem("Place", "Köln"))
        assertEquals("- Ort: Bei Köln", PlaceText.markdownItem("Ort", "Bei Köln"))
    }

    @Test
    fun `a copy starts with the place, then a blank line`() {
        assertEquals("Place: Near Köln\n\nfirst line\nsecond line", PlaceText.withPlace("Place", "Near Köln", "first line\nsecond line"))
    }

    @Test
    fun `a copy of a recording without a place is the text alone`() {
        assertEquals("first line", PlaceText.withPlace("Place", null, "first line"))
    }

    @Test
    fun `coordinates read the same in every language, south and west negative`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("50.93750, 6.96030", PlaceText.coordinates(cologne))
            assertEquals("-33.86882, -151.20929", PlaceText.coordinates(RecordingPlace(-33.86882, -151.209290, null, null)))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `the maps address has a pin with the name, encoded`() {
        assertEquals(
            "geo:50.937500,6.960300?q=50.937500,6.960300(K%C3%B6ln)",
            PlaceText.geoUri(cologne, "Köln")
        )
        assertEquals(
            "geo:50.937500,6.960300?q=50.937500,6.960300(Near%20K%C3%B6ln%20%28Rhein%29%2C%20DE)",
            PlaceText.geoUri(cologne, "Near Köln (Rhein), DE")
        )
    }
}
