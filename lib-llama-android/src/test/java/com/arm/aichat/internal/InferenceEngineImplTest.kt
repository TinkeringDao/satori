package com.arm.aichat.internal

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The engine's loads: each model's own settings, in a release build and in a debug build's own work
 * alike, and the debug properties over them only for the memtest's load and an armed window; the
 * line each load logs; the pre-read's pace handed to the app's hooks, and the copy's pacer asked
 * for. The engine clears the system prompt cache on its own thread, where every native call runs,
 * so the clear comes after a call under way and never runs beside one. The sampler's setting goes
 * to the native side only when it changes, and each load puts ai_chat.cpp's default back.
 *
 * The native library can't load in a JVM test; the engine is in its error state then, which
 * neither looks at. Its loads run over a stand-in for llama.cpp's part ([InferenceEngineImpl.NativeLoader]):
 * a caller that goes away while llama.cpp loads leaves the engine ready with the model, a weight
 * pacer can stop a load, and a load without MAP_POPULATE tells the app what it reads in place.
 */
class InferenceEngineImplTest {

    /** What llama.cpp's part of a load was given. */
    private data class NativeLoad(
        val mmap: Boolean,
        val directIo: Boolean,
        val extraBufts: Boolean,
        val tiedOutputLayers: String?,
        val paced: Boolean,
        val populate: Boolean,
        val dropCopied: Boolean
    )

    /** What the context was made with. */
    private data class Context(val nCtx: Int, val swaFull: Boolean, val kvQ8: Boolean, val nOutputsMax: Int)

    /** llama.cpp's part of a load, which [whileLoading] stands in for (0: loaded); [drop] is what lastDrop gives. */
    private class FakeNative(
        private val drop: LongArray? = null,
        private val whileLoading: (InferenceEngine.WeightPacer?) -> Int = { 0 }
    ) : InferenceEngineImpl.NativeLoader {
        val calls = CopyOnWriteArrayList<String>()
        val loads = CopyOnWriteArrayList<NativeLoad>()
        val contexts = CopyOnWriteArrayList<Context>()
        override fun start() {}
        override fun directIoApplies(modelPath: String) = false
        override fun dropFileCache(path: String) = true
        override fun load(
            modelPath: String,
            useMmap: Boolean,
            useDirectIo: Boolean,
            useExtraBufts: Boolean,
            tiedOutputLayers: String?,
            pacer: InferenceEngine.WeightPacer?,
            populate: Boolean,
            dropCopied: Boolean
        ): Int {
            calls += "load"
            loads += NativeLoad(useMmap, useDirectIo, useExtraBufts, tiedOutputLayers, pacer != null, populate, dropCopied)
            return whileLoading(pacer)
        }
        override fun lastDrop(): LongArray? = drop
        override fun prepare(nThreadsHint: Int, nCtx: Int, swaFull: Boolean, kvQ8: Boolean, nOutputsMax: Int): Int {
            calls += "prepare"
            contexts += Context(nCtx, swaFull, kvQ8, nOutputsMax)
            return 0
        }
        val samplers = CopyOnWriteArrayList<InferenceEngineImpl.SamplerSetting>()
        override fun updateSamplerConfig(temperature: Float, topK: Int, topP: Float, minP: Float, repeatPenalty: Float): Int {
            calls += "sampler"
            samplers += InferenceEngineImpl.SamplerSetting(temperature, topK, topP, minP, repeatPenalty)
            return 0
        }
        override fun unload() {
            calls += "unload"
        }
    }

    /** A pacer that lets every tensor go. */
    private object GoOn : InferenceEngine.WeightPacer {
        override fun beforeTensor(doneBytes: Long) = true
        override fun ended(loaded: Boolean) {}
    }

    /** Hooks as the app's pacing has them: a pre-read on IO, then llama.cpp's part in the caller's coroutine. */
    private class PacedHooks(private val pacer: InferenceEngine.WeightPacer? = GoOn) : InferenceEngine.LoadHooks {
        val readIns = CopyOnWriteArrayList<List<LongRange>?>()
        val preReads = CopyOnWriteArrayList<Int>()
        val copyPaces = CopyOnWriteArrayList<Int>()
        override suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) {
            readIns += readIn
            preReads += preReadMbPerSecond
            withContext(Dispatchers.IO) {}
            nativeLoad()
        }
        override fun weightPacer(mbPerSecond: Int): InferenceEngine.WeightPacer? {
            copyPaces += mbPerSecond
            return pacer
        }
    }

    private val lines = CopyOnWriteArrayList<String>()

    private fun newEngine(
        thread: ExecutorService,
        native: FakeNative,
        properties: Map<String, String> = emptyMap(),
        debug: Boolean = true
    ) = InferenceEngineImpl(
        "/no/native/libraries", null, thread.asCoroutineDispatcher(),
        debugSwitches = debug,
        propertyReader = { name -> properties[name].orEmpty() },
        nativeLoader = native,
        loadLog = { lines += it },
        warnLog = { lines += "W $it" }
    )

    @get:Rule
    val tmp = TemporaryFolder()

    // The fast rung as llama.cpp gets it, and its line's settings (memtest.sh checks these).
    private val fastLoad = NativeLoad(mmap = true, directIo = false, extraBufts = true, tiedOutputLayers = LoadSettings.TIED_OUTPUT_LAYERS, paced = true, populate = true, dropCopied = true)
    private val fastContext = Context(nCtx = 4096, swaFull = false, kvQ8 = true, nOutputsMax = 1)
    private val run4 = "llama.cpp repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped copypace=120 dropcopied=on"

    @Test
    fun `every load of Gemma 3 1B and Gemma 4 E2B has the fast rung's settings, in a release build and a debug build's own work`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            // Properties left set on the phone: neither build reads them for its own work.
            val leftOver = mapOf("debug.satori.llm.repack" to "off", "debug.satori.load.pace" to "off", "debug.satori.llm.cold" to "on")
            runBlocking {
                for ((file, whose) in listOf("gemma-3-1b-it-Q8_0.gguf" to "Gemma 3 1B's", "gemma-4-E2B_q4_0-it.gguf" to "Gemma 4 E2B's")) {
                    val model = File(tmp.root, file).apply { if (!exists()) createNewFile() }
                    for (debug in listOf(false, true)) {
                        lines.clear()
                        val native = FakeNative()
                        val engine = newEngine(thread, native, leftOver, debug = debug)
                        engine.state.first { it is InferenceEngine.State.Initialized }
                        val hooks = PacedHooks()

                        engine.loadModel(model.path, 2, hooks)

                        val build = if (debug) "debug" else "release"
                        assertEquals(build, listOf(fastLoad), native.loads)
                        assertEquals(build, listOf(fastContext), native.contexts)
                        assertEquals(build, listOf(120), hooks.preReads)
                        assertEquals(build, listOf(120), hooks.copyPaces)
                        // MAP_POPULATE maps the whole file, so all of it is read in first.
                        assertEquals(build, listOf<List<LongRange>?>(null), hooks.readIns)
                        assertEquals(build, run4, engine.loadSettings)
                        assertEquals(build, listOf("Load settings of $file ($whose): pre-read 120 MB/s, $run4"), lines)
                    }
                }
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `any other model loads paced, with llama_cpp's own settings and its own repacking`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val native = FakeNative()
            val engine = newEngine(thread, native, debug = false)
            val model = tmp.newFile("Qwen3.5-0.8B-Q8_0.gguf")
            val hooks = PacedHooks()
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }

                engine.loadModel(model.path, 2, hooks)
            }

            assertEquals(listOf(NativeLoad(mmap = true, directIo = false, extraBufts = true, tiedOutputLayers = null, paced = true, populate = true, dropCopied = true)), native.loads)
            assertEquals(listOf(Context(nCtx = 4096, swaFull = true, kvQ8 = false, nOutputsMax = 0)), native.contexts)
            assertEquals(listOf(120), hooks.preReads)
            assertEquals(listOf(120), hooks.copyPaces)
            assertEquals(
                listOf(
                    "Load settings of Qwen3.5-0.8B-Q8_0.gguf (any other model's): pre-read 120 MB/s, " +
                        "llama.cpp repack=on mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096 copypace=120 dropcopied=on"
                ),
                lines
            )
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a memtest that sets nothing loads with its model's settings, and one that sets properties changes those only`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val model = tmp.newFile("gemma-4-E2B_q4_0-it.gguf")
            suspend fun memtest(properties: Map<String, String>): Pair<FakeNative, PacedHooks> {
                val native = FakeNative()
                val engine = newEngine(thread, native, properties)
                engine.state.first { it is InferenceEngine.State.Initialized }
                val hooks = PacedHooks()
                engine.withDebugLoadSettings { engine.loadModel(model.path, 2, hooks) }
                return native to hooks
            }
            runBlocking {
                val (plain, plainHooks) = memtest(emptyMap())
                assertEquals(listOf(fastLoad), plain.loads)
                assertEquals(listOf(fastContext), plain.contexts)
                assertEquals(listOf(120), plainHooks.preReads)
                assertEquals("Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's): pre-read 120 MB/s, $run4", lines.last())

                // Run 6 by memtest.sh's keys: repack off, the copy left unpaced, a cold load, the pre-read slower.
                val (lean, leanHooks) = memtest(
                    mapOf(
                        "debug.satori.llm.repack" to "off",
                        "debug.satori.llm.copypace" to "off",
                        "debug.satori.llm.cold" to "on",
                        "debug.satori.load.pace" to "60"
                    )
                )
                // Nothing is copied with repack off, so nothing is dropped.
                assertEquals(listOf(NativeLoad(mmap = true, directIo = false, extraBufts = false, tiedOutputLayers = null, paced = false, populate = true, dropCopied = false)), lean.loads)
                assertEquals(listOf(fastContext), lean.contexts)
                assertEquals(listOf(60), leanHooks.preReads)
                assertTrue(leanHooks.copyPaces.isEmpty())
                assertEquals(
                    "Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's; debug properties: load.pace, repack, copypace, cold): " +
                        "pre-read 60 MB/s, llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 cold=on",
                    lines.last()
                )
            }
        } finally {
            thread.shutdownNow()
        }
    }

    // ─── The copied tensors' pages (0.7.2) ───────────────────────────────────────

    // The 1B's drop as lastDrop gives it: 707.03 MiB copied, 706.72 MiB given back in 78 ranges, none
    // still cached, the file the app's, RssFile 1,158 -> 452 MB, in 96 ms.
    private val oneBDrop = longArrayOf(741_374_976L, 741_048_320L, 0L, 78L, 0L, 1L, 1_185_792L, 462_848L, 96_000L)

    // One load on an engine of its own; a load that fails is let fail.
    private fun loadOnce(file: String, native: FakeNative, debug: Boolean = false, properties: Map<String, String> = emptyMap(), memtest: Boolean = false) {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val model = File(tmp.root, file).apply { if (!exists()) createNewFile() }
            val engine = newEngine(thread, native, properties, debug = debug)
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }
                runCatching {
                    if (memtest) engine.withDebugLoadSettings { engine.loadModel(model.path, 2, PacedHooks()) } else engine.loadModel(model.path, 2, PacedHooks())
                }
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a load that copied tensors says what it gave back of their pages, in a release build too`() {
        loadOnce("gemma-3-1b-it-Q8_0.gguf", FakeNative(drop = oneBDrop))

        assertEquals(
            listOf(
                "Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's): pre-read 120 MB/s, $run4",
                "Copied pages of gemma-3-1b-it-Q8_0.gguf given back: 706 MB of the 707 MB copied, in 78 ranges in 96 ms; " +
                    "0 MB still cached; RssFile 1158 -> 452 MB"
            ),
            lines
        )
    }

    @Test
    fun `a load that gave nothing back, or that failed, has no drop line`() {
        loadOnce("gemma-4-E2B_q4_0-it.gguf", FakeNative(drop = null))
        assertEquals(1, lines.size)

        lines.clear()
        loadOnce("gemma-4-E2B_q4_0-it.gguf", FakeNative(drop = oneBDrop) { 1 })
        assertTrue(lines.none { it.contains("Copied pages") })
    }

    @Test
    fun `a drop that went only in part, or whose calls failed, says so as a warning`() {
        // Pages still cached, though pageout may act: another mapping holds them.
        loadOnce("gemma-3-1b-it-Q8_0.gguf", FakeNative(drop = oneBDrop.copyOf().also { it[2] = 300L * 1_048_576 }))
        // A file that is neither the app's nor writable: mincore can't tell, and pageout may not act.
        loadOnce("gemma-3-1b-it-Q8_0.gguf", FakeNative(drop = oneBDrop.copyOf().also { it[2] = -1; it[5] = 0 }))
        // madvise failed with EINVAL's 22 (the JVM test has no errno names).
        loadOnce("gemma-3-1b-it-Q8_0.gguf", FakeNative(drop = oneBDrop.copyOf().also { it[4] = 22 }))

        assertEquals(
            listOf(
                "W Copied pages of gemma-3-1b-it-Q8_0.gguf given back only in part: 300 MB of the 706 MB still cached " +
                    "(another mapping holds them, or the kernel kept them); RssFile 1158 -> 452 MB",
                "W Copied pages of gemma-3-1b-it-Q8_0.gguf given back only in part: 706 MB of the 707 MB copied unmapped in 78 ranges, " +
                    "but MADV_PAGEOUT may not act on them (the file is neither the app's nor writable by it); RssFile 1158 -> 452 MB",
                "W Copied pages of gemma-3-1b-it-Q8_0.gguf: madvise or posix_fadvise failed (errno 22); 706 MB of the 707 MB copied " +
                    "unmapped in 78 ranges, 0 MB still cached; RssFile 1158 -> 452 MB"
            ),
            lines.filter { it.startsWith("W ") }
        )
    }

    @Test
    fun `the drop is the fast rung's and any other model's, and a memtest's dropcopied off turns it off for its own load only`() {
        val leftOver = mapOf("debug.satori.llm.dropcopied" to "off")
        // A debug build's own work doesn't read the property left set; the memtest's load does.
        val own = FakeNative()
        loadOnce("gemma-4-E2B_q4_0-it.gguf", own, debug = true, properties = leftOver)
        val memtest = FakeNative()
        loadOnce("gemma-4-E2B_q4_0-it.gguf", memtest, debug = true, properties = leftOver, memtest = true)

        assertEquals(listOf(true), own.loads.map { it.dropCopied })
        assertEquals(listOf(false), memtest.loads.map { it.dropCopied })
        assertEquals(
            "Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's; debug properties: dropcopied): pre-read 120 MB/s, " +
                run4.removeSuffix(" dropcopied=on"),
            lines.last()
        )
    }

    @Test
    fun `a caller that goes away while llama_cpp loads leaves the engine ready with the model in memory`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val loading = CountDownLatch(1)
            val loaded = CountDownLatch(1)
            val native = FakeNative {
                loading.countDown()
                loaded.await()
                0
            }
            val engine = newEngine(thread, native)
            val model = tmp.newFile("gemma-3-1b-it-Q8_0.gguf")
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }
                val load = async(Dispatchers.Default) { engine.loadModel(model.path, 2, PacedHooks()) }
                loading.await()

                load.cancel()
                loaded.countDown()

                assertTrue(runCatching { load.await() }.exceptionOrNull() is CancellationException)
                // Ready, not in the error state, whose cleanUp() would drop the model without unloading it.
                assertEquals(InferenceEngine.State.ModelReady, engine.state.value)
                assertEquals(listOf("load", "prepare"), native.calls)
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a caller that goes away before llama_cpp starts leaves the engine as it was`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val native = FakeNative()
            val engine = newEngine(thread, native)
            val model = tmp.newFile("gemma-3-1b-it-Q8_0.gguf")
            val inPreRead = CompletableDeferred<Unit>()
            val waitingHooks = object : InferenceEngine.LoadHooks {
                override suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) {
                    inPreRead.complete(Unit)
                    awaitCancellation()
                }
            }
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }
                val load = async(Dispatchers.Default) { engine.loadModel(model.path, 2, waitingHooks) }
                inPreRead.await()

                load.cancel()

                assertTrue(runCatching { load.await() }.exceptionOrNull() is CancellationException)
                assertEquals(InferenceEngine.State.Initialized, engine.state.value)
                assertTrue(native.calls.isEmpty())
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a load the weight pacer stops fails with why, not as a model it can't read`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val stops = object : InferenceEngine.WeightPacer {
                var ended: Boolean? = null
                override fun beforeTensor(doneBytes: Long) = doneBytes == 0L
                override fun ended(loaded: Boolean) {
                    ended = loaded
                }
            }
            // llama.cpp asks before each tensor and stops at the first no.
            val native = FakeNative(drop = longArrayOf(1, 1, 0, 1, 0, 1, 1, 1, 1)) { pacer -> if (pacer!!.beforeTensor(0) && pacer.beforeTensor(1_048_576)) 0 else 1 }
            // The app's own load of E2B, a release build's: its copy is paced by default.
            val engine = newEngine(thread, native, debug = false)
            val model = tmp.newFile("gemma-4-E2B_q4_0-it.gguf")
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }

                val failed = runCatching { engine.loadModel(model.path, 2, PacedHooks(stops)) }.exceptionOrNull()

                assertTrue(failed.toString(), failed is IOException)
                assertEquals("The weight copy stopped the load: the phone stayed short of memory for 10 s", failed?.message)
                assertEquals(false, stops.ended)
                assertTrue(engine.state.value is InferenceEngine.State.Error)
                assertEquals(listOf("load"), native.calls)
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a load without MAP_POPULATE tells the app the tensors it reads in place, and every other load all of the file`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val model = TestGguf.write(tmp.newFile("gemma-4-E2B_q4_0-it.gguf"), TestGguf.rows("gemma-4-E2B_q4_0-it"))
            val notGguf = tmp.newFile("damaged.gguf").apply { writeBytes(ByteArray(64)) }
            val properties = mapOf("debug.satori.llm.populate" to "off")
            // One engine for each load: a load leaves its engine with the model.
            suspend fun readInOf(path: String, memtest: Boolean, more: Map<String, String> = emptyMap()): List<LongRange>? {
                val engine = newEngine(thread, FakeNative(), properties + more)
                engine.state.first { it is InferenceEngine.State.Initialized }
                val hooks = PacedHooks()
                if (memtest) engine.withDebugLoadSettings { engine.loadModel(path, 2, hooks) } else engine.loadModel(path, 2, hooks)
                return hooks.readIns.single()
            }
            runBlocking {
                val inPlace = readInOf(model.path, memtest = true)
                assertEquals(InPlaceTensors.ranges(GgufTensors.read(model), layersCopied = true), inPlace)
                assertEquals(265, inPlace?.size)
                // The app's own loads have their model's settings, MAP_POPULATE among them.
                assertNull(readInOf(model.path, memtest = false))
                // A tensor table that can't be read: all of the file.
                assertNull(readInOf(notGguf.path, memtest = true))
                // With no pre-read nothing is read in first, so the table isn't read for it.
                assertNull(readInOf(model.path, memtest = true, more = mapOf("debug.satori.load.pace" to "off")))
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `the cache is cleared once the call under way on the engine's thread has ended, never beside it`() {
        val dir = tmp.newFolder("llm-prompts")
        val state = File(dir, "state.bin").apply { writeBytes(ByteArray(16)) }
        val thread = Executors.newSingleThreadExecutor()
        try {
            val engine = InferenceEngineImpl("/no/native/libraries", PromptCache(dir, "6-0"), thread.asCoroutineDispatcher())
            runBlocking {
                val inCall = CompletableDeferred<Unit>()
                val callEnds = CountDownLatch(1)
                // A native call holds the engine's thread for as long as it runs: seconds for a load.
                thread.execute {
                    inCall.complete(Unit)
                    callEnds.await()
                }
                inCall.await()

                val clear = async(Dispatchers.Default) { engine.forgetPromptCache() }
                delay(300)
                assertTrue("cleared beside the call under way", state.exists())
                assertFalse(clear.isCompleted)

                callEnds.countDown()
                withTimeout(5_000) { clear.await() }
                assertFalse(state.exists())
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a debug build reads the debug properties only for a load under withDebugLoadSettings, and a release build never`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val reads = CopyOnWriteArrayList<String>()
            val properties = mapOf("debug.satori.llm.repack" to "off", "debug.satori.llm.cold" to "on")
            val debug = InferenceEngineImpl(
                "/no/native/libraries", null, thread.asCoroutineDispatcher(),
                debugSwitches = true,
                propertyReader = { name -> reads += name; properties[name].orEmpty() }
            )
            val release = InferenceEngineImpl(
                "/no/native/libraries", null, thread.asCoroutineDispatcher(),
                debugSwitches = false,
                propertyReader = { name -> throw AssertionError("a release build read $name") }
            )
            val fast = LoadSettings.FAST
            runBlocking {
                // The app's own loads: the properties left set are not even read.
                assertEquals(fast, debug.settingsForLoad(fast))
                assertTrue(reads.isEmpty())

                assertEquals(fast.copy(repack = false, cold = true), debug.withDebugLoadSettings { debug.settingsForLoad(fast) })
                assertTrue(reads.contains("debug.satori.load.pace"))

                // A memtest whose load fails ends its window all the same.
                assertTrue(runCatching { debug.withDebugLoadSettings { error("the load failed") } }.isFailure)
                reads.clear()
                assertEquals(fast, debug.settingsForLoad(fast))
                assertTrue(reads.isEmpty())

                assertEquals(fast, release.withDebugLoadSettings { release.settingsForLoad(fast) })
                assertEquals(LoadSettings.LEAN, release.settingsForLoad(LoadSettings.LEAN))
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a sampler setting the native side has is never sent again, and each load puts the default back`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val native = FakeNative()
            val engine = newEngine(thread, native)
            val model = tmp.newFile("gemma-3-1b-it-Q8_0.gguf")
            val default = InferenceEngineImpl.SamplerSetting.DEFAULT
            val greedy = default.copy(temperature = 0f)
            suspend fun set(setting: InferenceEngineImpl.SamplerSetting) =
                engine.setSamplerConfig(setting.temperature, setting.topK, setting.topP, setting.minP, setting.repeatPenalty)
            runBlocking {
                engine.state.first { it is InferenceEngine.State.Initialized }
                // No model, no sampler: refused as before.
                assertTrue(runCatching { set(default) }.exceptionOrNull() is IllegalStateException)

                engine.loadModel(model.path, 2, PacedHooks())
                // The default, which the load's prepare set: the app asks for it before every reply, at no cost.
                set(default)
                set(default)
                assertTrue(native.samplers.isEmpty())
                // A rerun's greedy goes once, and the next call's default once, to put it back.
                set(greedy)
                set(greedy)
                set(default)
                assertEquals(listOf(greedy, default), native.samplers)

                // Greedy left set when the model goes: the next load's prepare made a default
                // sampler, so the default costs nothing and greedy goes again.
                set(greedy)
                engine.cleanUp()
                engine.loadModel(model.path, 2, PacedHooks())
                set(default)
                set(greedy)
                assertEquals(listOf(greedy, default, greedy, greedy), native.samplers)
                assertEquals(listOf("load", "prepare", "sampler", "sampler", "sampler", "unload", "load", "prepare", "sampler"), native.calls)
            }
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `the default sampler setting is the one ai_chat_cpp's prepare makes at every load`() {
        val source = File("src/main/cpp/ai_chat.cpp").readText()
        val temperature = Regex("""DEFAULT_SAMPLER_TEMP\s*=\s*([0-9.]+)f""").find(source)?.groupValues?.get(1)
            ?: error("no DEFAULT_SAMPLER_TEMP in ai_chat.cpp")
        val defaults = Regex(
            """sampler_params\(\s*float temp,\s*int top_k\s*=\s*(\d+),\s*float top_p\s*=\s*([0-9.]+)f,\s*""" +
                """float min_p\s*=\s*([0-9.]+)f,\s*float repeat_penalty\s*=\s*([0-9.]+)f"""
        ).find(source)?.groupValues ?: error("no defaults of sampler_params in ai_chat.cpp")

        assertEquals(
            InferenceEngineImpl.SamplerSetting(temperature.toFloat(), defaults[1].toInt(), defaults[2].toFloat(), defaults[3].toFloat(), defaults[4].toFloat()),
            InferenceEngineImpl.SamplerSetting.DEFAULT
        )
        // prepare, which every load runs, makes the sampler with those defaults.
        assertTrue(source.contains("g_sampler_params = sampler_params(DEFAULT_SAMPLER_TEMP);"))
    }

    @Test
    fun `an armed window makes every load of a debug build read the properties until it ends, and a release build never`() {
        val thread = Executors.newSingleThreadExecutor()
        try {
            var now = 1_000L
            val properties = mapOf("debug.satori.llm.tiedout" to "repack", "debug.satori.llm.copypace" to "60")
            val debug = InferenceEngineImpl(
                "/no/native/libraries", null, thread.asCoroutineDispatcher(),
                debugSwitches = true,
                propertyReader = { name -> properties[name].orEmpty() },
                clockMs = { now }
            )
            val release = InferenceEngineImpl(
                "/no/native/libraries", null, thread.asCoroutineDispatcher(),
                debugSwitches = false,
                propertyReader = { name -> throw AssertionError("a release build read $name") },
                clockMs = { now }
            )
            val fast = LoadSettings.FAST
            val variant = fast.copy(tiedOutputMapped = false, copyPaceMbPerSecond = 60)

            debug.armDebugLoadSettings(15 * 60_000L)
            release.armDebugLoadSettings(15 * 60_000L)
            assertEquals(variant, debug.settingsForLoad(fast))
            assertEquals(fast, release.settingsForLoad(fast))

            now += 15 * 60_000L - 1
            assertEquals(variant, debug.settingsForLoad(fast))
            now += 1
            assertEquals(fast, debug.settingsForLoad(fast))

            debug.armDebugLoadSettings(60_000L)
            assertEquals(variant, debug.settingsForLoad(fast))
            debug.armDebugLoadSettings(0)
            assertEquals(fast, debug.settingsForLoad(fast))
        } finally {
            thread.shutdownNow()
        }
    }
}
