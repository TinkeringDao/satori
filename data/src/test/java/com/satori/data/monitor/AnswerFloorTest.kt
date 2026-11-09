package com.satori.data.monitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first answer's wait for memory: none over the floor; under it, until MemFree is back at the
 * resume level, until MemFree and Cached stand still (the floor then drops, as for the loads), or
 * 10 s at most, and the answer begins either way; each with its line; a debug build's switch; and a
 * cancel ends it.
 */
class AnswerFloorTest {

    private var nowNs = 0L
    private val lines = mutableListOf<String>()
    private val floorLines = mutableListOf<String>()
    private val floor = MemoryFloor.fallback(log = { floorLines += it }, warn = { floorLines += "W $it" })

    private fun answerFloor(
        memory: () -> FreeMemory?,
        debuggable: Boolean = false,
        property: String? = "",
        pause: suspend (Long) -> Unit = { ms -> nowNs += ms * 1_000_000 }
    ) = AnswerFloor(
        floor = floor,
        debuggable = debuggable,
        read = { name ->
            if (!debuggable) throw AssertionError("a release build read $name")
            when (name) {
                AnswerFloor.PROPERTY -> property
                MemoryFloor.FLOOR_PROPERTY, MemoryFloor.STILL_PROPERTY -> ""
                else -> throw AssertionError("read $name")
            }
        },
        memory = memory,
        log = { lines += it },
        warn = { lines += "W $it" },
        clockNs = { nowNs },
        pause = pause
    )

    /** Readings in turn, the last one again and again. */
    private fun readings(vararg mb: Pair<Long, Long>): () -> FreeMemory {
        val left = ArrayDeque(mb.toList())
        return {
            val (free, cached) = if (left.size > 1) left.removeFirst() else left.first()
            FreeMemory(free, cached)
        }
    }

    @Test
    fun `with MemFree over the floor the answer begins at once, and nothing is logged`() = runBlocking {
        answerFloor(readings(299L to 2_000L)).await("gemma-3-1b-it-Q8_0.gguf")

        assertEquals(0L, nowNs)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `under the floor it waits until MemFree is back at the resume level, then says how long`() = runBlocking {
        // kswapd at work: MemFree rising 15 MB a poll, Cached falling.
        answerFloor(readings(132L to 2_000L, 147L to 1_985L, 162L to 1_970L, 177L to 1_955L, 192L to 1_940L, 207L to 1_925L))
            .await("gemma-3-1b-it-Q8_0.gguf")

        assertEquals(250_000_000L, nowNs)
        assertEquals(
            listOf("First answer on gemma-3-1b-it-Q8_0.gguf waited 250 ms for memory: MemFree 132 MB as it waited, 207 MB as it began (floor 150 MB)"),
            lines
        )
    }

    @Test
    fun `MemFree and Cached standing still end the wait after 500 ms, and the floor drops for the loads too`() = runBlocking {
        answerFloor(readings(141L to 2_000L)).await("gemma-3-1b-it-Q8_0.gguf")

        assertEquals(500_000_000L, nowNs)
        assertEquals(117L, floor.floorMb)
        assertEquals(
            listOf(
                "First answer on gemma-3-1b-it-Q8_0.gguf waited 500 ms: MemFree stood still at 141 MB under the floor of 150 MB, " +
                    "so the kernel wasn't reclaiming; it began (floor now 117 MB)"
            ),
            lines
        )
        assertTrue(floorLines.last(), floorLines.last().endsWith("in the wait before the first answer on gemma-3-1b-it-Q8_0.gguf, so the kernel wasn't reclaiming"))
    }

    @Test
    fun `after 10 s of waiting the answer begins all the same, with a warning`() = runBlocking {
        // Another app takes memory as fast as kswapd frees it: Cached falls, MemFree stays under the floor.
        var cached = 2_000L
        answerFloor({ FreeMemory(118L, cached).also { cached -= 10 } }).await("gemma-4-E2B_q4_0-it.gguf")

        assertEquals(AnswerFloor.GIVE_UP_MS * 1_000_000, nowNs)
        assertEquals(
            listOf("W First answer on gemma-4-E2B_q4_0-it.gguf began after 10000 ms of waiting for memory: MemFree 118 MB, under its floor of 150 MB"),
            lines
        )
    }

    @Test
    fun `MemFree that can't be read never holds an answer`() = runBlocking {
        answerFloor({ null }).await("gemma-3-1b-it-Q8_0.gguf")
        val left = ArrayDeque(listOf<FreeMemory?>(FreeMemory(120L, 2_000L), null))
        answerFloor({ left.removeFirstOrNull() }).await("gemma-3-1b-it-Q8_0.gguf")

        assertEquals(50_000_000L, nowNs)
        assertEquals(listOf("First answer on gemma-3-1b-it-Q8_0.gguf waited 50 ms for memory, then MemFree couldn't be read; it began"), lines)
    }

    @Test
    fun `a debug build's property off begins the answer at once, a value it doesn't know waits with a warning, and a release build reads nothing`() = runBlocking {
        val low = readings(120L to 2_000L, 120L to 1_980L, 210L to 1_960L)
        answerFloor(low, debuggable = true, property = "off").await("gemma-3-1b-it-Q8_0.gguf")
        assertEquals(0L, nowNs)
        assertTrue(lines.isEmpty())

        answerFloor(low, debuggable = true, property = "sometimes").await("gemma-3-1b-it-Q8_0.gguf")
        assertEquals("W Ignoring debug.satori.llm.answerfloor=sometimes: not on or off", lines.first())
        assertTrue(lines.last(), lines.last().startsWith("First answer on gemma-3-1b-it-Q8_0.gguf waited 100 ms for memory"))

        // A release build: it waits, and the property's reader would throw if asked.
        lines.clear()
        answerFloor(readings(120L to 2_000L, 210L to 1_980L)).await("gemma-3-1b-it-Q8_0.gguf")
        assertTrue(lines.single(), lines.single().startsWith("First answer on gemma-3-1b-it-Q8_0.gguf waited 50 ms for memory"))
    }

    @Test
    fun `a cancel while it waits ends the wait`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val floorWait = answerFloor({ FreeMemory(118L, 2_000L - nowNs / 1_000_000) }, pause = { ms ->
            waiting.complete(Unit)
            delay(ms)
        })

        val answer = async(Dispatchers.Default) { floorWait.await("gemma-3-1b-it-Q8_0.gguf") }
        waiting.await()
        answer.cancel()

        assertTrue(runCatching { withTimeout(5_000) { answer.await() } }.exceptionOrNull() is CancellationException)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `none never waits`() = runBlocking {
        AnswerFloor.NONE.await("gemma-3-1b-it-Q8_0.gguf")
        assertEquals(0L, nowNs)
    }
}
