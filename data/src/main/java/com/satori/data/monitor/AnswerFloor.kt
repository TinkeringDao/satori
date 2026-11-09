package com.satori.data.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The first answer after an AI model's load waits for memory ([await]): in r071-en-3-warm
 * (2026-10-16 14:36) the 1B's first answer began with the model just in, MemFree fell to 122 MB as
 * another app started three WebView processes, and lmkd killed six cached apps 1.6-2.4 s into the
 * answer. So before the first answer's first native call, while MemFree is under the floor of the
 * paced waits ([MemoryFloor], shared with the loads), it waits by their rule ([MemoryWait]): until
 * MemFree is back at the resume level, until MemFree, Cached and SwapFree stand still (the floor then
 * drops), or 10 s at most; then the answer begins. It never fails the answer, and a cancel ends it
 * at its next poll. A debug build's `debug.satori.llm.answerfloor` off (read at each first answer;
 * empty or on is on) lets the answer begin at once, for an A/B; a release build never reads it.
 *
 * Lines (tag SatoriLoads), none when MemFree was over the floor, with no text of a recording:
 * ```
 * First answer on <model> waited <ms> ms for memory: MemFree <a> MB as it waited, <b> MB as it began (floor <f> MB)
 * First answer on <model> waited <ms> ms: MemFree stood still at <b> MB under the floor of <f0> MB, so the kernel wasn't reclaiming; it began (floor now <f> MB)
 * W First answer on <model> began after <ms> ms of waiting for memory: MemFree <b> MB, under its floor of <f> MB
 * First answer on <model> waited <ms> ms for memory, then MemFree couldn't be read; it began
 * W Ignoring debug.satori.llm.answerfloor=<value>: not on or off
 * ```
 *
 * @param floor The process's floor; null for none, which never waits ([NONE])
 * @param read Reads a system property: "" when it isn't set, null when it can't be read
 * @param memory MemFree and Cached now; null when they can't be read, which never waits
 * @param clockNs Nanoseconds that never jump
 * @param pause Waits the given milliseconds, cancellably
 */
class AnswerFloor internal constructor(
    private val floor: MemoryFloor?,
    private val debuggable: Boolean,
    private val read: (String) -> String?,
    private val memory: () -> FreeMemory?,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    /**
     * Before the first answer on [model] after its load: waits while MemFree is under the floor,
     * as above, and returns once the answer may begin. Throws only a cancel.
     */
    suspend fun await(model: String) {
        val floor = floor ?: return
        if (!on()) return
        val still = try {
            floor.begin()
        } catch (e: RuntimeException) {
            false
        }
        val first = memory() ?: return
        val floorAtStart = floor.floorMb
        if (first.freeMb >= floorAtStart) return
        val from = clockNs()
        val wait = MemoryWait(floor, still, "the wait before the first answer on $model", from, first)
        var last = first
        val end = waitOut(wait, from) { last = it }
        val ms = (clockNs() - from) / NS_PER_MS
        when (end) {
            MemoryWait.End.BACK -> log(
                "First answer on $model waited $ms ms for memory: MemFree ${first.freeMb} MB as it waited, " +
                    "${last.freeMb} MB as it began (floor ${floor.floorMb} MB)"
            )
            MemoryWait.End.STOOD_STILL -> log(
                "First answer on $model waited $ms ms: MemFree stood still at ${last.freeMb} MB under the floor of $floorAtStart MB, " +
                    "so the kernel wasn't reclaiming; it began (floor now ${floor.floorMb} MB)"
            )
            MemoryWait.End.GAVE_UP -> warn(
                "First answer on $model began after $ms ms of waiting for memory: MemFree ${last.freeMb} MB, " +
                    "under its floor of ${floor.floorMb} MB"
            )
            MemoryWait.End.UNREADABLE -> log("First answer on $model waited $ms ms for memory, then MemFree couldn't be read; it began")
        }
    }

    // Polls until [wait] ends, each reading that could be read handed to [seen]; how it ended.
    private suspend fun waitOut(wait: MemoryWait, from: Long, seen: (FreeMemory) -> Unit): MemoryWait.End {
        while (true) {
            pause(MemoryWait.POLL_MS)
            val nowNs = clockNs()
            val now = memory()
            if (now != null) seen(now)
            wait.next(now, nowNs, spent = nowNs - from >= GIVE_UP_NS)?.let { return it }
        }
    }

    // Whether the first answer waits at all: always in a release build, which reads no property.
    private fun on(): Boolean {
        if (!debuggable) return true
        return when (val text = read(PROPERTY)?.trim()?.lowercase(Locale.ROOT)) {
            null -> true.also { warn("Ignoring $PROPERTY: the property can't be read") }
            "", "on" -> true
            "off" -> false
            else -> true.also { warn("Ignoring $PROPERTY=$text: not on or off") }
        }
    }

    companion object {
        /** A debug build's switch of the first answer's wait: off begins it at once; empty or on is on. */
        const val PROPERTY = "debug.satori.llm.answerfloor"

        /** How long the first answer waits for memory at most. */
        const val GIVE_UP_MS = 10_000L

        private const val GIVE_UP_NS = GIVE_UP_MS * 1_000_000L
        private const val NS_PER_MS = 1_000_000L
        private const val TAG = "SatoriLoads"

        /** Never waits: a test's, or code with no floor. */
        val NONE = AnswerFloor(null, false, { "" }, { null }, {}, {})

        /** The app's: on [floor], the loads' ([ModelLoadPacing.memoryFloor]), reading the properties in a debug build. */
        fun forApp(floor: MemoryFloor, context: Context): AnswerFloor = AnswerFloor(
            floor = floor,
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            read = SystemProperty::read,
            memory = ProcMemory::freeMemory,
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) }
        )
    }
}
