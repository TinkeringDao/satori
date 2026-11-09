package com.arm.aichat.internal

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GGUF headers for tests, laid out as llama.cpp writes them; no weights, only where they would be. */
internal object TestGguf {

    /** A tensor of the table: its name and its bytes. */
    class Row(val name: String, val bytes: Long)

    /** The tensor table of one of the app's two models, from the test resources (their headers' tables). */
    fun rows(model: String): List<Row> =
        TestGguf::class.java.getResourceAsStream("/gguf/$model.tensors.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Row(it[0], it[2].toLong()) } }

    /**
     * A GGUF header of [rows] in their order, each tensor's data aligned to [alignment], with
     * key-values like a model's before them (a vocabulary of strings and of floats among them), and
     * the length of the whole file it starts.
     */
    fun header(rows: List<Row>, alignment: Int = 32, withAlignmentKey: Boolean = true): Pair<ByteArray, Long> {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun string(s: String) = s.toByteArray().let { u64(it.size.toLong()); out.write(it) }
        u32(0x46554747)
        u32(3)
        u64(rows.size.toLong())
        u64(if (withAlignmentKey) 5 else 4)
        string("general.architecture"); u32(8); string("gemma4")
        string("tokenizer.ggml.tokens"); u32(9); u32(8); u64(3); string("<pad>"); string("▁the"); string("ing")
        string("tokenizer.ggml.scores"); u32(9); u32(6); u64(3); repeat(3) { u32(0) }
        string("gemma4.block_count"); u32(4); u32(35)
        if (withAlignmentKey) {
            string("general.alignment"); u32(4); u32(alignment)
        }
        var offset = 0L
        for (row in rows) {
            string(row.name)
            u32(2)
            u64(row.bytes)
            u64(1)
            u32(0)
            u64(offset)
            offset = (offset + row.bytes + alignment - 1) / alignment * alignment
        }
        val dataStart = (out.size().toLong() + alignment - 1) / alignment * alignment
        return out.toByteArray() to dataStart + offset
    }

    /** [file] as a GGUF of [rows]: the header, then the rest of its length as a hole. */
    fun write(file: File, rows: List<Row>): File {
        val (bytes, length) = header(rows)
        RandomAccessFile(file, "rw").use {
            it.write(bytes)
            it.setLength(length)
        }
        return file
    }
}
