package com.satori.ui.debug

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.di.readerDataSource
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.SttRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * The reader selftest's run of the reader (V1, V2) with the app's wiring of its reader
 * ([readerDataSource], as AppModule provides it) on fake native calls: RssAnon settled before the
 * load and after the release, the load, the reading and the release between the two, the summary's
 * memory part; and a run with a stop of its own reading through a reader of its own on the native
 * calls it watches, its memory figures then exactly one cancelled line.
 */
class ReaderSelftestRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // What happened, in order: the memory's reads, collections and purges, and each native call.
    private val events = CopyOnWriteArrayList<String>()
    private val logged = CopyOnWriteArrayList<String>()
    private val stopReaders = AtomicInteger()
    private lateinit var files: ReaderFiles

    // The peak's first sample, which every load waits for: the peak is sampled from before the load.
    private val sampled = CountDownLatch(1)

    // RssAnon as the settles read it: 455 and 412 MB before the load, 431 and 418 MB after the release.
    private val rssAnon = ArrayDeque(listOf(455L, 412L, 431L, 418L))
    private val memory = SelftestMemory(
        rssAnonMb = { rssAnon.removeFirst().also { events += "read $it" } },
        collect = { events += "collect" },
        purge = { events += "purge"; Purge.ALL }
    )

    // The app's reader's native calls, and the device's, which a stop run watches.
    private val appNatives = FakeNatives("app")
    private val deviceNatives = FakeNatives("device")

    // The app's wiring, as AppModule makes the app's reader.
    private fun wired(natives: ReaderNatives): ReaderDataSource = readerDataSource(
        context = AppContext(tmp.root),
        downloads = ModelDownloadManager(AppContext(tmp.root)),
        pacing = Pacing(),
        sttRepository = NoCapture(),
        llmRepository = NoAiModel(),
        heap = NativeHeap { events += "release purge"; Purge.ALL },
        natives = natives,
        log = { logged += "I $it" },
        warn = { logged += "W $it" }
    )

    private fun selftestRun() = ReaderSelftestRun(
        reader = wired(appNatives),
        stopReader = { natives -> stopReaders.incrementAndGet(); wired(natives) },
        memory = memory,
        natives = deviceNatives,
        rssAnonMb = { sampled.countDown(); 500 },
        clock = { 0L },
        pause = { ms -> events += "pause $ms" },
        log = { logged += "I $it" }
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

    private fun args(vararg extras: Pair<String, String>) =
        DebugReaderArgs.reader(mapOf("dir" to "test", "input" to "lines.txt") + extras) as DebugReaderArgs.Reader

    @Test
    fun `RssAnon is settled before the load and after the release, around the load, the reading and the release`() = runBlocking {
        val args = args()
        val out = File(tmp.root, args.out)

        val ended = withTimeout(5_000) { selftestRun().run("Reader selftest test", args, files, listOf("a", "a"), "lines.txt", out) }

        assertEquals(
            listOf(
                "read 455", "collect", "purge", "read 412",
                "app tokenizer load", "app load", "app translate", "app translate", "app release", "app tokenizer release", "release purge",
                "read 431", "collect", "purge", "read 418",
                "pause 11000"
            ),
            events
        )
        // The two ends after their collection and purge, the end's figure before them in brackets.
        assertEquals(
            SelftestEnded.Ran(
                "done: 2 lines (0 in parts, 0 empty, 0 capped), 2 pieces, 2 tokens, 4 decoder runs, in 0 ms (0 ms a line, 0.0 ms a run, " +
                    "the encoder and first step included); load 0 ms; RssAnon 412 MB before the load, peak 500 MB, 418 MB after the release " +
                    "(431 MB before its collection and purge); prepack=session,arena=on,threads=4, beam 4; output readings-test-prepack-session-arena-on-threads-4-beam4-lines.tsv"
            ),
            ended
        )
        assertTrue(logged.toString(), "I Reader selftest test: RssAnon 455 -> 412 MB, the Java heap collected and the native heap purged (M_PURGE_ALL) before the load" in logged)
        // Without a stop of its own it reads through the app's reader.
        assertEquals(0, stopReaders.get())
        assertTrue(deviceNatives.calls.isEmpty())
        assertEquals(3, out.readLines().count { !it.startsWith("#") })
    }

    @Test
    fun `a stop run reads through a reader of its own on the native calls it watches, and ends with its memory then one cancelled line`() = runBlocking {
        deviceNatives.translateBlocks = true
        val args = args("stopafter" to "40")
        val out = File(tmp.root, args.out)

        val ended = withTimeout(5_000) { selftestRun().run("Reader selftest test", args, files, listOf("a"), "lines.txt", out) }

        val memoryPart = "RssAnon 412 MB before the load, peak 500 MB, 418 MB after the release (431 MB before its collection and purge)"
        val cancelled = "Reader selftest cancelled: stopafter 40, the cancel came inside a Run; the reader is let go"
        assertEquals(SelftestEnded.Stopped(memoryPart, cancelled), ended)
        // Its reader is the app's wiring on the device's native calls, watched; the app's reader is never touched.
        assertEquals(1, stopReaders.get())
        assertEquals(listOf("tokenizer load", "load", "translate", "cancel", "release", "tokenizer release"), deviceNatives.calls)
        assertTrue(appNatives.calls.isEmpty())
        assertTrue(logged.toString(), logged.any { it.matches(Regex("I Reader reader-test Run stopped [0-9]+ ms after the cancel")) })
        assertEquals(listOf("read 455", "collect", "purge", "read 412"), events.take(4))
        assertEquals(listOf("read 431", "collect", "purge", "read 418", "pause 11000"), events.takeLast(5))
        // No readings file and no .partial.
        assertFalse(out.exists())
        assertFalse(File(out.path + ".partial").exists())
        // Its last lines: the memory figures of the run it stopped, then exactly one cancelled line.
        assertEquals(
            listOf(SelftestEnded.Line("Reader selftest test: $memoryPart"), SelftestEnded.Line(cancelled)),
            SelftestEnded.lines("Reader selftest test", DebugExclusive.Run.Done(ended, inForeground = true))
        )
    }

    @Test
    fun `a stop that never came leaves an ordinary run, its file named with the stop`() = runBlocking {
        val args = args("stopload" to "60000")
        val out = File(tmp.root, args.out)

        val ended = withTimeout(5_000) { selftestRun().run("Reader selftest test", args, files, listOf("a"), "lines.txt", out) }

        assertEquals("readings-test-prepack-session-arena-on-threads-4-beam4-lines-stopload60000.tsv", out.name)
        assertTrue("$ended", ended is SelftestEnded.Ran && ended.line.endsWith("; stopload 60000 never came: the reading ended first"))
        assertTrue(out.isFile)
        assertFalse(deviceNatives.calls.contains("cancel"))
    }

    @Test
    fun `each way a selftest ends is one line, a refusal and a failure a W line`() {
        val name = "Reader selftest int8"

        assertEquals(listOf(SelftestEnded.Line("$name refused: the AI is busy", warn = true)), SelftestEnded.lines(name, DebugExclusive.Run.Refused("the AI is busy")))
        assertEquals(
            listOf(SelftestEnded.Line("$name stopped: a recording started; the reader is let go", warn = true)),
            SelftestEnded.lines(name, DebugExclusive.Run.RecorderStarted)
        )
        assertEquals(listOf(SelftestEnded.Line("$name failed: LoadFailed", warn = true)), SelftestEnded.lines(name, DebugExclusive.Run.Failed("failed: LoadFailed")))
        assertEquals(
            listOf(SelftestEnded.Line("$name done: 20 lines; AI service not in the foreground")),
            SelftestEnded.lines(name, DebugExclusive.Run.Done(SelftestEnded.Ran("done: 20 lines"), inForeground = false))
        )
        assertEquals(
            listOf(SelftestEnded.Line("$name refused: no input file x", warn = true)),
            SelftestEnded.lines(name, DebugExclusive.Run.Done(SelftestEnded.Refused("no input file x"), inForeground = true))
        )
    }

    /** Native calls of one reader, named by [owner]: a reading of one token; a Run that runs until its cancel when told to. */
    private inner class FakeNatives(private val owner: String) : ReaderNatives {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var translateBlocks = false
        @Volatile private var cancelled = false

        private fun call(name: String) {
            calls += name
            events += "$owner $name"
        }

        override fun engine(): ReaderEngineCalls = object : ReaderEngineCalls {
            override fun load(graphs: ReaderGraphs, options: ReaderOptions) {
                call("load")
                sampled.await(5, TimeUnit.SECONDS)
                if (cancelled) throw ReaderException.Cancelled("cancelled while loading")
            }

            override fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
                call("translate")
                if (translateBlocks) {
                    // At most 5 s, so a regression fails the test instead of holding the suite.
                    val until = System.nanoTime() + 5_000_000_000L
                    while (!cancelled && System.nanoTime() < until) Thread.sleep(1)
                }
                if (cancelled) throw ReaderException.Cancelled("cancelled")
                return Reading(intArrayOf(10), -0.5, capped = false, steps = 2)
            }

            override fun dims(): ReaderDims = ReaderDims(18, 8, 64)

            override fun cancel() {
                call("cancel")
                cancelled = true
            }

            override fun release() = call("release")
        }

        override fun tokenizer(): ReaderTokenizerCalls = object : ReaderTokenizerCalls {
            override fun load(model: File) = call("tokenizer load")
            override fun pieces(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" }
            override fun release() = call("tokenizer release")
        }
    }

    /** The pace: the load at once. */
    private class Pacing : SpeechModelLoadPacing {
        override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T =
            error("the reader's load is never the recorder's")

        override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T = load()
    }

    /** No capture and no speech model: the reader may load. */
    private class NoCapture : SttRepository {
        override val audioLevel: StateFlow<Float> = MutableStateFlow(0f)
        override val captureState: StateFlow<CaptureState> = MutableStateFlow(CaptureState.IDLE)
        override val liveModelLoaded: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun initialize(): Result<Unit> = unused()
        override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> = unused()
        override fun mayHaveHeardSpeech(): Boolean = unused()
        override suspend fun stopStreaming(): Unit = unused()
        override suspend fun releaseModel(): Unit = unused()
        override suspend fun transcribeAudio(audio: RecordingAudio, model: SpeechModelId, language: String?, onProgress: (Float) -> Unit): List<SpokenLine> =
            unused()
    }

    /** No AI model in memory: the reader may load. */
    private class NoAiModel : LlmRepository {
        override val isModelInMemory: Boolean = false
        override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> = unused()
        override suspend fun switchModel(modelPath: String): Result<Unit> = unused()
        override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> = unused()
        override suspend fun cleanup(): Unit = unused()
        override suspend fun forgetModel(): Unit = unused()
        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = unused()
        override suspend fun reloadModel(): Result<Unit> = unused()
        override fun isMemoryConstrained(): Boolean = unused()
        override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = unused()
        override fun useConservativeThreads(): Unit = unused()
        override suspend fun checkAndCacheMemoryConstraint(): Unit = unused()
        override suspend fun recordConstrainedInference(inputChars: Int): Unit = unused()
        override fun isLargeContext(inputChars: Int): Boolean = unused()
        override val isGenerating: Boolean get() = unused()
        override fun beginInference(): Unit = unused()
        override fun endInference(): Unit = unused()
        override val thermalThrottleFlow: Flow<ThermalThrottle> get() = unused()
    }

    /** A release build's context, as far as the reader's wiring reads one: no memory lines, its external files in [dir]. */
    private class AppContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo()
        override fun getExternalFilesDir(type: String?): File = dir
    }

    private companion object {
        fun unused(): Nothing = error("not used by the reader's wiring")
    }
}
