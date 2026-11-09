package com.satori.data.datasource

import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import com.satori.domain.modelfile.ModelFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ModelFileInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Small stand-ins for the real files, so the tests don't need hundreds of megabytes.
    private val specs = listOf(
        ModelFileSpec("encoder.onnx", ModelFileFormat.Onnx, bytes = 300L),
        ModelFileSpec("decoder.onnx", ModelFileFormat.Onnx, bytes = 40L),
        ModelFileSpec("tokens.txt", ModelFileFormat.Text, bytes = 20L)
    )

    /** A file of exactly [size] bytes that starts with [first]. */
    private fun fileOf(dir: File, name: String, size: Long, first: Int = 0x08): File =
        File(dir, name).also { file ->
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(size)
                out.seek(0)
                out.write(first)
            }
        }

    private fun speechDir(): File = tmp.newFolder("stt")

    @Test
    fun `the sizes of the speech model are those of the files on the phone`() {
        // docs/DEV_SETUP.md, "Expected sizes". A change here means the model changed.
        assertEquals(
            mapOf(
                "encoder.int8.onnx" to 652_184_281L,
                "decoder.int8.onnx" to 11_845_275L,
                "joiner.int8.onnx" to 6_355_277L,
                "tokens.txt" to 93_939L
            ),
            SpeechModels.PARAKEET_V3.specs.associate { it.name to it.bytes }
        )
    }

    @Test
    fun `the speech model is downloaded from the revision whose sizes these are`() {
        // A branch name such as "main" moves, and the sizes would no longer match what is fetched.
        SpeechModels.PARAKEET_V3.files.forEach { file ->
            assertTrue(file.url, Regex("/resolve/[0-9a-f]{40}/${Regex.escape(file.spec.name)}$").containsMatchIn(file.url))
        }
    }

    @Test
    fun `a folder with every file the right size is intact`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 300L)
        fileOf(dir, "decoder.onnx", 40L)
        fileOf(dir, "tokens.txt", 20L, first = 'a'.code)
        assertEquals(ModelFileState.Intact, ModelFileInspector.speechModel(dir, specs))
    }

    @Test
    fun `placeholder files are damaged`() {
        // What the debug setup action writes so the emulator counts as set up.
        val dir = speechDir()
        SpeechModels.PARAKEET_V3.specs.forEach { File(dir, it.name).writeText("placeholder") }
        assertEquals(ModelFileState.Damaged, ModelFileInspector.speechModel(dir))
    }

    @Test
    fun `a folder with no files is missing the model`() {
        assertEquals(ModelFileState.Missing, ModelFileInspector.speechModel(speechDir(), specs))
        assertEquals(ModelFileState.Missing, ModelFileInspector.speechModel(File(tmp.root, "nowhere"), specs))
    }

    @Test
    fun `a file that is not there makes the model missing, even beside a damaged one`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 299L)
        fileOf(dir, "decoder.onnx", 40L)
        assertEquals(ModelFileState.Missing, ModelFileInspector.speechModel(dir, specs))
    }

    @Test
    fun `a truncated file makes the model damaged`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 299L)
        fileOf(dir, "decoder.onnx", 40L)
        fileOf(dir, "tokens.txt", 20L, first = 'a'.code)
        assertEquals(ModelFileState.Damaged, ModelFileInspector.speechModel(dir, specs))
    }

    @Test
    fun `a file of the right size that is not an ONNX model is damaged`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 300L, first = '<'.code)
        fileOf(dir, "decoder.onnx", 40L)
        fileOf(dir, "tokens.txt", 20L, first = 'a'.code)
        assertEquals(ModelFileState.Damaged, ModelFileInspector.speechModel(dir, specs))
    }

    @Test
    fun `the report names each file, its size, the size it should have and its state`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 299L)
        fileOf(dir, "decoder.onnx", 40L)
        assertEquals(
            "encoder.onnx 299/300 Damaged; decoder.onnx 40/40 Intact; tokens.txt absent/20 Missing",
            ModelFileInspector.speechModelReport(dir, specs)
        )
    }

    @Test
    fun `a file that cannot be read is unreadable, not damaged`() {
        val dir = speechDir()
        fileOf(dir, "encoder.onnx", 300L)
        fileOf(dir, "decoder.onnx", 40L)
        val tokens = fileOf(dir, "tokens.txt", 20L, first = 'a'.code)
        tokens.setReadable(false)
        // A process that may read every file (root) cannot be kept out this way.
        assumeFalse(tokens.canRead())
        assertEquals(ModelFileState.Unreadable, ModelFileInspector.speechModel(dir, specs))
        assertEquals("tokens.txt 20/20 Unreadable", ModelFileInspector.speechModelReport(dir, specs.takeLast(1)))
    }

    @Test
    fun `the AI model file of a variant is held to the size of that variant`() {
        val name = modelConfigForVariant(LlmModelVariant.Q8_0).llmFilename
        val dir = tmp.newFolder("models")
        File(dir, name).writeText("placeholder")
        assertEquals(ModelFileState.Damaged, ModelFileInspector.aiModel(File(dir, name)))
        assertEquals(ModelFileState.Missing, ModelFileInspector.aiModel(File(dir, "missing.gguf")))
    }

    @Test
    fun `Gemma 4 E2B is the file put on the phone, held to its exact size`() {
        val config = modelConfigForVariant(LlmModelVariant.GEMMA4_E2B_Q4_0)
        assertEquals(
            ModelFileSpec("gemma-4-E2B_q4_0-it.gguf", ModelFileFormat.Gguf, bytes = 3_349_516_256L),
            config.llmSpec
        )
        assertEquals(config.llmSpec.name, config.llmFilename)
        assertEquals(
            "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
            config.llmUrl
        )

        // A GGUF file big enough for any other model, such as a copy that stopped early, is damaged.
        val dir = tmp.newFolder("e2b")
        val partial = File(dir, config.llmFilename)
        RandomAccessFile(partial, "rw").use { out ->
            out.setLength(100_000_000L)
            out.seek(0)
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
        }
        assertEquals(ModelFileState.Damaged, ModelFileInspector.aiModel(partial))
    }

    @Test
    fun `every AI model has a file name of its own`() {
        // The inspector finds a file's spec by its name.
        val names = LlmModelVariant.entries.map { modelConfigForVariant(it).llmFilename }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `any other GGUF file has to look like a model`() {
        val dir = tmp.newFolder("other")
        val tiny = File(dir, "other.gguf").apply { writeText("GGUF and little else") }
        assertEquals(ModelFileState.Damaged, ModelFileInspector.aiModel(tiny))

        val large = File(dir, "large.gguf")
        RandomAccessFile(large, "rw").use { out ->
            out.setLength(100_000_000L)
            out.seek(0)
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
        }
        assertEquals(ModelFileState.Intact, ModelFileInspector.aiModel(large))

        val html = File(dir, "page.gguf")
        RandomAccessFile(html, "rw").use { out ->
            out.setLength(100_000_000L)
            out.seek(0)
            out.write("<html>".toByteArray(Charsets.US_ASCII))
        }
        assertEquals(ModelFileState.Damaged, ModelFileInspector.aiModel(html))
    }
}
