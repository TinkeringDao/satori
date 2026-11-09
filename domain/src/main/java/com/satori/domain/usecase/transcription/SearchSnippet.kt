package com.satori.domain.usecase.transcription

/** The short excerpt of a line or a note that a search result shows. */
object SearchSnippet {

    /** How much text is kept on each side of the match. */
    private const val HALF_WINDOW = 60

    private const val MAX_LENGTH = 120

    private val BULLET_OR_HEADING = Regex("(?m)^\\s*(?:[-*+\\u2022]\\s+|#{1,6}\\s+)")
    private val EMPHASIS = Regex("\\*+|__|`")
    private val WHITESPACE = Regex("\\s+")

    /**
     * About 120 characters of [text] around the first [query] match, cut at word boundaries (never
     * inside the match). The first 120 characters when the query isn't found.
     *
     * @param markdown [text] is a note: its Markdown marks (bold, bullets, headings) are left out,
     *   so the excerpt reads as plain text and the match isn't hidden behind asterisks
     */
    fun of(text: String, query: String, markdown: Boolean = false): String {
        val plain = if (markdown) plain(text) else text.replace(WHITESPACE, " ").trim()
        val index = plain.indexOf(query, ignoreCase = true)
        if (index < 0) return plain.take(MAX_LENGTH)
        val matchEnd = index + query.length
        var start = maxOf(0, index - HALF_WINDOW)
        var end = minOf(plain.length, matchEnd + HALF_WINDOW)
        if (start > 0) {
            val space = plain.indexOf(' ', start)
            if (space in 0 until index) start = space + 1
        }
        if (end < plain.length) {
            val space = plain.lastIndexOf(' ', end - 1)
            if (space >= matchEnd) end = space
        }
        return plain.substring(start, end).trim()
    }

    /** [markdown] on one line without its formatting marks. */
    fun plain(markdown: String): String =
        markdown.replace(BULLET_OR_HEADING, "")
            .replace(EMPHASIS, "")
            .replace(WHITESPACE, " ")
            .trim()
}
