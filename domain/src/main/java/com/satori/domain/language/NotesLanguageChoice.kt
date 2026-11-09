package com.satori.domain.language

import com.satori.domain.model.NotesLanguage
import java.util.Locale

/**
 * Picks the language the AI writes in for one call (notes, a name, to-dos). Pure: the facts it
 * needs are its arguments, so the rule is the same wherever it is used and easy to test.
 *
 * A text found to be Malayalam is written about in English, whatever the setting and the phone's
 * language: the app doesn't write Malayalam, and the owner reads a Malayalam recording's notes,
 * name, to-dos and map topics in English (his decision, 2026-10-14). The app says so nowhere (his
 * decision of 2026-10-15): Notes language's Auto line says "English or German, as spoken; English
 * otherwise", which is true of it without naming it. Otherwise, with [NotesLanguage.EN] or
 * [NotesLanguage.DE] the setting wins. With [NotesLanguage.AUTO]:
 * - a text clearly in another language ([LanguageDetector.OTHER], [detect]) is written about in
 *   English too, while [OTHER_LANGUAGES_IN_ENGLISH] says so;
 * - else the first of these that is English or German decides: the language found in the text the
 *   call reads, the language already stored on the recording (the first confident result of an
 *   earlier call), the phone's language; and when none of them is, English.
 */
object NotesLanguageChoice {

    /**
     * Whether Auto writes about a text clearly in another language than English or German in
     * English, instead of in the recording's or the phone's language: the owner's decision of
     * 2026-10-16 (question 2 of the 0.7.0 brief, its default: yes). False goes back to the
     * fallbacks for it; then Notes language's Auto line has to say "English or German, whichever
     * was spoken" again, which follows this constant.
     */
    const val OTHER_LANGUAGES_IN_ENGLISH = true

    /** The languages the app writes in. */
    private val SUPPORTED = setOf(LanguageDetector.ENGLISH, LanguageDetector.GERMAN)

    /**
     * The language of [text] as the choice reads it: what [LanguageDetector.detect] finds, or,
     * when it is unsure, [LanguageDetector.OTHER] for a text clearly in another language
     * ([LanguageDetector.isOtherLanguage]) while [OTHER_LANGUAGES_IN_ENGLISH] says so, else null.
     */
    fun detect(text: String): String? =
        LanguageDetector.detect(text)
            ?: LanguageDetector.OTHER.takeIf { OTHER_LANGUAGES_IN_ENGLISH && LanguageDetector.isOtherLanguage(text) }

    /**
     * @param setting What the user chose in Settings
     * @param detected What [detect] found in the text, or null if it wasn't sure;
     *   [LanguageDetector.MALAYALAM] gives English, and so does [LanguageDetector.OTHER] with Auto
     * @param stored The language stored on the recording, if any; anything but "en" or "de" is ignored
     * @param device The phone's language code; anything but "en" or "de" is ignored
     * @return "en" or "de"
     */
    fun choose(setting: NotesLanguage, detected: String?, stored: String?, device: String?): String {
        if (detected?.lowercase(Locale.ROOT) == LanguageDetector.MALAYALAM) return LanguageDetector.ENGLISH
        setting.code?.let { return it }
        if (detected == LanguageDetector.OTHER && OTHER_LANGUAGES_IN_ENGLISH) return LanguageDetector.ENGLISH
        return listOf(detected, stored, device)
            .mapNotNull { it?.lowercase(Locale.ROOT) }
            .firstOrNull { it in SUPPORTED }
            ?: LanguageDetector.ENGLISH
    }

    /**
     * Whether the app writes in [code] ("en", "de"): the only languages a recording stores as its
     * notes' language.
     */
    fun writesIn(code: String?): Boolean = code?.lowercase(Locale.ROOT) in SUPPORTED
}
