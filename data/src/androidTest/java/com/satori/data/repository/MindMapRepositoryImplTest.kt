package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.MindMapRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MindMapRepositoryImpl] on an in-memory database: the domain models it reads and writes (kinds,
 * points and segment ids, recording dates), the flows following writes, the threshold of
 * [MindMapRepository.MIN_TRANSCRIPT_CHARS], recordings read without topics, its transactions, and
 * deleting recordings through [TranscriptionRepositoryImpl], which tidies the map.
 */
@RunWith(AndroidJUnit4::class)
class MindMapRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MindMapRepository
    private lateinit var recordings: TranscriptionRepositoryImpl

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        repository = MindMapRepositoryImpl(db.mindMapDao(), db)
        recordings = TranscriptionRepositoryImpl(
            sessionDao = db.transcriptionSessionDao(),
            segmentDao = db.transcriptionSegmentDao(),
            insightDao = db.llmInsightDao(),
            searchDao = db.searchDao(),
            mindMapDao = db.mindMapDao(),
            audio = NoAudio,
            readingDao = db.segmentReadingDao()
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun nodesReadBackAsTheyWereStored() = runBlocking {
        val theme = MindNode(
            id = "heating", kind = MindNodeKind.THEME, label = "Heizung", matchKey = "heizung",
            summary = "Die Reparatur kommt diesen Monat.", summaryUpdatedAt = 30L, summaryStale = true,
            pinned = true, createdAt = 10L, updatedAt = 30L
        )
        val topic = MindNode(
            id = "repair", kind = MindNodeKind.TOPIC, label = "Heizung reparieren", matchKey = "heizung reparier",
            parentId = "heating", createdAt = 20L, updatedAt = 20L
        )

        repository.upsertNode(theme)
        repository.upsertNode(topic)

        assertEquals(theme, repository.node("heating").first())
        assertEquals(topic, repository.node("repair").first())
        assertEquals(listOf(theme, topic), repository.allNodes().first())
        assertNull(repository.node("unknown").first())
    }

    @Test
    fun aLinkKeepsItsPointsAndSegmentsAndIsDatedByItsRecording() = runBlocking {
        session("monday", createdAt = 1_000L)
        mapTopic("heating", "repair")
        val points = listOf(
            "Der Vermieter sagt: \"bis Ende des Monats\".",
            "Kosten: 40 €, [vorläufig], {offen}, a\\b",
            "Ünïcödé and a line\nbreak"
        )

        repository.link("repair", "monday", points, listOf("monday-seg1", "monday-seg3"), at = 5_000L)

        val expected = MindLink(
            topicId = "repair", sessionId = "monday", points = points,
            segmentIds = listOf("monday-seg1", "monday-seg3"), createdAt = 5_000L, recordedAt = 1_000L
        )
        assertEquals(listOf(expected), repository.links("repair").first())
        assertEquals(listOf(expected), repository.links("heating").first())
        assertEquals(
            listOf(MindSessionTopic(topic = node("repair", parentId = "heating"), theme = node("heating"), link = expected)),
            repository.nodesForSession("monday").first()
        )
    }

    @Test
    fun themesAndChildrenCountTheRecordings() = runBlocking {
        session("monday", createdAt = 1_000L)
        session("tuesday", createdAt = 2_000L)
        mapTopic("heating", "repair")
        mapTopic("heating", "bill")
        repository.link("repair", "monday", listOf("Fixed this month."), emptyList(), at = 9_000L)
        repository.link("bill", "monday", listOf("Higher than last year."), emptyList(), at = 9_000L)
        repository.link("bill", "tuesday", listOf("Paid."), emptyList(), at = 9_000L)

        assertEquals(listOf(MindNodeItem(node("heating"), recordingCount = 2, lastRecordedAt = 2_000L)), repository.themes().first())
        assertEquals(
            listOf(
                MindNodeItem(node("bill", parentId = "heating"), recordingCount = 2, lastRecordedAt = 2_000L),
                MindNodeItem(node("repair", parentId = "heating"), recordingCount = 1, lastRecordedAt = 1_000L)
            ),
            repository.children("heating").first()
        )
    }

    @Test
    fun theFlowsFollowTheWrites() = runBlocking {
        session("monday", createdAt = 1_000L)
        val themes = Channel<List<MindNodeItem>>(Channel.UNLIMITED)
        val collector = launch { repository.themes().collect { themes.send(it) } }
        assertEquals(emptyList<MindNodeItem>(), withTimeout(FLOW_TIMEOUT_MS) { themes.receive() })

        mapTopic("heating", "repair")
        repository.link("repair", "monday", listOf("Fixed this month."), emptyList(), at = 2_000L)
        // The node writes before the link list nothing new, so the next list is the linked one
        assertEquals(listOf("heating"), withTimeout(FLOW_TIMEOUT_MS) { themes.receive() }.map { it.node.id })

        repository.setSummary("heating", "The heating gets fixed.", at = 3_000L)
        assertEquals(
            "The heating gets fixed.",
            withTimeout(FLOW_TIMEOUT_MS) { themes.receive() }.single().node.summary
        )
        collector.cancel()
    }

    @Test
    fun recordingsWaitToBeMappedFromMinTranscriptCharacters() = runBlocking {
        session("enough", createdAt = 2_000L)
        segment("enough", "a".repeat(MindMapRepository.MIN_TRANSCRIPT_CHARS))
        session("too-short", createdAt = 1_000L)
        segment("too-short", "b".repeat(MindMapRepository.MIN_TRANSCRIPT_CHARS - 1))

        assertEquals(listOf("enough"), repository.unmappedSessionIds().first())

        mapTopic("heating", "repair")
        repository.link("repair", "enough", listOf("Fixed."), emptyList(), at = 3_000L)

        assertEquals(emptyList<String>(), repository.unmappedSessionIds().first())
    }

    @Test
    fun aRecordingReadWithoutTopicsWaitsUntilItsTranscriptChanges() = runBlocking {
        session("mic-test", createdAt = 2_000L)
        segment("mic-test", "a".repeat(MindMapRepository.MIN_TRANSCRIPT_CHARS + 10))
        val waiting = Channel<List<String>>(Channel.UNLIMITED)
        val collector = launch { repository.unmappedSessionIds().collect { waiting.send(it) } }
        assertEquals(listOf("mic-test"), withTimeout(FLOW_TIMEOUT_MS) { waiting.receive() })

        repository.markReadWithoutTopics("mic-test", chars = MindMapRepository.MIN_TRANSCRIPT_CHARS + 10)

        // The list follows the mark
        assertEquals(emptyList<String>(), withTimeout(FLOW_TIMEOUT_MS) { waiting.receive() })

        // A transcript made again replaces the lines
        val newLine = TranscriptionSegment("new", "mic-test", "b".repeat(MindMapRepository.MIN_TRANSCRIPT_CHARS + 20), 0L, isComplete = true)
        assertTrue(recordings.replaceSegments("mic-test", listOf(newLine)).isSuccess)

        assertEquals(listOf("mic-test"), withTimeout(FLOW_TIMEOUT_MS) { waiting.receive() })
        collector.cancel()
    }

    @Test
    fun aTransactionKeepsAllOfItsWritesOrNoneOfThem() = runBlocking {
        session("monday", createdAt = 1_000L)
        val failure = IllegalStateException("half-way")

        try {
            repository.inTransaction {
                mapTopic("heating", "repair")
                repository.link("repair", "monday", listOf("Fixed."), emptyList(), at = 2_000L)
                throw failure
            }
            fail("the failure was swallowed")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }

        assertEquals(emptyList<MindNode>(), repository.allNodesNow())
        assertEquals(emptyList<MindSessionTopic>(), repository.nodesForSession("monday").first())

        val stored = repository.inTransaction {
            mapTopic("heating", "repair")
            repository.link("repair", "monday", listOf("Fixed."), emptyList(), at = 2_000L)
            repository.allNodesNow().map { it.id }
        }

        assertEquals(listOf("heating", "repair"), stored)
        assertEquals(listOf("repair"), repository.nodesForSession("monday").first().map { it.topic.id })
    }

    @Test
    fun forgetSessionKeepsTheNodesAndCleanupRemovesThemWhenNothingLinksAgain() = runBlocking {
        session("monday", createdAt = 1_000L)
        mapTopic("heating", "repair")
        repository.link("repair", "monday", listOf("Fixed."), emptyList(), at = 2_000L)

        repository.forgetSession("monday")

        assertTrue(repository.node("heating").first()!!.summaryStale)
        assertEquals(emptyList<MindLink>(), repository.links("heating").first())
        assertEquals(listOf("heating", "repair"), repository.allNodes().first().map { it.id })

        repository.cleanup()

        assertEquals(emptyList<MindNode>(), repository.allNodes().first())
    }

    @Test
    fun deletingARecordingThroughTheTranscriptionRepositoryTidiesTheMap() = runBlocking {
        session("monday", createdAt = 1_000L)
        session("tuesday", createdAt = 2_000L)
        mapTopic("heating", "repair")
        mapTopic("rent", "march")
        repository.link("repair", "monday", listOf("Fixed."), emptyList(), at = 3_000L)
        repository.link("repair", "tuesday", listOf("Still cold."), emptyList(), at = 3_000L)
        repository.link("march", "monday", listOf("Up by 40 euros."), emptyList(), at = 3_000L)

        assertTrue(recordings.deleteSession("monday").isSuccess)

        assertEquals(listOf("heating"), repository.themes().first().map { it.node.id })
        assertTrue(repository.node("heating").first()!!.summaryStale)
        assertNull(repository.node("rent").first())
        assertNull(repository.node("march").first())

        assertTrue(recordings.deleteAllSessions().isSuccess)

        assertEquals(emptyList<MindNode>(), repository.allNodes().first())
    }

    private suspend fun mapTopic(themeId: String, topicId: String) {
        repository.upsertNode(node(themeId))
        repository.upsertNode(node(topicId, parentId = themeId))
    }

    private suspend fun session(id: String, createdAt: Long) {
        db.transcriptionSessionDao().insert(
            TranscriptionSessionEntity(id = id, name = null, createdAt = createdAt, lastModifiedAt = createdAt)
        )
    }

    private suspend fun segment(sessionId: String, text: String) {
        db.transcriptionSegmentDao().insert(
            TranscriptionSegmentEntity(id = "$sessionId-seg", sessionId = sessionId, text = text, timestamp = 0L, isComplete = true)
        )
    }

    private companion object {
        const val FLOW_TIMEOUT_MS = 5_000L

        /** A theme, or with [parentId] a topic, labelled by its id. */
        fun node(id: String, parentId: String? = null) = MindNode(
            id = id,
            kind = if (parentId == null) MindNodeKind.THEME else MindNodeKind.TOPIC,
            label = id,
            matchKey = id,
            parentId = parentId,
            createdAt = 0L,
            updatedAt = 0L
        )
    }
}
