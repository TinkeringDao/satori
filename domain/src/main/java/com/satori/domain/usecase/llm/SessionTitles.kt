package com.satori.domain.usecase.llm

import com.satori.domain.usecase.mindmap.MindWords
import java.util.Locale

/**
 * Turns a model's title into a recording name, or rejects it.
 *
 * Small models often wrap a title in a label ("Title: …"), quotes, Markdown or a full stop, and
 * sometimes answer with a title that only names the kind of text, like "Audio Transcription
 * Summary". The wrapping is removed. A title made only of such generic words is rejected,
 * because the default name ("Voice note") says as much.
 *
 * A name never ends on the kind of text it was made from ([KIND_ENDINGS]: "Text", "Recording",
 * "Note", in English and German): the word goes, so "Village Market Text" is "Village Market". On
 * 2026-10-16 the 1B named a Malayalam recording with "Text" on the end. What was said of that word
 * goes with it: the small words and the language before it ("Story of the Text", "Summary of the
 * Malayalam Text") and an "'s" ("Doctor's Note"). When one word is left, it was said of the kind
 * ("Field Recording", "Release Notes", "Arzt Notiz"), and the name is rejected, so the notes' title
 * or the default name is used. A "Text:" in front is a label, as "Title:" is.
 *
 * Neither rule holds for a title in the words of what it names ([clean]'s subject): two words or
 * more that say something, each said there ([MindWords], as the mind map checks a label against
 * its text). A recording about recordings, meetings or notes has them for its subject: on
 * 2026-10-17 the owner described a voice-notes app that records meetings and gives notes and
 * minutes, and the note's title and the name the 1B gave it, made of those words, went without a
 * line, so the recording was left without a name. "Meeting Notes" still goes for a meeting on a
 * budget, whose notes don't say notes; "Release Notes" stays for notes that say "release notes".
 * A title kept only so ([Verdict.Kept.bySubject]) is a weak one: a meeting's notes say "meeting"
 * and "minutes" too, so a name in another title's place should be a specific one where there is
 * one (AutoNameSessionUseCase). A title made only of the words the name's prompt forbids ("audio",
 * "recording", "transcript", "summary", ...: [NEVER_SAID]) is never in the words of what it names:
 * "Audio Recording" over a junk note's "This is an audio recording." names the kind of text.
 */
object SessionTitles {

    /** What [verdict] made of a title. */
    sealed interface Verdict {
        /**
         * [title] is the name, cleaned. [bySubject] when only its subject keeps it: in the words of
         * what it names, it is generic or names only the kind of text by the rules alone ("Meeting
         * Notes & Minutes" for notes about recording meetings).
         */
        data class Kept(val title: String, val bySubject: Boolean = false) : Verdict

        /** There was nothing to read: a blank answer, a label alone, JSON without a title. */
        data object Empty : Verdict

        /** Every word names the kind of text or joins such words ([isGeneric]): "Audio Transcription Summary". */
        data object Generic : Verdict

        /** One word was left once the kind of text went from its end: "Release Notes", "Field Recording". */
        data object KindAlone : Verdict
    }

    /** Longest name kept. Longer titles are cut at a word boundary. */
    const val MAX_LENGTH = 60

    /** The fewest words that say something of a title its subject's words keep ([verdict]): one word is a kind or a topic alone. */
    private const val MIN_SUBJECT_WORDS = 2

    // Words that describe the recording instead of its subject, in English and German, and the
    // filler words that join them. A title made only of these names nothing. Also covers
    // "Summary" and "Meeting Notes", which older insights carry as fallback titles, and the
    // "request" of a model that reads a voice note as a request to analyse it.
    private val GENERIC_WORDS = setOf(
        "audio", "voice", "recording", "recordings", "recorded", "transcript", "transcripts",
        "transcription", "transcriptions", "summary", "summaries", "summarized", "note", "notes",
        "memo", "meeting", "meetings", "minutes", "insight", "insights", "overview", "analysis",
        "text", "title", "untitled", "content", "discussion", "conversation", "conversations",
        "chat", "talk", "request", "requests",
        "aufnahme", "aufnahmen", "audioaufnahme", "sprachaufnahme", "sprachaufnahmen",
        "aufzeichnung", "aufzeichnungen", "sprachnotiz", "sprachnotizen", "sprachmemo",
        "transkript", "transkripte", "transkription", "transkriptionen", "audiotranskription",
        "zusammenfassung", "zusammenfassungen", "transkriptionszusammenfassung",
        "gesprächszusammenfassung", "besprechung", "besprechungen", "besprechungsnotizen",
        "besprechungsprotokoll", "protokoll", "mitschrift", "notiz", "notizen", "gespräch",
        "gesprächs", "gespräche", "unterhaltung", "übersicht", "analyse", "titel", "inhalt",
        "diskussion", "anfrage", "anfragen",
        "a", "an", "the", "of", "and", "for", "on", "about", "this", "my",
        "der", "die", "das", "des", "dem", "den", "ein", "eine", "einer", "eines", "und", "zur",
        "zum", "von", "vom", "über", "mein", "meine"
    )

    // "Title: …", "Here is a title: …", "Thema: …", "Vorschlag für einen Titel: …"
    private val LABEL = Regex(
        """^[^:]{0,40}?\b(?:title|titel|name|topic|subject|thema|betreff|summary|zusammenfassung|""" +
            """transcription|transkription|task|aufgabe)\b[^:]{0,15}[:=]\s*""",
        RegexOption.IGNORE_CASE
    )
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
    private const val QUOTES = "\"'`“”„‟«»‚‘’‹›"

    /** The words a name never ends on: the kind of text, not its subject (brief 0.7.1). */
    private val KIND_ENDINGS = setOf(
        "text", "texts", "recording", "recordings", "note", "notes",
        "texte", "aufnahme", "aufnahmen", "notiz", "notizen"
    )

    // A name's last word, with the dashes, colons and commas before it.
    private val LAST_WORD = Regex("""[\s\-–—:,;]*([\p{L}\p{N}]+)$""")

    /** Small words a name doesn't end on once the kind it named went: "Story of the Text" is no "Story of the". */
    private val DANGLING = setOf(
        "of", "the", "a", "an", "about", "in", "on", "for", "from", "with", "and", "to", "at", "by",
        "der", "die", "das", "des", "dem", "den", "ein", "eine", "einer", "eines", "vom", "zum", "zur", "über", "und",
        "von", "mit", "für", "im", "am", "aus"
    )

    /** The languages a kind was said of: "Summary of the Malayalam Text", "Englischer Text". */
    private val LANGUAGES = setOf(
        "malayalam", "english", "german", "englisch", "englische", "englischer", "englischen", "deutsch", "deutsche",
        "deutscher", "deutschen"
    )

    // An "'s" left at the end once the kind it was said of went: "Doctor's Note".
    private val POSSESSIVE_END = Regex("""['’]s$""")

    // "Text: Family Conflict": the word alone before a colon is a label.
    private val TEXT_LABEL = Regex("""^\s*(?:text|texte)\s*[:=]\s*""", RegexOption.IGNORE_CASE)

    /**
     * Returns the cleaned title, or null if [raw] is empty or names nothing specific ([verdict]).
     *
     * Keeps the model's capitalisation: German nouns stay capitalised.
     *
     * @param subject What the title names (a note's points, the text a name was asked for), whose
     *   words a title may be made of; null for none, and the title is held to the rules alone
     */
    fun clean(raw: String?, subject: String? = null): String? = (verdict(raw, subject) as? Verdict.Kept)?.title

    /**
     * What [raw] comes to as a name: the cleaned title ([Verdict.Kept]), or why there is none, for
     * the field log. A title of two or more words that say something, each of them a word of
     * [subject], and not all of them words the name's prompt forbids, is kept as written, generic or
     * not, ending on a kind of text or not ([Verdict.Kept.bySubject] when the rules alone would not
     * keep it); any other is held to the rules of this object's description.
     */
    fun verdict(raw: String?, subject: String? = null): Verdict {
        val written = written(raw) ?: return Verdict.Empty
        val byRules = byRules(written)
        if (subject != null && isSaidIn(written, subject)) return Verdict.Kept(shorten(written), bySubject = byRules !is Verdict.Kept)
        return byRules
    }

    /**
     * The title in [raw] as the model wrote it, without what wraps it (a fence, the insight JSON,
     * a label, quotes, Markdown, a full stop) but before the kind of text goes from its end or it
     * is cut ([verdict]): what its words are held to besides the name kept ("Short Plain Notes",
     * whose kept "Short Plain" says no rule). Null when there is nothing to read.
     */
    fun written(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Small models fence their answer now and then, as they do with insights.
        var text = InsightOutputParser.stripCodeFences(raw.trim())

        // A model that still answers in the insight JSON format: take its title field.
        if (text.startsWith("{")) {
            text = InsightOutputParser.extractJsonField(text, "title")
                ?: InsightOutputParser.extractJsonField(text, "titel")
                ?: return null
        }

        // "Here's a title:" on a line of its own introduces the title on the next line.
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }.toList()
        var title = lines.firstOrNull { !it.endsWith(":") } ?: lines.firstOrNull() ?: return null
        title = title.trimStart('#', '>', '-', '*', ' ')
        title = title.replace("**", "").replace("__", "")
        title = LABEL.replace(title, "")
        title = TEXT_LABEL.replace(title, "")
        title = title.trim().trim { it in QUOTES || it.isWhitespace() }
        title = title.trimEnd { it == '.' || it == ':' || it == ';' || it == ',' || it.isWhitespace() }
        title = title.trim { it in QUOTES || it.isWhitespace() }
        val written = SPACES.replace(title, " ")
        return written.takeIf { WORD.find(it) != null }
    }

    /** What the rules of this object's description make of the [written] title, its subject left aside. */
    private fun byRules(written: String): Verdict {
        val title = withoutKindAtEnd(written)
        if (title.isEmpty()) return Verdict.KindAlone
        if (isGeneric(title)) return Verdict.Generic
        // A cut can end on such a word too.
        val cut = withoutKindAtEnd(shorten(title))
        return when {
            cut.isEmpty() -> Verdict.KindAlone
            isGeneric(cut) -> Verdict.Generic
            else -> Verdict.Kept(cut)
        }
    }

    /**
     * Whether [title] is made of the words of [subject]: two or more of its words say something
     * ([MindWords.isContentWord]), each is a word of [subject], as written, in another form or as
     * part of a compound ([MindWords.inVocabulary]), and not all of them are words the name's
     * prompt forbids ([NEVER_SAID]).
     */
    private fun isSaidIn(title: String, subject: String): Boolean {
        val words = MindWords.words(title).filter(MindWords::isContentWord)
        if (words.size < MIN_SUBJECT_WORDS) return false
        if (words.all { it in NEVER_SAID }) return false
        val vocabulary = MindWords.contentStems(subject).toSet()
        return words.all { MindWords.inVocabulary(MindWords.stem(it), vocabulary) }
    }

    /**
     * The words the name's prompt tells the model not to use (prompt_session_title: "audio,
     * recording, transcript, transcription, summary, analysis or request", in German "Audio,
     * Aufnahme, Transkript, Transkription, Zusammenfassung, Analyse und Anfrage"), and "text", which
     * no name ends on, folded as [MindWords] folds them. A title made only of them names the kind of
     * text whatever its subject says: a junk transcript's note "This is an audio recording." keeps no
     * "Audio Recording".
     */
    private val NEVER_SAID = setOf(
        "audio", "recording", "recordings", "transcript", "transcripts", "transcription", "transcriptions", "summary",
        "summaries", "analysis", "analyses", "request", "requests", "text", "texts",
        "aufnahme", "aufnahmen", "transkript", "transkripte", "transkription", "transkriptionen", "zusammenfassung",
        "zusammenfassungen", "analyse", "analysen", "anfrage", "anfragen", "texte"
    )

    /**
     * [title] without the words of [KIND_ENDINGS] at its end, however many, and once one went, the
     * small words, the language and the "'s" before it: "Packing for the trip - Notes" is "Packing
     * for the trip". Empty when one word or none is left ("Field Recording", "Release Notes").
     */
    private fun withoutKindAtEnd(title: String): String {
        var text = title
        var cut = false
        while (true) {
            val last = LAST_WORD.find(text) ?: break
            val word = last.groupValues[1].lowercase(Locale.ROOT)
            if (word !in KIND_ENDINGS && !(cut && (word in DANGLING || word in LANGUAGES))) break
            text = text.substring(0, last.range.first)
            cut = true
        }
        if (!cut) return text
        text = POSSESSIVE_END.replace(text.trimEnd(), "").trimEnd { it.isWhitespace() || it in ".:;,-–—" }
        return if (WORD.findAll(text).count() <= 1) "" else text
    }

    /** True when every word of [title] is a generic or filler word. */
    fun isGeneric(title: String): Boolean {
        val words = WORD.findAll(title.lowercase()).map { it.value }.toList()
        return words.isEmpty() || words.all { it in GENERIC_WORDS }
    }

    private fun shorten(title: String): String {
        if (title.length <= MAX_LENGTH) return title
        val cut = title.take(MAX_LENGTH + 1).substringBeforeLast(' ', missingDelimiterValue = "")
        val kept = cut.ifBlank { title.take(MAX_LENGTH) }
        return kept.trimEnd { it == ',' || it == ';' || it == ':' || it == '-' || it == '–' || it.isWhitespace() }
    }
}
