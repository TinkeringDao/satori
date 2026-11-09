package com.satori.domain.usecase.llm

import com.satori.domain.model.LlmInsight

/**
 * The notes of one recording a later note of it is held to (0.7.3, [NoteRepeats]): those from
 * before ([earlier]: the live notes, for the last note after Stop), then each note [keep] kept.
 *
 * A later note that mostly repeats one of them ([NoteRepeats.repeatsNote]) loses the pieces that
 * repeat a piece of one of them, its title, points and action items each, and keeps what is new.
 * With nothing new left it is none, and it is not asked again: the model said something, the same
 * as before, and the note before it stands. Since the review of 0.7.3, as an English or German
 * meeting says its subject again and again: a live note that says the subject of the one before
 * again with one new point keeps that point, and so does a part of a pass. When [whole], as for a
 * recording the model can't read (a Malayalam pass on Gemma 3 1B, whose later parts said one
 * made-up subject again with a word or two more), such a note goes whole, as 0.7.3 had it.
 *
 * Thread-safe: a live note is read on the engine's thread, the next on another.
 */
internal class EarlierNotes(earlier: List<LlmInsight> = emptyList(), private val whole: Boolean = false) {

    private val notes: MutableList<List<List<NoteRepeats.Key>>> =
        earlier.mapTo(mutableListOf()) { NoteRepeats.piecesOf(it.title, it.content, it.tasks) }

    /** How many notes went as they mostly repeat an earlier one and had nothing new left. */
    var repeatedNotes = 0
        private set

    /** How many pieces of the notes kept went as they repeat a piece of an earlier note. */
    var repeatedLines = 0
        private set

    /**
     * [parsed], a note of the recording, as it is kept: whole, without the pieces an earlier note
     * says, or null when it mostly repeats an earlier note and nothing new is left (or [whole]). A
     * note kept is one a later note is held to.
     */
    @Synchronized
    fun keep(parsed: InsightOutputParser.ParsedInsight): InsightOutputParser.ParsedInsight? {
        val pieces = NoteRepeats.piecesOf(parsed.title, parsed.content, parsed.tasks)
        if (notes.none { NoteRepeats.repeatsNote(pieces, it) }) {
            notes += pieces
            return parsed
        }
        val left = if (whole) null else NoteRepeats.without(parsed.title, parsed.content, parsed.tasks, notes)
        if (left == null || (left.content.isBlank() && left.tasks == null)) {
            repeatedNotes++
            return null
        }
        repeatedLines += left.dropped
        val kept = parsed.copy(title = left.title, content = left.content, tasks = left.tasks, keptLines = left.points + left.actions)
        notes += NoteRepeats.piecesOf(kept.title, kept.content, kept.tasks)
        return kept
    }
}
