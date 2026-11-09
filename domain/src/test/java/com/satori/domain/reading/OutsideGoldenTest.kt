package com.satori.domain.reading

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Validation V0a on data that stays outside the repo, run by hand:
 * - `READING_GOLDEN`: a directory of golden.py outputs: preprocessing files (FLORES-200 devtest,
 *   the large fuzz set, the private dry run over the owner's segments) and `piece_ids_full.tsv`
 *   (every piece of `dict.SRC.json`);
 * - `READING_DICTS`: a directory holding the pinned `dict.SRC.json` and `dict.TGT.json`.
 *
 * Skipped when neither is set. It prints and asserts counts and row numbers only, never a line's
 * text, so a private run leaves nothing of the owner's in the test reports.
 */
class OutsideGoldenTest {

    private val golden = System.getenv("READING_GOLDEN")?.let(::File)
    private val dicts = System.getenv("READING_DICTS")?.let(::File)

    @Test
    fun `preprocessing matches the reference on every outside line`() {
        val dir = golden
        assumeTrue(dir != null)
        val files = requireNotNull(dir).listFiles { f -> f.name.endsWith(".tsv") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            // A parse error quotes the input; this one says only which file.
            val rows = runCatching { Golden.rows(file) }.getOrElse { throw AssertionError("${file.name} could not be read") }
            if (rows.isEmpty() || !rows.first().has("tagged")) continue
            var sameText = 0
            var sameMaps = 0
            val differ = ArrayList<Int>()
            for (row in rows) {
                val out = IndicPreprocessor.preprocess(row.string("input"))
                val text = out.tagged == row.string("tagged")
                val maps = out.placeholders.matches == row.strings("placeholders")
                if (text) sameText++
                if (maps) sameMaps++
                if (!text || !maps) differ += row.number
            }
            println("${file.name}: ${rows.size} lines, preprocessing identical on $sameText, placeholders on $sameMaps")
            assertEquals("${file.name}: rows that differ ${differ.take(20)}", 0, differ.size)
        }
    }

    @Test
    fun `the pinned vocabularies parse whole and map every piece as the reference does`() {
        val dir = dicts
        assumeTrue(dir != null)
        val source = File(dir, "dict.SRC.json")
        val target = File(dir, "dict.TGT.json")
        // The SHA-256 the reader's pins give these files (ReaderModels in the data module).
        assertEquals("3d3b180702520742dec423a5a7f7b4e3dad399d69e40ca7df294a1567884bed5", sha256(source))
        assertEquals("13c3a162fe655dbe99c790a413675c5d0634cd771fadcefe8d407676a7d1a311", sha256(target))
        val sourceEntries = PieceIds.parse(source.readText())
        val targetEntries = PieceIds.parse(target.readText())
        assertEquals(122_706, sourceEntries.size)
        assertEquals(32_296, targetEntries.size)
        assertEquals((0 until 122_706).toList(), sourceEntries.values.sorted())
        assertEquals((0 until 32_296).toList(), targetEntries.values.sorted())
        val ids = PieceIds(sourceEntries, targetEntries)

        val full = golden?.let { File(it, "piece_ids_full.tsv") }?.takeIf { it.isFile }
        if (full != null) {
            val rows = Golden.rows(full)
            for (row in rows) assertArrayEquals(row.name, row.ints("ids"), ids.sourceIds(row.strings("pieces")))
            val pieces = rows.sumOf { it.strings("pieces").size }
            println("piece_ids_full.tsv: ${rows.size} lists, $pieces pieces, ids identical on all")
        }

        val fixtures = Golden.rows("detok_fixtures.tsv")
        val same = fixtures.count { row ->
            val placeholders = IndicPreprocessor.preprocess(row.string("source")).placeholders
            EnglishDetokenizer.detokenize(ids.targetPieces(row.ints("ids")), placeholders) == row.string("text")
        }
        println("detok_fixtures.tsv with the full dict.TGT.json: $same of ${fixtures.size}")
        assertEquals(fixtures.size, same)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
