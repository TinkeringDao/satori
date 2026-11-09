package com.satori.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.MemoryReading
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.PacedLoadStoppedException
import com.satori.data.monitor.Purge
import com.satori.data.monitor.ReleasePurge
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.domain.model.CaptureState
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.LineNotReadException
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.ReaderFailedException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderStoppedException
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
 * The reader as the finishing reads with it (0.8.0, R5), on the real data source with fakes of the
 * native reader, the tokenizer and the pace: lines read at beam 4 and given back for D5, the native
 * heap purged before each load as after each release, and what the data source throws turned into
 * the domain's reasons. Synthetic text only.
 */
class ReaderRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val calls = CopyOnWriteArrayList<String>()
    private val capture = MutableStateFlow(CaptureState.IDLE)
    private val speechModel = MutableStateFlow(false)
    private var aiModel = false
    private var state = ModelFileState.Intact

    // Whether the files were found once with their pinned SHA-256 (the first run's read).
    private var pinned = true
    private lateinit var files: ReaderFiles
    private lateinit var dir: File

    private val engine = FakeEngine()
    private val pacing = FakePacing()
    private val heap = NativeHeap {
        calls += "purge"
        Purge.ALL
    }

    private val natives = object : ReaderNatives {
        override fun engine(): ReaderEngineCalls = engine
        override fun tokenizer(): ReaderTokenizerCalls = object : ReaderTokenizerCalls {
            override fun load(model: File) {
                calls += "tokenizer load"
            }

            override fun pieces(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" }

            override fun release() {
                calls += "tokenizer release"
            }
        }
    }

    private val warnings = CopyOnWriteArrayList<String>()

    private val repository by lazy {
        val source = ReaderDataSource(
            downloads = ModelDownloadManager(ExternalFilesContext(tmp.root)),
            pacing = pacing,
            memoryLines = {
                ModelMemoryLines.forFlags(
                    ApplicationInfo.FLAG_DEBUGGABLE,
                    log = {},
                    read = { MemoryReading(3_000, 120, 80, null, null) },
                    later = { _, _ -> },
                    every = { _, _ -> AutoCloseable {} },
                    rssAnonMb = { 120 }
                )
            },
            releasePurge = ReleasePurge(debuggable = false, read = { "" }, warn = {}, heap = heap),
            captureState = capture,
            speechModelLoaded = speechModel,
            aiModelInMemory = { aiModel },
            natives = natives,
            log = {},
            warn = {}
        )
        ReaderRepositoryImpl(source, heap, files = { files }, verified = { pinned }, warn = { warnings += it })
    }

    @Before
    fun files() {
        dir = tmp.newFolder("reader")
        File(dir, "config.json").writeText(
            "{\"encoder_vocab_size\": 122706, \"max_source_positions\": 256, \"max_target_positions\": 256, " +
                "\"decoder_start_token_id\": 2, \"eos_token_id\": 2, \"pad_token_id\": 1}"
        )
        File(dir, "dict.SRC.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"eng_Latn\": 4, \"mal_Mlym\": 13, \"▁a\": 20, \"▁b\": 21}")
        File(dir, "dict.TGT.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"▁Hello\": 10, \"▁there\": 11}")
        File(dir, "model.SRC").writeBytes(byteArrayOf(0x0A, 0x01))
        files = ReaderFiles("reader-test", dir, PackedBytes.of(ReaderModelId.INT8), dir.listFiles()!!.sorted()) { state }
    }

    @Test
    fun `lines are read at beam 4 and given back for the rules, the native heap purged before the load and after the release`() = runBlocking {
        val output = repository.withReader { reader -> reader.read("a b") }

        assertEquals(ReaderOutput("Hello there", pieces = 2, tokens = 2, meanLogProb = -0.5, capped = false), output)
        // The purge before comes ahead of the pace; the release's own comes after the native frees.
        assertEquals(listOf("purge", "pace", "tokenizer load", "load", "translate 4", "release", "tokenizer release", "purge"), calls)
        assertEquals("indictrans2-indic-en-dist-200m-int8", repository.model)
    }

    @Test
    fun `whether the reader is on the phone is its files' check, and their SHA-256 found the pinned one once`() = runBlocking {
        assertTrue(repository.isOnPhone())
        // Whole, but never found with their pins (design R7): not yet, as the first run reads them first.
        pinned = false
        assertFalse(repository.isOnPhone())
        pinned = true
        state = ModelFileState.Damaged
        assertFalse(repository.isOnPhone())
        state = ModelFileState.Missing
        assertFalse(repository.isOnPhone())
    }

    @Test
    fun `each refusal before the load becomes the domain's reason, and nothing loads`() {
        fun refused(): ReaderNotLoadedException.Why =
            assertThrows(ReaderNotLoadedException::class.java) { runBlocking { repository.withReader { error("used") } } }.why

        capture.value = CaptureState.OPENING
        assertEquals(ReaderNotLoadedException.Why.CAPTURE, refused())
        capture.value = CaptureState.IDLE
        speechModel.value = true
        assertEquals(ReaderNotLoadedException.Why.SPEECH_MODEL, refused())
        speechModel.value = false
        aiModel = true
        assertEquals(ReaderNotLoadedException.Why.AI_MODEL, refused())
        aiModel = false
        for (bad in listOf(ModelFileState.Missing, ModelFileState.Damaged, ModelFileState.Unreadable)) {
            state = bad
            assertEquals(ReaderNotLoadedException.Why.NOT_ON_PHONE, refused())
        }

        assertFalse(calls.toString(), "load" in calls)
    }

    @Test
    fun `a capture that opens while a line is read stops the Run at once, as the domain's stop`() = runBlocking {
        engine.translateBlocks = true
        val reading = async(Dispatchers.Default) { runCatching { repository.withReader { reader -> reader.read("a") } }.exceptionOrNull() }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        // The recorder leaves IDLE mid-reading.
        capture.value = CaptureState.OPENING
        val thrown = withTimeout(5_000) { reading.await() }

        assertTrue("$thrown", thrown is ReaderStoppedException)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals("purge", calls.last())
    }

    @Test
    fun `a line the native reader refuses or fails on alone is not read, and the next line is`() = runBlocking {
        engine.failing = mutableListOf(ReaderException.BadInput("framing"), ReaderException.RunFailed("not finite"))

        val read = repository.withReader { reader ->
            listOf("a", "b", "a b").map { line ->
                try {
                    reader.read(line).text
                } catch (e: LineNotReadException) {
                    "not read"
                }
            }
        }

        assertEquals(listOf("not read", "not read", "Hello there"), read)
    }

    @Test
    fun `a native failure as it loads or reads is the domain's failure, with the reader released`() {
        engine.loadFails = ReaderException.LoadFailed("not a model")
        assertThrows(ReaderFailedException::class.java) { runBlocking { repository.withReader { error("used") } } }
        assertTrue(calls.toString(), calls.contains("tokenizer release"))

        engine.loadFails = null
        engine.failing = mutableListOf(ReaderException.OutOfMemory("no memory"))
        calls.clear()
        val failed = assertThrows(ReaderFailedException::class.java) { runBlocking { repository.withReader { reader -> reader.read("a") } } }

        assertTrue(failed.cause is ReaderException.OutOfMemory)
        assertEquals("purge", calls.last())
    }

    @Test
    fun `the pace's stop for memory stays the domain's own, and a vocabulary that isn't one fails the load`() {
        pacing.stops = true
        val stopped = assertThrows(LoadStoppedForMemoryException::class.java) { runBlocking { repository.withReader { error("used") } } }
        assertTrue(stopped is PacedLoadStoppedException)

        pacing.stops = false
        File(dir, "dict.SRC.json").writeText("<html>")
        assertThrows(ReaderFailedException::class.java) { runBlocking { repository.withReader { error("used") } } }
    }

    @Test
    fun `what the caller's own block throws stays its own, as does a cancel`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.withReader { require(false) { "the caller's" } } } }
        assertThrows(CancellationException::class.java) { runBlocking { repository.withReader { throw CancellationException("the caller's") } } }
        assertEquals("purge", calls.last())
    }

    @Test
    fun `a purge that fails before the load is a warning, and the load goes on`() = runBlocking {
        val failing = ReaderRepositoryImpl(
            ReaderDataSource(
                downloads = ModelDownloadManager(ExternalFilesContext(tmp.root)),
                pacing = pacing,
                memoryLines = { ModelMemoryLines.forFlags(0, log = {}) },
                releasePurge = ReleasePurge.NONE,
                captureState = capture,
                speechModelLoaded = speechModel,
                aiModelInMemory = { false },
                natives = natives,
                log = {},
                warn = {}
            ),
            heap = NativeHeap { throw IllegalStateException("no mallopt") },
            files = { files },
            warn = { warnings += it }
        )

        assertEquals("Hello there", failing.withReader { reader -> reader.read("a b") }.text)
        assertEquals(listOf("Native heap not purged before the load of indictrans2-indic-en-dist-200m-int8: IllegalStateException"), warnings)
    }

    /** The native reader: "Hello there" for any source; told to, a Run waits for its cancel, or fails. */
    private inner class FakeEngine : ReaderEngineCalls {
        @Volatile var cancelled = false
        var loadFails: Throwable? = null
        var failing = mutableListOf<Throwable>()
        @Volatile var translateBlocks = false
        val translating = CountDownLatch(1)

        override fun load(graphs: ReaderGraphs, options: ReaderOptions) {
            calls += "load"
            loadFails?.let { throw it }
        }

        override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            calls += "translate $beam"
            failing.removeFirstOrNull()?.let { throw it }
            if (translateBlocks) {
                translating.countDown()
                val until = System.nanoTime() + 5_000_000_000L
                while (!cancelled && System.nanoTime() < until) Thread.sleep(2)
            }
            if (cancelled) throw ReaderException.Cancelled("cancelled")
            return Reading(intArrayOf(10, 11), -0.5, capped = false, steps = 3)
        }

        override fun dims(): ReaderDims = ReaderDims(18, 8, 64)

        override fun cancel() {
            calls += "cancel"
            cancelled = true
        }

        override fun release() {
            calls += "release"
        }
    }

    /** The pace: records that it ran; told to, it stops for memory before the load. */
    private inner class FakePacing : SpeechModelLoadPacing {
        var stops = false

        override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T =
            error("the reader's load is never the recorder's")

        override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T {
            calls += "pace"
            if (stops) throw PacedLoadStoppedException("The load of $name was stopped: memory")
            return load()
        }
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
