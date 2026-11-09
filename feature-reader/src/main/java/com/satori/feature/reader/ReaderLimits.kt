package com.satori.feature.reader

import com.satori.domain.reading.PieceIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The model's limits as its `config.json` gives them, which the native reader checks every call
 * against: the source vocabulary's size, the source and target positions, and the special ids. For
 * the pinned IndicTrans2-Dist they are ReaderOptions' defaults (122,706 source ids, 256 positions
 * each side, start and end 2, padding 1); read from the file, a toy model for the tests or the
 * emulator loads with its own.
 */
data class ReaderLimits(
    val sourceVocabulary: Int,
    val maxSourcePositions: Int,
    val maxTargetPositions: Int,
    val decoderStartId: Int,
    val endId: Int,
    val padId: Int
) {
    /**
     * The most pieces one reading takes: the source positions less the two language tags and
     * `</s>`, at most PieceIds' 253 (D15). A longer line is read in parts ([LineReader]).
     */
    val maxSourcePieces: Int get() = minOf(PieceIds.MAX_SOURCE_PIECES, maxSourcePositions - 3)

    companion object {
        /**
         * The limits in the text of a `config.json`.
         *
         * @throws IllegalArgumentException for a text that is no JSON object, or lacks a limit, or
         *   has one out of range: a file that is not the model's configuration
         */
        fun parse(json: String): ReaderLimits {
            val config: JsonElement = Json.parseToJsonElement(json)
            require(config is JsonObject) { "config.json is not a JSON object" }
            fun int(key: String): Int = requireNotNull((config[key] as? JsonPrimitive)?.intOrNull) { "config.json has no number $key" }
            val limits = ReaderLimits(
                sourceVocabulary = int("encoder_vocab_size"),
                maxSourcePositions = int("max_source_positions"),
                maxTargetPositions = int("max_target_positions"),
                decoderStartId = int("decoder_start_token_id"),
                endId = int("eos_token_id"),
                padId = int("pad_token_id")
            )
            require(limits.sourceVocabulary > 0 && limits.maxSourcePositions > 3 && limits.maxTargetPositions > 1) {
                "config.json's sizes are out of range"
            }
            return limits
        }
    }
}
