package com.satori.domain.language

/**
 * Regex classes that the phone and the unit tests read alike. Android's regex engine is ICU, the
 * JVM's tests run java.util.regex, and the two read `\w`, `\W` and `\b` differently: ICU by
 * Unicode, so an umlaut, a Malayalam letter and its vowel signs are a word's; the JVM (JDK 17)
 * reads `\w` and `\W` by ASCII, and `\b` by letters and digits. On the JVM `^\W*` takes the "Ü" of
 * "Übersicht:" and leaves "bersicht", on the phone it takes nothing (0.7.4's audit of the app's
 * regular expressions; docs/DEV_SETUP.md). A pattern says what it means with these, or with
 * `\p{...}` classes, which both read by Unicode.
 */
object WordClasses {
    /**
     * ICU's `\W` spelled out, so the JVM reads it as the phone does: anything but a letter or
     * another alphabetic character, a mark, a digit, a connector ("_") or a joiner (ZWNJ, ZWJ). On
     * the emulator it matches the very characters ICU's `\W` does, every code point, also with
     * IGNORE_CASE.
     */
    const val NOT_A_WORD_CHAR = """[^\p{IsAlphabetic}\p{M}\p{Nd}\p{Pc}\u200C\u200D]"""
}
