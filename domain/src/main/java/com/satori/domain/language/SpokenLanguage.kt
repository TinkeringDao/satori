package com.satori.domain.language

import com.satori.domain.model.TranscriptionSegment

/**
 * The language spoken in a recording, as its transcript tells it. A speech model that knows only
 * one language (Parakeet v2) or is told the language (Cohere Transcribe) decodes the whole
 * recording in it, for Transcribe again and for the transcript after Stop. So this answers only
 * when the recording is in one language throughout: [LanguageDetector] is sure of the whole
 * transcript, and sure of no stretch of it in the other language. A recording in both gets no
 * answer, and only Parakeet v3, which finds the language of each line itself, is used for it.
 *
 * The language the notes stored on the recording is no stand-in. It was found in one part of the
 * transcript, so where the whole is unsure it most often names one language of a mixed recording.
 */
object SpokenLanguage {

    /**
     * Lines are judged together in stretches of at least this many words, about ten seconds of
     * speech: enough for [LanguageDetector] to be sure, while a German name or phrase among
     * English lines stays too short to count.
     */
    const val STRETCH_WORDS = 20

    private val WHITESPACE = Regex("\\s+")

    /**
     * @param segments The recording's lines; only complete ones count
     * @return "en", "de" or "ml" (a transcript mostly in Malayalam script), or null when the
     *   recording isn't clearly in one language
     */
    fun of(segments: List<TranscriptionSegment>): String? {
        val lines = segments.filter { it.isComplete }.sortedBy { it.timestamp }.map { it.text.trim() }
        val whole = LanguageDetector.detect(lines.joinToString(" ")) ?: return null
        val otherStretch = stretches(lines).any { stretch ->
            LanguageDetector.detect(stretch).let { it != null && it != whole }
        }
        return whole.takeUnless { otherStretch }
    }

    /**
     * [lines] joined in order into stretches of at least [STRETCH_WORDS] words. A last one that
     * is shorter goes with the one before it, as it is too short to judge alone.
     */
    private fun stretches(lines: List<String>): List<String> {
        val stretches = mutableListOf<MutableList<String>>()
        var words = STRETCH_WORDS
        for (line in lines) {
            if (words >= STRETCH_WORDS) {
                stretches += mutableListOf<String>()
                words = 0
            }
            stretches.last() += line
            words += line.split(WHITESPACE).count { it.isNotEmpty() }
        }
        if (words < STRETCH_WORDS && stretches.size > 1) {
            val short = stretches.removeAt(stretches.lastIndex)
            stretches.last() += short
        }
        return stretches.map { it.joinToString(" ") }
    }
}
