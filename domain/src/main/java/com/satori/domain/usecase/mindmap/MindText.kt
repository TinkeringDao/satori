package com.satori.domain.usecase.mindmap

import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import kotlinx.coroutines.CancellationException

/**
 * Text rules the mind map's two model calls share ([MindTopics], [ThemeSummaryPrompt]): how what
 * the model reads is put on one line and cut, and which characters count as quotes.
 */
internal object MindText {

    /** Straight and typographic quotes, English and German, stripped from the ends of a model's text. */
    const val QUOTES = "\"'`“”„‟«»‚‘’‹›"

    /** Marks a text that was cut. */
    const val ELLIPSIS = "…"

    private val SPACES = Regex("""\s+""")

    /** [text] on one line: every run of whitespace, line breaks too, as one space, and trimmed. */
    fun oneLine(text: String): String = SPACES.replace(text, " ").trim()

    /** [text] cut to [max] characters at the last space before it; a text without one is cut hard. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val space = text.lastIndexOf(' ', startIndex = max)
        return text.substring(0, if (space > 0) space else max).trim()
    }
}

/**
 * Loads the model if it isn't loaded, and returns its whole answer to one call held to [grammar];
 * null when the model can't be loaded or run. Only cancellation is thrown. Both of the mind map's
 * calls end their run on null, since every call after it would fail the same way.
 */
internal suspend fun LlmRepository.answerOrNull(
    text: String,
    systemPrompt: String,
    maxTokens: Int,
    grammar: OutputGrammar
): String? =
    try {
        if (reloadModel().isFailure) {
            null
        } else {
            val answer = StringBuilder()
            generateInsight(text, systemPrompt, maxTokens, grammar).collect { answer.append(it) }
            answer.toString()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
