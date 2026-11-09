package com.satori.data.monitor

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * MemFree, Cached and SwapFree in MB, from one read of /proc/meminfo: what a wait for memory
 * watches ([MemoryWait]). [cachedMb] is null when it can't be read; the stand-still rule then never
 * ends a wait. [swapFreeMb] is null when it can't be read, and then only MemFree and Cached count.
 */
data class FreeMemory(val freeMb: Long, val cachedMb: Long?, val swapFreeMb: Long? = null)

/**
 * The kernel's watermarks as /proc/zoneinfo gives them, summed over the zones that hold memory
 * (`managed` over 0): [lowMb], under which an allocation wakes kswapd, and [highMb], up to which
 * kswapd then reclaims, each without the zone's `boost`, which is temporary. An allocation takes
 * a zone down to its low before it falls back to the next one, so kswapd wakes only once MemFree
 * is under about the sum of the lows; a floor there or under it never waits where kswapd sleeps.
 *
 * An app can't read the file on Android since O: SELinux denies untrusted apps `proc_zoneinfo`,
 * with a `dontaudit` rule, so without an audit line. The razr never gets these; a phone or an
 * emulator that lets the app read them does.
 */
internal data class KernelWatermarks(val lowMb: Long, val highMb: Long) {

    companion object {
        /**
         * The watermarks in [text], /proc/zoneinfo's, whose figures are pages of [pageBytes]; null
         * when no zone that holds memory has them all.
         */
        fun parse(text: String, pageBytes: Long): KernelWatermarks? {
            if (pageBytes <= 0) return null
            var low = 0L
            var high = 0L
            var zones = 0
            var zone: MutableMap<String, Long>? = null
            fun close() {
                val figures = zone ?: return
                val managed = figures["managed"] ?: return
                val zoneLow = figures["low"] ?: return
                val zoneHigh = figures["high"] ?: return
                if (managed <= 0) return
                val boost = figures["boost"] ?: 0L
                low += (zoneLow - boost).coerceAtLeast(0L)
                high += (zoneHigh - boost).coerceAtLeast(0L)
                zones++
            }
            for (line in text.lineSequence()) {
                if (line.startsWith("Node ")) {
                    close()
                    zone = if (line.contains(" zone ")) mutableMapOf() else null
                    continue
                }
                val figures = zone ?: continue
                val words = line.trim().split(WHITESPACE)
                if (words.size != 2 || words[0] !in KEYS) continue
                // The first of each: a zone's pagesets list per-CPU figures with colons further on.
                val value = words[1].toLongOrNull() ?: continue
                figures.putIfAbsent(words[0], value)
            }
            close()
            if (zones == 0) return null
            return KernelWatermarks(lowMb = low * pageBytes / BYTES_PER_MB, highMb = high * pageBytes / BYTES_PER_MB)
        }

        private val KEYS = setOf("boost", "min", "low", "high", "managed")
        private val WHITESPACE = Regex("\\s+")
        private const val BYTES_PER_MB = 1_048_576L
    }
}

/**
 * The floor of every paced wait for memory in the process ([Pace], [AnswerFloor]): the AI models'
 * pre-read and weight copy, the speech models' pre-read, the room for a speech model's parse and the
 * first answer after an AI model's load. A piece waits while MemFree is under [floorMb], until it is
 * back at [resumeMb]. One per process, owned by [ModelLoadPacing].
 *
 * On 2026-10-17 at 14:27 Gemma 4 E2B's paced load stopped with 4,155 MB available: MemFree sat
 * at 128-150 MB, between the razr's low watermark (121 MB) and the floor of 150 MB, where kswapd
 * sleeps, so the wait for 200 MB could only end after 10 s. So the floor follows the kernel:
 * 1. a debug build's `debug.satori.mem.floor` (64-400 MB, read at each paced load), for an A/B;
 *    never under [LOWEST_MB], the stand-still rule's own lowest, since a value a test script left
 *    set holds every paced wait of the owner's recordings until the phone restarts;
 * 2. else the kernel's low watermark ([KernelWatermarks], read once per process), where the app may
 *    read /proc/zoneinfo; the resume level is then the floor and half the gap to the high
 *    watermark, 50 MB at most;
 * 3. else 150 MB and 200 MB, which is what the razr gets.
 *
 * Under any of them a wait under the floor ends once MemFree, Cached and SwapFree have all stood
 * still for 500 ms ([MemoryWait]): the kernel isn't reclaiming then, and nothing changes until a
 * page is taken. The floor then drops to [STEP_MB] under where MemFree stood, never under
 * [LOWEST_MB], for the rest of the process ([lower]). A debug build's `debug.satori.mem.still` off
 * turns that off ([begin]).
 *
 * Lines (tag SatoriLoads), with no recording's text:
 * ```
 * Memory floor 150 MB, resume 200 MB: /proc/zoneinfo can't be read (EACCES); it follows the kernel from the first wait that stands still
 * Memory floor 142 MB, resume 169 MB: the kernel's low watermark (/proc/zoneinfo)
 * Memory floor 121 MB, resume 171 MB: debug.satori.mem.floor
 * Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of <model>, so the kernel wasn't reclaiming
 * W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the room for <model>'s parse
 * ```
 * The first comes once per process (or once a debug build's property is set or cleared), the
 * lowering once for each.
 *
 * @param read Reads a system property: "" when it isn't set, null when it can't be read
 * @param zoneinfo Reads /proc/zoneinfo: its text, or why it can't be read
 * @param pageBytes The page size the kernel's figures count in
 */
class MemoryFloor internal constructor(
    private val debuggable: Boolean,
    private val read: (String) -> String?,
    private val zoneinfo: () -> Zoneinfo,
    private val pageBytes: Long,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit
) {
    /** /proc/zoneinfo as the app found it: its [text], or [why] it can't be read. */
    class Zoneinfo(val text: String?, val why: String? = null)

    /** A floor and its resume level, and where they came from. */
    private class State(val floorMb: Long, val resumeMb: Long, val fromProperty: Boolean)

    // The floor from the kernel or the fallback, before any lowering; read at the first begin().
    @Volatile private var base: State? = null
    @Volatile private var state = State(FLOOR_MB, RESUME_MB, fromProperty = false)
    private val lock = Any()

    /** Below this MemFree the next piece waits. */
    val floorMb: Long
        get() = state.floorMb

    /** A wait for memory ends once MemFree is back at this. */
    val resumeMb: Long
        get() = state.resumeMb

    /**
     * As a paced load or a first answer begins: sets the floor up the first time (the kernel's
     * watermarks or the fallback, with its line), and in a debug build follows
     * `debug.satori.mem.floor`, which starts each load at its floor while it is set. Returns
     * whether a wait that stands still may end and lower the floor: always in a release build,
     * which reads no property; in a debug build unless `debug.satori.mem.still` is off.
     */
    fun begin(): Boolean {
        val base = base ?: synchronized(lock) { base ?: fromKernel().also { base = it } }
        if (!debuggable) return true
        val fixed = propertyFloor()
        synchronized(lock) {
            val now = state
            if (fixed != null) {
                if (!now.fromProperty || now.floorMb != fixed) {
                    state = State(fixed, fixed + PROPERTY_RESUME_ABOVE_MB, fromProperty = true)
                    log("Memory floor $fixed MB, resume ${fixed + PROPERTY_RESUME_ABOVE_MB} MB: $FLOOR_PROPERTY")
                }
            } else if (now.fromProperty) {
                state = base
                log("Memory floor ${base.floorMb} MB, resume ${base.resumeMb} MB: $FLOOR_PROPERTY cleared")
            }
        }
        return stillOn()
    }

    /**
     * The stand-still rule's outcome: MemFree stood still at [standingMb] in [where] while under the
     * floor, so the floor drops to [STEP_MB] under it, never under [LOWEST_MB], and never goes up.
     * Returns the floor now, and the wait ends: also, with no line, when another wait has lowered
     * the floor to [standingMb] or under it meanwhile. Null when MemFree stands under the floor even
     * so, the lowest ([LOWEST_MB]), lowered now or before: the wait goes on, with a warning once per
     * wait (the caller's [warned]); a wait that ended there would take one more piece at a MemFree
     * under the lowest floor (the review of 0.7.2).
     */
    internal fun lower(standingMb: Long, where: String, warned: Boolean): Long? {
        val floor: Long
        synchronized(lock) {
            val now = state
            val lowered = maxOf(LOWEST_MB, standingMb - STEP_MB)
            if (lowered < now.floorMb) {
                val resume = lowered + (now.resumeMb - now.floorMb)
                state = State(lowered, resume, now.fromProperty)
                log(
                    "Memory floor lowered to $lowered MB (resume $resume MB): MemFree stood still at $standingMb MB " +
                        "for ${STILL_MS} ms in $where, so the kernel wasn't reclaiming"
                )
            }
            floor = state.floorMb
            if (standingMb >= floor) return floor
        }
        if (!warned) warn("Memory floor stays at $floor MB, the lowest it goes: MemFree stood still at $standingMb MB in $where")
        return null
    }

    // The base floor: the kernel's watermarks where they can be read, else the fallback; logged once.
    private fun fromKernel(): State {
        val file = try {
            zoneinfo()
        } catch (e: RuntimeException) {
            Zoneinfo(null, e.javaClass.simpleName)
        }
        val marks = file.text?.let { KernelWatermarks.parse(it, pageBytes) }
        val chosen = if (marks != null && marks.lowMb >= LOWEST_MB) {
            val resume = marks.lowMb + minOf(KERNEL_RESUME_ABOVE_MB, ((marks.highMb - marks.lowMb) / 2).coerceAtLeast(0L))
            log("Memory floor ${marks.lowMb} MB, resume $resume MB: the kernel's low watermark (/proc/zoneinfo)")
            State(marks.lowMb, resume, fromProperty = false)
        } else {
            val why = when {
                file.text == null -> "/proc/zoneinfo can't be read (${file.why ?: "unknown"})"
                marks == null -> "/proc/zoneinfo holds no watermarks"
                else -> "the kernel's low watermark (${marks.lowMb} MB) is under $LOWEST_MB MB"
            }
            log("Memory floor $FLOOR_MB MB, resume $RESUME_MB MB: $why; it follows the kernel from the first wait that stands still")
            State(FLOOR_MB, RESUME_MB, fromProperty = false)
        }
        state = chosen
        return chosen
    }

    // A debug build's fixed floor, or null for none (empty, or a value it ignores with a warning).
    private fun propertyFloor(): Long? {
        val text = read(FLOOR_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
        if (text == null) {
            warn("Ignoring $FLOOR_PROPERTY: the property can't be read")
            return null
        }
        if (text.isEmpty()) return null
        return text.removeSuffix("mb").trim().toLongOrNull()?.takeIf { it in MIN_PROPERTY_MB..MAX_PROPERTY_MB }
            ?: null.also { warn("Ignoring $FLOOR_PROPERTY=$text: not $MIN_PROPERTY_MB-$MAX_PROPERTY_MB (MB)") }
    }

    private fun stillOn(): Boolean = when (val text = read(STILL_PROPERTY)?.trim()?.lowercase(Locale.ROOT)) {
        null -> true.also { warn("Ignoring $STILL_PROPERTY: the property can't be read") }
        "", "on" -> true
        "off" -> false
        else -> true.also { warn("Ignoring $STILL_PROPERTY=$text: not on or off") }
    }

    companion object {
        /** The floor where the kernel's watermarks can't be read: the razr's, as through 0.7.1. */
        const val FLOOR_MB = 150L

        /** Its resume level. */
        const val RESUME_MB = 200L

        /** How far under the level MemFree stood still at the floor drops. */
        const val STEP_MB = 24L

        /** The lowest the floor goes. */
        const val LOWEST_MB = 64L

        /** How long MemFree, Cached and SwapFree must stand still, within [STILL_BAND_MB], before a wait ends. */
        const val STILL_MS = 500L

        /** How far MemFree, Cached or SwapFree may move and still count as standing still. */
        const val STILL_BAND_MB = 4L

        /** A debug build's floor (64-400 MB), read at each paced load; empty is the kernel's or the fallback. */
        const val FLOOR_PROPERTY = "debug.satori.mem.floor"

        /** A debug build's switch of the stand-still rule: off waits as 0.7.1 did; empty or on is on. */
        const val STILL_PROPERTY = "debug.satori.mem.still"

        // No lower than the stand-still rule ever goes.
        private const val MIN_PROPERTY_MB = LOWEST_MB
        private const val MAX_PROPERTY_MB = 400L
        private const val PROPERTY_RESUME_ABOVE_MB = 50L
        private const val KERNEL_RESUME_ABOVE_MB = 50L

        /**
         * The app's floor: in a debug build it reads its properties; /proc/zoneinfo is tried once.
         */
        fun forApp(debuggable: Boolean, log: (String) -> Unit, warn: (String) -> Unit): MemoryFloor = MemoryFloor(
            debuggable = debuggable,
            read = SystemProperty::read,
            zoneinfo = ::readZoneinfo,
            pageBytes = pageSize(),
            log = log,
            warn = warn
        )

        /** A floor that never reads a property or /proc: 150 MB, the stand-still rule on. For tests. */
        internal fun fallback(log: (String) -> Unit = {}, warn: (String) -> Unit = {}): MemoryFloor =
            MemoryFloor(false, { "" }, { Zoneinfo(null, "not read") }, 4_096L, log, warn)

        private fun readZoneinfo(): Zoneinfo = try {
            Zoneinfo(File("/proc/zoneinfo").readText())
        } catch (e: IOException) {
            // "open failed: EACCES (Permission denied)" on Android.
            Zoneinfo(null, ERRNO.find(e.message.orEmpty())?.groupValues?.get(1) ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Zoneinfo(null, e.javaClass.simpleName)
        }

        private val ERRNO = Regex("failed: ([A-Z]+)")

        private fun pageSize(): Long = try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).takeIf { it > 0 } ?: 4_096L
        } catch (e: Throwable) {
            4_096L
        }
    }
}

/**
 * One wait for memory under the [floor] ([Pace.awaitTurn], [AnswerFloor.await]), from its [first]
 * reading under the floor at [startNs]; its owner sleeps [MemoryWait.POLL_MS] between two readings
 * ([next]). It ends:
 * - [End.BACK]: MemFree is back at the resume level (kswapd caught up);
 * - [End.STOOD_STILL]: MemFree, Cached and SwapFree have all stood still, within
 *   [MemoryFloor.STILL_BAND_MB], for [MemoryFloor.STILL_MS] ([still] on): nothing is reclaiming,
 *   and nothing will until a page is taken, so the floor drops under that level and the wait ends;
 *   one where MemFree stands under the lowest floor goes on waiting;
 * - [End.GAVE_UP]: the owner's budget of 10 s is spent;
 * - [End.UNREADABLE]: MemFree can't be read.
 *
 * While MemFree rises (kswapd reclaiming), falls (another app taking memory), Cached falls with
 * MemFree flat (kswapd keeping up with another app), or SwapFree moves with both flat (kswapd
 * swapping another app's memory to zram as fast as it takes it while the files' pages come back
 * as fast as they go, the squeeze of r071-en-3-warm: about 100 MB/s to zram), it waits. A SwapFree
 * that can't be read leaves MemFree and Cached to say it.
 *
 * @param where What waits, for the floor's line: "the pre-read of <model>", say
 */
internal class MemoryWait(
    private val floor: MemoryFloor,
    private val still: Boolean,
    private val where: String,
    startNs: Long,
    first: FreeMemory
) {
    /** How a wait for memory ended. */
    enum class End { BACK, STOOD_STILL, GAVE_UP, UNREADABLE }

    private var anchor = first
    private var anchorNs = startNs
    private var warned = false

    /**
     * After a sleep: [now] read at [nowNs]; [spent] when the owner's budget for waiting is used up.
     * Returns how the wait ended, or null to wait on.
     */
    fun next(now: FreeMemory?, nowNs: Long, spent: Boolean): End? {
        if (now == null) return End.UNREADABLE
        if (now.freeMb >= floor.resumeMb) return End.BACK
        val cached = now.cachedMb
        val anchorCached = anchor.cachedMb
        if (still && cached != null) {
            val swap = now.swapFreeMb
            val anchorSwap = anchor.swapFreeMb
            val moved = anchorCached == null ||
                abs(now.freeMb - anchor.freeMb) > MemoryFloor.STILL_BAND_MB ||
                abs(cached - anchorCached) > MemoryFloor.STILL_BAND_MB ||
                (swap != null && anchorSwap != null && abs(swap - anchorSwap) > MemoryFloor.STILL_BAND_MB)
            if (moved) {
                anchor = now
                anchorNs = nowNs
            } else if (nowNs - anchorNs >= MemoryFloor.STILL_MS * NS_PER_MS) {
                if (floor.lower(now.freeMb, where, warned) != null) return End.STOOD_STILL
                // At the lowest floor already: it waits as 0.7.1 did, until MemFree comes back or the budget ends.
                warned = true
                anchor = now
                anchorNs = nowNs
            }
        }
        if (spent) return End.GAVE_UP
        return null
    }

    companion object {
        /** How often a wait reads the memory. */
        const val POLL_MS = 50L
        private const val NS_PER_MS = 1_000_000L
    }
}

/**
 * The waits for memory of one paced part of a load, or of first answers, for its line: how many,
 * how long in all and the longest, how each ended, and the lowest MemFree read ([summary]).
 */
internal class WaitTally {
    var count = 0
        private set
    var totalNs = 0L
        private set
    var longestNs = 0L
        private set
    private val ends = IntArray(MemoryWait.End.entries.size)

    fun add(ns: Long, end: MemoryWait.End?) {
        count++
        totalNs += ns
        longestNs = maxOf(longestNs, ns)
        if (end != null) ends[end.ordinal]++
    }

    /**
     * `waited <ms> ms for memory` and what came of it: with no wait `(lowest MemFree <n> MB)` alone,
     * as through 0.7.1; else `in <k> waits (longest <ms> ms: <a> until MemFree came back, <b> stood
     * still, <c> gave up; floor <f> MB, lowest MemFree <n> MB)`, the counts that are 0 left out.
     * Without [withLowest] (the room's line) the lowest MemFree is left out.
     */
    fun summary(lowestMemFreeMb: Long?, floorMb: Long, withLowest: Boolean = true): String {
        val lowest = "lowest MemFree ${lowestMemFreeMb?.let { "$it MB" } ?: "n/a"}"
        if (count == 0) return if (withLowest) "waited 0 ms for memory ($lowest)" else "waited 0 ms for memory"
        val how = listOfNotNull(
            ends[MemoryWait.End.BACK.ordinal].takeIf { it > 0 }?.let { "$it until MemFree came back" },
            ends[MemoryWait.End.STOOD_STILL.ordinal].takeIf { it > 0 }?.let { "$it stood still" },
            ends[MemoryWait.End.GAVE_UP.ordinal].takeIf { it > 0 }?.let { "$it gave up" },
            ends[MemoryWait.End.UNREADABLE.ordinal].takeIf { it > 0 }?.let { "$it with MemFree unreadable" }
        ).joinToString(", ")
        return "waited ${totalNs / NS_PER_MS} ms for memory in $count wait${if (count == 1) "" else "s"} " +
            "(longest ${longestNs / NS_PER_MS} ms${if (how.isEmpty()) "" else ": $how"}; floor $floorMb MB" +
            "${if (withLowest) ", $lowest" else ""})"
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
