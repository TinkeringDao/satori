package com.satori.ui.design

import android.view.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The razr 70 plus as measured: cover 1080x1272 px at density 1.9125 with the cameras 495x349 px at
 * the bottom right upright (top left upside down), a navigation bar of 58 px and no status bar
 * inset; inner 1080x2640 px at 2.225 with a punch hole in the status bar.
 */
class CameraBandTest {

    private val cover = 1.9125f
    private val density = Density(cover)
    private val upright = IntRect(585, 923, 1080, 1272)

    private fun px(value: Int) = (value / cover).dp

    @Test
    fun `the cover held upright has the band at the bottom, the cameras at its right`() {
        val band = cameraBandOf(1080, 1272, listOf(upright), cover)

        assertEquals(CameraBand(BandEdge.BOTTOM, depth = px(349), cameraLength = px(495), cameraAtStart = false), band)
    }

    @Test
    fun `the cover turned upside down has the band at the top, the cameras at its left`() {
        val band = cameraBandOf(1080, 1272, listOf(IntRect(0, 0, 495, 349)), cover)

        assertEquals(CameraBand(BandEdge.TOP, depth = px(349), cameraLength = px(495), cameraAtStart = true), band)
    }

    @Test
    fun `the cover on its side has the band along that side, 349 px deep, not 495 px along the top or bottom`() {
        val turned90 = cameraBandOf(1272, 1080, listOf(rotated(upright, 1080, 1272, Surface.ROTATION_90)), cover)
        val turned270 = cameraBandOf(1272, 1080, listOf(rotated(upright, 1080, 1272, Surface.ROTATION_270)), cover)

        assertEquals(CameraBand(BandEdge.RIGHT, depth = px(349), cameraLength = px(495), cameraAtStart = true), turned90)
        assertEquals(CameraBand(BandEdge.LEFT, depth = px(349), cameraLength = px(495), cameraAtStart = false), turned270)
    }

    @Test
    fun `the inner screen's punch hole is no band, upright or on its side`() {
        assertNull(cameraBandOf(1080, 2640, listOf(IntRect(500, 0, 580, 108)), 2.225f))
        assertNull(cameraBandOf(2640, 1080, listOf(IntRect(0, 500, 108, 580)), 2.225f))
    }

    @Test
    fun `a cutout without bounds, with no room beside the cameras, or no window, is no band`() {
        assertNull(cameraBandOf(1080, 1272, emptyList(), cover))
        // As wide as the cover: no room beside it, and as a band along a side it would take the whole width.
        assertNull(cameraBandOf(1080, 1272, listOf(IntRect(0, 923, 1080, 1272)), cover))
        assertNull(cameraBandOf(0, 0, listOf(upright), cover))
    }

    @Test
    fun `the cameras turn with the display as Android turns the cutout`() {
        assertEquals(upright, rotated(upright, 1080, 1272, Surface.ROTATION_0))
        assertEquals(IntRect(0, 0, 495, 349), rotated(upright, 1080, 1272, Surface.ROTATION_180))
        assertEquals(IntRect(923, 0, 1272, 495), rotated(upright, 1080, 1272, Surface.ROTATION_90))
        assertEquals(IntRect(0, 585, 349, 1080), rotated(upright, 1080, 1272, Surface.ROTATION_270))
    }

    @Test
    fun `an emulator draws in the cameras debug_satori_cover_band names, and nothing for anything else`() {
        assertEquals(IntSize(495, 349), simulatedCameras("495x349"))
        assertEquals(IntSize(495, 349), simulatedCameras(" 495 X 349 "))
        assertNull(simulatedCameras(""))
        assertNull(simulatedCameras(null))
        assertNull(simulatedCameras("495"))
        assertNull(simulatedCameras("0x349"))
        assertNull(simulatedCameras("wide x low"))
    }

    @Test
    fun `the band holds the grille upright and upside down, at font 1_0 and 2_0, and never on a side`() {
        val bottom = CameraBand(BandEdge.BOTTOM, px(349), px(495), cameraAtStart = false)
        val top = CameraBand(BandEdge.TOP, px(349), px(495), cameraAtStart = true)
        val side = CameraBand(BandEdge.RIGHT, px(349), px(495), cameraAtStart = true)
        // Home's grille: 96 dp, 8 dp of space and the word's line of 20 sp (40 sp at font 2.0).
        val home = 96.dp + 8.dp + 20.dp
        val homeLargest = 96.dp + 8.dp + 40.dp
        // The recorder's: 104 dp.
        val recorder = 104.dp + 8.dp + 20.dp

        // Upright the navigation bar takes 58 of the band's 349 px.
        assertTrue(holdsGrille(bottom, px(349 - 58), home))
        assertTrue(holdsGrille(bottom, px(349 - 58), homeLargest))
        assertTrue(holdsGrille(bottom, px(349 - 58), recorder))
        assertTrue(holdsGrille(top, px(349), recorder))
        assertFalse(holdsGrille(side, px(349), home))
    }

    @Test
    fun `a band the system bars cover holds no grille, and the screen then keeps clear of it`() {
        val top = CameraBand(BandEdge.TOP, px(349), px(495), cameraAtStart = true)

        assertFalse(holdsGrille(top, 0.dp, 96.dp + 8.dp + 20.dp))
        assertFalse(holdsGrille(top, px(100), 96.dp + 8.dp + 20.dp))
    }

    @Test
    fun `a screen kept clear of the band at the bottom ends where the band starts, not 58 px into it`() {
        val bottom = CameraBand(BandEdge.BOTTOM, px(349), px(495), cameraAtStart = false)
        val navigationBar = WindowInsets(bottom = px(58))

        // 0.6.0 padded 349 - 58 px and took the navigation bar: the page ended at y=981, in the band.
        assertEquals(349, clearance(bottom, navigationBar).getBottom(density))
    }

    @Test
    fun `a screen kept clear of the band at the top starts below it, or below a status bar that reaches further`() {
        val top = CameraBand(BandEdge.TOP, px(349), px(495), cameraAtStart = true)

        assertEquals(349, clearance(top, WindowInsets(top = 0.dp)).getTop(density))
        assertEquals(400, clearance(top, WindowInsets(top = px(400))).getTop(density))
        assertEquals(0, clearance(top, WindowInsets(top = 0.dp)).getBottom(density))
    }

    @Test
    fun `a window Android draws off its top is logged with how far and which way, and its return`() {
        assertEquals("Window drawn 291 px lower than its top: Android scrolled it to show the focused control", windowScrollLine(291))
        assertEquals("Window drawn 58 px higher than its top: Android scrolled it to show the focused control", windowScrollLine(-58))
        assertEquals("Window drawn at its top again", windowScrollLine(0))
    }

    @Test
    fun `the keyboard over the band lifts a screen clear of the band only by what it reaches past it`() {
        val bottom = CameraBand(BandEdge.BOTTOM, px(349), px(495), cameraAtStart = false)
        val taken = clearance(bottom, WindowInsets(bottom = px(58)))

        assertEquals(600 - 349, WindowInsets(bottom = px(600)).exclude(taken).getBottom(density))
        assertEquals(0, WindowInsets(bottom = px(200)).exclude(taken).getBottom(density))
    }

    @Test
    fun `a sheet is small only with the keyboard up and the band at the top`() {
        val top = cameraBandOf(1080, 1272, listOf(IntRect(0, 0, 495, 349)), cover)
        val bottom = cameraBandOf(1080, 1272, listOf(upright), cover)

        // The 0.6.1 phone review: upside down, the keyboard left Rename's sheet some 290 px.
        assertTrue(sheetIsSmall(top, keyboardUp = true))
        assertFalse(sheetIsSmall(top, keyboardUp = false))
        // Upright the keyboard covers the band; the inner screen has none.
        assertFalse(sheetIsSmall(bottom, keyboardUp = true))
        assertFalse(sheetIsSmall(null, keyboardUp = true))
        assertFalse(sheetIsSmall(CameraBand(BandEdge.LEFT, depth = px(349), cameraLength = px(495), cameraAtStart = true), keyboardUp = true))
    }
}
