package com.satori.domain.reading

import com.satori.domain.reading.GreedySequence.Companion.among
import com.satori.domain.reading.GreedySequence.Companion.any
import com.satori.domain.reading.GreedySequence.Companion.atLeast
import com.satori.domain.reading.GreedySequence.Companion.char
import com.satori.domain.reading.GreedySequence.Companion.one
import com.satori.domain.reading.GreedySequence.Companion.optional
import com.satori.domain.reading.GreedySequence.Companion.some
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The matcher against the backtracking it stands for, spelled out plainly: each repeat takes as
 * many as it can, then gives back one at a time until the rest matches. On random sequences and
 * random text (letters, digits, marks, a letter outside the BMP and a lone surrogate) the end of
 * the match from every start must be the same.
 */
class GreedySequenceTest {

    private val classes: List<(Int) -> Boolean> = listOf(
        among("ab"), among("a1"), among("1."), char('.'), PythonText::isDigit, PythonText::isWord,
        { it != '.'.code }, { true }
    )

    @Test
    fun `every start ends where plain backtracking ends`() {
        val random = Random(15)
        val alphabet = listOf("a", "b", "1", "2", ".", "-", " ", "𝐀", "\uD800")
        repeat(3_000) {
            val elements = List(random.nextInt(1, 6)) {
                val test = classes[random.nextInt(classes.size)]
                when (random.nextInt(5)) {
                    0 -> one(test)
                    1 -> optional(test)
                    2 -> some(test)
                    3 -> any(test)
                    else -> atLeast(2, test)
                }
            }
            val sequence = GreedySequence(elements)
            val text = (0 until random.nextInt(0, 14)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val matcher = sequence.Matcher(text)
            var start = 0
            while (start <= text.length) {
                assertEquals("start $start of ${text.length}", backtrack(elements, text, 0, start), matcher.matchAt(start))
                if (start == text.length) break
                start = PythonText.next(text, start)
            }
        }
    }

    @Test
    fun `a run of tens of thousands of characters is matched in one pass`() {
        // Digits, then a point, then digits and a percent sign that never comes: every start fails.
        val sequence = GreedySequence(listOf(some(PythonText::isDigit), optional(char('.')), any(PythonText::isDigit), one(char('%'))))
        val text = "1".repeat(50_000)
        val started = System.nanoTime()

        assertEquals(emptyList<String>(), findAll(text, sequence.Matcher(text)::matchAt))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }

    /** The end of a match of [elements] from [index] at [pos], tried as the backtracking engine tries it. */
    private fun backtrack(elements: List<GreedySequence.Element>, text: String, index: Int, pos: Int): Int {
        if (index == elements.size) return pos
        val element = elements[index]
        val ends = arrayListOf(pos)
        while (ends.size - 1 < element.max && ends.last() < text.length) {
            val cp = text.codePointAt(ends.last())
            if (!element.test(cp)) break
            ends += ends.last() + Character.charCount(cp)
        }
        for (count in ends.indices.reversed()) {
            if (count < element.min) break
            val rest = backtrack(elements, text, index + 1, ends[count])
            if (rest >= 0) return rest
        }
        return -1
    }
}
