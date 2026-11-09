package com.satori.domain.model

/**
 * The audio kept of a recording ([AppSettings.keepAudio]): what the speech recognizer heard, as
 * 16 kHz mono 16-bit PCM in a WAV file, with where in it each capture began and where each
 * transcript line was said. It makes a recording playable, and lets it be transcribed again
 * with another speech model, so models can be compared on the owner's own voice.
 *
 * Positions are milliseconds from the start of the audio. A live transcript line is stamped when
 * the recognizer gives it out, at its end and a little after, so where the line starts can't be
 * read from its stamp: [lines] holds that, as the recognizer measured it.
 *
 * @property sessionId The recording
 * @property path The WAV file
 * @property durationMs How long the audio plays
 * @property runs Each capture of the recording, in order: one that stopped on an error and was
 *   started again has two, played one after the other
 * @property lines Where each transcript line was said, by segment id
 * @property encoding How the audio is stored
 * @property kept The owner chose to keep this audio as it is ("Keep this audio"): it is neither
 *   compressed nor deleted with older audio
 * @property sizeBytes How much storage the audio takes, its index included
 */
data class RecordingAudio(
    val sessionId: String,
    val path: String,
    val durationMs: Long,
    val runs: List<AudioRun> = emptyList(),
    val lines: Map<String, AudioSpan> = emptyMap(),
    val encoding: AudioEncoding = AudioEncoding.WAV,
    val kept: Boolean = false,
    val sizeBytes: Long = 0
) {

    /**
     * Where to play [segment] from: where it was said. A line the index doesn't know (its entry
     * was lost with a crash) is found by its stamp instead, a few seconds early, since the stamp
     * marks its end.
     *
     * @param recordingStart When the recording was created, the clock of a file whose index was
     *   lost entirely
     */
    fun startOf(segment: TranscriptionSegment, recordingStart: Long): Long {
        val start = lines[segment.id]?.startMs
            ?: (positionAt(segment.timestamp, recordingStart) - UNKNOWN_LINE_LEAD_MS)
        return start.coerceIn(0L, durationMs)
    }

    /**
     * The place in the audio of the moment [time] (wall clock), within the capture that ran then.
     * A time between two captures belongs to the earlier one and is held at its end.
     */
    fun positionAt(time: Long, recordingStart: Long): Long {
        if (runs.isEmpty()) return (time - recordingStart).coerceIn(0L, durationMs)
        val index = runs.indexOfLast { it.startedAt <= time }.coerceAtLeast(0)
        val run = runs[index]
        val runEnd = runs.getOrNull(index + 1)?.startMs ?: durationMs
        return (run.startMs + (time - run.startedAt)).coerceIn(run.startMs, maxOf(run.startMs, runEnd))
    }

    /**
     * The wall-clock time at [positionMs], from the capture it belongs to; from [recordingStart]
     * for a file whose index was lost.
     */
    fun wallClockAt(positionMs: Long, recordingStart: Long): Long {
        val run = runs.lastOrNull { it.startMs <= positionMs } ?: runs.firstOrNull()
            ?: return recordingStart + positionMs
        return run.startedAt + (positionMs - run.startMs)
    }

    /**
     * The id of the line that plays at [positionMs]: the last of [segments] (in spoken order)
     * that starts at or before it. Null before the first line.
     */
    fun lineAt(positionMs: Long, segments: List<TranscriptionSegment>, recordingStart: Long): String? =
        segments.lastOrNull { startOf(it, recordingStart) <= positionMs }?.id

    companion object {
        /**
         * How far before the place its stamp points to a line without an index entry is played.
         * The stamp is the end of the line plus the silence that ended it; most lines are
         * shorter than this.
         */
        const val UNKNOWN_LINE_LEAD_MS = 5_000L
    }
}

/**
 * One capture of a recording: when its first sample was taken (wall clock) and where that
 * sample is in the recording's audio.
 */
data class AudioRun(val startedAt: Long, val startMs: Long)

/** Where something was said in a recording's audio, in milliseconds from its start. */
data class AudioSpan(val startMs: Long, val endMs: Long)

/** A line the speech model recognized in a recording's audio, and where it was said. */
data class SpokenLine(val text: String, val span: AudioSpan)
