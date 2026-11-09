package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port of IndicProcessor's preprocessing against the Python reference: on the 200 code-mixed
 * sentences written for the test and on the edge cases (golden.py), the whole line and each step.
 * Every expected string is what the real IndicProcessor gives (under the hash seed the file names).
 */
class IndicPreprocessorTest {

    private val codeMixed = Golden.preprocessing(Golden.rows("preprocess_codemixed.tsv"))
    private val edge = Golden.preprocessing(Golden.rows("preprocess_edge.tsv"))

    @Test
    fun `the golden files hold what they should`() {
        assertEquals(200, codeMixed.size)
        assertEquals(200, codeMixed.map { it.input }.distinct().size)
        assertTrue(edge.size >= 300)
        // Some lines have placeholders in more than one order in the reference; the port's is one of them.
        assertTrue(codeMixed.any { it.variants > 1 })
    }

    @Test
    fun `each code-mixed sentence comes out as IndicProcessor's`() {
        assertAllEqual(codeMixed) { it.tagged to IndicPreprocessor.preprocess(it.input).tagged }
    }

    @Test
    fun `each edge case comes out as IndicProcessor's`() {
        assertAllEqual(edge) { it.tagged to IndicPreprocessor.preprocess(it.input).tagged }
    }

    @Test
    fun `the placeholders stand for what the reference's map has`() {
        assertAllEqual(codeMixed + edge) { it.placeholders to IndicPreprocessor.preprocess(it.input).placeholders.matches }
    }

    @Test
    fun `each step gives what the reference's step gives`() {
        val all = codeMixed + edge
        assertAllEqual(all) { it.punctuation to IndicPreprocessor.punctuation(it.input) }
        assertAllEqual(all) { it.wrapped to IndicPreprocessor.placeholders(IndicPreprocessor.digits(it.punctuation)).first }
        assertAllEqual(all) { it.normalized to MalayalamNormalizer.normalize(PythonText.strip(it.wrapped)) }
        assertAllEqual(all) { it.tokens to TrivialTokenizer.tokenize(it.normalized).joinToString(" ") }
        assertAllEqual(all) {
            it.tagged to "mal_Mlym eng_Latn " + PythonText.strip(IndicTransliterator.toDevanagari(it.tokens))
        }
    }

    @Test
    fun `the digit table is the reference's`() {
        val table = Golden.rows("char_classes.tsv").first { it.string("class") == "digit_table" }.string("ranges")
        val expected = table.split(' ').associate { entry ->
            val (cp, digit) = entry.split('=')
            cp.toInt(16) to digit
        }
        for (cp in 0..0xFFFF) {
            val c = cp.toChar()
            if (c.isSurrogate()) continue
            val want = expected[cp] ?: c.toString()
            assertEquals("U+%04X".format(cp), want, IndicPreprocessor.digits(c.toString()))
        }
    }

    @Test
    fun `placeholders of one length are numbered as their text first occurs`() {
        val line = IndicPreprocessor.preprocess("meeting 10:30 മുതൽ 11:15 വരെ, പിന്നെ 10:30")

        assertEquals(listOf("10:30", "11:15"), line.placeholders.matches)
        assertEquals("mal_Mlym eng_Latn meeting < ID1 > मुतൽ < ID2 > वरॆ , पिन्नॆ < ID1 >", line.tagged)
    }

    @Test
    fun `the longer match is numbered first, so a shorter one inside it does not cut it`() {
        // Both are outputs of the reference (the edge cases hold them with a seed). Its other
        // order gives "< ID1 > मुतൽ < ID1 > : 45" and "< ID1 > < ID1 > m", which read back as
        // "10:30 ... 10:30 : 45" and "a@b.co a@b.co m".
        val times = IndicPreprocessor.preprocess("10:30 മുതൽ 10:30:45 വരെ")
        val mails = IndicPreprocessor.preprocess("a@b.co a@b.com")

        assertEquals(listOf("10:30:45", "10:30"), times.placeholders.matches)
        assertEquals("mal_Mlym eng_Latn < ID2 > मुतൽ < ID1 > वरॆ", times.tagged)
        assertEquals("mal_Mlym eng_Latn < ID2 > < ID1 >", mails.tagged)
        assertEquals("a@b.co a@b.com", EnglishDetokenizer.detokenize(listOf("▁<", "▁ID2", "▁>", "▁<", "▁ID1", "▁>"), mails.placeholders))
    }

    @Test
    fun `a colon is read as IndicNLP 0_92 reads it, not as the rewrite the toolkit's wheel pulls in`() {
        // indic-nlp-library-itt 0.1.1 gives "\1ः B आण् :" here: a Latin letter lost, the Malayalam colon kept.
        assertEquals("mal_Mlym eng_Latn A : B आण्ः", IndicPreprocessor.preprocess("A: B ആണ്:").tagged)
    }

    @Test
    fun `Malayalam words joined by a point are taken for a web address, as by the reference`() {
        assertEquals(listOf("വന്നു.അവൻ"), IndicPreprocessor.preprocess("വന്നു.അവൻ പോയി").placeholders.matches)
    }

    @Test
    fun `a line of plain words has no placeholders and its text is what SentencePiece reads`() {
        val line = IndicPreprocessor.preprocess("ഞാൻ നാളെ വരാം")

        assertEquals(Placeholders.NONE, line.placeholders)
        assertEquals("ञाൻ नाळॆ वरां", line.text)
        assertEquals("mal_Mlym eng_Latn ञाൻ नाळॆ वरां", line.tagged)
    }

    @Test
    fun `an empty line keeps the reference's trailing space after the tags`() {
        assertEquals("mal_Mlym eng_Latn ", IndicPreprocessor.preprocess("").tagged)
        assertEquals("", IndicPreprocessor.preprocess(" \t ").text)
    }

    @Test
    fun `text no speech model writes goes through without an exception`() {
        val odd = listOf("\uD800", "a\uDC00b", "\uDBFF\uDFFF", "\u0000", "ക\uFFFF:", "%".repeat(500), "<ID1>".repeat(50))
        for (line in odd) IndicPreprocessor.preprocess(line)
    }

    @Test
    fun `long runs of digits and points stay quick`() {
        // Lines of 20,000 to 40,000 characters. The e-mail and number patterns share their work
        // along a run of their class, so these take milliseconds; work that grew with the square of
        // a run took about a minute for them.
        val lines = listOf(
            "1".repeat(40_000), "1 ".repeat(20_000), "1.".repeat(20_000), "a.".repeat(20_000), "1%".repeat(20_000),
            "a-".repeat(10_000), "~1".repeat(20_000)
        )
        val started = System.nanoTime()
        for (line in lines) IndicPreprocessor.preprocess(line)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }

    /** Every case's pair equal, or one failure that names the cases that differ and shows the first. */
    private fun <T> assertAllEqual(cases: List<Golden.PreprocessCase>, pair: (Golden.PreprocessCase) -> Pair<T, T>) {
        val wrong = cases.map { it to pair(it) }.filter { (_, p) -> p.first != p.second }
        if (wrong.isNotEmpty()) {
            val (case, p) = wrong.first()
            assertEquals("${wrong.size} of ${cases.size} differ (${wrong.take(8).joinToString { it.first.name }}); ${case.name}", p.first, p.second)
        }
    }
}
