package com.satori.data.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone's country is the first country code it reports (SIM, then network), in upper case;
 * none when it reports none, rather than a guess.
 */
class PhoneCountryTest {

    @Test
    fun `the first code reported is the country, in upper case`() {
        assertEquals("DE", firstCountryCode(sequenceOf("de", "at")))
    }

    @Test
    fun `a code that is unknown, empty or not a country code is passed over`() {
        assertEquals("AT", firstCountryCode(sequenceOf(null, "at")))
        assertEquals("AT", firstCountryCode(sequenceOf("", " at ")))
        assertEquals("IN", firstCountryCode(sequenceOf("310", "d1", "in")))
    }

    @Test
    fun `without a code there is no country`() {
        assertNull(firstCountryCode(sequenceOf(null, "")))
        assertNull(firstCountryCode(emptySequence()))
    }
}
