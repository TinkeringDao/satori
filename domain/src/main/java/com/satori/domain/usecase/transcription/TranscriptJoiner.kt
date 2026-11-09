package com.satori.domain.usecase.transcription

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.VadParameters
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import java.util.Locale

/**
 * Joins transcript lines the speech recognizer cut in the middle of a sentence, so the transcript,
 * the notes and the to-dos read whole sentences. The voice detector ends a line at every short
 * pause and at its longest stretch of speech, and the recognizer writes each line as a sentence of
 * its own, with a capital at its start and often a full stop at its end ("The bus was late
 * again." / "because of the snow.").
 *
 * Lines are read in spoken order, and a line B joins onto the line A before it (A may be lines
 * joined already) when all of these hold:
 * - B began at most [MAX_JOIN_GAP_MS] after A ended: by their places in the audio, or without
 *   them by their stamps, less the time it took to say B at [SPOKEN_MS_PER_CHAR], as the to-do
 *   prompt reckons a pause between two stamps;
 * - A and B were said in one capture of the recording ([Line.capture]), B not before A began;
 * - the joined line is at most [MAX_LINE_MS] long and [MAX_LINE_CHARS] characters;
 * - A doesn't end with "?" or "!", which end a sentence for sure;
 * - A and B are cited by the same notes. A recording's last note is written from the lines no
 *   note cites, so an uncited line joined onto a cited one would never get into a note;
 * - both hold a word ([DiscardSilentRecordingUseCase.holdsSpeech]): a lone "." is a noise;
 * - a signal says the sentence goes on ([Reason]).
 *
 * For every signal but [Reason.OPEN], A's final "." goes. B's first word is written small only
 * when it is one of a closed list of common words ([SMALL_WORDS]), so a name keeps its capital.
 *
 * A line in Malayalam script ([LanguageDetector.isMalayalam]) has no punctuation and no capitals:
 * the Malayalam speech model writes neither, so a missing final mark says nothing of where its
 * sentence ends, and [Reason.OPEN] would join every such line to the next up to the limits above.
 * It goes on in the next one only where the voice detector cut it at its longest stretch of speech
 * ([Reason.FORCED]); the other signals are English and German words. Read line by line, so the
 * English lines of a recording switched to Malayalam while it was recorded are joined as before.
 */
object TranscriptJoiner {

    /** The longest pause between two lines of one sentence: longer ones end it. */
    const val MAX_JOIN_GAP_MS = 1_500L

    /** The longest a joined line may be: tapping a line plays it from its start. */
    const val MAX_LINE_MS = 20_000L

    /** The most characters a joined line may have. */
    const val MAX_LINE_CHARS = 400

    /**
     * How close to the voice detector's longest stretch of speech a line has to run to count as
     * cut there ([Reason.FORCED]): the detector cuts at the first short dip once it is reached.
     */
    const val FORCED_CUT_MARGIN_MS = 300L

    /**
     * How long a character takes to say when the time between two stamps is read as a pause:
     * brisk speech, so the pause comes out no shorter than it was (as TodoPrompt.goesOn).
     */
    const val SPOKEN_MS_PER_CHAR = 50L

    /** What told that a sentence goes on into the next line; the first that holds is counted. */
    enum class Reason {
        /** A has no final ".", "!" or "?": the recognizer left its sentence open. */
        OPEN,

        /** B starts with a small letter: the recognizer heard the sentence go on. */
        LOWERCASE,

        /** A ends on a word no sentence ends on ([DANGLING]): "...because we need the." */
        DANGLING,

        /**
         * A ran to within [FORCED_CUT_MARGIN_MS] of the voice detector's longest stretch of
         * speech: it was cut there, not at a pause, wherever the sentence was. Read from the place
         * of the piece the detector cut ([Line.lastPiece]), so it needs one.
         */
        FORCED,

        /**
         * B starts with a word that carries a sentence on ([CONTINUERS]) and isn't a question of
         * its own ("Which one?").
         */
        CONTINUER
    }

    /**
     * A transcript line as the joiner reads it.
     *
     * @property span Where it was said in the recording's audio; null when the recording kept no
     *   audio or its index lost the line
     * @property stamp When it was given out (wall clock): at its end, after the pause that ended it
     * @property citedBy The ids of the notes that cite it
     * @property lastPiece Where the last of the lines it is made of was said: [span] for a line as
     *   the recognizer gave it out, the place of its last piece for one an earlier run joined. The
     *   voice detector cut only that piece, so [Reason.FORCED] reads it.
     * @property capture Which capture of the recording it was said in ([captureAt]); null when
     *   that isn't known. A recording started again after an error goes on in the audio of the
     *   capture before, so the places of two lines of different captures don't tell the time
     *   between them.
     */
    data class Line(
        val id: String,
        val text: String,
        val span: AudioSpan?,
        val stamp: Long,
        val citedBy: Set<String> = emptySet(),
        val lastPiece: AudioSpan? = span,
        val capture: Int? = null
    )

    /**
     * Lines that read as one, in spoken order. They are stored as one line with the first one's
     * id, [text], the last one's stamp and [span]. A group of one line keeps that line's text as it
     * was.
     */
    data class Group(val lines: List<Line>, val text: String) {
        val id: String get() = lines.first().id
        val stamp: Long get() = lines.last().stamp

        /**
         * From the start of the first line to the end of the last, as far as their places are
         * known; null when none is.
         */
        val span: AudioSpan?
            get() {
                val known = lines.mapNotNull { it.span }
                if (known.isEmpty()) return null
                return AudioSpan(known.minOf { it.startMs }, known.maxOf { it.endMs })
            }
    }

    /**
     * What [join] made of a transcript: its [groups] in spoken order, and how many joins each
     * [Reason] made, every reason listed.
     */
    data class Joined(val groups: List<Group>, val reasons: Map<Reason, Int>) {
        /** How many lines came in. */
        val lineCount: Int get() = groups.sumOf { it.lines.size }

        /** How many lines were joined away: the transcript has that many fewer. */
        val joinedAway: Int get() = lineCount - groups.size

        /** The id of every line once joined: its group's. */
        fun groupIds(): Map<String, String> =
            groups.flatMap { group -> group.lines.map { it.id to group.id } }.toMap()

        /** For the log: "Lines 26->19 (open 3, lowercase 3, dangling 0, forced 1, continuer 0)". */
        fun summary(): String =
            "Lines $lineCount->${groups.size} (" +
                reasons.entries.joinToString { (reason, count) -> "${reason.name.lowercase(Locale.ROOT)} $count" } + ")"
    }

    /** The longest stretch of speech of [vad], in the milliseconds [join] takes. */
    fun maxSpeechMsOf(vad: VadParameters): Long = (vad.maxSpeechDuration * MS_PER_SECOND).toLong()

    /**
     * Which of a recording's captures [runs] (in order) the place [positionMs] of its audio
     * belongs to, for [Line.capture]; null when there are none.
     */
    fun captureAt(runs: List<AudioRun>, positionMs: Long): Int? =
        if (runs.isEmpty()) null else runs.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /**
     * @param lines The lines of one recording, in spoken order
     * @param maxSpeechMs The longest stretch of speech the voice detector let these lines run to
     *   ([maxSpeechMsOf] its values)
     * @param language The language the transcript is in, "en" or "de" (as
     *   [com.satori.domain.language.SpokenLanguage] tells it), or null when that isn't
     *   clear: a few words end no sentence in one language and end one in the other
     *   ([Reason.DANGLING])
     */
    fun join(lines: List<Line>, maxSpeechMs: Long, language: String? = null): Joined {
        val reasons = Reason.entries.associateWithTo(LinkedHashMap()) { 0 }
        val groups = mutableListOf<Group>()
        for (line in lines) {
            val last = groups.lastOrNull()
            val joining = last?.let { joining(it, line, maxSpeechMs, language) }
            if (last == null || joining == null) {
                groups += Group(listOf(line), line.text)
            } else {
                val (reason, text) = joining
                groups[groups.lastIndex] = Group(last.lines + line, text)
                reasons[reason] = reasons.getValue(reason) + 1
            }
        }
        return Joined(groups, reasons)
    }

    /** Why [b] goes on from [group], and the joined text; null when it stays a line of its own. */
    private fun joining(group: Group, b: Line, maxSpeechMs: Long, language: String?): Pair<Reason, String>? {
        val a = group.lines.last()
        val aText = group.text.trim()
        val bText = b.text.trim()
        if (!DiscardSilentRecordingUseCase.holdsSpeech(aText) || !DiscardSilentRecordingUseCase.holdsSpeech(bText)) return null
        val end = finalMark(aText)
        if (end == '?' || end == '!') return null
        if (group.lines.first().citedBy != b.citedBy) return null
        if (!followsOn(a, b)) return null
        if (gapMs(a, b) > MAX_JOIN_GAP_MS || lengthMs(group.lines.first(), b) > MAX_LINE_MS) return null
        val reason = when {
            end != '.' && !LanguageDetector.isMalayalam(aText) -> Reason.OPEN
            startsSmall(bText) -> Reason.LOWERCASE
            endsOnDanglingWord(aText, language) -> Reason.DANGLING
            ranToMaxSpeech(a, maxSpeechMs) -> Reason.FORCED
            startsWithContinuer(bText) -> Reason.CONTINUER
            else -> return null
        }
        val text = joinedText(aText, reason, bText)
        return if (text.length <= MAX_LINE_CHARS) reason to text else null
    }

    /**
     * Whether [b] goes on from [a] in the audio: in the same capture, and not begun before [a]
     * (its later stamp would then say the clock was set back meanwhile, not that it came next).
     * One said within [a]'s place may: a death between the two writes of a join leaves the
     * joined line's place over its pieces (JoinTranscriptLinesUseCase).
     */
    private fun followsOn(a: Line, b: Line): Boolean {
        if (a.capture != null && b.capture != null && a.capture != b.capture) return false
        return a.span == null || b.span == null || b.span.startMs >= a.span.startMs
    }

    /** The pause between [a]'s end and [b]'s start. */
    private fun gapMs(a: Line, b: Line): Long {
        if (a.span != null && b.span != null) return b.span.startMs - a.span.endMs
        return b.stamp - a.stamp - SPOKEN_MS_PER_CHAR * b.text.trim().length
    }

    /** How long the line from [first]'s start to [b]'s end is. */
    private fun lengthMs(first: Line, b: Line): Long {
        if (first.span != null && b.span != null) return b.span.endMs - first.span.startMs
        return b.stamp - first.stamp + SPOKEN_MS_PER_CHAR * first.text.trim().length
    }

    private fun ranToMaxSpeech(a: Line, maxSpeechMs: Long): Boolean {
        val piece = a.lastPiece ?: return false
        return piece.endMs - piece.startMs >= maxSpeechMs - FORCED_CUT_MARGIN_MS
    }

    /** The last mark of [text], past closing quotes and brackets. */
    private fun finalMark(text: String): Char? = text.trimEnd { it.isWhitespace() || it in CLOSERS }.lastOrNull()

    private fun startsSmall(text: String): Boolean = text.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() == true

    /**
     * Whether [text] ends on a word of [DANGLING], or of [DANGLING_IN] for [language]. The last
     * word is read whole, with its digits, hyphens and apostrophes, so a sentence that ends on a
     * number ("We meet at 3.", "Der Termin ist am 5.") ends on no such word.
     */
    private fun endsOnDanglingWord(text: String, language: String?): Boolean {
        val word = text.trimEnd { !it.isLetterOrDigit() }.takeLastWhile { it.isLetterOrDigit() || it in WORD_MARKS }
        // A capital "A" names a letter, a grade or a plan: "We got an A."
        if (word.isEmpty() || word == "A" || !word.all { it.isLetter() }) return false
        val small = word.lowercase(Locale.ROOT)
        return small in DANGLING || small in language?.let { DANGLING_IN[it] }.orEmpty()
    }

    private fun startsWithContinuer(text: String): Boolean =
        firstWord(text).lowercase(Locale.ROOT) in CONTINUERS && finalMark(text) != '?'

    private fun firstWord(text: String): String = text.takeWhile { it.isLetter() }

    private fun joinedText(a: String, reason: Reason, b: String): String {
        // An ellipsis stays: it says the speaker trailed off.
        val head = if (reason != Reason.OPEN && a.endsWith('.') && !a.endsWith("..")) a.dropLast(1) else a
        return "$head ${withSmallFirstWord(b)}"
    }

    /** [text] with its first word written small when it is one of [SMALL_WORDS] and capitalized. */
    private fun withSmallFirstWord(text: String): String {
        val word = firstWord(text)
        if (word.isEmpty() || !word[0].isUpperCase() || word.drop(1).any { it.isUpperCase() }) return text
        if (word.lowercase(Locale.ROOT) !in SMALL_WORDS) return text
        return text.replaceFirstChar { it.lowercaseChar() }
    }

    private const val MS_PER_SECOND = 1_000

    /** What may follow a sentence's final mark. */
    private val CLOSERS = setOf('"', '”', '»', ')', ']')

    /** What a word may hold besides letters and digits: "mother-in-law", "it's". */
    private val WORD_MARKS = setOf('-', '\'', '’')

    /**
     * Words a sentence doesn't end on: articles, conjunctions, possessives, "very" and the
     * prepositions of, with, for, to, from, at and by, and their German kin. Left out are those
     * that do end sentences: "her" and "his" ("I called her."), and in German the articles that are
     * also pronouns (der, die, das, den, dem, ein, eine, einen, ...: "Ich weiß das."), the
     * prepositions that are also parts of verbs (an, auf, aus, bei, mit, nach, zu: "Hör auf."),
     * the possessives that stand alone ("Das ist meine."), "oder" ending a question ("Das passt,
     * oder.") and "sehr" ("Danke sehr."). The English article "an" and the German "am" (an dem)
     * count only in a transcript known to be in their language ([DANGLING_IN]).
     */
    private val DANGLING = setOf(
        "the", "a", "and", "or", "but", "nor", "because", "although", "than",
        "my", "your", "our", "their", "its", "very", "of", "with", "for", "to", "from", "at", "by",
        "des", "und", "aber", "sondern", "dass", "weil", "sodass", "für", "von",
        "im", "zum", "zur", "beim", "vom", "ins", "mein", "dein", "unser", "euer"
    )

    /**
     * Words a sentence doesn't end on in one language and does in the other, by the language they
     * dangle in: "an" ends German sentences as a part of a verb ("Ich rufe morgen an."), and "am"
     * English ones as a verb ("Yes, I am."). A transcript in both languages, or too short to tell,
     * counts neither.
     */
    private val DANGLING_IN = mapOf(
        LanguageDetector.ENGLISH to setOf("an"),
        LanguageDetector.GERMAN to setOf("am")
    )

    /** Words that start a clause carrying the sentence before it on. */
    private val CONTINUERS = setOf(
        "which", "whose", "than", "unless", "until", "whereas",
        "dass", "weil", "sodass", "damit"
    )

    /**
     * Common words written small inside a sentence: what B's first word may become once joined.
     * No name is among them, nor "I", nor German "Sie" and "Ihr", whose capital is a form of
     * address, nor words that are also names ("Will", "May", "Can").
     */
    private val SMALL_WORDS: Set<String> = listOf(
        "a", "an", "the", "and", "or", "but", "nor", "so", "then", "because", "although", "though",
        "if", "when", "while", "where", "which", "who", "whom", "whose", "what", "that", "this",
        "these", "those", "there", "here", "it", "we", "you", "they", "he", "she", "them", "us",
        "him", "her", "his", "my", "your", "our", "their", "its", "me", "of", "to", "in", "on", "at",
        "by", "for", "with", "from", "about", "into", "over", "after", "before", "as", "than",
        "unless", "until", "whereas", "also", "just", "not", "very", "is", "are", "was", "were",
        "be", "been", "have", "has", "had", "do", "does", "did", "would", "should", "could", "might",
        "must", "all", "some", "any", "no"
    ).plus(
        listOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
            "und", "oder", "aber", "denn", "sondern", "dass", "weil", "sodass", "damit", "wenn", "als",
            "wie", "ob", "ich", "du", "er", "es", "wir", "man", "mich", "mir", "dich", "dir", "uns",
            "euch", "ihn", "ihm", "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine",
            "unser", "unsere", "dann", "auch", "noch", "schon", "nicht", "so", "also", "da", "hier",
            "dort", "jetzt", "zu", "mit", "für", "von", "bei", "auf", "im", "am", "in", "an", "aus",
            "nach", "vor", "um", "ist", "sind", "war", "waren", "hat", "haben", "hatte", "wird",
            "werden", "kann", "muss", "soll", "sollte", "möchte", "sehr", "welche", "welcher",
            "welches", "dessen", "deren", "was", "wer", "wo"
        )
    ).plus(
        // Fillers, said in either language
        listOf("uh", "um", "uhm", "er", "erm", "ah", "äh", "ähm", "öhm", "hm", "hmm")
    ).toSet()
}
