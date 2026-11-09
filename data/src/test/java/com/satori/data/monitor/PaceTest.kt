package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * The pace of a load: the speech models' 120 MB/s in every build, the property read only in a
 * debug build, over it, and no pre-read when off; and a load that takes memory at the rate set,
 * waits while MemFree is low (up to 10 s in all, its pre-read's and its weight copy's waits
 * together) and while the recorder's load runs, and never catches up after a wait.
 */
class PaceTest {

    private val warnings = mutableListOf<String>()
    private var nowNs = 0L
    private val sleeps = mutableListOf<Long>()

    // MemFree alone, Cached unread: the waits of 0.7.1, which the stand-still rule never ends.
    private fun pace(mbPerSecond: Int, memFree: () -> Long? = { 4_000L }, mustYield: () -> Boolean = { false }) =
        Pace(mbPerSecond, freeOnly(memFree), mustYield, clockNs = { nowNs }, sleepNs = { ns ->
            sleeps += ns
            nowNs += ns
        })

    private fun freeOnly(memFree: () -> Long?): () -> FreeMemory? = { memFree()?.let { FreeMemory(it, cachedMb = null) } }

    private fun pace(value: String?, debuggable: Boolean = true) =
        LoadPace.forSpeechLoad(debuggable, read = { if (it == LoadPace.PROPERTY) value else "" }, warn = { warnings += it })

    @Test
    fun `every build reads a speech model in at 120 MB per second first, a release build without reading the property`() {
        assertEquals(LoadPace(120), LoadPace.forSpeechLoad(debuggable = false, read = { throw AssertionError("read $it") }) { warnings += it })
        assertEquals(LoadPace(120), pace(""))
        assertEquals(LoadPace(120), pace("  "))
        assertEquals(LoadPace.SPEECH, pace(""))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a debug build's property off reads no speech model in first`() {
        assertNull(pace("off"))
        assertNull(pace(" OFF "))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a debug build takes a rate of 10 to 1000 MB per second`() {
        assertEquals(LoadPace(120), pace("120"))
        assertEquals(LoadPace(10), pace("10"))
        assertEquals(LoadPace(1000), pace("1000"))
        assertEquals(LoadPace(60), pace(" 60MB/s "))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a rate it doesn't know, or a property it can't read, leaves the load at 120 MB per second with a warning`() {
        assertEquals(LoadPace(120), pace("9"))
        assertEquals(LoadPace(120), pace("1001"))
        assertEquals(LoadPace(120), pace("fast"))
        assertEquals(LoadPace(120), pace(null))

        assertEquals(
            listOf(
                "Ignoring debug.satori.load.pace=9: not off or 10-1000 (MB/s)",
                "Ignoring debug.satori.load.pace=1001: not off or 10-1000 (MB/s)",
                "Ignoring debug.satori.load.pace=fast: not off or 10-1000 (MB/s)",
                "Ignoring debug.satori.load.pace: the property can't be read"
            ),
            warnings
        )
    }

    @Test
    fun `memory is taken at the rate set, and a piece that came slower than the rate isn't held back`() {
        val pace = pace(mbPerSecond = 100)

        // 8 MiB at 100 MB/s are due after 80 ms.
        pace.took(8L * 1_048_576)
        assertEquals(80_000_000L, nowNs)
        // A piece that took longer than its share by itself waits for nothing.
        nowNs += 200_000_000L
        pace.took(8L * 1_048_576)
        assertEquals(280_000_000L, nowNs)
        assertEquals(listOf(80_000_000L), sleeps)
    }

    @Test
    fun `under 150 MB free the next piece waits until 200 MB, and the wait doesn't count toward the rate`() {
        val free = ArrayDeque(listOf(140L, 160L, 190L, 210L))
        val pace = pace(mbPerSecond = 100, memFree = { free.removeFirstOrNull() ?: 4_000L })

        pace.awaitTurn()
        assertEquals(3 * Pace.POLL_MS * 1_000_000, pace.memoryWaitNs)
        assertEquals(140L, pace.lowestMemFreeMb)
        assertFalse(pace.gaveUp)

        // No burst after the wait: the next 8 MiB still take their 80 ms.
        val before = nowNs
        pace.took(8L * 1_048_576)
        assertEquals(80_000_000L, nowNs - before)
    }

    @Test
    fun `waiting for memory ends after 10 s in all, and the load then goes on at the rate only`() {
        val pace = pace(mbPerSecond = 100, memFree = { 100L })

        pace.awaitTurn()
        assertTrue(pace.gaveUp)
        assertEquals(Pace.GIVE_UP_MS * 1_000_000, pace.memoryWaitNs)

        val before = nowNs
        pace.awaitTurn()
        assertEquals(before, nowNs)
    }

    @Test
    fun `the paces of one load share its 10 s of waiting for memory, and another load's don't`() {
        val waits = MemoryWaits()
        // Short of memory for the first 6 s, during the pre-read, then again during the copy.
        var copying = false
        val memFree = { if (copying || nowNs < 6_000_000_000L) 100L else 4_000L }
        val preRead = Pace(100, freeOnly(memFree), { false }, clockNs = { nowNs }, sleepNs = { nowNs += it }, waits = waits)
        val copy = Pace(100, freeOnly(memFree), { false }, clockNs = { nowNs }, sleepNs = { nowNs += it }, waits = waits)

        preRead.awaitTurn()
        assertEquals(6_000_000_000L, preRead.memoryWaitNs)
        assertFalse(preRead.gaveUp)

        copying = true
        copy.awaitTurn()
        assertEquals(4_000_000_000L, copy.memoryWaitNs)
        assertTrue(copy.gaveUp)
        assertTrue(preRead.gaveUp)
        assertEquals(10_000_000_000L, waits.ns)

        // A pace of another load has 10 s of its own.
        val other = pace(mbPerSecond = 100, memFree = { 100L })
        other.awaitTurn()
        assertEquals(Pace.GIVE_UP_MS * 1_000_000, other.memoryWaitNs)
    }

    @Test
    fun `MemFree that can't be read never holds a load`() {
        val pace = pace(mbPerSecond = 100, memFree = { null })

        pace.awaitTurn()

        assertEquals(0L, nowNs)
        assertNull(pace.lowestMemFreeMb)
    }

    @Test
    fun `while the recorder's load runs the next piece waits for it, and that doesn't count toward the rate either`() {
        // The recorder's load runs for the first look and three more.
        var looks = 4
        val pace = pace(mbPerSecond = 100, mustYield = { looks-- > 0 })

        pace.awaitTurn()
        assertEquals(3 * Pace.POLL_MS * 1_000_000, pace.yieldNs)

        val before = nowNs
        pace.took(8L * 1_048_576)
        assertEquals(80_000_000L, nowNs - before)
    }

    @Test
    fun `a load whose caller went away stops while it waits`() {
        val waitingForMemory = pace(mbPerSecond = 100, memFree = { 100L })
        val waitingForRecorder = pace(mbPerSecond = 100, mustYield = { true })

        for (pace in listOf(waitingForMemory, waitingForRecorder)) {
            try {
                pace.awaitTurn(cancelled = { nowNs > 100_000_000L })
                fail("not cancelled")
            } catch (e: CancellationException) {
                // expected
            }
        }
    }
}
