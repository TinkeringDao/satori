package com.satori.data.database.dao

import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin bodies of [MindMapDao]'s transactions on the JVM, with its queries answered by an
 * in-memory stand-in ([FakeQueries]) that records the writes: which node writes [MindMapDao.upsertNode]
 * makes, which links [MindMapDao.link] lets through, how [MindMapDao.markStale] splits its ids,
 * the order of the steps of forgetting, tidying and deleting, and that linking or forgetting a
 * recording removes its mark of a read without topics. The SQL itself runs in the instrumented
 * MindMapDaoTest.
 */
class MindMapDaoTransactionsTest {

    private val dao = FakeQueries()

    @Test
    fun upsertNodeInsertsANewThemeWithoutAParent() = runBlocking {
        assertTrue(dao.upsertNode(node("heating", THEME, parentId = "elsewhere", summary = "Kept", pinned = true)))

        assertEquals(listOf("insert heating"), dao.writes)
        assertEquals(node("heating", THEME, summary = "Kept", pinned = true), dao.nodes["heating"])
    }

    @Test
    fun upsertNodeOfAStoredNodeOnlyUpdatesAndKeepsTheStoredKind() = runBlocking {
        dao.nodes["heating"] = node("heating", THEME)

        assertTrue(dao.upsertNode(node("heating", TOPIC, label = "Renamed", parentId = "elsewhere", updatedAt = 7L)))

        assertEquals(listOf("update heating label=Renamed parent=null updatedAt=7"), dao.writes)
    }

    @Test
    fun upsertNodeStoresATopicOnlyUnderATheme() = runBlocking {
        dao.nodes["heating"] = node("heating", THEME)
        dao.nodes["repair"] = node("repair", TOPIC, parentId = "heating")

        assertFalse(dao.upsertNode(node("gone", TOPIC, parentId = "deleted-theme")))
        assertFalse(dao.upsertNode(node("nested", TOPIC, parentId = "repair")))
        assertFalse(dao.upsertNode(node("orphan", TOPIC, parentId = null)))
        assertFalse(dao.upsertNode(node("repair", TOPIC, parentId = "deleted-theme")))
        assertEquals(emptyList<String>(), dao.writes)

        assertTrue(dao.upsertNode(node("bill", TOPIC, parentId = "heating", summary = "Not for topics", pinned = true)))
        assertTrue(dao.upsertNode(node("repair", TOPIC, label = "Repair", parentId = "heating", updatedAt = 3L)))

        assertEquals(listOf("insert bill", "update repair label=Repair parent=heating updatedAt=3"), dao.writes)
        assertEquals(node("bill", TOPIC, parentId = "heating"), dao.nodes["bill"])
    }

    @Test
    fun linkIsStoredOnlyForATopicOfAnExistingRecording() = runBlocking {
        dao.nodes["heating"] = node("heating", THEME)
        dao.nodes["repair"] = node("repair", TOPIC, parentId = "heating")
        dao.sessions += "monday"

        assertFalse(dao.link(link("heating", "monday")))
        assertFalse(dao.link(link("unknown", "monday")))
        assertFalse(dao.link(link("repair", "deleted-recording")))
        assertTrue(dao.link(link("repair", "monday")))

        // A linked recording is mapped: its mark of a read without topics goes with the link.
        assertEquals(listOf("link repair/monday", "deleteSkipOf monday"), dao.writes)
    }

    @Test
    fun markStaleSendsEachIdOnceInListsTheDatabaseAccepts() = runBlocking {
        val ids = List(1_201) { "theme-$it" }

        dao.markStale(ids + ids.take(10))
        dao.markStale(emptyList())

        assertEquals(
            listOf("markStale 500", "markStale 500", "markStale 201"),
            dao.writes
        )
        assertEquals(ids, dao.markedStale)
    }

    @Test
    fun forgetSessionMarksItsThemesStaleBeforeItsLinksGoAndDropsItsMark() = runBlocking {
        dao.forgetSession("monday")

        assertEquals(listOf("markThemesOf monday", "deleteLinksOf monday", "deleteSkipOf monday"), dao.writes)
    }

    @Test
    fun cleanupRemovesTopicsBeforeThemes() = runBlocking {
        dao.cleanup()

        assertEquals(listOf("deleteTopicsWithoutLinks", "deleteThemesWithoutTopics"), dao.writes)
    }

    @Test
    fun deletingARecordingMarksItsThemesWhileItsLinksExistThenTidies() = runBlocking {
        dao.deleteSessionAndTidyMap("monday")

        assertEquals(
            listOf("markThemesOf monday", "deleteSession monday", "deleteTopicsWithoutLinks", "deleteThemesWithoutTopics"),
            dao.writes
        )
    }

    @Test
    fun deletingEveryRecordingTidiesAfterwards() = runBlocking {
        dao.deleteAllSessionsAndMap()

        assertEquals(listOf("deleteAllSessions", "deleteTopicsWithoutLinks", "deleteThemesWithoutTopics"), dao.writes)
    }

    /** The queries of [MindMapDao] on maps, with every write recorded in [writes]. */
    private class FakeQueries : MindMapDao() {
        val nodes = linkedMapOf<String, MindNodeEntity>()
        val sessions = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        val markedStale = mutableListOf<String>()

        override suspend fun getNode(id: String): MindNodeEntity? = nodes[id]
        override suspend fun isTheme(id: String): Boolean = nodes[id]?.kind == THEME
        override suspend fun isTopic(id: String): Boolean = nodes[id]?.kind == TOPIC
        override suspend fun sessionExists(sessionId: String): Boolean = sessionId in sessions

        override suspend fun insertNode(node: MindNodeEntity) {
            check(node.id !in nodes) { "insertNode over a stored node" }
            nodes[node.id] = node
            writes += "insert ${node.id}"
        }

        override suspend fun updateNode(id: String, label: String, matchKey: String, parentId: String?, updatedAt: Long) {
            writes += "update $id label=$label parent=$parentId updatedAt=$updatedAt"
        }

        override suspend fun insertOrReplaceLink(link: MindLinkEntity) {
            writes += "link ${link.nodeId}/${link.sessionId}"
        }

        override suspend fun markStaleRows(ids: List<String>) {
            markedStale += ids
            writes += "markStale ${ids.size}"
        }

        override suspend fun markThemesOfSessionStale(sessionId: String) {
            writes += "markThemesOf $sessionId"
        }

        override suspend fun deleteLinksOfSession(sessionId: String) {
            writes += "deleteLinksOf $sessionId"
        }

        override suspend fun deleteTopicsWithoutLinks() {
            writes += "deleteTopicsWithoutLinks"
        }

        override suspend fun deleteThemesWithoutTopics() {
            writes += "deleteThemesWithoutTopics"
        }

        override suspend fun deleteSessionRow(sessionId: String) {
            writes += "deleteSession $sessionId"
        }

        override suspend fun deleteAllSessionRows() {
            writes += "deleteAllSessions"
        }

        override suspend fun deleteSkipOfSession(sessionId: String) {
            writes += "deleteSkipOf $sessionId"
        }

        override suspend fun markReadWithoutTopics(sessionId: String, chars: Int, now: Long) = unused()
        override suspend fun getAllNodes(): List<MindNodeEntity> = unused()
        override suspend fun setSummary(id: String, summary: String, at: Long) = unused()
        override suspend fun setPinned(id: String, pinned: Boolean) = unused()
        override fun observeThemes(): Flow<List<MindNodeCountRow>> = unused()
        override fun observeChildren(parentId: String): Flow<List<MindNodeCountRow>> = unused()
        override fun observeNode(id: String): Flow<MindNodeEntity?> = unused()
        override fun observeLinks(nodeId: String): Flow<List<MindLinkRow>> = unused()
        override fun observeSessionTopics(sessionId: String): Flow<List<MindSessionTopicRow>> = unused()
        override fun observeAllNodes(): Flow<List<MindNodeEntity>> = unused()
        override fun observeUnmappedSessionIds(minChars: Int): Flow<List<String>> = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("Not part of these tests")
    }

    private companion object {
        const val THEME = MindMapDao.THEME
        const val TOPIC = MindMapDao.TOPIC

        fun node(
            id: String,
            kind: String,
            label: String = id,
            parentId: String? = null,
            summary: String? = null,
            pinned: Boolean = false,
            updatedAt: Long = 0L
        ) = MindNodeEntity(
            id = id, kind = kind, label = label, matchKey = label.lowercase(), parentId = parentId,
            summary = summary, summaryUpdatedAt = null, summaryStale = false, pinned = pinned,
            createdAt = 0L, updatedAt = updatedAt
        )

        fun link(nodeId: String, sessionId: String) =
            MindLinkEntity(nodeId = nodeId, sessionId = sessionId, points = "[]", segmentIds = "[]", createdAt = 0L)
    }
}
