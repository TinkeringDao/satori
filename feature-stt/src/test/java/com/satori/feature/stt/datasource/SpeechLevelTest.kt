package com.satori.feature.stt.datasource

import com.satori.domain.model.MicSource
import com.k2fsa.sherpa.onnx.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechLevelTest {

    private companion object {
        const val WINDOW = 512 // what the recognition hands the detector at a time
    }

    /** [WINDOW] samples of a 1 kHz tone at [amplitude] (-1..1). */
    private fun tone(amplitude: Double) =
        FloatArray(WINDOW) { (amplitude * sin(2 * PI * 1_000 * it / 16_000)).toFloat() }

    @Test
    fun `a level is the RMS against full scale, as the compatibility definition measures it`() {
        assertEquals(0.0, levelDbfs(FloatArray(WINDOW) { 1f }), 1e-9)
        assertEquals(-20.0, levelDbfs(FloatArray(WINDOW) { -0.1f }), 1e-6)
        // 90 dB SPL SHOULD give an RMS of 2500 of 32768 on VOICE_RECOGNITION.
        assertEquals(-22.35, levelDbfs(tone(amplitude = 2_500 * 1.4142135 / 32_768)), 0.01)
        assertEquals(SILENT_DBFS, levelDbfs(FloatArray(WINDOW)), 0.0)
        assertEquals(SILENT_DBFS, levelDbfs(FloatArray(0)), 0.0)
    }

    @Test
    fun `the general input keeps its scale of -50 to -10 dBFS`() {
        assertEquals(0f, meterLevel(-50.0, MicSource.MIC), 1e-6f)
        assertEquals(0.5f, meterLevel(-30.0, MicSource.MIC), 1e-6f)
        assertEquals(1f, meterLevel(-10.0, MicSource.MIC), 1e-6f)
        assertEquals(0f, meterLevel(SILENT_DBFS, MicSource.MIC), 1e-6f)
        assertEquals(1f, meterLevel(0.0, MicSource.MIC), 1e-6f)
    }

    @Test
    fun `the input for calls, which has the automatic gain of calls, shares the general input's scale`() {
        for (dbfs in listOf(SILENT_DBFS, -50.0, -30.0, -10.0, 0.0)) {
            assertEquals(meterLevel(dbfs, MicSource.MIC), meterLevel(dbfs, MicSource.VOICE_COMMUNICATION), 1e-6f)
        }
    }

    @Test
    fun `the speech recognition input, which has no automatic gain, starts 15 dB lower`() {
        assertEquals(0f, meterLevel(-65.0, MicSource.VOICE_RECOGNITION), 1e-6f)
        assertEquals(0.5f, meterLevel(-45.0, MicSource.VOICE_RECOGNITION), 1e-6f)
        assertEquals(1f, meterLevel(-25.0, MicSource.VOICE_RECOGNITION), 1e-6f)
        // Speech at 60 dB SPL, a meeting across a table, at the sensitivity the compatibility
        // definition asks for: a third of the display here, none of it on the general input's scale.
        val meeting = -22.35 - (90 - 60)
        assertEquals(0.32f, meterLevel(meeting, MicSource.VOICE_RECOGNITION), 0.01f)
        assertEquals(0f, meterLevel(meeting, MicSource.MIC), 1e-6f)
    }

    @Test
    fun `the median and the 90th percentile of the speech`() {
        val level = SpeechLevel()
        // 1 window at each of -50, -49, ..., -31 dBFS.
        (-50..-31).forEach { level.record(it.toDouble()) }

        assertEquals(20, level.count)
        // The 10th of 20 and the 18th.
        assertEquals(-41.0, level.percentile(50)!!, 1e-9)
        assertEquals(-33.0, level.percentile(90)!!, 1e-9)
        assertEquals(-50.0, level.percentile(1)!!, 1e-9)
        assertEquals(-31.0, level.percentile(100)!!, 1e-9)
        // To the nearest 0.1 dB.
        level.record(-12.34)
        assertEquals(-12.3, level.percentile(100)!!, 1e-9)
    }

    @Test
    fun `no speech, no level, and levels beyond the scale count at its ends`() {
        val level = SpeechLevel()
        assertNull(level.percentile(50))

        level.record(Double.NEGATIVE_INFINITY)
        level.record(6.0)

        assertEquals(SILENT_DBFS, level.percentile(50)!!, 1e-9)
        assertEquals(0.0, level.percentile(100)!!, 1e-9)
    }

    @Test
    fun `only the windows the detector hears speech in are counted`() {
        /** Hears speech in every window louder than -40 dBFS. */
        val loudOnly = object : VoiceDetector {
            private var speech = false
            override val minSilenceSamples = 8_000
            override val maxSpeechSamples = 320_000
            override fun acceptWaveform(samples: FloatArray) {
                speech = levelDbfs(samples) > -40.0
            }

            override fun isSpeechDetected() = speech
            override fun empty() = true
            override fun front(): SpeechSegment = error("no segment")
            override fun pop() = Unit
            override fun reset() = Unit
        }
        val level = SpeechLevel()
        val detector = LevelMeteringDetector(loudOnly, level)

        detector.acceptWaveform(tone(amplitude = 0.001)) // -63 dBFS: silence to it
        detector.acceptWaveform(tone(amplitude = 0.1)) // -23 dBFS
        detector.acceptWaveform(tone(amplitude = 0.1))

        assertEquals(2, level.count)
        assertEquals(-23.0, level.percentile(50)!!, 1e-9)
        // Everything else is the detector's own.
        assertEquals(320_000, detector.maxSpeechSamples)
        assertTrue(detector.isSpeechDetected())
    }
}
