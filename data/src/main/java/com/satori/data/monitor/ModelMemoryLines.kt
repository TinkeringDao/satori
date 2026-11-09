package com.satori.data.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug builds only: a memory line for every load and release of a model, to find the settings and
 * the bars under which a load costs the phone no killed app and no direct reclaim (the owner,
 * 2026-10-13: "we cannot have memory issues on the phone"). The code that loads or frees the model
 * logs the line under its own tag as the load or the release ends, and another one 10 s later:
 *
 * ```
 * Memory: load of <model> took <ms> ms; MemAvailable <a> MB before, <b> MB after; RssAnon <x> -> <y> MB[ (peak <p> MB)], RssFile <x> -> <y> MB; PSI some avg10 <s> -> <s>, full avg10 <f> -> <f>[; <settings>]
 * Memory: 10 s after the load of <model>: MemAvailable <c> MB; RssAnon <y> MB, RssFile <y> MB; PSI some avg10 <s>, full avg10 <f>
 * ```
 *
 * "release of" for a release, "failed after" in place of "took" for a load that failed, and "stopped
 * after" for a speech model's load that stopped before it loaded, as the recording wanted another
 * model by then ([LoadSupersededException]). MemAvailable is the kernel's (/proc/meminfo), what the
 * low-memory killer and ActivityManager's availMem start from; RssAnon and RssFile are this
 * process's (/proc/self/status): its own memory, and the pages of mapped files such as a model's
 * GGUF; PSI is the kernel's memory pressure (/proc/pressure/memory), the share of the last 10 s in
 * which some or all tasks stalled on memory, given as "PSI unreadable" where SELinux keeps the app
 * from it. A figure that can't be read is "n/a". The model is named by its file or its id only,
 * never by anything of a recording.
 *
 * A load measured with its peak ([begin]'s peak, the speech models' loads since 0.7.1) also gives
 * the highest RssAnon of the process while it ran: read every [PEAK_SAMPLE_MS] from
 * /proc/self/status alone, and before and after. On 2026-10-13 Parakeet v3's start peaked at 1,255
 * MB and settled at 748: ONNX Runtime's prepacking had come and gone in between, which the
 * figures before and after don't show.
 *
 * In a release build ([enabled] false) it reads nothing, logs nothing and schedules nothing: [begin]
 * returns null and [end] does nothing with it.
 *
 * @param enabled Whether the lines are logged at all
 * @param log Where a line goes, under the caller's tag
 * @param read Reads the figures now
 * @param clock Milliseconds that never jump, for how long a load or release took
 * @param later Runs its block once the given milliseconds have passed, for the line 10 s later
 * @param every Runs its block every given milliseconds until the handle it returns is closed, for
 *   the peak
 * @param rssAnonMb Reads RssAnon now, for the peak; null when it can't be read
 */
class ModelMemoryLines internal constructor(
    private val enabled: Boolean,
    private val log: (String) -> Unit,
    private val read: () -> MemoryReading = ProcMemory::read,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val later: (Long, () -> Unit) -> Unit = Later::run,
    private val every: (Long, () -> Unit) -> AutoCloseable = Later::every,
    private val rssAnonMb: () -> Long? = ProcMemory::rssAnonMb
) {
    /** What is measured: a model going into memory, or coming out of it. */
    enum class Event(internal val word: String) {
        LOAD("load"),
        RELEASE("release")
    }

    /** The figures as a load or release began, and when; and the load's peak, when it is sampled. */
    class Start internal constructor(internal val atMs: Long, internal val reading: MemoryReading, internal val peak: PeakRssAnon? = null)

    /**
     * Reads the figures as a load or release begins; null when the lines are off. With [peak],
     * RssAnon is sampled from now until [end] for the line's peak.
     */
    fun begin(peak: Boolean = false): Start? {
        if (!enabled) return null
        val reading = read()
        val sampled = if (peak) PeakRssAnon(reading.rssAnonMb).also { it.sample(every, rssAnonMb) } else null
        return Start(clock(), reading, sampled)
    }

    /**
     * Logs the line of the [event] of [model] that began at [start], and schedules the line 10 s
     * later. Nothing when [start] is null (the lines are off). Never throws: a diagnostic never
     * fails the load it measures.
     *
     * @param failed The load or release failed; the line says "failed after" instead of "took"
     * @param settings How the model was loaded (llama.cpp's settings), put at the end of the line
     * @param stopped The load stopped before the model loaded, as it wasn't wanted any more; the
     *   line says "stopped after"
     */
    fun end(start: Start?, event: Event, model: String, failed: Boolean = false, settings: String? = null, stopped: Boolean = false) =
        end(start, event, model, failed, settings, stopped, released = null)

    private fun end(
        start: Start?,
        event: Event,
        model: String,
        failed: Boolean,
        settings: String?,
        stopped: Boolean,
        released: Released?
    ) {
        start ?: return
        try {
            val ms = clock() - start.atMs
            start.peak?.stop()
            val now = read()
            val peak = start.peak?.let { Peak(it.highest(now.rssAnonMb)) }
            val outcome = when {
                stopped -> "stopped after"
                failed -> "failed after"
                else -> "took"
            }
            log(lineOf(event, model, ms, outcome, start.reading, now, settings, peak, released))
            later(AFTER_MS) {
                try {
                    log(laterLineOf(event, model, read()))
                } catch (e: RuntimeException) {
                    log("Memory: 10 s after the ${event.word} of $model: not read (${e.javaClass.simpleName})")
                }
            }
        } catch (e: RuntimeException) {
            log("Memory: ${event.word} of $model: not read (${e.javaClass.simpleName})")
        } finally {
            start.peak?.stop()
        }
    }

    /**
     * [block], measured as the [event] of [model], with its peak RssAnon when [peak]: a block that
     * throws counts as failed, but for a load stopped before it loaded ([LoadSupersededException]).
     */
    inline fun <T> measured(event: Event, model: String, peak: Boolean = false, block: () -> T): T {
        val start = begin(peak)
        var failed = true
        var stopped = false
        try {
            return block().also { failed = false }
        } catch (e: LoadSupersededException) {
            stopped = true
            throw e
        } finally {
            end(start, event, model, failed = failed, stopped = stopped)
        }
    }

    /**
     * [free], a speech model's free, measured as the release of [model], and then the native heap
     * purged as [plan] says ([ReleasePurge]), in every build, before this returns: so the caller's
     * next step (the recognizer marked out, then the next load) comes after both. The line's
     * `took` covers the free and the purge, and its RssAnon has the figure after the free and the
     * one after the purge (since 0.7.2):
     * ```
     * Memory: release of <model> took <ms> ms; MemAvailable <a> MB before, <b> MB after; RssAnon <x> -> <y> MB freed -> <z> MB purged (M_PURGE_ALL, <ms> ms), RssFile ...
     * Memory: release of <model> took <ms> ms; ...; RssAnon <x> -> <y> MB freed, not purged (debug.satori.mem.purge=off), RssFile ...
     * Memory: release of <model> took <ms> ms; ...; RssAnon <x> -> <y> MB freed, not purged (neither M_PURGE_ALL nor M_PURGE took), RssFile ...
     * ```
     * A free that throws is a failed release, and nothing is purged after it. With [plan]
     * [ReleasePurge.Plan.Skip] it is [measured] as through 0.7.1.
     */
    fun <T> released(model: String, plan: ReleasePurge.Plan, free: () -> T): T {
        if (plan == ReleasePurge.Plan.Skip) return measured(Event.RELEASE, model) { free() }
        val start = begin()
        var failed = true
        var figures: Released? = null
        try {
            val result = free()
            failed = false
            figures = when (plan) {
                is ReleasePurge.Plan.Purging -> {
                    val freedMb = start?.let { safeRssAnon() }
                    val from = if (start != null) clock() else 0L
                    val purge = plan.purge(model)
                    Released(freedMb, purge, if (start != null) clock() - from else 0L)
                }
                else -> Released(freedMb = null, purge = null, purgeMs = 0L)
            }
            return result
        } finally {
            end(start, Event.RELEASE, model, failed, settings = null, stopped = false, released = figures)
        }
    }

    // RssAnon now for a release's line; null when it can't be read.
    private fun safeRssAnon(): Long? = try {
        rssAnonMb()
    } catch (e: RuntimeException) {
        null
    }

    /** The peak RssAnon a load's line gives: [mb], or null when it couldn't be read. */
    internal class Peak(val mb: Long?)

    /**
     * What a release's line gives after the free ([released]): RssAnon after the free ([freedMb])
     * and how the purge went ([purge] in [purgeMs]); [purge] null when a debug build's property
     * turned it off.
     */
    internal class Released(val freedMb: Long?, val purge: Purge?, val purgeMs: Long)

    /**
     * The highest RssAnon seen while one load runs: the first reading, then one every
     * [PEAK_SAMPLE_MS] on the lines' own thread until [stop], then the last one ([highest]). A
     * sample that can't be read is left out; one that fails ends nothing.
     */
    internal class PeakRssAnon(first: Long?) {
        private val most = AtomicLong(first ?: NONE)

        @Volatile private var sampling: AutoCloseable? = null

        internal fun sample(every: (Long, () -> Unit) -> AutoCloseable, rssAnonMb: () -> Long?) {
            sampling = try {
                every(PEAK_SAMPLE_MS) {
                    try {
                        saw(rssAnonMb())
                    } catch (e: RuntimeException) {
                        // One sample less.
                    }
                }
            } catch (e: RuntimeException) {
                // No samples: the peak is the larger of the figures before and after.
                null
            }
        }

        /** Stops the sampling; again does nothing. */
        internal fun stop() {
            val handle = sampling ?: return
            sampling = null
            try {
                handle.close()
            } catch (e: Exception) {
                // The samples stop with the process at the latest.
            }
        }

        /** The highest RssAnon seen, with [last] the reading after the load; null when none was read. */
        internal fun highest(last: Long?): Long? {
            saw(last)
            return most.get().takeIf { it != NONE }
        }

        private fun saw(mb: Long?) {
            if (mb != null) most.accumulateAndGet(mb) { a, b -> maxOf(a, b) }
        }

        private companion object {
            const val NONE = -1L
        }
    }

    companion object {
        /** How long after a load or release its second line comes. */
        const val AFTER_MS = 10_000L

        /**
         * How often RssAnon is read for a load's peak: on 2026-10-13 Parakeet v3's start fell from
         * its peak of 1,255 MB to 748 MB between two of memwatch's samples a second apart, and
         * /proc/self/status is a small file.
         */
        const val PEAK_SAMPLE_MS = 25L

        /** Lines that are never logged, for code that isn't measured (a test, say). */
        val OFF = ModelMemoryLines(enabled = false, log = {})

        /**
         * The lines of the app, under [tag]: on in a debuggable build, the debug build the phone
         * runs, off in a release build ([forFlags]).
         */
        fun forApp(context: Context, tag: String): ModelMemoryLines =
            forFlags(context.applicationInfo.flags, log = { line -> Log.i(tag, line) })

        /**
         * The lines of an app whose ApplicationInfo has [appFlags]: on only with
         * [ApplicationInfo.FLAG_DEBUGGABLE]; a release build reads, logs and schedules nothing. The
         * rest is as for the constructor; a test gives its own [read], [clock] and [later].
         */
        fun forFlags(
            appFlags: Int,
            log: (String) -> Unit,
            read: () -> MemoryReading = ProcMemory::read,
            clock: () -> Long = SystemClock::elapsedRealtime,
            later: (Long, () -> Unit) -> Unit = Later::run,
            every: (Long, () -> Unit) -> AutoCloseable = Later::every,
            rssAnonMb: () -> Long? = ProcMemory::rssAnonMb
        ): ModelMemoryLines = ModelMemoryLines(
            enabled = appFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            log = log,
            read = read,
            clock = clock,
            later = later,
            every = every,
            rssAnonMb = rssAnonMb
        )

        internal fun lineOf(
            event: Event,
            model: String,
            ms: Long,
            outcome: String,
            before: MemoryReading,
            after: MemoryReading,
            settings: String?,
            peak: Peak? = null,
            released: Released? = null
        ): String = buildString {
            append("Memory: ${event.word} of $model ")
            append(outcome).append(" $ms ms; ")
            append("MemAvailable ${mb(before.memAvailableMb)} before, ${mb(after.memAvailableMb)} after; ")
            append("RssAnon ${number(before.rssAnonMb)} -> ")
            val purge = released?.purge
            when {
                released == null -> append(mb(after.rssAnonMb))
                purge == null -> append("${mb(after.rssAnonMb)} freed, not purged (${ReleasePurge.PROPERTY}=off)")
                purge == Purge.NONE -> append("${mb(released.freedMb)} freed, not purged (neither M_PURGE_ALL nor M_PURGE took)")
                else -> append("${mb(released.freedMb)} freed -> ${mb(after.rssAnonMb)} purged (${purge.call}, ${released.purgeMs} ms)")
            }
            if (peak != null) append(" (peak ${mb(peak.mb)})")
            append(", ")
            append("RssFile ${number(before.rssFileMb)} -> ${mb(after.rssFileMb)}; ")
            append(
                if (before.psiReadable || after.psiReadable) {
                    "PSI some avg10 ${number(before.psiSomeAvg10)} -> ${number(after.psiSomeAvg10)}, " +
                        "full avg10 ${number(before.psiFullAvg10)} -> ${number(after.psiFullAvg10)}"
                } else {
                    "PSI unreadable"
                }
            )
            if (settings != null) append("; ").append(settings)
        }

        internal fun laterLineOf(event: Event, model: String, now: MemoryReading): String = buildString {
            append("Memory: ${AFTER_MS / 1_000} s after the ${event.word} of $model: ")
            append("MemAvailable ${mb(now.memAvailableMb)}; ")
            append("RssAnon ${mb(now.rssAnonMb)}, RssFile ${mb(now.rssFileMb)}; ")
            append(
                if (now.psiReadable) {
                    "PSI some avg10 ${number(now.psiSomeAvg10)}, full avg10 ${number(now.psiFullAvg10)}"
                } else {
                    "PSI unreadable"
                }
            )
        }

        private fun mb(value: Long?): String = if (value == null) "n/a" else "$value MB"

        private fun number(value: Any?): String = value?.toString() ?: "n/a"
    }
}

/**
 * The figures of one memory line: MemAvailable, the process's RssAnon and RssFile in MB, and the
 * kernel's memory pressure over the last 10 s as it writes it ("0.00"); null for what couldn't be
 * read.
 */
data class MemoryReading(
    val memAvailableMb: Long?,
    val rssAnonMb: Long?,
    val rssFileMb: Long?,
    val psiSomeAvg10: String?,
    val psiFullAvg10: String?
) {
    internal val psiReadable: Boolean get() = psiSomeAvg10 != null || psiFullAvg10 != null
}

/** Reads [MemoryReading] from /proc: a few small files, about a millisecond. */
internal object ProcMemory {

    fun read(): MemoryReading {
        val meminfo = text("/proc/meminfo")
        val status = text("/proc/self/status")
        val pressure = text("/proc/pressure/memory")
        return MemoryReading(
            memAvailableMb = meminfo?.let { kbField(it, "MemAvailable")?.div(KB_PER_MB) },
            rssAnonMb = status?.let { kbField(it, "RssAnon")?.div(KB_PER_MB) },
            rssFileMb = status?.let { kbField(it, "RssFile")?.div(KB_PER_MB) },
            psiSomeAvg10 = pressure?.let { avg10(it, "some") },
            psiFullAvg10 = pressure?.let { avg10(it, "full") }
        )
    }

    /**
     * The kernel's MemFree in MB (/proc/meminfo): the pages no one uses at all. A load that takes
     * pages faster than kswapd frees them drives it down; under the kernel's low watermark kswapd
     * wakes, and under its min the loading thread reclaims memory itself, which is when Motorola's
     * low-memory killer ends cached apps. Null when it can't be read.
     */
    fun memFreeMb(): Long? = text("/proc/meminfo")?.let { kbField(it, "MemFree")?.div(KB_PER_MB) }

    /**
     * MemFree, Cached and SwapFree in MB from one read of /proc/meminfo, for a wait for memory
     * ([MemoryWait]): Cached falls while kswapd reclaims the page cache, SwapFree while it swaps
     * memory out to zram. Null when MemFree can't be read; Cached or SwapFree null when only it can't.
     */
    fun freeMemory(): FreeMemory? = text("/proc/meminfo")?.let { meminfo ->
        kbField(meminfo, "MemFree")?.let { free ->
            FreeMemory(free / KB_PER_MB, kbField(meminfo, "Cached")?.div(KB_PER_MB), kbField(meminfo, "SwapFree")?.div(KB_PER_MB))
        }
    }

    /**
     * This process's RssAnon in MB (/proc/self/status) alone, for a load's peak: no other file, as
     * each read of /proc/pressure/memory, which SELinux denies the app on the razr, leaves an audit
     * line in the log. Null when it can't be read.
     */
    fun rssAnonMb(): Long? = text("/proc/self/status")?.let { kbField(it, "RssAnon")?.div(KB_PER_MB) }

    /** The kB value of [name] in a /proc file of "Name:   1234 kB" lines, or null. */
    fun kbField(text: String, name: String): Long? = text.lineSequence()
        .firstOrNull { it.startsWith("$name:") }
        ?.substringAfter(':')
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()

    /** The avg10 of the [kind] ("some" or "full") line of /proc/pressure/memory, as written, or null. */
    fun avg10(text: String, kind: String): String? = text.lineSequence()
        .firstOrNull { it.startsWith("$kind ") }
        ?.split(' ')
        ?.firstOrNull { it.startsWith("avg10=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

    // Unreadable where SELinux denies the app the file, which /proc/pressure is on some phones.
    private fun text(path: String): String? = try {
        File(path).readText()
    } catch (e: Exception) {
        null
    }

    private const val KB_PER_MB = 1_024L
}

/**
 * One daemon thread for the lines 10 s after a load or release and for a load's peak samples, made
 * only when one is due.
 */
private object Later {
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ModelMemoryLines").apply { isDaemon = true } }
    }

    fun run(delayMs: Long, block: () -> Unit) {
        executor.schedule(block, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Runs [block] now and every [periodMs] until the handle is closed. */
    fun every(periodMs: Long, block: () -> Unit): AutoCloseable {
        val task = executor.scheduleAtFixedRate(block, 0, periodMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel(false) }
    }
}
