package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stand-still rule of a paced wait (0.7.2): under the floor a wait ends once MemFree and Cached
 * have both stood still for 500 ms, and the floor drops 24 MB under that level, never under 64 MB;
 * while memory moves it waits, for kswapd to bring MemFree back or for 10 s at most. Then a model
 * of the kernel's reclaim, as the investigation of part B ran it: kswapd sleeps above the low
 * watermark, wakes under it and frees memory until MemFree is back at high, with the real [Pace]
 * driving a pre-read as [PacedPreRead] does. On 2026-10-17 at 14:27 the razr's watermarks (low
 * 121 MB, high 230 MB) left Gemma 4 E2B's pre-read waiting at 128-150 MB for a kswapd that never
 * woke, until the 10 s give-up.
 */
class PaceFloorTest {

    private var nowNs = 0L
    private val lines = mutableListOf<String>()

    private fun floor() = MemoryFloor.fallback(log = { lines += it }, warn = { lines += "W $it" })

    private fun pace(memory: () -> FreeMemory?, floor: MemoryFloor = floor(), still: Boolean = true, waits: MemoryWaits = MemoryWaits()) =
        Pace(100, memory, { false }, clockNs = { nowNs }, sleepNs = { nowNs += it }, waits = waits, floor = floor, still = still, where = "the pre-read of e2b")

    /** Readings in turn, the last one again and again. */
    private fun readings(vararg mb: Pair<Long, Long>): () -> FreeMemory {
        val left = ArrayDeque(mb.toList())
        return {
            val (free, cached) = if (left.size > 1) left.removeFirst() else left.first()
            FreeMemory(free, cached)
        }
    }

    @Test
    fun `MemFree and Cached standing still for 500 ms end the wait, and the floor drops 24 MB under where MemFree stood`() {
        val floor = floor()
        val pace = pace(readings(146L to 3_000L), floor)

        pace.awaitTurn()

        assertFalse(pace.gaveUp)
        assertEquals(500_000_000L, pace.memoryWaitNs)
        assertEquals(122L, floor.floorMb)
        assertEquals(172L, floor.resumeMb)
        assertEquals(
            "Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of e2b, " +
                "so the kernel wasn't reclaiming",
            lines.single()
        )
        assertEquals("waited 500 ms for memory in 1 wait (longest 500 ms: 1 stood still; floor 122 MB, lowest MemFree 146 MB)", pace.waitSummary())
        // The next piece finds MemFree over the floor now.
        val before = nowNs
        pace.awaitTurn()
        assertEquals(before, nowNs)
    }

    @Test
    fun `MemFree rising, kswapd at work, waits for the resume level and lowers nothing`() {
        val floor = floor()
        val pace = pace(readings(140L to 3_000L, 150L to 2_990L, 165L to 2_975L, 180L to 2_960L, 200L to 2_940L), floor)

        pace.awaitTurn()

        assertEquals(4 * Pace.POLL_MS * 1_000_000, pace.memoryWaitNs)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.isEmpty())
        assertEquals("waited 200 ms for memory in 1 wait (longest 200 ms: 1 until MemFree came back; floor 150 MB, lowest MemFree 140 MB)", pace.waitSummary())
    }

    @Test
    fun `MemFree falling, another app taking memory, waits on, and so does Cached falling while MemFree stays flat`() {
        // Another app takes 10 MB a poll for 2 s, then kswapd brings MemFree back.
        var polls = 0
        val falling = pace({
            polls++
            if (polls <= 40) FreeMemory(140L - polls, 3_000L) else FreeMemory(200L, 2_000L)
        })
        falling.awaitTurn()
        assertEquals(40 * Pace.POLL_MS * 1_000_000, falling.memoryWaitNs)

        // kswapd keeps up with another app: MemFree flat at 140 MB, Cached falling 10 MB a poll, until the 10 s run out.
        var cached = 3_000L
        val waits = MemoryWaits()
        val floor = floor()
        val flat = pace({ FreeMemory(140L, cached).also { cached -= 10 } }, floor, waits = waits)
        flat.awaitTurn()

        assertTrue(flat.gaveUp)
        assertEquals(Pace.GIVE_UP_MS * 1_000_000, flat.memoryWaitNs)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun `with the rule off, a debug build's debug_satori_mem_still off, it waits as 0_7_1 did, 10 s and no lower floor`() {
        val floor = floor()
        val pace = pace(readings(146L to 3_000L), floor, still = false)

        pace.awaitTurn()

        assertTrue(pace.gaveUp)
        assertEquals(Pace.GIVE_UP_MS * 1_000_000, pace.memoryWaitNs)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.isEmpty())
        assertEquals("waited 10000 ms for memory in 1 wait (longest 10000 ms: 1 gave up; floor 150 MB, lowest MemFree 146 MB)", pace.waitSummary())
    }

    @Test
    fun `SwapFree falling while MemFree and Cached stay flat, kswapd swapping another app out, waits on and lowers nothing`() {
        // r071-en-3-warm: about 100 MB/s to zram while another app took what kswapd freed and the
        // file pages came back as fast as they went: 5 MB of SwapFree a poll, the rest flat.
        var swapFree = 4_000L
        val floor = floor()
        val pace = pace({ FreeMemory(140L, 3_000L, swapFree).also { swapFree -= 5 } }, floor)

        pace.awaitTurn()

        assertTrue(pace.gaveUp)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun `SwapFree standing still with the rest ends the wait as before`() {
        val floor = floor()
        val pace = pace({ FreeMemory(146L, 3_000L, 4_000L) }, floor)

        pace.awaitTurn()

        assertFalse(pace.gaveUp)
        assertEquals(500_000_000L, pace.memoryWaitNs)
        assertEquals(122L, floor.floorMb)
    }

    @Test
    fun `without Cached, MemFree alone never ends a wait standing still`() {
        val floor = floor()
        val pace = pace({ FreeMemory(146L, cachedMb = null) }, floor)

        pace.awaitTurn()

        assertTrue(pace.gaveUp)
        assertEquals(150L, floor.floorMb)
    }

    @Test
    fun `at the lowest floor a wait that stands still waits on until the 10 s run out, with one warning`() {
        val floor = floor()
        val waits = MemoryWaits()
        val pace = pace(readings(60L to 3_000L), floor, waits = waits)

        // The first wait lowers the floor to 64 MB, the lowest, and goes on: MemFree is under it even so,
        // so no piece is taken there (review of 0.7.2). It waits until the 10 s run out.
        pace.awaitTurn()
        assertEquals(64L, floor.floorMb)
        assertTrue(pace.gaveUp)
        assertEquals(10_000_000_000L, pace.memoryWaitNs)
        // The next piece doesn't wait any more: the load has waited as long as it may.
        pace.awaitTurn()

        assertTrue(pace.gaveUp)
        assertEquals(10_000_000_000L, waits.ns)
        assertEquals(
            listOf(
                "Memory floor lowered to 64 MB (resume 114 MB): MemFree stood still at 60 MB for 500 ms in the pre-read of e2b, " +
                    "so the kernel wasn't reclaiming",
                "W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the pre-read of e2b"
            ),
            lines
        )
    }

    @Test
    fun `two waits that stand still at once lower the floor once, and the second ends too, with no second line`() {
        val floor = floor()
        val standing = FreeMemory(146L, 3_000L)
        val first = MemoryWait(floor, still = true, where = "the pre-read of e2b", startNs = 0L, first = standing)
        val second = MemoryWait(floor, still = true, where = "the room for parakeet's parse", startNs = 0L, first = standing)

        assertEquals(MemoryWait.End.STOOD_STILL, first.next(standing, 500_000_000L, spent = false))
        assertEquals(MemoryWait.End.STOOD_STILL, second.next(standing, 500_000_000L, spent = false))

        assertEquals(122L, floor.floorMb)
        assertEquals(1, lines.size)
    }

    @Test
    fun `a floor lowered in one load stays for the next one's waits, the process's one floor`() {
        val floor = floor()
        pace(readings(146L to 3_000L), floor).awaitTurn()

        // Another load, MemFree at 130 MB: over the lowered floor, so it doesn't wait.
        val next = pace(readings(130L to 3_000L), floor)
        val before = nowNs
        next.awaitTurn()

        assertEquals(before, nowNs)
        assertEquals("waited 0 ms for memory (lowest MemFree 130 MB)", next.waitSummary())
    }

    // ─── The kernel model ────────────────────────────────────────────────────────

    /**
     * MemFree, Cached and SwapFree as the kernel moves them: a piece read in takes MemFree and adds
     * to the page cache; kswapd wakes once MemFree is under [lowMb] and frees [kswapdMbPerS] of the
     * page cache until MemFree is back at [highMb]; [other] is another app taking memory (MB/s, from,
     * to in s); [swapOut] is kswapd swapping another app's memory to zram (MB/s, from, to in s) as
     * fast as that app takes memory, its file pages coming back as fast as they go, so only SwapFree
     * moves.
     */
    private class Kernel(
        var freeMb: Double,
        val lowMb: Double,
        val highMb: Double,
        val minMb: Double,
        val kswapdMbPerS: Double,
        val other: Triple<Double, Double, Double>? = null,
        val swapOut: Triple<Double, Double, Double>? = null
    ) {
        var cachedMb = 3_000.0
        var swapFreeMb = 4_000.0
        var kswapdWoke = 0
        var underMinMb = 0.0
        var lowestMb = freeMb
        private var awake = false
        private var tNs = 0L

        fun take(mb: Double, cache: Boolean = true) {
            freeMb -= mb
            if (cache) cachedMb += mb
            if (!awake && freeMb < lowMb) {
                awake = true
                kswapdWoke++
            }
            if (freeMb < minMb) underMinMb = maxOf(underMinMb, minMb - freeMb)
            lowestMb = minOf(lowestMb, freeMb)
        }

        fun pass(ns: Long) {
            var left = ns
            while (left > 0) {
                val step = minOf(left, 1_000_000L)
                left -= step
                tNs += step
                val s = tNs / 1e9
                other?.let { (rate, from, to) -> if (s in from..to) take(rate * step / 1e9, cache = false) }
                swapOut?.let { (rate, from, to) -> if (s in from..to) swapFreeMb -= rate * step / 1e9 }
                if (awake) {
                    freeMb += kswapdMbPerS * step / 1e9
                    cachedMb -= kswapdMbPerS * step / 1e9
                    if (freeMb >= highMb) {
                        freeMb = highMb
                        awake = false
                    }
                }
            }
        }
    }

    private class Run(val readMb: Long, val pace: Pace, val kernel: Kernel, val ms: Long)

    /** A pre-read of [fileMb] at 120 MB/s, 8 MiB at a time, as [PacedPreRead] reads. */
    private fun preRead(kernel: Kernel, fileMb: Long, floor: MemoryFloor = floor(), still: Boolean = true): Run {
        var now = 0L
        val pace = Pace(
            120, { FreeMemory(kernel.freeMb.toLong(), kernel.cachedMb.toLong(), kernel.swapFreeMb.toLong()) }, { false },
            clockNs = { now },
            sleepNs = { ns ->
                now += ns
                kernel.pass(ns)
            },
            floor = floor, still = still, where = "the pre-read of gemma-4-E2B_q4_0-it.gguf"
        )
        var read = 0L
        val piece = PacedPreRead.CHUNK_BYTES.toLong()
        while (read < fileMb * MIB) {
            pace.awaitTurn()
            if (pace.gaveUp) break
            kernel.take(piece / MIB.toDouble())
            read += piece
            pace.took(piece)
        }
        return Run(read / MIB, pace, kernel, now / 1_000_000)
    }

    @Test
    fun `evidence 6 - with the rule E2B's pre-read reads all of the file, the floor just over the razr's low, and never under min`() {
        val floor = floor()
        val run = preRead(Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertFalse(run.pace.gaveUp)
        assertEquals(3_200L, run.readMb)
        // Within one 8 MiB piece of the kernel's low: the next piece crosses it and wakes kswapd.
        assertTrue("floor ${floor.floorMb}", floor.floorMb in 121L until 121L + 8)
        assertTrue(run.kernel.kswapdWoke > 0)
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
        assertTrue(run.pace.memoryWaitNs < 2_000_000_000L)
        assertTrue(lines.toString(), lines.single().startsWith("Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB"))
    }

    @Test
    fun `evidence 6 with the rule off stops as on 2026-10-17, 10 s of waiting with kswapd asleep`() {
        val run = preRead(Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, still = false)

        assertTrue(run.pace.gaveUp)
        assertEquals(0, run.kernel.kswapdWoke)
        assertTrue("read ${run.readMb} MB", run.readMb < 900)
        assertEquals(Pace.GIVE_UP_MS * 1_000_000, run.pace.memoryWaitNs)
    }

    @Test
    fun `a kswapd slower than the pace makes the waits real ones, and the read still ends without reaching min`() {
        val run = preRead(Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 90.0), 3_194)

        assertEquals(3_200L, run.readMb)
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
    }

    @Test
    fun `another app taking 150 MB per second for 4 s while E2B is read in - it waits, the floor stays, never under min`() {
        val floor = floor()
        val run = preRead(
            Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0, other = Triple(150.0, 8.0, 12.0)),
            3_194,
            floor
        )

        assertEquals(3_200L, run.readMb)
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
        // The stand-still before the burst lowered it once; the burst itself, with Cached moving, lowered it no further.
        assertEquals(122L, floor.floorMb)
    }

    @Test
    fun `kswapd swapping another app out at 100 MB per second while E2B waits under the floor - no lower floor until it stops`() {
        // The pre-read reaches the floor at about 6.9 s; the squeeze of r071-en-3-warm runs from 5 s to 12 s.
        val floor = floor()
        val run = preRead(
            Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0, swapOut = Triple(100.0, 5.0, 12.0)),
            3_194,
            floor
        )

        assertEquals(3_200L, run.readMb)
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
        // It stood under the floor while SwapFree moved, and lowered it only once the squeeze ended.
        assertTrue("${run.pace.memoryWaitNs} ns", run.pace.memoryWaitNs >= 5_000_000_000L)
        assertEquals(1, lines.count { it.startsWith("Memory floor lowered to 122 MB") })
    }

    @Test
    fun `a kernel whose low is under the lowest floor - the floor goes to 64 MB, and the read stops after 10 s as before`() {
        val floor = floor()
        val run = preRead(Kernel(978.0, lowMb = 15.0, highMb = 18.0, minMb = 12.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertTrue(run.pace.gaveUp)
        assertEquals(64L, floor.floorMb)
        assertTrue(lines.last(), lines.last().startsWith("W Memory floor stays at 64 MB, the lowest it goes"))
    }

    @Test
    fun `a phone whose kswapd wakes only near 100 MB - two steps down, then the read ends`() {
        val floor = floor()
        val run = preRead(Kernel(978.0, lowMb = 100.0, highMb = 200.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertFalse(run.pace.gaveUp)
        assertEquals(3_200L, run.readMb)
        assertEquals(2, lines.count { it.startsWith("Memory floor lowered to") })
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
    }

    @Test
    fun `a low watermark over the floor - kswapd is awake whenever the pace waits, and nothing is lowered`() {
        val floor = floor()
        val run = preRead(Kernel(978.0, lowMb = 160.0, highMb = 270.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertEquals(3_200L, run.readMb)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a low watermark at the floor's 150 MB - kswapd wakes as soon as a piece waits, and nothing is lowered`() {
        val floor = floor()
        val run = preRead(Kernel(978.0, lowMb = 150.0, highMb = 260.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertEquals(3_200L, run.readMb)
        assertEquals(150L, floor.floorMb)
        assertTrue(lines.isEmpty())
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
    }

    @Test
    fun `a floor taken from the kernel's own low watermark never waits where kswapd sleeps`() {
        // /proc/zoneinfo readable, as it is on a phone or an emulator that lets the app read it: low 121 MB, high 230 MB.
        val zone = "Node 0, zone   Normal\n  pages free     4000\n        boost    0\n        min      2816\n" +
            "        low      30976\n        high     58880\n        managed  2900000\n"
        val floor = MemoryFloor(false, { "" }, { MemoryFloor.Zoneinfo(zone) }, 4_096L, { lines += it }, { lines += "W $it" })
        floor.begin()

        val run = preRead(Kernel(978.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0), 3_194, floor)

        assertEquals(3_200L, run.readMb)
        assertEquals(121L, floor.floorMb)
        assertEquals(listOf("Memory floor 121 MB, resume 171 MB: the kernel's low watermark (/proc/zoneinfo)"), lines)
        assertEquals(0.0, run.kernel.underMinMb, 0.0)
    }

    @Test
    fun `the 1B's pre-read of 0_7_1 never reaches the floor and never waits`() {
        val run = preRead(Kernel(853.0, lowMb = 121.0, highMb = 230.0, minMb = 11.0, kswapdMbPerS = 150.0), 617)

        assertEquals(0L, run.pace.memoryWaitNs)
        assertTrue(lines.isEmpty())
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
