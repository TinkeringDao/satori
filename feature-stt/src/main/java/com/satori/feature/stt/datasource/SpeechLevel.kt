package com.satori.feature.stt.datasource

import com.satori.domain.model.MicSource
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/** The level [levelDbfs] gives silence, and the lowest any level is counted at. */
internal const val SILENT_DBFS = -120.0

/**
 * The level of [samples] (-1..1) in dBFS: their RMS relative to full scale, the measure Android's
 * compatibility definition sets microphone sensitivity in (5.4.2: a 1 kHz tone at 90 dB SPL SHOULD
 * give an RMS of 2500 of 32768, -22.35 dBFS, on VOICE_RECOGNITION). [SILENT_DBFS] for silence.
 */
internal fun levelDbfs(samples: FloatArray): Double {
    if (samples.isEmpty()) return SILENT_DBFS
    var sum = 0.0
    for (s in samples) sum += (s * s).toDouble()
    val rms = sqrt(sum / samples.size)
    return if (rms > 1e-6) 20 * log10(rms) else SILENT_DBFS
}

/** How many dB the level display spans, from no dot lit beyond the centre to all of them. */
private const val METER_RANGE_DB = 40.0

/**
 * Where the grille's level display puts a chunk at [dbfs]: 0..1 on a 40 dB scale that puts normal
 * speech mid-range for the input it was recorded from. [MicSource.MIC] keeps the scale earlier
 * versions had (-50..-10 dBFS), with whatever gain the phone gives that input.
 * [MicSource.VOICE_RECOGNITION] has no automatic gain, and at the sensitivity the compatibility
 * definition asks for, speech at 60-70 dB SPL (a meeting across a table, a voice note at arm's
 * length) arrives at about -52 to -42 dBFS: on MIC's scale the display would hardly move, so its
 * scale starts 15 dB lower. On the razr its field log ([SpeechLevel]) showed the owner's speech at
 * -55 to -64 dBFS on it, about 20 dB under MIC (2026-10-13), which is why MIC is the default.
 * [MicSource.VOICE_COMMUNICATION] has the automatic gain of calls, so it shares MIC's scale.
 */
internal fun meterLevel(dbfs: Double, source: MicSource): Float {
    val floor = when (source) {
        MicSource.MIC, MicSource.VOICE_COMMUNICATION -> -50.0
        MicSource.VOICE_RECOGNITION -> -65.0
    }
    return ((dbfs - floor) / METER_RANGE_DB).toFloat().coerceIn(0f, 1f)
}

/**
 * How loud the speech of one run was, for the field log's summary line: the levels ([levelDbfs]) of
 * the detector's windows while it heard speech ([LevelMeteringDetector]), so captures from the two
 * microphone inputs can be compared, and a line the detector missed can be told from a quiet input.
 * Kept as counts of levels to the nearest 0.1 dB, so a run of any length takes the same few KB.
 */
internal class SpeechLevel {
    // One count for each 0.1 dB from SILENT_DBFS to 0 dBFS, both included.
    private val counts = IntArray(-SILENT_DBFS.toInt() * STEPS_PER_DB + 1)
    private var total = 0

    /** How many windows were counted. */
    val count: Int
        @Synchronized get() = total

    /** Counts a window of speech at [dbfs]; one below [SILENT_DBFS] counts there, one above 0 dBFS at 0. */
    @Synchronized
    fun record(dbfs: Double) {
        counts[floor((dbfs - SILENT_DBFS) * STEPS_PER_DB + 0.5).toInt().coerceIn(0, counts.size - 1)]++
        total++
    }

    /**
     * The level, to 0.1 dB, that [percent] (1..100) of the counted windows are at or below: the
     * median for 50. Null before any.
     */
    @Synchronized
    fun percentile(percent: Int): Double? {
        require(percent in 1..100) { "Not a percentage: $percent" }
        if (total == 0) return null
        // Which window it is in order, from 1: of 4, the 2nd for the median.
        val rank = (total.toLong() * percent + 99) / 100
        var seen = 0L
        val step = counts.indexOfFirst { seen += it; seen >= rank }
        return SILENT_DBFS + step.toDouble() / STEPS_PER_DB
    }

    private companion object {
        const val STEPS_PER_DB = 10
    }
}

/** [detector], also counting in [level] how loud each window is that it hears speech in. */
internal class LevelMeteringDetector(
    private val detector: VoiceDetector,
    private val level: SpeechLevel
) : VoiceDetector by detector {
    override fun acceptWaveform(samples: FloatArray) {
        detector.acceptWaveform(samples)
        if (detector.isSpeechDetected()) level.record(levelDbfs(samples))
    }
}
