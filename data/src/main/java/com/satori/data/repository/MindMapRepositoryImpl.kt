package com.satori.data.repository

import androidx.room.withTransaction
import com.satori.data.database.AppDatabase
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.MindNodeCountRow
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.mapper.serializeStringList
import com.satori.data.database.mapper.toDomain
import com.satori.data.database.mapper.toEntity
import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.repository.MindMapRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [MindMapRepository] on Room ([MindMapDao]).
 *
 * The rules of the map live in the DAO, in SQL and in its transactions: counts and orders in the
 * queries, the two-level tree in [MindMapDao.upsertNode], topic-only links in [MindMapDao.link].
 * Deleting a recording tidies the map in TranscriptionRepositoryImpl, through
 * [MindMapDao.deleteSessionAndTidyMap].
 *
 * The lists that count or date read transcription_sessions, which a live recording writes every
 * few seconds (its last-modified time), and unmappedSessionIds reads its segments, so Room re-runs
 * them all through a recording; the identical results are dropped here so the map does not redraw.
 *
 * @property database The database [inTransaction] runs its transactions on
 */
class MindMapRepositoryImpl @Inject constructor(
    private val mindMapDao: MindMapDao,
    private val database: AppDatabase
) : MindMapRepository {

    override fun themes(): Flow<List<MindNodeItem>> = mindMapDao.observeThemes().mapItems()

    override fun children(parentId: String): Flow<List<MindNodeItem>> =
        mindMapDao.observeChildren(parentId).mapItems()

    override fun node(id: String): Flow<MindNode?> =
        mindMapDao.observeNode(id).map { it?.toDomain() }.distinctUntilChanged()

    override fun links(nodeId: String): Flow<List<MindLink>> =
        mindMapDao.observeLinks(nodeId).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun nodesForSession(sessionId: String): Flow<List<MindSessionTopic>> =
        mindMapDao.observeSessionTopics(sessionId).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun allNodes(): Flow<List<MindNode>> =
        mindMapDao.observeAllNodes().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun upsertNode(node: MindNode) {
        mindMapDao.upsertNode(node.toEntity())
    }

    override suspend fun link(
        topicId: String,
        sessionId: String,
        points: List<String>,
        segmentIds: List<String>,
        at: Long
    ) {
        mindMapDao.link(
            MindLinkEntity(
                nodeId = topicId,
                sessionId = sessionId,
                points = serializeStringList(points),
                segmentIds = serializeStringList(segmentIds),
                createdAt = at
            )
        )
    }

    override suspend fun setSummary(nodeId: String, summary: String, at: Long) {
        mindMapDao.setSummary(nodeId, summary, at)
    }

    override suspend fun markStale(nodeIds: Collection<String>) {
        mindMapDao.markStale(nodeIds)
    }

    override suspend fun setPinned(nodeId: String, pinned: Boolean) {
        mindMapDao.setPinned(nodeId, pinned)
    }

    override fun unmappedSessionIds(): Flow<List<String>> =
        mindMapDao.observeUnmappedSessionIds(MindMapRepository.MIN_TRANSCRIPT_CHARS).distinctUntilChanged()

    override suspend fun forgetSession(sessionId: String) {
        mindMapDao.forgetSession(sessionId)
    }

    override suspend fun cleanup() {
        mindMapDao.cleanup()
    }

    override suspend fun markReadWithoutTopics(sessionId: String, chars: Int) {
        mindMapDao.markReadWithoutTopics(sessionId, chars, System.currentTimeMillis())
    }

    /**
     * Room's transaction: the DAO's suspend calls inside it run on its connection, so they see
     * its writes and are rolled back with it.
     */
    override suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction { block() }

    override suspend fun allNodesNow(): List<MindNode> = mindMapDao.getAllNodes().map { it.toDomain() }

    private fun Flow<List<MindNodeCountRow>>.mapItems(): Flow<List<MindNodeItem>> =
        map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
}
