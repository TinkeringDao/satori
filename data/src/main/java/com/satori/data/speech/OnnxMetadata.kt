package com.satori.data.speech

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A file that isn't the protobuf message it should be: no read of it again will change that. */
class MalformedModelFileException(message: String) : IOException(message)

/**
 * The metadata of an ONNX model file: the key and value of each entry of its ModelProto's
 * metadata_props (field 14), which sherpa-onnx reads with ONNX Runtime's custom metadata map as it
 * loads the model. Read here without loading it, so a file sherpa-onnx would end the process on is
 * refused first ([SpeechModelLoadCheck]).
 *
 * A ModelProto is a protobuf message: a run of fields, each a tag (field number and wire type)
 * and a value. The graph, which holds the weights, is one length-delimited field of nearly the
 * whole file, so it is skipped with a seek, as is every other field but metadata_props: a read of
 * a 138 MB model takes a few small reads. Serializers write metadata_props after the graph, but
 * any order is read. When a key comes twice the last one counts, as ONNX Runtime keeps it.
 */
object OnnxMetadata {

    /** ModelProto.metadata_props, a repeated StringStringEntryProto. */
    private const val METADATA_PROPS = 14

    /** StringStringEntryProto.key and .value. */
    private const val KEY = 1
    private const val VALUE = 2

    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH_DELIMITED = 2
    private const val FIXED32 = 5

    /**
     * An entry longer than this is skipped unread: sherpa-onnx's keys and values are a few bytes
     * each, and a doc string of some kilobytes fits too, while a damaged length can't make this
     * allocate most of the file.
     */
    internal const val MAX_ENTRY_BYTES = 64 * 1024

    /**
     * The metadata of [file], in the order of its entries.
     *
     * @throws MalformedModelFileException when the file isn't a well-formed protobuf message
     * @throws IOException when it can't be read
     */
    fun read(file: File): Map<String, String> = RandomAccessFile(file, "r").use { input ->
        val end = input.length()
        val metadata = LinkedHashMap<String, String>()
        while (input.filePointer < end) {
            val at = input.filePointer
            val tag = input.readVarint()
            val field = tag ushr 3
            if (field == 0L) throw MalformedModelFileException("Field number 0 at byte $at")
            when (val wireType = (tag and 7L).toInt()) {
                VARINT -> input.readVarint()
                FIXED64 -> input.skipTo(input.filePointer + 8, end)
                FIXED32 -> input.skipTo(input.filePointer + 4, end)
                LENGTH_DELIMITED -> {
                    val length = input.readVarint()
                    if (length < 0 || length > end - input.filePointer) {
                        throw MalformedModelFileException("Field $field at byte $at runs past the end of the file")
                    }
                    if (field == METADATA_PROPS.toLong() && length <= MAX_ENTRY_BYTES) {
                        val bytes = ByteArray(length.toInt())
                        input.readFully(bytes)
                        entry(bytes, at)?.let { (key, value) -> metadata[key] = value }
                    } else {
                        input.seek(input.filePointer + length)
                    }
                }
                else -> throw MalformedModelFileException("Wire type $wireType at byte $at")
            }
        }
        metadata
    }

    /** One StringStringEntryProto: its key and value, or null without a key. */
    private fun entry(bytes: ByteArray, at: Long): Pair<String, String>? {
        val reader = BytesReader(bytes, at)
        var key: String? = null
        var value = ""
        while (!reader.atEnd) {
            val tag = reader.varint()
            val field = tag ushr 3
            when (val wireType = (tag and 7L).toInt()) {
                VARINT -> reader.varint()
                FIXED64 -> reader.skip(8)
                FIXED32 -> reader.skip(4)
                LENGTH_DELIMITED -> {
                    val text = reader.bytes(reader.varint())
                    when (field) {
                        KEY.toLong() -> key = String(text, Charsets.UTF_8)
                        VALUE.toLong() -> value = String(text, Charsets.UTF_8)
                    }
                }
                else -> throw MalformedModelFileException("Wire type $wireType in the metadata entry at byte $at")
            }
        }
        return key?.let { it to value }
    }

    /** A base-128 varint of at most ten bytes, the last of them at most 1. */
    private fun RandomAccessFile.readVarint(): Long {
        var result = 0L
        for (shift in 0 until 64 step 7) {
            val byte = read()
            if (byte < 0) throw MalformedModelFileException("The file ends inside a number")
            if (shift == 63 && byte > 1) throw MalformedModelFileException("A number longer than 64 bits")
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
        }
        throw MalformedModelFileException("A number longer than ten bytes")
    }

    private fun RandomAccessFile.skipTo(position: Long, end: Long) {
        if (position > end) throw MalformedModelFileException("A field runs past the end of the file")
        seek(position)
    }

    /** The bytes of one metadata entry, read as a message of their own. */
    private class BytesReader(private val bytes: ByteArray, private val at: Long) {
        private var position = 0

        val atEnd: Boolean get() = position >= bytes.size

        fun varint(): Long {
            var result = 0L
            for (shift in 0 until 64 step 7) {
                if (position >= bytes.size) throw MalformedModelFileException("The metadata entry at byte $at ends inside a number")
                val byte = bytes[position++].toInt() and 0xFF
                if (shift == 63 && byte > 1) throw MalformedModelFileException("A number longer than 64 bits at byte $at")
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
            }
            throw MalformedModelFileException("A number longer than ten bytes at byte $at")
        }

        fun skip(count: Long) {
            if (count < 0 || count > bytes.size - position) {
                throw MalformedModelFileException("A field of the metadata entry at byte $at runs past its end")
            }
            position += count.toInt()
        }

        fun bytes(count: Long): ByteArray {
            val from = position
            skip(count)
            return bytes.copyOfRange(from, position)
        }
    }
}
