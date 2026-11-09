package com.arm.aichat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

/**
 * What a load without MAP_POPULATE reads in place, from a GGUF header laid out as the app's two
 * models' are (their tensor tables, test resources): Gemma 4 E2B with its layers' matrices copied
 * reads its tied output, per_layer_model_proj and its norms, 342.34 MiB, never its 1.8 GB of
 * per-layer embeddings; with nothing copied (repack off) all but those, 1341.76 MiB; the 1B its
 * tied output and its norms, 306.51 MiB, or all of it.
 */
class InPlaceTensorsTest {

    private fun rows(model: String) = TestGguf.rows(model)

    private fun header(rows: List<TestGguf.Row>, withAlignmentKey: Boolean = true) = TestGguf.header(rows, withAlignmentKey = withAlignmentKey)

    private fun inPlace(model: String, layersCopied: Boolean): List<LongRange> {
        val (bytes, length) = header(rows(model))
        return InPlaceTensors.ranges(GgufTensors.read(ByteArrayInputStream(bytes), length), layersCopied)
    }

    private fun mib(ranges: List<LongRange>) = String.format(Locale.ROOT, "%.2f", ranges.sumOf { it.last - it.first + 1 } / 1_048_576.0)

    @Test
    fun `E2B with its layers copied reads its tied output, per_layer_model_proj and the norms in place`() {
        val ranges = inPlace("gemma-4-E2B_q4_0-it", layersCopied = true)

        assertEquals(265, ranges.size)
        assertEquals(358_972_512L, ranges.sumOf { it.last - it.first + 1 })
        assertEquals("342.34", mib(ranges))
    }

    @Test
    fun `E2B with nothing copied reads all but its per-layer embeddings in place`() {
        val ranges = inPlace("gemma-4-E2B_q4_0-it", layersCopied = false)

        assertEquals(540, ranges.size)
        assertEquals("1341.76", mib(ranges))
    }

    @Test
    fun `the 1B reads its tied output and its norms in place, or all of it`() {
        assertEquals(158, inPlace("gemma-3-1b-it-Q8_0", layersCopied = true).size)
        assertEquals("306.51", mib(inPlace("gemma-3-1b-it-Q8_0", layersCopied = true)))
        assertEquals("1013.54", mib(inPlace("gemma-3-1b-it-Q8_0", layersCopied = false)))
    }

    @Test
    fun `the table gives each tensor's place in the file, from the start of its data`() {
        val rows = rows("gemma-4-E2B_q4_0-it")
        val (bytes, length) = header(rows)
        val tensors = GgufTensors.read(ByteArrayInputStream(bytes), length)
        val dataStart = (bytes.size + 31L) / 32 * 32

        assertEquals(541, tensors.size)
        val embeddings = tensors.single { it.name == "token_embd.weight" }
        // After output_norm, per_layer_model_proj, per_layer_proj_norm, per_layer_token_embd and rope_freqs.
        assertEquals(dataStart + 1_954_291_712L, embeddings.offset)
        assertEquals(330_301_440L, embeddings.size)
        // The last tensor reaches to the end of the file.
        assertEquals(length, tensors.last().offset + tensors.last().size)
        assertTrue(tensors.zipWithNext().all { (a, b) -> a.offset + a.size == b.offset })
    }

    @Test
    fun `without an alignment key the data starts at 32 bytes, llama_cpp's default`() {
        val rows = listOf(TestGguf.Row("output_norm.weight", 6_144), TestGguf.Row("token_embd.weight", 330_301_440))
        val (bytes, length) = header(rows, withAlignmentKey = false)

        val tensors = GgufTensors.read(ByteArrayInputStream(bytes), length)

        assertEquals((bytes.size + 31L) / 32 * 32, tensors.first().offset)
    }

    @Test
    fun `a model with an output matrix of its own reads its token embeddings by row, so not in place`() {
        val rows = listOf(TestGguf.Row("token_embd.weight", 4_096), TestGguf.Row("output.weight", 4_096), TestGguf.Row("blk.0.attn_q.weight", 1_024))
        val (bytes, length) = header(rows)
        val tensors = GgufTensors.read(ByteArrayInputStream(bytes), length)

        val ranges = InPlaceTensors.ranges(tensors, layersCopied = true)

        assertEquals(listOf(tensors.single { it.name == "output.weight" }.let { it.offset until it.offset + it.size }), ranges)
    }

    @Test
    fun `a file that isn't a GGUF, or whose header ends early, isn't read`() {
        val (bytes, length) = header(rows("gemma-3-1b-it-Q8_0"))
        val notGguf = bytes.copyOf().also { it[0] = 'X'.code.toByte() }

        for (broken in listOf(notGguf, bytes.copyOf(bytes.size / 2))) {
            try {
                GgufTensors.read(ByteArrayInputStream(broken), length)
                fail("read a broken header")
            } catch (e: IOException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
    }
}
