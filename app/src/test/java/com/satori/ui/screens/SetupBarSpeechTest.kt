package com.satori.ui.screens

import com.satori.domain.firstrun.FirstRunPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What TalkBack hears of Getting ready's bar (design G4): a download is announced at most once per 10 %. */
class SetupBarSpeechTest {

    @Test
    fun `the spoken percent is the percent down to its ten`() {
        assertEquals(0, spokenPercent(0))
        assertEquals(0, spokenPercent(9))
        assertEquals(30, spokenPercent(34))
        assertEquals(80, spokenPercent(81))
        assertEquals(100, spokenPercent(100))
        // Never more than is in, and never outside the bar.
        assertEquals(0, spokenPercent(-3))
        assertEquals(100, spokenPercent(130))
    }

    @Test
    fun `a part that changes within a ten is named from the next ten, so the setup is heard once per ten`() {
        // Speech recognition is 83 % of the setup with Malayalam: the bar goes on with Malayalam there.
        val download = (0..100).map { percent -> percent to if (percent < 83) FirstRunPart.SPEECH else FirstRunPart.MALAYALAM }
        val heard = download.runningFold(SpokenBar.of(0, FirstRunPart.SPEECH)) { bar, (percent, part) -> bar.next(percent, part) }
        val changes = heard.zipWithNext().count { (before, after) -> before != after }

        assertEquals(10, changes)
        assertEquals(SpokenBar(80, FirstRunPart.SPEECH), heard[84])
        assertEquals(SpokenBar(90, FirstRunPart.MALAYALAM), heard[91])
    }

    @Test
    fun `a download counted up by one percent changes what is heard ten times`() {
        val heard = (0..100).map(::spokenPercent)
        val changes = heard.zipWithNext().count { (before, after) -> before != after }

        assertEquals(10, changes)
        assertTrue(heard.zipWithNext().all { (before, after) -> after == before || after == before + 10 })
    }
}
