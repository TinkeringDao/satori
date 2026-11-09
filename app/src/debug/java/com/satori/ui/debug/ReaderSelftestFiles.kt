package com.satori.ui.debug

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug builds only: where the reader's selftests read their inputs and write their outputs, the
 * app's external files' `debug/reader` (`/sdcard/Android/data/com.satori/files/debug/reader`), and
 * the files' formats. The inputs are pushed public text (FLORES-200, synthetic lines,
 * `~/satori-audit/tools/reading/push/`); the outputs are pulled from the same folder.
 */
internal object ReaderSelftestFiles {

    /** The folder's name under the external files' `debug`. */
    const val FOLDER = "reader"

    /**
     * The file [name] in [dir], or null for a name that is no plain file name of that folder (a
     * path, a `..`, an empty name).
     */
    fun inFolder(dir: File, name: String?): File? {
        val given = name?.trim().orEmpty()
        if (given.isEmpty() || given == "." || given == ".." || '/' in given || '\\' in given) return null
        return File(dir, given)
    }

    /** One line of a TSV a selftest reads: its fields by column name. */
    class Row(val number: Int, private val fields: Map<String, String>) {
        operator fun get(column: String): String? = fields[column]
    }

    /**
     * The rows of a TSV in golden.py's format (`~/satori-audit/tools/reading/golden.py`): lines
     * starting with `#` left out, a header of column names, then one row per line, each field a
     * JSON value (a string, or a number). A field that is a JSON string gives its text; any other
     * value its JSON.
     *
     * @throws IllegalArgumentException for a file without a header, or a row with a field that
     *   isn't JSON, or with more or fewer fields than the header
     */
    fun rows(text: String): Pair<List<String>, List<Row>> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() && !it.startsWith("#") }
        require(lines.isNotEmpty()) { "no header" }
        val header = lines.first().split('\t')
        val rows = lines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == header.size) { "row ${index + 1} has ${fields.size} fields, the header ${header.size}" }
            Row(index + 1, header.zip(fields.map(::decode)).toMap())
        }
        return header to rows
    }

    /** The text of a JSON field: a string's characters, any other value's JSON. */
    fun decode(field: String): String {
        val value = try {
            Json.parseToJsonElement(field)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("a field that is not JSON", e)
        }
        return if (value is JsonPrimitive && value.isString) value.content else value.toString()
    }

    /** [text] as a JSON string, as golden.py writes its fields. */
    fun json(text: String): String = JsonPrimitive(text).toString()

    /** [values] as a JSON array of strings. */
    fun jsonStrings(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    /** [values] as a JSON array of numbers. */
    fun jsonInts(values: IntArray): String = values.joinToString(",", "[", "]")

    /** The lines of a plain text file, one sentence each: the last newline ends the last line, no empty line after it. */
    fun lines(text: String): List<String> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /**
     * Writes [out] through [write] into a `.partial` beside it, renamed to [out] only once [write]
     * has returned, so a file pulled under an output's name is always whole. When [write] throws,
     * or is cancelled, the `.partial` goes and a file already under that name stays as it was.
     */
    suspend fun <T> writing(out: File, write: suspend (Appendable) -> T): T {
        val partial = File(out.path + ".partial")
        val writer = withContext(Dispatchers.IO) { partial.bufferedWriter() }
        try {
            val result = write(writer)
            withContext(Dispatchers.IO) {
                writer.close()
                if (!partial.renameTo(out)) throw IOException("${out.name} not written")
            }
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { writer.close() }
                partial.delete()
            }
        }
    }
}
