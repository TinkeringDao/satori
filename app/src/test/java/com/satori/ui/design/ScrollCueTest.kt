package com.satori.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line that says a list goes on shows while more follows below, while it moves and a moment
 * after, and is as long and as far down as a scroll bar's thumb would be. A list that fits shows
 * none. And a list sheet running through the cover's camera band keeps the band's depth past the
 * navigation bar clear at its end.
 */
class ScrollCueTest {

    @Test
    fun `it shows while more follows, while moving and a moment after, never on a list that fits`() {
        assertTrue(ScrollCue.visible(canScrollForward = true, canScrollBackward = false, moving = false, recentlyMoved = false))
        assertTrue(ScrollCue.visible(canScrollForward = false, canScrollBackward = true, moving = true, recentlyMoved = false))
        assertTrue(ScrollCue.visible(canScrollForward = false, canScrollBackward = true, moving = false, recentlyMoved = true))
        // At the end of the list, still: nothing more follows.
        assertFalse(ScrollCue.visible(canScrollForward = false, canScrollBackward = true, moving = false, recentlyMoved = false))
        assertFalse(ScrollCue.visible(canScrollForward = false, canScrollBackward = false, moving = true, recentlyMoved = true))
        assertEquals(1_000L, ScrollCue.LINGER_MS)
    }

    @Test
    fun `the line is as long as the share of the list that shows, and moves down with it`() {
        // A list three times as long as its viewport.
        assertEquals(ScrollCue.Thumb(top = 0f, length = 300f), ScrollCue.thumb(viewport = 900f, content = 2700f, offset = 0f, minLength = 48f))
        assertEquals(ScrollCue.Thumb(top = 300f, length = 300f), ScrollCue.thumb(viewport = 900f, content = 2700f, offset = 900f, minLength = 48f))
        assertEquals(ScrollCue.Thumb(top = 600f, length = 300f), ScrollCue.thumb(viewport = 900f, content = 2700f, offset = 1800f, minLength = 48f))
        // A very long list keeps a line long enough to see.
        assertEquals(48f, ScrollCue.thumb(viewport = 900f, content = 900_000f, offset = 0f, minLength = 48f)!!.length)
        assertNull(ScrollCue.thumb(viewport = 900f, content = 900f, offset = 0f, minLength = 48f))
        assertNull(ScrollCue.thumb(viewport = 0f, content = 900f, offset = 0f, minLength = 48f))
    }

    @Test
    fun `a list running through the camera band keeps the band past the navigation bar clear`() {
        // The razr's band, 349 px at 306 dpi, holds the 58 px navigation bar.
        assertEquals(152.dp, sheetBandClearance(band = 182.dp, navigationBar = 30.dp))
        assertEquals(0.dp, sheetBandClearance(band = 20.dp, navigationBar = 30.dp))
    }
}
