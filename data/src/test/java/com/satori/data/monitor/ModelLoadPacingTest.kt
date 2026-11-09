package com.satori.data.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The app's model loads: every one behind the one-load gate, in a release build too, the
 * recorder's own load ahead of the others; the AI models' files read in at their pace first and
 * their weights copied at theirs, stopped once a load has waited 10 s in all for memory; the speech
 * models' files read in first at 120 MB/s in every build, a debug build's `debug.satori.load.pace`
 * over it, the recorder's load going ahead unpaced after 10 s of waiting or a pre-read that failed,
 * and stopping, unloaded, once the recording wants another speech model; each with its line, word
 * for word.
 */
class ModelLoadPacingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // The loads block threads: a gate that isn't given back, or a recorder's load that waits for it,
    // fails its test here instead of hanging the build.
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val lines = CopyOnWriteArrayList<String>()
    private var nowNs = 0L
    private val cache = Opened()
    private var memFree: () -> Long? = { 4_000L }

    // Cached unread unless a test reads it: the stand-still rule (MemoryFloor) then never ends a wait.
    private var cachedMb: () -> Long? = { null }

    // A debug build's floor properties, empty unless a test sets them.
    private val floorProperties = mutableMapOf(MemoryFloor.FLOOR_PROPERTY to "", MemoryFloor.STILL_PROPERTY to "")

    /** Nothing is in the page cache, so every piece is read; names the files opened. */
    private class Opened(private val onOpen: () -> Unit = {}) : FileResidency {
        val files = CopyOnWriteArrayList<String>()
        override fun open(file: File): FileResidency.View {
            files += file.name
            onOpen()
            return object : FileResidency.Cached {
                override fun missingBytes(offset: Long, length: Long): Long = length
                override fun close() {}
            }
        }
    }

    /** A room for a parse that notes what it was opened for, what it took, and when it was given back. */
    private inner class Room : ParseRoom {
        val events = CopyOnWriteArrayList<String>()
        var failure: Exception? = null

        /** Called with the count of pieces taken in all rooms so far, after each piece. */
        var onTake: (Int) -> Unit = {}
        private var takes = 0

        override fun open(bytes: Long): ParseRoom.Held {
            failure?.let { throw it }
            events += "open ${bytes / MIB} MiB"
            return object : ParseRoom.Held {
                override var bytes = 0L
                override fun take(bytes: Long) {
                    this.bytes += bytes
                    onTake(++takes)
                }

                override fun close() {
                    events += "close ${this.bytes / MIB} MiB at ${nowNs / 1_000_000} ms"
                }
            }
        }
    }

    private val room = Room()

    // The floor's own lines (MemoryFloor), apart from the loads' lines.
    private val floorLines = CopyOnWriteArrayList<String>()

    private fun pacing(
        pace: String?,
        debuggable: Boolean = true,
        residency: FileResidency = cache,
        sleepNs: (Long) -> Unit = { nowNs += it },
        roomProperty: String? = null
    ): ModelLoadPacing {
        val read: (String) -> String? = {
            when (it) {
                LoadPace.PROPERTY -> pace
                ModelLoadPacing.ROOM_PROPERTY -> roomProperty ?: throw AssertionError("read $it")
                in floorProperties -> if (debuggable) floorProperties[it] else throw AssertionError("a release build read $it")
                else -> throw AssertionError("read $it")
            }
        }
        return ModelLoadPacing(
            debuggable = debuggable,
            read = read,
            memory = { memFree()?.let { FreeMemory(it, cachedMb()) } },
            log = { lines += it },
            warn = { lines += "W $it" },
            clockNs = { nowNs },
            sleepNs = sleepNs,
            preRead = PacedPreRead(residency, chunkBytes = MIB),
            gate = ModelLoadGate(pollMs = 5),
            room = room,
            memoryFloor = MemoryFloor(
                debuggable, read, { MemoryFloor.Zoneinfo(null, "EACCES") }, 4_096L,
                log = { floorLines += it }, warn = { floorLines += "W $it" }
            )
        )
    }

    private fun model(name: String, mib: Int): File = tmp.newFile(name).apply { writeBytes(ByteArray(mib * MIB)) }

    @Test
    fun `every AI model load takes the gate and reads its file in at its pace first, in a release build too`() = runBlocking {
        val gguf = model("gemma-3-1b-it-Q8_0.gguf", 2)
        // A release build reads no property for it, nor does a debug build.
        val release = pacing(pace = null, debuggable = false)
        val caller = Thread.currentThread()

        release.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {
            assertEquals(caller, Thread.currentThread())
            assertEquals("gemma-3-1b-it-Q8_0.gguf", release.gate.holder)
            assertEquals(listOf("gemma-3-1b-it-Q8_0.gguf"), cache.files)
        }

        assertNull(release.gate.holder)
        // 2 MiB at 120 MB/s.
        assertEquals(16_666_666L, nowNs)
        assertEquals(
            "Pre-read of gemma-3-1b-it-Q8_0.gguf: 2 MB of 2 MB read in 16 ms at 120 MB/s; " +
                "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load",
            lines.single()
        )
    }

    @Test
    fun `an AI model load with its pre-read off still takes the gate, and reads nothing in`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 2)
        val pacing = pacing("")

        pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 0) {
            assertEquals("gemma-4-E2B_q4_0-it.gguf", pacing.gate.holder)
        }

        assertNull(pacing.gate.holder)
        assertTrue(cache.files.isEmpty())
        assertTrue(lines.isEmpty())
        assertEquals(0L, nowNs)
    }

    @Test
    fun `every speech model's load reads its files in at 120 MB per second before it loads, in a release build too`() {
        val encoder = model("encoder.int8.onnx", 2)
        // A release build reads no property for it.
        val release = pacing(pace = null, debuggable = false)

        assertEquals("loaded", release.otherLoad("cohere-transcribe-14-lang-int8", listOf(encoder)) {
            assertEquals("cohere-transcribe-14-lang-int8", release.gate.holder)
            // Read in first, at the pace.
            assertEquals(listOf("encoder.int8.onnx"), cache.files)
            assertEquals(16_666_666L, nowNs)
            "loaded"
        })
        assertNull(release.gate.holder)
        assertEquals("loaded", release.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder)) {
            assertTrue(release.gate.recorderLoading)
            assertEquals(listOf("encoder.int8.onnx", "encoder.int8.onnx"), cache.files)
            "loaded"
        })
        assertFalse(release.gate.recorderLoading)

        // 2 MiB at 120 MB/s, twice.
        assertEquals(2 * 16_666_666L, nowNs)
        assertEquals(
            listOf(
                "Pre-read of cohere-transcribe-14-lang-int8: 2 MB of 2 MB read in 16 ms at 120 MB/s; " +
                    "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load",
                "Pre-read of parakeet-tdt-0.6b-v3-int8: 2 MB of 2 MB read in 16 ms at 120 MB/s; " +
                    "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load"
            ),
            lines
        )
    }

    @Test
    fun `a debug build's empty pace is the 120 MB per second, and off reads nothing in, the load still behind the gate`() {
        val encoder = model("encoder.int8.onnx", 2)
        pacing("").recorderLoad("indicconformer-ml-int8", listOf(encoder)) {}
        assertEquals(16_666_666L, nowNs)
        assertTrue(lines.single(), lines.single().startsWith("Pre-read of indicconformer-ml-int8: 2 MB of 2 MB read in 16 ms at 120 MB/s;"))
        lines.clear()
        cache.files.clear()
        val off = pacing("off")

        assertEquals("loaded", off.otherLoad("cohere-transcribe-14-lang-int8", listOf(encoder)) {
            assertEquals("cohere-transcribe-14-lang-int8", off.gate.holder)
            "loaded"
        })
        assertNull(off.gate.holder)
        assertEquals("loaded", off.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder)) {
            assertTrue(off.gate.recorderLoading)
            "loaded"
        })

        assertTrue(cache.files.isEmpty())
        assertTrue(lines.isEmpty())
        assertEquals(16_666_666L, nowNs)
    }

    @Test
    fun `with a debug build's pace a speech model's load reads its files in at that pace first`() {
        val encoder = model("encoder.int8.onnx", 3)
        val tokens = model("tokens.txt", 1)
        val pacing = pacing("100")

        val loaded = pacing.otherLoad("cohere-transcribe-14-lang-int8", listOf(encoder, tokens)) {
            assertEquals("cohere-transcribe-14-lang-int8", pacing.gate.holder)
            assertEquals(listOf("encoder.int8.onnx", "tokens.txt"), cache.files)
            "loaded"
        }

        assertEquals("loaded", loaded)
        assertNull(pacing.gate.holder)
        // 4 MiB at 100 MB/s.
        assertEquals(40_000_000L, nowNs)
        assertEquals(
            listOf(
                "Pre-read of cohere-transcribe-14-lang-int8: 4 MB of 4 MB read in 40 ms at 100 MB/s; " +
                    "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load"
            ),
            lines
        )
    }

    @Test
    fun `the recorder's load goes ahead of a load holding the gate, without waiting`() {
        val parakeet = model("encoder.int8.onnx", 1)
        for (pace in listOf("off", "")) {
            lines.clear()
            val pacing = pacing(pace)
            pacing.gate.acquire("gemma-4-E2B_q4_0-it.gguf") { false }

            val loaded = pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(parakeet)) {
                assertTrue(pacing.gate.recorderLoading)
                "loaded"
            }

            assertEquals("loaded", loaded)
            assertEquals("Load gate: parakeet-tdt-0.6b-v3-int8, the recorder's, goes ahead of gemma-4-E2B_q4_0-it.gguf", lines.first())
            if (pace == "off") {
                assertEquals(1, lines.size)
            } else {
                assertTrue(lines[1], lines[1].startsWith("Pre-read of parakeet-tdt-0.6b-v3-int8: 1 MB of 1 MB read in "))
            }
            pacing.gate.release()
        }
    }

    @Test
    fun `one load at a time, the recorder's never behind another, and a load about to start waits for it`() {
        val e2b = model("gemma-4-E2B_q4_0-it.gguf", 1)
        val oneB = model("gemma-3-1b-it-Q8_0.gguf", 1)
        val pacing = pacing("")
        val events = CopyOnWriteArrayList<String>()
        val e2bIn = CountDownLatch(1)
        val e2bOut = CountDownLatch(1)
        val recorderIn = CountDownLatch(1)
        val recorderOut = CountDownLatch(1)

        val first = thread {
            runBlocking {
                pacing.aiLoad(e2b).aroundNativeLoad(readIn = null, preReadMbPerSecond = 0) {
                    events += "E2B"
                    e2bIn.countDown()
                    e2bOut.await()
                }
            }
        }
        assertTrue(e2bIn.await(2, TimeUnit.SECONDS))
        val second = thread {
            runBlocking { pacing.aiLoad(oneB).aroundNativeLoad(readIn = null, preReadMbPerSecond = 0) { events += "1B" } }
        }
        Thread.sleep(100)
        assertEquals("the 1B loaded beside E2B", listOf("E2B"), events)

        val recorder = thread {
            pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList()) {
                events += "Parakeet"
                recorderIn.countDown()
                recorderOut.await()
            }
        }
        // The recorder's load goes at once, though E2B holds the gate and the 1B waits for it.
        assertTrue(recorderIn.await(2, TimeUnit.SECONDS))
        e2bOut.countDown()
        first.join(2_000)
        Thread.sleep(100)
        assertEquals("the 1B started beside the recorder's load", listOf("E2B", "Parakeet"), events)

        recorderOut.countDown()
        recorder.join(2_000)
        second.join(2_000)
        assertEquals(listOf("E2B", "Parakeet", "1B"), events)
        assertNull(pacing.gate.holder)
        assertTrue(lines.toString(), "Load gate: parakeet-tdt-0.6b-v3-int8, the recorder's, goes ahead of gemma-4-E2B_q4_0-it.gguf" in lines)
        assertTrue(lines.toString(), lines.any { Regex("Load gate: gemma-3-1b-it-Q8_0.gguf waited \\d+ ms for another load").matches(it) })
    }

    @Test
    fun `an AI model's pre-read gives way to a recorder's load that starts meanwhile, at its next piece`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 2)
        val recorderDone = CountDownLatch(1)
        lateinit var pacing: ModelLoadPacing
        // The recorder's load starts as the pre-read opens the file, once the gate is the AI load's.
        val startsRecorder = Opened(onOpen = {
            thread {
                pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList()) { Thread.sleep(100) }
                recorderDone.countDown()
            }
            while (!pacing.gate.recorderLoading) Thread.sleep(1)
        })
        // Each poll of the pace takes a real millisecond, so the recorder's load can end.
        pacing = pacing("", residency = startsRecorder, sleepNs = { nowNs += it; Thread.sleep(1) })

        pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {}

        assertTrue(recorderDone.await(2, TimeUnit.SECONDS))
        assertTrue(lines.toString(), "Load gate: parakeet-tdt-0.6b-v3-int8, the recorder's, goes ahead of gemma-4-E2B_q4_0-it.gguf" in lines)
        val preRead = lines.last()
        assertTrue(
            preRead,
            Regex(
                "Pre-read of gemma-4-E2B_q4_0-it\\.gguf: 2 MB of 2 MB read in \\d+ ms at 120 MB/s; waited 0 ms for memory " +
                    "\\(lowest MemFree 4000 MB\\), [1-9]\\d* ms for the recorder's load"
            ).matches(preRead)
        )
    }

    @Test
    fun `a load that waited for another says how long`() {
        val encoder = model("encoder.int8.onnx", 1)
        val pacing = pacing("")
        pacing.gate.acquire("gemma-3-1b-it-Q8_0.gguf") { false }
        val freer = thread {
            Thread.sleep(100)
            pacing.gate.release()
        }

        pacing.otherLoad("cohere-transcribe-14-lang-int8", listOf(encoder)) {}
        freer.join()

        val waited = lines.first()
        assertTrue(waited, Regex("Load gate: cohere-transcribe-14-lang-int8 waited \\d+ ms for another load").matches(waited))
    }

    @Test
    fun `an AI model's load whose pre-read waited 10 s for memory is stopped, and llama_cpp never starts`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 2)
        memFree = { 100L }
        val pacing = pacing("", debuggable = false)

        val stopped = runCatching { pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) { error("llama.cpp loaded") } }

        assertTrue(stopped.exceptionOrNull().toString(), stopped.exceptionOrNull() is PacedLoadStoppedException)
        assertEquals(
            "The load of gemma-4-E2B_q4_0-it.gguf was stopped: MemFree stayed under 150 MB for 10 s while its files were read in",
            stopped.exceptionOrNull()?.message
        )
        assertNull(pacing.gate.holder)
        assertEquals(10_000_000_000L, nowNs)
        assertEquals(
            listOf(
                "W Pre-read of gemma-4-E2B_q4_0-it.gguf gave up after 10000 ms: MemFree under 150 MB for 10 s; " +
                    "0 MB of 2 MB read; the load is stopped"
            ),
            lines
        )
    }

    @Test
    fun `a load's pre-read and weight copy wait 10 s in all for memory, then the copy stops the load`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 1)
        var copying = false
        // Short of memory for the pre-read's first 6 s, then again throughout the copy.
        memFree = { if (copying || nowNs < 6_000_000_000L) 100L else 4_000L }
        val pacing = pacing("", debuggable = false)
        val load = pacing.aiLoad(gguf)
        var goOn: Boolean? = null

        load.aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {
            copying = true
            val copy = load.weightCopy(120)
            goOn = copy.beforeTensor(0)
            copy.ended()
        }

        assertEquals(false, goOn)
        assertNull(pacing.gate.holder)
        assertEquals(
            listOf(
                "Pre-read of gemma-4-E2B_q4_0-it.gguf: 1 MB of 1 MB read in 6008 ms at 120 MB/s; " +
                    "waited 6000 ms for memory in 1 wait (longest 6000 ms: 1 until MemFree came back; floor 150 MB, lowest MemFree 100 MB), " +
                    "0 ms for the recorder's load",
                "W Weight copy of gemma-4-E2B_q4_0-it.gguf at 120 MB/s: 0 MB copied, 0 MB left in the mapping, in 4000 ms; " +
                    "waited 4000 ms for memory in 1 wait (longest 4000 ms: 1 gave up; floor 150 MB, lowest MemFree 100 MB), " +
                    "0 ms for the recorder's load; the load is stopped: " +
                    "MemFree under 150 MB for 10 s in all"
            ),
            lines
        )
    }

    @Test
    fun `each load has 10 s of its own, whatever another load waited`() = runBlocking {
        val gguf = model("gemma-3-1b-it-Q8_0.gguf", 1)
        memFree = { 100L }
        val pacing = pacing("", debuggable = false)
        assertTrue(runCatching { pacing.aiLoad(gguf).aroundNativeLoad(null, 120) {} }.exceptionOrNull() is PacedLoadStoppedException)

        // The next load finds memory: it reads in and copies without a wait.
        memFree = { 4_000L }
        lines.clear()
        val next = pacing.aiLoad(gguf)
        var goOn: Boolean? = null
        next.aroundNativeLoad(null, 120) { goOn = next.weightCopy(120).beforeTensor(0) }

        assertEquals(true, goOn)
        assertTrue(lines.single(), lines.single().startsWith("Pre-read of gemma-3-1b-it-Q8_0.gguf: 1 MB of 1 MB read in "))
    }

    @Test
    fun `a speech model's load whose pre-read waited too long for memory is stopped before it loads`() {
        val encoder = model("encoder.int8.onnx", 2)
        memFree = { 100L }
        val pacing = pacing("120")

        val stopped = runCatching { pacing.otherLoad("cohere-transcribe-14-lang-int8", listOf(encoder)) { error("loaded") } }

        assertTrue(stopped.exceptionOrNull() is PacedLoadStoppedException)
        assertNull(pacing.gate.holder)
        assertEquals(
            listOf(
                "W Pre-read of cohere-transcribe-14-lang-int8 gave up after 10000 ms: MemFree under 150 MB for 10 s; " +
                    "0 MB of 2 MB read; the load is stopped"
            ),
            lines
        )
    }

    @Test
    fun `the recorder's load goes ahead unpaced once its pre-read has waited 10 s for memory, in a release build too`() {
        val parakeet = model("encoder.int8.onnx", 2)
        memFree = { 100L }
        val pacing = pacing(pace = null, debuggable = false)
        var loadedAtNs: Long? = null

        val loaded = pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(parakeet)) {
            loadedAtNs = nowNs
            "loaded"
        }

        assertEquals("loaded", loaded)
        // 10 s of waiting, then nothing more read in: the load reads the rest itself, at once.
        assertEquals(10_000_000_000L, loadedAtNs)
        assertEquals(10_000_000_000L, nowNs)
        assertEquals(
            listOf(
                "W Pre-read of parakeet-tdt-0.6b-v3-int8 gave up after 10000 ms: MemFree under 150 MB for 10 s; " +
                    "0 MB of 2 MB read; the recorder's load goes ahead unpaced"
            ),
            lines
        )
    }

    @Test
    fun `a recorder's pre-read that fails never fails its load, which goes ahead unpaced`() {
        val encoder = model("encoder.int8.onnx", 1)
        val pacing = pacing(pace = null, debuggable = false, residency = { throw IllegalStateException("no mapping") })

        assertEquals("loaded", pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder)) { "loaded" })

        assertFalse(pacing.gate.recorderLoading)
        assertEquals(listOf("W Pre-read of parakeet-tdt-0.6b-v3-int8 failed (IllegalStateException); the recorder's load goes ahead unpaced"), lines)
    }

    @Test
    fun `the recorder's load stops while its files are read in once the recording wants another speech model, and loads nothing`() {
        val encoder = model("encoder.int8.onnx", 4)
        val pacing = pacing(pace = null, debuggable = false)
        // Asked at each piece: switched as the third MiB would be read in.
        var asked = 0

        val stopped = runCatching {
            pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder), stop = { if (asked++ >= 2) LoadStop.SWITCHED else null }) {
                error("Parakeet loaded")
            }
        }

        val failure = stopped.exceptionOrNull()
        assertTrue(failure.toString(), failure is LoadSupersededException)
        assertEquals("The load of parakeet-tdt-0.6b-v3-int8 was stopped, as the recording wants another speech model now", failure?.message)
        assertEquals(LoadStop.SWITCHED, (failure as LoadSupersededException).why)
        assertFalse(pacing.gate.recorderLoading)
        assertEquals(
            listOf(
                "Pre-read of parakeet-tdt-0.6b-v3-int8 stopped after 16 ms, as the recording wants another speech model now: " +
                    "2 MB of 4 MB read; parakeet-tdt-0.6b-v3-int8 is not loaded"
            ),
            lines
        )
    }

    @Test
    fun `a recorder's load that waits for memory stops at once when the recording wants another speech model`() {
        val encoder = model("encoder.int8.onnx", 2)
        memFree = { 100L }
        val pacing = pacing(pace = null, debuggable = false)

        val stopped = runCatching {
            pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder), stop = { if (nowNs >= 1_000_000_000L) LoadStop.SWITCHED else null }) {
                error("Parakeet loaded")
            }
        }

        assertTrue(stopped.exceptionOrNull().toString(), stopped.exceptionOrNull() is LoadSupersededException)
        // At the first poll after the switch, not after 10 s of waiting.
        assertEquals(1_000_000_000L, nowNs)
        assertEquals(
            listOf(
                "Pre-read of parakeet-tdt-0.6b-v3-int8 stopped after 1000 ms, as the recording wants another speech model now: " +
                    "0 MB of 2 MB read; parakeet-tdt-0.6b-v3-int8 is not loaded"
            ),
            lines
        )
    }

    @Test
    fun `a recorder's load the recording no longer wants as it would start loads nothing`() {
        val pacing = pacing("off")

        val stopped = runCatching { pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), stop = { LoadStop.SWITCHED }) { error("Parakeet loaded") } }

        assertTrue(stopped.exceptionOrNull().toString(), stopped.exceptionOrNull() is LoadSupersededException)
        assertFalse(pacing.gate.recorderLoading)
        assertEquals(listOf("Load of parakeet-tdt-0.6b-v3-int8 not started, as the recording wants another speech model now"), lines)
    }

    @Test
    fun `a load without MAP_POPULATE reads in only the tensors it reads in place`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 4)
        val pacing = pacing("")
        // The tied output in the second MiB, and a norm early in the fourth.
        val inPlace = listOf(MIB.toLong() until 2L * MIB, 3L * MIB + 100 until 3L * MIB + 200)

        pacing.aiLoad(gguf).aroundNativeLoad(inPlace, preReadMbPerSecond = 100) {}

        // 1 MiB and one 16 KiB block, at 100 MB/s.
        assertEquals(
            "Pre-read of gemma-4-E2B_q4_0-it.gguf, the tensors it reads in place: 1 MB of 1 MB read in 10 ms at 100 MB/s; " +
                "waited 0 ms for memory (lowest MemFree 4000 MB), 0 ms for the recorder's load",
            lines.single()
        )
    }

    @Test
    fun `a file whose cached pages can't be seen is named, and read in full`() = runBlocking {
        val gguf = model("gemma-3-1b-it-Q8_0.gguf", 1)
        val pacing = pacing("", residency = { FileResidency.Unseen("neither the app's nor writable by it, so mincore would call every page cached") })

        pacing.aiLoad(gguf).aroundNativeLoad(null, preReadMbPerSecond = 100) {}

        assertEquals(
            "W Pre-read of gemma-3-1b-it-Q8_0.gguf: gemma-3-1b-it-Q8_0.gguf's cached pages can't be seen (neither the app's nor " +
                "writable by it, so mincore would call every page cached); all of it is read",
            lines.first()
        )
        assertTrue(lines[1], lines[1].startsWith("Pre-read of gemma-3-1b-it-Q8_0.gguf: 1 MB of 1 MB read in "))
    }

    @Test
    fun `the probe says what it read, and stops where a load would`() = runBlocking {
        val gguf = model("gemma-3-1b-it-Q8_0.gguf", 2)
        val pacing = pacing("")

        assertEquals(ProbeRead(2L * MIB, 2L * MIB, gaveUp = false), pacing.probe("1b", listOf(gguf), 200))
        memFree = { 100L }
        assertEquals(ProbeRead(0L, 2L * MIB, gaveUp = true), pacing.probe("1b", listOf(gguf), 200))

        assertEquals(
            "W Pre-read of 1b gave up after 10000 ms: MemFree under 150 MB for 10 s; 0 MB of 2 MB read; the probe stops",
            lines.last()
        )
        assertNull(pacing.gate.holder)
    }

    @Test
    fun `a file it can't read is named, and the load reads it itself`() {
        val pacing = pacing("120")

        pacing.otherLoad("parakeet-tdt-0.6b-v2-int8", listOf(File(tmp.root, "joiner.int8.onnx"))) {}

        assertEquals("W Pre-read of parakeet-tdt-0.6b-v2-int8: joiner.int8.onnx (FileNotFoundException) not read; the load reads it itself", lines.first())
    }

    @Test
    fun `an AI model's load whose file its pre-read can't read is stopped, and llama_cpp never starts`() = runBlocking {
        val pacing = pacing("", debuggable = false)

        val stopped = runCatching {
            pacing.aiLoad(File(tmp.root, "gemma-3-1b-it-Q8_0.gguf")).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) { error("llama.cpp loaded") }
        }

        // MAP_POPULATE would read it in one burst.
        assertTrue(stopped.exceptionOrNull().toString(), stopped.exceptionOrNull() is PacedLoadStoppedException)
        assertEquals(
            "The load of gemma-3-1b-it-Q8_0.gguf was stopped: gemma-3-1b-it-Q8_0.gguf (FileNotFoundException) couldn't be read in first",
            stopped.exceptionOrNull()?.message
        )
        assertNull(pacing.gate.holder)
        assertEquals(listOf("W Pre-read of gemma-3-1b-it-Q8_0.gguf: gemma-3-1b-it-Q8_0.gguf (FileNotFoundException) not read; the load is stopped"), lines)
    }

    @Test
    fun `an AI model's pre-read stops at its next piece once its coroutine is cancelled, and llama_cpp never starts`() = runBlocking {
        val gguf = model("gemma-3-1b-it-Q8_0.gguf", 2)
        // Short of memory: the pre-read waits, polling, until it may read on or is cancelled.
        memFree = { 100L }
        val opened = CountDownLatch(1)
        // Each poll takes 5 real milliseconds, so the cancel comes long before the 10 s of waiting are up.
        val pacing = pacing("", debuggable = false, residency = Opened(onOpen = { opened.countDown() }), sleepNs = { nowNs += it; Thread.sleep(5) })
        var loaded = false

        val load = launch(Dispatchers.Default) {
            pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) { loaded = true }
        }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        load.cancelAndJoin()

        assertFalse(loaded)
        assertNull(pacing.gate.holder)
        assertTrue("the pre-read waited on past the cancel: $nowNs ns", nowNs < 9_000_000_000L)
        assertTrue(lines.toString(), lines.none { "gave up" in it })
    }

    @Test
    fun `an AI load's weight copy gives way to the recorder's load at its next tensor`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 1)
        val pacing = pacing("", debuggable = false)
        val recorderIn = CountDownLatch(1)
        val recorderOut = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var goOn: Boolean? = null
        val load = pacing.aiLoad(gguf)

        load.aroundNativeLoad(readIn = null, preReadMbPerSecond = 0) {
            val recorder = thread {
                pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList()) {
                    recorderIn.countDown()
                    recorderOut.await()
                }
            }
            assertTrue(recorderIn.await(2, TimeUnit.SECONDS))
            val copy = load.weightCopy(120)
            val tensor = thread {
                goOn = copy.beforeTensor(0)
                answered.countDown()
            }
            // The copy waits while the recorder's load runs.
            assertFalse("the copy went on beside the recorder's load", answered.await(300, TimeUnit.MILLISECONDS))
            recorderOut.countDown()
            recorder.join(2_000)
            assertTrue(answered.await(2, TimeUnit.SECONDS))
            tensor.join(2_000)
        }

        assertEquals(true, goOn)
    }

    // --- Room for a speech model's parse (review of 0.7.1) -------------------------------------

    private val parse = 400L * MIB

    @Test
    fun `the recorder's load makes room for its parse at 120 MB per second and gives it back right before the native load`() {
        // kswapd keeps MemFree at 300 MB while the room is taken.
        memFree = { 300L }
        val pacing = pacing(pace = null, debuggable = false)

        val loaded = pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {
            // Given back before the load begins.
            assertEquals(listOf("open 450 MiB", "close 304 MiB at 2533 ms"), room.events)
            "loaded"
        }

        assertEquals("loaded", loaded)
        // 304 MiB with MemFree's 300 MB make the parse's 450 MB and 150 MB more.
        assertEquals(
            "Room for parakeet-tdt-0.6b-v3-int8's parse: 304 MB held in 2533 ms at 120 MB/s for its 450 MB, " +
                "MemFree 300 MB before and 604 MB with the room; waited 0 ms for memory",
            lines.last()
        )
    }

    @Test
    fun `a load for a file makes room the same way behind the gate`() {
        memFree = { 300L }
        val pacing = pacing("", roomProperty = "")

        pacing.otherLoad("cohere-transcribe-14-lang-int8", emptyList(), parseBytes = parse, cancelled = { false }) {
            assertEquals("cohere-transcribe-14-lang-int8", pacing.gate.holder)
            assertEquals(listOf("open 450 MiB", "close 304 MiB at 2533 ms"), room.events)
        }

        assertNull(pacing.gate.holder)
    }

    @Test
    fun `a load for a file gives its room back while the recorder's load runs, and makes it again after it`() {
        memFree = { 300L }
        val recorderIn = CountDownLatch(1)
        val recorderOut = CountDownLatch(1)
        // Each poll takes a real millisecond, so the recorder's load can end.
        val pacing = pacing("", roomProperty = "", sleepNs = { nowNs += it; Thread.sleep(1) })
        // The recorder's load starts once the room holds its first piece, and ends a little later.
        room.onTake = { taken ->
            if (taken == 1) {
                thread { pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList()) { recorderIn.countDown(); recorderOut.await(2, TimeUnit.SECONDS) } }
                assertTrue(recorderIn.await(2, TimeUnit.SECONDS))
                thread {
                    Thread.sleep(100)
                    recorderOut.countDown()
                }
            }
        }

        pacing.otherLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse, cancelled = { false }) {
            assertEquals(listOf("open 450 MiB", "close 8 MiB", "open 450 MiB", "close 304 MiB"), room.events.map { it.substringBefore(" at") })
        }

        assertNull(pacing.gate.holder)
    }

    @Test
    fun `no room is held when MemFree is room enough already, nor when a debug build turns it off`() {
        val pacing = pacing(pace = null, debuggable = false)

        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {}

        assertTrue(room.events.isEmpty())
        assertEquals("Room for parakeet-tdt-0.6b-v3-int8's parse: none held, MemFree 4000 MB is room enough for its 450 MB", lines.last())
        assertEquals(0L, nowNs)

        lines.clear()
        memFree = { 300L }
        pacing("", roomProperty = "off").recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {}
        assertTrue(room.events.isEmpty())
        assertTrue(lines.toString(), lines.none { it.startsWith("Room") })
        // An unknown value is the default, with a warning.
        pacing("", roomProperty = "maybe").recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {}
        assertEquals("open 450 MiB", room.events.first())
        assertTrue(lines.toString(), "W Ignoring debug.satori.load.room=maybe: not on or off" in lines)
    }

    @Test
    fun `a room that waited 10 s for memory is given back, and the recorder's load goes ahead unpaced`() {
        memFree = { 100L }
        val pacing = pacing(pace = null, debuggable = false)
        var loadedAtNs: Long? = null

        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) { loadedAtNs = nowNs }

        assertEquals(10_000_000_000L, loadedAtNs)
        assertEquals(listOf("open 450 MiB", "close 0 MiB at 10000 ms"), room.events)
        assertEquals(
            "W Room for parakeet-tdt-0.6b-v3-int8's parse gave up after 10000 ms: MemFree under 150 MB for 10 s; " +
                "0 MB of 450 MB held; the recorder's load goes ahead unpaced",
            lines.last()
        )
    }

    @Test
    fun `a load for a file whose room waited 10 s for memory is stopped and loads nothing`() {
        memFree = { 100L }
        val pacing = pacing(pace = null, debuggable = false)

        val stopped = runCatching { pacing.otherLoad("cohere-transcribe-14-lang-int8", emptyList(), parseBytes = parse) { error("loaded") } }

        assertTrue(stopped.exceptionOrNull().toString(), stopped.exceptionOrNull() is PacedLoadStoppedException)
        assertEquals(listOf("open 450 MiB", "close 0 MiB at 10000 ms"), room.events)
        assertNull(pacing.gate.holder)
        assertTrue(lines.last(), lines.last().endsWith("0 MB of 450 MB held; the load is stopped"))
    }

    @Test
    fun `a pre-read that waited 10 s for memory leaves no wait for a room, and the load goes ahead at once`() {
        val parakeet = model("encoder.int8.onnx", 2)
        memFree = { 100L }
        val pacing = pacing(pace = null, debuggable = false)

        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(parakeet), parseBytes = parse) {}

        assertEquals(10_000_000_000L, nowNs)
        assertTrue(room.events.isEmpty())
    }

    @Test
    fun `Stop while room is made gives it back, loads nothing and says why`() {
        memFree = { 300L }
        val pacing = pacing(pace = null, debuggable = false)

        val stopped = runCatching {
            pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse, stop = { if (nowNs >= 1_000_000_000L) LoadStop.STOPPED else null }) {
                error("Parakeet loaded")
            }
        }

        val failure = stopped.exceptionOrNull()
        assertTrue(failure.toString(), failure is LoadSupersededException)
        assertEquals(LoadStop.STOPPED, (failure as LoadSupersededException).why)
        assertEquals(listOf("open 450 MiB", "close 120 MiB at 1000 ms"), room.events)
        assertFalse(pacing.gate.recorderLoading)
        assertEquals(
            "Room for parakeet-tdt-0.6b-v3-int8's parse given back after 1000 ms, as Stop came first and the recording's audio " +
                "is kept for after Stop: 120 MB of 450 MB held; parakeet-tdt-0.6b-v3-int8 is not loaded",
            lines.last()
        )
    }

    @Test
    fun `a pre-read stopped as its capture ended says so, not that another model is wanted`() {
        val encoder = model("encoder.int8.onnx", 2)
        val pacing = pacing(pace = null, debuggable = false)

        val stopped = runCatching { pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder), stop = { LoadStop.ENDED }) { error("loaded") } }

        assertEquals(LoadStop.ENDED, (stopped.exceptionOrNull() as LoadSupersededException).why)
        assertEquals(
            listOf("Pre-read of parakeet-tdt-0.6b-v3-int8 stopped after 0 ms, as the capture ended: 0 MB of 2 MB read; parakeet-tdt-0.6b-v3-int8 is not loaded"),
            lines
        )
    }

    @Test
    fun `a room that can't be made lets the recorder's load go ahead without it`() {
        memFree = { 300L }
        room.failure = IllegalStateException("no shared memory")
        val pacing = pacing(pace = null, debuggable = false)

        assertEquals("loaded", pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) { "loaded" })

        assertEquals("W Room for parakeet-tdt-0.6b-v3-int8's parse not made (IllegalStateException); its load goes ahead without it", lines.last())
    }

    @Test
    fun `a recorder's pre-read that finds no Java heap for its buffer never fails its load`() {
        val encoder = model("encoder.int8.onnx", 1)
        val pacing = pacing(pace = null, debuggable = false, residency = { throw OutOfMemoryError("no heap for the buffer") })

        assertEquals("loaded", pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(encoder)) { "loaded" })

        assertEquals(listOf("W Pre-read of parakeet-tdt-0.6b-v3-int8 failed (OutOfMemoryError); the recorder's load goes ahead unpaced"), lines)
    }

    // --- The floor follows the kernel (0.7.2) --------------------------------------------------

    @Test
    fun `a pre-read that finds MemFree and Cached standing still under the floor goes on, and the next load has the lowered floor`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 2)
        // The razr of 2026-10-17 at 14:27: MemFree between its low watermark (121 MB) and 150 MB, kswapd asleep.
        memFree = { 146L }
        cachedMb = { 2_000L }
        val pacing = pacing("", debuggable = false)

        pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {}

        assertEquals(122L, pacing.memoryFloor.floorMb)
        assertEquals(
            "Pre-read of gemma-4-E2B_q4_0-it.gguf: 2 MB of 2 MB read in 516 ms at 120 MB/s; waited 500 ms for memory in 1 wait " +
                "(longest 500 ms: 1 stood still; floor 122 MB, lowest MemFree 146 MB), 0 ms for the recorder's load",
            lines.single()
        )
        assertEquals(
            listOf(
                "Memory floor 150 MB, resume 200 MB: /proc/zoneinfo can't be read (EACCES); it follows the kernel from the first wait that stands still",
                "Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of " +
                    "gemma-4-E2B_q4_0-it.gguf, so the kernel wasn't reclaiming"
            ),
            floorLines
        )

        // The recorder's next load holds room for its parse up to the parse and the lowered floor: 450 + 122 MB.
        lines.clear()
        memFree = { 300L }
        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {}
        assertEquals(
            "Room for parakeet-tdt-0.6b-v3-int8's parse: 272 MB held in 2266 ms at 120 MB/s for its 450 MB, " +
                "MemFree 300 MB before and 572 MB with the room; waited 0 ms for memory",
            lines.last()
        )
        // The floor's line came once in the process.
        assertEquals(2, floorLines.size)
    }

    @Test
    fun `the recorder's pre-read that finds MemFree and Cached standing still reads on paced, not unpaced after 10 s, in a release build too`() {
        val parakeet = model("encoder.int8.onnx", 2)
        // Between the razr's low watermark and the floor, kswapd asleep (2026-10-17 14:27).
        memFree = { 146L }
        cachedMb = { 2_000L }
        val pacing = pacing(pace = null, debuggable = false)
        var loadedAtNs: Long? = null

        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", listOf(parakeet)) { loadedAtNs = nowNs }

        assertEquals(516_666_666L, loadedAtNs)
        assertEquals(122L, pacing.memoryFloor.floorMb)
        assertEquals(
            "Pre-read of parakeet-tdt-0.6b-v3-int8: 2 MB of 2 MB read in 516 ms at 120 MB/s; waited 500 ms for memory in 1 wait " +
                "(longest 500 ms: 1 stood still; floor 122 MB, lowest MemFree 146 MB), 0 ms for the recorder's load",
            lines.single()
        )
        assertEquals(
            "Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of " +
                "parakeet-tdt-0.6b-v3-int8, so the kernel wasn't reclaiming",
            floorLines.last()
        )
    }

    @Test
    fun `the recorder's room for its parse that finds MemFree and Cached standing still is made paced, and gives it back before the load`() {
        memFree = { 146L }
        cachedMb = { 2_000L }
        val pacing = pacing(pace = null, debuggable = false)

        pacing.recorderLoad("parakeet-tdt-0.6b-v3-int8", emptyList(), parseBytes = parse) {
            assertEquals(listOf("open 450 MiB", "close 450 MiB at 4250 ms"), room.events)
        }

        assertEquals(122L, pacing.memoryFloor.floorMb)
        assertEquals(
            "Room for parakeet-tdt-0.6b-v3-int8's parse: 450 MB held in 4250 ms at 120 MB/s for its 450 MB, " +
                "MemFree 146 MB before and 596 MB with the room; waited 500 ms for memory in 1 wait (longest 500 ms: 1 stood still; floor 122 MB)",
            lines.last()
        )
        assertTrue(lines.toString(), lines.none { it.contains("unpaced") || it.startsWith("W ") })
        assertTrue(floorLines.last(), floorLines.last().contains("in the room for parakeet-tdt-0.6b-v3-int8's parse"))
    }

    @Test
    fun `a load that waits at the lowest floor gives up after 10 s, and its lines and its stop name the floor in effect`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 2)
        memFree = { 60L }
        cachedMb = { 2_000L }
        val pacing = pacing("")

        val stopped = runCatching { pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) { error("llama.cpp loaded") } }

        assertEquals(
            "The load of gemma-4-E2B_q4_0-it.gguf was stopped: MemFree stayed under 64 MB for 10 s while its files were read in",
            stopped.exceptionOrNull()?.message
        )
        // Nothing read at a MemFree under even the lowest floor (review of 0.7.2).
        assertEquals(
            listOf(
                "W Pre-read of gemma-4-E2B_q4_0-it.gguf gave up after 10000 ms: MemFree under 64 MB for 10 s; 0 MB of 2 MB read; the load is stopped"
            ),
            lines
        )
        assertEquals(
            listOf(
                "Memory floor lowered to 64 MB (resume 114 MB): MemFree stood still at 60 MB for 500 ms in the pre-read of " +
                    "gemma-4-E2B_q4_0-it.gguf, so the kernel wasn't reclaiming",
                "W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the pre-read of gemma-4-E2B_q4_0-it.gguf"
            ),
            floorLines.drop(1)
        )
    }

    @Test
    fun `a debug build's debug_satori_mem_still off waits as 0_7_1 did, and its debug_satori_mem_floor sets the floor of its loads`() = runBlocking {
        val gguf = model("gemma-4-E2B_q4_0-it.gguf", 1)
        memFree = { 146L }
        cachedMb = { 2_000L }
        floorProperties[MemoryFloor.STILL_PROPERTY] = "off"
        val pacing = pacing("")

        val stopped = runCatching { pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {} }

        assertTrue(stopped.exceptionOrNull() is PacedLoadStoppedException)
        assertEquals(150L, pacing.memoryFloor.floorMb)
        assertEquals(10_000_000_000L, nowNs)

        // A floor of 100 MB: MemFree at 146 MB is over it, so nothing waits.
        floorProperties[MemoryFloor.STILL_PROPERTY] = ""
        floorProperties[MemoryFloor.FLOOR_PROPERTY] = "100"
        lines.clear()
        pacing.aiLoad(gguf).aroundNativeLoad(readIn = null, preReadMbPerSecond = 120) {}

        assertTrue(lines.single(), lines.single().contains("; waited 0 ms for memory (lowest MemFree 146 MB),"))
        assertEquals("Memory floor 100 MB, resume 150 MB: debug.satori.mem.floor", floorLines.last())
    }

    private companion object {
        const val MIB = 1_048_576
    }
}
