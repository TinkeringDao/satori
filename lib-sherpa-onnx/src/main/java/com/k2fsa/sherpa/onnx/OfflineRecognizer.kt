package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,

    // valid only for TDT models
    val durations: FloatArray,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineParaformerModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineDolphinModelConfig(
    var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineWenetCtcModelConfig(
    var model: String = "",
)

data class OfflineOmnilingualAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineMedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineFireRedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineFunAsrNanoModelConfig(
    var encoderAdaptor: String = "",
    var llm: String = "",
    var embedding: String = "",
    var tokenizer: String = "",
    var systemPrompt: String = "You are a helpful assistant.",
    var userPrompt: String = "语音转写：",
    var maxNewTokens: Int = 512,
    var temperature: Float = 1e-6f,
    var topP: Float = 0.8f,
    var seed: Int = 42,
    var language: String = "",
    var itn: Boolean = true,
    var hotwords: String = "",
)

data class OfflineQwen3AsrModelConfig(
    var convFrontend: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var tokenizer: String = "",
    var maxTotalLen: Int = 512,
    var maxNewTokens: Int = 128,
    var temperature: Float = 1e-6f,
    var topP: Float = 0.8f,
    var seed: Int = 42,
    var hotwords: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en", // Used with multilingual model
    var task: String = "transcribe", // transcribe or translate
    var tailPaddings: Int = 1000, // Padding added at the end of the samples
    var enableTokenTimestamps: Boolean = false,
    var enableSegmentTimestamps: Boolean = false,
)

data class OfflineCanaryModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var srcLang: String = "en",
    var tgtLang: String = "en",
    var usePnc: Boolean = true,
)

data class OfflineCohereTranscribeModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",
    var usePunct: Boolean = true,
    var useItn: Boolean = true,
)

data class OfflineFireRedAsrModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

// For moonshine v1, you need four models.
// For moonshine v2, you need two models.
// - v1: preprocessor, encoder, uncachedDecoder, cachedDecoder
// - v2: encoder, mergedDecoder
data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
    var mergedDecoder: String = "",
)

data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = true,
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var omnilingual: OfflineOmnilingualAsrCtcModelConfig = OfflineOmnilingualAsrCtcModelConfig(),
    var medasr: OfflineMedAsrCtcModelConfig = OfflineMedAsrCtcModelConfig(),
    var funasrNano: OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(),
    var qwen3Asr: OfflineQwen3AsrModelConfig = OfflineQwen3AsrModelConfig(),
    var fireRedAsrCtc: OfflineFireRedAsrCtcModelConfig = OfflineFireRedAsrCtcModelConfig(),
    var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    var cohereTranscribe: OfflineCohereTranscribeModelConfig =
        OfflineCohereTranscribeModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    // var lmConfig: OfflineLMConfig(), // TODO(fangjun): enable it
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid OfflineRecognizerConfig: failed to create native OfflineRecognizer"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    fun createStream(hotwords: String): OfflineStream {
        val p = createStreamWithHotwords(ptr, hotwords)
        return OfflineStream(p)
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        return getResult(stream.ptr)
    }

    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun createStreamWithHotwords(ptr: Long, hotwords: String): Long

    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(streamPtr: Long): OfflineRecognizerResult

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }

        @JvmStatic
        external fun prependAdspLibraryPath(newPath: String) // for qnn
    }
}

/*
Satori keeps upstream's entries for the models it runs or tries, under upstream's type numbers:

30 - sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 (English)
40 - sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 (25 European languages, told apart by the model)
61 - sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25

Upstream has no entry for Cohere Transcribe; its model config is OfflineCohereTranscribeModelConfig.
The config classes above stay complete, also for the models Satori doesn't use:
libsherpa-onnx-jni.so reads every one of their fields by name, and a field left out fails in
native code when a recognizer is created.

For the other pre-trained models see
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
 */
fun getOfflineModelConfig(type: Int): OfflineModelConfig? {
    when (type) {
        30 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        40 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        61 -> {
            val modelDir = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
            return OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = "$modelDir/conv_frontend.onnx",
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    tokenizer = "$modelDir/tokenizer",
                ),
                tokens = "",
                numThreads=3,
            )
        }
    }
    return null
}
