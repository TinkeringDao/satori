package com.satori.feature.reader

import com.satori.data.config.ReaderModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.reader.ReaderGraphs
import java.io.File

/**
 * The bytes each of the reader's graphs takes into anonymous memory when its session prepacks its
 * MatMul weights (D3): what a load makes room for before its native part ([ReaderFiles.parseBytes]),
 * as the speech models' loads do for their parse ([com.satori.data.monitor.ParseRoom]).
 *
 * @property always What the sessions take whether they prepack or not
 */
data class PackedBytes(val encoder: Long, val decoder: Long, val decoderWithPast: Long, val always: Long = 0L) {

    /** What [arm]'s sessions take: [always], and the packed weights of its prepacked graphs. */
    fun of(arm: ReaderArm): Long = always + when (arm.prepacking) {
        Prepacking.SESSION -> encoder + decoderWithPast
        Prepacking.ALL -> encoder + decoder + decoderWithPast
        Prepacking.OFF -> 0L
    }

    companion object {
        // IndicTrans2-Dist: 18 layers a side, d 512, FFN 2048, 32,296 target pieces (config.json).
        private const val LAYERS = 18L
        private const val D = 512L
        private const val FFN = 2_048L
        private const val TARGET_VOCABULARY = 32_296L
        private const val ATTENTION = 4 * D * D
        private const val FEED_FORWARD = 2 * D * FFN
        private const val OUTPUT = TARGET_VOCABULARY * D

        /** The MatMul weights of each graph, one per parameter: the int8 build's bytes. */
        private val WEIGHTS = PackedBytes(
            // Self-attention and feed-forward: about 57 MB.
            encoder = LAYERS * (ATTENTION + FEED_FORWARD),
            // Self- and cross-attention, feed-forward, the output layer: about 92 MB.
            decoder = LAYERS * (2 * ATTENTION + FEED_FORWARD) + OUTPUT,
            // Without the cross-attention's key and value, which come in as its cache: about 83 MB.
            decoderWithPast = LAYERS * (ATTENTION + 2 * D * D + FEED_FORWARD) + OUTPUT
        )

        /**
         * The packed weights of the build [model]: the int8 build's one byte per weight, which
         * the design's 0.14 GB for the default arm (0.23 GB all prepacked) comes from. The fp16
         * build's memory on ONNX Runtime's CPU path is not known (a V1 arm), so it is priced at its
         * worst: every weight of all three graphs in fp32, 4 bytes, whatever the arm, as ONNX
         * Runtime may cast the fp16 weights its CPU kernels don't take into new fp32 ones.
         */
        fun of(model: ReaderModelId): PackedBytes = when (model) {
            ReaderModelId.INT8 -> WEIGHTS
            ReaderModelId.FP16 ->
                PackedBytes(0L, 0L, 0L, always = 4 * (WEIGHTS.encoder + WEIGHTS.decoder + WEIGHTS.decoderWithPast))
        }
    }
}

/**
 * The files one reader loads from: a downloaded build's folder ([of]), checked before every load,
 * or, in a debug build, a folder of a toy model with the same file names.
 *
 * @property name What the field log calls the model: the build's id
 * @property dir The folder all the files are in
 * @property packed What the graphs' prepacking takes, for the parse room
 * @property loadFiles What a load of the whole reader reads, so its pre-read reads them in first:
 *   a build's every file (the graphs, their external data, the tokenizers, the vocabularies and the
 *   configurations)
 * @property check Whether the files are the ones to load ([ModelFileState.Intact]); file access
 */
class ReaderFiles(
    val name: String,
    val dir: File,
    private val packed: PackedBytes,
    val loadFiles: List<File>,
    val check: () -> ModelFileState
) {
    val graphs: ReaderGraphs
        get() = ReaderGraphs(File(dir, ReaderModels.ENCODER), File(dir, ReaderModels.DECODER), File(dir, ReaderModels.DECODER_WITH_PAST))

    val sourcePieces: File get() = File(dir, ReaderModels.SOURCE_PIECES)
    val sourceVocabulary: File get() = File(dir, ReaderModels.SOURCE_VOCABULARY)
    val targetVocabulary: File get() = File(dir, ReaderModels.TARGET_VOCABULARY)
    val config: File get() = File(dir, ReaderModels.CONFIG)

    /** What the tokenizer alone reads: SentencePiece's model and the two vocabularies. */
    fun tokenizerFiles(): List<File> = listOf(sourcePieces, sourceVocabulary, targetVocabulary)

    /** The bytes [arm]'s sessions turn anonymous at load, for its parse room: the packed weights, not the `.data` files. */
    fun parseBytes(arm: ReaderArm): Long = packed.of(arm)

    companion object {
        /** The downloaded build [model]: its folder, its packed weights, its files, and the registry's check of them. */
        fun of(downloads: ModelDownloadManager, model: ReaderModelId): ReaderFiles {
            val dir = downloads.readerModelDir(model)
            return ReaderFiles(model.id, dir, PackedBytes.of(model), ReaderModels.of(model).files.map { File(dir, it.spec.name) }) {
                downloads.checkReaderModel(model)
            }
        }
    }
}
