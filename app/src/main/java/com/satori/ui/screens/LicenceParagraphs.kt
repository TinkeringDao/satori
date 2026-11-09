package com.satori.ui.screens

/**
 * A licence text as the Licences page draws it: the lines of each paragraph joined into one, so
 * the phone wraps them to its own width instead of the source's 80 columns wrapping again into
 * short ragged lines ("software", "permissions and"). A blank line still parts two paragraphs, and
 * a line stays on its own where the text lays it out that way: a heading in capitals ("PREAMBLE"),
 * a rule of dashes, an address and what follows it, an indented line, and an item of a list ("1)",
 * "-").
 */
internal object LicenceParagraphs {

    fun of(text: String): String {
        val lines = text.lines()
        val out = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index == 0) {
                out.append(line.trimEnd())
                return@forEachIndexed
            }
            val previous = lines[index - 1]
            if (joins(previous, line)) {
                out.append(' ').append(line.trim())
            } else {
                out.append('\n').append(line.trimEnd())
            }
        }
        return out.toString()
    }

    private fun joins(previous: String, line: String): Boolean =
        previous.isNotBlank() && line.isNotBlank() &&
            !isHeading(previous) && !isRule(previous) && !isRule(line) &&
            !line.first().isWhitespace() && !previous.first().isWhitespace() &&
            !line.trimStart().startsWith("http") && !endsWithAddress(previous) && !startsItem(line)

    /** A line whose last word is an address, which the text sets apart from what follows. */
    private fun endsWithAddress(line: String): Boolean = line.trim().substringAfterLast(' ').startsWith("http")

    /** A short line in capitals, such as "PREAMBLE" or "PERMISSION & CONDITIONS". */
    private fun isHeading(line: String): Boolean {
        val text = line.trim()
        return text.any { it.isLetter() } && text == text.uppercase() &&
            text.split(' ').size <= MAX_HEADING_WORDS && text.last().isLetter()
    }

    private fun isRule(line: String): Boolean = line.isNotBlank() && line.trim().all { it == '-' || it == '=' }

    private fun startsItem(line: String): Boolean = ITEM.containsMatchIn(line)

    private const val MAX_HEADING_WORDS = 4
    private val ITEM = Regex("^\\s*(\\d+[).]|\\([a-z0-9]+\\)|[-*•])\\s")
}
