package com.satori.data.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.util.Locale

/** How far a purge of the native heap went ([NativeHeap.purge]): the mallopt call that took, or none. */
enum class Purge(val call: String?) {
    /** `mallopt(M_PURGE_ALL)`, bionic's since API 34: every freed page given back, the allocator's caches too. */
    ALL("M_PURGE_ALL"),

    /** `mallopt(M_PURGE)`, since API 28, where M_PURGE_ALL isn't known. */
    SOME("M_PURGE"),

    /** mallopt knew neither, or the call couldn't be made: nothing was given back. */
    NONE(null)
}

/**
 * The process's native heap, which llama.cpp, ONNX Runtime and sherpa-onnx all allocate from
 * (bionic's malloc: neither of the speech libraries brings an allocator of its own). A seam: the
 * app's is lib-llama-android's `com.arm.aichat.NativeHeap`, one JNI call in libai-chat.so.
 */
fun interface NativeHeap {
    /** Gives the pages of the memory freed so far back to the kernel now. */
    fun purge(): Purge
}

/**
 * Whether a speech model's release purges the native heap after its free ([plan]): in every build
 * since 0.7.2, unless a debug build's `debug.satori.mem.purge` is off (read at each release; empty
 * or on is on, a value it doesn't know or a property it can't read is ignored with a warning). A
 * release build never reads it.
 *
 * Parakeet v3's release freed only part of the model at once on the razr: RssAnon 880 -> 658 MB and
 * 919 -> 655 MB in the two runs of 2026-10-16, while the native heap in use was 31 MB; the rest came
 * back 8-11 s later, with the next burst of native allocations (the 1B's load), so the 1B's paced
 * pre-read ran with about 500 MB of the speech model's freed memory still held. The allocator keeps
 * freed blocks until its own next activity; `mallopt(M_PURGE_ALL)` (the call Zygote makes before it
 * starts each app's process) returns them at once, in about 40-55 ms for 500 MB on the emulator.
 *
 * `W Native heap not purged after the release of <model>: mallopt knows neither M_PURGE_ALL nor M_PURGE`
 * (or `: <exception class>`), tag SatoriLoads, comes in every build when a purge gave nothing back.
 *
 * @param heap The heap to purge; null for none, as in a test ([NONE]): nothing is purged and the
 *   release's memory line is as through 0.7.1
 */
class ReleasePurge(
    private val debuggable: Boolean,
    private val read: (String) -> String?,
    private val warn: (String) -> Unit,
    private val heap: NativeHeap?
) {
    /** What a release does after its free. */
    sealed interface Plan {
        /** No heap to purge here (a test, or code that has none): the release's line is as through 0.7.1. */
        data object Skip : Plan

        /** A debug build's `debug.satori.mem.purge` is off: no purge, and the line says so. */
        data object Off : Plan

        /** The heap is purged after the free ([purge]). */
        class Purging internal constructor(private val owner: ReleasePurge) : Plan {
            /** Purges for the release of [model]; never throws: a purge that can't be made is [Purge.NONE], with a warning. */
            fun purge(model: String): Purge = owner.purge(model)
        }
    }

    /** The plan of a release starting now: the property is read now, in a debug build only. */
    fun plan(): Plan {
        if (heap == null) return Plan.Skip
        if (!debuggable) return Plan.Purging(this)
        return when (val text = read(PROPERTY)?.trim()?.lowercase(Locale.ROOT)) {
            null -> Plan.Purging(this).also { warn("Ignoring $PROPERTY: the property can't be read") }
            "", "on" -> Plan.Purging(this)
            "off" -> Plan.Off
            else -> Plan.Purging(this).also { warn("Ignoring $PROPERTY=$text: not on or off") }
        }
    }

    private fun purge(model: String): Purge {
        val heap = heap ?: return Purge.NONE
        val purged = try {
            heap.purge()
        } catch (e: RuntimeException) {
            warn("Native heap not purged after the release of $model: ${e.javaClass.simpleName}")
            return Purge.NONE
        } catch (e: LinkageError) {
            // The native library that holds the call can't be loaded: the release itself is done.
            warn("Native heap not purged after the release of $model: ${e.javaClass.simpleName}")
            return Purge.NONE
        }
        if (purged == Purge.NONE) warn("Native heap not purged after the release of $model: mallopt knows neither M_PURGE_ALL nor M_PURGE")
        return purged
    }

    companion object {
        /** A debug build's switch of the purge after a speech model's release: off purges nothing; empty or on is on. */
        const val PROPERTY = "debug.satori.mem.purge"

        private const val TAG = "SatoriLoads"

        /** No heap to purge: a test's, or code that has none. */
        val NONE = ReleasePurge(debuggable = false, read = { "" }, warn = {}, heap = null)

        /** The app's purge of [heap], reading the property in a debug build. */
        fun forApp(context: Context, heap: NativeHeap): ReleasePurge = ReleasePurge(
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            read = SystemProperty::read,
            warn = { Log.w(TAG, it) },
            heap = heap
        )
    }
}
