package com.satori.feature.stt.datasource

import com.satori.feature.stt.datasource.MicSwitches.Gain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gain on what the recognizer hears: level in, level out, no clipping, the automatic gain's
 * target, cap and slow release, and that it doesn't raise the noise between words.
 */
class CaptureGainTest {

    /** 100 ms (one microphone read) of a 440 Hz tone whose RMS is [dbfs]. */
    private fun tone(dbfs: Double, samples: Int = 1_600): FloatArray {
        val amplitude = 10.0.pow(dbfs / 20) * sqrt(2.0)
        return FloatArray(samples) { (amplitude * sin(2 * PI * 440 * it / SpeechRecognition.SAMPLE_RATE_HZ)).toFloat() }
    }

    /** The automatic gain after [chunks] of room noise at [noiseDbfs], so it knows its floor. */
    private fun auto(noiseDbfs: Double = -70.0, chunks: Int = 5, log: (String) -> Unit = {}): CaptureGain =
        CaptureGain(Gain.Auto, log).apply { repeat(chunks) { apply(tone(noiseDbfs)) } }

    /** 100 ms of digital silence: zeros, as an input gives while it starts or while Android silences it for another app. */
    private fun silence(): FloatArray = FloatArray(1_600)

    /** The levels out of [gain] for [chunks] reads of a tone at [dbfs]. */
    private fun levelsOut(gain: CaptureGain, dbfs: Double, chunks: Int): List<Double> =
        (1..chunks).map { levelDbfs(gain.apply(tone(dbfs))) }

    @Test
    fun `off hands every chunk on as it came`() {
        val gain = CaptureGain(Gain.Off)
        val chunk = tone(-50.0)

        assertSame(chunk, gain.apply(chunk))
        assertEquals("off", gain.describe())
    }

    @Test
    fun `a fixed gain raises the level by its dB`() {
        val gain = CaptureGain(Gain.Fixed(12.0))

        assertEquals(-38.0, levelDbfs(gain.apply(tone(-50.0))), 0.05)
        assertEquals(-48.0, levelDbfs(gain.apply(tone(-60.0))), 0.05)
        assertEquals("+12.0dB", gain.describe())
    }

    @Test
    fun `no chunk clips, a gain is held to what keeps the loudest sample at 0_98`() {
        // A peak of 0.45: 30 dB would take it far past full scale.
        val out = CaptureGain(Gain.Fixed(30.0)).apply(tone(-10.0))

        assertEquals(CaptureGain.PEAK_LIMIT, out.maxOf { abs(it) }, 0.001f)
        assertTrue(out.all { abs(it) <= CaptureGain.PEAK_LIMIT + 1e-6f })
    }

    @Test
    fun `a chunk that came in clipped goes on as it came, never quieter`() {
        val clipped = FloatArray(1_600) { if (it % 2 == 0) 1f else -1f }

        assertSame(clipped, CaptureGain(Gain.Fixed(6.0)).apply(clipped))
        assertSame(clipped, auto().apply(clipped))
    }

    @Test
    fun `before any speech the automatic gain is 0 dB`() {
        val gain = CaptureGain(Gain.Auto)
        val noise = tone(-70.0)

        assertSame(noise, gain.apply(noise))
        assertEquals("auto(none)", gain.describe())
    }

    @Test
    fun `the automatic gain brings speech to about -26 dBFS`() {
        assertEquals(CaptureGain.TARGET_DBFS, levelDbfs(auto().apply(tone(-50.0))), 0.05)
        // As the owner's closed phone gave on 2026-10-13.
        assertEquals(CaptureGain.TARGET_DBFS, levelDbfs(auto(noiseDbfs = -75.0).apply(tone(-56.0))), 0.05)
    }

    @Test
    fun `the automatic gain gives at most 30 dB, and nothing to speech as loud as the target`() {
        assertEquals(-65.0 + MicSwitches.MAX_GAIN_DB, levelDbfs(auto(noiseDbfs = -85.0).apply(tone(-65.0))), 0.05)
        val loud = tone(-20.0)
        assertSame(loud, auto().apply(loud))
    }

    @Test
    fun `quieter speech raises the gain slowly, 3 dB a second of speech once the pause after it shows it was speech, and louder speech lowers it at once`() {
        val gain = auto(noiseDbfs = -80.0)
        // Loud speech first: 4 dB bring it to the target.
        assertEquals(CaptureGain.TARGET_DBFS, levelDbfs(gain.apply(tone(-30.0))), 0.05)

        // A second of quieter speech keeps the 4 dB while it lasts, where 24 would bring it to the target.
        val second = levelsOut(gain, -50.0, chunks = 10)
        assertTrue("$second", second.all { abs(it - (-50.0 + 4.0)) < 0.05 })

        // The 2 s after it hold the room 30 dB quieter: it was speech, so the gain goes up 3 dB, from 4 to 7.
        levelsOut(gain, -80.0, chunks = 20)
        assertEquals(-50.0 + 4.0 + CaptureGain.RELEASE_DB_PER_S, levelDbfs(gain.apply(tone(-50.0))), 0.05)

        // Loud again: down to 4 dB at once, so it doesn't overshoot.
        assertEquals(CaptureGain.TARGET_DBFS, levelDbfs(gain.apply(tone(-30.0))), 0.05)
    }

    @Test
    fun `speech with no pause in the 2 s after it raises nothing, the gain stays`() {
        val gain = auto(noiseDbfs = -80.0)
        gain.apply(tone(-30.0))

        // Ten seconds of speech at one level, never 9 dB quieter: no rise can be shown speech.
        val talk = levelsOut(gain, -50.0, chunks = 100)

        assertTrue("$talk", talk.all { abs(it - (-50.0 + 4.0)) < 0.05 })
        assertEquals("auto(+4.0/+4.0dB)", gain.describe())
    }

    @Test
    fun `between words the gain stays where the speech set it, so the noise there isn't pumped up`() {
        val gain = auto(noiseDbfs = -70.0)
        gain.apply(tone(-50.0))

        // Five seconds of pause: the noise keeps the speech's 24 dB, never the 30 it would take.
        val pause = (1..50).map { levelDbfs(gain.apply(tone(-70.0))) }

        assertTrue("$pause", pause.all { abs(it - (-70.0 + 24.0)) < 0.05 })
        assertEquals("auto(+24.0/+24.0dB)", gain.describe())
    }

    @Test
    fun `silent reads at the start set no floor, so the noise before the first word gets nothing and the pause after it the speech's gain`() {
        val gain = CaptureGain(Gain.Auto)
        // 300 ms of zeros while the input starts.
        repeat(3) { gain.apply(silence()) }

        val before = levelsOut(gain, -65.0, chunks = 10)
        gain.apply(tone(-35.0))
        val pause = levelsOut(gain, -65.0, chunks = 50)

        assertTrue("$before", before.all { abs(it - (-65.0)) < 0.05 })
        assertTrue("$pause", pause.all { abs(it - (-65.0 + 9.0)) < 0.05 })
        assertEquals("auto(+9.0/+9.0dB)", gain.describe())
    }

    @Test
    fun `one first read that is all but silent sets no floor either, so the room after it is not taken for speech`() {
        val gain = CaptureGain(Gain.Auto)
        // Zeros with the room's first samples at the end: -95 dBFS, more than digital silence.
        gain.apply(tone(-95.0))

        val before = levelsOut(gain, -70.0, chunks = 10)
        gain.apply(tone(-50.0))
        val pause = levelsOut(gain, -70.0, chunks = 30)

        assertTrue("$before", before.all { abs(it - (-70.0)) < 0.05 })
        assertTrue("$pause", pause.all { abs(it - (-70.0 + 24.0)) < 0.05 })
        assertEquals("auto(+24.0/+24.0dB)", gain.describe())
    }

    @Test
    fun `half a second of silence mid-capture doesn't drop the floor, so the pause after it keeps the speech's gain`() {
        val gain = auto(noiseDbfs = -65.0)
        gain.apply(tone(-35.0))
        repeat(5) { gain.apply(silence()) }

        val pause = levelsOut(gain, -65.0, chunks = 50)

        assertTrue("$pause", pause.all { abs(it - (-65.0 + 9.0)) < 0.05 })
        assertEquals("auto(+9.0/+9.0dB)", gain.describe())
    }

    @Test
    fun `a step up in the room's noise is not taken for speech, so the gain stays where the speech set it`() {
        val gain = auto(noiseDbfs = -75.0)
        gain.apply(tone(-45.0))
        levelsOut(gain, -75.0, chunks = 10)

        // The noise comes up 15 dB and stays there for 10 s, with nobody speaking.
        val step = levelsOut(gain, -60.0, chunks = 100)

        assertTrue("$step", step.all { abs(it - (-60.0 + 19.0)) < 0.05 })
        assertEquals("auto(+19.0/+19.0dB)", gain.describe())
    }

    @Test
    fun `the floor follows the room up within 2 s, so a sound 6 dB over the louder room is no speech`() {
        val gain = auto(noiseDbfs = -86.0)
        gain.apply(tone(-56.0))
        // The room comes up to -60 dBFS and stays; 3 s later something 6 dB louder.
        levelsOut(gain, -60.0, chunks = 30)

        // Over the floor of 3 s ago it would count as speech and lower the gain to 28 dB.
        assertEquals(-54.0 + 30.0, levelDbfs(gain.apply(tone(-54.0))), 0.05)
        assertEquals("auto(+30.0/+30.0dB)", gain.describe())
    }

    @Test
    fun `the summary names the median and the highest gain the speech got`() {
        val gain = auto(noiseDbfs = -85.0)
        listOf(-50.0, -50.0, -45.0, -50.0, -30.0).forEach { gain.apply(tone(it)) }
        levelsOut(gain, -85.0, chunks = 20)

        // 24 at once, then 19 and 4 as louder speech lowers it; the second and the fourth chunk
        // keep the gain there was, and count once the pause after them shows they were speech.
        assertEquals("auto(+19.0/+24.0dB)", gain.describe())
    }

    @Test
    fun `each move of the automatic gain by 6 dB or more goes to the field log`() {
        val lines = mutableListOf<String>()
        val gain = auto(log = { lines += it })
        gain.apply(tone(-50.0))
        gain.apply(tone(-52.0))
        gain.apply(tone(-30.0))

        assertEquals(
            listOf(
                "Capture gain now +24.0 dB (speech at -50.0 dBFS)",
                "Capture gain now +4.0 dB (speech at -30.0 dBFS)"
            ),
            lines
        )
    }
}
