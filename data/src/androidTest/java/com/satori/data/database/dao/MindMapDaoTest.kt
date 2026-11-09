package com.satori.data.database.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindNodeEntity
import com.satori.data.database.entity.TodoEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MindMapDao] on an in-memory database with the app's schema and foreign keys: how nodes are
 * stored and updated, which links are kept, what the map counts and in which order it lists it,
 * which recordings wait to be mapped (and which no longer, read without topics), and how
 * forgetting, tidying and deleting recordings keep the map whole.
 */
@RunWith(AndroidJUnit4::class)
class MindMapDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MindMapDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        dao = db.mindMapDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ========== upsertNode ==========

    @Test
    fun upsertNodeStoresAThemeWithoutAParent() = runBlocking {
        assertTrue(dao.upsertNode(node("heating", THEME, parentId = "elsewhere")))

        assertNull(stored("heating").parentId)
    }

    @Test
    fun upsertNodeUpdatesLabelKeyParentAndTimeAndKeepsTheRest() = runBlocking {
        dao.upsertNode(node("heating", THEME, label = "Heating", createdAt = 1L))
        // A copy read before the summary, the pin and the stale mark were written
        val copy = stored("heating")
        dao.setSummary("heating", "The landlord fixes it.", at = 5L)
        dao.setPinned("heating", true)
        dao.markStale(listOf("heating"))

        dao.upsertNode(
            copy.copy(kind = TOPIC, label = "Heating repair", matchKey = "heating repair", createdAt = 99L, updatedAt = 7L)
        )

        assertEquals(
            node(
                "heating", THEME, label = "Heating repair", matchKey = "heating repair",
                summary = "The landlord fixes it.", summaryUpdatedAt = 5L, summaryStale = true, pinned = true,
                createdAt = 1L, updatedAt = 7L
            ),
            stored("heating")
        )
    }

    @Test
    fun upsertNodeMovesATopicToAnotherTheme() = runBlocking {
        theme("heating")
        theme("rent")
        dao.upsertNode(node("bill", TOPIC, parentId = "heating", createdAt = 3L))

        assertTrue(dao.upsertNode(node("bill", TOPIC, label = "Bill", parentId = "rent", createdAt = 9L, updatedAt = 8L)))

        assertEquals(
            node("bill", TOPIC, label = "Bill", parentId = "rent", createdAt = 3L, updatedAt = 8L),
            stored("bill")
        )
    }

    @Test
    fun upsertNodeStoresNoTopicWithoutATheme() = runBlocking {
        theme("heating")
        topic("repair", "heating")

        assertFalse(dao.upsertNode(node("gone", TOPIC, parentId = "deleted-theme")))
        assertFalse(dao.upsertNode(node("under-a-topic", TOPIC, parentId = "repair")))
        assertFalse(dao.upsertNode(node("no-parent", TOPIC, parentId = null)))
        // An update that would move a topic to a theme that is gone changes nothing
        assertFalse(dao.upsertNode(node("repair", TOPIC, label = "Moved", parentId = "deleted-theme", updatedAt = 5L)))

        assertEquals(listOf("heating", "repair"), dao.observeAllNodes().first().map { it.id })
        assertEquals("repair", stored("repair").label)
        assertEquals("heating", stored("repair").parentId)
    }

    @Test
    fun upsertNodeStoresANewTopicWithoutSummaryOrPin() = runBlocking {
        theme("heating")

        dao.upsertNode(
            node(
                "repair", TOPIC, parentId = "heating", summary = "Only themes have one", summaryUpdatedAt = 4L,
                summaryStale = true, pinned = true
            )
        )

        assertEquals(node("repair", TOPIC, parentId = "heating"), stored("repair"))
    }

    @Test
    fun upsertNodeStoresANewThemeWithTheSummaryAndPinItCarries() = runBlocking {
        val theme = node(
            "heating", THEME, summary = "Fixed this month.", summaryUpdatedAt = 4L, summaryStale = true,
            pinned = true, createdAt = 2L, updatedAt = 4L
        )

        dao.upsertNode(theme)

        assertEquals(theme, stored("heating"))
    }

    // ========== link ==========

    @Test
    fun linkReplacesTheLinkOfATopicAndRecordingAndStoresItLast() = runBlocking {
        session("monday", createdAt = 100L)
        theme("home")
        topic("heating", "home")
        topic("rent", "home")
        link("heating", "monday", points = "[\"first\"]")
        link("rent", "monday", points = "[\"rent\"]")

        assertTrue(link("heating", "monday", points = "[\"second\"]", at = 9L))

        assertEquals(
            listOf("rent" to "[\"rent\"]", "heating" to "[\"second\"]"),
            dao.observeSessionTopics("monday").first().map { it.topic.id to it.link.points }
        )
        assertEquals(9L, dao.observeLinks("heating").first().single().link.createdAt)
    }

    @Test
    fun linkStoresNothingForAThemeOrWhenTheTopicOrRecordingIsGone() = runBlocking {
        session("monday", createdAt = 100L)
        theme("home")
        topic("heating", "home")

        assertFalse(link("home", "monday"))
        assertFalse(link("deleted-topic", "monday"))
        assertFalse(link("heating", "deleted-recording"))

        assertEquals(emptyList<MindLinkRow>(), dao.observeLinks("home").first())
        assertEquals(emptyList<MindLinkRow>(), dao.observeLinks("heating").first())
    }

    // ========== Reads ==========

    @Test
    fun themesCountEachRecordingOnceAndAreDatedByTheRecordings() = runBlocking {
        session("monday", createdAt = 100L)
        session("tuesday", createdAt = 200L)
        theme("home")
        topic("heating", "home")
        topic("rent", "home")
        theme("no-topics")
        theme("no-links")
        topic("garage", "no-links")
        // Linked long after they were recorded, as "Map older recordings" does
        link("heating", "monday", at = 999L)
        link("rent", "monday", at = 999L)
        link("heating", "tuesday", at = 999L)

        assertEquals(listOf(Triple("home", 2, 200L)), themes())
        assertEquals(listOf(Triple("heating", 2, 200L), Triple("rent", 1, 100L)), children("home"))
    }

    @Test
    fun themesComeByRecordingsThenNewestThenLabelThenId() = runBlocking {
        session("old", createdAt = 50L)
        session("middle", createdAt = 100L)
        session("new", createdAt = 300L)
        themeWithRecordings("a", "Beta", "new")
        themeWithRecordings("b", "Zeta", "middle", "old")
        themeWithRecordings("c", "Alpha", "new")
        themeWithRecordings("d", "Alpha", "new")
        themeWithRecordings("e", "Gamma", "middle")

        assertEquals(listOf("b", "c", "d", "a", "e"), themes().map { it.first })
    }

    @Test
    fun childrenAreEmptyForATopicAndForAnUnknownId() = runBlocking {
        session("monday", createdAt = 100L)
        theme("home")
        topic("heating", "home")
        link("heating", "monday")

        assertEquals(emptyList<Triple<String, Int, Long>>(), children("heating"))
        assertEquals(emptyList<Triple<String, Int, Long>>(), children("unknown"))
    }

    @Test
    fun linksOfAThemeAreThoseOfItsTopicsNewestRecordingFirst() = runBlocking {
        session("monday", createdAt = 100L)
        session("tuesday", createdAt = 200L)
        theme("home")
        topic("heating", "home")
        topic("rent", "home")
        link("heating", "monday", at = 1L)
        link("rent", "monday", at = 2L)
        link("rent", "tuesday", at = 3L)

        assertEquals(
            listOf(Triple("rent", "tuesday", 200L), Triple("heating", "monday", 100L), Triple("rent", "monday", 100L)),
            dao.observeLinks("home").first().map { Triple(it.link.nodeId, it.link.sessionId, it.recordedAt) }
        )
        assertEquals(
            listOf("tuesday", "monday"),
            dao.observeLinks("rent").first().map { it.link.sessionId }
        )
        assertEquals(emptyList<MindLinkRow>(), dao.observeLinks("unknown").first())
    }

    @Test
    fun sessionTopicsComeInStoringOrderWithTheirThemes() = runBlocking {
        session("monday", createdAt = 100L)
        theme("home")
        theme("work")
        topic("heating", "home")
        topic("budget", "work")
        link("budget", "monday", points = "[\"Over by twelve percent\"]")
        link("heating", "monday", points = "[\"Fixed this month\"]")

        val rows = dao.observeSessionTopics("monday").first()

        assertEquals(listOf("budget", "heating"), rows.map { it.topic.id })
        assertEquals(listOf("work", "home"), rows.map { it.theme.id })
        assertEquals(listOf("[\"Over by twelve percent\"]", "[\"Fixed this month\"]"), rows.map { it.link.points })
        assertEquals(listOf(100L, 100L), rows.map { it.recordedAt })
        assertEquals(emptyList<MindSessionTopicRow>(), dao.observeSessionTopics("unmapped").first())
    }

    @Test
    fun allNodesComeByCreationThenInStoringOrder() = runBlocking {
        theme("x", createdAt = 5L)
        theme("y", createdAt = 1L)
        theme("z", createdAt = 5L)
        // An update keeps its place
        dao.upsertNode(node("x", THEME, label = "Renamed", updatedAt = 9L))

        assertEquals(listOf("y", "x", "z"), dao.observeAllNodes().first().map { it.id })
        assertEquals(dao.observeAllNodes().first(), dao.getAllNodes())
    }

    // ========== Summary, stale mark and pin ==========

    @Test
    fun setSummaryStoresItsTimeAndClearsTheStaleMark() = runBlocking {
        theme("home", createdAt = 1L)
        topic("heating", "home", createdAt = 1L)
        dao.markStale(listOf("home"))

        dao.setSummary("home", "Repairs and rent.", at = 42L)
        dao.setSummary("heating", "Only themes have one", at = 43L)

        val home = stored("home")
        assertEquals("Repairs and rent.", home.summary)
        assertEquals(42L, home.summaryUpdatedAt)
        assertEquals(42L, home.updatedAt)
        assertFalse(home.summaryStale)
        assertEquals(node("heating", TOPIC, parentId = "home", createdAt = 1L), stored("heating"))
    }

    @Test
    fun markStaleMarksThemesOnlyHoweverManyIdsItGets() = runBlocking {
        theme("home")
        topic("heating", "home")
        theme("work")

        dao.markStale(listOf("home", "heating", "unknown", "home"))

        assertTrue(stored("home").summaryStale)
        assertFalse(stored("heating").summaryStale)
        assertFalse(stored("work").summaryStale)

        // More ids than one statement may bind
        dao.markStale(List(1_200) { "unknown-$it" } + "work")

        assertTrue(stored("work").summaryStale)
    }

    @Test
    fun setPinnedPinsAndUnpinsThemesOnlyAndChangesNothingElse() = runBlocking {
        theme("home", createdAt = 1L)
        topic("heating", "home", createdAt = 1L)

        dao.setPinned("home", true)
        dao.setPinned("heating", true)

        assertEquals(node("home", THEME, pinned = true, createdAt = 1L), stored("home"))
        assertFalse(stored("heating").pinned)

        dao.setPinned("home", false)

        assertFalse(stored("home").pinned)
    }

    // ========== Unmapped recordings ==========

    @Test
    fun unmappedRecordingsHaveNoLinkAndEnoughCompleteTranscript() = runBlocking {
        session("exactly-40", createdAt = 600L)
        segment("exactly-40", "   " + "x".repeat(40) + "   ")
        session("only-39", createdAt = 500L)
        segment("only-39", "y".repeat(39))
        session("two-segments", createdAt = 400L)
        segment("two-segments", " " + "z".repeat(20))
        segment("two-segments", "z".repeat(20) + "  ")
        session("partly-unfinished", createdAt = 300L)
        segment("partly-unfinished", "w".repeat(30))
        segment("partly-unfinished", "w".repeat(30), complete = false)
        session("linked", createdAt = 200L)
        segment("linked", "v".repeat(100))
        session("empty", createdAt = 100L)
        session("older", createdAt = 50L)
        segment("older", "u".repeat(40))
        theme("home")
        topic("heating", "home")
        link("heating", "linked")

        assertEquals(
            listOf("exactly-40", "two-segments", "older"),
            dao.observeUnmappedSessionIds(minChars = 40).first()
        )
    }

    // ========== Recordings read without topics ==========

    @Test
    fun aRecordingReadWithoutTopicsIsNotOfferedWhileItsTranscriptCountsTheSame() = runBlocking {
        session("mic-test", createdAt = 200L)
        segment("mic-test", "  " + "m".repeat(50) + "  ")
        segment("mic-test", "p".repeat(30), complete = false)
        session("other", createdAt = 100L)
        segment("other", "o".repeat(60))

        dao.markReadWithoutTopics("mic-test", chars = 50, now = 5L)

        // Counted as the waiting list counts: complete lines, spaces at their ends removed
        assertEquals(listOf("mic-test/50"), skips())
        assertEquals(listOf("other"), dao.observeUnmappedSessionIds(minChars = 40).first())
    }

    @Test
    fun aTranscriptThatChangedWhileItWasReadGetsNoMark() = runBlocking {
        session("mic-test", createdAt = 200L)
        segment("mic-test", "m".repeat(50))

        // The AI read 48 characters; an edit made them 50 meanwhile.
        dao.markReadWithoutTopics("mic-test", chars = 48, now = 5L)

        assertEquals(emptyList<String>(), skips())
        assertEquals(listOf("mic-test"), dao.observeUnmappedSessionIds(minChars = 40).first())
    }

    @Test
    fun aTranscriptThatChangedIsOfferedAgainUntilItIsReadAgain() = runBlocking {
        session("mic-test", createdAt = 200L)
        segment("mic-test", "m".repeat(50))
        dao.markReadWithoutTopics("mic-test", chars = 50, now = 5L)

        db.transcriptionSegmentDao().updateText("mic-test-seg1", "m".repeat(51))

        assertEquals(listOf("mic-test"), dao.observeUnmappedSessionIds(minChars = 40).first())

        dao.markReadWithoutTopics("mic-test", chars = 51, now = 6L)

        assertEquals(listOf("mic-test/51"), skips())
        assertEquals(emptyList<String>(), dao.observeUnmappedSessionIds(minChars = 40).first())
    }

    @Test
    fun linkingOrForgettingARecordingDropsItsMark() = runBlocking {
        session("monday", createdAt = 100L)
        segment("monday", "m".repeat(50))
        theme("home")
        topic("heating", "home")
        dao.markReadWithoutTopics("monday", chars = 50, now = 5L)

        assertTrue(link("heating", "monday"))
        assertEquals(emptyList<String>(), skips())

        dao.markReadWithoutTopics("monday", chars = 50, now = 6L)
        dao.forgetSession("monday")

        assertEquals(emptyList<String>(), skips())
        assertEquals(listOf("monday"), dao.observeUnmappedSessionIds(minChars = 40).first())
    }

    @Test
    fun deletingARecordingDeletesItsMarkAndOneThatIsGoneGetsNone() = runBlocking {
        session("monday", createdAt = 100L)
        segment("monday", "m".repeat(50))
        dao.markReadWithoutTopics("monday", chars = 50, now = 5L)

        dao.deleteSessionAndTidyMap("monday")
        dao.markReadWithoutTopics("deleted-recording", chars = 0, now = 6L)

        assertEquals(emptyList<String>(), skips())
    }

    // ========== forgetSession, cleanup and deleting recordings ==========

    @Test
    fun forgetSessionDropsItsLinksMarksItsThemesStaleAndKeepsTheNodes() = runBlocking {
        mapTwoRecordings()

        dao.forgetSession("monday")

        assertEquals(listOf("both/tuesday", "tuesdayOnly/tuesday"), linkKeys())
        assertEquals(listOf(true, true, false), listOf("shared", "mondays", "tuesdays").map { stored(it).summaryStale })
        assertEquals(
            listOf("both", "mondayOnly", "mondays", "mondays-topic", "shared", "tuesdayOnly", "tuesdays"),
            dao.observeAllNodes().first().map { it.id }.sorted()
        )
    }

    @Test
    fun cleanupRemovesTopicsWithoutLinksThenThemesWithoutTopics() = runBlocking {
        session("monday", createdAt = 100L)
        theme("kept")
        topic("linked", "kept")
        topic("unlinked", "kept")
        theme("emptied")
        topic("also-unlinked", "emptied")
        theme("never-had-topics")
        link("linked", "monday")

        dao.cleanup()

        assertEquals(listOf("kept", "linked"), dao.observeAllNodes().first().map { it.id })
    }

    @Test
    fun deletingARecordingKeepsTheMapWhole() = runBlocking {
        mapTwoRecordings()
        segment("monday", "Monday's only line")
        db.todoDao().upsert(todo("call-the-landlord", sessionId = "monday"))

        dao.deleteSessionAndTidyMap("monday")

        // The theme only Monday held is gone with its topic; the one Tuesday still holds stays,
        // stale, without Monday's topic
        assertEquals(
            listOf("both", "shared", "tuesdayOnly", "tuesdays"),
            dao.observeAllNodes().first().map { it.id }.sorted()
        )
        assertTrue(stored("shared").summaryStale)
        assertFalse(stored("tuesdays").summaryStale)
        assertEquals(listOf("both/tuesday", "tuesdayOnly/tuesday"), linkKeys())
        assertEquals(listOf(Triple("shared", 1, 200L), Triple("tuesdays", 1, 200L)), themes())
        assertEquals(emptyList<TranscriptionSegmentEntity>(), db.transcriptionSegmentDao().getSegmentsBySession("monday").first())
        assertNull(db.todoDao().get("call-the-landlord")!!.sessionId)
    }

    @Test
    fun deletingEveryRecordingDeletesTheWholeMap() = runBlocking {
        mapTwoRecordings()
        dao.setPinned("shared", true)
        db.todoDao().upsert(todo("call-the-landlord", sessionId = "monday"))

        dao.deleteAllSessionsAndMap()

        assertEquals(emptyList<MindNodeEntity>(), dao.observeAllNodes().first())
        assertEquals(emptyList<String>(), linkKeys())
        assertNull(db.todoDao().get("call-the-landlord")!!.sessionId)
    }

    // ========== Helpers ==========

    /**
     * Monday and Tuesday share the theme "shared" (Monday through two of its topics); "mondays"
     * holds only Monday and "tuesdays" only Tuesday.
     */
    private suspend fun mapTwoRecordings() {
        session("monday", createdAt = 100L)
        session("tuesday", createdAt = 200L)
        theme("shared")
        theme("mondays")
        theme("tuesdays")
        topic("both", "shared")
        topic("mondayOnly", "shared")
        topic("mondays-topic", "mondays")
        topic("tuesdayOnly", "tuesdays")
        link("both", "monday")
        link("mondayOnly", "monday")
        link("mondays-topic", "monday")
        link("both", "tuesday")
        link("tuesdayOnly", "tuesday")
    }

    private suspend fun themeWithRecordings(id: String, label: String, vararg sessionIds: String) {
        theme(id, label = label)
        topic("$id-topic", id, label = label)
        sessionIds.forEach { link("$id-topic", it) }
    }

    private suspend fun session(id: String, createdAt: Long) {
        db.transcriptionSessionDao().insert(
            TranscriptionSessionEntity(id = id, name = null, createdAt = createdAt, lastModifiedAt = createdAt)
        )
    }

    private var segments = 0

    private suspend fun segment(sessionId: String, text: String, complete: Boolean = true) {
        segments++
        db.transcriptionSegmentDao().insert(
            TranscriptionSegmentEntity(
                id = "$sessionId-seg$segments", sessionId = sessionId, text = text,
                timestamp = segments.toLong(), isComplete = complete
            )
        )
    }

    private suspend fun theme(id: String, label: String = id, createdAt: Long = 0L) {
        assertTrue(dao.upsertNode(node(id, THEME, label = label, createdAt = createdAt)))
    }

    private suspend fun topic(id: String, themeId: String, label: String = id, createdAt: Long = 0L) {
        assertTrue(dao.upsertNode(node(id, TOPIC, label = label, parentId = themeId, createdAt = createdAt)))
    }

    private suspend fun link(topicId: String, sessionId: String, points: String = "[]", at: Long = 0L): Boolean =
        dao.link(MindLinkEntity(nodeId = topicId, sessionId = sessionId, points = points, segmentIds = "[]", createdAt = at))

    private suspend fun stored(id: String): MindNodeEntity = dao.getNode(id)!!

    private suspend fun themes(): List<Triple<String, Int, Long>> =
        dao.observeThemes().first().map { Triple(it.node.id, it.recordingCount, it.lastRecordedAt) }

    private suspend fun children(parentId: String): List<Triple<String, Int, Long>> =
        dao.observeChildren(parentId).first().map { Triple(it.node.id, it.recordingCount, it.lastRecordedAt) }

    /** Every link as "topic/recording", in storing order. */
    private fun linkKeys(): List<String> =
        db.openHelper.readableDatabase.query("SELECT node_id || '/' || session_id FROM mind_links ORDER BY rowid").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }

    /** Every mark of a read without topics as "recording/characters", by recording. */
    private fun skips(): List<String> =
        db.openHelper.readableDatabase.query("SELECT session_id || '/' || chars FROM mind_map_skips ORDER BY session_id").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }

    private companion object {
        const val THEME = MindMapDao.THEME
        const val TOPIC = MindMapDao.TOPIC

        fun node(
            id: String,
            kind: String,
            label: String = id,
            matchKey: String = label.lowercase(),
            parentId: String? = null,
            summary: String? = null,
            summaryUpdatedAt: Long? = null,
            summaryStale: Boolean = false,
            pinned: Boolean = false,
            createdAt: Long = 0L,
            updatedAt: Long = createdAt
        ) = MindNodeEntity(
            id = id, kind = kind, label = label, matchKey = matchKey, parentId = parentId, summary = summary,
            summaryUpdatedAt = summaryUpdatedAt, summaryStale = summaryStale, pinned = pinned,
            createdAt = createdAt, updatedAt = updatedAt
        )

        fun todo(id: String, sessionId: String) = TodoEntity(
            id = id, sessionId = sessionId, text = "Text of $id", kind = "TASK", status = "OPEN",
            certainty = "CONFIRMED", capture = "EXPLICIT", dueAt = null, dueHasTime = false, timePhrase = null,
            remindAt = null, reminderShownAt = null, sourceSegmentIds = "[]", createdAt = 0L, updatedAt = 0L,
            completedAt = null
        )
    }
}
