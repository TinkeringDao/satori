package com.satori.data.database.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindNodeEntity
import com.satori.data.database.entity.TodoEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TranscriptionSegmentDao.replaceForSession], which Transcribe again swaps a transcript with, on
 * an in-memory database: all or nothing, and of the notes, to-dos and mind-map links only where
 * they point.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionSegmentDaoReplaceTest {

    private lateinit var db: AppDatabase
    private lateinit var segments: TranscriptionSegmentDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        segments = db.transcriptionSegmentDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private fun session(id: String) = TranscriptionSessionEntity(id = id, name = "Walk", createdAt = 1_000, lastModifiedAt = 1_000)

    private fun line(id: String, sessionId: String, at: Long) =
        TranscriptionSegmentEntity(id = id, sessionId = sessionId, text = "line $id", timestamp = at, isComplete = true)

    @Test
    fun theNewTranscriptReplacesTheOldOneAndRepointsTheNotesAndToDos() = runBlocking {
        db.transcriptionSessionDao().insert(session("s1"))
        db.transcriptionSessionDao().insert(session("s2"))
        segments.insert(line("old1", "s1", 2_000))
        segments.insert(line("old2", "s1", 3_000))
        segments.insert(line("other", "s2", 2_500))
        db.llmInsightDao().insert(note("note", "s1", sources = "[\"old1\"]"))
        db.llmInsightDao().insert(note("summary", "s1", sources = "[]"))
        db.todoDao().upsert(todo("todo", "s1", sources = "[\"old1\"]"))

        segments.replaceForSession(
            sessionId = "s1",
            segments = listOf(line("new1", "s1", 2_100), line("new2", "s1", 2_900)),
            insightSources = mapOf("note" to "[\"new1\",\"new2\"]"),
            todoSources = mapOf("todo" to "[\"new2\"]"),
            modifiedAt = 9_000
        )

        assertEquals(listOf("new1", "new2"), segments.getSegmentsBySession("s1").first().map { it.id })
        assertEquals(listOf("other"), segments.getSegmentsBySession("s2").first().map { it.id })
        val notes = db.llmInsightDao().getInsightsBySession("s1").first().associateBy { it.id }
        assertEquals("[\"new1\",\"new2\"]", notes.getValue("note").sourceSegmentIds)
        assertEquals("A hike", notes.getValue("note").content)
        assertEquals("[]", notes.getValue("summary").sourceSegmentIds)
        assertEquals("[\"new2\"]", db.todoDao().get("todo")!!.sourceSegmentIds)
        val stored = db.transcriptionSessionDao().getSession("s1").first()!!
        assertEquals(9_000L, stored.lastModifiedAt)
        assertEquals("Walk", stored.name)
    }

    @Test
    fun theMindMapLinksOfTheRecordingArePointedAtTheNewLines() = runBlocking {
        db.transcriptionSessionDao().insert(session("s1"))
        db.transcriptionSessionDao().insert(session("s2"))
        segments.insert(line("old1", "s1", 2_000))
        val map = db.mindMapDao()
        map.upsertNode(mindNode("home", MindMapDao.THEME, parentId = null))
        map.upsertNode(mindNode("rent", MindMapDao.TOPIC, parentId = "home"))
        map.upsertNode(mindNode("repair", MindMapDao.TOPIC, parentId = "home"))
        map.link(MindLinkEntity("rent", "s1", points = "[]", segmentIds = "[\"old1\"]", createdAt = 1L))
        map.link(MindLinkEntity("repair", "s1", points = "[]", segmentIds = "[\"old1\"]", createdAt = 1L))
        map.link(MindLinkEntity("rent", "s2", points = "[]", segmentIds = "[\"x\"]", createdAt = 1L))

        assertEquals(
            mapOf("rent" to "[\"old1\"]", "repair" to "[\"old1\"]"),
            segments.linkSourcesOf("s1").associate { it.nodeId to it.segmentIds }
        )

        segments.replaceForSession(
            sessionId = "s1",
            segments = listOf(line("new1", "s1", 2_100)),
            insightSources = emptyMap(),
            todoSources = emptyMap(),
            modifiedAt = 9_000,
            linkSources = mapOf("rent" to "[\"new1\"]")
        )

        assertEquals(
            mapOf("rent" to "[\"new1\"]", "repair" to "[\"old1\"]"),
            segments.linkSourcesOf("s1").associate { it.nodeId to it.segmentIds }
        )
        assertEquals(listOf("[\"x\"]"), segments.linkSourcesOf("s2").map { it.segmentIds })
    }

    @Test
    fun aRecordingDeletedMeanwhileGetsNoLinesAndTheCallFails() = runBlocking {
        try {
            segments.replaceForSession("gone", listOf(line("new1", "gone", 2_100)), emptyMap(), emptyMap(), modifiedAt = 9_000)
            fail("replaced the transcript of a recording that doesn't exist")
        } catch (e: IllegalStateException) {
            assertTrue(segments.getSegmentsBySession("gone").first().isEmpty())
        }
    }

    private fun mindNode(id: String, kind: String, parentId: String?) = MindNodeEntity(
        id = id, kind = kind, label = id, matchKey = id, parentId = parentId, summary = null,
        summaryUpdatedAt = null, summaryStale = false, pinned = false, createdAt = 1L, updatedAt = 1L
    )

    private fun note(id: String, sessionId: String, sources: String) = LlmInsightEntity(
        id = id, sessionId = sessionId, content = "A hike", timestamp = 4_000, sourceSegmentIds = sources, title = "Plans"
    )

    private fun todo(id: String, sessionId: String, sources: String) = TodoEntity(
        id = id, sessionId = sessionId, text = "Book the hike", kind = "TASK", status = "OPEN",
        certainty = "CONFIRMED", capture = "EXPLICIT", dueAt = null, dueHasTime = false, timePhrase = null,
        remindAt = null, reminderShownAt = null, sourceSegmentIds = sources, createdAt = 1_000,
        updatedAt = 1_000, completedAt = null
    )
}
