package com.satori.domain.language

import com.satori.domain.fakes.segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A model that knows one language, or is told it, decodes the whole recording in it: the language
 * counts only when the recording is in it throughout.
 */
class SpokenLanguageTest {

    // Each at least SpokenLanguage.STRETCH_WORDS words long.
    private val launch = "We agreed to move the launch to March, and Anna will update the plan before Friday so the whole team can review it."
    private val cover = "I think the cover screen should show the timer and the last line, because that is what I look at when I walk."
    private val budget = "Then we talked about the budget for the next quarter and who is going to call the landlord about the heating."
    private val start = "Wir haben heute im Team besprochen, dass der Start auf März verschoben wird und Anna den Plan bis Freitag aktualisiert."
    private val heating = "Danach müssen wir noch mit dem Vermieter über die Heizung sprechen, weil sie seit einer Woche nicht mehr richtig funktioniert."
    // Malayalam, each at least STRETCH_WORDS words: after work go to the shop for milk, bread and
    // vegetables, then call home and ask how mother is; tomorrow at ten there is a meeting with the
    // team, the report has to be sent before it, then the budget and next month's trip.
    private val shop = "ഇന്ന് വൈകുന്നേരം ജോലി കഴിഞ്ഞ് കടയിൽ പോയി പാലും റൊട്ടിയും പച്ചക്കറികളും വാങ്ങണം, " +
        "പിന്നെ വീട്ടിലേക്ക് വിളിച്ച് അമ്മയോട് സുഖമാണോ എന്ന് ചോദിക്കണം, അത് മറക്കരുത്, ശരിക്കും മറക്കരുത്"
    private val team = "നാളെ രാവിലെ പത്ത് മണിക്ക് ടീമുമായി മീറ്റിംഗ് ഉണ്ട്, അതിന് മുമ്പ് റിപ്പോർട്ട് അയക്കണം, " +
        "പിന്നെ ബജറ്റിനെ കുറിച്ച് സംസാരിക്കണം, അടുത്ത മാസത്തെ യാത്രയെ കുറിച്ചും ചോദിക്കണം, അത്രയേ ഉള്ളൂ"

    private fun lines(vararg texts: String) = texts.mapIndexed { index, text -> segment(text, at = 1_000L * (index + 1)) }

    @Test
    fun `a recording in one language is in it`() {
        assertEquals("en", SpokenLanguage.of(lines(launch, cover, budget)))
        assertEquals("de", SpokenLanguage.of(lines(start, heating)))
    }

    @Test
    fun `a recording in both languages, half and half, is in neither`() {
        // The detector is unsure of the whole: where the stored notes language once stood in,
        // a model told English would have rewritten the German half.
        assertNull(LanguageDetector.detect(listOf(launch, start, cover, heating).joinToString(" ")))

        assertNull(SpokenLanguage.of(lines(launch, start, cover, heating)))
    }

    @Test
    fun `a stretch in the other language makes it mixed, also where the whole looks like one`() {
        val recording = lines(launch, cover, budget, start)
        assertEquals("en", LanguageDetector.detect(recording.joinToString(" ") { it.text }))

        assertNull(SpokenLanguage.of(recording))
    }

    @Test
    fun `a German phrase among English lines is too short to count`() {
        assertEquals("en", SpokenLanguage.of(lines(launch, "Das ist gut, sagte sie.", cover, budget)))
        // Also as the last words, which go with the stretch before them.
        assertEquals("en", SpokenLanguage.of(lines(launch, cover, "Ja, das ist es, und das war es dann auch schon.")))
    }

    @Test
    fun `a recording in Malayalam is in Malayalam`() {
        assertEquals("ml", SpokenLanguage.of(lines(shop, team)))
    }

    @Test
    fun `a stretch of English in a Malayalam recording makes it mixed`() {
        assertNull(SpokenLanguage.of(lines(shop, team, launch)))
    }

    @Test
    fun `no answer without a sure one`() {
        assertNull(SpokenLanguage.of(lines("Okay then")))
        assertNull(SpokenLanguage.of(emptyList()))
    }

    @Test
    fun `only complete lines count`() {
        val partial = segment(launch, at = 1L, complete = false)

        assertNull(SpokenLanguage.of(listOf(partial, segment("Okay then", at = 2L))))
    }
}
