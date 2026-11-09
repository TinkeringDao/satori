package com.satori.domain.modelfile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelFileCheckTest {

    private val encoder = ModelFileSpec("encoder.int8.onnx", ModelFileFormat.Onnx, bytes = 1_000L)
    private val tokens = ModelFileSpec("tokens.txt", ModelFileFormat.Text, bytes = 50L)
    private val gguf = ModelFileSpec("model.gguf", ModelFileFormat.Gguf, minBytes = 1_000L)
    private val exactGguf = ModelFileSpec("exact.gguf", ModelFileFormat.Gguf, bytes = 2_000L)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private val onnxHeader = bytes(0x08, 0x08, 0x12, 0x07)
    private val ggufHeader = "GGUF".toByteArray(Charsets.US_ASCII)

    @Test
    fun `no file is missing, and so is an empty one`() {
        assertEquals(ModelFileState.Missing, ModelFileCheck.check(encoder, null, ByteArray(0)))
        assertEquals(ModelFileState.Missing, ModelFileCheck.check(encoder, 0L, ByteArray(0)))
    }

    @Test
    fun `a file of the expected size that starts like an ONNX model is intact`() {
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(encoder, 1_000L, onnxHeader))
    }

    @Test
    fun `every field of a model can come first in an ONNX file`() {
        val firstBytes = listOf(0x08, 0x12, 0x1A, 0x22, 0x28, 0x32, 0x3A, 0x42, 0x72, 0xA2, 0xCA)
        for (first in firstBytes) {
            assertEquals(
                "first byte 0x${first.toString(16)}",
                ModelFileState.Intact,
                ModelFileCheck.check(encoder, 1_000L, bytes(first, 0x01, 0x02, 0x03))
            )
        }
    }

    @Test
    fun `a truncated or a longer file is damaged`() {
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, 999L, onnxHeader))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, 1_001L, onnxHeader))
    }

    @Test
    fun `a placeholder text is damaged, whatever its size`() {
        val placeholder = "placeholder".toByteArray(Charsets.US_ASCII)
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, placeholder.size.toLong(), placeholder))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, 1_000L, placeholder))
    }

    @Test
    fun `an error page saved as a model is damaged`() {
        val page = "<html><body>Access denied</body></html>".toByteArray(Charsets.US_ASCII)
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, 1_000L, page.copyOf(4)))
    }

    @Test
    fun `a text file is checked by its size alone`() {
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(tokens, 50L, "abcd".toByteArray()))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(tokens, 49L, "abcd".toByteArray()))
    }

    @Test
    fun `the weights an ONNX model keeps beside it are checked by their size alone`() {
        val weights = ModelFileSpec("encoder.int8.onnx.data", ModelFileFormat.Data, bytes = 4_000L)

        // Raw tensor bytes: any first byte, an ONNX field tag or not.
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(weights, 4_000L, bytes(0x00, 0x7f, 0x3c, 0x01)))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(weights, 3_999L, bytes(0x00, 0x7f, 0x3c, 0x01)))
    }

    @Test
    fun `a GGUF file needs its four letters`() {
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(exactGguf, 2_000L, ggufHeader))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(exactGguf, 2_000L, "GGML".toByteArray()))
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(exactGguf, 2_000L, bytes(0x47, 0x47)))
    }

    @Test
    fun `a file that could not be read is unreadable, not damaged`() {
        assertEquals(ModelFileState.Unreadable, ModelFileCheck.check(encoder, 1_000L, null))
        assertEquals(ModelFileState.Unreadable, ModelFileCheck.check(exactGguf, 2_000L, null))
        assertEquals(ModelFileState.Unreadable, ModelFileCheck.check(tokens, 50L, null))
    }

    @Test
    fun `the size is judged before the header, so a wrong size is damaged even if unreadable`() {
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(encoder, 999L, null))
        assertEquals(ModelFileState.Missing, ModelFileCheck.check(encoder, null, null))
        assertEquals(ModelFileState.Missing, ModelFileCheck.check(encoder, 0L, null))
    }

    @Test
    fun `without an exact size a file must reach the minimum`() {
        assertEquals(ModelFileState.Damaged, ModelFileCheck.check(gguf, 999L, ggufHeader))
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(gguf, 1_000L, ggufHeader))
        assertEquals(ModelFileState.Intact, ModelFileCheck.check(gguf, 5_000_000L, ggufHeader))
    }

    @Test
    fun `a model of several files is as bad as its worst file`() {
        val intact = ModelFileState.Intact
        val damaged = ModelFileState.Damaged
        val missing = ModelFileState.Missing
        val unreadable = ModelFileState.Unreadable
        assertEquals(intact, ModelFileCheck.combine(listOf(intact, intact)))
        assertEquals(intact, ModelFileCheck.combine(emptyList()))
        assertEquals(damaged, ModelFileCheck.combine(listOf(intact, damaged, intact)))
        assertEquals(missing, ModelFileCheck.combine(listOf(intact, missing)))
        assertEquals(missing, ModelFileCheck.combine(listOf(damaged, missing)))
        assertEquals(unreadable, ModelFileCheck.combine(listOf(intact, unreadable)))
        assertEquals(damaged, ModelFileCheck.combine(listOf(unreadable, damaged)))
        assertEquals(missing, ModelFileCheck.combine(listOf(unreadable, missing)))
    }

    @Test
    fun `a spec cannot expect fewer bytes than its minimum`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileSpec("x.gguf", ModelFileFormat.Gguf, bytes = 10L, minBytes = 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileSpec("x.gguf", ModelFileFormat.Gguf, minBytes = 0L)
        }
    }
}
