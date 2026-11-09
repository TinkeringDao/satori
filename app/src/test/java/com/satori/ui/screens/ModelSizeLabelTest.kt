package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ModelSizeLabelTest {

    private fun en(bytes: Long) = modelSizeLabel(bytes, Locale.ENGLISH)

    @Test
    fun `below a gigabyte the nearest megabyte, with a no-break space`() {
        assertEquals("670\u00A0MB", en(670_478_772L))
        assertEquals("138\u00A0MB", en(137_566_803L))
        // Get ready's button: speech recognition and Malayalam together.
        assertEquals("808\u00A0MB", en(808_045_575L))
        assertEquals("600\u00A0MB", en(600_000_000L))
    }

    @Test
    fun `from a gigabyte one decimal`() {
        assertEquals("1.1\u00A0GB", en(1_069_306_368L))
        assertEquals("3.3\u00A0GB", en(3_349_516_256L))
        // Get ready short of space: 808 MB and 300 MB of room.
        assertEquals("1.1\u00A0GB", en(1_108_045_575L))
        // Rounds up into a gigabyte rather than to 1000 MB.
        assertEquals("1.0\u00A0GB", en(999_600_000L))
    }

    @Test
    fun `German writes the decimal with a comma`() {
        assertEquals("1,1\u00A0GB", modelSizeLabel(1_069_306_368L, Locale.GERMAN))
        assertEquals("3,3\u00A0GB", modelSizeLabel(3_349_516_256L, Locale.GERMAN))
        assertEquals("808\u00A0MB", modelSizeLabel(808_045_575L, Locale.GERMAN))
    }
}
