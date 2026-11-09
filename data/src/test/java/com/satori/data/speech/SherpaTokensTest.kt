package com.satori.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A tokens.txt as sherpa-onnx parses it: each line a token and its id, or an id alone; the ids 0
 * to the model's vocabulary size less one, each once; and a blank for greedy CTC search. Anything
 * else would end the process in sherpa-onnx, so it is named instead.
 */
class SherpaTokensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tokens(text: String): File = tmp.newFile().apply { writeText(text) }

    /** The Malayalam model's layout: <unk> first, the pieces, the blank last. */
    private fun ctcList(count: Int, blank: String = "<blk>"): String = buildString {
        append("<unk> 0\n")
        for (id in 1 until count - 1) append("▁പ$id $id\n")
        append("$blank ${count - 1}\n")
    }

    @Test
    fun `a list of the model's size with a blank is one sherpa-onnx can decode with`() {
        assertNull(SherpaTokens.problem(tokens(ctcList(257)), 257))
        assertNull(SherpaTokens.problem(tokens(ctcList(5, blank = "<blank>")), 5))
        assertNull(SherpaTokens.problem(tokens(ctcList(5, blank = "<eps>")), 5))
    }

    @Test
    fun `an id alone is the token of a space`() {
        assertNull(SherpaTokens.problem(tokens("<blk> 0\n1\n▁a 2\n"), 3))
    }

    @Test
    fun `Windows line ends and tabs are white space, as sherpa-onnx trims them`() {
        assertNull(SherpaTokens.problem(tokens("<blk>\t0\r\n▁a 1\r\n  ▁b   2  \r\n"), 3))
    }

    @Test
    fun `a list shorter or longer than the model's vocabulary is refused`() {
        assertEquals("256 tokens, not 257", SherpaTokens.problem(tokens(ctcList(256)), 257))
        assertEquals("line 258 has the id 257, not one of 0 to 256", SherpaTokens.problem(tokens(ctcList(258)), 257))
    }

    @Test
    fun `a line sherpa-onnx can't parse is refused`() {
        assertEquals(
            "line 2 has 3 fields, not a token and its id",
            SherpaTokens.problem(tokens("<blk> 0\n▁a b 1\n"), 2)
        )
        assertEquals("line 2 has the id one, not one of 0 to 1", SherpaTokens.problem(tokens("<blk> 0\n▁a one\n"), 2))
        assertEquals("line 2 has 0 fields, not a token and its id", SherpaTokens.problem(tokens("<blk> 0\n\n▁a 1\n"), 2))
    }

    @Test
    fun `an id given twice is refused`() {
        assertEquals("line 3 has the id 1 a second time", SherpaTokens.problem(tokens("<blk> 0\n▁a 1\n▁b 1\n"), 3))
    }

    @Test
    fun `a list without a blank is refused`() {
        assertEquals(
            "no blank among the tokens (<blk>, <eps>, <blank>)",
            SherpaTokens.problem(tokens("<unk> 0\n▁a 1\n▁b 2\n"), 3)
        )
    }
}
