package com.satori.domain.settings

import com.satori.domain.model.RecordingMode
import kotlin.math.abs

/**
 * The few intervals offered for how often the AI writes notes while recording, instead of a
 * slider over every second.
 */
object NoteIntervals {

    /** Seconds between notes of a voice note or a meeting. At least 30: less gives thin notes. */
    val NOTES_SECONDS = listOf(30, 60, 120)

    /** Seconds between translations, which can come more often than notes. */
    val TRANSLATION_SECONDS = listOf(15, 30, 60)

    /** Minutes between notes of a long meeting. */
    val LONG_MEETING_MINUTES = listOf(3, 5, 10)

    /**
     * The choices for [mode]: minutes for a long meeting, seconds for the others. A value
     * stored by an older version, a slider position such as 90 seconds, is not among them; see
     * [nearest].
     */
    fun optionsFor(mode: RecordingMode): List<Int> = when (mode) {
        RecordingMode.SIMPLE_LISTENING, RecordingMode.SHORT_MEETING -> NOTES_SECONDS
        RecordingMode.LONG_MEETING -> LONG_MEETING_MINUTES
        RecordingMode.REAL_TIME_TRANSLATION -> TRANSLATION_SECONDS
    }

    /** The option closest to [value]; the shorter one when two are equally close. */
    fun nearest(options: List<Int>, value: Int): Int =
        options.minWithOrNull(compareBy({ abs(it - value) }, { it })) ?: value
}
