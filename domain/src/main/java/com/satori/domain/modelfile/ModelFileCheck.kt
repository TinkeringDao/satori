package com.satori.domain.modelfile

/**
 * How a model file is encoded, which decides the bytes it must start with.
 */
enum class ModelFileFormat {
    /** An ONNX model, a serialized protobuf message: its first byte is the tag of one of the model's fields. */
    Onnx,

    /** A llama.cpp model: the four letters "GGUF" come first. */
    Gguf,

    /** Plain text, such as a token list: there is no header to look at. */
    Text,

    /**
     * Raw bytes with no header, such as the weights an ONNX model keeps in a file of their own
     * (`encoder.int8.onnx.data` beside `encoder.int8.onnx`): only the size tells a whole file.
     */
    Data
}

/**
 * What a complete model file looks like, so a damaged one is caught before a native loader
 * opens it. The loaders (sherpa-onnx, llama.cpp) abort the whole process on a bad file, which no
 * Kotlin code can catch.
 *
 * @property name File name inside the model folder
 * @property format Decides the header [ModelFileCheck] expects
 * @property bytes Size of the complete file in bytes, or null where only [minBytes] is known
 * @property minBytes A file smaller than this is damaged when [bytes] is null; it catches a
 *   placeholder, an error page saved as a model, and a download that stopped early
 */
data class ModelFileSpec(
    val name: String,
    val format: ModelFileFormat,
    val bytes: Long? = null,
    val minBytes: Long = 1L
) {
    init {
        require(minBytes >= 1L) { "minBytes must be at least 1" }
        require(bytes == null || bytes >= minBytes) { "bytes must not be below minBytes" }
    }
}

/** What [ModelFileCheck] makes of a file, or of a set of files. */
enum class ModelFileState {
    /** The file is there and looks complete. */
    Intact,

    /** There is no file, or it is empty: a download has not happened or was removed. */
    Missing,

    /** The file is there but is the wrong size or starts with the wrong bytes. */
    Damaged,

    /**
     * The file is there and the right size, but it could not be read, so nothing is known about
     * what is in it. It is not known to be damaged, and trying again may work.
     */
    Unreadable
}

/** The reason a model was refused, for a loader that can only report an exception. */
class ModelDamagedException(message: String) : IllegalStateException(message)

/**
 * Checks a model file against its [ModelFileSpec] from three facts about it: its size, and the
 * first bytes. Pure, so the rules are testable without a phone; the file reading is
 * `ModelFileInspector` in the data module.
 */
object ModelFileCheck {

    /** How many leading bytes [check] looks at. */
    const val HEADER_BYTES = 4

    /**
     * @param size The file's size in bytes, or null if there is no such file
     * @param header The file's first [HEADER_BYTES] bytes (fewer if it is shorter), or null if
     *   the file could not be read; that is [ModelFileState.Unreadable], not damage
     */
    fun check(spec: ModelFileSpec, size: Long?, header: ByteArray?): ModelFileState {
        if (size == null || size <= 0L) return ModelFileState.Missing
        val sizeFits = if (spec.bytes != null) size == spec.bytes else size >= spec.minBytes
        if (!sizeFits) return ModelFileState.Damaged
        if (header == null) return ModelFileState.Unreadable
        return if (startsLike(spec.format, header)) ModelFileState.Intact else ModelFileState.Damaged
    }

    /**
     * The state of a model made of several files: [ModelFileState.Missing] if any file is
     * missing (there is nothing to repair yet, it has to be downloaded), else
     * [ModelFileState.Damaged] if any is damaged (it has to be downloaded again), else
     * [ModelFileState.Unreadable] if any is, else [ModelFileState.Intact].
     */
    fun combine(states: Collection<ModelFileState>): ModelFileState = when {
        states.any { it == ModelFileState.Missing } -> ModelFileState.Missing
        states.any { it == ModelFileState.Damaged } -> ModelFileState.Damaged
        states.any { it == ModelFileState.Unreadable } -> ModelFileState.Unreadable
        else -> ModelFileState.Intact
    }

    private fun startsLike(format: ModelFileFormat, header: ByteArray): Boolean = when (format) {
        ModelFileFormat.Text, ModelFileFormat.Data -> true
        ModelFileFormat.Gguf -> header.size >= GGUF_MAGIC.size &&
            GGUF_MAGIC.indices.all { header[it] == GGUF_MAGIC[it] }
        ModelFileFormat.Onnx -> header.isNotEmpty() && (header[0].toInt() and 0xFF) in ONNX_FIRST_BYTES
    }

    private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

    // A protobuf message starts with the tag of its first field, (number shl 3) or wire type.
    // ModelProto's fields are ir_version (1), producer_name (2), producer_version (3),
    // domain (4), model_version (5), doc_string (6), graph (7), opset_import (8),
    // metadata_props (14), training_info (20) and functions (25). Serializers write them in
    // field order, so ir_version's 0x08 comes first in practice; the others are listed so no
    // valid model is refused. Most text, JSON and HTML start with other bytes, and the size
    // check catches the rest.
    private val ONNX_FIRST_BYTES = setOf(0x08, 0x12, 0x1A, 0x22, 0x28, 0x32, 0x3A, 0x42, 0x72, 0xA2, 0xCA)
}
