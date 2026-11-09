package com.satori.feature.stt.datasource

import android.util.Log
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.data.speech.SpeechVocabularyStore
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelDamagedException
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileState
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a speech recognizer is for, which decides how many threads it decodes with. */
enum class RecognizerUse(val threads: Int) {
    /**
     * A recording. Two threads, to leave the AI model its cores: on an 8-core phone 4 for speech
     * and 4 for the AI saturated every core, and decodes took up to 9 s while it wrote a note.
     */
    LIVE(2),

    /**
     * Transcribe again, on a recording's kept audio, while no recorder runs. Four threads: a
     * large model only runs with the AI model out of memory, and the sherpa-onnx docs measured
     * Parakeet v2 on an RK3588 at 0.62 times the time with four threads as with two.
     */
    FILE(4)
}

/**
 * The load of a recognizer for [use]: the recorder's own for a recording ([RecognizerUse.LIVE]),
 * which never waits for another and stops before its model loads once [stop] says why; any other
 * for a file ([RecognizerUse.FILE]: Transcribe again, the better transcript after Stop, a Malayalam
 * recording transcribed again), which waits its turn behind every other model load and stops while
 * it waits once [cancelled]. Either makes room for the parse of [parseBytes] first.
 */
internal fun <T> SpeechModelLoadPacing.loadFor(
    use: RecognizerUse,
    name: String,
    files: List<File>,
    parseBytes: Long,
    cancelled: () -> Boolean,
    stop: () -> LoadStop?,
    load: () -> T
): T = when (use) {
    RecognizerUse.LIVE -> recorderLoad(name, files, parseBytes, stop, load)
    RecognizerUse.FILE -> otherLoad(name, files, parseBytes, cancelled, load)
}

/**
 * The files beside Parakeet v3 that words to recognize need: the BPE vocabulary they are split
 * into the model's pieces with, and the words themselves, one per line.
 */
internal class HotwordFiles(val vocabulary: String, val words: String)

/**
 * Builds the sherpa-onnx recognizer of a speech model from its files on the phone
 * ([com.satori.data.config.SpeechModels]): Parakeet v3 and v2 as NeMo transducers,
 * Cohere Transcribe with its own decoder, told the language, and AI4Bharat's Malayalam model as a
 * NeMo CTC model. Every load of a speech model passes here, and sherpa-onnx aborts the whole
 * process on a file that is truncated or not a model, and on a metadata key or a line of tokens it
 * can't read. Nothing in Kotlin can catch that, so a file that fails its check
 * ([ModelDownloadManager.checkSttModel]: the size and first bytes, and where the registry names
 * them, the metadata and the tokens) never reaches the loader.
 */
@Singleton
class SpeechRecognizerFactory @Inject constructor(
    private val downloads: ModelDownloadManager,
    private val vocabulary: SpeechVocabularyStore,
    private val sessionOptions: SpeechSessionOptions,
    private val pacing: ModelLoadPacing
) {
    /**
     * A new recognizer of [model] for [use]; the caller releases it. Reads the disk and loads the
     * model: call it off the main thread.
     *
     * Parakeet v3 listens for the words to recognize when there are some and their vocabulary is
     * on the phone: it then decodes with beam search, which hotwords need. Every load has ONNX
     * Runtime's prepacking off ([SpeechSessionOptions], which a debug build reads as the load
     * begins). Every build loads one model at a time ([ModelLoadPacing]), its files read in first at
     * 120 MB/s (a debug build's `debug.satori.load.pace` over it), then room made at that pace for
     * ONNX Runtime's parse of the files that hold their weights ([parseBytesOf]): the live model is
     * the recorder's own load, which never waits for another (the other loads give way to it) and
     * never stops for memory, but goes ahead unpaced after 10 s of waiting; a model for a file
     * waits its turn, and stops before it loads when the phone stays short of memory.
     *
     * @param language The language spoken, for a model that is told it ([SpeechModelId.languagePerStream])
     * @param cancelled Whether the caller has gone away: a load for a file that waits its turn or
     *   is read in then stops before the model loads
     * @param stop Why the recording no longer wants the live model, if it doesn't ([LoadStop]: its
     *   language was switched, Stop came while its audio is kept, the capture ended): the live
     *   model's load then stops before the model loads ([com.satori.data.monitor.LoadSupersededException])
     * @throws IllegalStateException if the model's files are not on the phone
     * @throws ModelDamagedException if they are, but damaged or incomplete
     * @throws IllegalArgumentException if the model is told the language and none is given
     * @throws java.util.concurrent.CancellationException once [cancelled] before the model loads
     * @throws com.satori.data.monitor.PacedLoadStoppedException for a model for a file whose
     *   paced pre-read waited for memory as long as it may; nothing is loaded then
     * @throws com.satori.data.monitor.LoadSupersededException for the live model once [stop] says
     *   why before it loads; nothing is loaded then
     * @throws PrepackingStaysOnException for a large model whose ONNX Runtime options can't be
     *   written, which would load with prepacking on
     */
    fun create(
        model: SpeechModelId,
        use: RecognizerUse,
        language: String? = null,
        cancelled: () -> Boolean = { false },
        stop: () -> LoadStop? = { null }
    ): OfflineRecognizer {
        require(!model.languagePerStream || language != null) { "${model.id} has to be told the language" }
        val dir = downloads.getSttModelPath(model)
            ?: throw IllegalStateException("Speech model ${model.id} not downloaded. Please download the model first.")
        if (downloads.checkSttModel(model) != ModelFileState.Intact) {
            throw ModelDamagedException("The speech model files are damaged or incomplete")
        }
        val hotwords = if (RecognizerConfigs.listensForWords(model)) hotwordFiles() else null
        // A large model (Cohere) never loads with prepacking on: its packed copies would take about 2 GB.
        val provider = sessionOptions.providerForLoad(large = !model.runsLive)
        val config = RecognizerConfigs.of(model, use, dir, hotwords, language, provider)
        // The model and how it decodes, never the words: they are personal.
        Log.i(TAG, "Speech model ${model.id}: ${config.modelConfig.numThreads} threads, ${config.decodingMethod}" +
            (if (hotwords != null) ", words to recognize on" else "") +
            if (provider != SpeechSessionOptions.CPU) ", ORT prepack off ($provider)" else ", ORT prepack on")
        val specs = SpeechModels.of(model).specs
        val files = specs.map { File(dir, it.name) }
        val parseBytes = parseBytesOf(specs.map { File(dir, it.name) to it.format })
        return pacing.loadFor(use, model.id, files, parseBytes, cancelled, stop) { OfflineRecognizer(assetManager = null, config = config) }
    }

    private fun hotwordFiles(): HotwordFiles? {
        val pieces = downloads.speechVocabularyFile() ?: return null
        val words = vocabulary.hotwordsFile() ?: return null
        return HotwordFiles(pieces.absolutePath, words.absolutePath)
    }

    internal companion object {
        private const val TAG = "SpeechRecognizers"

        /**
         * The bytes of [files] that ONNX Runtime parses onto the heap as the model loads: those of
         * its ONNX files, which hold their weights (Parakeet v3's 652 MB encoder, the Malayalam
         * model's 138 MB); not an external data file it maps (Cohere's 2.7 GB
         * `encoder.int8.onnx.data`), nor the tokens. The room made before the load is for these
         * ([com.satori.data.monitor.ParseRoom]).
         */
        fun parseBytesOf(files: List<Pair<File, ModelFileFormat>>): Long =
            files.filter { (_, format) -> format == ModelFileFormat.Onnx }.sumOf { (file, _) -> file.length() }
    }
}

/**
 * The sherpa-onnx recognizer configs of the speech models. Pure, so they are tested without the
 * native library, which only the recognizer itself loads.
 */
internal object RecognizerConfigs {

    /**
     * Beam search keeps this many hypotheses, sherpa-onnx's default; greedy search, which keeps
     * one, ignores hotwords.
     */
    const val MAX_ACTIVE_PATHS = 4

    /** How much a hotword is favoured per piece, sherpa-onnx's default. */
    const val HOTWORDS_SCORE = 1.5f

    /** sherpa-onnx's only decoding for a CTC model, and the default of the others. */
    const val GREEDY_SEARCH = "greedy_search"

    /**
     * The mel features a NeMo CTC model is given: AI4Bharat's IndicConformer takes 80 (its config:
     * 80 mel, normalized per feature). sherpa-onnx takes this count from the config, not from the
     * model file, for a NeMo CTC model.
     */
    const val NEMO_CTC_FEATURES = 80

    /**
     * Whether [model] listens for the words to recognize: Parakeet v3 only, whose pieces the
     * vocabulary has. Parakeet v2's tokenizer is another, so the words would be split into pieces
     * it doesn't know, and sherpa-onnx writes each such piece to the log, with its word; Cohere
     * Transcribe has no hotwords at all, and the Malayalam model decodes greedily, which ignores them.
     */
    fun listensForWords(model: SpeechModelId): Boolean = model == SpeechModelId.PARAKEET_V3

    /**
     * The config of [model] whose files are in [dir].
     *
     * @param hotwords The words to recognize, which switch Parakeet v3 to beam search; ignored
     *   for the other models ([listensForWords])
     * @param language Cohere only: the language spoken, which it has to be told
     * @param provider sherpa-onnx's provider for every session of the recognizer: "cpu:<file>" with
     *   ONNX Runtime's options, or "cpu" for its defaults ([SpeechSessionOptions])
     */
    fun of(
        model: SpeechModelId,
        use: RecognizerUse,
        dir: String,
        hotwords: HotwordFiles?,
        language: String?,
        provider: String = SpeechSessionOptions.CPU
    ): OfflineRecognizerConfig = when (model) {
        SpeechModelId.PARAKEET_V3, SpeechModelId.PARAKEET_V2_EN ->
            parakeet(dir, use, hotwords?.takeIf { listensForWords(model) }, provider)
        SpeechModelId.COHERE_TRANSCRIBE -> cohere(dir, use, language, provider)
        SpeechModelId.INDICCONFORMER_ML -> nemoCtc(dir, use, provider)
    }

    private fun parakeet(dir: String, use: RecognizerUse, hotwords: HotwordFiles?, provider: String): OfflineRecognizerConfig {
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$dir/encoder.int8.onnx",
                decoder = "$dir/decoder.int8.onnx",
                joiner = "$dir/joiner.int8.onnx"
            ),
            tokens = "$dir/tokens.txt",
            modelType = "nemo_transducer",
            numThreads = use.threads,
            provider = provider,
            // The words are split into the model's BPE pieces with the vocabulary.
            modelingUnit = if (hotwords != null) "bpe" else "",
            bpeVocab = hotwords?.vocabulary.orEmpty()
        )
        if (hotwords == null) return OfflineRecognizerConfig(modelConfig = modelConfig)
        return OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "modified_beam_search",
            maxActivePaths = MAX_ACTIVE_PATHS,
            hotwordsFile = hotwords.words,
            hotwordsScore = HOTWORDS_SCORE
        )
    }

    /**
     * A NeMo CTC model whose files are in [dir]: one ONNX file, `model.int8.onnx`, that holds the
     * encoder and the CTC head (AI4Bharat's IndicConformer Malayalam, exported for sherpa-onnx), and
     * its `tokens.txt`. sherpa-onnx picks its CTC implementation by the model's own field
     * (offline-recognizer-impl.cc, v1.13.8), so the model type stays empty, and reads the
     * subsampling factor, the vocabulary size and the feature normalization from the file's
     * metadata, which the registry has checked before the load
     * (com.satori.data.speech.SpeechModelLoadCheck). It decodes with greedy search, which
     * ignores hotwords, so the words to recognize don't apply; any other decoding would end the
     * process. The features are [NEMO_CTC_FEATURES] mel at 16 kHz.
     */
    internal fun nemoCtc(dir: String, use: RecognizerUse, provider: String): OfflineRecognizerConfig =
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechRecognition.SAMPLE_RATE_HZ, featureDim = NEMO_CTC_FEATURES),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "$dir/model.int8.onnx"),
                tokens = "$dir/tokens.txt",
                numThreads = use.threads,
                provider = provider
            ),
            decodingMethod = GREEDY_SEARCH
        )

    private fun cohere(dir: String, use: RecognizerUse, language: String?, provider: String): OfflineRecognizerConfig =
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                cohereTranscribe = OfflineCohereTranscribeModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    // Each stream is told it too (decodeWith); this is the fallback sherpa-onnx reads.
                    language = language.orEmpty(),
                    usePunct = true,
                    useItn = true
                ),
                tokens = "$dir/tokens.txt",
                numThreads = use.threads,
                provider = provider
            )
        )
}
