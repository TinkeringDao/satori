package com.satori.data.speech

import com.satori.data.config.SpeechModelFile
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import com.satori.domain.modelfile.ModelFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The check before sherpa-onnx loads a model: the metadata and the tokens the registry names, with
 * the reason for a refusal in names, keys, values and counts. A model whose entry names nothing is
 * passed without a read.
 */
class SpeechModelLoadCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val expected = mapOf(
        "model_type" to "EncDecCTCModelBPE",
        "vocab_size" to "4",
        "subsampling_factor" to "4",
        "normalize_type" to "per_feature"
    )

    private fun entry(name: String, metadata: Map<String, String> = emptyMap(), tokens: Int? = null) = SpeechModelFile(
        spec = ModelFileSpec(name, if (name.endsWith(".onnx")) ModelFileFormat.Onnx else ModelFileFormat.Text, bytes = 1L),
        url = "https://huggingface.co/a/b/resolve/0000000000000000000000000000000000000000/$name",
        sha256 = "0".repeat(64),
        metadata = metadata,
        tokens = tokens
    )

    private val model = entry("model.int8.onnx", metadata = expected)
    private val tokens = entry("tokens.txt", tokens = 4)

    /** A folder with a model file of [metadata] and a tokens.txt of [tokenLines]. */
    private fun folder(metadata: Map<String, String> = expected, tokenLines: String = "<unk> 0\n▁a 1\n▁b 2\n<blk> 3\n"): File {
        val dir = tmp.newFolder()
        OnnxProto().irVersion().graph(ByteArray(1_000)).metadata(metadata).writeTo(File(dir, "model.int8.onnx"))
        File(dir, "tokens.txt").writeText(tokenLines)
        return dir
    }

    @Test
    fun `files that hold what the registry says pass`() {
        val result = SpeechModelLoadCheck.check(folder(), listOf(model, tokens))

        assertEquals(ModelFileState.Intact, result.state)
        assertNull(result.reason)
    }

    @Test
    fun `other metadata besides is no reason to refuse`() {
        val dir = folder(metadata = expected + mapOf("version" to "1", "model_author" to "ai4bharat"))

        assertEquals(ModelFileState.Intact, SpeechModelLoadCheck.check(dir, listOf(model, tokens)).state)
    }

    @Test
    fun `a subsampling factor of 8 on a model that subsamples by 4 is refused, with what it is`() {
        val result = SpeechModelLoadCheck.check(folder(metadata = expected + ("subsampling_factor" to "8")), listOf(model, tokens))

        assertEquals(ModelFileState.Damaged, result.state)
        assertEquals("model.int8.onnx metadata subsampling_factor=8, expected 4", result.reason)
    }

    @Test
    fun `a key sherpa-onnx needs and doesn't find is refused before it ends the process`() {
        val result = SpeechModelLoadCheck.check(folder(metadata = expected - "vocab_size" - "model_type"), listOf(model, tokens))

        assertEquals(ModelFileState.Damaged, result.state)
        assertEquals(
            "model.int8.onnx metadata model_type=absent, expected EncDecCTCModelBPE, vocab_size=absent, expected 4",
            result.reason
        )
    }

    @Test
    fun `a model file that is no protobuf message is refused`() {
        val dir = folder()
        File(dir, "model.int8.onnx").writeText("<html>Not found</html>")

        val result = SpeechModelLoadCheck.check(dir, listOf(model, tokens))

        assertEquals(ModelFileState.Damaged, result.state)
        assertEquals("model.int8.onnx: Wire type 4 at byte 0", result.reason)
    }

    @Test
    fun `tokens that don't fit the model are refused`() {
        val result = SpeechModelLoadCheck.check(folder(tokenLines = "<unk> 0\n▁a 1\n<blk> 2\n"), listOf(model, tokens))

        assertEquals(ModelFileState.Damaged, result.state)
        assertEquals("tokens.txt: 3 tokens, not 4", result.reason)
    }

    @Test
    fun `a file that can't be read is unreadable, not damaged`() {
        val dir = folder()
        // A folder where the model file should be: there is something, but nothing to read.
        File(dir, "model.int8.onnx").delete()
        File(dir, "model.int8.onnx").mkdir()

        val result = SpeechModelLoadCheck.check(dir, listOf(model, tokens))

        assertEquals(ModelFileState.Unreadable, result.state)
        assertEquals("model.int8.onnx can't be read (FileNotFoundException)", result.reason)
    }

    @Test
    fun `a model whose entry names nothing is passed without a read`() {
        // No files at all: the entries say nothing to check.
        val result = SpeechModelLoadCheck.check(tmp.newFolder(), listOf(entry("encoder.int8.onnx"), entry("tokens.txt")))

        assertEquals(ModelFileState.Intact, result.state)
    }
}
