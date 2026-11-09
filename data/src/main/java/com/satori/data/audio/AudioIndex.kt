package com.satori.data.audio

import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan

/**
 * The index kept beside a recording's audio file, the WAV or the AAC copy that replaces it: when
 * each capture of the recording began, and where each transcript line was said. Compressing the
 * audio leaves it as it is. Plain text with one entry a line, so a capture adds an entry without
 * rewriting the file, and an entry cut short by a crash costs only itself:
 *
 *     run <wall-clock ms of the capture's first sample> <its place in the audio, ms>
 *     line <segment id> <start ms> <end ms>
 *     keep <1 or 0>
 *
 * Unknown and broken lines are skipped; of two entries for one line the later one counts, and so
 * of two keep entries: "Keep this audio" adds one each time it is switched, without rewriting the
 * index.
 */
object AudioIndex {

    private val SPACES = Regex(" +")

    /**
     * The entries of an index.
     *
     * @property kept Whether the owner keeps the audio as it is (the last keep entry; false without one)
     */
    class Entries(val runs: List<AudioRun>, val lines: Map<String, AudioSpan>, val kept: Boolean = false) {

        /**
         * When the last capture ended, wall clock, in audio that plays [durationMs]: the last
         * run's start plus the audio after it. Null without runs.
         */
        fun lastCaptureEndMs(durationMs: Long): Long? =
            runs.lastOrNull()?.let { it.startedAt + (durationMs - it.startMs).coerceAtLeast(0L) }
    }

    fun run(run: AudioRun): String = "run ${run.startedAt} ${run.startMs}\n"

    /** The entry for line [id], or null for an id with a space in it, which can't be read back. */
    fun line(id: String, span: AudioSpan): String? =
        if (id.isEmpty() || id.any { it.isWhitespace() }) null else "line $id ${span.startMs} ${span.endMs}\n"

    /** The entry that keeps the audio as it is, or with [kept] false no longer. */
    fun keep(kept: Boolean): String = "keep ${if (kept) 1 else 0}\n"

    /**
     * A whole index, as a new transcript's lines are written in place of the old ones. A keep
     * entry is written only for [kept] audio, since an index without one reads as not kept.
     */
    fun format(runs: List<AudioRun>, lines: Map<String, AudioSpan>, kept: Boolean): String = buildString {
        runs.forEach { append(run(it)) }
        lines.forEach { (id, span) -> line(id, span)?.let(::append) }
        if (kept) append(keep(true))
    }

    fun parse(text: String): Entries {
        val runs = mutableListOf<AudioRun>()
        val lines = LinkedHashMap<String, AudioSpan>()
        var kept = false
        for (entry in text.lineSequence()) {
            val fields = entry.trim().split(SPACES)
            when (fields.firstOrNull()) {
                "run" -> if (fields.size == 3) {
                    val startedAt = fields[1].toLongOrNull()
                    val startMs = fields[2].toLongOrNull()
                    if (startedAt != null && startMs != null && startMs >= 0) runs += AudioRun(startedAt, startMs)
                }
                "line" -> if (fields.size == 4) {
                    val startMs = fields[2].toLongOrNull()
                    val endMs = fields[3].toLongOrNull()
                    if (startMs != null && endMs != null && startMs in 0..endMs) lines[fields[1]] = AudioSpan(startMs, endMs)
                }
                "keep" -> if (fields.size == 2) {
                    when (fields[1]) {
                        "1" -> kept = true
                        "0" -> kept = false
                    }
                }
            }
        }
        return Entries(runs.sortedBy { it.startMs }, lines, kept)
    }
}
