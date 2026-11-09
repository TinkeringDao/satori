package com.satori.feature.stt.datasource

import android.os.Process
import java.io.File
import java.io.IOException

/**
 * The CPUs a thread of this process may run on: the Cpus_allowed_list of its status
 * (/proc/self/task/<tid>/status), such as "0-7". Android sets it by the app's scheduling group,
 * so the field log tells the groups apart by it without adb. On the razr top-app (Satori in
 * front) is 0-7, foreground (the screen off under a foreground service) 0-6, and background (the
 * screen off, no service) 0-2, the three little cores, on which a whole-file transcription took 4
 * to 7 times as long as in front, about 2 to 4.5 times the speech models' estimate (2026-10-12/13).
 */
internal object ThreadCpus {
    private const val FIELD = "Cpus_allowed_list:"

    /** The calling thread's, or null when its status can't be read. */
    fun current(): String? = of(Process.myTid())

    /** Thread [tid]'s, or null when its status can't be read: it has ended, say. */
    fun of(tid: Int): String? = try {
        allowedList(File("/proc/self/task/$tid/status").readText())
    } catch (e: IOException) {
        null
    }

    /** The Cpus_allowed_list in a thread's [status], or null when it has none. */
    fun allowedList(status: String): String? = status.lineSequence()
        .firstOrNull { it.startsWith(FIELD) }
        ?.substringAfter(FIELD)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
