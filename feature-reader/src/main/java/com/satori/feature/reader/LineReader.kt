package com.satori.feature.reader

import com.satori.domain.reading.EnglishDetokenizer
import com.satori.domain.reading.IndicPreprocessor
import com.satori.domain.reading.PieceIds
import com.satori.reader.Reading

/**
 * One part's reading: the source pieces it read, the target tokens it wrote (without the end),
 * the decoder runs, the mean log-probability per token (the end counted), whether it reached the
 * cap or ended without an end, and the ms the native reading took.
 */
data class PartReading(
    val pieces: Int,
    val tokens: Int,
    val steps: Int,
    val meanLogProb: Double,
    val capped: Boolean,
    val ms: Long
)

/**
 * One line's reading: its English, and the parts it was read in (none for a line with no piece).
 */
data class LineReading(val text: String, val parts: List<PartReading>) {
    val pieces: Int get() = parts.sumOf { it.pieces }
    val tokens: Int get() = parts.sumOf { it.tokens }
    val steps: Int get() = parts.sumOf { it.steps }
    val capped: Boolean get() = parts.any { it.capped }
    val ms: Long get() = parts.sumOf { it.ms }

    /**
     * The mean log-probability per token over all parts, each part's end counted as the search
     * counts it; null for a line read in no part. Negative infinity when a part's is.
     */
    val meanLogProb: Double?
        get() {
            if (parts.isEmpty()) return null
            val weights = parts.sumOf { it.tokens + 1 }
            return parts.sumOf { it.meanLogProb * (it.tokens + 1) } / weights
        }

    companion object {
        val EMPTY = LineReading("", emptyList())
    }
}

/**
 * Reads one line into English as IndicTrans2's Python reference does, part by part (R1's surface
 * on R2's): IndicProcessor's preprocessing ([IndicPreprocessor]), SentencePiece's pieces of the
 * text after the tags ([pieces]), at most [maxPieces] pieces a part, cut where a word starts and
 * never inside a placeholder ([PieceIds.splitAtWords], D15), each part as the model's source ids
 * ([PieceIds.sourceIds]) through [translate], its target ids back to pieces and English
 * ([EnglishDetokenizer], the line's placeholders restored), the parts joined with a space.
 *
 * A line longer than [maxChars] is cut at a space first, and each piece of it read so: the
 * preprocessing's address and number scans take time that grows with the square of a line's
 * length (about 0.33 s for 20,000 adversarial characters on a Mac), and a capture that opens must
 * find the reader between two native calls soon. A line with no piece reads as empty, without the
 * model. Blocking but for [translate]; one line at a time.
 *
 * @param clock Nanoseconds that never jump, for each part's time
 */
class LineReader(
    private val pieces: (String) -> List<String>,
    private val ids: PieceIds,
    private val translate: suspend (IntArray) -> Reading,
    private val maxPieces: Int = PieceIds.MAX_SOURCE_PIECES,
    private val maxChars: Int = MAX_CHARS,
    private val clock: () -> Long = System::nanoTime
) {
    init {
        require(maxPieces > 0 && maxChars > 0) { "the limits must be positive" }
    }

    suspend fun read(line: String): LineReading {
        val texts = ArrayList<String>()
        val parts = ArrayList<PartReading>()
        for (chunk in chunks(line, maxChars)) {
            val pre = IndicPreprocessor.preprocess(chunk)
            val all = pieces(pre.text)
            if (all.isEmpty()) continue
            for (part in PieceIds.splitAtWords(all, maxPieces)) {
                val started = clock()
                val reading = translate(ids.sourceIds(part))
                val ms = (clock() - started) / NS_PER_MS
                texts += EnglishDetokenizer.detokenize(ids.targetPieces(reading.ids), pre.placeholders)
                parts += PartReading(part.size, reading.ids.size, reading.steps, reading.meanLogProb, reading.capped, ms)
            }
        }
        if (parts.isEmpty()) return LineReading.EMPTY
        return LineReading(texts.filter { it.isNotEmpty() }.joinToString(" "), parts)
    }

    companion object {
        /** R1's bound on a line read in one piece: about 15 ms of preprocessing at worst on a Mac. */
        const val MAX_CHARS = 4_000

        private const val NS_PER_MS = 1_000_000L

        /**
         * [line] in pieces of at most [max] chars, each cut at the last space within it, else at
         * [max] (never between the two halves of a surrogate pair); the spaces at a cut are dropped.
         */
        internal fun chunks(line: String, max: Int): List<String> {
            if (line.length <= max) return listOf(line)
            val out = ArrayList<String>()
            var start = 0
            while (start < line.length) {
                if (line.length - start <= max) {
                    out += line.substring(start)
                    break
                }
                var end = line.lastIndexOf(' ', start + max)
                if (end <= start) {
                    end = start + max
                    // Both halves of a pair go into one piece: this one, unless it would be empty.
                    if (Character.isHighSurrogate(line[end - 1]) && Character.isLowSurrogate(line[end])) {
                        end = if (end - 1 > start) end - 1 else end + 1
                    }
                }
                out += line.substring(start, end)
                start = end
                while (start < line.length && line[start] == ' ') start++
            }
            return out.filter { it.isNotBlank() }
        }
    }
}
