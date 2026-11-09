package com.satori.domain.usecase.llm

import com.satori.domain.language.WordClasses
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Whether a piece of the model's answer (a point of a note, a note's title or action item, a
 * recording's name, a to-do or an idea) repeats the instructions of its own prompt instead of
 * saying something of what was said. On 2026-10-16 the 1B began a Malayalam recording's notes with
 * "Text is Malayalam." and "Malayalam language used.", the note on the language in its user message
 * said back, and stored "Malayalam text is copied as it is written there, at most 8 words", the
 * to-do prompt's order on the "said" field, as an open to-do.
 *
 * The instructions are the lines of the call's user message that are no part of what was said:
 * the line of instructions ([TextLanguageNote]), the topic's sentence without the topic and the
 * lead-in ([NotesTopic.Prompt.instructions]). Not what the recording holds (its topic, the to-dos
 * already noted). The system prompt, whose words a real point may share ("Speech recognition
 * misheard the word", feedback on the app), is held to rules of its own since 0.7.3
 * ([PromptRules]). With no lines, nothing is an echo here: an English or German recording's notes
 * and to-dos have none, but under a topic or on a notes call's second try, which also counts the
 * notes' language's name ([NotesTopic.Prompt.instructions]).
 *
 * A text is an echo of them when:
 * - at least [MIN_SHARE] of its content words, and one, are words of the instructions, all lines
 *   taken together, as the 1B joins two of them in one item ("Malayalam" of the order to read it,
 *   "copied" of the one on "said"). Content words leave out the small words of English and German,
 *   numbers, the words that only speak of a text, its language or its answer ([TEXT_WORDS]: "text",
 *   "language", "used", "words", "written"), and those that name a recording's medium
 *   ([MEDIUM_WORDS]: "recording", "recorded", "audio", "speech", "transcript", "notes"), which a
 *   real point says something besides: "Text is Malayalam", "Recorded in Malayalam" and "Malayalam
 *   audio" are all instruction, "Malayalam film festival on Saturday" is not. An abbreviation of
 *   two capitals ("AI", "KI", "TV") is a content word, though other words of two letters are not;
 * - or it is only such words and small words, and says nothing of a medium: words about the text
 *   alone ("What was said", "The text is written in words"), one word of the medium alone
 *   ("Recording", "Notes"), or words that point at the recording itself ([pointsAtTheRecording]:
 *   "This is an audio recording", "Transcript of the recording", "Audio recording"); never an order
 *   of a thing to do with them ("Write the letter", "Answer the letters", "Use the notes": [isOrder]);
 *   words that say nothing was found ([EMPTY_WORDS]: "unclear", "nothing", "not mentioned") count
 *   as no word, so "The subject is unclear" under the order to write about the subject is one, and
 *   a text of them alone ("Nothing.") is one too;
 * - or it holds [MIN_RUN] words in a row of one line of the instructions, two of them more than
 *   small words or numbers ("the topic of the text").
 *
 * What a text of only such words says of a medium is a subject, not an echo: "AI writes the notes",
 * "Speech to text", "Notes from recordings", "Audio recording and transcription". On 2026-10-17 the
 * owner described a voice-notes app in a Malayalam note, an app that records meetings and writes
 * notes; until 0.7.2 its points of that kind went as echoes of the order to read the Malayalam,
 * which every Malayalam recording's call has, as an English or German recording's has when its
 * notes are in the other language, and every Rewrite notes under the recording's name. What only
 * says that this is a recording stays an echo: the review of 0.7.2 found a junk transcript's second
 * answer ("This is an audio recording.", "Transcript of the recording.") kept as its note.
 *
 * Words are compared folded (case and accents; Malayalam as written), as written and with their
 * English endings off ("copied" is "copy"), and one counts as the other when it starts with it
 * ("noted", "note"). Pure Kotlin, no I/O.
 */
object PromptEcho {

    /** The share of a text's content words that, found in the instructions, make it an echo. */
    const val MIN_SHARE = 0.75

    /** Words in a row of one instruction line that make a text an echo, whatever else it says. */
    const val MIN_RUN = 4

    /** Of those, the words that must be more than small words ([STOP_WORDS]). */
    private const val RUN_WORDS = 2

    /** The fewest letters of a word that counts as another when it starts it, and the most it may lack. */
    private const val MIN_PREFIX = 4
    private const val MAX_PREFIX_GAP = 3

    private val WORD = Regex("""[\p{L}\p{M}\p{N}']+""")
    private val MARKS = Regex("""\p{M}+""")
    private val MALAYALAM_BLOCK = 0x0D00..0x0D7F

    // An abbreviation of two capitals as written ("AI", "KI", "TV"), before the words are folded.
    private val ABBREVIATION = Regex("""(?<![\p{L}\p{M}\p{N}'])\p{Lu}{2}(?![\p{L}\p{M}\p{N}'])""")

    /** Whether [text] repeats [instructions] by the rules above; never with no instructions. */
    fun isEcho(text: String, instructions: List<String>): Boolean {
        if (instructions.isEmpty()) return false
        val said = wordsOf(text)
        if (said.isEmpty()) return false
        val mine = contentOf(text, said)
        // A copy of a line's words in a row is an echo also when they are all words of the text or
        // the medium: "never about this recording, its text or its language" of the order a call
        // asked again has.
        if (mine.isEmpty()) return (speaksOnlyOfTheText(said) && !isOrder(said, text)) || instructions.any { sharesRun(said, wordsOf(it)) }
        val theirs = instructions.flatMap(::wordsOf).filter(::isContent).distinct()
        val found = mine.count { word -> theirs.any { same(word, it) } }
        if (found >= ceil(mine.size * MIN_SHARE).toInt()) return true
        return instructions.any { sharesRun(said, wordsOf(it)) }
    }

    /** The content words of [text], whose words are [said]: those of [isContent], and its abbreviations ([abbreviationsIn]). */
    internal fun contentOf(text: String, said: List<String>): List<String> {
        val abbreviations = abbreviationsIn(text)
        return said.filter { isContent(it) || it in abbreviations }
    }

    /**
     * Whether [said], words of nothing but [TEXT_WORDS], [MEDIUM_WORDS], [EMPTY_WORDS] and small
     * words, says nothing but that there is a text: words about the text, its language or its
     * answer alone ("What was said"), one word of the medium alone ("Notes"), words that point at
     * the recording itself ([pointsAtTheRecording]), or words that say nothing was found alone
     * ("Nothing.", "Unclear."). Two words of them, one a word of the medium, say something of it
     * otherwise, which a recording about recordings, notes or speech is about ("AI writes the
     * notes", "Speech to text", "Notes from recordings").
     */
    internal fun speaksOnlyOfTheText(said: List<String>): Boolean {
        val named = said.filter { it in TEXT_WORDS || it in MEDIUM_WORDS }
        if (named.isEmpty()) return said.any { it in EMPTY_WORDS }
        if (named.none { it in MEDIUM_WORDS }) return true
        if (pointsAtTheRecording(said)) return true
        // Twice, so "recordings" is "record" as "recording" and "recorded" are.
        return named.map { stem(stem(it)) }.distinct().size < 2
    }

    /**
     * Whether [said], words of nothing but [TEXT_WORDS], [MEDIUM_WORDS], [EMPTY_WORDS] and small
     * words, points at the recording itself rather than saying something of recordings: it says
     * "this" ("This is an audio recording", "Diese Aufnahme"), it has one word of the medium after an
     * article ("Transcript of the recording", "Notes from the audio", "A recording of speech",
     * "Notizen der Aufnahme"), or it is words of the medium alone that name one of them ("Audio
     * recording", "Speech recording", "Recorded audio"), or it says what was done to it, with a
     * past participle of the medium ([DONE_TO_IT]: "Voice recording transcribed", "Notes recorded",
     * "Aufnahme transkribiert"). Plural and feature phrasings say something of recordings: "Notes
     * from recordings", "Speech to text", "Audio recording and transcription", "Voice notes",
     * "Records conversations". On 2026-10-19 the 1B's answer to the 11:31 note, run again on 0.7.2,
     * had "Note: Voice recording transcribed" for a point, which 0.7.2 kept as a phrasing of the
     * subject.
     */
    private fun pointsAtTheRecording(said: List<String>): Boolean {
        if (said.any { it in DEMONSTRATIVES || it in DONE_TO_IT }) return true
        if (said.zipWithNext().any { (before, word) -> before in ARTICLES && word in ONE_OF_THE_MEDIUM }) return true
        return said.size >= 2 && said.all { it in MEDIUM_WORDS } && said.last() in ONE_OF_THE_MEDIUM
    }

    /**
     * The abbreviations of two capitals in [text] ([ABBREVIATION]), folded, that are no small word
     * ("IT", "US", "AM") and no language's code ("EN", "DE", "ML"): "AI" names a thing, as "TV"
     * does, and the 1B writes the app's AI as such ("AI writes the notes").
     */
    internal fun abbreviationsIn(text: String): Set<String> =
        ABBREVIATION.findAll(text).map { fold(it.value) }.filterTo(mutableSetOf()) { it !in STOP_WORDS && it !in LANGUAGE_CODES }

    /**
     * Whether [text], whose words [said] are nothing but [TEXT_WORDS], [MEDIUM_WORDS] and small
     * words, is an order of a thing to do: an English verb of them first with what it is done to
     * after it ("Write the letter", "Answer the letters"), or a German one last ("Den Brief
     * schreiben"). A to-do or a note's action item, not an instruction said back. A first word
     * with a colon after it is a label, no verb ("Note: Audio transcribed").
     */
    internal fun isOrder(said: List<String>, text: String): Boolean =
        said.size >= 2 && ((said.first() in ORDER_VERBS && !LABEL.containsMatchIn(text)) || said.last() in GERMAN_ORDER_VERBS)

    // A first word with a colon after it: "Note: ...", "Notiz: ...". Not \W, which the JVM reads
    // as ASCII only ([WordClasses]).
    private val LABEL = Regex("""^${WordClasses.NOT_A_WORD_CHAR}*[\p{L}\p{M}']+\s*:""")

    /** The words of [text]: Latin ones folded, without an "'s"; Malayalam ones as written (NFC). */
    internal fun wordsOf(text: String): List<String> =
        WORD.findAll(Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFC))
            .map { match ->
                val word = match.value.trim('\'').removeSuffix("'s")
                if (word.any { it.code in MALAYALAM_BLOCK }) word else fold(word)
            }
            .filter { it.isNotEmpty() }
            .toList()

    private fun fold(word: String): String =
        MARKS.replace(Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD), "").replace("ß", "ss")

    internal fun isContent(word: String): Boolean {
        if (word.all { it.isDigit() }) return false
        if (word.any { it.code in MALAYALAM_BLOCK }) return word.codePointCount(0, word.length) >= 2
        return word.length >= 3 && word !in STOP_WORDS && word !in TEXT_WORDS && word !in MEDIUM_WORDS && word !in EMPTY_WORDS
    }

    /** Whether two folded words are one: alike as written or without their English endings, or one the start of the other. */
    internal fun same(a: String, b: String): Boolean = alike(a, b) || alike(stem(a), stem(b))

    private fun alike(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= MIN_PREFIX && long.length - short.length <= MAX_PREFIX_GAP && long.startsWith(short)
    }

    /** [word] without the English ending of a plural, a past or an -ing form: "copied" is "copy", "items" "item". */
    internal fun stem(word: String): String = when {
        word.length > 4 && (word.endsWith("ies") || word.endsWith("ied")) -> word.dropLast(3) + "y"
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 4 && (word.endsWith("ses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    /**
     * Whether [said] holds [MIN_RUN] words in a row of [line], [RUN_WORDS] of them words of letters
     * that are more than small words: "the topic of the text" counts, "in 2 to 5" doesn't.
     */
    private fun sharesRun(said: List<String>, line: List<String>): Boolean {
        if (said.size < MIN_RUN || line.size < MIN_RUN) return false
        return (0..said.size - MIN_RUN).any { start ->
            val run = said.subList(start, start + MIN_RUN)
            run.count { word -> word.any { it.isLetter() } && word !in STOP_WORDS } >= RUN_WORDS &&
                Collections.indexOfSubList(line, run) >= 0
        }
    }

    /**
     * Words that only speak of a text, its language, its letters or its answer, in English and
     * German, folded: an instruction's words, which a point says something besides. A language's
     * name is none of them: the instructions name the languages, and a point that only names one
     * repeats them.
     */
    internal val TEXT_WORDS: Set<String> = setOf(
        "text", "texts", "texte", "language", "languages", "sprache", "sprachen", "word", "words", "wort", "worte",
        "worter", "letter", "letters", "buchstaben", "script", "scripts", "schrift", "written", "write", "writes",
        "writing", "wrote", "geschrieben", "schreiben", "schreibt", "use", "used", "uses", "using", "answer", "answers",
        "answered", "antwort", "antworten", "say", "says", "said", "saying", "gesagt", "sagt", "sagen", "spoken",
        // A translation, its content, and who speaks it: "Translated from Malayalam to English.",
        // "The speaker speaks Malayalam.", "Malayalam Translation".
        "translate", "translated", "translates", "translating", "translation", "translations", "ubersetzt", "ubersetzen",
        "ubersetzung", "ubersetzungen", "content", "contents", "inhalt", "speaker", "speakers",
        "speaks", "speaking", "sprecher", "sprecherin", "spricht", "passage",
        // How it was said, since the review of 0.7.2: "The speaker talks in Malayalam.", "Auf Englisch gesprochen."
        "talk", "talks", "talked", "talking", "gesprochen"
    )

    /**
     * Words that name a recording's medium, in English and German, folded: its audio, its speech,
     * its voice, its conversation, its transcript, its notes, that it was recorded. Never content
     * words, as [TEXT_WORDS] are not ("Malayalam audio", "Recorded in Malayalam", "Conversation in
     * Malayalam" repeat the instructions); but a text that says something of the medium is about it
     * ([speaksOnlyOfTheText]): a recording about recordings, notes or speech has them for its subject.
     */
    internal val MEDIUM_WORDS: Set<String> = setOf(
        "recording", "recordings", "recorded", "records", "audio", "speech", "transcript", "transcripts",
        "transcription", "transcriptions", "transcribed", "note", "notes", "voice", "conversation", "conversations",
        "aufnahme", "aufnahmen", "aufgenommen", "notiz", "notizen", "transkript", "transkripte", "transkription",
        "transkriptionen", "gesprach", "gesprache", "gesprachs", "unterhaltung", "unterhaltungen", "audioaufnahme",
        "audioaufnahmen", "sprachaufnahme", "sprachaufnahmen", "sprachnotiz", "sprachnotizen", "transkribiert",
        "aufgezeichnet"
    )

    /**
     * The past participles of [MEDIUM_WORDS], folded: what was done to the recording itself
     * ([pointsAtTheRecording]), never a feature of recordings ("Records meetings").
     */
    private val DONE_TO_IT = setOf("recorded", "transcribed", "aufgenommen", "transkribiert", "aufgezeichnet")

    /**
     * Words that say nothing was found, folded: a small model's answer when it reads nothing it can
     * name ("The subject is unclear.", "Subject not mentioned.", "The speaker talks about
     * something.", "Das Thema ist unklar."). No content words, so such a point under the order to
     * write about the subject is made of the order's words.
     */
    private val EMPTY_WORDS = setOf(
        "nothing", "none", "something", "anything", "unclear", "unknown", "mentioned",
        "nichts", "etwas", "unklar", "unbekannt", "erwahnt"
    )

    /** "This" and its German forms, folded: what points at the recording itself ([pointsAtTheRecording]). */
    private val DEMONSTRATIVES = setOf("this", "these", "diese", "dieser", "dieses", "diesen", "diesem")

    /** The articles of English and German, folded, before one word of the medium ([pointsAtTheRecording]). */
    private val ARTICLES = setOf("the", "a", "an", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines")

    /** The words of the medium and the text in the singular, folded: "the recording", not "the recordings" ([pointsAtTheRecording]). */
    private val ONE_OF_THE_MEDIUM = setOf(
        "recording", "audio", "speech", "transcript", "transcription", "note", "voice", "conversation", "text",
        "aufnahme", "notiz", "transkript", "transkription", "gesprach", "unterhaltung", "audioaufnahme", "sprachaufnahme",
        "sprachnotiz", "textes"
    )

    /** The codes of the languages the instructions name, which no abbreviation stands for ([abbreviationsIn]). */
    private val LANGUAGE_CODES = setOf("en", "de", "ml")

    /** Of [TEXT_WORDS] and [MEDIUM_WORDS], the English verbs an order of a thing to do may start with ([isOrder]). */
    private val ORDER_VERBS = setOf("write", "answer", "use", "say", "note", "record", "text", "translate", "transcribe", "speak")

    /** Of [TEXT_WORDS], German infinitives an order ends on ([isOrder]), folded: "Den Brief schreiben". */
    private val GERMAN_ORDER_VERBS = setOf("schreiben", "antworten", "sagen", "ubersetzen")

    // The small words of English and German, folded like the words.
    internal val STOP_WORDS: Set<String> = (
        "the and but nor yet for with from into onto about than then there here this that these those its are was " +
            "were been being has have had having does did done will would shall should can could may might must not " +
            "never all any some each every both either neither such same other own very just only also even still too " +
            "out over under again more most much many few less least one you your yours him his her hers our ours they " +
            "them their theirs she who whom whose which what when where why how while whether below above before " +
            "after per via let lets " +
            "der die das den dem des ein eine einen einem einer eines und oder aber auch noch nur schon sehr mal nicht " +
            "kein keine keinen dass wenn weil als wie ich mich mir wir uns ihr ihm ihn sie sein seine man dich dir " +
            "mein meine unser dein dieser diese dieses diesen diesem jetzt dann hier dort was wer auf aus bei beim mit nach " +
            "von vom zum zur fur uber unter vor bis gegen ohne ist sind war waren bin hat haben habe wird werden kann " +
            "konnen muss soll nie unten oben wurde wurden worden gewesen hatte hatten " +
            "a an to of in on at by as is it be am do or so up if no my me we us he i"
        ).split(' ').filter { it.isNotEmpty() }.toSet()
}
