package com.satori.domain.reading

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * IndicTrans2's two vocabularies, `dict.SRC.json` and `dict.TGT.json`: SentencePiece's pieces to
 * the model's source ids, and the ids it writes back to pieces, as the export's
 * `tokenization_indictrans.py` maps them. SentencePiece's own ids are not the model's; these
 * dictionaries are.
 *
 * @param source Piece to source id, as `dict.SRC.json` has them
 * @param target Piece to target id, as `dict.TGT.json` has them
 */
class PieceIds(source: Map<String, Int>, target: Map<String, Int>) {

    /** As given: the source vocabulary has 122,706 entries, too many to copy on the phone for nothing. */
    private val sourceIds = source
    private val unknownId = idOf(source, UNKNOWN)
    private val endId = idOf(source, END)
    private val tagIds = intArrayOf(idOf(source, IndicPreprocessor.SOURCE_TAG), idOf(source, IndicPreprocessor.TARGET_TAG))

    /** Target id to piece. Of two pieces with one id the later wins, as in the reference's reversed dict. */
    private val targetPieces: Array<String?>

    /** The ids the export's decoding skips: `<s>`, `<pad>`, `</s>` and `<unk>`. */
    private val specialIds: Set<Int> = SPECIAL.mapNotNull { target[it] }.toSet()

    init {
        // The largest id is compared before one is added to it, so an id of Int.MAX_VALUE is refused too.
        val largest = target.values.maxOrNull() ?: -1
        require(largest < MAX_VOCABULARY && target.values.all { it >= 0 }) { "target ids out of range" }
        targetPieces = arrayOfNulls(largest + 1)
        for ((piece, id) in target) targetPieces[id] = piece
    }

    /**
     * The source sequence of a line's pieces: the two language tags, each piece's id (`<unk>`'s
     * for a piece the vocabulary lacks), then `</s>`. At most [MAX_SOURCE_PIECES] pieces fit the
     * model's 256 positions with the tags and `</s>`; split longer lines first ([splitAtWords]).
     */
    fun sourceIds(pieces: List<String>): IntArray {
        require(pieces.size <= MAX_SOURCE_PIECES) { "${pieces.size} pieces, at most $MAX_SOURCE_PIECES fit" }
        val ids = IntArray(pieces.size + 3)
        ids[0] = tagIds[0]
        ids[1] = tagIds[1]
        pieces.forEachIndexed { index, piece -> ids[index + 2] = sourceIds[piece] ?: unknownId }
        ids[ids.size - 1] = endId
        return ids
    }

    /**
     * The pieces of the ids the model wrote, without the special tokens: the export skips those
     * when it decodes, and maps an id past its vocabulary to `<unk>`, which it skips too.
     */
    fun targetPieces(ids: IntArray): List<String> {
        val pieces = ArrayList<String>(ids.size)
        for (id in ids) {
            if (id in specialIds) continue
            val piece = targetPieces.getOrNull(id) ?: continue
            pieces += piece
        }
        return pieces
    }

    companion object {

        /**
         * The source pieces one reading may have: the model has 256 source positions, and the two
         * language tags and `</s>` take three (decision D15).
         */
        const val MAX_SOURCE_PIECES = 253

        /** SentencePiece's mark for the start of a word. */
        const val WORD_START = '▁'

        private const val UNKNOWN = "<unk>"
        private const val END = "</s>"
        private val SPECIAL = listOf("<s>", "<pad>", END, UNKNOWN)

        /** More ids than any vocabulary of this model has; a file claiming more is damaged. */
        private const val MAX_VOCABULARY = 1 shl 20

        private val dictionary = MapSerializer(String.serializer(), Int.serializer())

        /** A vocabulary file's entries, piece to id. Throws on anything that is not such a JSON object. */
        fun parse(json: String): Map<String, Int> = Json.decodeFromString(dictionary, json)

        /** Both vocabularies, from the text of `dict.SRC.json` and `dict.TGT.json`. */
        fun of(sourceJson: String, targetJson: String): PieceIds = PieceIds(parse(sourceJson), parse(targetJson))

        /**
         * A line's pieces in parts of at most [max], each ending where a word ends (before a piece
         * that starts with [WORD_START]), so each part reads alone (decision D15). A placeholder's
         * `< IDn >` is three words, but it is put back only whole, so no part ends inside one. A
         * single word longer than [max] pieces is cut after [max] of them.
         */
        fun splitAtWords(pieces: List<String>, max: Int = MAX_SOURCE_PIECES): List<List<String>> {
            require(max > 0) { "max must be positive" }
            if (pieces.size <= max) return listOf(pieces)
            val inPlaceholder = insidePlaceholders(pieces)
            val parts = ArrayList<List<String>>()
            var start = 0
            while (start < pieces.size) {
                var end = minOf(start + max, pieces.size)
                if (end < pieces.size) {
                    var cut = end
                    while (cut > start && (!pieces[cut].startsWith(WORD_START) || inPlaceholder[cut])) cut--
                    if (cut > start) end = cut
                }
                parts += pieces.subList(start, end)
                start = end
            }
            return parts
        }

        /** How IndicPreprocessor's `<IDn>` reaches SentencePiece: a word each for `<`, `IDn` and `>`. */
        private const val PLACEHOLDER_OPEN = "$WORD_START<${WORD_START}ID"
        private const val PLACEHOLDER_CLOSE = "$WORD_START>"

        /**
         * For each piece, whether it lies inside a placeholder's pieces after the first, so that a
         * part ending before it would cut the placeholder in two: the pieces are read as the text
         * they spell, whichever way SentencePiece cut `▁<▁ID1▁>`.
         */
        private fun insidePlaceholders(pieces: List<String>): BooleanArray {
            val inside = BooleanArray(pieces.size)
            val text = pieces.joinToString("")
            var piece = 0
            var pieceStart = 0
            var from = 0
            while (true) {
                val open = text.indexOf(PLACEHOLDER_OPEN, from)
                if (open < 0) break
                var end = open + PLACEHOLDER_OPEN.length
                val digits = end
                while (end < text.length && text[end] in '0'..'9') end++
                if (end == digits || !text.startsWith(PLACEHOLDER_CLOSE, end)) {
                    from = open + 1
                    continue
                }
                end += PLACEHOLDER_CLOSE.length
                // The pieces that start after the placeholder's first character and before its end.
                while (piece < pieces.size && pieceStart < end) {
                    if (pieceStart > open) inside[piece] = true
                    pieceStart += pieces[piece].length
                    piece++
                }
                from = end
            }
            return inside
        }

        private fun idOf(vocabulary: Map<String, Int>, piece: String): Int =
            requireNotNull(vocabulary[piece]) { "the source vocabulary has no $piece" }
    }
}
