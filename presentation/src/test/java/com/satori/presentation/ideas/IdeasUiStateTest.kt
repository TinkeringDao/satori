package com.satori.presentation.ideas

import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdeasUiStateTest {

    @Test
    fun `ideas are grouped by recording, the group with the newest idea first and newest first in it`() {
        val recordings = listOf(recording("evening", createdAt = 3_000), recording("morning", createdAt = 1_000))
        val ideas = listOf(
            idea("e1", "evening", createdAt = 3_500),
            idea("m1", "morning", createdAt = 1_500),
            idea("e2", "evening", createdAt = 3_600)
        )

        val groups = IdeasUiState.group(ideas, recordings)

        assertEquals(listOf("evening", "morning"), groups.map { it.recording?.sessionId })
        assertEquals(listOf("e2", "e1"), groups[0].ideas.map { it.id })
        assertEquals(listOf("m1"), groups[1].ideas.map { it.id })
    }

    @Test
    fun `an older recording whose ideas were just found again comes first`() {
        val recordings = listOf(recording("evening", createdAt = 3_000), recording("morning", createdAt = 1_000))
        val ideas = listOf(
            idea("e1", "evening", createdAt = 3_500),
            idea("m1", "morning", createdAt = 1_500),
            idea("m2", "morning", createdAt = 9_000)
        )

        assertEquals(listOf("morning", "evening"), IdeasUiState.group(ideas, recordings).map { it.recording?.sessionId })
    }

    @Test
    fun `recordings found at the same time follow the newer recording`() {
        // The ids sort the other way round, so only the recordings' dates can give this order.
        val recordings = listOf(recording("a-older", createdAt = 1_000), recording("b-newer", createdAt = 2_000))
        val ideas = listOf(idea("o1", "a-older", createdAt = 5_000), idea("n1", "b-newer", createdAt = 5_000))

        assertEquals(listOf("b-newer", "a-older"), IdeasUiState.group(ideas, recordings).map { it.recording?.sessionId })
    }

    @Test
    fun `ideas of deleted recordings found at the same time as a recording's come after them`() {
        val recordings = listOf(recording("kept", createdAt = 1_000))
        val ideas = listOf(idea("gone-1", sessionId = null, createdAt = 5_000), idea("k1", "kept", createdAt = 5_000))

        assertEquals(listOf("kept", null), IdeasUiState.group(ideas, recordings).map { it.recording?.sessionId })
    }

    @Test
    fun `the ideas of deleted recordings make one group without a recording`() {
        val recordings = listOf(recording("kept", createdAt = 1_000, name = "Walk by the river"))
        val ideas = listOf(
            idea("gone-1", sessionId = null, createdAt = 2_000),
            idea("k1", "kept", createdAt = 1_500),
            // Its recording was deleted while the recordings were read.
            idea("gone-2", sessionId = "deleted-meanwhile", createdAt = 500)
        )

        val groups = IdeasUiState.group(ideas, recordings)

        assertEquals(2, groups.size)
        assertNull(groups[0].recording)
        assertEquals(listOf("gone-1", "gone-2"), groups[0].ideas.map { it.id })
        assertEquals(
            IdeaRecording("kept", RecordingTitle("Walk by the river", null, RecordingMode.SIMPLE_LISTENING), 1_000),
            groups[1].recording
        )
    }

    @Test
    fun `the order follows what is stored, so an idea Undo brings back returns to its place`() {
        val recordings = listOf(recording("s1", createdAt = 1_000))
        val ideas = listOf(
            idea("a", "s1", createdAt = 2_000),
            idea("b", "s1", createdAt = 2_000),
            idea("c", "s1", createdAt = 1_000)
        )
        val deleted = ideas.filter { it.id != "b" }

        val before = IdeasUiState.group(ideas, recordings)
        val afterDelete = IdeasUiState.group(deleted, recordings)
        // However the store returns it once restored, it lands where it was.
        val afterUndo = IdeasUiState.group(listOf(ideas[1]) + deleted.reversed(), recordings)

        assertEquals(listOf("a", "b", "c"), before.single().ideas.map { it.id })
        assertEquals(listOf("a", "c"), afterDelete.single().ideas.map { it.id })
        assertEquals(before, afterUndo)
    }

    @Test
    fun `an idea's sheet finds it with its recording, which opens at the line it was said in`() {
        val state = IdeasUiState(
            groups = IdeasUiState.group(
                listOf(idea("i1", "s1", createdAt = 2_000, segments = listOf("seg-3", "seg-4"))),
                listOf(recording("s1", createdAt = 1_000))
            ),
            isLoading = false
        )

        val listed = state.idea("i1")

        assertEquals("s1", listed?.recording?.sessionId)
        assertEquals("seg-3", listed?.firstSourceSegment)
        assertNull(state.idea("made-a-to-do"))
        assertNull(state.idea(null))
    }

    @Test
    fun `an idea without lines opens its recording at the top, and one without a recording opens none`() {
        val state = IdeasUiState(
            groups = IdeasUiState.group(
                listOf(idea("i1", "s1", createdAt = 2_000), idea("i2", sessionId = null, createdAt = 1_000)),
                listOf(recording("s1", createdAt = 1_000))
            ),
            isLoading = false
        )

        assertNull(state.idea("i1")?.firstSourceSegment)
        assertNull(state.idea("i2")?.recording)
    }
}
