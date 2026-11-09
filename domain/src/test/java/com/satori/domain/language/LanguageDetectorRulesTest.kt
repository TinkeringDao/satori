package com.satori.domain.language

import com.satori.domain.language.LanguageDetector.ENGLISH
import com.satori.domain.language.LanguageDetector.GERMAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the thresholds ([LanguageDetector.MIN_WORDS], the evidence and ratio rules) and the
 * spellings that used to point to the wrong language ("th" in German, "sch" in English).
 */
class LanguageDetectorRulesTest {

    @Test
    fun `German th words alone give no answer`() {
        // Thema, Theater, Methodik, Rathaus, Apotheke, Bibliothek, Thermalbad, Thomas: German, but
        // nothing in them says so, and "th" must not be taken for English.
        assertNull(
            LanguageDetector.detect("Thema Theater Methodik Rathaus Apotheke Bibliothek Thermalbad Thomas")
        )
    }

    @Test
    fun `a German sentence with many th words is German`() {
        assertEquals(
            GERMAN,
            LanguageDetector.detect(
                "Das Thema ist nicht das Theater von Thomas, sondern die Methodik, der Rathaus-Termin " +
                    "und die Bibliothek"
            )
        )
    }

    @Test
    fun `English sch words alone give no answer`() {
        // "sch" must not be taken for German.
        assertNull(
            LanguageDetector.detect("Schedule school Schmidt Schneider Schwartz Schultz schema scheme")
        )
    }

    @Test
    fun `an English sentence with sch words is English`() {
        assertEquals(
            ENGLISH,
            LanguageDetector.detect(
                "Please schedule the school meeting with Schmidt and Schneider for the weekend"
            )
        )
    }

    @Test
    fun `the word count is the limit, not the number of cues`() {
        // Seven words, all of them German function words.
        assertNull(LanguageDetector.detect("Ich habe das nicht mit ihm und"))
        // One more makes eight.
        assertEquals(GERMAN, LanguageDetector.detect("Ich habe das nicht mit ihm und dann"))
    }

    @Test
    fun `two cues are too few, three are enough`() {
        assertNull(LanguageDetector.detect("Milk eggs bread butter cheese and the coffee"))
        assertEquals(ENGLISH, LanguageDetector.detect("Milk eggs bread butter cheese and the coffee too"))
    }

    @Test
    fun `the leading language needs twice the other one`() {
        // Five English cues against three German ones: ahead by two, but not twice as many.
        assertNull(LanguageDetector.detect("the and you we it der die das"))
        // Against two, it is.
        assertEquals(ENGLISH, LanguageDetector.detect("the and you we it der die today"))
    }
}
