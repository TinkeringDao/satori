package com.satori.domain.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The golden data of `domain/src/test/resources/reading/`, written by
 * `~/satori-audit/tools/reading/golden.py` from the Python reference. Each file is a TSV: lines
 * starting with `#` say where it came from, then a header, then one row per case, every field
 * JSON (so a tab or a line break inside a string is escaped).
 */
internal object Golden {

    fun resource(name: String): String =
        requireNotNull(Golden::class.java.getResourceAsStream("/reading/$name")) { "no test resource reading/$name" }
            .use { it.readBytes().decodeToString() }

    fun rows(name: String): List<Row> = parse(resource(name))

    fun rows(file: File): List<Row> = parse(file.readText())

    fun parse(text: String): List<Row> {
        val lines = text.split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split('\t')
        return lines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == header.size) { "row ${index + 1}: ${fields.size} fields for ${header.size} columns" }
            Row(index + 1, header.zip(fields).associate { (column, field) -> column to Json.parseToJsonElement(field) })
        }
    }

    class Row(val number: Int, private val fields: Map<String, JsonElement>) {

        fun string(column: String): String = fields.getValue(column).jsonPrimitive.content

        fun int(column: String): Int = fields.getValue(column).jsonPrimitive.int

        fun has(column: String): Boolean = fields[column].let { it != null && it !is JsonNull }

        fun strings(column: String): List<String> = fields.getValue(column).jsonArray.map { it.jsonPrimitive.content }

        fun ints(column: String): IntArray = fields.getValue(column).jsonArray.map { it.jsonPrimitive.int }.toIntArray()

        /** The row's name, for messages; never its text, which may be private in an outside run. */
        val name: String get() = (fields["name"] as? JsonPrimitive)?.content ?: "row $number"
    }

    /** The cases of a golden preprocessing file, the inputs with what each step gives. */
    fun preprocessing(rows: List<Row>): List<PreprocessCase> = rows.map { row ->
        PreprocessCase(
            name = row.name,
            input = row.string("input"),
            tagged = row.string("tagged"),
            placeholders = row.strings("placeholders"),
            punctuation = row.string("punc"),
            wrapped = row.string("wrapped"),
            normalized = row.string("normalized"),
            tokens = row.string("tokens"),
            variants = row.int("variants")
        )
    }

    data class PreprocessCase(
        val name: String,
        val input: String,
        val tagged: String,
        val placeholders: List<String>,
        val punctuation: String,
        val wrapped: String,
        val normalized: String,
        val tokens: String,
        val variants: Int
    )

    /** Both subset vocabularies: every entry the golden ids and English need, as the pinned files have them. */
    val pieceIds: PieceIds by lazy { PieceIds.of(resource("dict.SRC.subset.json"), resource("dict.TGT.subset.json")) }
}
