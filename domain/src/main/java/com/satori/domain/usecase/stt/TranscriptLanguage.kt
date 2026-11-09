package com.satori.domain.usecase.stt

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.TranscriptionSegment

/**
 * Whether a recording's transcript is in the language the recording is in. Told line by line by
 * the script: the Malayalam model writes Malayalam script only, its English words included, and the
 * English and German models write Latin letters only. A line in the other script was made by a model
 * of another language: before the recording was switched to its language while it recorded, or
 * before its language was changed on its page, which leaves the transcript as it was.
 */
object TranscriptLanguage {

    /**
     * Whether each complete line of [segments] that holds a letter is in [language]'s script:
     * mostly Malayalam ([LanguageDetector.isMalayalam]) for [RecordingLanguage.MALAYALAM], not for
     * English or German. A line without letters, a lone "." of noise say, counts for neither, and
     * a transcript without such lines is in any language.
     */
    fun isIn(language: RecordingLanguage, segments: List<TranscriptionSegment>): Boolean {
        val malayalam = language == RecordingLanguage.MALAYALAM
        return segments.all { line ->
            !line.isComplete || line.text.none(Char::isLetter) || LanguageDetector.isMalayalam(line.text) == malayalam
        }
    }
}
