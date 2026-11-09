package com.satori.domain.language

import com.satori.domain.language.LanguageDetector.ENGLISH
import com.satori.domain.language.LanguageDetector.GERMAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `detects English`() {
        assertEquals(
            ENGLISH,
            LanguageDetector.detect(
                "We agreed to move the launch to March, and Anna will update the plan before " +
                    "Friday so that the whole team can review it on Monday morning."
            )
        )
        assertEquals(ENGLISH, LanguageDetector.detect("I think we should call the bank about it tomorrow."))
    }

    @Test
    fun `detects German`() {
        assertEquals(
            GERMAN,
            LanguageDetector.detect(
                "Also, der Umzug nach Köln ist jetzt für den fünfzehnten November geplant. " +
                    "Den Transporter muss ich noch diese Woche reservieren."
            )
        )
        assertEquals(GERMAN, LanguageDetector.detect("Ich muss morgen noch die Bank anrufen, weil das Konto gesperrt ist."))
    }

    @Test
    fun `an evenly mixed text gives no answer`() {
        assertNull(
            LanguageDetector.detect("We need to finish the report today. Wir müssen den Bericht heute fertig machen.")
        )
    }

    @Test
    fun `a German text with some English words is German`() {
        assertEquals(
            GERMAN,
            LanguageDetector.detect(
                "Wir haben das Meeting auf nächste Woche verschoben, weil der Kunde noch auf das " +
                    "Feedback wartet and the deadline is flexible."
            )
        )
    }

    @Test
    fun `an English text with German names is English`() {
        assertEquals(
            ENGLISH,
            LanguageDetector.detect("We met Jürgen and Björn in München to talk about the budget for the next quarter.")
        )
    }

    @Test
    fun `too short a text gives no answer`() {
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("Hello there, how are you?"))
        assertNull(LanguageDetector.detect("Ich bin gleich wieder da."))
        // Seven words are one short, however clear: see LanguageDetectorRulesTest for the eighth.
        assertNull(LanguageDetector.detect("Ich habe das nicht mit ihm und"))
    }

    @Test
    fun `umlauts and sharp s count for German, also in capitals`() {
        assertEquals(GERMAN, LanguageDetector.detect("ÜBER DIE GRÖSSE DER RÄUME MÜSSEN WIR NOCH SPRECHEN"))
        assertEquals(
            GERMAN,
            LanguageDetector.detect("Schöne Grüße aus München nach Köln über Düsseldorf und Würzburg")
        )
        // Without a single function word, the spelling alone decides.
        assertEquals(GERMAN, LanguageDetector.detect("Größe Länge Höhe Brücke Mädchen Übung Ärger Öffnung"))
    }

    @Test
    fun `numbers alone give no answer`() {
        assertNull(LanguageDetector.detect("12 34 56 78 90 11 22 33 44 55 66 77"))
        assertNull(LanguageDetector.detect("3:32, 1:24:10, 42 %, 650 MB, 0.5 s, 2026-10-10"))
    }

    @Test
    fun `words with nothing to go on give no answer`() {
        assertNull(LanguageDetector.detect("Milk eggs bread butter cheese apples bananas coffee"))
    }

    @Test
    fun `reads curly apostrophes and contractions`() {
        assertEquals(
            ENGLISH,
            LanguageDetector.detect("I don’t think we’re ready, but they’ve said it’s fine for now.")
        )
    }

    @Test
    fun `punctuation and line breaks separate words`() {
        assertEquals(listOf("hallo", "welt", "wie", "geht's"), LanguageDetector.words("Hallo,Welt!\nWie geht's?"))
    }

    @Test
    fun `no word counts for both languages`() {
        val both = LanguageDetector.ENGLISH_WORDS intersect LanguageDetector.GERMAN_WORDS
        assertTrue("in both lists: $both", both.isEmpty())
    }
}
