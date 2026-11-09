package com.satori.presentation.main

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment

/**
 * What the recorder says of a Translate recording's languages: the one that goes in, as
 * [LanguageDetector] tells it from the newest finished lines ([spoken], null until it is sure), and
 * the one that comes out ([target], "en" or "de"). Speech in the language it is translated into is
 * repeated, not translated ([same]): the owner sees that at once, not after Stop, also when the
 * speaker changes to it later on.
 */
data class TranslationLanguages(val spoken: String?, val target: String) {

    /** The speech is in the language it is translated into. */
    val same: Boolean
        get() = spoken == target

    companion object {
        /**
         * The most of the newest finished lines read for the language: some 300 words, plenty for
         * the detector to be sure, and the same small cost each time a line comes, on the main
         * thread, however long the recording (its whole transcript grew with every line).
         */
        const val MAX_CHARS = 2_000

        /**
         * The languages of a recording whose finished lines are among [lines], in spoken order,
         * translated into [target]: what its newest finished lines, up to [MAX_CHARS], are in.
         */
        fun of(lines: List<TranscriptionSegment>, target: String): TranslationLanguages {
            val newest = ArrayList<String>()
            var chars = 0
            for (line in lines.asReversed()) {
                if (!line.isComplete) continue
                newest += line.text
                chars += line.text.length + 1
                if (chars >= MAX_CHARS) break
            }
            return TranslationLanguages(LanguageDetector.detect(newest.asReversed().joinToString(" ")), target)
        }
    }
}

/**
 * The newest translation of a Translate recording ([text]), with the lines it was translated from
 * ([source], empty when none of them is on screen), for the cover screen, whose recorder has no
 * room for tabs: each translation reads finished lines, each once, and cites them.
 */
data class LatestTranslation(val text: String, val source: String) {

    companion object {
        /** The newest of [translations] with something in it, and its lines among [lines]; null when there is none. */
        fun of(translations: List<LlmInsight>, lines: List<TranscriptionSegment>): LatestTranslation? {
            val newest = translations.filter { it.content.isNotBlank() }.maxByOrNull { it.timestamp } ?: return null
            val byId = lines.associateBy { it.id }
            val source = newest.sourceSegmentIds.mapNotNull { byId[it]?.text?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            return LatestTranslation(newest.content.trim(), source)
        }
    }
}
