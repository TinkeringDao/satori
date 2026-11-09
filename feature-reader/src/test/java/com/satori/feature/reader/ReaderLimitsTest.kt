package com.satori.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The model's limits from its config.json, as the native reader is told them. */
class ReaderLimitsTest {

    // The keys the reader reads, with the values of the pinned IndicTrans2-Dist and keys it doesn't.
    private val pinned = """
        {
          "decoder_layers": 18,
          "decoder_start_token_id": 2,
          "decoder_vocab_size": 32296,
          "encoder_vocab_size": 122706,
          "eos_token_id": 2,
          "max_source_positions": 256,
          "max_target_positions": 256,
          "pad_token_id": 1,
          "torch_dtype": "float32"
        }
    """.trimIndent()

    @Test
    fun `the pinned build's limits are ReaderOptions' defaults`() {
        val limits = ReaderLimits.parse(pinned)

        assertEquals(ReaderLimits(122_706, 256, 256, decoderStartId = 2, endId = 2, padId = 1), limits)
        assertEquals(253, limits.maxSourcePieces)
        assertEquals(com.satori.reader.ReaderOptions(), ReaderArm.DEFAULT.options(limits))
    }

    @Test
    fun `fewer source positions read fewer pieces a part, never more than 253`() {
        assertEquals(61, ReaderLimits.parse(pinned.replace("\"max_source_positions\": 256", "\"max_source_positions\": 64")).maxSourcePieces)
        assertEquals(253, ReaderLimits.parse(pinned.replace("\"max_source_positions\": 256", "\"max_source_positions\": 1024")).maxSourcePieces)
    }

    @Test
    fun `a file that is not the model's configuration is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ReaderLimits.parse("[1, 2]") }
        assertThrows(IllegalArgumentException::class.java) { ReaderLimits.parse("<html>") }
        assertThrows(IllegalArgumentException::class.java) { ReaderLimits.parse(pinned.replace("\"eos_token_id\": 2,", "")) }
        assertThrows(IllegalArgumentException::class.java) { ReaderLimits.parse(pinned.replace("\"pad_token_id\": 1", "\"pad_token_id\": null")) }
        assertThrows(IllegalArgumentException::class.java) { ReaderLimits.parse(pinned.replace("122706", "0")) }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderLimits.parse(pinned.replace("\"max_source_positions\": 256", "\"max_source_positions\": 3"))
        }
    }
}
