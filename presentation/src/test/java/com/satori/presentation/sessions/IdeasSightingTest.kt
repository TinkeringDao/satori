package com.satori.presentation.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which ideas home's row still counts as new, from what home and its owner did. */
class IdeasSightingTest {

    private val unseen = IdeasSighting(seenAt = 1_000)

    private val away = IdeasSighting.RETURN_WITHIN_MS

    @Test
    fun `what the row said when it was seen is no longer new once home stayed away`() {
        val left = unseen.shown(sinceMs = 3_000).left(nowMs = 4_000)

        // While home is away nothing changes yet: it may be a fold.
        assertEquals(1_000, left.seenAt)
        assertEquals(IdeasSighting(seenAt = 3_000), left.back(nowMs = 4_000 + away))
    }

    @Test
    fun `home back within a moment, as after a fold, keeps the count and what was seen`() {
        val folded = unseen.shown(sinceMs = 3_000).left(nowMs = 4_000).back(nowMs = 4_000 + away - 1)

        assertEquals(IdeasSighting(seenAt = 1_000, shownSince = 3_000), folded)
        // The leave that follows counts.
        assertEquals(3_000, folded.left(nowMs = 9_000).back(nowMs = 9_000 + away).seenAt)
    }

    @Test
    fun `home left without the row seen keeps every idea new`() {
        // Home went behind the lock screen as it started, or the row was never on screen.
        assertEquals(unseen, unseen.left(nowMs = 4_000).back(nowMs = 60_000))
    }

    @Test
    fun `leaving home for the ideas from the row leaves none new, at once`() {
        val opened = unseen.tapped().left(nowMs = 4_000)

        assertEquals(IdeasSighting(seenAt = 4_000), opened)
        assertEquals(opened, opened.back(nowMs = 4_100))
    }

    @Test
    fun `a leave counts from when home went, also when it stops twice`() {
        val left = unseen.shown(sinceMs = 3_000).left(nowMs = 4_000).left(nowMs = 5_500)

        assertEquals(3_000, left.back(nowMs = 4_000 + away).seenAt)
    }

    @Test
    fun `a later sighting covers more, and an earlier one doesn't take it back`() {
        assertEquals(5_000L, unseen.shown(sinceMs = 3_000).shown(sinceMs = 5_000).shownSince)
        assertEquals(5_000L, unseen.shown(sinceMs = 5_000).shown(sinceMs = 3_000).shownSince)
    }

    @Test
    fun `ideas once seen are never new again`() {
        // A clock set back while home was away: the leave still counts.
        assertEquals(3_000, unseen.shown(sinceMs = 3_000).left(nowMs = 4_000).back(nowMs = 2_000).seenAt)
        // A sighting from before the time already seen up to doesn't move it back.
        assertEquals(5_000, IdeasSighting(seenAt = 5_000, shownSince = 3_000, leftAt = 6_000).back(nowMs = 60_000).seenAt)
    }
}
