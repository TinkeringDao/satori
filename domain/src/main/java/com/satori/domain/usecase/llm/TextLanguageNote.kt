package com.satori.domain.usecase.llm

import com.satori.domain.language.LanguageDetector
import com.satori.domain.provider.ResourceProvider
import java.util.Locale

/**
 * The line of instructions a call's user message holds when the text it reads is in another
 * language than the one it writes in: "Instruction, never to be repeated in your answer: read the
 * Malayalam below and write in English. Read English words written in Malayalam letters as
 * English." A Malayalam recording's notes, name, to-dos and topics are written in English from its
 * Malayalam transcript (the owner's decision, 2026-10-14), and a Translate recording's to-dos in
 * its target language from what was said; the system prompts say which language to write in, but
 * not that the text is in another, and a small model then answers in the language it reads.
 *
 * Every sentence of the line is an order, and the line says it is not to be repeated. None says
 * anything of the text: the note this replaced ("The text is in Malayalam. ...") came back on
 * 2026-10-16 as the first two points of a Malayalam recording's notes, as the 1B writes down what
 * it reads as statements. What an answer still repeats of the line is dropped ([PromptEcho]).
 *
 * The line goes in the user message, never in the system prompt, which stays the same for every
 * recording of a kind and language so the engine can keep its encoded state (lib-llama-android's
 * PromptCache). It is in the language of the answer, from [ResourceProvider]'s sentences.
 */
internal object TextLanguageNote {

    /** The languages the app writes in. */
    private val WRITTEN = setOf(LanguageDetector.ENGLISH, LanguageDetector.GERMAN)

    /**
     * The language of [text] when it is known and isn't [written]: "ml" for a text mostly in
     * Malayalam script, "en" or "de" when [LanguageDetector] is sure; null for a text in [written],
     * one the detector isn't sure of, and when [written] isn't "en" or "de".
     */
    fun spokenOtherThan(text: String, written: String?): String? {
        val answer = answerLanguage(written) ?: return null
        return LanguageDetector.detect(text)?.takeIf { it != answer }
    }

    /**
     * The line for a call that reads [text] and writes in [written] ("en" or "de"), or null when
     * none is needed ([spokenOtherThan]): the orders of [readingOrders].
     */
    fun of(text: String, written: String?, resources: ResourceProvider): String? =
        line(written, readingOrders(text, written, resources), resources)

    /**
     * The line for a recording's name, written in [written] from [text]: the orders of [of], and
     * for a Malayalam recording ([malayalam], whatever [text] is: its transcript, or its notes,
     * which are English) the order to name its topic in 2 to 5 words
     * ([ResourceProvider.getTitleTopicSentence]). Null when there is no order to give.
     */
    fun forTitle(text: String, written: String?, malayalam: Boolean, resources: ResourceProvider): String? {
        val answer = answerLanguage(written) ?: return null
        val topic = if (malayalam) resources.getTitleTopicSentence(answer).format(resources.getLanguageName(answer, answer)) else null
        return line(answer, readingOrders(text, answer, resources) + listOfNotNull(topic), resources)
    }

    /**
     * The orders for reading [text] and answering in [written]: to read it in its language and
     * write in the answer's ([ResourceProvider.getReadLanguageSentence]) and, for Malayalam, to
     * read the English words in Malayalam letters as English. None for a text in [written], one of
     * no sure language, or an answer in no language the app writes.
     */
    fun readingOrders(text: String, written: String?, resources: ResourceProvider): List<String> {
        val answer = answerLanguage(written) ?: return emptyList()
        val spoken = spokenOtherThan(text, answer) ?: return emptyList()
        return listOfNotNull(
            resources.getReadLanguageSentence(answer)
                .format(resources.getLanguageName(spoken, answer), resources.getLanguageName(answer, answer)),
            resources.getMalayalamLettersSentence(answer).takeIf { spoken == LanguageDetector.MALAYALAM }
        )
    }

    /**
     * [orders] as one line after the lead ([ResourceProvider.getInstructionLead]), all in the
     * language of [written], the first in lower case after the lead's colon; null without an
     * order, or for an answer in no language the app writes.
     */
    fun line(written: String?, orders: List<String>, resources: ResourceProvider): String? {
        val answer = answerLanguage(written) ?: return null
        if (orders.isEmpty()) return null
        val first = orders.first().replaceFirstChar { it.lowercase(Locale.ROOT) }
        return (listOf(resources.getInstructionLead(answer), first) + orders.drop(1)).joinToString(" ")
    }

    /** [written] as "en" or "de", or null for anything else. */
    private fun answerLanguage(written: String?): String? =
        written?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in WRITTEN }
}
