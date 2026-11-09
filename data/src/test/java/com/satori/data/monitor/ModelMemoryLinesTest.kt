package com.satori.data.monitor

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory line of a model load or release, and the one 10 s later, from the figures read before
 * and after, with a speech model's load's peak RssAnon and the word for a load stopped as no longer
 * wanted; that a release build has none; and how /proc's files are read for them.
 */
class ModelMemoryLinesTest {

    private val logged = mutableListOf<String>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var now = 1_000L
    private val readings = ArrayDeque<MemoryReading>()

    private fun lines(enabled: Boolean = true) = ModelMemoryLines(
        enabled = enabled,
        log = { logged += it },
        read = { readings.removeFirst() },
        clock = { now },
        later = { delayMs, block -> scheduled += delayMs to block }
    )

    private val before = MemoryReading(4997, 164, 128, "0.00", "0.00")
    private val after = MemoryReading(4188, 1631, 3298, "9.87", "4.32")
    private val tenSecondsOn = MemoryReading(4103, 1672, 3298, "3.10", "1.05")

    @Test
    fun `a load's line has the figures before and after and the settings, and the line 10 s later the figures then`() {
        readings += listOf(before, after, tenSecondsOn)
        val memory = lines()

        val start = memory.begin()
        now += 5_092
        memory.end(start, ModelMemoryLines.Event.LOAD, "gemma-4-E2B_q4_0-it.gguf", settings = "llama.cpp repack=on")

        assertEquals(
            listOf(
                "Memory: load of gemma-4-E2B_q4_0-it.gguf took 5092 ms; MemAvailable 4997 MB before, 4188 MB after; " +
                    "RssAnon 164 -> 1631 MB, RssFile 128 -> 3298 MB; PSI some avg10 0.00 -> 9.87, full avg10 0.00 -> 4.32; " +
                    "llama.cpp repack=on"
            ),
            logged
        )
        assertEquals(listOf(10_000L), scheduled.map { it.first })

        scheduled.single().second()

        assertEquals(
            "Memory: 10 s after the load of gemma-4-E2B_q4_0-it.gguf: MemAvailable 4103 MB; RssAnon 1672 MB, " +
                "RssFile 3298 MB; PSI some avg10 3.10, full avg10 1.05",
            logged.last()
        )
    }

    @Test
    fun `a release's line says release, a failed load says failed after, and neither has settings unless given`() {
        readings += listOf(after, before, after, before)
        val memory = lines()

        val release = memory.begin()
        now += 230
        memory.end(release, ModelMemoryLines.Event.RELEASE, "parakeet-tdt-0.6b-v3-int8")
        val load = memory.begin()
        now += 40
        memory.end(load, ModelMemoryLines.Event.LOAD, "cohere-transcribe", failed = true)

        assertTrue(logged[0], logged[0].startsWith("Memory: release of parakeet-tdt-0.6b-v3-int8 took 230 ms; MemAvailable 4188 MB before, 4997 MB after;"))
        assertTrue(logged[0], logged[0].endsWith("full avg10 4.32 -> 0.00"))
        assertTrue(logged[1], logged[1].startsWith("Memory: load of cohere-transcribe failed after 40 ms;"))
    }

    @Test
    fun `a load measured with its peak gives the highest RssAnon read while it ran, and stops reading it as it ends`() {
        readings += listOf(before, after)
        var sample: (() -> Unit)? = null
        var closed = 0
        val rssAnon = ArrayDeque(listOf<Long?>(900L, 1702L, null, 1650L))
        val memory = ModelMemoryLines(
            enabled = true,
            log = { logged += it },
            read = { readings.removeFirst() },
            clock = { now },
            later = { delayMs, block -> scheduled += delayMs to block },
            every = { periodMs, block ->
                assertEquals(ModelMemoryLines.PEAK_SAMPLE_MS, periodMs)
                sample = block
                AutoCloseable { closed++ }
            },
            rssAnonMb = { rssAnon.removeFirst() }
        )

        val start = memory.begin(peak = true)
        // Four samples while it loads, one of them unreadable.
        repeat(4) { sample?.invoke() }
        now += 2_787
        memory.end(start, ModelMemoryLines.Event.LOAD, "parakeet-tdt-0.6b-v3-int8")

        assertEquals(1, closed)
        assertEquals(
            "Memory: load of parakeet-tdt-0.6b-v3-int8 took 2787 ms; MemAvailable 4997 MB before, 4188 MB after; " +
                "RssAnon 164 -> 1631 MB (peak 1702 MB), RssFile 128 -> 3298 MB; PSI some avg10 0.00 -> 9.87, full avg10 0.00 -> 4.32",
            logged.single()
        )
    }

    @Test
    fun `without a sample the peak is the larger of the figures before and after, and n a when none was read`() {
        readings += listOf(before, after, MemoryReading(3000, null, 60, null, null), MemoryReading(3100, null, 60, null, null))
        val memory = ModelMemoryLines(
            enabled = true,
            log = { logged += it },
            read = { readings.removeFirst() },
            clock = { now },
            later = { _, _ -> },
            every = { _, _ -> AutoCloseable {} },
            rssAnonMb = { null }
        )

        memory.end(memory.begin(peak = true), ModelMemoryLines.Event.LOAD, "indicconformer-ml-int8")
        memory.end(memory.begin(peak = true), ModelMemoryLines.Event.LOAD, "indicconformer-ml-int8")

        assertTrue(logged[0], logged[0].contains("; RssAnon 164 -> 1631 MB (peak 1631 MB), RssFile "))
        assertTrue(logged[1], logged[1].contains("; RssAnon n/a -> n/a (peak n/a), RssFile "))
    }

    @Test
    fun `a sampler that can't be started leaves the load's line with the figures before and after`() {
        readings += listOf(before, after)
        val memory = ModelMemoryLines(
            enabled = true,
            log = { logged += it },
            read = { readings.removeFirst() },
            clock = { now },
            later = { _, _ -> },
            every = { _, _ -> throw IllegalStateException("no thread") }
        )

        memory.end(memory.begin(peak = true), ModelMemoryLines.Event.LOAD, "parakeet-tdt-0.6b-v3-int8")

        assertTrue(logged.single(), logged.single().contains("; RssAnon 164 -> 1631 MB (peak 1631 MB), RssFile "))
    }

    @Test
    fun `a load stopped before its model loaded, as no longer wanted, says stopped after and its exception goes on`() {
        readings += listOf(before, after)
        val memory = lines()

        assertThrows(LoadSupersededException::class.java) {
            memory.measured(ModelMemoryLines.Event.LOAD, "parakeet-tdt-0.6b-v3-int8") { throw LoadSupersededException("switched") }
        }

        assertTrue(logged.single(), logged.single().startsWith("Memory: load of parakeet-tdt-0.6b-v3-int8 stopped after 0 ms;"))
    }

    @Test
    fun `memory pressure the app may not read is said once, and a figure that can't be read is n a`() {
        readings += listOf(
            MemoryReading(null, 100, 50, null, null),
            MemoryReading(3000, null, 60, null, null),
            MemoryReading(3100, 110, 60, null, null)
        )
        val memory = lines()

        memory.end(memory.begin(), ModelMemoryLines.Event.LOAD, "gemma-3-1b-it-Q8_0.gguf")
        scheduled.single().second()

        assertEquals(
            "Memory: load of gemma-3-1b-it-Q8_0.gguf took 0 ms; MemAvailable n/a before, 3000 MB after; " +
                "RssAnon 100 -> n/a, RssFile 50 -> 60 MB; PSI unreadable",
            logged[0]
        )
        assertTrue(logged[1], logged[1].endsWith("RssAnon 110 MB, RssFile 60 MB; PSI unreadable"))
    }

    @Test
    fun `off, as in a release build, it reads, logs and schedules nothing`() {
        val memory = ModelMemoryLines(
            enabled = false,
            log = { logged += it },
            read = { error("nothing may be read") },
            later = { _, _ -> error("nothing may be scheduled") },
            every = { _, _ -> error("nothing may be sampled") },
            rssAnonMb = { error("nothing may be read") }
        )

        val start = memory.begin()
        memory.end(start, ModelMemoryLines.Event.LOAD, "gemma-4-E2B_q4_0-it.gguf")
        val value = memory.measured(ModelMemoryLines.Event.RELEASE, "gemma-4-E2B_q4_0-it.gguf") { 42 }
        val peak = memory.begin(peak = true)
        val loaded = memory.measured(ModelMemoryLines.Event.LOAD, "parakeet-tdt-0.6b-v3-int8", peak = true) { 43 }

        assertNull(start)
        assertNull(peak)
        assertEquals(43, loaded)
        assertEquals(42, value)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `an app that isn't debuggable, a release build, reads, logs and schedules nothing, and a debuggable one does`() {
        val release = ModelMemoryLines.forFlags(
            appFlags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP,
            log = { logged += it },
            read = { error("a release build read the memory") },
            later = { _, _ -> error("a release build scheduled a line") }
        )

        assertNull(release.begin())
        assertEquals(42, release.measured(ModelMemoryLines.Event.LOAD, "gemma-4-E2B_q4_0-it.gguf") { 42 })
        assertTrue(logged.isEmpty())

        readings += listOf(before, after)
        val debug = ModelMemoryLines.forFlags(
            appFlags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_DEBUGGABLE,
            log = { logged += it },
            read = { readings.removeFirst() },
            clock = { now },
            later = { delayMs, block -> scheduled += delayMs to block }
        )
        debug.measured(ModelMemoryLines.Event.RELEASE, "gemma-4-E2B_q4_0-it.gguf") {}

        assertTrue(logged.single(), logged.single().startsWith("Memory: release of gemma-4-E2B_q4_0-it.gguf took 0 ms;"))
        assertEquals(listOf(10_000L), scheduled.map { it.first })
    }

    @Test
    fun `a measured block that throws counts as failed and its exception goes on`() {
        readings += listOf(before, after)
        val memory = lines()

        assertThrows(IllegalStateException::class.java) {
            memory.measured(ModelMemoryLines.Event.LOAD, "cohere-transcribe") { error("no model") }
        }

        assertTrue(logged.single(), logged.single().startsWith("Memory: load of cohere-transcribe failed after 0 ms;"))
    }

    @Test
    fun `a read that fails never fails the load it measures`() {
        readings += before
        val memory = ModelMemoryLines(
            enabled = true,
            log = { logged += it },
            read = { readings.removeFirstOrNull() ?: throw IllegalStateException("no /proc") },
            clock = { now },
            later = { _, block -> block() }
        )

        memory.end(memory.begin(), ModelMemoryLines.Event.LOAD, "gemma-4-E2B_q4_0-it.gguf")

        assertEquals(listOf("Memory: load of gemma-4-E2B_q4_0-it.gguf: not read (IllegalStateException)"), logged)
    }

    // ─── A speech model's release and the purge after it (0.7.2) ─────────────────

    private val loaded = MemoryReading(2269, 880, 138, null, null)
    private val purgedReading = MemoryReading(3030, 152, 138, null, null)

    private fun plan(property: String = "", heap: NativeHeap) = ReleasePurge(true, { property }, { logged += "W $it" }, heap).plan()

    @Test
    fun `a release frees, then purges, and its line has RssAnon before, after the free and after the purge`() {
        readings += listOf(loaded, purgedReading)
        val steps = mutableListOf<String>()
        var rssAnon = 880L
        val memory = ModelMemoryLines(
            enabled = true, log = { logged += it }, read = { readings.removeFirst() }, clock = { now },
            later = { _, _ -> }, rssAnonMb = { rssAnon }
        )
        val heap = NativeHeap {
            steps += "purge"
            now += 46
            Purge.ALL
        }

        val value = memory.released("parakeet-tdt-0.6b-v3-int8", plan(heap = heap)) {
            steps += "free"
            now += 85
            rssAnon = 658L
            42
        }

        assertEquals(42, value)
        assertEquals(listOf("free", "purge"), steps)
        assertEquals(
            "Memory: release of parakeet-tdt-0.6b-v3-int8 took 131 ms; MemAvailable 2269 MB before, 3030 MB after; " +
                "RssAnon 880 -> 658 MB freed -> 152 MB purged (M_PURGE_ALL, 46 ms), RssFile 138 -> 138 MB; PSI unreadable",
            logged.single()
        )
    }

    @Test
    fun `a release with the purge off says why, and one whose purge gave nothing back says so too`() {
        readings += listOf(loaded, MemoryReading(2500, 658, 138, null, null), loaded, MemoryReading(2500, 658, 138, null, null))
        val memory = ModelMemoryLines(enabled = true, log = { logged += it }, read = { readings.removeFirst() }, clock = { now }, later = { _, _ -> }, rssAnonMb = { 658L })

        memory.released("parakeet-tdt-0.6b-v3-int8", plan(property = "off", heap = NativeHeap { throw AssertionError("purged") })) {}
        memory.released("parakeet-tdt-0.6b-v3-int8", plan(heap = NativeHeap { Purge.NONE })) {}

        assertTrue(logged[0], logged[0].contains("; RssAnon 880 -> 658 MB freed, not purged (debug.satori.mem.purge=off), RssFile 138 -> 138 MB;"))
        assertEquals("W Native heap not purged after the release of parakeet-tdt-0.6b-v3-int8: mallopt knows neither M_PURGE_ALL nor M_PURGE", logged[1])
        assertTrue(logged[2], logged[2].contains("; RssAnon 880 -> 658 MB freed, not purged (neither M_PURGE_ALL nor M_PURGE took), RssFile"))
    }

    @Test
    fun `a release build frees and purges and logs nothing, and a release without a heap is measured as before`() {
        val steps = mutableListOf<String>()
        val release = ModelMemoryLines(
            enabled = false,
            log = { logged += it },
            read = { error("nothing may be read") },
            later = { _, _ -> error("nothing may be scheduled") },
            rssAnonMb = { error("nothing may be read") }
        )
        val purging = ReleasePurge(false, { error("a release build read $it") }, { logged += "W $it" }, NativeHeap {
            steps += "purge"
            Purge.SOME
        })

        release.released("parakeet-tdt-0.6b-v3-int8", purging.plan()) { steps += "free" }

        assertEquals(listOf("free", "purge"), steps)
        assertTrue(logged.isEmpty())

        readings += listOf(loaded, purgedReading)
        lines().released("parakeet-tdt-0.6b-v3-int8", ReleasePurge.NONE.plan()) {}
        assertTrue(logged.single(), logged.single().contains("; RssAnon 880 -> 152 MB, RssFile"))
    }

    @Test
    fun `a free that throws is a failed release, and nothing is purged after it`() {
        readings += listOf(loaded, loaded)
        val memory = ModelMemoryLines(enabled = true, log = { logged += it }, read = { readings.removeFirst() }, clock = { now }, later = { _, _ -> }, rssAnonMb = { 880L })

        assertThrows(IllegalStateException::class.java) {
            memory.released("parakeet-tdt-0.6b-v3-int8", plan(heap = NativeHeap { throw AssertionError("purged after a failed free") })) {
                error("the native free failed")
            }
        }

        assertTrue(logged.single(), logged.single().startsWith("Memory: release of parakeet-tdt-0.6b-v3-int8 failed after 0 ms;"))
    }

    // ─── /proc ───────────────────────────────────────────────────────────────────

    @Test
    fun `MemAvailable, RssAnon and RssFile come from their kB lines`() {
        val meminfo = "MemTotal:       11656016 kB\nMemFree:          700624 kB\nMemAvailable:    5116928 kB\nBuffers:            2288 kB\n"
        val status = "Name:\tsatori\nVmRSS:\t 1795392 kB\nRssAnon:\t 1670144 kB\nRssFile:\t  125248 kB\nRssShmem:\t     768 kB\n"

        assertEquals(4997L, ProcMemory.kbField(meminfo, "MemAvailable")?.div(1_024))
        assertEquals(1631L, ProcMemory.kbField(status, "RssAnon")?.div(1_024))
        assertEquals(122L, ProcMemory.kbField(status, "RssFile")?.div(1_024))
        assertNull(ProcMemory.kbField(status, "MemAvailable"))
    }

    @Test
    fun `the avg10 of some and full come from the pressure file as written`() {
        val pressure = "some avg10=12.34 avg60=3.21 avg300=0.80 total=123456\nfull avg10=5.67 avg60=1.00 avg300=0.20 total=65432\n"

        assertEquals("12.34", ProcMemory.avg10(pressure, "some"))
        assertEquals("5.67", ProcMemory.avg10(pressure, "full"))
        assertNull(ProcMemory.avg10("some avg60=1.00\n", "some"))
        assertNull(ProcMemory.avg10(pressure, "none"))
    }
}
