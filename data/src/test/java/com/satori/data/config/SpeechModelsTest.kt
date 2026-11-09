package com.satori.data.config

import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry of speech models: what each model's files are, how big, from which commit, and
 * where on the phone. A change here means a model changed; the sizes and hashes are those of the
 * Hugging Face listings of the pinned commits.
 */
class SpeechModelsTest {

    private val all = SpeechModelId.entries.map(SpeechModels::of)

    @Test
    fun `every model has its entry`() {
        assertEquals(SpeechModelId.entries, all.map { it.id })
    }

    @Test
    fun `the sizes are those of the pinned commits`() {
        assertEquals(670_478_772L, SpeechModels.PARAKEET_V3.bytes)
        assertEquals(661_190_513L, SpeechModels.PARAKEET_V2_EN.bytes)
        assertEquals(2_888_052_036L, SpeechModels.COHERE_TRANSCRIBE.bytes)
        assertEquals(137_566_803L, SpeechModels.INDICCONFORMER_ML.bytes)
        assertEquals(
            mapOf("model.int8.onnx" to 137_563_723L, "tokens.txt" to 3_080L),
            SpeechModels.INDICCONFORMER_ML.specs.associate { it.name to it.bytes }
        )
        assertEquals(
            mapOf(
                "encoder.int8.onnx" to 652_184_296L,
                "decoder.int8.onnx" to 7_257_753L,
                "joiner.int8.onnx" to 1_739_080L,
                "tokens.txt" to 9_384L
            ),
            SpeechModels.PARAKEET_V2_EN.specs.associate { it.name to it.bytes }
        )
        assertEquals(
            mapOf(
                "encoder.int8.onnx" to 3_090_822L,
                "encoder.int8.onnx.data" to 2_731_503_072L,
                "decoder.int8.onnx" to 153_250_705L,
                "tokens.txt" to 207_437L
            ),
            SpeechModels.COHERE_TRANSCRIBE.specs.associate { it.name to it.bytes }
        )
    }

    @Test
    fun `each file has an exact size and a SHA-256`() {
        all.flatMap { it.files + listOfNotNull(it.vocabulary) }.forEach { file ->
            assertTrue(file.spec.name, (file.spec.bytes ?: 0L) > 0L)
            assertTrue(file.spec.name, Regex("[0-9a-f]{64}").matches(file.sha256))
        }
    }

    @Test
    fun `each file comes from a commit, not a branch, under its own name`() {
        all.flatMap { it.files + listOfNotNull(it.vocabulary) }.forEach { file ->
            assertTrue(
                file.url,
                Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/${Regex.escape(file.spec.name)}$").matches(file.url)
            )
        }
    }

    @Test
    fun `Parakeet v3 stays where it is, and the others have a folder named after them`() {
        assertEquals("", SpeechModels.PARAKEET_V3.folder)
        assertEquals("parakeet-tdt-0.6b-v2-int8", SpeechModels.PARAKEET_V2_EN.folder)
        assertEquals("cohere-transcribe-14-lang-int8", SpeechModels.COHERE_TRANSCRIBE.folder)
        assertEquals("indicconformer-ml-int8", SpeechModels.INDICCONFORMER_ML.folder)
    }

    @Test
    fun `the Malayalam model is the pinned community export, its hashes those of the listing`() {
        val (model, tokens) = SpeechModels.INDICCONFORMER_ML.files

        assertEquals(
            "https://huggingface.co/csndhanasekar/sherpa-onnx-indicconformer-ml-int8/resolve/" +
                "394e2c20b57eb0db12839ecff00f904a55ea9f47/model.int8.onnx",
            model.url
        )
        assertEquals(ModelFileFormat.Onnx, model.spec.format)
        assertEquals("357382eb9ad4ef45c9269042af953e627ec9ca66a396cad1a2f4ed8562c012a1", model.sha256)
        assertEquals(ModelFileFormat.Text, tokens.spec.format)
        assertEquals("60eb97740574a55504786d4ac0699b14280977ec346a7d9a9bbef4deeb1e559b", tokens.sha256)
        assertNull(SpeechModels.INDICCONFORMER_ML.vocabulary)
    }

    @Test
    fun `the Malayalam model's metadata and tokens are checked as the phone read them`() {
        val (model, tokens) = SpeechModels.INDICCONFORMER_ML.files

        assertEquals(
            mapOf(
                "model_type" to "EncDecCTCModelBPE",
                "vocab_size" to "257",
                "subsampling_factor" to "4",
                "normalize_type" to "per_feature"
            ),
            model.metadata
        )
        // The tokens are as many as the vocabulary the model writes from.
        assertEquals(model.metadata.getValue("vocab_size").toInt(), tokens.tokens)
        assertTrue(tokens.metadata.isEmpty())
        assertNull(model.tokens)
    }

    @Test
    fun `the models that loaded on the phone before the check have nothing checked beyond size and bytes`() {
        listOf(SpeechModels.PARAKEET_V3, SpeechModels.PARAKEET_V2_EN, SpeechModels.COHERE_TRANSCRIBE).forEach { spec ->
            spec.files.forEach { file ->
                assertTrue(file.spec.name, file.metadata.isEmpty())
                assertNull(file.spec.name, file.tokens)
            }
        }
    }

    @Test
    fun `Cohere's encoder weights sit beside the encoder, checked by their size`() {
        val names = SpeechModels.COHERE_TRANSCRIBE.files.map { it.spec.name }
        val weights = SpeechModels.COHERE_TRANSCRIBE.files.single { it.spec.name == "encoder.int8.onnx.data" }

        assertTrue("encoder.int8.onnx" in names)
        assertEquals(ModelFileFormat.Data, weights.spec.format)
        // From the same commit of the same repository as the encoder.
        assertEquals(
            SpeechModels.COHERE_TRANSCRIBE.files.first().url.substringBeforeLast('/'),
            weights.url.substringBeforeLast('/')
        )
    }

    @Test
    fun `the Parakeet models load as NeMo transducers and Cohere has no joiner`() {
        listOf(SpeechModels.PARAKEET_V3, SpeechModels.PARAKEET_V2_EN).forEach { model ->
            assertEquals(
                setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
                model.specs.map { it.name }.toSet()
            )
        }
        assertTrue(SpeechModels.COHERE_TRANSCRIBE.specs.none { it.name.startsWith("joiner") })
    }

    @Test
    fun `only Parakeet v3 has the vocabulary for words to recognize, as an extra file`() {
        val vocabulary = SpeechModels.PARAKEET_V3.vocabulary

        assertEquals("bpe.vocab", vocabulary?.spec?.name)
        assertEquals(117_408L, vocabulary?.spec?.bytes)
        // Not one of the files v3 needs: a phone without it still records.
        assertTrue(SpeechModels.PARAKEET_V3.files.none { it.spec.name == "bpe.vocab" })
        assertNull(SpeechModels.PARAKEET_V2_EN.vocabulary)
        assertNull(SpeechModels.COHERE_TRANSCRIBE.vocabulary)
    }
}
