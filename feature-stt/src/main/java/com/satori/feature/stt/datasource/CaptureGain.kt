package com.satori.feature.stt.datasource

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The digital gain of [setting] ([MicSwitches.gain], a debug switch) on one capture: applied to
 * each chunk the voice detector and the recognizer get, never to the audio the recording keeps.
 *
 * - [MicSwitches.Gain.Off] hands every chunk on as it is.
 * - [MicSwitches.Gain.Fixed] raises every chunk by its dB.
 * - [MicSwitches.Gain.Auto] aims the speech at [TARGET_DBFS], at most [MicSwitches.MAX_GAIN_DB]
 *   above what came in. A chunk counts as speech when it is [SPEECH_OVER_FLOOR_DB] above the
 *   noise floor: the second quietest chunk of the last [FLOOR_WINDOW_S] (the quietest while
 *   there is one), chunks of digital silence (below [DIGITAL_SILENCE_DBFS]) aside. The first
 *   speech sets the gain, and louder speech lowers it, at once. Quieter speech raises it by
 *   [RELEASE_DB_PER_S] a second of speech (the slow release), but only once the
 *   [FLOOR_WINDOW_S] after it held a chunk [SPEECH_OVER_FLOOR_DB] quieter than it: so it was
 *   speech, with a pause after it, and not the room getting louder. Between words the gain stays
 *   where it was, so the noise there isn't pumped up. Before any speech it is 0 dB.
 *
 * Why digital silence is left out: an input that delivers zeros (while it starts, or while
 * Android silences the capture for another app) reads about -120 dBFS, and a floor taken from it
 * would count every chunk of room noise as speech; the second quietest chunk keeps one chunk that
 * is all but silent (zeros with the room's first samples at its end) from doing the same. Why the
 * window: a floor that rose slowly after a quieter moment took a step up in the room's noise for
 * speech for seconds; the window forgets the quieter moment after [FLOOR_WINDOW_S], and the rises
 * wait as long, so such a step raises nothing. A step in the noise before anyone has spoken is
 * still taken for the first speech, which sets the gain at once; the first words louder than the
 * gain allows lower it again.
 *
 * No chunk clips: the gain on a chunk is held to what keeps its loudest sample at [PEAK_LIMIT],
 * and it never goes below 0 dB, so a chunk that comes in clipped goes on as it came.
 *
 * One instance is one capture; its chunks come from one thread. [describe] says what it did, for
 * the capture's summary line, and [log] hears of each move of the automatic gain by
 * [LOGGED_STEP_DB] or more.
 *
 * @param sampleRate Samples a second, for the times above
 */
internal class CaptureGain(
    private val setting: MicSwitches.Gain,
    private val log: (String) -> Unit = {},
    private val sampleRate: Int = SpeechRecognition.SAMPLE_RATE_HZ
) {
    // The samples seen so far: the clock of the floor's window and of the rises waiting.
    private var clock = 0L
    private val windowSamples = (FLOOR_WINDOW_S * sampleRate).toLong()

    // The chunks of the last FLOOR_WINDOW_S that carried sound, oldest first; the floor is the
    // second quietest of them (floorDbfs).
    private val recent = ArrayDeque<Heard>()

    // Speech that would raise the gain, oldest first, until the FLOOR_WINDOW_S after it show it
    // was speech.
    private val rises = ArrayDeque<Rise>()

    private var gainDb: Double? = null
    private var loggedDb = 0.0

    // The gain each speech chunk got, in steps of 0.1 dB from 0 to the highest.
    private val applied = IntArray((MicSwitches.MAX_GAIN_DB * STEPS_PER_DB).toInt() + 1)
    private var speechChunks = 0

    /** A chunk that carried sound: its level, and when it ended on [clock]. */
    private class Heard(val end: Long, val level: Double)

    /**
     * Speech at [level] that ended at [end] and lasted [seconds], which would raise the gain to
     * [desired]; [applied] is the gain it got, [quietestAfter] the quietest chunk since.
     */
    private class Rise(val end: Long, val level: Double, val desired: Double, val seconds: Double) {
        var applied = 0.0
        var quietestAfter = Double.POSITIVE_INFINITY
    }

    /** What the automatic gain made of a chunk. */
    private enum class Kind {
        /** Noise, silence, or a chunk before any floor: the gain stays. */
        NOISE,

        /** Speech that set or lowered the gain, counted at once. */
        SPEECH,

        /** Speech that would raise the gain, counted once what follows shows it was speech. */
        RISE
    }

    /** [chunk] (-1..1) with the gain on it: the same array when it gets none. */
    fun apply(chunk: FloatArray): FloatArray {
        if (setting == MicSwitches.Gain.Off || chunk.isEmpty()) return chunk
        val kind = if (setting == MicSwitches.Gain.Auto) automatic(levelDbfs(chunk), chunk.size) else Kind.NOISE
        val wanted = when (setting) {
            is MicSwitches.Gain.Fixed -> setting.db
            MicSwitches.Gain.Auto -> gainDb ?: 0.0
            MicSwitches.Gain.Off -> 0.0
        }
        val db = wanted.coerceAtMost(headroomDb(chunk)).coerceAtLeast(0.0)
        when (kind) {
            Kind.SPEECH -> record(db)
            Kind.RISE -> rises.last().applied = db
            Kind.NOISE -> Unit
        }
        if (db <= 0.0) return chunk
        val factor = 10.0.pow(db / 20).toFloat()
        return FloatArray(chunk.size) { (chunk[it] * factor).coerceIn(-1f, 1f) }
    }

    /**
     * What it did, as the summary line says it: off; the fixed gain ("+12.0dB"); for the automatic
     * one the median and the highest gain the speech got ("auto(+18.2/+30.0dB)"), or "auto(none)"
     * without speech.
     */
    fun describe(): String = when (setting) {
        MicSwitches.Gain.Off, is MicSwitches.Gain.Fixed -> MicSwitches.gainText(setting)
        MicSwitches.Gain.Auto -> {
            val median = percentile(50)
            val highest = percentile(100)
            if (median == null || highest == null) "auto(none)" else String.format(Locale.ROOT, "auto(%+.1f/%+.1fdB)", median, highest)
        }
    }

    /** The automatic gain's view of a chunk at [level] dBFS of [samples] samples; moves [gainDb]. */
    private fun automatic(level: Double, samples: Int): Kind {
        clock += samples
        if (level >= DIGITAL_SILENCE_DBFS) {
            recent.addLast(Heard(clock, level))
            while (clock - recent.first().end >= windowSamples) recent.removeFirst()
            for (rise in rises) rise.quietestAfter = min(rise.quietestAfter, level)
        }
        settleRises()
        val floor = floorDbfs() ?: return Kind.NOISE
        if (level <= MIN_SPEECH_DBFS || level < floor + SPEECH_OVER_FLOOR_DB) return Kind.NOISE
        val desired = (TARGET_DBFS - level).coerceIn(0.0, MicSwitches.MAX_GAIN_DB)
        val before = gainDb
        if (before == null || desired < before) {
            moveTo(desired, level)
            return Kind.SPEECH
        }
        rises.addLast(Rise(clock, level, desired, samples.toDouble() / sampleRate))
        return Kind.RISE
    }

    /**
     * The rises whose [FLOOR_WINDOW_S] have passed: one with a chunk [SPEECH_OVER_FLOOR_DB]
     * quieter after it was speech, counted now, and raises the gain by its share of the release;
     * one without was the room, and goes.
     */
    private fun settleRises() {
        while (rises.isNotEmpty() && clock - rises.first().end >= windowSamples) {
            val rise = rises.removeFirst()
            if (rise.level < rise.quietestAfter + SPEECH_OVER_FLOOR_DB) continue
            record(rise.applied)
            val before = gainDb ?: 0.0
            moveTo(max(before, min(rise.desired, before + RELEASE_DB_PER_S * rise.seconds)), rise.level)
        }
    }

    /** The noise floor: the second quietest chunk of [recent], the quietest while it holds one; null before any. */
    private fun floorDbfs(): Double? {
        var quietest = Double.POSITIVE_INFINITY
        var second = Double.POSITIVE_INFINITY
        for (heard in recent) {
            if (heard.level < quietest) {
                second = quietest
                quietest = heard.level
            } else if (heard.level < second) {
                second = heard.level
            }
        }
        return when (recent.size) {
            0 -> null
            1 -> quietest
            else -> second
        }
    }

    /** Sets the automatic gain to [db], set by speech at [level] dBFS, and logs a move of [LOGGED_STEP_DB] or more. */
    private fun moveTo(db: Double, level: Double) {
        gainDb = db
        if (abs(db - loggedDb) >= LOGGED_STEP_DB) {
            loggedDb = db
            log(String.format(Locale.ROOT, "Capture gain now %+.1f dB (speech at %.1f dBFS)", db, level))
        }
    }

    /** How many dB [chunk] can be raised before its loudest sample passes [PEAK_LIMIT]. */
    private fun headroomDb(chunk: FloatArray): Double {
        var peak = 0f
        for (sample in chunk) peak = maxOf(peak, abs(sample))
        return if (peak <= 0f) Double.MAX_VALUE else 20 * log10((PEAK_LIMIT / peak).toDouble())
    }

    private fun record(db: Double) {
        applied[floor(db * STEPS_PER_DB + 0.5).toInt().coerceIn(0, applied.lastIndex)]++
        speechChunks++
    }

    /** The gain, to 0.1 dB, that [percent] of the speech chunks got at most; null before any. */
    private fun percentile(percent: Int): Double? {
        if (speechChunks == 0) return null
        val rank = (speechChunks.toLong() * percent + 99) / 100
        var seen = 0L
        val step = applied.indexOfFirst { seen += it; seen >= rank }
        return step.toDouble() / STEPS_PER_DB
    }

    companion object {
        /** Where the automatic gain puts speech: 4 dB under the 90 dB SPL tone of Android's CDD. */
        const val TARGET_DBFS = -26.0

        /** How far above the noise floor a chunk counts as speech. */
        const val SPEECH_OVER_FLOOR_DB = 9.0

        /**
         * How far back the noise floor looks, and how long a rise waits for a quieter chunk after
         * it: long enough to hold a pause between the phrases of normal speech.
         */
        const val FLOOR_WINDOW_S = 2.0

        /**
         * Below this a chunk carries no sound at all: 16-bit audio's own rounding noise is about
         * -101 dBFS, so a live microphone never reads less. Such a chunk never moves the floor.
         */
        const val DIGITAL_SILENCE_DBFS = -100.0

        /** How fast quieter speech raises the automatic gain, per second of speech. */
        const val RELEASE_DB_PER_S = 3.0

        /** Below this a chunk is silence, never speech. */
        const val MIN_SPEECH_DBFS = -80.0

        /** The loudest a sample may get. */
        const val PEAK_LIMIT = 0.98f

        /** How far the automatic gain moves before it is logged again. */
        const val LOGGED_STEP_DB = 6.0

        private const val STEPS_PER_DB = 10
    }
}
