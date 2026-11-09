package com.satori.data.config

import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug build's test server for the model downloads: its manifest, and what it changes in the registry. */
class ModelSourceTest {

    private val encoderHash = "a".repeat(64)
    private val tokensHash = "B".repeat(64)
    private val manifest = """
        # synthetic files
        $encoderHash  652184281  stt/parakeet-tdt-0.6b-v3-int8/encoder.int8.onnx

        $tokensHash 93939 stt/parakeet-tdt-0.6b-v3-int8/tokens.txt
    """.trimIndent()

    private val server = ModelSource.TestServer.parse("http://10.0.2.2:8099", manifest)

    @Test
    fun `the manifest gives each file's hash and size by its path, and the address ends in a slash`() {
        assertEquals("http://10.0.2.2:8099/", server.base)
        assertEquals(
            mapOf(
                "stt/parakeet-tdt-0.6b-v3-int8/encoder.int8.onnx" to ModelSource.ManifestEntry(encoderHash, 652_184_281L),
                "stt/parakeet-tdt-0.6b-v3-int8/tokens.txt" to ModelSource.ManifestEntry("b".repeat(64), 93_939L)
            ),
            server.files
        )
    }

    @Test
    fun `every speech file comes from the server, with the manifest's hash where it has one`() {
        val spec = server.speech(SpeechModels.PARAKEET_V3)
        val encoder = spec.files.first { it.spec.name == "encoder.int8.onnx" }
        val decoder = spec.files.first { it.spec.name == "decoder.int8.onnx" }

        assertEquals("http://10.0.2.2:8099/stt/parakeet-tdt-0.6b-v3-int8/encoder.int8.onnx", encoder.url)
        assertEquals(encoderHash, encoder.sha256)
        // Not in the manifest: still from the server, never from the internet, with the registry's hash.
        assertEquals("http://10.0.2.2:8099/stt/parakeet-tdt-0.6b-v3-int8/decoder.int8.onnx", decoder.url)
        assertEquals(SpeechModels.PARAKEET_V3.files[1].sha256, decoder.sha256)
        assertEquals("http://10.0.2.2:8099/stt/parakeet-tdt-0.6b-v3-int8/bpe.vocab", spec.vocabulary?.url)
    }

    @Test
    fun `only the addresses and hashes change, never what a complete file looks like`() {
        val spec = server.speech(SpeechModels.INDICCONFORMER_ML)

        assertEquals(SpeechModels.INDICCONFORMER_ML.specs, spec.specs)
        assertEquals(SpeechModels.INDICCONFORMER_ML.files.map { it.metadata }, spec.files.map { it.metadata })
        assertEquals(SpeechModels.INDICCONFORMER_ML.files.map { it.tokens }, spec.files.map { it.tokens })
        assertEquals(SpeechModels.INDICCONFORMER_ML.folder, spec.folder)
        assertTrue(spec.files.all { it.url.startsWith("http://10.0.2.2:8099/stt/indicconformer-ml-int8/") })
    }

    @Test
    fun `a reader's file the manifest lists takes its size and hash, so a toy reader counts on an emulator`() {
        val toyHash = "c".repeat(64)
        val toy = ModelSource.TestServer.parse(
            "http://10.0.2.2:8099",
            "$toyHash 4096 reader/${ReaderModelId.INT8.id}/encoder_model.onnx"
        )

        val spec = toy.reader(ReaderModelId.INT8, ReaderModels.INT8)
        val encoder = spec.file("encoder_model.onnx")
        val decoder = spec.file("decoder_model.onnx")

        assertEquals("http://10.0.2.2:8099/reader/${ReaderModelId.INT8.id}/encoder_model.onnx", encoder.url)
        assertEquals(toyHash, encoder.sha256)
        assertEquals(4_096L, encoder.spec.bytes)
        assertEquals(ReaderModels.INT8.file("encoder_model.onnx").spec.format, encoder.spec.format)
        // Not in the manifest: the registry's size and hash, at the server.
        assertEquals(ReaderModels.INT8.file("decoder_model.onnx").spec, decoder.spec)
        assertEquals(ReaderModels.INT8.file("decoder_model.onnx").sha256, decoder.sha256)
        assertEquals("http://10.0.2.2:8099/reader/${ReaderModelId.INT8.id}/decoder_model.onnx", decoder.url)
    }

    @Test
    fun `the AI model comes from the server's llm folder`() {
        val config = server.llm(DefaultModelConfig.INSTANCE)

        assertEquals("http://10.0.2.2:8099/llm/gemma-3-1b-it-Q8_0.gguf", config.llmUrl)
        assertEquals(DefaultModelConfig.INSTANCE.llmSpec, config.llmSpec)
    }

    @Test
    fun `without a server the registry is as it is`() {
        assertNull(ModelSource.server)
        assertEquals(SpeechModels.PARAKEET_V3, SpeechModels.of(SpeechModelId.PARAKEET_V3))
        assertEquals(DefaultModelConfig.INSTANCE, modelConfigForVariant(LlmModelVariant.Q8_0))
    }

    @Test
    fun `a manifest that can't be used is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ModelSource.TestServer.parse("10.0.2.2:8099", manifest) }
        assertThrows(IllegalArgumentException::class.java) { ModelSource.TestServer.parse("http://10.0.2.2:8099/", "") }
        assertThrows(IllegalArgumentException::class.java) { ModelSource.TestServer.parse("http://h/", "$encoderHash stt/a") }
        assertThrows(IllegalArgumentException::class.java) { ModelSource.TestServer.parse("http://h/", "nothex 12 stt/a") }
        assertThrows(IllegalArgumentException::class.java) { ModelSource.TestServer.parse("http://h/", "$encoderHash -3 stt/a") }
    }

    @Test
    fun `the paths on the server name the model and the file`() {
        assertEquals("stt/indicconformer-ml-int8/tokens.txt", ModelSource.speechPath(SpeechModelId.INDICCONFORMER_ML, "tokens.txt"))
        assertEquals("llm/gemma-4-E2B_q4_0-it.gguf", ModelSource.llmPath("gemma-4-E2B_q4_0-it.gguf"))
    }
}
