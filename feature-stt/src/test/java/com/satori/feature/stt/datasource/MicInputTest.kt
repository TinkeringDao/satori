package com.satori.feature.stt.datasource

import com.satori.domain.model.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicInputTest {

    /** Opens the inputs in [opens], counting each attempt in [tried]. */
    private class Phone(private val opens: Set<MicSource>) {
        val tried = mutableListOf<MicSource>()
        fun open(source: MicSource): String? {
            tried += source
            return if (source in opens) "record from $source" else null
        }
    }

    @Test
    fun `records from the input asked for when it opens`() {
        val phone = Phone(opens = setOf(MicSource.VOICE_RECOGNITION, MicSource.MIC))

        val opened = MicInput().open(MicSource.VOICE_RECOGNITION, phone::open)!!

        assertEquals(MicSource.VOICE_RECOGNITION, opened.source)
        assertEquals("record from VOICE_RECOGNITION", opened.input)
        assertFalse(opened.fellBack)
        assertFalse(opened.reportFallback)
        assertEquals(listOf(MicSource.VOICE_RECOGNITION), phone.tried)
    }

    @Test
    fun `falls back to the general input when the speech recognition input doesn't open`() {
        val phone = Phone(opens = setOf(MicSource.MIC))

        val opened = MicInput().open(MicSource.VOICE_RECOGNITION, phone::open)!!

        assertEquals(MicSource.MIC, opened.source)
        assertEquals("record from MIC", opened.input)
        assertTrue(opened.fellBack)
        assertEquals(listOf(MicSource.VOICE_RECOGNITION, MicSource.MIC), phone.tried)
    }

    @Test
    fun `records from the input for calls when it opens`() {
        val phone = Phone(opens = setOf(MicSource.VOICE_COMMUNICATION, MicSource.MIC))

        val opened = MicInput().open(MicSource.VOICE_COMMUNICATION, phone::open)!!

        assertEquals(MicSource.VOICE_COMMUNICATION, opened.source)
        assertEquals("record from VOICE_COMMUNICATION", opened.input)
        assertFalse(opened.fellBack)
        assertEquals(listOf(MicSource.VOICE_COMMUNICATION), phone.tried)
    }

    @Test
    fun `falls back to the general input when the input for calls doesn't open`() {
        val phone = Phone(opens = setOf(MicSource.MIC))

        val opened = MicInput().open(MicSource.VOICE_COMMUNICATION, phone::open)!!

        assertEquals(MicSource.MIC, opened.source)
        assertEquals("record from MIC", opened.input)
        assertTrue(opened.fellBack)
        assertTrue(opened.reportFallback)
        assertEquals(listOf(MicSource.VOICE_COMMUNICATION, MicSource.MIC), phone.tried)
    }

    @Test
    fun `no input when neither the input for calls nor the general one opens`() {
        val phone = Phone(opens = emptySet())

        assertNull(MicInput().open(MicSource.VOICE_COMMUNICATION, phone::open))
        assertEquals(listOf(MicSource.VOICE_COMMUNICATION, MicSource.MIC), phone.tried)
    }

    @Test
    fun `only the first fallback after the app starts is reported`() {
        val input = MicInput()
        val phone = Phone(opens = setOf(MicSource.MIC))

        val reports = List(3) { input.open(MicSource.VOICE_RECOGNITION, phone::open)!!.reportFallback }

        assertEquals(listOf(true, false, false), reports)
    }

    @Test
    fun `a recording that opens the input asked for doesn't use up the report`() {
        val input = MicInput()

        input.open(MicSource.VOICE_RECOGNITION, Phone(opens = setOf(MicSource.VOICE_RECOGNITION))::open)

        assertTrue(input.open(MicSource.VOICE_RECOGNITION, Phone(opens = setOf(MicSource.MIC))::open)!!.reportFallback)
    }

    @Test
    fun `the general input asked for is tried once, with nothing to fall back to`() {
        val phone = Phone(opens = emptySet())

        assertNull(MicInput().open(MicSource.MIC, phone::open))
        assertEquals(listOf(MicSource.MIC), phone.tried)
    }

    @Test
    fun `no input when neither opens`() {
        val phone = Phone(opens = emptySet())

        assertNull(MicInput().open(MicSource.VOICE_RECOGNITION, phone::open))
        assertEquals(listOf(MicSource.VOICE_RECOGNITION, MicSource.MIC), phone.tried)
    }
}
