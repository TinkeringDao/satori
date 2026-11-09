package com.arm.aichat.internal

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The tensors a load without MAP_POPULATE (a debug build's `debug.satori.llm.populate=off`) reads
 * from its mapping as inference runs, as byte ranges of the GGUF, so the app can read them in at its
 * pace before the load instead of the whole file.
 *
 * Without MAP_POPULATE the copied tensors come in as llama.cpp copies them (at `copypace`'s pace),
 * and the ones left in place as the first prompt reads them: for Gemma 4 E2B with its layers'
 * matrices copied that is the tied output, `token_embd` (315 MiB of Q6_K, read in full for every
 * token's logits), `per_layer_model_proj` (26 MiB of F16) and the norms, 342 MiB in all, from flash
 * in a fraction of a second, a burst of the size that cost the 1B 3 kills on 2026-10-13. Left out
 * are the tensors the load copies (the layers' quantized matrices, [LoadSettings.TIED_OUTPUT_LAYERS],
 * while repack is on) and the embeddings a token reads a few rows of: E2B's 1.8 GB of per-layer
 * embeddings, and `token_embd` when the model has an output matrix of its own.
 */
internal object InPlaceTensors {

    /** E2B's per-layer embeddings, of which a token reads one row per layer. */
    private const val PER_LAYER_EMBEDDINGS = "per_layer_token_embd.weight"
    private const val TOKEN_EMBEDDINGS = "token_embd.weight"
    private const val OUTPUT = "output.weight"

    private val copiedLayers = Regex(LoadSettings.TIED_OUTPUT_LAYERS)

    /**
     * The byte ranges of [tensors] (first..last byte) a load reads in place; [layersCopied] when
     * the load repacks the layers' matrices (repack on, with or without the tied output mapped).
     */
    fun ranges(tensors: List<GgufTensors.Tensor>, layersCopied: Boolean): List<LongRange> {
        val ownOutput = tensors.any { it.name == OUTPUT }
        return tensors
            .filter { tensor ->
                when {
                    tensor.size <= 0 -> false
                    tensor.name == PER_LAYER_EMBEDDINGS -> false
                    tensor.name == TOKEN_EMBEDDINGS -> !ownOutput
                    layersCopied && copiedLayers.containsMatchIn(tensor.name) -> false
                    else -> true
                }
            }
            .map { it.offset until it.offset + it.size }
    }
}

/**
 * The tensor table of a GGUF file's header (version 2 or 3, little-endian): each tensor's name and
 * where its data lies in the file. Only what [InPlaceTensors] needs; llama.cpp reads the header
 * itself for the load, and the metadata reader (gguf.GgufMetadataReader) stops before the table. A
 * tensor's bytes reach to the next tensor's start, or to the end of the file for the last, so they
 * include the padding to the alignment (under 32 bytes in the app's two models).
 */
internal object GgufTensors {

    /** One tensor's data: [size] bytes at [offset] from the start of the file. */
    class Tensor(val name: String, val offset: Long, val size: Long)

    private const val MAGIC = 0x46554747 // "GGUF", little-endian
    private const val DEFAULT_ALIGNMENT = 32L
    private const val ALIGNMENT_KEY = "general.alignment"

    // Bounds for what a header holds, so a damaged one fails here instead of asking for gigabytes:
    // E2B has 541 tensors and a vocabulary of 262,144 pieces, which are skipped, never kept.
    private const val MAX_STRING_BYTES = 64L * 1024 * 1024
    private const val MAX_COUNT = 100_000_000L
    private const val MAX_TENSORS = 65_536L
    private const val MAX_DIMS = 8

    private const val TYPE_UINT32 = 4
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    /** The tensors of the GGUF [file]. @throws IOException when it isn't one this can read */
    fun read(file: File): List<Tensor> =
        BufferedInputStream(file.inputStream(), 1 shl 16).use { read(it, file.length()) }

    /** The tensors of a GGUF read from [input], the start of a file of [fileLength] bytes. */
    fun read(input: InputStream, fileLength: Long): List<Tensor> {
        val reader = Reader(input)
        if (reader.u32() != MAGIC) throw IOException("Not a GGUF file")
        val version = reader.u32()
        if (version != 2 && version != 3) throw IOException("GGUF version $version not read")
        val tensorCount = reader.count()
        if (tensorCount > MAX_TENSORS) throw IOException("$tensorCount tensors in the GGUF header")
        val kvCount = reader.count()
        var alignment = DEFAULT_ALIGNMENT
        repeat(kvCount.toInt()) {
            val key = reader.string()
            val type = reader.u32()
            if (key == ALIGNMENT_KEY && type == TYPE_UINT32) {
                alignment = reader.u32().toLong() and 0xFFFFFFFFL
                if (alignment <= 0 || alignment and (alignment - 1) != 0L) throw IOException("Alignment $alignment not a power of two")
            } else {
                reader.skipValue(type)
            }
        }
        class Info(val name: String, val relative: Long)
        val infos = ArrayList<Info>()
        repeat(tensorCount.toInt()) {
            val name = reader.string()
            val dims = reader.u32()
            if (dims < 0 || dims > MAX_DIMS) throw IOException("Tensor $name has $dims dimensions")
            reader.skip(dims * 8L)
            reader.u32() // its type: the next tensor's start gives its size
            infos += Info(name, reader.u64())
        }
        val dataStart = (reader.position + alignment - 1) / alignment * alignment
        val sorted = infos.sortedBy { it.relative }
        return sorted.mapIndexed { index, info ->
            val start = dataStart + info.relative
            val end = if (index + 1 < sorted.size) dataStart + sorted[index + 1].relative else fileLength
            if (start < dataStart || end > fileLength || end < start) throw IOException("Tensor ${info.name} lies outside the file")
            Tensor(info.name, start, end - start)
        }
    }

    /** Little-endian reads of a GGUF header, counting where it is. */
    private class Reader(private val input: InputStream) {
        var position = 0L
            private set

        private fun byte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("The GGUF header ends early")
            position++
            return b
        }

        fun u32(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)

        fun u64(): Long = (u32().toLong() and 0xFFFFFFFFL) or (u32().toLong() shl 32)

        fun count(): Long = u64().also { if (it < 0 || it > MAX_COUNT) throw IOException("A count of $it in the GGUF header") }

        fun skip(bytes: Long) {
            var left = bytes
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    byte()
                    left--
                } else {
                    position += skipped
                    left -= skipped
                }
            }
        }

        fun string(): String {
            val length = u64()
            if (length < 0 || length > MAX_STRING_BYTES) throw IOException("A string of $length bytes in the GGUF header")
            val bytes = ByteArray(length.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) throw EOFException("The GGUF header ends early")
                read += n
            }
            position += length
            return String(bytes, Charsets.UTF_8)
        }

        fun skipValue(type: Int) {
            when (type) {
                TYPE_STRING -> skip(u64().also { if (it < 0 || it > MAX_STRING_BYTES) throw IOException("A string of $it bytes") })
                TYPE_ARRAY -> {
                    val elementType = u32()
                    val count = count()
                    val size = fixedSize(elementType)
                    if (size != null) skip(count * size) else repeat(count.toInt()) { skipValue(elementType) }
                }
                else -> skip(fixedSize(type) ?: throw IOException("A value of type $type in the GGUF header"))
            }
        }

        // The bytes of a value of a fixed-size type; null for a string or an array.
        private fun fixedSize(type: Int): Long? = when (type) {
            0, 1, 7 -> 1L // uint8, int8, bool
            2, 3 -> 2L // uint16, int16
            4, 5, 6 -> 4L // uint32, int32, float32
            10, 11, 12 -> 8L // uint64, int64, float64
            TYPE_STRING, TYPE_ARRAY -> null
            else -> throw IOException("A value of type $type in the GGUF header")
        }
    }
}
