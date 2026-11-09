package com.satori.data.database.mapper

import com.satori.data.database.dao.MindLinkRow
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.MindNodeCountRow
import com.satori.data.database.dao.MindSessionTopicRow
import com.satori.data.database.entity.MindNodeEntity
import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.MindSessionTopic

/**
 * Mappers between the mind map's rows and its domain models.
 *
 * The kind is stored as [MindMapDao.THEME] or [MindMapDao.TOPIC], the strings the DAO's queries
 * filter on, so they are defined in one place. A link's points and segment ids are JSON array
 * strings ([serializeStringList]).
 */

// ========== Node Mappers ==========

fun MindNodeEntity.toDomain(): MindNode {
    return MindNode(
        id = id,
        kind = mindNodeKindOf(kind),
        label = label,
        matchKey = matchKey,
        parentId = parentId,
        summary = summary,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryStale = summaryStale,
        pinned = pinned,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun MindNode.toEntity(): MindNodeEntity {
    return MindNodeEntity(
        id = id,
        kind = kind.stored(),
        label = label,
        matchKey = matchKey,
        parentId = parentId,
        summary = summary,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryStale = summaryStale,
        pinned = pinned,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun MindNodeCountRow.toDomain(): MindNodeItem {
    return MindNodeItem(
        node = node.toDomain(),
        recordingCount = recordingCount,
        lastRecordedAt = lastRecordedAt
    )
}

/** mind_nodes.kind for [this] kind. */
internal fun MindNodeKind.stored(): String = when (this) {
    MindNodeKind.THEME -> MindMapDao.THEME
    MindNodeKind.TOPIC -> MindMapDao.TOPIC
}

/**
 * The kind stored as [kind]. Anything but a theme reads as a topic: the queries list a node as a
 * theme only when it is stored as one, so a value this version doesn't know never reaches the
 * map's top level.
 */
internal fun mindNodeKindOf(kind: String): MindNodeKind =
    if (kind == MindMapDao.THEME) MindNodeKind.THEME else MindNodeKind.TOPIC

// ========== Link Mappers ==========

fun MindLinkRow.toDomain(): MindLink {
    return MindLink(
        topicId = link.nodeId,
        sessionId = link.sessionId,
        points = parseStringList(link.points),
        segmentIds = parseStringList(link.segmentIds),
        createdAt = link.createdAt,
        recordedAt = recordedAt
    )
}

fun MindSessionTopicRow.toDomain(): MindSessionTopic {
    return MindSessionTopic(
        topic = topic.toDomain(),
        theme = theme.toDomain(),
        link = MindLinkRow(link, recordedAt).toDomain()
    )
}
