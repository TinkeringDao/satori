package com.satori.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL of what a finishing made again over a recording's readings keeps of the owner's (0.8.0,
 * design R9), on an in-memory database: the copy of a recording's name and notes made once before
 * anything is replaced, putting it back, marking a name and notes as his, a note he saves after
 * notes made again replaced it, notes made again never written over his, and the recordings due to
 * be made again. The texts are made up.
 */
@RunWith(AndroidJUnit4::class)
class RedoKeepsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var notes: LlmInsightDao
    private lateinit var sessions: TranscriptionSessionDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        notes = db.llmInsightDao()
        sessions = db.transcriptionSessionDao()
        runBlocking {
            sessions.insert(TranscriptionSessionEntity(id = "s1", name = "Office Market Challenges", createdAt = 1_000, lastModifiedAt = 1_000, language = "ml"))
            sessions.insert(TranscriptionSessionEntity(id = "s2", name = null, createdAt = 2_000, lastModifiedAt = 2_000, language = "ml"))
            notes.insert(note("n1", "s1", "Workflow challenges", at = 10))
            notes.insert(note("n2", "s1", "Market", at = 20))
        }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun whatARecordingHadIsKeptOnceAndPutBackAsTheOwners() = runBlocking {
        assertTrue(notes.keepBeforeRedo("s1", now = 100))
        // The finishing made again replaces the notes and the name.
        notes.replaceForSession("s1", listOf(note("n9", "s1", "Papers and the market", at = 90)), "END_OF_SESSION", modifiedAt = 90)
        assertEquals(1, sessions.replaceName("s1", expected = "Office Market Challenges", name = "Office papers"))
        // A second run made again keeps nothing more: the copy is what an older version left.
        assertFalse(notes.keepBeforeRedo("s1", now = 200))
        assertEquals(listOf("n1", "n2"), notes.notesKeptBeforeRedo("s1").map { it.id })
        assertEquals("Office Market Challenges", notes.keptBeforeRedo("s1")?.name)
        assertEquals("REAL_TIME", notes.keptBeforeRedo("s1")?.insightStrategy)
        sessions.setRedoDue("s1", due = true)

        assertEquals(2, notes.restoreBeforeRedo("s1", now = 300))

        val back = notes.getInsightsBySession("s1").first()
        assertEquals(listOf("n1", "n2"), back.map { it.id })
        assertEquals(listOf("Workflow challenges", "Market"), back.map { it.title })
        // His now: a finishing made again leaves them, and the name, as they are.
        assertEquals(listOf<Long?>(300, 300), back.map { it.editedAt })
        val session = sessions.getSession("s1").first()!!
        assertEquals("Office Market Challenges", session.name)
        assertTrue(session.nameByOwner)
        assertEquals("REAL_TIME", session.insightStrategy)
        assertFalse(session.redoDue)
        assertEquals(0, sessions.replaceName("s1", expected = "Office Market Challenges", name = "Something else"))
    }

    @Test
    fun aRecordingWithoutNameOrNotesIsKeptSoAndOneGoneOrNeverKeptGivesNothingBack() = runBlocking {
        assertTrue(notes.keepBeforeRedo("s2", now = 100))
        notes.insert(note("n5", "s2", "Written over the readings", at = 110))
        sessions.setNameIfUnnamed("s2", "The app's name")

        assertEquals(0, notes.restoreBeforeRedo("s2", now = 300))

        assertTrue(notes.getInsightsBySession("s2").first().isEmpty())
        assertNull(sessions.getSession("s2").first()!!.name)
        assertFalse(sessions.getSession("s2").first()!!.nameByOwner)
        assertFalse(notes.keepBeforeRedo("gone", now = 100))
        assertNull(notes.restoreBeforeRedo("gone", now = 100))
    }

    @Test
    fun theKeptCopyGoesWithItsRecording() = runBlocking {
        notes.keepBeforeRedo("s1", now = 100)

        db.mindMapDao().deleteSessionAndTidyMap("s1")

        assertNull(notes.keptBeforeRedo("s1"))
        assertTrue(notes.notesKeptBeforeRedo("s1").isEmpty())
    }

    @Test
    fun aNameAndNotesTheOwnerSaysAreHisAreMarkedSo() = runBlocking {
        notes.updateNote("n2", "Market", "Edited before.", null, editedAt = 50)

        // His name, and the note not yet marked; the other was marked when he saved it.
        assertEquals(2, notes.markOwnersEdits("s1", now = 400))
        // A recording without a name has only its notes to mark: none here.
        assertEquals(0, notes.markOwnersEdits("s2", now = 400))

        assertTrue(sessions.getSession("s1").first()!!.nameByOwner)
        assertEquals(listOf<Long?>(400, 50), notes.getInsightsBySession("s1").first().map { it.editedAt })
        assertFalse(sessions.getSession("s2").first()!!.nameByOwner)
        assertTrue(notes.hasEditedNote("s1"))
        assertFalse(notes.hasEditedNote("s2"))
    }

    @Test
    fun notesMadeAgainNeverReplaceANoteTheOwnerSaved() = runBlocking {
        assertTrue(notes.replaceForSessionUnlessEdited("s1", listOf(note("n9", "s1", "Papers", at = 90)), "END_OF_SESSION", modifiedAt = 90))
        assertEquals(listOf("n9"), notes.getInsightsBySession("s1").first().map { it.id })

        notes.updateNote("n9", "Papers", "His words.", null, editedAt = 95)
        assertFalse(notes.replaceForSessionUnlessEdited("s1", emptyList(), "REAL_TIME", modifiedAt = 99))

        assertEquals(listOf("His words."), notes.getInsightsBySession("s1").first().map { it.content })
    }

    @Test
    fun anEditSavedAfterNotesMadeAgainReplacedTheNoteIsKeptAsANoteOfItsOwn(): Unit = runBlocking {
        notes.replaceForSession("s1", listOf(note("n9", "s1", "Papers", at = 90)), "END_OF_SESSION", modifiedAt = 90)

        // The editor was open on n1, which is gone now.
        assertEquals(0, notes.updateNote("n1", "Workflow", "His words.", null, editedAt = 120))
        notes.saveEdit("n1", "s1", "Workflow", "His words.", null, editedAt = 120)
        // And an edit of a note that is there is an edit.
        notes.saveEdit("n9", "s1", "Papers", "His other words.", null, editedAt = 130)

        val kept = notes.getInsightsBySession("s1").first().associateBy { it.id }
        assertEquals(setOf("n1", "n9"), kept.keys)
        assertEquals("His words.", kept.getValue("n1").content)
        assertEquals(120L, kept.getValue("n1").editedAt)
        assertEquals(LlmInsightDao.NO_SOURCES, kept.getValue("n1").sourceSegmentIds)
        assertEquals("His other words.", kept.getValue("n9").content)
        assertEquals(130L, kept.getValue("n9").editedAt)
        // A recording that is gone gets no note.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { notes.saveEdit("n7", "gone", null, "Text.", null, editedAt = 140) }
        }
    }

    @Test
    fun theBackfillPicksTheRecordingsDueToBeMadeAgain() = runBlocking {
        assertTrue(sessions.backfillCandidates(maxFailures = 2).isEmpty())
        assertEquals(1, sessions.setRedoDue("s2", due = true))
        assertEquals(1, sessions.setRedoDue("s1", due = true))
        assertEquals(listOf("s1", "s2"), sessions.backfillCandidates(maxFailures = 2))

        // Made again, and finished.
        sessions.setRedoDue("s1", due = false)
        assertEquals(listOf("s2"), sessions.backfillCandidates(maxFailures = 2))
        assertEquals(0, sessions.setRedoDue("gone", due = true))
    }

    private fun note(id: String, sessionId: String, title: String, at: Long) = LlmInsightEntity(
        id = id, sessionId = sessionId, title = title, content = "- $title.", tasks = null, timestamp = at, sourceSegmentIds = "[]"
    )
}
