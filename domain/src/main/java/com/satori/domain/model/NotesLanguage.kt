package com.satori.domain.model

/**
 * The language the AI writes notes, summaries and recording names in.
 *
 * - [AUTO]: the language of what was said, found by
 *   [com.satori.domain.language.LanguageDetector] from the text each AI call reads.
 * - [EN], [DE]: always English or always German.
 *
 * Translation is separate: it always writes in [AppSettings.translationTargetLanguage].
 *
 * @property code BCP-47 code as stored on recordings ("en", "de"); null for [AUTO].
 */
enum class NotesLanguage(val code: String?) {
    AUTO(null),
    EN("en"),
    DE("de");

    companion object {
        /** The value stored under [name], or [AUTO] for anything unknown. */
        fun fromName(name: String?): NotesLanguage = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
