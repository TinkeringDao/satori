package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "Save the place" switch shows on only while the setting is on and location is allowed, and
 * says why when location was refused, is only approximate, or is off on the phone.
 */
class SavePlaceRowStateTest {

    private fun state(savePlace: Boolean, access: LocationAccess, locationOn: Boolean = true, refused: Boolean = false) =
        SavePlaceRowState.of(savePlace, access, locationOn, refused)

    @Test
    fun `on with the precise location it is on and says what it does`() {
        assertEquals(SavePlaceRowState(true, SavePlaceNote.NONE), state(true, LocationAccess.PRECISE))
    }

    @Test
    fun `off it is off and says what it does, whatever is allowed`() {
        assertEquals(SavePlaceRowState(false, SavePlaceNote.NONE), state(false, LocationAccess.PRECISE))
        assertEquals(SavePlaceRowState(false, SavePlaceNote.NONE), state(false, LocationAccess.APPROXIMATE, locationOn = false))
        assertEquals(SavePlaceRowState(false, SavePlaceNote.NONE), state(false, LocationAccess.NONE))
    }

    @Test
    fun `refused it goes back off and says so`() {
        assertEquals(SavePlaceRowState(false, SavePlaceNote.REFUSED), state(false, LocationAccess.NONE, refused = true))
    }

    @Test
    fun `a permission taken away in Android's settings shows it off`() {
        assertEquals(SavePlaceRowState(false, SavePlaceNote.NONE), state(true, LocationAccess.NONE))
    }

    @Test
    fun `only the approximate location keeps it on with a word of warning`() {
        assertEquals(SavePlaceRowState(true, SavePlaceNote.APPROXIMATE), state(true, LocationAccess.APPROXIMATE))
    }

    @Test
    fun `the phone's location off keeps it on and says no place is saved`() {
        assertEquals(SavePlaceRowState(true, SavePlaceNote.LOCATION_OFF), state(true, LocationAccess.PRECISE, locationOn = false))
        assertEquals(SavePlaceRowState(true, SavePlaceNote.LOCATION_OFF), state(true, LocationAccess.APPROXIMATE, locationOn = false))
    }
}
