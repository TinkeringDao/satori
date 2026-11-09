package com.satori.data.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes the protobuf of an ONNX model file for the tests: the fields of a ModelProto in the
 * order given, the graph as a length-delimited field of any bytes, and the metadata entries as
 * StringStringEntryProto. No weights: the graph's bytes are made up, and only their length counts.
 */
internal class OnnxProto {
    private val out = ByteArrayOutputStream()

    fun varintField(number: Int, value: Long) = apply {
        tag(number, 0)
        varint(value)
    }

    fun bytesField(number: Int, bytes: ByteArray) = apply {
        tag(number, 2)
        varint(bytes.size.toLong())
        out.write(bytes)
    }

    fun fixed32Field(number: Int) = apply {
        tag(number, 5)
        out.write(ByteArray(4))
    }

    fun fixed64Field(number: Int) = apply {
        tag(number, 1)
        out.write(ByteArray(8))
    }

    /** ir_version, field 1, as every ONNX file starts. */
    fun irVersion(version: Long = 8) = varintField(1, version)

    /** producer_name, field 2. */
    fun producer(name: String) = bytesField(2, name.toByteArray())

    /** The graph, field 7, of [bytes]. */
    fun graph(bytes: ByteArray) = bytesField(7, bytes)

    /** One metadata_props entry, field 14; a null key or value leaves that field out. */
    fun metadata(key: String?, value: String?) = apply {
        val entry = ByteArrayOutputStream()
        key?.let { entry.writeBytesField(1, it.toByteArray()) }
        value?.let { entry.writeBytesField(2, it.toByteArray()) }
        bytesField(14, entry.toByteArray())
    }

    fun metadata(entries: Map<String, String>) = apply { entries.forEach { (key, value) -> metadata(key, value) } }

    /** Raw bytes, for a file that isn't well formed. */
    fun raw(vararg bytes: Int) = apply { bytes.forEach { out.write(it) } }

    fun bytes(): ByteArray = out.toByteArray()

    fun writeTo(file: File): File = file.also { it.writeBytes(bytes()) }

    private fun tag(number: Int, wireType: Int) = varint((number.toLong() shl 3) or wireType.toLong())

    private fun varint(value: Long) = out.writeVarint(value)

    companion object {
        /**
         * A model file of exactly [size] bytes whose graph is [size] less the rest, left as a hole
         * of zeros (a sparse file where the file system has them), followed by [metadata]: the
         * size check passes on it, and the metadata is read from its end, as from a real model.
         */
        fun sparseModel(file: File, size: Long, metadata: Map<String, String>): File {
            val tail = OnnxProto().metadata(metadata).bytes()
            val head = OnnxProto().irVersion()
            // The graph's tag and length come before its bytes; the length takes as many bytes as it needs.
            var graphBytes = size - head.bytes().size - tail.size
            var header: ByteArray
            while (true) {
                header = ByteArrayOutputStream().apply {
                    writeVarint((7L shl 3) or 2L)
                    writeVarint(graphBytes)
                }.toByteArray()
                val fitting = size - head.bytes().size - tail.size - header.size
                if (fitting == graphBytes) break
                graphBytes = fitting
            }
            RandomAccessFile(file, "rw").use { output ->
                output.write(head.bytes())
                output.write(header)
                output.setLength(output.filePointer + graphBytes)
                output.seek(output.length())
                output.write(tail)
            }
            check(file.length() == size) { "wrote ${file.length()} bytes, not $size" }
            return file
        }

        private fun ByteArrayOutputStream.writeVarint(value: Long) {
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

        private fun ByteArrayOutputStream.writeBytesField(number: Int, bytes: ByteArray) {
            writeVarint((number.toLong() shl 3) or 2L)
            writeVarint(bytes.size.toLong())
            write(bytes)
        }
    }
}
