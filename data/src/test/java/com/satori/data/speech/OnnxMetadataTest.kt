package com.satori.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * The metadata of an ONNX file, read as sherpa-onnx's ONNX Runtime reads it, without loading the
 * model: every metadata_props entry wherever it is, every other field skipped unread, and a file
 * that isn't a protobuf message refused. The files are made here: no model weights.
 */
class OnnxMetadataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The metadata of the Malayalam model's file, as sherpa-onnx's CLI logged it on the phone. */
    private val malayalam = linkedMapOf(
        "version" to "1",
        "model_type" to "EncDecCTCModelBPE",
        "normalize_type" to "per_feature",
        "subsampling_factor" to "4",
        "model_author" to "ai4bharat",
        "vocab_size" to "257"
    )

    // A graph of bytes that are no protobuf: a reader that went into it would fail.
    private val graph = Random(7).nextBytes(200_000).also { it.fill(0xFF.toByte(), 0, 64) }

    private fun file(proto: OnnxProto) = proto.writeTo(tmp.newFile())

    @Test
    fun `the entries after the graph are read, in their order`() {
        val model = file(OnnxProto().irVersion().producer("onnx.quantize").graph(graph).varintField(8, 17).metadata(malayalam))

        val read = OnnxMetadata.read(model)

        assertEquals(malayalam, read)
        assertEquals(malayalam.keys.toList(), read.keys.toList())
    }

    @Test
    fun `entries before the graph and between other fields count as well`() {
        val model = file(
            OnnxProto().irVersion().metadata("model_type", "EncDecCTCModelBPE").graph(graph)
                .fixed32Field(30).fixed64Field(31).metadata("vocab_size", "257")
        )

        assertEquals(mapOf("model_type" to "EncDecCTCModelBPE", "vocab_size" to "257"), OnnxMetadata.read(model))
    }

    @Test
    fun `a key given twice keeps its last value, as ONNX Runtime does`() {
        val model = file(OnnxProto().irVersion().metadata("subsampling_factor", "8").graph(graph).metadata("subsampling_factor", "4"))

        assertEquals(mapOf("subsampling_factor" to "4"), OnnxMetadata.read(model))
    }

    @Test
    fun `an entry without a value is empty, one without a key is no entry`() {
        val model = file(OnnxProto().irVersion().metadata("normalize_type", null).metadata(null, "4"))

        assertEquals(mapOf("normalize_type" to ""), OnnxMetadata.read(model))
    }

    @Test
    fun `a file without metadata has none, and so has an empty one`() {
        assertEquals(emptyMap<String, String>(), OnnxMetadata.read(file(OnnxProto().irVersion().graph(graph))))
        assertEquals(emptyMap<String, String>(), OnnxMetadata.read(tmp.newFile()))
    }

    @Test
    fun `values are text in UTF-8`() {
        val model = file(OnnxProto().irVersion().metadata("comment", "മലയാളം, CTC head"))

        assertEquals("മലയാളം, CTC head", OnnxMetadata.read(model)["comment"])
    }

    @Test
    fun `an entry longer than a metadata entry can be is skipped unread`() {
        val long = "x".repeat(OnnxMetadata.MAX_ENTRY_BYTES)
        val model = file(OnnxProto().irVersion().metadata("doc", long).metadata("vocab_size", "257"))

        assertEquals(mapOf("vocab_size" to "257"), OnnxMetadata.read(model))
    }

    @Test
    fun `the metadata at the end of a large model is read past its graph`() {
        // As large as the Malayalam model's file, its graph a hole of zeros.
        val model = OnnxProto.sparseModel(tmp.newFile(), 137_563_723L, malayalam)

        assertEquals(malayalam, OnnxMetadata.read(model))
    }

    @Test
    fun `a field that runs past the end is refused`() {
        val whole = OnnxProto().irVersion().graph(graph).metadata(malayalam).bytes()
        val cut = tmp.newFile().apply { writeBytes(whole.copyOf(whole.size - 10)) }

        assertThrows(MalformedModelFileException::class.java) { OnnxMetadata.read(cut) }
    }

    @Test
    fun `a file that is no protobuf message is refused`() {
        val page = tmp.newFile().apply { writeText("<!DOCTYPE html><html><body>Sign in to continue</body></html>") }
        // Field 1 as a group (wire type 3), which ONNX never writes; field number 0.
        val group = file(OnnxProto().raw(0x0B, 0x0C))
        val zero = file(OnnxProto().raw(0x02, 0x00))
        // A number that never ends.
        val endless = file(OnnxProto().raw(0x08, 0xFF, 0xFF))

        for (file in listOf(page, group, zero, endless)) {
            assertThrows(file.readText(), MalformedModelFileException::class.java) { OnnxMetadata.read(file) }
        }
    }

    @Test
    fun `a metadata entry that runs past its own end is refused`() {
        // An entry of 4 bytes whose key says it has 9.
        val model = file(OnnxProto().irVersion().bytesField(14, byteArrayOf(0x0A, 0x09, 0x61, 0x62)))

        assertThrows(MalformedModelFileException::class.java) { OnnxMetadata.read(model) }
    }
}
