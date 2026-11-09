package com.satori.domain.language

import java.util.Locale

/**
 * Tells English from German in a transcript, so the AI can write its notes in the language
 * that was spoken ([com.satori.domain.model.NotesLanguage.AUTO]), and a Malayalam
 * transcript from both: a text mostly in Malayalam script is Malayalam ([MALAYALAM]) whatever
 * English or German words are in it, so it is never taken for English.
 *
 * It counts small function words that only one of the two languages has ("the", "and",
 * "und", "nicht"), and spelling that gives a word away (ä ö ü ß and the endings "-ung",
 * "-keit" for German; contractions such as "wasn't" for English). Words both languages use
 * ("in", "so", "was", "will", "man", "also") count for neither, and so do letter groups that
 * both spell ("th" in Thema and in think, "sch" in Schmidt and in schedule).
 *
 * It answers only when it is sure: at least [MIN_WORDS] words, enough evidence, and one
 * language well ahead of the other. Otherwise it returns null and the caller keeps its
 * fallback, so a short or evenly mixed text never flips the language. Malayalam is told by its
 * script, which no other language Satori hears is written in, so a few words of it are as sure as
 * many ([isMalayalam]). Pure Kotlin, no I/O; fast enough to run on every chunk of text the AI reads.
 */
object LanguageDetector {

    /** "en" */
    const val ENGLISH = "en"

    /** "de" */
    const val GERMAN = "de"

    /** "ml" */
    const val MALAYALAM = "ml"

    /**
     * A text in a language that is neither English, German nor Malayalam ([isOtherLanguage]), as
     * Parakeet v3 writes French or Polish speech in its own language. Not a language code: it is
     * never stored on a recording, and the app never writes in it.
     */
    const val OTHER = "other"

    /** Fewer words than this give no answer. */
    const val MIN_WORDS = 8

    /** The leading language needs at least this many hits. */
    private const val MIN_EVIDENCE = 3

    /** ...at least this many more than the other one... */
    private const val MIN_LEAD = 2

    /** ...and at least this many times as many. */
    private const val MIN_RATIO = 2

    /**
     * The language of [text] as a BCP-47 code: [MALAYALAM] for a text mostly in Malayalam script,
     * else [ENGLISH] or [GERMAN], or null when the text is too short, has too little to go on, or
     * is too evenly mixed to say.
     */
    fun detect(text: String): String? {
        if (isMalayalam(text)) return MALAYALAM
        val words = words(text)
        if (words.size < MIN_WORDS) return null
        var english = 0
        var german = 0
        for (word in words) {
            when (cue(word)) {
                ENGLISH -> english++
                GERMAN -> german++
            }
        }
        val lead = maxOf(english, german)
        val other = minOf(english, german)
        return when {
            lead < MIN_EVIDENCE -> null
            lead - other < MIN_LEAD -> null
            lead < other * MIN_RATIO -> null
            english > german -> ENGLISH
            else -> GERMAN
        }
    }

    /**
     * Whether most of the letters in [text] are Malayalam: the letters and marks of the Malayalam
     * block (a vowel sign or a virama is part of the syllable it is written in, as a Latin letter
     * is of its word) outnumber the letters of all other scripts together. Digits, punctuation and
     * spaces count for neither. Malayalam words are long in letters, so a line of Malayalam with a
     * few English words in Latin letters is still Malayalam, and an English or German text with a
     * Malayalam name in it is not.
     */
    internal fun isMalayalam(text: String): Boolean {
        var malayalam = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                codePoint in MALAYALAM_BLOCK -> if (Character.isLetter(codePoint) || isMark(codePoint)) malayalam++
                Character.isLetter(codePoint) -> other++
            }
        }
        return malayalam > other
    }

    /**
     * Whether [text] is clearly in another language than English, German or Malayalam: mostly in
     * another script than the Latin and the Malayalam one (Cyrillic, Greek, Devanagari, ...), or,
     * in Latin letters, a text of at least [MIN_WORDS] words whose cues of other languages (their
     * small function words, "et", "pour", "niet", "się", and letters neither English nor German
     * writes, é ñ ø ł) lead those of English and of German by the margins [detect] asks of either.
     * As sure as [detect] is, or it says no: a text that is unsure stays with the caller's fallback.
     * Not for a text [detect] finds English or German in, which this is not asked about.
     */
    fun isOtherLanguage(text: String): Boolean {
        if (isMalayalam(text)) return false
        if (isInOtherScript(text)) return true
        val words = words(text)
        if (words.size < MIN_WORDS) return false
        var english = 0
        var german = 0
        var other = 0
        for (word in words) {
            when {
                word in OTHER_WORDS || word.any { it in OTHER_LETTERS } -> other++
                else -> when (cue(word)) {
                    ENGLISH -> english++
                    GERMAN -> german++
                }
            }
        }
        val lead = maxOf(english, german)
        return other >= MIN_EVIDENCE && other - lead >= MIN_LEAD && other >= lead * MIN_RATIO
    }

    /** Whether most letters of [text] are of other scripts than the Latin and the Malayalam one. */
    private fun isInOtherScript(text: String): Boolean {
        var latin = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.MALAYALAM -> latin++
                else -> other++
            }
        }
        return other > latin
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Lower-case words; anything that is not a letter or an apostrophe separates them. */
    internal fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .split(NON_WORD)
            .map { it.trim('\'') }
            .filter { word -> word.any { it.isLetter() } }

    /** The language one word points to, or null if it says nothing. */
    private fun cue(word: String): String? = when {
        word in ENGLISH_WORDS -> ENGLISH
        word in GERMAN_WORDS -> GERMAN
        word.any { it in GERMAN_LETTERS } -> GERMAN
        word.length >= 6 && GERMAN_ENDINGS.any { word.endsWith(it) } -> GERMAN
        '\'' in word && ENGLISH_CONTRACTIONS.any { word.endsWith(it) } -> ENGLISH
        else -> null
    }

    private val NON_WORD = Regex("[^\\p{L}']+")

    /** Unicode's Malayalam block: its letters, vowel signs, virama, chillus and digits. */
    private val MALAYALAM_BLOCK = 0x0D00..0x0D7F

    private val GERMAN_LETTERS = setOf('ä', 'ö', 'ü', 'ß')

    /** "Zeitung", "Freiheit", "Möglichkeit", "natürlich"; never English at six letters or more. */
    private val GERMAN_ENDINGS = listOf("ung", "ungen", "heit", "keit", "lich")

    private val ENGLISH_CONTRACTIONS = listOf("n't", "'re", "'ll", "'ve", "'m", "'d")

    // Words that are common in both languages ("in", "an", "am", "so", "was", "will", "man",
    // "also", "war", "her") or English fillers in transcripts ("er", "um") are in neither list.

    /**
     * Letters of Latin alphabets that neither English nor German writes: French, Spanish,
     * Portuguese, Italian, Dutch, the Nordic and the Slavic languages, Hungarian, Romanian and the
     * Baltic ones, which Parakeet v3 also transcribes.
     */
    private val OTHER_LETTERS = setOf(
        'à', 'á', 'â', 'ã', 'å', 'æ', 'ç', 'è', 'é', 'ê', 'ë', 'ì', 'í', 'î', 'ï', 'ñ', 'ò', 'ó', 'ô', 'õ',
        'ø', 'ù', 'ú', 'û', 'ý', 'ÿ', 'ā', 'ă', 'ą', 'ć', 'č', 'ď', 'ē', 'ė', 'ę', 'ě', 'ģ', 'ī', 'į', 'ķ',
        'ļ', 'ł', 'ń', 'ņ', 'ň', 'ő', 'ř', 'ś', 'ş', 'š', 'ș', 'ț', 'ť', 'ū', 'ů', 'ű', 'ų', 'ź', 'ż', 'ž'
    )

    /**
     * Small function words of the other languages Parakeet v3 transcribes, none of which is an
     * English or a German word: "die", "des", "an", "so", "is", "to", "a", "no", "da", "ja", "on",
     * "du", "do", and words English speech has ("pour", "con", "hay", "yo", "non", "til", "med") or the
     * pieces of an address ("com") are left out for that reason, and so are the German words "nie",
     * "je", "Mai" and "Ei" and spoken German's "ne", "ma", "nu", "och", "se", "dat", "wat" and "ik":
     * a German recording the detector is unsure of must not get English notes. French, Spanish,
     * Italian, Portuguese, Dutch, Swedish, Danish, Polish, Czech, Croatian, Finnish, Hungarian,
     * Romanian.
     */
    private val OTHER_WORDS = setOf(
        // French
        "le", "les", "une", "et", "est", "que", "qui", "pas", "nous", "vous", "sur",
        "avec", "dans", "ce", "cette", "sont", "mais", "elle", "ils", "leur", "il", "au", "aux",
        "c'est", "j'ai", "ou",
        // Spanish, Italian, Portuguese
        "el", "los", "las", "y", "por", "para", "una", "muy", "pero", "como", "del", "lo", "su", "sus",
        "porque", "cuando", "eso", "esto", "che", "di", "sono", "della", "anche", "molto",
        "questo", "gli", "os", "uma", "mas", "muito", "isso",
        // Dutch, Swedish, Danish
        "het", "een", "niet", "zijn", "maar", "ook", "heb", "naar", "voor", "moet",
        "att", "det", "som", "jag", "inte", "har", "og", "ikke", "jeg",
        // Polish, Czech, Croatian
        "jest", "jak", "tak", "ale", "czy", "dla", "jsem", "ani", "aby",
        "kako", "ali",
        // Finnish, Hungarian, Romanian
        "mutta", "kun", "niin", "az", "hogy", "nem", "egy", "vagy", "este", "cu", "pe"
    )

    internal val ENGLISH_WORDS = setOf(
        "a", "about", "after", "again", "all", "and", "any", "anything", "are", "aren't",
        "as", "at", "be", "because", "been", "before", "being", "both", "but", "by",
        "can", "can't", "could", "couldn't", "did", "didn't", "do", "does", "doesn't", "doing",
        "don't", "down", "each", "every", "for", "from", "get", "going", "gonna", "got", "had",
        "has", "have", "haven't", "he", "he's", "here", "him", "his", "how", "i", "i'd", "i'll",
        "i'm", "i've", "if", "into", "is", "isn't", "it", "it's", "its", "just", "know",
        "let's", "like", "many", "me", "more", "much", "my", "no", "not", "now", "of", "off",
        "on", "once", "one", "only", "or", "other", "our", "out", "over", "really", "she",
        "should", "some", "something", "such", "than", "that", "that's", "the", "their",
        "them", "then", "there", "there's", "these", "they", "they're", "this", "those",
        "through", "to", "too", "until", "up", "us", "very", "want", "we", "we're", "well",
        "were", "what", "what's", "when", "where", "which", "while", "who", "why", "with",
        "won't", "would", "wouldn't", "yeah", "yes", "you", "you're", "your"
    )

    internal val GERMAN_WORDS = setOf(
        "aber", "alle", "allem", "allen", "aller", "alles", "als", "auch", "auf", "aus",
        "bei", "beim", "bin", "bis", "bist", "da", "damit", "dann", "das", "dass", "daß",
        "dein", "deine", "dem", "den", "denn", "der", "des", "dich", "die", "dies", "diese",
        "diesem", "diesen", "dieser", "dieses", "dir", "doch", "dort", "du", "durch",
        "eigentlich", "ein", "eine", "einem", "einen", "einer", "eines", "es", "etwas", "euch",
        "für", "ganz", "gegen", "geht", "genau", "gewesen", "gibt", "habe", "haben", "hast",
        "hat", "hatte", "hätte", "ich", "ihm", "ihn", "ihnen", "ihr", "ihre", "im", "immer",
        "ins", "ist", "ja", "jetzt", "kann", "kein", "keine", "können", "mal", "mehr", "mein",
        "meine", "mich", "mir", "mit", "muss", "müssen", "nach", "nicht", "nichts", "noch",
        "nun", "nur", "ob", "oder", "ohne", "schon", "sehr", "sein", "seine", "sich", "sie",
        "sind", "soll", "sollen", "sondern", "über", "und", "uns", "unser", "unsere", "unter",
        "viel", "vom", "von", "vor", "waren", "weil", "wenn", "wer", "werden", "wie", "wieder",
        "wir", "wird", "wo", "wurde", "würde", "zu", "zum", "zur", "zwischen"
    )
}
