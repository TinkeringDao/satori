package com.satori.domain.reading

/**
 * A regular expression made of single-character classes in a row, each repeated a number of
 * times, matched the way Python's `regex` module matches one from a given start: every repeat
 * takes as many characters as it can and gives them back one at a time only when the rest fails.
 * IndicProcessor's e-mail, number and tag patterns have this shape, so they run as data here,
 * without a regular expression engine.
 *
 * A [Matcher] remembers, for its text, where the rest of the pattern ends from each element and
 * position (that does not depend on where the match began). A repeat without an upper bound
 * also shares its work along a run of characters of its class: every start inside the run ends
 * the run at the same place, and gives characters back towards the same last place where the
 * rest matches. So a search over the whole text costs the elements times the text, also on a
 * line of 20,000 digits.
 */
internal class GreedySequence(private val elements: List<Element>) {

    /** One character class ([test] on a code point), repeated [min] to [max] times. */
    class Element(val min: Int, val max: Int, val test: (Int) -> Boolean)

    /** Matches on [text]; its [matchAt] calls share what they find out. */
    inner class Matcher(private val text: String) {

        private val stride = text.length + 1
        private val memo = IntArray((elements.size + 1) * stride) { UNKNOWN }

        /** Per repeat without an upper bound: where its run of characters ends, from each position. */
        private val runEnds = arrayOfNulls<IntArray>(elements.size)

        /** Per repeat without an upper bound: by a run's end, the last place in the run where the rest matches. */
        private val lastMatches = arrayOfNulls<IntArray>(elements.size)

        /** Where the match that starts at [start] ends, or -1 if none does. */
        fun matchAt(start: Int): Int = from(0, start)

        private fun from(index: Int, pos: Int): Int {
            if (index == elements.size) return pos
            val slot = index * stride + pos
            if (memo[slot] != UNKNOWN) return memo[slot]
            val element = elements[index]
            val result = if (element.max == MANY) unbounded(index, element, pos) else bounded(index, element, pos)
            memo[slot] = result
            return result
        }

        /** A repeat of at most [Element.max]: as many as fit, then one fewer at a time. */
        private fun bounded(index: Int, element: Element, pos: Int): Int {
            var count = 0
            var end = pos
            while (count < element.max && end < text.length) {
                val cp = text.codePointAt(end)
                if (!element.test(cp)) break
                end += Character.charCount(cp)
                count++
            }
            if (count < element.min) return -1
            while (true) {
                val rest = from(index + 1, end)
                if (rest >= 0) return rest
                if (count == element.min) return -1
                end -= Character.charCount(text.codePointBefore(end))
                count--
            }
        }

        /**
         * A repeat without an upper bound, which the backtracking engine runs to the end of the
         * run and gives back towards its [Element.min]-th character: the first place it tries
         * where the rest matches is the run's last such place, if that is not before the minimum.
         */
        private fun unbounded(index: Int, element: Element, pos: Int): Int {
            val runEnd = runEnds(index, element)[pos]
            var least = pos
            repeat(element.min) {
                if (least >= runEnd) return -1
                least += Character.charCount(text.codePointAt(least))
            }
            val last = lastMatch(index, element, runEnd)
            return if (last >= least) from(index + 1, last) else -1
        }

        /** Where the run of [element]'s class that starts at each position ends, from right to left. */
        private fun runEnds(index: Int, element: Element): IntArray {
            runEnds[index]?.let { return it }
            val ends = IntArray(stride)
            ends[text.length] = text.length
            var after = text.length
            while (after > 0) {
                val cp = text.codePointBefore(after)
                val at = after - Character.charCount(cp)
                ends[at] = if (element.test(cp)) ends[after] else at
                after = at
            }
            runEnds[index] = ends
            return ends
        }

        /**
         * The last place, from [runEnd] back to the start of the run of [element]'s class that
         * ends there, where the rest of the pattern matches; -1 if there is none.
         */
        private fun lastMatch(index: Int, element: Element, runEnd: Int): Int {
            val known = lastMatches[index] ?: IntArray(stride) { UNKNOWN }.also { lastMatches[index] = it }
            if (known[runEnd] != UNKNOWN) return known[runEnd]
            var end = runEnd
            var last = -1
            while (true) {
                if (from(index + 1, end) >= 0) {
                    last = end
                    break
                }
                if (end == 0) break
                val cp = text.codePointBefore(end)
                if (!element.test(cp)) break
                end -= Character.charCount(cp)
            }
            known[runEnd] = last
            return last
        }
    }

    companion object {
        private const val UNKNOWN = -2

        /** As often as the text allows. */
        const val MANY = Int.MAX_VALUE

        fun one(test: (Int) -> Boolean) = Element(1, 1, test)
        fun optional(test: (Int) -> Boolean) = Element(0, 1, test)
        fun some(test: (Int) -> Boolean) = Element(1, MANY, test)
        fun any(test: (Int) -> Boolean) = Element(0, MANY, test)
        fun atLeast(min: Int, test: (Int) -> Boolean) = Element(min, MANY, test)
        fun char(c: Char): (Int) -> Boolean = { it == c.code }
        fun among(chars: String): (Int) -> Boolean = { it < 0x10000 && chars.indexOf(it.toChar()) >= 0 }
    }
}

/**
 * Python's `pattern.findall(text)` for a pattern without groups: the matches from left to right,
 * each search starting where the last match ended, or one code point further where none began.
 * [matchAt] gives the end of the match at a start, or a value at or below the start for none.
 */
internal fun findAll(text: String, matchAt: (Int) -> Int): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val end = matchAt(i)
        if (end > i) {
            out += text.substring(i, end)
            i = end
        } else {
            i = PythonText.next(text, i)
        }
    }
    return out
}
