package com.satori.presentation.mindmap

import org.junit.Assert.assertEquals
import org.junit.Test

class MapFormTest {

    @Test
    fun `the map opens as the drawing on the cover screen, alone under the top bar`() {
        assertEquals(MapForm.DRAWING, MapForm.of(chosen = null, touchExploration = false, compact = true, canDraw = true))
    }

    @Test
    fun `the map opens as the drawing over its pane on the inner screen`() {
        assertEquals(MapForm.DRAWING_AND_PANE, MapForm.of(chosen = null, touchExploration = false, compact = false, canDraw = true))
    }

    @Test
    fun `with TalkBack on the map opens as the list, on either screen`() {
        assertEquals(MapForm.LIST, MapForm.of(chosen = null, touchExploration = true, compact = true, canDraw = true))
        assertEquals(MapForm.LIST, MapForm.of(chosen = null, touchExploration = true, compact = false, canDraw = true))
    }

    @Test
    fun `a choice made with the toggle wins over both`() {
        assertEquals(MapForm.LIST, MapForm.of(chosen = true, touchExploration = false, compact = true, canDraw = true))
        assertEquals(MapForm.LIST, MapForm.of(chosen = true, touchExploration = false, compact = false, canDraw = true))
        assertEquals(MapForm.DRAWING, MapForm.of(chosen = false, touchExploration = true, compact = true, canDraw = true))
        assertEquals(MapForm.DRAWING_AND_PANE, MapForm.of(chosen = false, touchExploration = true, compact = false, canDraw = true))
    }

    @Test
    fun `a view with nothing to draw is its list whatever the choice`() {
        assertEquals(MapForm.LIST, MapForm.of(chosen = false, touchExploration = false, compact = true, canDraw = false))
        assertEquals(MapForm.LIST, MapForm.of(chosen = null, touchExploration = false, compact = false, canDraw = false))
    }
}
