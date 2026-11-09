package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * llama.cpp's weight copy at a pace: from its progress before each tensor, a copied tensor is
 * paced and one left in the mapping (microseconds, whatever its size) isn't, from the first copied
 * tensor on, with the load's waits for memory and for the recorder's load; once the load has
 * waited 10 s in all for memory, its pre-read's waits among them, it stops the load; one line at
 * the end.
 */
class WeightCopyPaceTest {

    private var nowNs = 0L
    private val lines = mutableListOf<String>()

    private fun copy(
        mbPerSecond: Int = 100,
        memFree: () -> Long? = { 4_000L },
        mustYield: () -> Boolean = { false },
        waits: MemoryWaits = MemoryWaits()
    ) = WeightCopyPace(
        "gemma-4-E2B_q4_0-it.gguf",
        mbPerSecond,
        Pace(mbPerSecond, { memFree()?.let { FreeMemory(it, cachedMb = null) } }, mustYield, clockNs = { nowNs }, sleepNs = { nowNs += it }, waits = waits),
        clockNs = { nowNs },
        log = { lines += it },
        warn = { lines += "W $it" }
    )

    @Test
    fun `a copied tensor is paced at the rate, and the mapped ones before it cost nothing`() {
        val copy = copy()

        assertTrue(copy.beforeTensor(0))
        // 1.8 GB left in the mapping, in 5 microseconds.
        nowNs += 5_000
        assertTrue(copy.beforeTensor(1_800L * MIB))
        val afterMapped = nowNs
        // 10 MiB copied in 10 ms: at 100 MB/s they are due 100 ms after the copy began.
        nowNs += 10_000_000
        assertTrue(copy.beforeTensor(1_810L * MIB))
        copy.ended()

        assertEquals(5_000L, afterMapped)
        assertEquals(100_005_000L, nowNs)
        assertEquals(
            "Weight copy of gemma-4-E2B_q4_0-it.gguf at 100 MB/s: 10 MB copied, 1800 MB left in the mapping, in 100 ms; " +
                "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load",
            lines.single()
        )
    }

    @Test
    fun `a copy already slower than the rate isn't held back`() {
        val copy = copy(mbPerSecond = 1_000)

        copy.beforeTensor(0)
        nowNs += 50_000_000
        copy.beforeTensor(10L * MIB)

        assertEquals(50_000_000L, nowNs)
    }

    @Test
    fun `the next tensor waits while MemFree is low and while the recorder's load runs, and the line says so`() {
        val free = ArrayDeque(listOf(4_000L, 120L, 300L))
        var recorderLooks = 3
        val copy = copy(memFree = { free.removeFirstOrNull() ?: 4_000L }, mustYield = { recorderLooks-- > 0 })

        copy.beforeTensor(0)
        nowNs += 1_000_000
        copy.beforeTensor(MIB)
        copy.ended()

        assertEquals(
            "Weight copy of gemma-4-E2B_q4_0-it.gguf at 100 MB/s: 1 MB copied, 0 MB left in the mapping, in 160 ms; " +
                "waited 50 ms for memory in 1 wait (longest 50 ms: 1 until MemFree came back; floor 150 MB, lowest MemFree 120 MB), " +
                "100 ms for the recorder's load",
            lines.single()
        )
    }

    @Test
    fun `after 10 s of waiting for memory the copy stops the load, and the line says so as a warning`() {
        val copy = copy(memFree = { 100L })

        assertFalse(copy.beforeTensor(0))
        copy.ended()

        assertEquals(
            "W Weight copy of gemma-4-E2B_q4_0-it.gguf at 100 MB/s: 0 MB copied, 0 MB left in the mapping, in 10000 ms; " +
                "waited 10000 ms for memory in 1 wait (longest 10000 ms: 1 gave up; floor 150 MB, lowest MemFree 100 MB), " +
                "0 ms for the recorder's load; the load is stopped: " +
                "MemFree under 150 MB for 10 s in all",
            lines.single()
        )
    }

    @Test
    fun `a load that waited 9 s for memory before its copy has a second left, and one that gave up stops at the first tensor`() {
        val waited = MemoryWaits().apply { ns = 9_000_000_000L }
        val short = copy(memFree = { 100L }, waits = waited)

        assertFalse(short.beforeTensor(0))
        assertEquals(1_000_000_000L, nowNs)
        assertEquals(10_000_000_000L, waited.ns)

        // Once the load has given up, its copy waits no more and stops it.
        nowNs = 0L
        val after = copy(memFree = { 100L }, waits = waited)
        assertFalse(after.beforeTensor(0))
        assertEquals(0L, nowNs)
    }

    @Test
    fun `the time llama_cpp takes before its first copied tensor gives the copy no credit to pass unpaced`() {
        val copy = copy(mbPerSecond = 120)
        // A second for the vocabulary, the tensors' setup and the file's mapping.
        nowNs = 1_000_000_000L
        copy.beforeTensor(0)

        // Twelve tensors of 10 MiB, each copied in a millisecond.
        for (tensor in 1..12) {
            nowNs += 1_000_000
            copy.beforeTensor(tensor * 10L * MIB)
        }
        copy.ended()

        // 120 MiB at 120 MB/s take a second from the first copied tensor, not from the pace's making.
        assertEquals(2_000L, Math.round(nowNs / 1e6))
        assertTrue(lines.single(), lines.single().startsWith("Weight copy of gemma-4-E2B_q4_0-it.gguf at 120 MB/s: 120 MB copied"))
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
