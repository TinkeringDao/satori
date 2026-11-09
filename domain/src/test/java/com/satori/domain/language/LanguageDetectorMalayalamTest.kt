package com.satori.domain.language

import com.satori.domain.language.LanguageDetector.ENGLISH
import com.satori.domain.language.LanguageDetector.GERMAN
import com.satori.domain.language.LanguageDetector.MALAYALAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A text mostly in Malayalam script is Malayalam, never English: the transcript of the Malayalam
 * model, with English words in Malayalam letters, and one with a few English words in Latin
 * letters too. An English or German text with a Malayalam name stays what it is.
 */
class LanguageDetectorMalayalamTest {

    // Go to the shop this evening and buy milk and bread.
    private val shop = "ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം"

    // Tomorrow at ten there is a meeting with the team: "team" and "meeting" in Malayalam letters,
    // as the Malayalam model writes English words.
    private val meeting = "നാളെ രാവിലെ പത്ത് മണിക്ക് ടീമുമായി മീറ്റിംഗ് ഉണ്ട്"

    @Test
    fun `a Malayalam text is Malayalam`() {
        assertEquals(MALAYALAM, LanguageDetector.detect(shop))
        assertEquals(MALAYALAM, LanguageDetector.detect("$shop $meeting"))
    }

    @Test
    fun `English words in Malayalam letters keep it Malayalam`() {
        assertEquals(MALAYALAM, LanguageDetector.detect(meeting))
    }

    @Test
    fun `a few English words in Latin letters keep it Malayalam`() {
        // A meeting tomorrow morning; the report has to be sent before it.
        assertEquals(MALAYALAM, LanguageDetector.detect("നാളെ രാവിലെ meeting ഉണ്ട്, അതിന് മുമ്പ് report അയക്കണം"))
        // English function words that would be cues for English do not outweigh the script.
        assertEquals(MALAYALAM, LanguageDetector.detect("$shop and then the $meeting, is it?"))
    }

    @Test
    fun `the atomic chillus count as Malayalam letters`() {
        // He didn't come yesterday, she comes tomorrow: chillu n and chillu ll, U+0D7B and U+0D7E.
        assertEquals(MALAYALAM, LanguageDetector.detect("അവൻ ഇന്നലെ വന്നില്ല, അവൾ നാളെ വരും"))
    }

    @Test
    fun `a short Malayalam text is sure too, as no other language is written in its script`() {
        // OK, thanks.
        assertEquals(MALAYALAM, LanguageDetector.detect("ശരി, നന്ദി"))
    }

    @Test
    fun `an English or German text with a Malayalam name stays English or German`() {
        assertEquals(
            ENGLISH,
            LanguageDetector.detect("We met ശ്രീധരൻ at the station and talked about the budget for the next quarter.")
        )
        assertEquals(
            GERMAN,
            LanguageDetector.detect("Wir haben ശ്രീധരൻ am Bahnhof getroffen und über das Budget für das nächste Quartal gesprochen.")
        )
    }

    @Test
    fun `half Latin and half Malayalam is not mostly Malayalam`() {
        // We need the report today, once in each script: 20 letters each.
        val half = "We need the report today. ഇന്ന് റിപ്പോർട്ട് വേണം"

        assertFalse(LanguageDetector.isMalayalam(half))
        assertNull(LanguageDetector.detect(half))
    }

    @Test
    fun `digits and punctuation count for neither script`() {
        assertFalse(LanguageDetector.isMalayalam("൧൨൩ ൪൫ 12 34, 1:30"))
        assertNull(LanguageDetector.detect("൧൨൩ ൪൫ 12 34, 1:30"))
        // Malayalam digits beside a Malayalam word: the word alone decides.
        assertTrue(LanguageDetector.isMalayalam("൧൨൩൪൫൬൭൮ ശരി"))
    }

    @Test
    fun `an empty text is no Malayalam`() {
        assertFalse(LanguageDetector.isMalayalam(""))
        assertNull(LanguageDetector.detect(""))
    }
}
