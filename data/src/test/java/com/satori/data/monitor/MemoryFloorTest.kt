package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor of the paced waits: from a debug build's property, else the kernel's low watermark where
 * /proc/zoneinfo can be read, else 150 MB, with its line once; lowered by a wait that stood still to
 * 24 MB under that level, never under 64 MB and never raised; and the stand-still rule's switch.
 * The zoneinfo texts are made up, in the kernel's layout.
 */
class MemoryFloorTest {

    private val lines = mutableListOf<String>()
    private val properties = mutableMapOf<String, String?>()
    private val reads = mutableListOf<String>()

    private fun floor(debuggable: Boolean = true, zoneinfo: MemoryFloor.Zoneinfo = UNREADABLE, pageBytes: Long = PAGE) = MemoryFloor(
        debuggable = debuggable,
        read = { name ->
            if (!debuggable) throw AssertionError("a release build read $name")
            reads += name
            if (properties.containsKey(name)) properties[name] else ""
        },
        zoneinfo = { zoneinfo },
        pageBytes = pageBytes,
        log = { lines += it },
        warn = { lines += "W $it" }
    )

    // ─── /proc/zoneinfo ──────────────────────────────────────────────────────────

    @Test
    fun `one zone's low and high, in pages of the caller's size`() {
        val text = zone("Normal", boost = 0, min = 2_816, low = 30_976, high = 58_880, managed = 2_900_000)

        assertEquals(KernelWatermarks(lowMb = 121, highMb = 230), KernelWatermarks.parse(text, PAGE))
        // The same figures in 16 KiB pages are four times as much memory.
        assertEquals(KernelWatermarks(lowMb = 484, highMb = 920), KernelWatermarks.parse(text, 16_384L))
    }

    @Test
    fun `two zones that hold memory add up, empty zones are left out and a zone's boost is taken off`() {
        val text = zone("DMA32", boost = 0, min = 17_104, low = 27_697, high = 38_290, managed = 762_096) +
            zone("Normal", boost = 1_000, min = 5_423, low = 9_782, high = 13_141, managed = 241_665) +
            zone("Movable", boost = 0, min = 32, low = 32, high = 32, managed = 0) +
            zone("NoSplit", boost = 0, min = 32, low = 32, high = 32, managed = 0)

        // 27,697 + 8,782 pages low, 38,290 + 12,141 high: the Normal zone's boost of 1,000 is temporary.
        assertEquals(KernelWatermarks(lowMb = 142, highMb = 196), KernelWatermarks.parse(text, PAGE))
    }

    @Test
    fun `a zone's per-CPU pagesets, whose figures carry colons, are not its watermarks`() {
        val text = zone("Normal", boost = 0, min = 2_816, low = 30_976, high = 58_880, managed = 2_900_000) +
            "  pagesets\n    cpu: 0\n              count: 12\n              high:  999999\n              batch: 63\n"

        assertEquals(KernelWatermarks(lowMb = 121, highMb = 230), KernelWatermarks.parse(text, PAGE))
    }

    @Test
    fun `text that holds no zone with its watermarks gives none`() {
        assertNull(KernelWatermarks.parse("", PAGE))
        assertNull(KernelWatermarks.parse("not the kernel's file\nlow 12\n", PAGE))
        assertNull(KernelWatermarks.parse(zone("Movable", boost = 0, min = 32, low = 32, high = 32, managed = 0), PAGE))
        assertNull(KernelWatermarks.parse("Node 0, zone   Normal\n  pages free 12\n        low      x\n", PAGE))
        assertNull(KernelWatermarks.parse(zone("Normal", boost = 0, min = 1, low = 2, high = 3, managed = 10), pageBytes = 0))
    }

    // ─── Where the floor comes from ──────────────────────────────────────────────

    @Test
    fun `where zoneinfo can't be read, the razr's case, the floor is 150 MB and it says why once`() {
        val floor = floor(zoneinfo = MemoryFloor.Zoneinfo(null, "EACCES"))

        assertTrue(floor.begin())
        floor.begin()

        assertEquals(150L, floor.floorMb)
        assertEquals(200L, floor.resumeMb)
        assertEquals(
            listOf(
                "Memory floor 150 MB, resume 200 MB: /proc/zoneinfo can't be read (EACCES); " +
                    "it follows the kernel from the first wait that stands still"
            ),
            lines
        )
    }

    @Test
    fun `the kernel's low watermarks under, at and over 150 MB give the floor, and the resume level half the way to high`() {
        for ((marks, expected) in listOf(
            (121L to 230L) to (121L to 171L),
            (150L to 200L) to (150L to 175L),
            (230L to 340L) to (230L to 280L)
        )) {
            lines.clear()
            val (low, high) = marks
            val floor = floor(zoneinfo = MemoryFloor.Zoneinfo(zone("Normal", boost = 0, min = 1, low = low * 256, high = high * 256, managed = 1_000_000)))

            floor.begin()

            assertEquals("$marks", expected, floor.floorMb to floor.resumeMb)
            assertEquals(
                listOf("Memory floor ${expected.first} MB, resume ${expected.second} MB: the kernel's low watermark (/proc/zoneinfo)"),
                lines
            )
        }
    }

    @Test
    fun `a file without watermarks, or a low under 64 MB, keeps the 150 MB`() {
        val garbage = floor(zoneinfo = MemoryFloor.Zoneinfo("nothing like zoneinfo"))
        garbage.begin()
        val tiny = floor(zoneinfo = MemoryFloor.Zoneinfo(zone("Normal", boost = 0, min = 1, low = 15 * 256, high = 18 * 256, managed = 100_000)))
        tiny.begin()

        assertEquals(150L, garbage.floorMb)
        assertEquals(150L, tiny.floorMb)
        assertEquals(
            listOf(
                "Memory floor 150 MB, resume 200 MB: /proc/zoneinfo holds no watermarks; it follows the kernel from the first wait that stands still",
                "Memory floor 150 MB, resume 200 MB: the kernel's low watermark (15 MB) is under 64 MB; " +
                    "it follows the kernel from the first wait that stands still"
            ),
            lines
        )
    }

    @Test
    fun `a debug build's property comes first, starts each load at its floor, and once cleared the kernel's or the fallback is back`() {
        val floor = floor(zoneinfo = MemoryFloor.Zoneinfo(zone("Normal", boost = 0, min = 1, low = 121 * 256, high = 230 * 256, managed = 1_000_000)))
        properties[MemoryFloor.FLOOR_PROPERTY] = "100"

        floor.begin()
        assertEquals(100L to 150L, floor.floorMb to floor.resumeMb)
        // A wait that stood still lowers it for the load; the next load starts at the property's again.
        floor.lower(standingMb = 90, where = "the pre-read of a.gguf", warned = false)
        assertEquals(66L, floor.floorMb)
        floor.begin()
        assertEquals(100L, floor.floorMb)

        properties[MemoryFloor.FLOOR_PROPERTY] = ""
        floor.begin()

        assertEquals(121L to 171L, floor.floorMb to floor.resumeMb)
        assertEquals(
            listOf(
                "Memory floor 121 MB, resume 171 MB: the kernel's low watermark (/proc/zoneinfo)",
                "Memory floor 100 MB, resume 150 MB: debug.satori.mem.floor",
                "Memory floor lowered to 66 MB (resume 116 MB): MemFree stood still at 90 MB for 500 ms in the pre-read of a.gguf, " +
                    "so the kernel wasn't reclaiming",
                "Memory floor 100 MB, resume 150 MB: debug.satori.mem.floor",
                "Memory floor 121 MB, resume 171 MB: debug.satori.mem.floor cleared"
            ),
            lines
        )
    }

    @Test
    fun `a property value it doesn't know, or can't read, is ignored with a warning`() {
        val floor = floor()
        // Never under the stand-still rule's own lowest, 64 MB (review of 0.7.2): a value a test
        // script left set holds every paced wait of the owner's recordings until the phone restarts.
        for (value in listOf("31", "32", "63", "401", "low", null)) {
            properties[MemoryFloor.FLOOR_PROPERTY] = value
            floor.begin()
            assertEquals("$value", 150L, floor.floorMb)
        }
        properties[MemoryFloor.FLOOR_PROPERTY] = "64"
        floor.begin()
        assertEquals(64L, floor.floorMb)
        properties[MemoryFloor.FLOOR_PROPERTY] = "120MB"
        floor.begin()
        assertEquals(120L, floor.floorMb)

        assertEquals(
            listOf(
                "W Ignoring debug.satori.mem.floor=31: not 64-400 (MB)",
                "W Ignoring debug.satori.mem.floor=32: not 64-400 (MB)",
                "W Ignoring debug.satori.mem.floor=63: not 64-400 (MB)",
                "W Ignoring debug.satori.mem.floor=401: not 64-400 (MB)",
                "W Ignoring debug.satori.mem.floor=low: not 64-400 (MB)",
                "W Ignoring debug.satori.mem.floor: the property can't be read",
                "Memory floor 64 MB, resume 114 MB: debug.satori.mem.floor",
                "Memory floor 120 MB, resume 170 MB: debug.satori.mem.floor"
            ),
            lines.drop(1)
        )
    }

    @Test
    fun `the stand-still rule is on unless a debug build's property is off, and a release build reads no property`() {
        val debug = floor()
        assertTrue(debug.begin())
        properties[MemoryFloor.STILL_PROPERTY] = "off"
        assertFalse(debug.begin())
        properties[MemoryFloor.STILL_PROPERTY] = " ON "
        assertTrue(debug.begin())
        properties[MemoryFloor.STILL_PROPERTY] = "maybe"
        assertTrue(debug.begin())
        properties[MemoryFloor.STILL_PROPERTY] = null
        assertTrue(debug.begin())
        assertEquals(
            listOf(
                "W Ignoring debug.satori.mem.still=maybe: not on or off",
                "W Ignoring debug.satori.mem.still: the property can't be read"
            ),
            lines.filter { it.startsWith("W ") }
        )
        assertTrue(reads.containsAll(listOf(MemoryFloor.FLOOR_PROPERTY, MemoryFloor.STILL_PROPERTY)))

        // A release build: the stand-still rule always on, nothing read but /proc/zoneinfo, once.
        properties[MemoryFloor.STILL_PROPERTY] = "off"
        var zoneinfoReads = 0
        val release = MemoryFloor(false, { throw AssertionError("a release build read $it") }, {
            zoneinfoReads++
            UNREADABLE
        }, PAGE, {}, {})
        assertTrue(release.begin())
        assertTrue(release.begin())
        assertEquals(1, zoneinfoReads)
    }

    // ─── Lowering ────────────────────────────────────────────────────────────────

    @Test
    fun `a wait that stood still lowers the floor 24 MB under where MemFree stood, with its resume level, and says so`() {
        val floor = floor()
        floor.begin()

        assertEquals(122L, floor.lower(standingMb = 146, where = "the pre-read of gemma-4-E2B_q4_0-it.gguf", warned = false))

        assertEquals(122L to 172L, floor.floorMb to floor.resumeMb)
        assertEquals(
            "Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of " +
                "gemma-4-E2B_q4_0-it.gguf, so the kernel wasn't reclaiming",
            lines.last()
        )
    }

    @Test
    fun `the floor never goes under 64 MB nor up, and at 64 MB it says so as a warning only when told it hasn't yet`() {
        val floor = floor()
        floor.begin()

        assertEquals(64L, floor.lower(standingMb = 70, where = "the room for parakeet-tdt-0.6b-v3-int8's parse", warned = false))
        assertNull(floor.lower(standingMb = 60, where = "the room for parakeet-tdt-0.6b-v3-int8's parse", warned = false))
        assertNull(floor.lower(standingMb = 60, where = "the room for parakeet-tdt-0.6b-v3-int8's parse", warned = true))
        // A level over the floor, which another wait lowered meanwhile, would raise it: it stays, and that wait ends too.
        assertEquals(64L, floor.lower(standingMb = 140, where = "the pre-read of a.gguf", warned = false))

        assertEquals(64L to 114L, floor.floorMb to floor.resumeMb)
        assertEquals(
            listOf(
                "Memory floor lowered to 64 MB (resume 114 MB): MemFree stood still at 70 MB for 500 ms in the room for " +
                    "parakeet-tdt-0.6b-v3-int8's parse, so the kernel wasn't reclaiming",
                "W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the room for parakeet-tdt-0.6b-v3-int8's parse"
            ),
            lines.drop(1)
        )
    }

    @Test
    fun `a wait standing under the lowest floor lowers it but goes on, as the floor is still over MemFree`() {
        val floor = floor()
        floor.begin()

        // From 150 MB the floor goes to 64 MB, but MemFree at 60 MB is under that too: the wait must not end there.
        assertNull(floor.lower(standingMb = 60, where = "the pre-read of a.gguf", warned = false))

        assertEquals(64L, floor.floorMb)
        assertEquals(
            listOf(
                "Memory floor lowered to 64 MB (resume 114 MB): MemFree stood still at 60 MB for 500 ms in the pre-read of a.gguf, " +
                    "so the kernel wasn't reclaiming",
                "W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the pre-read of a.gguf"
            ),
            lines.drop(1)
        )
        // At 64 MB or over, the wait the lowering came from ends.
        assertEquals(64L, floor.lower(standingMb = 64, where = "the pre-read of a.gguf", warned = true))
    }

    private companion object {
        const val PAGE = 4_096L
        val UNREADABLE = MemoryFloor.Zoneinfo(null, "EACCES")

        // One zone of /proc/zoneinfo as the kernel lays it out, with its per-node stats and pagesets around the figures.
        fun zone(name: String, boost: Long, min: Long, low: Long, high: Long, managed: Long): String = """
            |Node 0, zone ${name.padStart(8)}
            |  per-node stats
            |      nr_inactive_anon 91116
            |      nr_free_pages 4000
            |  pages free     266067
            |        boost    $boost
            |        min      $min
            |        low      $low
            |        high     $high
            |        spanned  786432
            |        present  786432
            |        managed  $managed
            |        cma      0
            |        protection: (0, 943, 943, 943, 943)
            |      nr_free_pages 266067
            |      nr_zone_active_file 143779
            |
        """.trimMargin()
    }
}
