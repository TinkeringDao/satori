package com.satori.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps Satori's own log on the phone, so a day of testing can be read afterwards. Logcat holds
 * only the last few megabytes of the whole system and loses an afternoon of Satori within hours.
 *
 * - A background thread reads this process's lines from logcat (an app may read its own lines
 *   without any permission) and appends them to `files/logs/satori-<start time>.log` in the app's
 *   external files directory, a new file per process start and every [MAX_FILE_BYTES]; the oldest
 *   files go once there are more than [MAX_FILES] or [MAX_TOTAL_BYTES].
 * - A crash is written straight to the file before the process dies.
 * - At start it records why the previous processes ended (crash, ANR, low memory, ...), from
 *   Android's exit reasons, and copies an ANR trace or native tombstone next to the logs.
 * - Every [VITALS_INTERVAL_MS] a "SatoriVitals" line gives memory, battery and thermal state.
 *
 * Pull everything with `adb pull /sdcard/Android/data/com.satori/files/logs`.
 * The log lines don't hold what was said: the app logs no transcript text, and this class adds
 * only sizes, levels and states. An ANR trace or a tombstone is copied as Android wrote it; a
 * tombstone holds some bytes of memory around the crash, which can include text the app was
 * working on. The folder is the app's own: other apps can't read it, adb can.
 */
object FieldLog {

    private const val TAG = "SatoriFieldLog"
    private const val VITALS_TAG = "SatoriVitals"
    private const val DIR = "logs"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES = 40
    private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024
    private const val VITALS_INTERVAL_MS = 60_000L
    private const val PREFS = "field_log"
    private const val KEY_LAST_EXIT = "last_exit_timestamp"
    private const val MAX_EXIT_REASONS = 10
    private const val MAX_TRACE_BYTES = 4L * 1024 * 1024

    private val lock = Any()
    private var dir: File? = null
    private var file: File? = null
    private var writer: Writer? = null
    private var installed = false

    /** Starts the log. Call once, early in Application.onCreate. */
    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            val logs = (context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)).apply { mkdirs() }
            dir = logs
            openFile(header(context))
        }
        installCrashHandler()
        Thread({ copyLogcat() }, "SatoriFieldLog").apply { isDaemon = true }.start()
        Thread({ recordExitReasons(context); logVitalsForever(context) }, "SatoriVitals").apply {
            isDaemon = true
        }.start()
    }

    private fun header(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val version = info?.let { "${it.versionName} (${it.longVersionCode})" } ?: "?"
        return "=== Satori $version pid=${Process.myPid()} started ${timestamp(System.currentTimeMillis())} " +
            "zone=${TimeZone.getDefault().id} locale=${Locale.getDefault().toLanguageTag()} " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ===\n"
    }

    /** Opens a new file, writes [firstLine] and prunes old files. Call with [lock] held. */
    private fun openFile(firstLine: String) {
        val logs = dir ?: return
        runCatching { writer?.close() }
        val name = "satori-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) +
            "-${Process.myPid()}.log"
        val next = File(logs, name)
        file = next
        writer = runCatching { FileWriter(next, true).also { it.write(firstLine); it.flush() } }
            .onFailure { Log.w(TAG, "Can't open the field log", it) }
            .getOrNull()
        prune(logs)
    }

    private fun prune(logs: File) {
        val files = logs.listFiles { f -> f.isFile && f.name.startsWith("satori-") }
            ?.sortedByDescending { it.name } ?: return
        var total = 0L
        files.forEachIndexed { index, f ->
            total += f.length()
            if (index >= MAX_FILES || total > MAX_TOTAL_BYTES) f.delete()
        }
    }

    private fun append(text: String, flush: Boolean) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(text)
                if (flush) w.flush()
                if ((file?.length() ?: 0L) > MAX_FILE_BYTES) {
                    w.flush()
                    openFile("=== continued pid=${Process.myPid()} ${timestamp(System.currentTimeMillis())} ===\n")
                }
            } catch (e: Exception) {
                writer = null
                Log.w(TAG, "Field log write failed; stopping it", e)
            }
        }
    }

    /** Streams this process's logcat lines into the file until the process ends. */
    private fun copyLogcat() {
        try {
            val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                val chunk = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    chunk.append(line).append('\n')
                    // Write in batches, flushing whenever logcat has nothing more queued.
                    if (!reader.ready() || chunk.length > 8192) {
                        append(chunk.toString(), flush = true)
                        chunk.setLength(0)
                    }
                }
            }
            append("=== logcat reader ended ===\n", flush = true)
        } catch (e: Exception) {
            append("=== logcat reader failed: ${e.javaClass.simpleName}: ${e.message} ===\n", flush = true)
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = java.io.StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                append(
                    "=== CRASH ${timestamp(System.currentTimeMillis())} thread=${thread.name} ===\n$trace\n",
                    flush = true
                )
            }
            if (previous != null) previous.uncaughtException(thread, error) else Process.killProcess(Process.myPid())
        }
    }

    /**
     * Logs why earlier processes of the app ended, newest first, once each. An ANR trace or a
     * native crash tombstone is copied into the log directory.
     */
    private fun recordExitReasons(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS) }
            .getOrDefault(emptyList())
            .filter { it.timestamp > lastSeen }
        if (exits.isEmpty()) return
        for (exit in exits.sortedBy { it.timestamp }) {
            Log.i(
                TAG,
                "Previous process ended: pid=${exit.pid} at ${timestamp(exit.timestamp)} " +
                    "reason=${reasonName(exit.reason)} status=${exit.status} importance=${exit.importance} " +
                    "pss=${exit.pss / 1024}MB rss=${exit.rss / 1024}MB description=${exit.description}"
            )
            if (exit.reason == ApplicationExitInfo.REASON_ANR || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                copyTrace(exit)
            }
        }
        prefs.edit().putLong(KEY_LAST_EXIT, exits.maxOf { it.timestamp }).apply()
    }

    private fun copyTrace(exit: ApplicationExitInfo) {
        val logs = dir ?: return
        val kind = if (exit.reason == ApplicationExitInfo.REASON_ANR) "anr" else "tombstone"
        val target = File(logs, "satori-$kind-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(exit.timestamp))}-${exit.pid}.trace")
        runCatching {
            exit.traceInputStream?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (copied < MAX_TRACE_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
                Log.i(TAG, "Copied the $kind of pid ${exit.pid} to ${target.name}")
            }
        }.onFailure { Log.w(TAG, "Can't copy the $kind of pid ${exit.pid}", it) }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        // Added in API 33, after minSdk, so named by value.
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    private fun logVitalsForever(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        while (true) {
            runCatching {
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val system = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
                val runtime = Runtime.getRuntime()
                // A sticky broadcast, read without registering a receiver.
                val battery = ContextCompat.registerReceiver(
                    context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
                )
                val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                Log.i(
                    VITALS_TAG,
                    "pss=${memory.totalPss / 1024}MB java=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}MB " +
                        "native=${Debug.getNativeHeapAllocatedSize() / 1_048_576}MB " +
                        "systemAvail=${system.availMem / 1_048_576}MB lowMemory=${system.lowMemory} " +
                        "battery=${batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1}% " +
                        "current=${batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0}uA " +
                        "batteryTemp=${tempTenths / 10.0}C plugged=$plugged thermal=${power?.currentThermalStatus ?: -1} " +
                        "powerSave=${power?.isPowerSaveMode ?: false}"
                )
            }
            Thread.sleep(VITALS_INTERVAL_MS)
        }
    }

    private fun timestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(ms))
}
