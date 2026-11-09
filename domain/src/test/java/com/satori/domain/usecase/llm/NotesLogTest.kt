package com.satori.domain.usecase.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** The SatoriNotes lines of what became of a recording's notes and name, as docs/FIELD_LOG.md lists them: counts, ids and classes only. */
class NotesLogTest {

    private fun line(event: NotesLog.Event) = NotesLog.line(event)

    @Test
    fun `the notes' lines`() {
        assertEquals("Dropped 1 note titles that name only the kind of text in s1", line(NotesLog.GenericTitles("s1", 1)))
        assertEquals("Dropped 3 note lines with no words in s1", line(NotesLog.EmptyLines("s1", 3)))
        assertEquals(
            "Notes of s1: no point left in the answer (every point left out), asking once more",
            line(NotesLog.NoPoint("s1", NotesLog.Part.Final, NotesLog.Why.ALL_DROPPED))
        )
        assertEquals(
            "Notes of s1, part 2 of 3: no point left in the answer (it was blank), asking once more",
            line(NotesLog.NoPoint("s1", NotesLog.Part.Pass(2, 3), NotesLog.Why.BLANK))
        )
        assertEquals(
            "Notes of s1: no point left in the answer (no point in full), asking once more",
            line(NotesLog.NoPoint("s1", NotesLog.Part.Pass(1, 1), NotesLog.Why.NONE_IN_FULL))
        )
        assertEquals("Notes of s1, the merge: the second answer kept, 1 line", line(NotesLog.SecondKept("s1", NotesLog.Part.Merge, 1)))
        assertEquals("Notes of s1: the second answer kept, 4 lines", line(NotesLog.SecondKept("s1", NotesLog.Part.Final, 4)))
        assertEquals("Notes of s1, part 1 of 2: no note, no point in 2 answers", line(NotesLog.NoNote("s1", NotesLog.Part.Pass(1, 2))))
        assertEquals(
            "Notes of s1, part 3 of 4: no note, no point left in the answer (every point left out), " +
                "and no second try after 2 calls in a row gave none in 2 answers",
            line(NotesLog.NoSecondTry("s1", NotesLog.Part.Pass(3, 4), NotesLog.Why.ALL_DROPPED, 2))
        )
    }

    @Test
    fun `the name's lines`() {
        assertEquals("Dropped 1 names that name only the kind of text in s1", line(NotesLog.GenericNames("s1", 1)))
        assertEquals("Dropped 1 names that are empty in s1", line(NotesLog.EmptyNames("s1", 1)))
        assertEquals("Name of s1: asking once more, from its transcript", line(NotesLog.NameAgain("s1")))
        assertEquals("Name of s1: from its notes, after 1 answer", line(NotesLog.Named("s1", NotesLog.NameSource.NOTES, 1)))
        assertEquals("Name of s1: from its transcript, after 2 answers", line(NotesLog.Named("s1", NotesLog.NameSource.TRANSCRIPT, 2)))
        assertEquals("Name of s1: the notes' title, after 2 answers", line(NotesLog.Named("s1", NotesLog.NameSource.NOTE_TITLE, 2)))
        assertEquals("Name of s1: none after 2 answers, the default name shows", line(NotesLog.NoName("s1", 2)))
        assertEquals(
            "Name of s1: none, the model failed (IOException); the default name shows",
            line(NotesLog.NameFailed("s1", "IOException"))
        )
        // Make text again, which replaces a name, leaves the old one when it finds none.
        assertEquals("Name of s1: none after 2 answers, the old name stays", line(NotesLog.NoName("s1", 2, oldNameStays = true)))
        assertEquals(
            "Name of s1: none, the model failed (IOException); the old name stays",
            line(NotesLog.NameFailed("s1", "IOException", oldNameStays = true))
        )
    }
}
