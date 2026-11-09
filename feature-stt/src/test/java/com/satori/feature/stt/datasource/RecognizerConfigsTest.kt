package com.satori.feature.stt.datasource

import com.satori.data.config.SpeechModels
import com.satori.domain.model.SpeechModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recognizer config of each speech model: the field names are those JNI reads, so a wrong
 * one is a native crash or a model that silently loads as something else.
 */
class RecognizerConfigsTest {

    private val v3 = "/models/stt"
    private val cohere = "/models/stt/cohere-transcribe-14-lang-int8"
    private val malayalam = "/models/stt/indicconformer-ml-int8"

    @Test
    fun `Parakeet v3 records as a NeMo transducer with greedy search on two threads`() {
        val config = RecognizerConfigs.of(SpeechModelId.PARAKEET_V3, RecognizerUse.LIVE, v3, hotwords = null, language = null)

        assertEquals("$v3/encoder.int8.onnx", config.modelConfig.transducer.encoder)
        assertEquals("$v3/decoder.int8.onnx", config.modelConfig.transducer.decoder)
        assertEquals("$v3/joiner.int8.onnx", config.modelConfig.transducer.joiner)
        assertEquals("$v3/tokens.txt", config.modelConfig.tokens)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        assertEquals(2, config.modelConfig.numThreads)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("", config.hotwordsFile)
        assertEquals("", config.modelConfig.modelingUnit)
        assertEquals("", config.modelConfig.cohereTranscribe.encoder)
        assertEquals("cpu", config.modelConfig.provider)
    }

    @Test
    fun `a debug build's ORT options reach every speech model's sessions through the provider`() {
        val provider = "cpu:/data/files/ort/prepack-off.conf"

        val configs = listOf(
            RecognizerConfigs.of(SpeechModelId.PARAKEET_V3, RecognizerUse.LIVE, v3, hotwords = null, language = null, provider = provider),
            RecognizerConfigs.of(SpeechModelId.PARAKEET_V3, RecognizerUse.FILE, v3, hotwords = null, language = null, provider = provider),
            RecognizerConfigs.of(SpeechModelId.PARAKEET_V2_EN, RecognizerUse.FILE, "/models/stt/v2", hotwords = null, language = "en", provider = provider),
            RecognizerConfigs.of(SpeechModelId.COHERE_TRANSCRIBE, RecognizerUse.FILE, cohere, hotwords = null, language = "en", provider = provider),
            RecognizerConfigs.of(SpeechModelId.INDICCONFORMER_ML, RecognizerUse.LIVE, malayalam, hotwords = null, language = null, provider = provider),
            RecognizerConfigs.of(SpeechModelId.INDICCONFORMER_ML, RecognizerUse.FILE, malayalam, hotwords = null, language = "ml", provider = provider)
        )

        configs.forEach { assertEquals(provider, it.modelConfig.provider) }
    }

    @Test
    fun `a file is transcribed on four threads`() {
        val config = RecognizerConfigs.of(SpeechModelId.PARAKEET_V3, RecognizerUse.FILE, v3, hotwords = null, language = null)

        assertEquals(4, config.modelConfig.numThreads)
    }

    @Test
    fun `words to recognize switch Parakeet to beam search with the vocabulary and the words`() {
        val hotwords = HotwordFiles(vocabulary = "$v3/bpe.vocab", words = "/files/speech/words.txt")

        val config = RecognizerConfigs.of(SpeechModelId.PARAKEET_V3, RecognizerUse.LIVE, v3, hotwords, language = null)

        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals(RecognizerConfigs.MAX_ACTIVE_PATHS, config.maxActivePaths)
        assertEquals("/files/speech/words.txt", config.hotwordsFile)
        assertEquals(1.5f, config.hotwordsScore)
        assertEquals("bpe", config.modelConfig.modelingUnit)
        assertEquals("$v3/bpe.vocab", config.modelConfig.bpeVocab)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
    }

    @Test
    fun `only Parakeet v3 listens for the words, whose pieces the vocabulary has`() {
        val hotwords = HotwordFiles(vocabulary = "$v3/bpe.vocab", words = "/files/speech/words.txt")
        val v2 = RecognizerConfigs.of(SpeechModelId.PARAKEET_V2_EN, RecognizerUse.FILE, "/models/stt/v2", hotwords, language = "en")
        val cohereConfig = RecognizerConfigs.of(SpeechModelId.COHERE_TRANSCRIBE, RecognizerUse.FILE, cohere, hotwords, language = "en")

        val malayalamConfig = RecognizerConfigs.of(SpeechModelId.INDICCONFORMER_ML, RecognizerUse.LIVE, malayalam, hotwords, language = null)

        assertTrue(RecognizerConfigs.listensForWords(SpeechModelId.PARAKEET_V3))
        assertFalse(RecognizerConfigs.listensForWords(SpeechModelId.PARAKEET_V2_EN))
        assertFalse(RecognizerConfigs.listensForWords(SpeechModelId.COHERE_TRANSCRIBE))
        assertFalse(RecognizerConfigs.listensForWords(SpeechModelId.INDICCONFORMER_ML))
        for (config in listOf(v2, cohereConfig, malayalamConfig)) {
            assertEquals("", config.hotwordsFile)
            assertEquals("", config.modelConfig.bpeVocab)
            assertEquals("", config.modelConfig.modelingUnit)
            assertEquals("greedy_search", config.decodingMethod)
        }
    }

    @Test
    fun `Parakeet v2 loads from its own folder like v3`() {
        val dir = "/models/stt/parakeet-tdt-0.6b-v2-int8"

        val config = RecognizerConfigs.of(SpeechModelId.PARAKEET_V2_EN, RecognizerUse.FILE, dir, hotwords = null, language = "en")

        assertEquals("$dir/encoder.int8.onnx", config.modelConfig.transducer.encoder)
        assertEquals("$dir/joiner.int8.onnx", config.modelConfig.transducer.joiner)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        assertEquals("greedy_search", config.decodingMethod)
    }

    @Test
    fun `the Malayalam model loads as a NeMo CTC model from its folder, its files the registry's`() {
        val config = RecognizerConfigs.of(SpeechModelId.INDICCONFORMER_ML, RecognizerUse.FILE, malayalam, hotwords = null, language = "ml")

        assertEquals(RecognizerConfigs.nemoCtc(malayalam, RecognizerUse.FILE, SpeechSessionOptions.CPU), config)
        val names = SpeechModels.INDICCONFORMER_ML.files.map { it.spec.name }.toSet()
        assertEquals(names, setOf(config.modelConfig.nemo.model, config.modelConfig.tokens).map { it.substringAfterLast('/') }.toSet())
        // Live it decodes on the recording's two threads, as Parakeet v3 does.
        assertEquals(2, RecognizerConfigs.of(SpeechModelId.INDICCONFORMER_ML, RecognizerUse.LIVE, malayalam, null, null).modelConfig.numThreads)
    }

    @Test
    fun `a NeMo CTC model loads by its model field, greedily, on 80 mel features`() {
        val dir = "/models/stt/indicconformer-ml-int8"

        val config = RecognizerConfigs.nemoCtc(dir, RecognizerUse.FILE, SpeechSessionOptions.CPU)

        val model = config.modelConfig
        assertEquals("$dir/model.int8.onnx", model.nemo.model)
        assertEquals("$dir/tokens.txt", model.tokens)
        assertEquals(4, model.numThreads)
        assertEquals("cpu", model.provider)
        // sherpa-onnx picks the CTC implementation by the model field: no type, no other model.
        assertEquals("", model.modelType)
        assertEquals("", model.transducer.encoder)
        assertEquals("", model.cohereTranscribe.encoder)
        assertEquals("", model.omnilingual.model)
        // Greedy search, the only one a CTC model has; no words to recognize.
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("", config.hotwordsFile)
        assertEquals("", model.modelingUnit)
        assertEquals("", model.bpeVocab)
        // The model's 80 mel features at 16 kHz, from the config, as sherpa-onnx reads them.
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(0f, config.featConfig.dither)
    }

    @Test
    fun `a NeMo CTC model records on two threads, with a debug build's ORT options`() {
        val config = RecognizerConfigs.nemoCtc("/models/stt/ml", RecognizerUse.LIVE, "cpu:/data/files/ort/prepack-off.conf")

        assertEquals(2, config.modelConfig.numThreads)
        assertEquals("cpu:/data/files/ort/prepack-off.conf", config.modelConfig.provider)
    }

    @Test
    fun `Cohere Transcribe has its own decoder, told the language, with punctuation and numbers as digits`() {
        val config = RecognizerConfigs.of(SpeechModelId.COHERE_TRANSCRIBE, RecognizerUse.FILE, cohere, hotwords = null, language = "de")

        val model = config.modelConfig
        assertEquals("$cohere/encoder.int8.onnx", model.cohereTranscribe.encoder)
        assertEquals("$cohere/decoder.int8.onnx", model.cohereTranscribe.decoder)
        assertEquals("de", model.cohereTranscribe.language)
        assertTrue(model.cohereTranscribe.usePunct)
        assertTrue(model.cohereTranscribe.useItn)
        assertEquals("$cohere/tokens.txt", model.tokens)
        assertEquals(4, model.numThreads)
        // Only greedy search exists for it; the transducer fields stay empty, so sherpa-onnx
        // picks the Cohere implementation by its encoder.
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals("", model.transducer.encoder)
        assertEquals("", model.modelType)
        assertEquals("cpu", model.provider)
    }
}
