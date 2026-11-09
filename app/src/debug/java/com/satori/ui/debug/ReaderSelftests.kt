package com.satori.ui.debug

import com.satori.domain.reading.EnglishDetokenizer
import com.satori.domain.reading.IndicPreprocessor
import com.satori.domain.reading.PieceIds
import com.satori.feature.reader.LoadedReader
import com.satori.feature.reader.LoadedTokenizer
import com.satori.reader.ReaderEngine
import java.util.Locale

/**
 * Debug builds only: the tokenizer selftest (V0b), what the C++ SentencePiece of the app makes of
 * public lines with the downloaded `model.SRC`, for `~/satori-audit/tools/reading/v0b_compare.py`
 * on the Mac. Its input is a TSV in golden.py's format with a `name` column and an `input` column
 * (the line as it was said, which the real IndicPreprocessor preprocesses here), a `text` column
 * (the preprocessed text, which SentencePiece reads as it is: golden.py's `v0b_inputs.tsv`), or
 * both, when the preprocessing is also held to the text. Each row's output:
 *
 * `name, text, matches, pieces, ids, unknown, parts`: the text SentencePiece read; 1 or 0 when
 * the row's `text` was there to compare the preprocessing with, else empty; the pieces; the model's
 * source ids of the whole line as the export's tokenizer gives them (the two tags, each piece's
 * id, `<unk>`'s for a piece dict.SRC.json lacks, `</s>`), not cut at 253; how many of them are
 * `<unk>`; and how many parts of at most 253 pieces the reading would read the line in.
 *
 * @param clock Milliseconds that never jump
 */
internal class TokenizerSelftest(private val clock: () -> Long) {

    /** What one run did, for its summary line. */
    data class Summary(
        val lines: Int,
        val pieces: Int,
        val unknown: Int,
        val linesWithUnknown: Int,
        val longLines: Int,
        val compared: Int,
        val matched: Int,
        val ms: Long
    )

    /** The header of the output, after its comment lines. */
    val header = listOf("name", "text", "matches", "pieces", "ids", "unknown", "parts")

    /**
     * Writes [rows] (with [columns]) to [out], tokenized by [tokenizer].
     *
     * @throws IllegalArgumentException for an input without `name` and `input` or `text`
     */
    fun run(columns: List<String>, rows: List<ReaderSelftestFiles.Row>, tokenizer: LoadedTokenizer, out: Appendable): Summary {
        require("name" in columns && ("input" in columns || "text" in columns)) {
            "the input needs a name column and an input or text column"
        }
        val started = clock()
        val ids = tokenizer.pieceIds
        val unknownId = ids.sourceIds(listOf(UNKNOWN))[2]
        out.append(header.joinToString("\t")).append('\n')
        var pieceCount = 0
        var unknown = 0
        var linesWithUnknown = 0
        var longLines = 0
        var compared = 0
        var matched = 0
        for (row in rows) {
            val given = row["text"]
            val text = row["input"]?.let { IndicPreprocessor.preprocess(it).text } ?: given.orEmpty()
            val matches = if (row["input"] != null && given != null) (text == given) else null
            if (matches != null) {
                compared++
                if (matches) matched++
            }
            val pieces = tokenizer.pieces(text)
            val sourceIds = wholeSource(ids, pieces)
            val unknownHere = (2 until sourceIds.size - 1).count { sourceIds[it] == unknownId }
            pieceCount += pieces.size
            unknown += unknownHere
            if (unknownHere > 0) linesWithUnknown++
            if (pieces.size > PieceIds.MAX_SOURCE_PIECES) longLines++
            val parts = if (pieces.isEmpty()) 0 else PieceIds.splitAtWords(pieces).size
            out.append(ReaderSelftestFiles.json(row["name"].orEmpty())).append('\t')
                .append(ReaderSelftestFiles.json(text)).append('\t')
                .append(matches?.let { if (it) "1" else "0" } ?: "").append('\t')
                .append(ReaderSelftestFiles.jsonStrings(pieces)).append('\t')
                .append(ReaderSelftestFiles.jsonInts(sourceIds)).append('\t')
                .append(unknownHere.toString()).append('\t')
                .append(parts.toString()).append('\n')
        }
        return Summary(rows.size, pieceCount, unknown, linesWithUnknown, longLines, compared, matched, clock() - started)
    }

    companion object {
        private const val UNKNOWN = "<unk>"

        /**
         * [pieces] as the export's tokenizer makes them the model's source, whatever their number:
         * the two tags, each piece's id, `</s>`. PieceIds.sourceIds frames at most 253 pieces, as
         * one reading takes, so a longer line is framed in 253s and their ids joined.
         */
        internal fun wholeSource(ids: PieceIds, pieces: List<String>): IntArray {
            val frame = ids.sourceIds(emptyList())
            val inner = pieces.chunked(PieceIds.MAX_SOURCE_PIECES).flatMap { part ->
                val framed = ids.sourceIds(part)
                framed.copyOfRange(2, framed.size - 1).asList()
            }
            return intArrayOf(frame[0], frame[1]) + inner.toIntArray() + intArrayOf(frame[2])
        }
    }
}

/**
 * Debug builds only: the reader selftest (V1 and V2), the English the reader makes of pushed
 * Malayalam lines, one a line, with how long each took and what it cost, for
 * `~/satori-audit/tools/reading/score.py` (chrF++ against FLORES' English) and memtest.sh's reader
 * runs. Each line's output:
 *
 * `line, ms, first_ms, pieces, parts, tokens, steps, mean_log_prob, capped, english`: its number
 * (from 1); the ms its reading took, all parts; with [firstStep], the ms of a reading of its first
 * part cut at one token, which is the encoder and decoder_model's step, else empty; the source
 * pieces; the parts; the target tokens without their ends; the decoder runs; the mean
 * log-probability per token, each part's end counted (empty for a line read in no part); 1 if a
 * part reached its cap; the English (JSON).
 *
 * The worst case ([worstCase]) reads one source instead: the first 253 pieces of the lines (each
 * preprocessed and tokenized alone, their pieces put end to end), with the end held back to the
 * cap's last token (D15: 255), so the search runs all 255 steps at its beam.
 *
 * @param clock Milliseconds that never jump
 */
internal class ReaderSelftest(private val clock: () -> Long) {

    /** What a run over lines did, for its summary line; [firstSteps] with first steps. */
    data class Summary(
        val lines: Int,
        val inParts: Int,
        val pieces: Int,
        val tokens: Int,
        val steps: Int,
        val capped: Int,
        val empty: Int,
        val readMs: Long,
        val firstSteps: FirstSteps? = null
    ) {
        /**
         * The ms a decoder run took on average, all of a reading's ms over its runs: the
         * preprocessing, SentencePiece, the encoder and decoder_model's first step included, so
         * more than D2's per-token figure ([FirstSteps.msPerLaterStep]); null without runs.
         */
        val msPerStep: Double? get() = if (steps == 0) null else readMs.toDouble() / steps
    }

    /**
     * The lines whose first step was timed ([lines]): the first steps' ms in all ([firstMs]),
     * the readings' own ms ([readMs]) and their decoder runs ([steps]).
     */
    data class FirstSteps(val lines: Int, val firstMs: Long, val readMs: Long, val steps: Int) {
        /** A first step's ms on average: the encoder and decoder_model's step. */
        val msPerLine: Double get() = if (lines == 0) 0.0 else firstMs.toDouble() / lines

        /**
         * The ms of a run after the first, decoder_with_past's step, D2's per-token figure (beam 4
         * at most 25 ms): the readings' ms less their first steps, over their runs less one a
         * line (score.py's "the runs after them"); null without such runs.
         */
        val msPerLaterStep: Double? get() = (steps - lines).let { later -> if (later <= 0) null else (readMs - firstMs).toDouble() / later }
    }

    /** The lines hold [pieces] pieces in all, fewer than the [want] of the worst case: nothing was read or written. */
    class TooFewPieces(val pieces: Int, val want: Int) : Exception("the worst case needs $want pieces, the lines hold $pieces")

    /** The header of the output, after its comment lines. */
    val header = listOf("line", "ms", "first_ms", "pieces", "parts", "tokens", "steps", "mean_log_prob", "capped", "english")

    /** Reads [lines] at [beam] with [reader], one output row each to [out]; [onLine] hears of each line read. */
    suspend fun lines(
        lines: List<String>,
        reader: LoadedReader,
        beam: Int,
        firstStep: Boolean,
        out: Appendable,
        onLine: (Int) -> Unit = {}
    ): Summary {
        out.append(header.joinToString("\t")).append('\n')
        var inParts = 0
        var pieces = 0
        var tokens = 0
        var steps = 0
        var capped = 0
        var empty = 0
        var readMs = 0L
        // The lines with a first step timed: their count, the first steps' ms, their own ms and runs.
        var firstLines = 0
        var firstMsAll = 0L
        var firstReadMs = 0L
        var firstLineSteps = 0
        for ((index, line) in lines.withIndex()) {
            // Before the line's own clock starts: its ms is its reading alone.
            val firstMs = if (firstStep) firstStepMs(line, reader, beam) else null
            val started = clock()
            val reading = reader.read(line, beam)
            val ms = clock() - started
            readMs += ms
            pieces += reading.pieces
            tokens += reading.tokens
            steps += reading.steps
            if (reading.parts.size > 1) inParts++
            if (reading.capped) capped++
            if (reading.text.isEmpty()) empty++
            if (firstMs != null) {
                firstLines++
                firstMsAll += firstMs
                firstReadMs += ms
                firstLineSteps += reading.steps
            }
            row(out, index + 1, ms, firstMs, reading.pieces, reading.parts.size, reading.tokens, reading.steps, reading.meanLogProb, reading.capped, reading.text)
            onLine(index + 1)
        }
        val firstSteps = if (firstStep) FirstSteps(firstLines, firstMsAll, firstReadMs, firstLineSteps) else null
        return Summary(lines.size, inParts, pieces, tokens, steps, capped, empty, readMs, firstSteps)
    }

    /** What the worst case did. */
    data class WorstCase(val pieces: Int, val tokens: Int, val steps: Int, val capped: Boolean, val ms: Long)

    /**
     * The worst case, one source of 253 pieces from [lines] read at [beam] with the end held back
     * to the cap, written as one row to [out].
     *
     * @throws TooFewPieces when the lines hold fewer than 253 pieces in all, before anything is
     *   written, so a caller writing [out] to a file leaves none ([ReaderSelftestFiles.writing])
     */
    suspend fun worstCase(lines: List<String>, reader: LoadedReader, beam: Int, out: Appendable): WorstCase {
        val want = reader.limits.maxSourcePieces
        val pieces = ArrayList<String>(want)
        for (line in lines) {
            if (pieces.size >= want) break
            pieces += reader.pieces(IndicPreprocessor.preprocess(line).text)
        }
        if (pieces.size < want) throw TooFewPieces(pieces.size, want)
        val source = reader.pieceIds.sourceIds(pieces.subList(0, want))
        val cap = ReaderEngine.maxNewTokens(want, reader.limits.maxTargetPositions)
        out.append(header.joinToString("\t")).append('\n')
        val started = clock()
        val reading = reader.translate(source, beam, maxNewTokens = 0, minTokens = cap)
        val ms = clock() - started
        val english = EnglishDetokenizer.detokenize(reader.pieceIds.targetPieces(reading.ids))
        row(out, 1, ms, null, want, 1, reading.ids.size, reading.steps, reading.meanLogProb, reading.capped, english)
        return WorstCase(want, reading.ids.size, reading.steps, reading.capped, ms)
    }

    // The ms of a reading of the line's first part cut at one token: its encoder and first step.
    private suspend fun firstStepMs(line: String, reader: LoadedReader, beam: Int): Long? {
        val pieces = reader.pieces(IndicPreprocessor.preprocess(line).text)
        if (pieces.isEmpty()) return null
        val first = PieceIds.splitAtWords(pieces, reader.limits.maxSourcePieces).first()
        val started = clock()
        reader.translate(reader.pieceIds.sourceIds(first), beam, maxNewTokens = 1)
        return clock() - started
    }

    private fun row(
        out: Appendable,
        line: Int,
        ms: Long,
        firstMs: Long?,
        pieces: Int,
        parts: Int,
        tokens: Int,
        steps: Int,
        mean: Double?,
        capped: Boolean,
        english: String
    ) {
        out.append(line.toString()).append('\t')
            .append(ms.toString()).append('\t')
            .append(firstMs?.toString() ?: "").append('\t')
            .append(pieces.toString()).append('\t')
            .append(parts.toString()).append('\t')
            .append(tokens.toString()).append('\t')
            .append(steps.toString()).append('\t')
            .append(mean?.let(::number) ?: "").append('\t')
            .append(if (capped) "1" else "0").append('\t')
            .append(ReaderSelftestFiles.json(english)).append('\n')
    }

    companion object {
        /** A mean log-probability as Python reads it back: `-inf` for negative infinity. */
        internal fun number(value: Double): String = when {
            value == Double.NEGATIVE_INFINITY -> "-inf"
            value.isNaN() -> "nan"
            else -> String.format(Locale.ROOT, "%.6f", value)
        }
    }
}
