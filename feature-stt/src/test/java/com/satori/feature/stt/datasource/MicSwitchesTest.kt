package com.satori.feature.stt.datasource

import com.satori.feature.stt.datasource.MicSwitches.Companion.DEVICE_PROPERTY
import com.satori.feature.stt.datasource.MicSwitches.Companion.GAIN_PROPERTY
import com.satori.feature.stt.datasource.MicSwitches.Device
import com.satori.feature.stt.datasource.MicSwitches.Gain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone's debug switches: read only in a debug build, today's capture when none is set,
 * and a value they don't know ignored with a warning.
 */
class MicSwitchesTest {

    private val warnings = mutableListOf<String>()

    /** The switches of a debug build whose properties hold [set]; any other property is unset. */
    private fun switches(vararg set: Pair<String, String>): MicSwitches {
        val properties = set.toMap()
        return MicSwitches.forCapture(debuggable = true, read = { properties[it] ?: "" }, warn = { warnings += it })
    }

    @Test
    fun `a release build reads no property and records as it always has`() {
        var reads = 0
        val switches = MicSwitches.forCapture(
            debuggable = false,
            read = {
                reads++
                "back"
            },
            warn = { warnings += it }
        )

        assertEquals(MicSwitches.DEFAULT, switches)
        assertEquals(0, reads)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `with nothing set a debug build records as it always has too`() {
        assertEquals(MicSwitches.DEFAULT, switches())
        assertEquals(MicSwitches(Device.AUTO, Gain.Off), MicSwitches.DEFAULT)
        assertNull(Device.AUTO.address)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the device switch asks for the bottom or the back built-in microphone`() {
        assertEquals(Device.BOTTOM, switches(DEVICE_PROPERTY to "bottom").device)
        assertEquals(Device.BACK, switches(DEVICE_PROPERTY to " Back ").device)
        assertEquals(Device.AUTO, switches(DEVICE_PROPERTY to "auto").device)
        assertEquals("bottom", Device.BOTTOM.address)
        assertEquals("back", Device.BACK.address)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the gain switch is off, a fixed gain of up to 30 dB, or auto`() {
        assertEquals(Gain.Off, switches(GAIN_PROPERTY to "off").gain)
        assertEquals(Gain.Off, switches(GAIN_PROPERTY to "0").gain)
        assertEquals(Gain.Fixed(12.0), switches(GAIN_PROPERTY to "12").gain)
        assertEquals(Gain.Fixed(12.5), switches(GAIN_PROPERTY to "+12.5").gain)
        assertEquals(Gain.Fixed(9.0), switches(GAIN_PROPERTY to "9dB").gain)
        assertEquals(Gain.Fixed(30.0), switches(GAIN_PROPERTY to "30").gain)
        assertEquals(Gain.Auto, switches(GAIN_PROPERTY to "AUTO").gain)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a value it doesn't know is ignored with a warning, and the other switch still applies`() {
        assertEquals(MicSwitches(Device.AUTO, Gain.Fixed(12.0)), switches(DEVICE_PROPERTY to "front", GAIN_PROPERTY to "12"))
        assertEquals(MicSwitches(Device.BACK, Gain.Off), switches(DEVICE_PROPERTY to "back", GAIN_PROPERTY to "31"))
        assertEquals(Gain.Off, switches(GAIN_PROPERTY to "-6").gain)
        assertEquals(Gain.Off, switches(GAIN_PROPERTY to "loud").gain)

        assertEquals(
            listOf(
                "Ignoring debug.satori.mic.device=front: not auto, bottom or back",
                "Ignoring debug.satori.mic.gain=31: not off, auto or 0 to 30 dB",
                "Ignoring debug.satori.mic.gain=-6: not off, auto or 0 to 30 dB",
                "Ignoring debug.satori.mic.gain=loud: not off, auto or 0 to 30 dB"
            ),
            warnings
        )
    }

    @Test
    fun `properties that can't be read leave both switches at the default, with a warning`() {
        val switches = MicSwitches.forCapture(debuggable = true, read = { null }, warn = { warnings += it })

        assertEquals(MicSwitches.DEFAULT, switches)
        assertEquals(listOf("Ignoring debug.satori.mic.*: the properties can't be read"), warnings)
    }

    @Test
    fun `the start line names the switches as they are written`() {
        assertEquals("asked=auto gain=off", MicSwitches.DEFAULT.describe())
        assertEquals("asked=back gain=+12.0dB", MicSwitches(Device.BACK, Gain.Fixed(12.0)).describe())
        assertEquals("asked=bottom gain=auto", MicSwitches(Device.BOTTOM, Gain.Auto).describe())
    }
}
