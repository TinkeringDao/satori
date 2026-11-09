package com.satori.data.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Performance logger for battery, RAM and native heap snapshots, to measure on-device resource
 * use during long recordings and around the AI's work.
 *
 * The reads are binder calls, not cheap ones: the battery's level and current go through
 * system_server to the battery HAL, the memory through ActivityManager. They take a few ms, but on
 * the razr up to 0.9 s in the seconds after the AI's service started (2026-10-13: 909 ms in a
 * recording's stop, and 557 ms in the next one's start, on the main thread, ahead of its start
 * buzz). So [logSnapshot] hands them to PerfLogger's own thread and returns at once, and its line
 * says how long they took. [logInference] and [getBatteryPct] still read on the caller's thread:
 * never call them on the main thread.
 *
 * Logcat tag: PerfLogger
 * Filter with: adb logcat -s PerfLogger
 */
object PerfLogger {

    private const val TAG = "PerfLogger"

    // The snapshots' reads, one at a time in the order asked, on a thread of their own. A test puts
    // a queue of its own in for a while, to see what logSnapshot hands it.
    @Volatile internal var snapshots = SnapshotQueue(
        Executors.newSingleThreadExecutor { task -> Thread(task, "PerfLogger").apply { isDaemon = true } }
    ) { line -> Log.i(TAG, line) }

    /**
     * Logs a full resource snapshot, battery level and current, RAM and native heap, as
     * "[label] ... | read in <ms> ms". The reads run on PerfLogger's own thread ([SnapshotQueue]),
     * so this returns at once, also on the main thread.
     *
     * @param context Any context; its application context is read
     * @param label   Short description of the snapshot point (e.g. "heartbeat", "llm-start")
     */
    fun logSnapshot(context: Context, label: String) {
        val app = context.applicationContext ?: context
        snapshots.take(label) { snapshotOf(app) }
    }

    /** The snapshot's figures, read now. */
    private fun snapshotOf(context: Context): String {
        val battery = readBattery(context)
        val mem = readMemory(context)
        return "battery=${battery.levelPct}% current=${battery.currentUa}µA " +
            "| RAM used=${mem.usedRamMb}MB avail=${mem.availRamMb}MB total=${mem.totalRamMb}MB " +
            "| nativeHeap=${mem.nativeHeapMb}MB jvmHeap=${mem.jvmHeapMb}MB"
    }

    /**
     * Logs the result of a single LLM inference call.
     *
     * @param durationMs     Wall-clock time from first token request to last token received
     * @param tokenCount     Number of tokens emitted by the model
     * @param batteryBefore  Battery % captured just before the call
     * @param batteryAfter   Battery % captured just after the call
     */
    fun logInference(
        context: Context,
        durationMs: Long,
        tokenCount: Int,
        batteryBefore: Int,
        batteryAfter: Int
    ) {
        val tokPerSec = if (durationMs > 0) tokenCount * 1000f / durationMs else 0f
        val mem = readMemory(context)
        Log.i(
            TAG,
            "[llm-done] duration=${durationMs}ms tokens=$tokenCount " +
            "speed=${"%.1f".format(tokPerSec)}tok/s " +
            "battery=${batteryBefore}%→${batteryAfter}% Δ=${batteryAfter - batteryBefore}% " +
            "| nativeHeap=${mem.nativeHeapMb}MB"
        )
    }

    /** Returns current battery level in percent (0–100). */
    fun getBatteryPct(context: Context): Int = readBattery(context).levelPct

    // ─── Private helpers ──────────────────────────────────────────────────────

    private data class BatterySnapshot(
        val levelPct: Int,
        // Instantaneous current in µA. Negative = discharging, positive = charging.
        // Returns Int.MIN_VALUE if the device does not expose this counter.
        val currentUa: Long
    )

    private data class MemSnapshot(
        val availRamMb: Long,
        val totalRamMb: Long,
        val usedRamMb: Long,
        // malloc()'d native heap for this process (does NOT include mmap'd regions).
        val nativeHeapMb: Long,
        val jvmHeapMb: Long
    )

    private fun readBattery(context: Context): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatterySnapshot(levelPct = level, currentUa = current)
    }

    private fun readMemory(context: Context): MemSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val availMb = memInfo.availMem / 1_048_576L
        val totalMb = memInfo.totalMem / 1_048_576L
        val usedMb  = totalMb - availMb

        // getNativeHeapAllocatedSize() tracks malloc()/free() via the native allocator.
        // Note: mmap'd + mlock'd regions (e.g. GGUF weights) appear here only if
        // llama.cpp wraps the mmap in its own allocator; otherwise use Debug.MemoryInfo
        // for PSS-based accounting.
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576L

        val rt = Runtime.getRuntime()
        val jvmMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L

        return MemSnapshot(
            availRamMb  = availMb,
            totalRamMb  = totalMb,
            usedRamMb   = usedMb,
            nativeHeapMb = nativeMb,
            jvmHeapMb   = jvmMb
        )
    }
}

/**
 * Takes the reads of [PerfLogger]'s snapshots off their callers' threads: each [take] returns at
 * once, its reads run on [run] in the order asked, and the line goes to [log] with the label first
 * and how long the reads took last. A read that throws (a system service missing, say) is logged
 * as such, and the snapshots after it run as usual: a diagnostic never fails its caller.
 *
 * @param run Where the reads run: one thread, so they come in order
 * @param clock Milliseconds that never jump, for how long the reads took
 * @param log Where each line goes
 */
internal class SnapshotQueue(
    private val run: Executor,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit
) {
    /** Reads the snapshot [label] with [read] on [run], and logs it there. */
    fun take(label: String, read: () -> String) {
        run.execute {
            val started = clock()
            val line = try {
                "[$label] ${read()} | read in ${clock() - started} ms"
            } catch (e: RuntimeException) {
                "[$label] not read: ${e.javaClass.simpleName}"
            }
            log(line)
        }
    }
}
