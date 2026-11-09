package com.satori.ui.debug

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
import com.satori.domain.modelfile.ModelFileState
import com.satori.feature.reader.LoadedReader
import com.satori.feature.reader.PackedBytes
import com.satori.feature.reader.ReaderDataSource
import com.satori.feature.reader.ReaderEngineCalls
import com.satori.feature.reader.ReaderFiles
import com.satori.feature.reader.ReaderNatives
import com.satori.feature.reader.ReaderTokenizerCalls
import com.satori.reader.ReaderDims
import com.satori.reader.ReaderException
import com.satori.reader.ReaderGraphs
import com.satori.reader.ReaderOptions
import com.satori.reader.Reading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reader selftest's own stop (0.8.0-dev2), through the reader's data source on fake native
 * calls: the cancel a recording's start makes, `stopafter` ms into the reading or `stopload` ms
 * after the pre-read; the data source's own stop line, the release and the purge, no readings file;
 * and the selftest's last line saying where the cancel found the reader, as the data source's line
 * has it. A stop due after the reading's end never comes, and the caller's own cancel stays its own.
 */
class ReaderStopsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val calls = CopyOnWriteArrayList<String>()
    private val logged = CopyOnWriteArrayList<String>()
    private val memoryLines = CopyOnWriteArrayList<String>()
    private val purges = AtomicInteger()
    private val engine = FakeEngine()
    private val pacing = FakePacing()
    private lateinit var files: ReaderFiles

    // Milliseconds that never jump, the stop's and the fakes' alike.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }

    // When the reading began, for the stop's timing.
    @Volatile private var readingBegan = 0L

    // How long SentencePiece's model takes to load, which nothing interrupts, and when it was in.
    @Volatile private var tokenizerLoadMs = 0L
    @Volatile private var tokenizerLoadedAt = 0L

    private val natives = object : ReaderNatives {
        override fun engine(): ReaderEngineCalls = engine.also { calls += "engine" }
        override fun tokenizer(): ReaderTokenizerCalls = object : ReaderTokenizerCalls {
            override fun load(model: File) {
                Thread.sleep(tokenizerLoadMs)
                tokenizerLoadedAt = clock()
                calls += "tokenizer load ${model.name}"
            }

            override fun pieces(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" }

            override fun release() {
                calls += "tokenizer release"
            }
        }
    }

    // The reader's data source as the app wires it, on the given native calls.
    private fun source(natives: ReaderNatives) = ReaderDataSource(
        downloads = ModelDownloadManager(ExternalFilesContext(tmp.root)),
        pacing = pacing,
        memoryLines = { sink ->
            ModelMemoryLines.forFlags(
                ApplicationInfo.FLAG_DEBUGGABLE,
                log = { line ->
                    memoryLines += line
                    sink?.invoke(line)
                },
                read = { MemoryReading(3_000, 120, 80, null, null) },
                clock = clock,
                later = { _, _ -> },
                every = { _, _ -> AutoCloseable {} },
                rssAnonMb = { 120 }
            )
        },
        releasePurge = ReleasePurge(debuggable = false, read = { "" }, warn = {}, heap = NativeHeap { purges.incrementAndGet(); Purge.ALL }),
        captureState = MutableStateFlow(CaptureState.IDLE),
        speechModelLoaded = MutableStateFlow(false),
        aiModelInMemory = { false },
        natives = natives,
        log = { logged += "I $it" },
        warn = { logged += "W $it" }
    )

    @Before
    fun files() {
        val dir = tmp.newFolder("reader")
        File(dir, "config.json").writeText(
            "{\"encoder_vocab_size\": 122706, \"max_source_positions\": 256, \"max_target_positions\": 256, " +
                "\"decoder_start_token_id\": 2, \"eos_token_id\": 2, \"pad_token_id\": 1}"
        )
        File(dir, "dict.SRC.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"eng_Latn\": 4, \"mal_Mlym\": 13, \"▁a\": 20}")
        File(dir, "dict.TGT.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"<unk>\": 3, \"▁Hello\": 10}")
        File(dir, "model.SRC").writeBytes(byteArrayOf(0x0A, 0x01))
        files = ReaderFiles("reader-test", dir, PackedBytes(0L, 0L, 0L), dir.listFiles()!!.sorted()) { ModelFileState.Intact }
    }

    // A selftest's reading as runReader makes it: the stop's clock from the reading's start, its end disarming the stop.
    private suspend fun <T> reading(run: ReaderStopRun, read: suspend (LoadedReader) -> T): T =
        source(run.natives).withReader(files) { loaded ->
            readingBegan = clock()
            run.readingBegins()
            try {
                read(loaded)
            } finally {
                run.readingEnds()
            }
        }

    private fun stopRun(kind: ReaderStop.Kind, ms: Int) = ReaderStopRun(ReaderStop(kind, ms), natives, clock)

    @Test
    fun `stopafter cancels the Run under way as a recording's start would, the reader released, no file written`() = runBlocking {
        engine.translateBlocks = true
        val run = stopRun(ReaderStop.Kind.AFTER, 40)
        val out = File(tmp.root, "readings-test-stopafter40.tsv").apply { writeText("an earlier run\n") }

        val ended = withTimeout(5_000) {
            run.run { ReaderSelftestFiles.writing(out) { writer -> reading(run) { loaded -> writer.append("row\n"); loaded.read("a") } } }
        }

        assertEquals(ReaderStopRun.Ended.Stopped("Reader selftest cancelled: stopafter 40, the cancel came inside a Run; the reader is let go"), ended)
        // The data source's own line gives the figure.
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
        // Not before its time from the reading's start, and through the engine's cancel before the release, as every cancel.
        assertTrue("${engine.cancelAt - readingBegan} ms", engine.cancelAt - readingBegan in 40L..3_000L)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals("tokenizer release", calls.last())
        assertEquals(1, purges.get())
        assertTrue(memoryLines.toString(), memoryLines.any { it.startsWith("Memory: release of reader-test took") && "purged (M_PURGE_ALL" in it })
        // No readings file: an earlier one of the name stays, and no .partial.
        assertEquals("an earlier run\n", out.readText())
        assertFalse(File(out.path + ".partial").exists())
    }

    @Test
    fun `stopafter between two Runs stops the reading before the next, and says between Runs`() = runBlocking {
        val run = stopRun(ReaderStop.Kind.AFTER, 40)

        val ended = withTimeout(5_000) {
            run.run {
                reading(run) { loaded ->
                    loaded.read("a")
                    // The reading's own work between two Runs, which no cancel cuts short, past the stop's time.
                    val until = clock() + 200
                    while (clock() < until) Thread.sleep(2)
                    loaded.read("a")
                }
            }
        }

        assertEquals(ReaderStopRun.Ended.Stopped("Reader selftest cancelled: stopafter 40, the cancel came between Runs; the reader is let go"), ended)
        // The next Run never ran, so no Run stopped and no line of one.
        assertEquals(calls.toString(), 1, calls.count { it == "translate" })
        assertFalse(logged.toString(), logged.any { "stopped" in it })
        assertEquals("tokenizer release", calls.last())
        assertEquals(1, purges.get())
    }

    @Test
    fun `stopload cancels the sessions' creation once the pre-read is over, and says inside the load`() = runBlocking {
        // A pre-read of 150 ms, then a load of the sessions that runs until its cancel.
        pacing.preReadMs = 150
        engine.loadBlocks = true
        val run = stopRun(ReaderStop.Kind.LOAD, 40)

        val ended = withTimeout(5_000) { run.run { reading(run) { error("the load was stopped") } } }

        assertEquals(ReaderStopRun.Ended.Stopped("Reader selftest cancelled: stopload 40, the cancel came inside the load; the reader is let go"), ended)
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test load stopped [0-9]+ ms after the cancel")) })
        assertFalse(logged.toString(), logged.any { "loaded in" in it || "Run stopped" in it })
        // Its clock started with the sessions' creation, after the pre-read, which ran to its end.
        assertTrue(pacing.preReadDone)
        assertTrue(calls.toString(), calls.indexOf("tokenizer load model.SRC") in 0 until calls.indexOf("load"))
        assertTrue("${engine.cancelAt - pacing.preReadDoneAt} ms", engine.cancelAt - pacing.preReadDoneAt in 40L..3_000L)
        assertTrue(engine.cancelAt >= engine.loadAt)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals("tokenizer release", calls.last())
        assertEquals(1, purges.get())
    }

    @Test
    fun `stopload's clock starts with the sessions, not with SentencePiece's model or the vocabularies before them`() = runBlocking {
        // SentencePiece's model takes 300 ms, which no cancel cuts short; the sessions run until their cancel.
        tokenizerLoadMs = 300
        engine.loadBlocks = true
        val run = stopRun(ReaderStop.Kind.LOAD, 40)

        val ended = withTimeout(5_000) { run.run { reading(run) { error("the load was stopped") } } }

        // The cancel came 40 ms after all that, inside the sessions' creation, so the load stopped line times ONNX Runtime's flag.
        assertEquals(ReaderStopRun.Ended.Stopped("Reader selftest cancelled: stopload 40, the cancel came inside the load; the reader is let go"), ended)
        assertTrue(calls.toString(), calls.indexOf("tokenizer load model.SRC") in 0 until calls.indexOf("load"))
        assertTrue("${engine.cancelAt - tokenizerLoadedAt} ms", engine.cancelAt - tokenizerLoadedAt in 40L..3_000L)
        assertTrue(engine.cancelAt >= engine.loadAt)
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test load stopped [0-9]+ ms after the cancel")) })
        assertEquals(1, purges.get())
    }

    @Test
    fun `stopload due after the load cancels the Run under way, and says after the load`() = runBlocking {
        engine.translateBlocks = true
        val run = stopRun(ReaderStop.Kind.LOAD, 40)

        val ended = withTimeout(5_000) { run.run { reading(run) { loaded -> loaded.read("a") } } }

        assertEquals(ReaderStopRun.Ended.Stopped("Reader selftest cancelled: stopload 40, the cancel came after the load; the reader is let go"), ended)
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
        assertEquals(1, purges.get())
    }

    @Test
    fun `a stop due after the reading's end never comes, and the run is what it read`() = runBlocking {
        val run = stopRun(ReaderStop.Kind.AFTER, 60_000)

        val ended = withTimeout(5_000) { run.run { reading(run) { loaded -> loaded.read("a").text } } }

        assertEquals(ReaderStopRun.Ended.Read("Hello"), ended)
        assertFalse(calls.toString(), "cancel" in calls)
        assertEquals(1, purges.get())
    }

    @Test
    fun `a stop due while the reader is released after its reading is no stop, and the run is what it read`() = runBlocking {
        // The reading ends at once; the release after it takes 300 ms, past the stop's time.
        engine.releaseMs = 300
        val run = stopRun(ReaderStop.Kind.AFTER, 100)

        val ended = withTimeout(5_000) { run.run { reading(run) { loaded -> loaded.read("a").text } } }

        assertEquals(ReaderStopRun.Ended.Read("Hello"), ended)
        assertFalse(calls.toString(), "cancel" in calls)
        assertEquals(1, purges.get())
    }

    @Test
    fun `a caller cancelled while its stop's cancel is still under way gets its own cancel, not the stop`() = runBlocking {
        // The stop cancels the Run; the release after it takes 300 ms, and the caller is cancelled meanwhile.
        engine.translateBlocks = true
        engine.releaseMs = 300
        val run = stopRun(ReaderStop.Kind.AFTER, 40)
        val caller = async(Dispatchers.Default) { run.run { reading(run) { loaded -> loaded.read("a") } } }
        assertTrue(engine.releasing.await(5, TimeUnit.SECONDS))

        caller.cancel()
        val thrown = withTimeout(5_000) { runCatching { caller.await() }.exceptionOrNull() }

        assertTrue("$thrown", thrown is CancellationException)
        assertTrue(caller.isCancelled)
        assertEquals(1, purges.get())
    }

    @Test
    fun `a cancel of the caller, Back or a recording that starts, stays the caller's and is no stop`() = runBlocking {
        engine.translateBlocks = true
        val run = stopRun(ReaderStop.Kind.AFTER, 60_000)
        val caller = async(Dispatchers.Default) { run.run { reading(run) { loaded -> loaded.read("a") } } }
        assertTrue(engine.translating.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) { caller.cancelAndJoin() }

        assertTrue(caller.isCancelled)
        assertTrue(calls.toString(), calls.indexOf("cancel") in 0 until calls.indexOf("release"))
        assertEquals(1, purges.get())
    }

    @Test
    fun `only the stop's own cancel is a stop, not one the reading throws by itself`() = runBlocking {
        val run = stopRun(ReaderStop.Kind.AFTER, 60_000)

        val thrown = runCatching { run.run { throw CancellationException("not the stop") } }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
    }

    @Test
    fun `what the reading throws is thrown as it is`() = runBlocking {
        val run = stopRun(ReaderStop.Kind.AFTER, 60_000)

        val thrown = runCatching { run.run { reading(run) { throw ReaderSelftest.TooFewPieces(3, 253) } } }.exceptionOrNull()

        assertTrue("$thrown", thrown is ReaderSelftest.TooFewPieces)
        assertEquals(1, purges.get())
    }

    @Test
    fun `the last line names the stop and where it found the reader`() {
        val after = ReaderStop(ReaderStop.Kind.AFTER, 2_000)
        val load = ReaderStop(ReaderStop.Kind.LOAD, 300)

        assertEquals("stopafter 2000", after.label)
        assertEquals("Reader selftest cancelled: stopafter 2000, the cancel came inside a Run; the reader is let go", after.line(ReaderStop.Where.IN_RUN))
        assertEquals("Reader selftest cancelled: stopafter 2000, the cancel came between Runs; the reader is let go", after.line(ReaderStop.Where.BETWEEN_RUNS))
        assertEquals("Reader selftest cancelled: stopload 300, the cancel came inside the load; the reader is let go", load.line(ReaderStop.Where.IN_LOAD))
        assertEquals("Reader selftest cancelled: stopload 300, the cancel came after the load; the reader is let go", load.line(ReaderStop.Where.IN_RUN))
        assertEquals("Reader selftest cancelled: stopload 300, the cancel came after the load; the reader is let go", load.line(ReaderStop.Where.BETWEEN_RUNS))
    }

    /** The native reader: a reading of one token; a load or a Run that runs until its cancel when told to. */
    private inner class FakeEngine : ReaderEngineCalls {
        @Volatile var cancelled = false
        @Volatile var loadBlocks = false
        @Volatile var translateBlocks = false
        @Volatile var cancelAt = 0L
        @Volatile var loadAt = 0L
        @Volatile var releaseMs = 0L
        val translating = CountDownLatch(1)
        val releasing = CountDownLatch(1)

        override fun load(graphs: ReaderGraphs, options: ReaderOptions) {
            loadAt = clock()
            calls += "load"
            if (loadBlocks) untilCancelled()
            if (cancelled) throw ReaderException.Cancelled("cancelled while loading")
        }

        override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            calls += "translate"
            if (translateBlocks) {
                translating.countDown()
                untilCancelled()
            }
            if (cancelled) throw ReaderException.Cancelled("cancelled")
            return Reading(intArrayOf(10), -0.5, capped = false, steps = 2)
        }

        override fun dims(): ReaderDims = ReaderDims(18, 8, 64)

        override fun cancel() {
            if (!cancelled) cancelAt = clock()
            calls += "cancel"
            cancelled = true
        }

        override fun release() {
            releasing.countDown()
            Thread.sleep(releaseMs)
            calls += "release"
        }

        // At most 5 s, so a regression fails the test instead of holding the suite.
        private fun untilCancelled() {
            val until = System.nanoTime() + 5_000_000_000L
            while (!cancelled && System.nanoTime() < until) Thread.sleep(1)
        }
    }

    /** The pace: a pre-read of [preReadMs] that its cancel stops, then the load. */
    private inner class FakePacing : SpeechModelLoadPacing {
        @Volatile var preReadMs = 0L
        @Volatile var preReadDone = false
        @Volatile var preReadDoneAt = 0L

        override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T =
            error("the reader's load is never the recorder's")

        override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T {
            val until = clock() + preReadMs
            while (clock() < until) {
                if (cancelled()) throw java.util.concurrent.CancellationException("stopped while it read")
                Thread.sleep(2)
            }
            preReadDone = true
            preReadDoneAt = clock()
            return load()
        }
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
