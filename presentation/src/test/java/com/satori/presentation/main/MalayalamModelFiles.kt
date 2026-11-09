package com.satori.presentation.main

import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.SpeechModelId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Puts the Malayalam speech model's files in the folder [models] keeps them in: each as large as
 * the registry says, the model file a hole of zeros (sparse, no room on the disk) between the
 * start of an ONNX file and the metadata sherpa-onnx reads at its end, and a list of tokens of the
 * registry's count with the blank, so every check finds the model whole
 * ([ModelDownloadManager.checkSttModel]). No weights: nothing here could be loaded.
 */
internal fun malayalamModelIn(models: ModelDownloadManager) {
    val dir = models.speechModelDir(SpeechModelId.INDICCONFORMER_ML).apply { mkdirs() }
    val (model, tokens) = SpeechModels.INDICCONFORMER_ML.files
    sparseOnnx(File(dir, model.spec.name), checkNotNull(model.spec.bytes), model.metadata)
    File(dir, tokens.spec.name).writeText(tokenList(checkNotNull(tokens.tokens), checkNotNull(tokens.spec.bytes).toInt()))
}

/** Takes the Malayalam model off the phone again, as its deletion in Advanced settings does. */
internal fun noMalayalamModelIn(models: ModelDownloadManager) {
    models.speechModelDir(SpeechModelId.INDICCONFORMER_ML).deleteRecursively()
}

/**
 * An ONNX file of exactly [size] bytes: ir_version, a graph of zeros as long as the rest leaves,
 * then [metadata] as metadata_props entries (field 14), as sherpa-onnx's export writes them.
 */
private fun sparseOnnx(file: File, size: Long, metadata: Map<String, String>) {
    val head = ByteArrayOutputStream().apply {
        varint(1L shl 3)
        varint(8L)
    }.toByteArray()
    val tail = ByteArrayOutputStream().apply {
        metadata.forEach { (key, value) ->
            val entry = ByteArrayOutputStream().apply {
                field(1, key.toByteArray())
                field(2, value.toByteArray())
            }.toByteArray()
            field(14, entry)
        }
    }.toByteArray()
    // The graph's tag and length come before its bytes; the length takes as many bytes as it needs.
    var graphBytes = size - head.size - tail.size
    var header: ByteArray
    while (true) {
        header = ByteArrayOutputStream().apply {
            varint((7L shl 3) or 2L)
            varint(graphBytes)
        }.toByteArray()
        val fitting = size - head.size - tail.size - header.size
        if (fitting == graphBytes) break
        graphBytes = fitting
    }
    RandomAccessFile(file, "rw").use { output ->
        output.write(head)
        output.write(header)
        output.setLength(output.filePointer + graphBytes)
        output.seek(output.length())
        output.write(tail)
    }
    check(file.length() == size) { "wrote ${file.length()} bytes, not $size" }
}

/** [count] tokens, <unk> first and the blank last, as the Malayalam model's, padded to [bytes]. */
private fun tokenList(count: Int, bytes: Int): String {
    val rest = buildString {
        for (id in 1 until count - 1) append("p$id $id\n")
        append("<blk> ${count - 1}\n")
    }
    val padding = bytes - "<unk> 0\n".length - rest.toByteArray().size
    return "<unk>${"x".repeat(padding)} 0\n$rest".also { check(it.toByteArray().size == bytes) }
}

private fun ByteArrayOutputStream.varint(value: Long) {
    var rest = value
    while (true) {
        if (rest and 0x7FL.inv() == 0L) {
            write(rest.toInt())
            return
        }
        write(((rest and 0x7F) or 0x80).toInt())
        rest = rest ushr 7
    }
}

private fun ByteArrayOutputStream.field(number: Int, bytes: ByteArray) {
    varint((number.toLong() shl 3) or 2L)
    varint(bytes.size.toLong())
    write(bytes)
}
