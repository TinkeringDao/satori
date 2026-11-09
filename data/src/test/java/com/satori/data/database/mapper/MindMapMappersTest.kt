package com.satori.data.database.mapper

import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.MindNodeCountRow
import com.satori.data.database.entity.MindNodeEntity
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The node mappers: every field both ways, and the kind as the strings the DAO's queries filter
 * on. The link mappers read JSON with org.json, which only runs on a device; MindMapRepositoryImplTest
 * covers them there.
 */
class MindMapMappersTest {

    @Test
    fun aThemeMapsBothWays() {
        val theme = MindNode(
            id = "heating", kind = MindNodeKind.THEME, label = "Heizung", matchKey = "heizung",
            summary = "Die Reparatur kommt diesen Monat.", summaryUpdatedAt = 30L, summaryStale = true,
            pinned = true, createdAt = 10L, updatedAt = 30L
        )

        val entity = theme.toEntity()

        assertEquals(
            MindNodeEntity(
                id = "heating", kind = MindMapDao.THEME, label = "Heizung", matchKey = "heizung", parentId = null,
                summary = "Die Reparatur kommt diesen Monat.", summaryUpdatedAt = 30L, summaryStale = true,
                pinned = true, createdAt = 10L, updatedAt = 30L
            ),
            entity
        )
        assertEquals(theme, entity.toDomain())
    }

    @Test
    fun aTopicMapsBothWays() {
        val topic = MindNode(
            id = "repair", kind = MindNodeKind.TOPIC, label = "Heating repair", matchKey = "heating repair",
            parentId = "heating", createdAt = 20L, updatedAt = 21L
        )

        val entity = topic.toEntity()

        assertEquals(MindMapDao.TOPIC, entity.kind)
        assertEquals("heating", entity.parentId)
        assertEquals(topic, entity.toDomain())
    }

    @Test
    fun aKindThisVersionDoesNotKnowReadsAsATopic() {
        val entity = MindNode(
            id = "x", kind = MindNodeKind.THEME, label = "X", matchKey = "x", createdAt = 0L, updatedAt = 0L
        ).toEntity().copy(kind = "PERSON")

        assertEquals(MindNodeKind.TOPIC, entity.toDomain().kind)
    }

    @Test
    fun theStoredKindsAreTheDaosStrings() {
        assertEquals(MindMapDao.THEME, MindNodeKind.THEME.stored())
        assertEquals(MindMapDao.TOPIC, MindNodeKind.TOPIC.stored())
        assertEquals(MindNodeKind.THEME, mindNodeKindOf(MindMapDao.THEME))
        assertEquals(MindNodeKind.TOPIC, mindNodeKindOf(MindMapDao.TOPIC))
    }

    @Test
    fun aCountedRowMapsToAnItem() {
        val theme = MindNode(
            id = "heating", kind = MindNodeKind.THEME, label = "Heating", matchKey = "heating",
            createdAt = 1L, updatedAt = 2L
        )

        val item = MindNodeCountRow(theme.toEntity(), recordingCount = 3, lastRecordedAt = 99L).toDomain()

        assertEquals(MindNodeItem(theme, recordingCount = 3, lastRecordedAt = 99L), item)
    }
}
