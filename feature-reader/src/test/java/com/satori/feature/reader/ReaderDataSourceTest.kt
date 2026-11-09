package com.satori.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.MemoryReading
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.data.monitor.ReleasePurge
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.domain.model.CaptureState
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.reader.ReaderDims
import com.satori.reader.ReaderException
import com.satori.reader.ReaderGraphs
import com.satori.reader.ReaderOptions
import com.satori.reader.Reading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The reader's data source with fakes of the native reader, the tokenizer and the pace: a load
 * through the speech models' pace with the room for the arm's packed weights, never beside a
 * capture, the speech model or an AI model, stopped at once by a capture that opens or a cancel,
 * and released with the heap purged however it ends.
 */
class ReaderDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val calls = CopyOnWriteArrayList<String>()
    private val memoryLines = CopyOnWriteArrayList<String>()
    private val logged = CopyOnWriteArrayList<String>()

    // The data source's clock: the real one, or a test's own ([fakeNs]) that only the native fake moves.
    @Volatile private var fakeNs: Long? = null
    private val capture = MutableStateFlow(CaptureState.IDLE)
    private val speechModel = MutableStateFlow(false)
    private var aiModel = false
    private var purges = 0
    private var state = ModelFileState.Intact
    private lateinit var dir: File
    private lateinit var files: ReaderFiles

    private val engine = FakeEngine()
    private val pacing = FakePacing()

    private val natives = object : ReaderNatives {
        override fun engine(): ReaderEngineCalls = engine.also { calls += "engine" }
        override fun tokenizer(): ReaderTokenizerCalls = object : ReaderTokenizerCalls {
            override fun load(model: File) {
                calls += "tokenizer load ${model.name}"
            }

            override fun pieces(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" }

            override fun release() {
                calls += "tokenizer release"
            }
        }
    }

    private val source by lazy {
        ReaderDataSource(
            downloads = ModelDownloadManager(ExternalFilesContext(tmp.root)),
            pacing = pacing,
            memoryLines = { sink ->
                ModelMemoryLines.forFlags(
                    ApplicationInfo.FLAG_DEBUGGABLE,
                    log = { line -> sink?.invoke(line) },
                    read = { MemoryReading(3_000, 120, 80, null, null) },
                    later = { _, _ -> },
                    every = { _, _ -> AutoCloseable {} },
                    rssAnonMb = { 120 }
                )
            },
            releasePurge = ReleasePurge(debuggable = false, read = { "" }, warn = {}, heap = NativeHeap { purges++; Purge.ALL }),
            captureState = capture,
            speechModelLoaded = speechModel,
            aiModelInMemory = { aiModel },
            natives = natives,
            clock = { fakeNs ?: System.nanoTime() },
            log = { logged += "I $it" },
            warn = { logged += "W $it" }
        )
    }

    @Before
    fun files() {
        dir = tmp.newFolder("reader")
        File(dir, "config.json").writeText(
            "{\"encoder_vocab_size\": 122706, \"max_source_positions\": 256, \"max_target_positions\": 256, " +
                "\"decoder_start_token_id\": 2, \"eos_token_id\": 2, \"pad_token_id\": 1}"
        )
        File(dir, "dict.SRC.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"eng_Latn\": 4, \"mal_Mlym\": 13, \"▁a\": 20}")
        File(dir, "dict.TGT.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"▁Hello\": 10}")
        File(dir, "model.SRC").writeBytes(byteArrayOf(0x0A, 0x01))
        files = ReaderFiles("reader-test", dir, PackedBytes.of(ReaderModelId.INT8), dir.listFiles()!!.sorted()) { state }
    }

    @Test
    fun `the reader loads at the speech models' pace, with room for the arm's packed weights, and reads`() = runBlocking {
        val text = source.withReader(files, memoryLine = { memoryLines += it }) { reader -> reader.read("a").text }

        assertEquals("Hello", text)
        assertEquals("reader-test", pacing.name)
        assertEquals(files.loadFiles, pacing.files)
        assertEquals(139_218_944L, pacing.parseBytes)
        assertEquals(ReaderOptions(), engine.options)
        assertEquals(
            listOf("engine", "tokenizer load model.SRC", "load", "translate 4 0 1", "release", "tokenizer release"),
            calls
        )
        assertEquals(1, purges)
        assertTrue(memoryLines.toString(), memoryLines.any { it.startsWith("Memory: load of reader-test took") && "(peak" in it })
        assertTrue(memoryLines.toString(), memoryLines.any { it.startsWith("Memory: release of reader-test took") && "purged (M_PURGE_ALL" in it })
        // Nothing stopped it, so no line says how long a stop took.
        assertFalse(logged.toString(), logged.any { "stopped" in it })
    }

    @Test
    fun `an arm's sessions and room go to the native reader`() = runBlocking {
        source.withReader(files, ReaderArm(Prepacking.ALL, arena = false, threads = 2)) {}

        assertEquals(231_251_968L, pacing.parseBytes)
        assertEquals(ReaderOptions(threads = 2, prepackDecoder = true, cpuArena = false, shrinkArenaAfterSegment = false), engine.options)
    }

    @Test
    fun `a source goes to the native reader with its beam, its cap and its minimum`() = runBlocking {
        val reading = source.withReader(files) { reader -> reader.translate(intArrayOf(13, 4, 20, 2), beam = 1, maxNewTokens = 5, minTokens = 255) }

        assertEquals(1, reading.ids.size)
        assertTrue(calls.toString(), "translate 1 5 255" in calls)
    }

    @Test
    fun `it never loads beside a capture, the speech model or an AI model, and reads nothing then`() {
        capture.value = CaptureState.OPENING
        assertRefused(ReaderRefusal.CAPTURE)
        capture.value = CaptureState.IDLE
        speechModel.value = true
        assertRefused(ReaderRefusal.SPEECH_MODEL)
        speechModel.value = false
        aiModel = true
        assertRefused(ReaderRefusal.AI_MODEL)

        assertNull(pacing.name)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `files that aren't whole, or a configuration that doesn't read, are refused before a load`() {
        state = ModelFileState.Missing
        assertRefused(ReaderRefusal.NOT_DOWNLOADED)
        state = ModelFileState.Damaged
        assertRefused(ReaderRefusal.DAMAGED)
        state = ModelFileState.Unreadable
        assertRefused(ReaderRefusal.UNREADABLE)
        state = ModelFileState.Intact
        File(dir, "config.json").writeText("<html>")
        assertRefused(ReaderRefusal.DAMAGED)

        assertNull(pacing.name)
    }

    @Test
    fun `the tokenizer alone loads paced with no room and no sessions`() = runBlocking {
        val pieces = source.withTokenizer(files) { tokenizer -> tokenizer.pieceIds.sourceIds(tokenizer.pieces("a")).toList() }

        assertEquals(listOf(13, 4, 20, 2), pieces)
        assertEquals("reader-test/tokenizer", pacing.name)
        assertEquals(listOf("model.SRC", "dict.SRC.json", "dict.TGT.json"), pacing.files?.map { it.name })
        assertEquals(0L, pacing.parseBytes)
        assertEquals(listOf("tokenizer load model.SRC", "tokenizer release"), calls)
        assertEquals(1, purges)
    }

    @Test
    fun `the tokenizer alone is refused where the reader is, beside a capture, the speech model or an AI model`() {
        // The owner's rule holds for every load of the reader's: never beside a capture or the 1B.
        for ((set, refusal) in listOf(
            { capture.value = CaptureState.TRANSCRIBING } to ReaderRefusal.CAPTURE,
            { speechModel.value = true } to ReaderRefusal.SPEECH_MODEL,
            { aiModel = true } to ReaderRefusal.AI_MODEL
        )) {
            capture.value = CaptureState.IDLE
            speechModel.value = false
            aiModel = false
            set()
            val refused = assertThrows(ReaderRefusedException::class.java) { runBlocking { source.withTokenizer(files) { error("used") } } }
            assertEquals(refusal, refused.refusal)
        }

        assertNull(pacing.name)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a load the native reader refuses releases what it made and throws its own exception`() {
        engine.loadFails = ReaderException.LoadFailed("not a model")

        assertThrows(ReaderException.LoadFailed::class.java) { runBlocking { source.withReader(files) { error("used") } } }

        assertEquals(listOf("engine", "tokenizer load model.SRC", "load", "release", "tokenizer release"), calls)
        assertEquals(1, purges)
    }

    @Test
    fun `a capture that opens during a reading stops the Run at once, and the reader is released`() = runBlocking {
        engine.translateBlocks = true
        val reading = async(Dispatchers.Default) { runCatching { source.withReader(files) { reader -> reader.read("a") } }.exceptionOrNull() }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        capture.value = CaptureState.OPENING
        val thrown = withTimeout(5_000) { reading.await() }

        assertTrue("$thrown", thrown is ReaderYieldedException)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals("tokenizer release", calls.last())
        assertEquals(1, purges)
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
    }

    @Test
    fun `a stopped Run says how long the native call took to return after the stop`() = runBlocking {
        // The test's own clock, which only the native fake moves: 37 ms from its cancel to its return.
        fakeNs = 0L
        engine.translateBlocks = true
        engine.returnAfterCancelNs = 37_000_000L
        val reading = async(Dispatchers.Default) { runCatching { source.withReader(files) { reader -> reader.read("a") } }.exceptionOrNull() }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        capture.value = CaptureState.OPENING
        withTimeout(5_000) { reading.await() }

        assertTrue(logged.toString(), "I Reader reader-test Run stopped 37 ms after the cancel" in logged)
    }

    @Test
    fun `a capture that opens as a Run of a line ends gives that Run's line alone, not one for the Run after it`() = runBlocking {
        // 300 pieces: two parts, 253 and 47; the capture opens while the first part's Run ends, which still returns its reading.
        engine.opensCaptureInRun = 1
        val thrown = withTimeout(5_000) { runCatching { source.withReader(files) { reader -> reader.read(List(300) { "a" }.joinToString(" ")) } }.exceptionOrNull() }

        assertTrue("$thrown", thrown is ReaderYieldedException)
        // The second part's Run began after the stop and returned at once: no line says it was stopped.
        assertEquals(calls.toString(), 2, calls.count { it.startsWith("translate") })
        assertEquals(logged.toString(), 1, logged.count { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
        assertEquals(1, purges)
    }

    @Test
    fun `a Run stopped twice counts from the first stop`() = runBlocking {
        // The test's own clock: the capture's stop at 0 ms, the caller's cancel at 20 ms, the Run's return at 37 ms.
        fakeNs = 0L
        engine.translateBlocks = true
        engine.returnAfterCancels = 2
        engine.returnAfterCancelNs = 17_000_000L
        val reading = async(Dispatchers.Default) { source.withReader(files) { reader -> reader.read("a") } }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        capture.value = CaptureState.OPENING
        assertTrue(engine.cancelledOnce.await(5, TimeUnit.SECONDS))
        fakeNs = 20_000_000L
        reading.cancel()
        withTimeout(5_000) { reading.join() }

        assertTrue(logged.toString(), "I Reader reader-test Run stopped 37 ms after the cancel" in logged)
        assertEquals(1, purges)
    }

    @Test
    fun `a capture that opens while the load waits its turn stops it before the native load`() = runBlocking {
        pacing.waits = true
        val loading = async(Dispatchers.Default) { runCatching { source.withReader(files) { error("used") } }.exceptionOrNull() }
        assertTrue(pacing.waiting.await(5, TimeUnit.SECONDS))

        capture.value = CaptureState.LISTENING
        val thrown = withTimeout(5_000) { loading.await() }

        assertTrue("$thrown", thrown is ReaderYieldedException)
        assertFalse(calls.toString(), "load" in calls)
        assertEquals("tokenizer release", calls.last())
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test load stopped [0-9]+ ms after the cancel")) })
        assertFalse(logged.toString(), logged.any { "Run stopped" in it })
    }

    @Test
    fun `a cancel of the caller during a reading stops the Run at once, and the reader is released`() = runBlocking {
        engine.translateBlocks = true
        val reading = async(Dispatchers.Default) { source.withReader(files) { reader -> reader.read("a") } }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) { reading.cancel(); reading.join() }

        assertTrue(reading.isCancelled)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals(1, purges)
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
        val thrown = runCatching { reading.await() }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
    }

    private fun assertRefused(refusal: ReaderRefusal) {
        val refused = assertThrows(ReaderRefusedException::class.java) { runBlocking { source.withReader(files) { error("used") } } }
        assertEquals(refusal, refused.refusal)
    }

    /** The native reader: a reading of one token; a Run that waits for its cancel when told to. */
    private inner class FakeEngine : ReaderEngineCalls {
        @Volatile var cancelled = false
        @Volatile var options: ReaderOptions? = null
        var loadFails: Throwable? = null
        @Volatile var translateBlocks = false
        val translating = CountDownLatch(1)

        // How far a cancelled Run moves the test's clock ([fakeNs]) before it returns, and after how many cancels it does.
        @Volatile var returnAfterCancelNs = 0L
        @Volatile var returnAfterCancels = 1
        private val cancels = java.util.concurrent.atomic.AtomicInteger()
        val cancelledOnce = CountDownLatch(1)

        // The Run (1 for the first) in which a capture opens: it waits for the stop, then returns its reading all the same.
        @Volatile var opensCaptureInRun = 0
        private var runs = 0

        override fun load(graphs: ReaderGraphs, options: ReaderOptions) {
            calls += "load"
            this.options = options
            loadFails?.let { throw it }
            if (cancelled) throw ReaderException.Cancelled("cancelled while loading")
        }

        override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            calls += "translate $beam $maxNewTokens $minTokens"
            if (++runs == opensCaptureInRun) {
                capture.value = CaptureState.OPENING
                untilCancelled(1)
                return Reading(intArrayOf(10), -0.5, capped = false, steps = 2)
            }
            if (translateBlocks) {
                translating.countDown()
                untilCancelled(returnAfterCancels)
            }
            if (cancelled) {
                fakeNs = fakeNs?.plus(returnAfterCancelNs)
                throw ReaderException.Cancelled("cancelled")
            }
            return Reading(intArrayOf(10), -0.5, capped = false, steps = 2)
        }

        // At most 5 s, so a regression fails the test instead of holding the suite.
        private fun untilCancelled(times: Int) {
            val until = System.nanoTime() + 5_000_000_000L
            while (cancels.get() < times && System.nanoTime() < until) Thread.sleep(2)
        }

        override fun dims(): ReaderDims = ReaderDims(18, 8, 64)

        override fun cancel() {
            calls += "cancel"
            cancelled = true
            cancels.incrementAndGet()
            cancelledOnce.countDown()
        }

        override fun release() {
            calls += "release"
        }
    }

    /** The pace: records what it was given; told to, it waits for its turn until it is cancelled. */
    private class FakePacing : SpeechModelLoadPacing {
        var name: String? = null
        var files: List<File>? = null
        var parseBytes: Long? = null
        @Volatile var waits = false
        val waiting = CountDownLatch(1)

        override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T =
            error("the reader's load is never the recorder's")

        override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T {
            this.name = name
            this.files = files
            this.parseBytes = parseBytes
            if (waits) {
                waiting.countDown()
                val until = System.nanoTime() + 5_000_000_000L
                while (!cancelled() && System.nanoTime() < until) Thread.sleep(2)
                if (cancelled()) throw java.util.concurrent.CancellationException("stopped while it waited")
            }
            return load()
        }
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
