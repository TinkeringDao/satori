package com.satori.data.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.satori.domain.repository.LoadStoppedForMemoryException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a speech model's load waits its turn ([ModelLoadPacing]): the recorder's own load, which
 * never waits for another, and any other, which takes the one-load gate first. A seam, so the
 * choice between them is tested without a model.
 */
interface SpeechModelLoadPacing {
    /**
     * The recorder's load of its speech model [name] from [files]: at once, ahead of the others.
     * Once [stop] says why it is not wanted any more ([LoadStop]: the recording wants another
     * speech model now, Stop came, the capture ended) while its files are read in, while room is
     * made for its parse, or before [load] starts, it stops with a [LoadSupersededException] and
     * loads nothing.
     *
     * @param parseBytes The bytes of [files] ONNX Runtime parses onto the heap: those with their
     *   weights in them ([ParseRoom]); 0 makes no room
     */
    fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long = 0L, stop: () -> LoadStop? = { null }, load: () -> T): T

    /**
     * Any other load of a speech model [name] from [files], behind the gate; [cancelled] stops it
     * while it waits. [parseBytes] as for [recorderLoad].
     */
    fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long = 0L, cancelled: () -> Boolean = { false }, load: () -> T): T
}

/**
 * Why the recorder's load of a speech model stopped before its model loaded
 * ([SpeechModelLoadPacing.recorderLoad]), as its field log line says it ([because]).
 */
enum class LoadStop(val because: String) {
    /** The recording's language was switched: the capture loads the new language's model instead. */
    SWITCHED("as the recording wants another speech model now"),

    /**
     * Stop came, and the recording keeps its audio: what was recorded is transcribed from it after
     * the hand-off instead of the capture waiting for the model.
     */
    STOPPED("as Stop came first and the recording's audio is kept for after Stop"),

    /** The capture ended: its recorder closed, or its stream was cancelled. */
    ENDED("as the capture ended")
}

/**
 * How the AI's model loads take part in the app's pace ([ModelLoadPacing]): LlamaAndroidDataSource
 * asks for one [PacedAiLoad] per load ([aiLoad]) and runs llama.cpp's part of the load through it.
 */
interface AiModelLoadPacing {
    /**
     * The pace of one load of the AI model in [file]. One per load: its pre-read and its weight
     * copy share its waits for memory, 10 s in all.
     */
    fun aiLoad(file: File): PacedAiLoad
}

/**
 * One load of an AI model as every one runs since 0.6.1, a release build's too: behind the
 * one-load gate, its file read in at its pre-read's pace first, its weights copied at its copy's
 * pace, and stopped once the two have waited 10 s in all for memory.
 */
interface PacedAiLoad {
    /**
     * Runs [nativeLoad], llama.cpp loading the file, behind the one-load gate, once the file is read
     * in at [preReadMbPerSecond]; the gate goes back when it returns or throws.
     *
     * @param readIn What of the file the load reads from its mapping: null for all of it, or the
     *   byte ranges of the tensors it reads in place for a load that maps the file without
     *   MAP_POPULATE (a debug build's `debug.satori.llm.populate=off`), which are all that is read in
     * @param preReadMbPerSecond The pre-read's pace; 0 reads nothing in first (a debug build's
     *   `debug.satori.load.pace=off`), and the load still waits for the gate
     * @throws PacedLoadStoppedException when the pre-read waited for memory as long as it may, or
     *   couldn't read the file (llama.cpp would read it in one burst instead); llama.cpp loads
     *   nothing then
     */
    suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit)

    /**
     * The pace of llama.cpp's weight copy in this load, at [mbPerSecond], giving way to the
     * recorder's own load as the pre-read does, and stopping the load once the load has waited for
     * memory 10 s in all, its pre-read's waits among them.
     */
    fun weightCopy(mbPerSecond: Int): WeightCopy
}

/**
 * A paced load stopped before its model loaded: the pace waited for memory as long as it may (10 s
 * in all of MemFree under the floor, [MemoryFloor]) while its files were read in or its weights copied, or an AI
 * model's file couldn't be read in first, which llama.cpp would then read in one burst. The caller
 * takes it as a failed load, so the work drops a rung (Gemma 4 E2B's falls back to the live model)
 * or waits (MEMORY_RESEARCH_PLAN P2), instead of the load then reading the rest at full speed just
 * when the phone is shortest of memory; work after Stop whose live model's load stops so tries
 * again later ([LoadStoppedForMemoryException]).
 */
class PacedLoadStoppedException(message: String, cause: Throwable? = null) : LoadStoppedForMemoryException(message, cause)

/**
 * The recorder's load of a speech model stopped before the model loaded, for [why]: its language
 * was switched while the files were read in, Stop came, or the capture ended
 * ([SpeechModelLoadPacing.recorderLoad]). Nothing is loaded. After a switch the capture loads the
 * new language's model instead, so the two are never in memory together and the one no longer
 * wanted never takes its memory. Not a cancellation: the capture decides what comes next.
 */
class LoadSupersededException(message: String, val why: LoadStop = LoadStop.SWITCHED) : Exception(message)

/**
 * What a pace probe read ([ModelLoadPacing.probe]): [newBytes] of [totalBytes] weren't cached; it
 * ended early when it [gaveUp], with MemFree under [floorMb] ([MemoryFloor]) for 10 s.
 */
data class ProbeRead(val newBytes: Long, val totalBytes: Long, val gaveUp: Boolean, val floorMb: Long = MemoryFloor.FLOOR_MB)

/**
 * Every model load of the app, one at a time, and the AI models' at a pace: on 2026-10-13 at
 * 14:21:40 the live Gemma 3 1B and Parakeet v3 loaded at once and 4 cached apps were killed, and
 * on 2026-10-15 an unpaced load of the 1B ended 14 in 0.6 s.
 *
 * Every load takes the one-load gate ([ModelLoadGate]), in every build:
 * - the recorder's own load, its speech model ([recorderLoad]), never waits for another: the other
 *   loads give way to it at their next piece, and one about to start waits until it is done;
 * - a speech model's load for a file (Transcribe again, the better transcript after Stop) waits
 *   for the gate ([otherLoad]);
 * - an AI model's load ([aiLoad], Gemma 3 1B and Gemma 4 E2B for the live notes, the finisher,
 *   Rewrite notes, Find again, the map and Translate) waits for the gate, reads what its llama.cpp
 *   load reads from the mapping in first at its pre-read's pace ([PacedPreRead]), and lets
 *   llama.cpp copy its weights at its copy's pace ([WeightCopyPace]). Both wait while MemFree is
 *   under the floor until it is back at the resume level ([MemoryFloor]: 150 and 200 MB on the
 *   razr, lowered once a wait finds MemFree and Cached standing still, since 0.7.2), and once the
 *   load has waited 10 s in all it stops ([PacedLoadStoppedException], or the copy's stop): Gemma 4
 *   E2B's work then falls back to the live model, as for any load that fails. A file its pre-read
 *   can't read stops it as well: the load maps it with MAP_POPULATE, which would read all of it at
 *   once.
 *
 * Since 0.7.1 the speech models' loads read their files in first as well, at [LoadPace.SPEECH] (120
 * MB/s) in every build, as the memory plan's run 5 has them (MEMORY_RESEARCH_PLAN, not yet measured
 * on the phone), so ONNX Runtime's reads find them cached; a debug build's `debug.satori.load.pace`
 * overrides the pace ([LoadPace]). Their ONNX Runtime options are their own (SpeechSessionOptions).
 * Then, before the native load, each speech model's load makes room for ONNX Runtime's parse of
 * the weights its ONNX files hold ([ParseRoom]): it takes that memory at the same pace into a room
 * it holds, until the room and MemFree are the parse and the floor more, and gives the room back right
 * before the native load, which then finds the memory free. Parakeet v3's parse took 660 MB in one
 * second on 2026-10-16, from cached files, and a cached app was killed. With MemFree high enough
 * already no room is held; a debug build's `debug.satori.load.room` off makes none ([ROOM_PROPERTY]).
 * The recorder's load never stops for memory and never fails for its pre-read or its room, as a
 * recording must never fail: after 10 s of waiting for memory in all, or a pre-read or a room that
 * fails, it goes ahead unpaced, and only the live text starts later meanwhile, as the capture
 * records from the start ([recorderLoad]). Once the recording no longer wants the model (its
 * language was switched, Stop came while its audio is kept, the capture ended) before the native
 * load begins, the load stops and loads nothing ([LoadSupersededException], [LoadStop]).
 *
 * Lines (tag SatoriLoads), with the model named by its id or file, never anything of a recording:
 * ```
 * Pre-read of <model>: <n> MB of <m> MB read in <ms> ms at <r> MB/s; waited <ms> ms for memory (lowest MemFree <n> MB), <ms> ms for the recorder's load
 * Pre-read of <model>: ...; waited <ms> ms for memory in <k> waits (longest <ms> ms: <a> until MemFree came back, <b> stood still, <c> gave up; floor <f> MB, lowest MemFree <n> MB), <ms> ms for the recorder's load
 * Pre-read of <model>, the tensors it reads in place: <n> MB of <m> MB read in ... (as above)
 * W Pre-read of <model> gave up after <ms> ms: MemFree under <f> MB for 10 s; <n> MB of <m> MB read; the load is stopped
 * W Pre-read of <model> gave up after <ms> ms: MemFree under <f> MB for 10 s; <n> MB of <m> MB read; the recorder's load goes ahead unpaced
 * W Pre-read of <model> failed (<class>); the recorder's load goes ahead unpaced
 * Pre-read of <model> stopped after <ms> ms, <why>: <n> MB of <m> MB read; <model> is not loaded
 * Room for <model>'s parse: none held, MemFree <n> MB is room enough for its <m> MB
 * Room for <model>'s parse: <n> MB held in <ms> ms at <r> MB/s for its <m> MB, MemFree <a> MB before and <b> MB with the room; waited <ms> ms for memory
 * W Room for <model>'s parse gave up after <ms> ms: MemFree under <f> MB for 10 s; <n> MB of <m> MB held; the recorder's load goes ahead unpaced
 * W Room for <model>'s parse gave up after <ms> ms: MemFree under <f> MB for 10 s; <n> MB of <m> MB held; the load is stopped
 * W Room for <model>'s parse not made (<class>); its load goes ahead without it
 * Room for <model>'s parse given back after <ms> ms, <why>: <n> MB of <m> MB held; <model> is not loaded
 * Load of <model> not started, <why>
 * W Pre-read of <model>: <file> (<class>) not read; the load reads it itself
 * W Pre-read of <model>: <file> (<class>) not read; the load is stopped
 * W Pre-read of <model>: <file>'s cached pages can't be seen (<why>); all of it is read
 * Load gate: <model> waited <ms> ms for another load
 * Load gate: <model>, the recorder's, goes ahead of <model>
 * ```
 * The probe's gave-up line ends `; the probe stops`. The weight copy adds its own line
 * ([WeightCopyPace]), and the engine one with the load's settings (lib-llama-android's LoadSettings).
 * <f> is the floor in effect ([MemoryFloor], whose lines come under this tag too).
 *
 * <why> is the [LoadStop]'s: "as the recording wants another speech model now", "as Stop came first
 * and the recording's audio is kept for after Stop", "as the capture ended".
 *
 * @param debuggable Whether the speech models' loads read `debug.satori.load.pace` and `debug.satori.load.room`
 * @param memory MemFree and Cached now, null when they can't be read
 * @param room Where a speech model's load holds the room for its parse
 * @param memoryFloor The process's floor of every paced wait, which each load begins with
 *   ([MemoryFloor.begin]); the first answer after an AI model's load waits on it too ([AnswerFloor])
 */
@Singleton
class ModelLoadPacing internal constructor(
    private val debuggable: Boolean,
    private val read: (String) -> String?,
    private val memory: () -> FreeMemory?,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
    private val sleepNs: (Long) -> Unit = ::sleepNanos,
    private val preRead: PacedPreRead = PacedPreRead(),
    internal val gate: ModelLoadGate = ModelLoadGate(),
    private val room: ParseRoom = SharedMemoryRoom,
    val memoryFloor: MemoryFloor = MemoryFloor(debuggable, read, { MemoryFloor.Zoneinfo(null, "not read") }, 4_096L, log, warn)
) : AiModelLoadPacing, SpeechModelLoadPacing {
    @Inject constructor(@ApplicationContext context: Context) : this(
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        read = SystemProperty::read,
        memory = ProcMemory::freeMemory,
        log = { Log.i(TAG, it) },
        warn = { Log.w(TAG, it) },
        memoryFloor = MemoryFloor.forApp(
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) }
        )
    )

    /** Who a pre-read is for, which decides what it does once the pace stops waiting for memory. */
    private enum class Reader {
        /** The recorder's own load: it stops reading, and loads unpaced. */
        RECORDER,

        /** Any other load: it stops, and loads nothing ([PacedLoadStoppedException]). */
        LOAD,

        /** The pace probe: it stops, and says so. */
        PROBE
    }

    /**
     * The recorder's load of its speech model [name] from [files]: [load] at once, without waiting
     * for another load, while the others give way to it, once its files are read in at the speech
     * models' pace ([LoadPace.forSpeechLoad]) and room is made for the parse of its [parseBytes]
     * ([room]). The capture records meanwhile, so only its live text waits for this. Blocking, on IO.
     *
     * It never stops for memory, and its pre-read and its room never make it fail: once the two
     * have waited 10 s in all for memory, or one fails, the load goes ahead unpaced. Only a load
     * the recording no longer wants stops ([stop]: its language was switched, Stop came while its
     * audio is kept, the capture ended), as a native load can't be stopped half way. It is asked at
     * each piece of the pre-read and of the room, while they wait, and once more before [load]
     * starts. The room is given back right before [load], and whenever the load stops.
     *
     * @throws LoadSupersededException once [stop] says why before [load] starts; nothing is loaded then
     */
    override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T {
        val pace = speechPace()
        val still = if (pace != null) floorBegins() else true
        gate.holder?.let { log("Load gate: $name, the recorder's, goes ahead of $it") }
        return gate.recorder {
            val waits = MemoryWaits()
            if (pace != null) {
                try {
                    preRead(name, files.map { PacedPreRead.Part(it) }, pace.mbPerSecond, Reader.RECORDER, waits, still, stop = stop)
                } catch (e: LoadSupersededException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("Pre-read of $name failed (${e.javaClass.simpleName}); the recorder's load goes ahead unpaced")
                } catch (e: OutOfMemoryError) {
                    // The pre-read's buffer on a full Java heap: the native load takes nothing from it.
                    warn("Pre-read of $name failed (${e.javaClass.simpleName}); the recorder's load goes ahead unpaced")
                }
            }
            val held = pace?.let { roomFor(name, parseBytes, it.mbPerSecond, Reader.RECORDER, waits, still, stop) }
            try {
                stop()?.let { why ->
                    log("Load of $name not started, ${why.because}")
                    throw LoadSupersededException(supersededMessage(name, why), why)
                }
            } finally {
                // Given back right before the native load, which takes it at once.
                held?.close()
            }
            load()
        }
    }

    /**
     * Any other load of a speech model [name] from [files] (one for a file): behind the gate, after
     * its files are read in at the speech models' pace and room is made for the parse of its
     * [parseBytes], and the gate goes back once [load] returns. Blocking, on IO.
     *
     * @throws java.util.concurrent.CancellationException once [cancelled] while it waits, reads or
     *   makes room; nothing is loaded then
     * @throws PacedLoadStoppedException when the pre-read and the room waited for memory as long as
     *   they may; nothing is loaded then
     */
    override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T {
        val pace = speechPace()
        logWait(name, gate.acquire(name, cancelled))
        try {
            val stop = { if (cancelled()) LoadStop.ENDED else null }
            var held: ParseRoom.Held? = null
            try {
                if (pace != null) {
                    val waits = MemoryWaits()
                    val still = floorBegins()
                    preRead(name, files.map { PacedPreRead.Part(it) }, pace.mbPerSecond, Reader.LOAD, waits, still, stop = stop)
                    held = roomFor(name, parseBytes, pace.mbPerSecond, Reader.LOAD, waits, still, stop)
                }
            } finally {
                held?.close()
            }
            return load()
        } finally {
            gate.release()
        }
    }

    override fun aiLoad(file: File): PacedAiLoad = AiLoad(file)

    /**
     * One AI model load: llama.cpp's part runs in the caller's coroutine, on the engine's thread,
     * where the gate is waited for without blocking it; the pre-read runs on IO.
     */
    private inner class AiLoad(private val file: File) : PacedAiLoad {
        private val waits = MemoryWaits()

        // Read once for the load, its pre-read and its copy alike.
        private val still: Boolean by lazy { floorBegins() }

        override suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) {
            val name = file.name
            logWait(name, gate.acquireSuspending(name))
            try {
                if (preReadMbPerSecond > 0) {
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext().job
                        preRead(
                            name,
                            listOf(PacedPreRead.Part(file, readIn)),
                            preReadMbPerSecond,
                            Reader.LOAD,
                            waits,
                            still,
                            stop = { if (job.isActive) null else LoadStop.ENDED },
                            stopsUnread = true
                        )
                    }
                }
                nativeLoad()
            } finally {
                gate.release()
            }
        }

        override fun weightCopy(mbPerSecond: Int): WeightCopy = WeightCopyPace(
            file.name,
            mbPerSecond,
            Pace(
                mbPerSecond, memory, mustYield = { gate.recorderLoading }, clockNs = clockNs, sleepNs = sleepNs, waits = waits,
                floor = memoryFloor, still = still, where = "the weight copy of ${file.name}"
            ),
            clockNs,
            log,
            warn
        )
    }

    /**
     * A debug build's probe of the pace (DebugToolsActivity --es action prewarm): [files] read in
     * at [mbPerSecond] under the gate, as a paced load's pre-read, and nothing loaded; its line is
     * the pre-read's. Whatever `debug.satori.load.pace` says. Cancelled as its caller is.
     *
     * @return What it read, for the probe's own line
     */
    suspend fun probe(name: String, files: List<File>, mbPerSecond: Int): ProbeRead {
        logWait(name, gate.acquireSuspending(name))
        try {
            return withContext(Dispatchers.IO) {
                val job = currentCoroutineContext().job
                val outcome = preRead(
                    name, files.map { PacedPreRead.Part(it) }, mbPerSecond, Reader.PROBE, MemoryWaits(), floorBegins(),
                    stop = { if (job.isActive) null else LoadStop.ENDED }
                )
                ProbeRead(outcome.newBytes, outcome.totalBytes, outcome.gaveUp, memoryFloor.floorMb)
            }
        } finally {
            gate.release()
        }
    }

    // The pre-read pace of a speech model's load starting now, read for each load; null reads nothing in first.
    private fun speechPace(): LoadPace? = LoadPace.forSpeechLoad(debuggable, read, warn)

    // The floor as a paced load begins (MemoryFloor.begin): whether its waits may end standing still.
    private fun floorBegins(): Boolean = try {
        memoryFloor.begin()
    } catch (e: RuntimeException) {
        // A floor that can't be set up never fails a load: it waits as through 0.7.1.
        warn("Memory floor not set up (${e.javaClass.simpleName}); waits end at ${memoryFloor.resumeMb} MB or after 10 s")
        false
    }

    /**
     * Whether a speech model's load makes room for its parse: in every build, unless a debug
     * build's `debug.satori.load.room` is off. Read for each load; empty or on is on, and a value
     * it doesn't know, or a property it can't read, is ignored with a warning.
     */
    private fun roomOn(): Boolean {
        if (!debuggable) return true
        val text = read(ROOM_PROPERTY)?.trim()?.lowercase(Locale.ROOT)
        return when (text) {
            null -> true.also { warn("Ignoring $ROOM_PROPERTY: the property can't be read") }
            "", "on" -> true
            "off" -> false
            else -> true.also { warn("Ignoring $ROOM_PROPERTY=$text: not on or off") }
        }
    }

    /**
     * Room for the parse of [name]'s [parseBytes] ([ParseRoom]) before its native load, taken at
     * [mbPerSecond] with the load's [waits]: held until the room and MemFree together are what the
     * parse needs ([ParseRoom.needFor]) and the floor more ([MemoryFloor.floorMb] as the room
     * begins), or the parse's need is held. None when MemFree is that high already, the load has
     * given up waiting for memory, or [roomOn] says no. The caller gives it back right before the
     * native load.
     *
     * Once the load has waited for memory 10 s in all, the room is given back: the recorder's load
     * goes ahead unpaced, any other stops ([PacedLoadStoppedException]). A room that can't be made
     * or taken lets the load go ahead without it. Once [stop] says why, the room is given back and
     * the load stops: the recorder's with a [LoadSupersededException], any other with a
     * [CancellationException].
     *
     * @return The room held, or null for none
     */
    private fun roomFor(
        name: String,
        parseBytes: Long,
        mbPerSecond: Int,
        reader: Reader,
        waits: MemoryWaits,
        still: Boolean,
        stop: () -> LoadStop?
    ): ParseRoom.Held? {
        if (parseBytes <= 0 || waits.gaveUp || !roomOn()) return null
        val need = ParseRoom.needFor(parseBytes)
        val enough = need + memoryFloor.floorMb * BYTES_PER_MB
        val freeBefore = memory()?.freeMb
        if (freeBefore != null && freeBefore * BYTES_PER_MB >= enough) {
            log("Room for $name's parse: none held, MemFree $freeBefore MB is room enough for its ${mb(need)} MB")
            return null
        }
        val started = clockNs()
        var held = openRoom(name, need) ?: return null
        // A fresh pace for each room made: the time it was given back doesn't count toward the rate.
        val tally = WaitTally()
        fun pace() = Pace(
            mbPerSecond, memory, mustYield = { false }, clockNs = clockNs, sleepNs = sleepNs, waits = waits,
            floor = memoryFloor, still = still, where = "the room for $name's parse", tally = tally
        )
        var paced = pace()
        val cancelled = { stop() != null }
        var free = freeBefore
        try {
            while (held.bytes < need) {
                if (cancelled()) throw CancellationException("The room was given back")
                if (reader != Reader.RECORDER && gate.recorderLoading) {
                    // Never held while the recorder's own load takes its memory: given back, and made again after it.
                    held.close()
                    while (gate.recorderLoading) {
                        if (cancelled()) throw CancellationException("The room was given back")
                        sleepNs(Pace.POLL_MS * NS_PER_MS)
                    }
                    held = openRoom(name, need) ?: return null
                    paced = pace()
                    continue
                }
                paced.awaitTurn(cancelled)
                if (paced.gaveUp) break
                free = memory()?.freeMb
                if (free != null && held.bytes + free * BYTES_PER_MB >= enough) break
                val piece = minOf(PacedPreRead.CHUNK_BYTES.toLong(), need - held.bytes)
                held.take(piece)
                paced.took(piece)
            }
        } catch (e: CancellationException) {
            held.close()
            if (reader != Reader.RECORDER) throw e
            val why = stop() ?: LoadStop.ENDED
            log(
                "Room for $name's parse given back after ${(clockNs() - started) / NS_PER_MS} ms, ${why.because}: " +
                    "${mb(held.bytes)} MB of ${mb(need)} MB held; $name is not loaded"
            )
            throw LoadSupersededException(supersededMessage(name, why), why)
        } catch (e: Exception) {
            held.close()
            warn("Room for $name's parse not made (${e.javaClass.simpleName}); its load goes ahead without it")
            return null
        } catch (e: OutOfMemoryError) {
            held.close()
            warn("Room for $name's parse not made (${e.javaClass.simpleName}); its load goes ahead without it")
            return null
        }
        val ms = (clockNs() - started) / NS_PER_MS
        if (paced.gaveUp) {
            held.close()
            val then = if (reader == Reader.RECORDER) "the recorder's load goes ahead unpaced" else "the load is stopped"
            warn(
                "Room for $name's parse gave up after $ms ms: MemFree under ${paced.floorMb} MB for ${Pace.GIVE_UP_MS / 1_000} s; " +
                    "${mb(held.bytes)} MB of ${mb(need)} MB held; $then"
            )
            if (reader != Reader.RECORDER) {
                throw PacedLoadStoppedException(
                    "The load of $name was stopped: MemFree stayed under ${paced.floorMb} MB for " +
                        "${Pace.GIVE_UP_MS / 1_000} s while room was made for it"
                )
            }
            return null
        }
        log(
            "Room for $name's parse: ${mb(held.bytes)} MB held in $ms ms at $mbPerSecond MB/s for its ${mb(need)} MB, " +
                "MemFree ${freeBefore?.let { "$it MB" } ?: "n/a"} before and ${free?.let { "${it + held.bytes / BYTES_PER_MB} MB" } ?: "n/a"} " +
                "with the room; ${tally.summary(lowestMemFreeMb = null, floorMb = paced.floorMb, withLowest = false)}"
        )
        return held
    }

    /** A room of [need] for [name]'s parse, or null, with a warning, when none can be made: the load goes ahead without it. */
    private fun openRoom(name: String, need: Long): ParseRoom.Held? = try {
        room.open(need)
    } catch (e: Exception) {
        warn("Room for $name's parse not made (${e.javaClass.simpleName}); its load goes ahead without it")
        null
    } catch (e: OutOfMemoryError) {
        warn("Room for $name's parse not made (${e.javaClass.simpleName}); its load goes ahead without it")
        null
    }

    private fun logWait(name: String, waitedMs: Long) {
        if (waitedMs > 0) log("Load gate: $name waited $waitedMs ms for another load")
    }

    /**
     * Reads [parts] in at [mbPerSecond] for [reader], with the load's [waits], and logs it.
     *
     * @param still Whether a wait that stands still ends and lowers the floor ([MemoryFloor.begin])
     * @param stop Ends the read at its next piece or while it waits, once it says why: for the
     *   recorder's load ([LoadSupersededException]), the recording no longer wants the model
     * @param stopsUnread Whether a file it can't read stops the load: an AI model's, which llama.cpp
     *   maps with MAP_POPULATE and so would read in one burst; a speech model's reads it itself
     * @throws PacedLoadStoppedException for a [Reader.LOAD] whose pace gave up waiting for memory,
     *   or with [stopsUnread] that couldn't read a file
     * @throws LoadSupersededException for a [Reader.RECORDER] once [stop] says why
     */
    private fun preRead(
        name: String,
        parts: List<PacedPreRead.Part>,
        mbPerSecond: Int,
        reader: Reader,
        waits: MemoryWaits,
        still: Boolean,
        stop: () -> LoadStop?,
        stopsUnread: Boolean = false
    ): PacedPreRead.Outcome {
        val cancelled = { stop() != null }
        val started = clockNs()
        val paced = Pace(
            mbPerSecond,
            memory,
            mustYield = if (reader == Reader.RECORDER) ({ false }) else ({ gate.recorderLoading }),
            clockNs = clockNs,
            sleepNs = sleepNs,
            waits = waits,
            floor = memoryFloor,
            still = still,
            where = if (reader == Reader.PROBE) "the probe of $name" else "the pre-read of $name"
        )
        val outcome = try {
            preRead.read(parts, paced, cancelled)
        } catch (e: CancellationException) {
            if (reader != Reader.RECORDER) throw e
            // The recorder's read stops only as the recording no longer wants the model, for the reason stop gives.
            val why = stop() ?: LoadStop.ENDED
            val total = parts.sumOf { it.file.length() }
            log(
                "Pre-read of $name stopped after ${(clockNs() - started) / NS_PER_MS} ms, ${why.because}: " +
                    "${mb(paced.takenBytes)} MB of ${mb(total)} MB read; $name is not loaded"
            )
            throw LoadSupersededException(supersededMessage(name, why), why)
        }
        val ms = (clockNs() - started) / NS_PER_MS
        if (stopsUnread && outcome.unreadable.isNotEmpty()) {
            val unread = outcome.unreadable.joinToString()
            warn("Pre-read of $name: $unread not read; the load is stopped")
            throw PacedLoadStoppedException("The load of $name was stopped: $unread couldn't be read in first")
        }
        outcome.unreadable.forEach { warn("Pre-read of $name: $it not read; the load reads it itself") }
        outcome.unseen.forEach { (file, why) -> warn("Pre-read of $name: $file's cached pages can't be seen ($why); all of it is read") }
        val what = if (parts.any { it.ranges != null }) ", the tensors it reads in place" else ""
        val done = "Pre-read of $name$what: ${mb(outcome.newBytes)} MB of ${mb(outcome.totalBytes)} MB read in $ms ms at " +
            "$mbPerSecond MB/s; ${paced.waitSummary()}, ${paced.yieldNs / NS_PER_MS} ms for the recorder's load"
        if (!outcome.gaveUp) {
            log(done)
            return outcome
        }
        val then = when (reader) {
            Reader.RECORDER -> "the recorder's load goes ahead unpaced"
            Reader.PROBE -> "the probe stops"
            Reader.LOAD -> "the load is stopped"
        }
        warn(
            "Pre-read of $name$what gave up after $ms ms: MemFree under ${paced.floorMb} MB for ${Pace.GIVE_UP_MS / 1_000} s; " +
                "${mb(outcome.newBytes)} MB of ${mb(outcome.totalBytes)} MB read; $then"
        )
        if (reader == Reader.LOAD) {
            throw PacedLoadStoppedException(
                "The load of $name was stopped: MemFree stayed under ${paced.floorMb} MB for " +
                    "${Pace.GIVE_UP_MS / 1_000} s while its files were read in"
            )
        }
        return outcome
    }

    companion object {
        /** A debug build's switch of the room for a speech model's parse: off makes none; empty or on is the default, on. */
        const val ROOM_PROPERTY = "debug.satori.load.room"

        private const val TAG = "SatoriLoads"
        private const val NS_PER_MS = 1_000_000L
        private const val BYTES_PER_MB = 1_048_576L

        private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%d", bytes / BYTES_PER_MB)

        private fun supersededMessage(name: String, why: LoadStop): String = "The load of $name was stopped, ${why.because}"
    }
}

/** Sleeps [ns] nanoseconds. */
internal fun sleepNanos(ns: Long) {
    if (ns > 0) Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt())
}
