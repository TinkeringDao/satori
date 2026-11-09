package com.satori.domain.usecase.llm

import com.satori.domain.language.LanguageDetector
import kotlin.math.ceil

/**
 * How much of a transcript the AI's prompts take in, by what its script costs in tokens. The caps
 * on what a call reads are in characters, set for English and German against the model's
 * 4,096-token context: a final note reads at most [GenerateFinalInsightUseCase.MAX_USER_PROMPT_CHARS],
 * a part of the map-reduce pass 5,000, a to-do call [com.satori.domain.usecase.todo.TodoPrompt.MAX_WINDOW_CHARS],
 * a name 3,000.
 *
 * Malayalam costs Gemma 4 about 1.8 times the tokens of English for the same content: 12,928
 * against 7,206 on 261 parallel FLEURS sentences, 3.38 tokens per Malayalam word (ggml-vocab-gemma-4,
 * 2026-10-14). Its words are longer too, so per character the gap is smaller: 2.93 characters a
 * token against 4.72 for English, and a cap set for English holds 0.62 times as many characters of
 * Malayalam for the same tokens. [MALAYALAM_SHARE] rounds that down. A Malayalam transcript so
 * holds fewer words in each prompt, its tail goes to the map-reduce pass sooner, and the pass
 * cuts it into smaller parts.
 *
 * The answers are another thing: a Malayalam recording's notes, name, to-dos and topics are
 * written in English (the owner's decision, 2026-10-14), so the caps on what the model writes stay
 * as they are.
 */
object TextBudget {

    /**
     * The share of a cap's characters a text mostly in Malayalam script may take: its characters
     * cost 4.72 / 2.93 = 1.61 times the tokens of English ones, so 0.62, rounded down.
     */
    const val MALAYALAM_SHARE = 0.6

    /**
     * How many characters of [text] a cap of [cap] characters, set for English and German, holds:
     * [cap] itself, or [MALAYALAM_SHARE] of it for a text mostly in Malayalam script
     * ([LanguageDetector.isMalayalam]).
     */
    fun chars(cap: Int, text: String): Int =
        if (LanguageDetector.isMalayalam(text)) (cap * MALAYALAM_SHARE).toInt() else cap

    /**
     * What [text] costs against a cap in characters set for English and German: its length, or
     * its length divided by [MALAYALAM_SHARE], rounded up, for a text mostly in Malayalam script.
     * Summed line by line, so a recording switched between the languages while it was recorded
     * pays for each line in its own script.
     */
    fun cost(text: String): Int =
        if (LanguageDetector.isMalayalam(text)) ceil(text.length / MALAYALAM_SHARE).toInt() else text.length
}
