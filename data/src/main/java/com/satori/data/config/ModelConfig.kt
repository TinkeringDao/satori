package com.satori.data.config

import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec

/**
 * Encapsulates the download configuration of one AI model variant: its URL, filename, and what
 * the complete file looks like. The speech models have their own registry, [SpeechModels].
 *
 * Separating configuration from [com.satori.data.datasource.ModelDownloadManager]
 * allows model references to be changed (e.g. for different build variants or A/B tests)
 * without touching download infrastructure.
 *
 * @property llmSpec What the complete LLM file looks like, checked before it is loaded
 */
data class ModelConfig(
    val llmUrl: String,
    val llmFilename: String,
    val llmSpec: ModelFileSpec
)

/**
 * A model file the app downloads from an address that names a commit and checks against its
 * SHA-256 before it takes its name ([com.satori.data.datasource.ModelDownloadManager]): a speech
 * model's ([SpeechModelFile]) or the reader's ([ReaderModelFile]).
 */
interface PinnedModelFile {
    /** Name, format and exact size, checked before a native loader opens the file. */
    val spec: ModelFileSpec

    /** Where it is downloaded from, at a commit, not a branch. */
    val url: String

    /** The SHA-256 of the complete file, lower-case hex. */
    val sha256: String
}

/**
 * One file of a speech model: what a complete one looks like, and where it comes from.
 *
 * @property spec Name, format and exact size, checked before the speech engine opens the file
 * @property url Where it is downloaded from. The address names a commit, not a branch, so the
 *   file it serves is always the one sized here: an update of the repository cannot make a
 *   download fail the size check.
 * @property sha256 The SHA-256 of the complete file, which a download is checked against: the LFS
 *   object id Hugging Face lists for the files kept in LFS, and for a small text file kept in git
 *   itself the hash of the file whose git object id the listing gives.
 * @property metadata For an ONNX file, what sherpa-onnx reads from its metadata as it loads it,
 *   with the values the file must have, checked before every load
 *   ([com.satori.data.speech.SpeechModelLoadCheck]). sherpa-onnx ends the whole process on
 *   a key it needs and doesn't find, and a wrong value it reads silently (a subsampling factor of 8
 *   on a model that subsamples by 4 decodes only the first half of every utterance). Empty where
 *   nothing is checked: the models that have loaded on the phone since before the check.
 * @property tokens For a tokens.txt, how many tokens it lists, with ids from 0, one of them the blank
 *   of greedy CTC search, checked before every load like [metadata]: sherpa-onnx ends the process
 *   on a line it can't parse, or on an id the model writes that the list lacks. Null where nothing
 *   is checked.
 */
data class SpeechModelFile(
    override val spec: ModelFileSpec,
    override val url: String,
    override val sha256: String,
    val metadata: Map<String, String> = emptyMap(),
    val tokens: Int? = null
) : PinnedModelFile

/**
 * A speech model ([SpeechModelId]) as Satori downloads, checks and loads it.
 *
 * @property folder Its folder in the speech model directory (`models/stt`). Empty for Parakeet v3,
 *   whose files have been there from the start, so a copy already on the phone stays in place.
 * @property files Every file it needs, in the order they are downloaded
 * @property vocabulary An optional file beside them: the BPE vocabulary that words to recognize
 *   (hotwords) are split with. The model loads without it; it is downloaded only for those words.
 */
data class SpeechModelSpec(
    val id: SpeechModelId,
    val folder: String,
    val files: List<SpeechModelFile>,
    val vocabulary: SpeechModelFile? = null
) {
    /** What each of its files looks like complete, for [com.satori.data.datasource.ModelFileInspector]. */
    val specs: List<ModelFileSpec> get() = files.map { it.spec }

    /** The bytes of its files together, also about what it takes in memory. */
    val bytes: Long get() = files.sumOf { it.spec.bytes ?: it.spec.minBytes }
}

/**
 * The speech models Satori can transcribe with, with every file's exact size and SHA-256, read
 * off the Hugging Face listings of the pinned commits (`api/models/<repo>/tree/<commit>`) on
 * 2026-10-12. To use another revision, change the commit and these numbers together.
 *
 * The files come from the per-file mirrors of the sherpa-onnx conversions, so the phone downloads
 * them as they are loaded, with nothing to unpack.
 */
object SpeechModels {

    private const val HF = "https://huggingface.co"

    private fun file(
        repo: String,
        commit: String,
        name: String,
        format: ModelFileFormat,
        bytes: Long,
        sha256: String,
        metadata: Map<String, String> = emptyMap(),
        tokens: Int? = null
    ) = SpeechModelFile(ModelFileSpec(name, format, bytes = bytes), "$HF/$repo/resolve/$commit/$name", sha256, metadata, tokens)

    private const val V3_REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val V3_COMMIT = "2bda32ec70b097a55adaa07d9a7173915b43cc78"

    // A third party's bpe.vocab, generated from NVIDIA's own tokenizer.model of v3 (commit 8c59831,
    // the repository's card says). Checked against v3's tokens.txt on 2026-10-12: all 8,192 of
    // its pieces are tokens of v3, each on the line of its token id; only the blank <blk> is not
    // in it, as it is no piece of the tokenizer. The repository also holds a copy of v3's files,
    // the same as the ones above (same LFS ids); only the vocabulary is taken from it.
    private const val VOCABULARY_REPO = "MadnessOverflow/parakeet-tdt-0.6b-v3-bpe-vocab"
    private const val VOCABULARY_COMMIT = "0890cc43446a37ac7066237543074a7927b58464"

    /** NVIDIA Parakeet TDT 0.6B v3, int8: live, and any of its 25 languages. */
    val PARAKEET_V3 = SpeechModelSpec(
        id = SpeechModelId.PARAKEET_V3,
        folder = "",
        files = listOf(
            file(V3_REPO, V3_COMMIT, "encoder.int8.onnx", ModelFileFormat.Onnx, 652_184_281L,
                "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            file(V3_REPO, V3_COMMIT, "decoder.int8.onnx", ModelFileFormat.Onnx, 11_845_275L,
                "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            file(V3_REPO, V3_COMMIT, "joiner.int8.onnx", ModelFileFormat.Onnx, 6_355_277L,
                "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            file(V3_REPO, V3_COMMIT, "tokens.txt", ModelFileFormat.Text, 93_939L,
                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
        ),
        vocabulary = file(VOCABULARY_REPO, VOCABULARY_COMMIT, "bpe.vocab", ModelFileFormat.Text, 117_408L,
            "41d5e71b3591642eff088151efd7acd4e750124cc0054c8ba9fa3245187a4804")
    )

    private const val V2_REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    private const val V2_COMMIT = "1ab9323565ddb038682214b292f588070a538ce2"

    /** NVIDIA Parakeet TDT 0.6B v2, int8: English only, the same runtime and speed as v3. */
    val PARAKEET_V2_EN = SpeechModelSpec(
        id = SpeechModelId.PARAKEET_V2_EN,
        folder = SpeechModelId.PARAKEET_V2_EN.id,
        files = listOf(
            file(V2_REPO, V2_COMMIT, "encoder.int8.onnx", ModelFileFormat.Onnx, 652_184_296L,
                "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            file(V2_REPO, V2_COMMIT, "decoder.int8.onnx", ModelFileFormat.Onnx, 7_257_753L,
                "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            file(V2_REPO, V2_COMMIT, "joiner.int8.onnx", ModelFileFormat.Onnx, 1_739_080L,
                "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            file(V2_REPO, V2_COMMIT, "tokens.txt", ModelFileFormat.Text, 9_384L,
                "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d")
        )
    )

    private const val COHERE_REPO = "csukuangfj2/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01"
    private const val COHERE_COMMIT = "156a470cf08eefe706a0004f3c52d9ee567ca7a0"

    /**
     * Cohere Transcribe 03-2026, int8: 14 languages, told the language of each stream. Its
     * encoder keeps its weights in `encoder.int8.onnx.data`, which ONNX Runtime reads from beside
     * `encoder.int8.onnx`, so the two share the folder.
     */
    val COHERE_TRANSCRIBE = SpeechModelSpec(
        id = SpeechModelId.COHERE_TRANSCRIBE,
        folder = SpeechModelId.COHERE_TRANSCRIBE.id,
        files = listOf(
            file(COHERE_REPO, COHERE_COMMIT, "encoder.int8.onnx", ModelFileFormat.Onnx, 3_090_822L,
                "cf704f8cfa90e3f0a76f9ffc05998bdf00ba9ae983192c14a85a3a5eb008b367"),
            file(COHERE_REPO, COHERE_COMMIT, "encoder.int8.onnx.data", ModelFileFormat.Data, 2_731_503_072L,
                "bcf1b7148c8518ae52df1ad2d2fc2b4e89261ea23e6c874eef1d9f55bcbaa4a3"),
            file(COHERE_REPO, COHERE_COMMIT, "decoder.int8.onnx", ModelFileFormat.Onnx, 153_250_705L,
                "8372ca6c8ff4db8b916ca3592f5c757a715e691b9edec751ba19b29fc854baf9"),
            file(COHERE_REPO, COHERE_COMMIT, "tokens.txt", ModelFileFormat.Text, 207_437L,
                "013ede043ae2480e3a9205cc34550d9686100cc682bacc90f702facdfbb93035")
        )
    )

    private const val ML_REPO = "csndhanasekar/sherpa-onnx-indicconformer-ml-int8"
    private const val ML_COMMIT = "394e2c20b57eb0db12839ecff00f904a55ea9f47"

    /** What the Malayalam model's tokens.txt lists, and its file's metadata says: 256 pieces and the blank. */
    private const val ML_TOKENS = 257

    /**
     * AI4Bharat IndicConformer Malayalam 120M, the CTC head in int8 (MIT; the community export
     * read on 2026-10-14, commit 394e2c2): one ONNX file and its tokens, 137.6 MB, loaded as a
     * NeMo CTC model. The size and SHA-256 are the listing's; the tokens' hash was computed from
     * the file. The metadata is what sherpa-onnx's own tool logged on the phone that day: a CTC
     * model of NeMo, 257 tokens (Malayalam's 256 pieces of AI4Bharat's shared vocabulary and the
     * blank, all Malayalam script), subsampling by 4, features normalized per feature. A file
     * stamped with the export scripts' factor of 8 would lose the second half of every utterance
     * without a word; this one transcribed whole utterances on the phone.
     */
    val INDICCONFORMER_ML = SpeechModelSpec(
        id = SpeechModelId.INDICCONFORMER_ML,
        folder = SpeechModelId.INDICCONFORMER_ML.id,
        files = listOf(
            file(ML_REPO, ML_COMMIT, "model.int8.onnx", ModelFileFormat.Onnx, 137_563_723L,
                "357382eb9ad4ef45c9269042af953e627ec9ca66a396cad1a2f4ed8562c012a1",
                metadata = mapOf(
                    "model_type" to "EncDecCTCModelBPE",
                    "vocab_size" to ML_TOKENS.toString(),
                    "subsampling_factor" to "4",
                    "normalize_type" to "per_feature"
                )),
            file(ML_REPO, ML_COMMIT, "tokens.txt", ModelFileFormat.Text, 3_080L,
                "60eb97740574a55504786d4ac0699b14280977ec346a7d9a9bbef4deeb1e559b",
                tokens = ML_TOKENS)
        )
    )

    /**
     * The registry entry of [id]; with a debug build's test server, that server's addresses and
     * hashes ([ModelSource]).
     */
    fun of(id: SpeechModelId): SpeechModelSpec = ModelSource.speech(
        when (id) {
            SpeechModelId.PARAKEET_V3 -> PARAKEET_V3
            SpeechModelId.PARAKEET_V2_EN -> PARAKEET_V2_EN
            SpeechModelId.COHERE_TRANSCRIBE -> COHERE_TRANSCRIBE
            SpeechModelId.INDICCONFORMER_ML -> INDICCONFORMER_ML
        }
    )
}

/**
 * One file of the reader, the model that reads a Malayalam line into English: what a complete
 * one looks like and where it comes from, as [SpeechModelFile] has it for a speech model.
 *
 * @property spec Name, format and exact size
 * @property url Where it is downloaded from, at a commit, not a branch
 * @property sha256 The SHA-256 of the complete file: the LFS object id Hugging Face lists for a file
 *   kept in LFS, and for a text file kept in git itself the hash of the file whose git object id
 *   the listing gives
 */
data class ReaderModelFile(
    override val spec: ModelFileSpec,
    override val url: String,
    override val sha256: String
) : PinnedModelFile

/**
 * One build of the reader, pinned to a commit of its repository.
 *
 * @property repo The Hugging Face repository
 * @property commit The commit every file comes from
 * @property files Every file the reader needs: the three ONNX graphs with the weights they keep in
 *   files of their own, the two SentencePiece models, the two vocabularies that turn pieces into
 *   the model's ids and back, and the two configurations
 */
data class ReaderModelSpec(
    val repo: String,
    val commit: String,
    val files: List<ReaderModelFile>
) {
    /** The bytes of its files together. */
    val bytes: Long get() = files.sumOf { it.spec.bytes ?: it.spec.minBytes }

    /** What each of its files looks like complete, for [com.satori.data.datasource.ModelFileInspector]. */
    val specs: List<ModelFileSpec> get() = files.map { it.spec }

    /** The file named [name]; the registry lists each name once. */
    fun file(name: String): ReaderModelFile = files.first { it.spec.name == name }
}

/**
 * The reader: AI4Bharat IndicTrans2, distilled to 200M parameters, Indic to English (MIT), as one
 * uploader's ONNX export (MIT, its card). Satori uses its Malayalam to English direction, on the
 * ONNX Runtime it already ships for the speech models.
 *
 * Where it comes from, owner decision O2's default (2026-10-19): AI4Bharat's own repository is
 * gated and there is no other ungated export, so the reader is pinned to the uploader's commit
 * with every file's size and SHA-256, read off the Hugging Face listing
 * (`api/models/<repo>/revision/<commit>`) on 2026-10-19. Before any wider release it is copied to
 * a repository the owner controls, and only the repository changes here. To use another revision,
 * change the commit and these numbers together.
 *
 * Each build is a reader model of its own ([ReaderModelId]) with its own folder,
 * `models/reader/<id>`: the two builds' graphs have the same names. ModelDownloadManager downloads
 * and checks them ([of]); the first-run part that plans the download comes with 0.8.0's stage B2.
 */
object ReaderModels {

    private const val HF = "https://huggingface.co"

    private fun file(repo: String, commit: String, name: String, format: ModelFileFormat, bytes: Long, sha256: String) =
        ReaderModelFile(ModelFileSpec(name, format, bytes = bytes), "$HF/$repo/resolve/$commit/$name", sha256)

    // The files both builds share, byte for byte: the same git objects at both commits.
    private fun shared(repo: String, commit: String) = listOf(
        file(repo, commit, "model.SRC", ModelFileFormat.Data, 3_256_903L,
            "ac9257c8e76b8b607705b959cc3d075656ea33032f7a974e467b8941df6e98d4"),
        file(repo, commit, "model.TGT", ModelFileFormat.Data, 759_425L,
            "3cedc5cbcc740369b76201942a0f096fec7287fee039b55bdb956f301235b914"),
        file(repo, commit, "dict.SRC.json", ModelFileFormat.Text, 3_391_208L,
            "3d3b180702520742dec423a5a7f7b4e3dad399d69e40ca7df294a1567884bed5"),
        file(repo, commit, "dict.TGT.json", ModelFileFormat.Text, 644_755L,
            "13c3a162fe655dbe99c790a413675c5d0634cd771fadcefe8d407676a7d1a311"),
        file(repo, commit, "config.json", ModelFileFormat.Text, 1_370L,
            "7268e8cc0fb12d2791d5380595eb44039d03a2066c995f99a1bcc9da8d1100a6"),
        file(repo, commit, "generation_config.json", ModelFileFormat.Text, 163L,
            "6c8b29ae72e680d5a2d9e849567f6f556d3f4e9bbd42434964a646f49b556972")
    )

    private const val INT8_REPO = "hari31416/indictrans2-indic-en-dist-200M-ONNX-int8"
    private const val INT8_COMMIT = "a7ecdc0ba73148c6e1d051ca372332471d8aff4f"

    /**
     * The int8 build, the one Satori downloads: weights quantized dynamically, per channel, 242 MB
     * in all. On the export's 50 Malayalam fixtures it wrote the same English as fp32 on 43.
     */
    val INT8 = ReaderModelSpec(
        repo = INT8_REPO,
        commit = INT8_COMMIT,
        files = listOf(
            file(INT8_REPO, INT8_COMMIT, "encoder_model.onnx", ModelFileFormat.Onnx, 831_436L,
                "79dd1c3d9f90f8bbd48effcc2d150616ac3a97525c8081481f14cd5095567ca6"),
            file(INT8_REPO, INT8_COMMIT, "encoder_model.onnx.data", ModelFileFormat.Data, 120_068_096L,
                "6c402df00dfc45a69607e0231e1e5fd884508b19ca664836c94dd61bfa1ba137"),
            file(INT8_REPO, INT8_COMMIT, "decoder_model.onnx", ModelFileFormat.Onnx, 1_585_382L,
                "845cdbaf0ee97d3c8ddff249a2e2d6e475d7ae1781157fcc6d0d651405bb8dd6"),
            file(INT8_REPO, INT8_COMMIT, "decoder_with_past_model.onnx", ModelFileFormat.Onnx, 1_398_395L,
                "bcc818db56bc681fab2d50ca31b99dbcb5f82abbce4dccc92946edf5906fa86e"),
            file(INT8_REPO, INT8_COMMIT, "decoder_shared.onnx.data", ModelFileFormat.Data, 110_052_040L,
                "abe939ce4d60bcbb9744790640188686c8af084083eb50d63bdbb7266a325d9a")
        ) + shared(INT8_REPO, INT8_COMMIT)
    )

    private const val FP16_REPO = "hari31416/indictrans2-indic-en-dist-200M-ONNX-fp16"
    private const val FP16_COMMIT = "b6169410862d56cdace9a5e26298b1243c7c1265"

    /**
     * The fp16 build, the pinned fallback should int8 read Malayalam too poorly (validation V2
     * between 58 and 60.5 chrF++): the same tokenizers, vocabularies and configurations, graphs
     * and weights of 458 MB. Its memory on ONNX Runtime's CPU path is not known yet.
     */
    val FP16 = ReaderModelSpec(
        repo = FP16_REPO,
        commit = FP16_COMMIT,
        files = listOf(
            file(FP16_REPO, FP16_COMMIT, "encoder_model.onnx", ModelFileFormat.Onnx, 237_385L,
                "037b2e65080612483ce75da7009637f7a60ea573ab5d0d6e231a12527d4a9f79"),
            file(FP16_REPO, FP16_COMMIT, "encoder_model.onnx.data", ModelFileFormat.Data, 239_405_056L,
                "ebafa0df9aade5b797f8f070bde96a52cf80a0ca2aaa03f825e4cdd059193b71"),
            file(FP16_REPO, FP16_COMMIT, "decoder_model.onnx", ModelFileFormat.Onnx, 691_272L,
                "ad52ec121142229d84038704b9056eeb3f4f2844c707edfaca4f0fa10a01cf66"),
            file(FP16_REPO, FP16_COMMIT, "decoder_with_past_model.onnx", ModelFileFormat.Onnx, 631_183L,
                "e740d7c10c52982742957c13f88bf55ab6e5fbe9fc82385180c625795b5c0172"),
            file(FP16_REPO, FP16_COMMIT, "decoder_shared.onnx.data", ModelFileFormat.Data, 217_475_072L,
                "16dbbad3329e4143c2906bd1b9fde53eefc5f0d4349cabf124b4be656340e2ba")
        ) + shared(FP16_REPO, FP16_COMMIT)
    )

    /** The graph each session loads from; the external data each keeps its weights in lies beside it. */
    const val ENCODER = "encoder_model.onnx"
    const val DECODER = "decoder_model.onnx"
    const val DECODER_WITH_PAST = "decoder_with_past_model.onnx"

    /** SentencePiece's model of the source side, and the two vocabularies of the model's ids. */
    const val SOURCE_PIECES = "model.SRC"
    const val SOURCE_VOCABULARY = "dict.SRC.json"
    const val TARGET_VOCABULARY = "dict.TGT.json"

    /** The model's configuration: its vocabularies' sizes, its positions and its special ids. */
    const val CONFIG = "config.json"

    /**
     * The registry entry of [id]; with a debug build's test server, that server's addresses and
     * hashes ([ModelSource]).
     */
    fun of(id: ReaderModelId): ReaderModelSpec = ModelSource.reader(
        id,
        when (id) {
            ReaderModelId.INT8 -> INT8
            ReaderModelId.FP16 -> FP16
        }
    )
}

// No LLM variant is smaller than this; it is also what a finished download is measured against
// (ModelDownloadManager.downloadLlmModel). It catches a placeholder, an error page and a download
// that stopped early, which is all that can be said where the exact size is not known. Replace
// it with the exact size once it has been read off a downloaded file.
internal const val MIN_LLM_BYTES = 100_000_000L

/**
 * Q8_0 model configuration — ~1 GB.
 * Recommended for flagship devices (≥ 10 GB RAM, Cortex-A78 / Oryon CPU or newer).
 *
 * LLM  : Gemma 3 1B Q8_0 (ggml-org, HuggingFace)
 */
object DefaultModelConfig {

    private const val LLM_URL =
        "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q8_0.gguf?download=true"
    private const val LLM_FILENAME = "gemma-3-1b-it-Q8_0.gguf"

    val INSTANCE = ModelConfig(
        llmUrl = LLM_URL,
        llmFilename = LLM_FILENAME,
        // The size in docs/DEV_SETUP.md, from the file as downloaded on 2026-10-08.
        llmSpec = ModelFileSpec(LLM_FILENAME, ModelFileFormat.Gguf, bytes = 1_069_306_368L)
    )
}

/**
 * IQ4_NL model configuration — ~650 MB, lower CPU load and battery use.
 * Recommended for mid-range and older devices (Cortex-A77 / Snapdragon 865 era and below).
 *
 * LLM  : Gemma 3 1B IQ4_NL (unsloth, HuggingFace)
 */
object LowEndModelConfig {

    private const val LLM_URL =
        "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-IQ4_NL.gguf?download=true"
    private const val LLM_FILENAME = "gemma-3-1b-it-IQ4_NL.gguf"

    val INSTANCE = ModelConfig(
        llmUrl = LLM_URL,
        llmFilename = LLM_FILENAME,
        llmSpec = ModelFileSpec(LLM_FILENAME, ModelFileFormat.Gguf, minBytes = MIN_LLM_BYTES)
    )
}

/**
 * Qwen 3.5 0.8B Q8_0 configuration — alternative model, ~870 MB.
 * Beta: available only via manual download in Settings; not auto-recommended by DeviceTierDetector.
 *
 * LLM  : Qwen 3.5 0.8B Q8_0 (unsloth, HuggingFace)
 */
object Qwen35ModelConfig {

    private const val LLM_URL =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf?download=true"
    private const val LLM_FILENAME = "Qwen3.5-0.8B-Q8_0.gguf"

    val INSTANCE = ModelConfig(
        llmUrl = LLM_URL,
        llmFilename = LLM_FILENAME,
        llmSpec = ModelFileSpec(LLM_FILENAME, ModelFileFormat.Gguf, minBytes = MIN_LLM_BYTES)
    )
}

/**
 * Gemma 4 E2B configuration, about 3.3 GB: the model for the work after Stop, never for the live
 * notes (see [LlmModelVariant.GEMMA4_E2B_Q4_0]). llama.cpp b9574 loads its architecture
 * ("gemma4"), and the native chat code turns its thinking off (ai_chat.cpp, enable_thinking).
 *
 * LLM  : Gemma 4 E2B instruction-tuned, quantization-aware trained, Q4_0 (google, HuggingFace),
 *        Apache-2.0
 */
object Gemma4E2bModelConfig {

    // A branch, not a commit: should the file at "main" change, a download of it no longer has the
    // size below and is refused as damaged when it would be loaded. Pin a commit once one is known.
    private const val LLM_URL =
        "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf"
    private const val LLM_FILENAME = "gemma-4-E2B_q4_0-it.gguf"

    val INSTANCE = ModelConfig(
        llmUrl = LLM_URL,
        llmFilename = LLM_FILENAME,
        // The file put on the phone on 2026-10-11, sha256
        // fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634.
        llmSpec = ModelFileSpec(LLM_FILENAME, ModelFileFormat.Gguf, bytes = 3_349_516_256L)
    )
}

/**
 * Returns the [ModelConfig] that corresponds to [variant]; with a debug build's test server, with
 * that server's address ([ModelSource]).
 */
fun modelConfigForVariant(variant: LlmModelVariant): ModelConfig = ModelSource.llm(
    when (variant) {
        LlmModelVariant.Q8_0            -> DefaultModelConfig.INSTANCE
        LlmModelVariant.IQ4_NL          -> LowEndModelConfig.INSTANCE
        LlmModelVariant.QWEN3_5_Q8_0    -> Qwen35ModelConfig.INSTANCE
        LlmModelVariant.GEMMA4_E2B_Q4_0 -> Gemma4E2bModelConfig.INSTANCE
    }
)
