package com.satori.data.monitor

import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * The pace at which a speech model's load reads its files in first: [SPEECH], 120 MB/s, in every
 * build since 0.7.1, the pace the AI models' loads have had since 0.6.1 (lib-llama-android's
 * LoadSettings), which the memory session's run 1 measured without any direct reclaim. A debug
 * build's `debug.satori.load.pace` overrides it ([forSpeechLoad]): off for no pre-read, or another
 * rate in MB/s; empty is the default. A release build never reads it. A debug build's memtest sets
 * the AI models' pre-read from the same property.
 *
 * On 2026-10-13 every model load that took memory at about 270 MB/s or more put the razr into
 * direct reclaim, where Motorola's low-memory killer ends cached apps whatever MemAvailable says;
 * the one phase that took it at about 156 MB/s, for 5 s, didn't. A load asks for 1-4.5 GB in 1-3 s
 * while kswapd frees about 150 MB/s, so the load has to be spread out: see [Pace].
 */
data class LoadPace(val mbPerSecond: Int) {

    companion object {
        const val PROPERTY = "debug.satori.load.pace"
        const val MIN_MB_PER_S = 10
        const val MAX_MB_PER_S = 1000

        /** The speech models' pre-read in every build: 120 MB/s, the AI models' and the memory session's run 1's. */
        const val SPEECH_MB_PER_S = 120

        /** The pace of every speech model's load unless a debug build's property says otherwise. */
        val SPEECH = LoadPace(SPEECH_MB_PER_S)

        /**
         * The pace of a speech model's load starting now: [SPEECH] in a release build, which reads
         * no property, and in a debug build whose property is empty; there the property can also
         * turn the pre-read off (null) or name another rate of [MIN_MB_PER_S]-[MAX_MB_PER_S] MB/s. A
         * value it doesn't know, or a property it can't read, is ignored with a warning, and the
         * load has [SPEECH].
         *
         * @param read Reads a system property: "" when it isn't set, null when it can't be read
         */
        fun forSpeechLoad(debuggable: Boolean, read: (String) -> String?, warn: (String) -> Unit): LoadPace? {
            if (!debuggable) return SPEECH
            val text = read(PROPERTY)?.trim()?.lowercase(Locale.ROOT)
            if (text == null) {
                warn("Ignoring $PROPERTY: the property can't be read")
                return SPEECH
            }
            if (text.isEmpty()) return SPEECH
            if (text == "off") return null
            val rate = text.removeSuffix("mb/s").trim().toIntOrNull()
            if (rate == null || rate !in MIN_MB_PER_S..MAX_MB_PER_S) {
                warn("Ignoring $PROPERTY=$text: not off or $MIN_MB_PER_S-$MAX_MB_PER_S (MB/s)")
                return SPEECH
            }
            return LoadPace(rate)
        }
    }
}

/**
 * The waits for memory of one model load, which its paces share, the pre-read's and the weight
 * copy's: [Pace.GIVE_UP_MS] in all, after which the load stops (MEMORY_RESEARCH_PLAN P2). Used by
 * one pace at a time: the copy begins once the pre-read has ended.
 */
internal class MemoryWaits {
    /** How long the load has waited for memory so far, in nanoseconds. */
    var ns = 0L

    /** Whether the load has waited for memory as long as it may. */
    var gaveUp = false
}

/**
 * Paces one part of a model load as it takes memory, piece by piece (8 MiB of a file read in, or a
 * tensor llama.cpp copies): at most [mbPerSecond] MB/s ([took]), and no further while MemFree is
 * under the [floor] ([MemoryFloor.floorMb], 150 MB unless the kernel's watermarks say otherwise),
 * until it is back at its resume level, or while [mustYield] (the recorder's own load runs)
 * ([awaitTurn]). Time spent waiting doesn't count toward the rate, so no burst catches up
 * afterwards. Since 0.7.2 a wait also ends once MemFree, Cached and SwapFree have stood still for 500 ms
 * ([MemoryWait], [still]): the kernel isn't reclaiming then, so the floor sits where it won't act,
 * and it drops under that level for every later wait. Waiting for memory ends once the load's
 * [waits] reach [GIVE_UP_MS] in all ([gaveUp]), this part's and the load's other parts' together;
 * what the load does then is its owner's: every load but the recorder's stops there, and the
 * recorder's stops reading and goes ahead unpaced, since a recording never fails for memory.
 *
 * The floor is MemFree, not MemAvailable: the app can't read the kernel's pressure files on the
 * razr, and on 2026-10-13 the kills came while MemAvailable rose and MemFree fell to 15-74 MB.
 *
 * @param memory MemFree and Cached now; null when they can't be read, which never waits
 * @param clockNs Nanoseconds that never jump
 * @param sleepNs Sleeps the given nanoseconds
 * @param waits The waits for memory of the whole load, which its other paces share
 * @param floor The process's floor, which the stand-still rule may lower
 * @param still Whether a wait that stands still ends and lowers the floor ([MemoryFloor.begin])
 * @param where What this pace paces, for the floor's line: "the pre-read of <model>", say
 * @param tally Where its waits are counted for its line; paces of one line share one
 */
internal class Pace(
    private val mbPerSecond: Int,
    private val memory: () -> FreeMemory?,
    private val mustYield: () -> Boolean,
    private val clockNs: () -> Long,
    private val sleepNs: (Long) -> Unit,
    private val waits: MemoryWaits = MemoryWaits(),
    private val floor: MemoryFloor = MemoryFloor.fallback(),
    private val still: Boolean = true,
    private val where: String = "a paced load",
    val tally: WaitTally = WaitTally()
) {
    // Where the rate is counted from: when the pace was made, or where restartAt put it.
    private var startNs = clockNs()
    private var pacedBytes = 0L

    /** How long this part of the load waited for memory, and for the recorder's load, in nanoseconds. */
    var memoryWaitNs = 0L
        private set
    var yieldNs = 0L
        private set

    /** The bytes taken so far ([took]): what a pre-read stopped half way had read in. */
    val takenBytes: Long
        get() = pacedBytes

    /** The lowest MemFree read, in MB; null while none could be read. */
    var lowestMemFreeMb: Long? = null
        private set

    /** Whether waiting for memory has ended for this load ([GIVE_UP_MS] reached in all). */
    val gaveUp: Boolean
        get() = waits.gaveUp

    /** The floor in effect now, which the lines name: the process's, maybe lowered since. */
    val floorMb: Long
        get() = floor.floorMb

    /** `waited <ms> ms for memory ...` for this part's line: its waits, how they ended, the floor and the lowest MemFree. */
    fun waitSummary(): String = tally.summary(lowestMemFreeMb, floor.floorMb)

    /**
     * Before the next piece: waits while [mustYield], then while MemFree is under the floor, until
     * it is back at the resume level, until MemFree, Cached and SwapFree stand still ([still]), or until the
     * load has waited [GIVE_UP_MS] in all.
     *
     * @throws CancellationException once [cancelled] says the load's caller went away
     */
    fun awaitTurn(cancelled: () -> Boolean = { false }) {
        if (mustYield()) {
            val from = clockNs()
            while (mustYield()) {
                if (cancelled()) throw CancellationException("The load was cancelled while it waited")
                sleepNs(POLL_NS)
            }
            yieldNs += clockNs() - from
        }
        if (waits.gaveUp) return
        val first = readMemory() ?: return
        if (first.freeMb >= floor.floorMb) return
        val from = clockNs()
        val wait = MemoryWait(floor, still, where, from, first)
        var end: MemoryWait.End? = null
        try {
            while (true) {
                if (cancelled()) throw CancellationException("The load was cancelled while it waited")
                sleepNs(POLL_NS)
                val now = clockNs()
                end = wait.next(readMemory(), now, spent = waits.ns + now - from >= GIVE_UP_NS)
                if (end != null) {
                    if (end == MemoryWait.End.GAVE_UP) waits.gaveUp = true
                    break
                }
            }
        } finally {
            val waited = clockNs() - from
            memoryWaitNs += waited
            waits.ns += waited
            tally.add(waited, end)
        }
    }

    /**
     * Counts the rate from [ns] on, when the first piece that takes memory began, instead of from
     * when this pace was made: llama.cpp reads the vocabulary, makes the tensors and maps the file
     * (with MAP_POPULATE) before it copies a weight, seconds that must give the first copied
     * tensors no credit to pass unpaced. The waits counted so far stay out of the rate. Call it
     * before the first [took].
     */
    fun restartAt(ns: Long) {
        startNs = ns - memoryWaitNs - yieldNs
    }

    /** After [bytes] more were taken: sleeps until the rate allows them. */
    fun took(bytes: Long) {
        if (bytes <= 0) return
        pacedBytes += bytes
        val dueNs = startNs + memoryWaitNs + yieldNs + (pacedBytes.toDouble() / (mbPerSecond * BYTES_PER_MB) * NS_PER_S).toLong()
        val now = clockNs()
        if (now < dueNs) sleepNs(dueNs - now)
    }

    private fun readMemory(): FreeMemory? = memory()?.also { now ->
        lowestMemFreeMb = lowestMemFreeMb?.let { minOf(it, now.freeMb) } ?: now.freeMb
    }

    companion object {
        /** The floor where the kernel's watermarks can't be read, before any wait stood still ([MemoryFloor.FLOOR_MB]). */
        const val FLOOR_MB = MemoryFloor.FLOOR_MB

        /** Its resume level ([MemoryFloor.RESUME_MB]). */
        const val RESUME_MB = MemoryFloor.RESUME_MB

        /** How long one load waits for memory in all, all its parts together, before it gives up waiting ([gaveUp]). */
        const val GIVE_UP_MS = 10_000L

        const val POLL_MS = MemoryWait.POLL_MS

        private const val GIVE_UP_NS = GIVE_UP_MS * 1_000_000L
        private const val POLL_NS = POLL_MS * 1_000_000L
        private const val NS_PER_S = 1_000_000_000.0
        private const val BYTES_PER_MB = 1_048_576.0
    }
}
