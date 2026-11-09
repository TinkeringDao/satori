package com.satori.domain.usecase.finish

import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tail the last note is written from: the complete lines no note cites. */
class RecordingTailTest {

    private val first = segment("The parcel for the neighbours arrived this morning.", at = 1_000L)
    private val second = segment("It goes over to them after lunch.", at = 2_000L)
    private val third = segment("And the bike needs new brakes before the trip.", at = 3_000L)

    @Test
    fun `without notes the whole transcript is the tail`() {
        assertEquals(listOf(first, second, third), RecordingTail.of(listOf(first, second, third), emptyList()))
    }

    @Test
    fun `lines a live note cites are covered, the rest is the tail`() {
        val live = insight("Parcel", "The parcel arrived.", at = 1_500L).copy(sourceSegmentIds = listOf(first.id, second.id))

        assertEquals(listOf(third), RecordingTail.of(listOf(first, second, third), listOf(live)))
    }

    @Test
    fun `a final note that cites the tail leaves none`() {
        val live = insight("Parcel", "The parcel arrived.", at = 1_500L).copy(sourceSegmentIds = listOf(first.id))
        val final = insight("Brakes", "The bike needs brakes.", at = 3_500L).copy(sourceSegmentIds = listOf(second.id, third.id))

        assertTrue(RecordingTail.of(listOf(first, second, third), listOf(live, final)).isEmpty())
    }

    @Test
    fun `a line the recognizer had not finished is never in the tail`() {
        val partial = segment("And then we", at = 4_000L, complete = false)

        assertEquals(listOf(first), RecordingTail.of(listOf(first, partial), emptyList()))
    }

    @Test
    fun `the tail is in spoken order, and lines of the same time keep the order they came in`() {
        val sameTime = segment("Then the brakes.", at = 2_000L).copy(id = "same")

        assertEquals(
            listOf(first, second, sameTime, third),
            RecordingTail.of(listOf(third, second, sameTime, first), emptyList())
        )
    }
}
