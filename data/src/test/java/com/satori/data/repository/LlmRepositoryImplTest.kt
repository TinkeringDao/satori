package com.satori.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.BatteryManager
import com.satori.data.datasource.LlmDataSource
import com.satori.data.monitor.AnswerFloor
import com.satori.data.monitor.FreeMemory
import com.satori.data.monitor.MemoryFloor
import com.satori.data.monitor.MemoryReading
import com.satori.data.monitor.ModelMemoryLines
import com.satori.domain.model.AppSettings
import com.satori.domain.repository.AiSampling
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.Sampling
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The memory margin of a switch, forgetting the stored model, clearing the prompt cache, the
 * memory lines of loads and releases, and how each reply samples and the hash its line gives, on a
 * model loader that only records what it is asked. The model files are sparse files with a GGUF
 * header, as large as the file check wants them.
 */
class LlmRepositoryImplTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val live by lazy { gguf("live.gguf", 120_000_000L) }
    private val large by lazy { gguf("large.gguf", 150_000_000L) }
    private val source = RecordingLoader()

    // What the switch measures as free, once the model in memory is out.
    @Volatile private var available = 0L
    private val repository by lazy { LlmRepositoryImpl(source, NoSettings(), ContextWrapper(null)) { available } }

    @Test
    fun `a switch with a margin is refused when less memory is free than the file and the margin, with the figures`() = runBlocking {
        assertTrue(repository.initialize(live.path, loadImmediately = true).isSuccess)
        available = large.length() + MARGIN - 1

        val cause = repository.switchModel(large.path, MARGIN).exceptionOrNull() as NotEnoughMemoryException

        assertEquals(available, cause.availableBytes)
        assertEquals(large.length() + MARGIN, cause.neededBytes)
        // The large model never reached the loader; the live one went out first, as for any switch.
        assertEquals(listOf(live.path), source.loads)
        assertEquals(1, source.unloads.get())
        assertFalse(repository.isModelInMemory)
        // The live model is still the one stored, so the next step loads it again.
        assertTrue(repository.reloadModel().isSuccess)
        assertEquals(listOf(live.path, live.path), source.loads)
    }

    @Test
    fun `the same memory is enough for a switch without the margin`() = runBlocking {
        available = large.length() + MARGIN - 1
        source.failing = large.path

        val failure = repository.switchModel(large.path).exceptionOrNull()

        // Past the memory check: the loader was asked, and its own failure came back.
        assertEquals(listOf(large.path), source.loads)
        assertEquals(RecordingLoader.LOAD_FAILED, failure?.message)
    }

    @Test
    fun `a switch without enough memory even for the file is refused the same way`() = runBlocking {
        available = large.length() - 1

        val cause = repository.switchModel(large.path).exceptionOrNull() as NotEnoughMemoryException

        assertEquals(large.length(), cause.neededBytes)
        assertTrue(source.loads.isEmpty())
    }

    @Test
    fun `a negative margin is no margin - the file's size must still be free`() = runBlocking {
        available = large.length() - 1

        val short = repository.switchModel(large.path, -300L * MB).exceptionOrNull() as NotEnoughMemoryException

        // Measured as the plain switch: refused one byte short of the file, with the file's size.
        assertEquals(large.length(), short.neededBytes)
        assertTrue(source.loads.isEmpty())
        available = large.length()
        assertTrue(repository.switchModel(large.path, -300L * MB).isSuccess)
        assertEquals(listOf(large.path), source.loads)
    }

    @Test
    fun `a lead stands before the text, outside the quotes of a free-text call, and none leaves the text as it was`() {
        val note = "Instruction, never to be repeated in your answer: read the Malayalam below and write in English."
        val grammar = OutputGrammar.JsonSchema("{}")

        assertEquals("$note\n\nTranscription: \"words\"", repository.buildPrompt("words", grammar = null, lead = note))
        assertEquals("$note\n\nS1 words", repository.buildPrompt("S1 words", grammar, lead = note))
        assertEquals("Transcription: \"words\"", repository.buildPrompt("words", grammar = null, lead = " "))
        assertEquals("S1 words", repository.buildPrompt("S1 words", grammar))
    }

    @Test
    fun `the model in memory is the switched one only while a switch's model is loaded`() = runBlocking {
        assertTrue(repository.initialize(live.path, loadImmediately = true).isSuccess)
        assertFalse(repository.isSwitchedModelInMemory)

        available = large.length() + MARGIN
        assertTrue(repository.switchModel(large.path, MARGIN).isSuccess)
        assertTrue(repository.isSwitchedModelInMemory)

        repository.cleanup()
        assertFalse(repository.isSwitchedModelInMemory)
        // Forgotten, then the live model again: the small one answers.
        repository.forgetModel()
        assertTrue(repository.initialize(live.path, loadImmediately = true).isSuccess)
        assertFalse(repository.isSwitchedModelInMemory)
    }

    @Test
    fun `a forgotten model is not loaded again`() = runBlocking {
        assertTrue(repository.initialize(live.path, loadImmediately = false).isSuccess)

        repository.forgetModel()

        assertTrue(repository.reloadModel().isFailure)
        assertTrue(source.loads.isEmpty())
    }

    @Test
    fun `forgetting during a load keeps the model stored, as that load's caller wants it`() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        source.hold = hold
        val load = async(Dispatchers.Default) { repository.initialize(live.path, loadImmediately = true) }
        source.started.await()

        repository.forgetModel()
        hold.complete(Unit)

        assertTrue(load.await().isSuccess)
        source.hold = null
        repository.cleanup()
        assertTrue(repository.reloadModel().isSuccess)
        assertEquals(listOf(live.path, live.path), source.loads)
    }

    @Test
    fun `the prompt cache is cleared in the background, once the engine's call under way has ended`() = runBlocking {
        val callUnderWay = CompletableDeferred<Unit>()
        source.clearHold = callUnderWay

        // Returns at once, though the engine is busy: a delete doesn't wait for it.
        repository.forgetPromptCache()
        withTimeout(5_000) { source.clearAsked.await() }
        assertFalse(source.cleared.isCompleted)

        callUnderWay.complete(Unit)
        withTimeout(5_000) { source.cleared.await() }
    }

    @Test
    fun `a prompt cache that can't be cleared stays in the repository, where an uncaught failure would end the app`() = runBlocking {
        val uncaught = CompletableDeferred<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }
        try {
            source.clearFailure = IllegalStateException("the cache directory is gone")

            repository.forgetPromptCache()

            withTimeout(5_000) { source.clearAsked.await() }
            assertNull(withTimeoutOrNull(1_000) { uncaught.await() })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun `the checks after a load and after a note read the memory off their caller's thread`() = runBlocking {
        val readOn = CopyOnWriteArrayList<Thread>()
        val checking = LlmRepositoryImpl(source, NoSettings(), ContextWrapper(null), memoryReading = {
            readOn += Thread.currentThread()
            ActivityManager.MemoryInfo().apply {
                availMem = 4_000L * MB
                threshold = 200L * MB
            }
        }) { available }

        checking.checkAndCacheMemoryConstraint()
        checking.recordConstrainedInference(inputChars = 5_000)

        // The recorder and the live notes check from the main thread, where this binder call
        // held up the screen.
        assertEquals(2, readOn.size)
        assertTrue(readOn.none { it === Thread.currentThread() })
    }

    @Test
    fun `each load and release logs its memory line with the model's file, and a failed load says so`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val memory = ModelMemoryLines(
            enabled = true,
            log = { lines += it },
            read = { MemoryReading(memAvailableMb = 4000, rssAnonMb = 100, rssFileMb = 50, psiSomeAvg10 = null, psiFullAvg10 = null) },
            clock = { 0L },
            later = { _, _ -> }
        )
        val measured = LlmRepositoryImpl(source, NoSettings(), ContextWrapper(null), memoryLines = memory) { Long.MAX_VALUE }
        source.failing = large.path

        assertTrue(measured.initialize(live.path, loadImmediately = true).isSuccess)
        // The live model goes out for the switch, and E2B's load then fails.
        assertTrue(measured.switchModel(large.path, MARGIN).isFailure)
        assertTrue(measured.initialize(live.path, loadImmediately = true).isSuccess)
        measured.cleanup()

        assertEquals(
            listOf(
                "load of live.gguf took",
                "release of live.gguf took",
                "load of large.gguf failed after",
                "load of live.gguf took",
                "release of live.gguf took"
            ),
            lines.map { it.removePrefix("Memory: ").substringBefore(" 0 ms") }
        )
    }

    @Test
    fun `a load's memory line ends with how the engine set the model up, and a release's has no settings`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val memory = ModelMemoryLines(
            enabled = true,
            log = { lines += it },
            read = { MemoryReading(memAvailableMb = 4000, rssAnonMb = 100, rssFileMb = 50, psiSomeAvg10 = "0.00", psiFullAvg10 = "0.00") },
            clock = { 0L },
            later = { _, _ -> }
        )
        val measured = LlmRepositoryImpl(source, NoSettings(), ContextWrapper(null), memoryLines = memory) { Long.MAX_VALUE }
        source.settings = "llama.cpp repack=off mmap=on directio=off swa_full=on kv=q8_0 fa=on ctx=4096"

        assertTrue(measured.initialize(live.path, loadImmediately = true).isSuccess)
        measured.cleanup()

        assertTrue(lines[0], lines[0].endsWith("full avg10 0.00 -> 0.00; llama.cpp repack=off mmap=on directio=off swa_full=on kv=q8_0 fa=on ctx=4096"))
        assertTrue(lines[1], lines[1].startsWith("Memory: release of live.gguf") && lines[1].endsWith("full avg10 0.00 -> 0.00"))
    }

    @Test
    fun `every reply samples at the default, but in a coroutine that carries its own sampling and in its children, and at the default again after it`() = runBlocking {
        val rerun = Sampling(temperature = 0f)

        repository.generateInsight("first", "system", 8).toList()
        withContext(AiSampling(rerun)) {
            repository.generateInsight("rerun", "system", 8).toList()
            coroutineScope { launch(Dispatchers.Default) { repository.generateInsight("a child's", "system", 8).toList() } }
        }
        // The rerun's setting doesn't outlive its coroutine: the next call puts the default back.
        repository.generateInsight("after", "system", 8).toList()

        assertEquals(listOf(Sampling.DEFAULT, rerun, rerun, Sampling.DEFAULT), source.samplings)
        // Each is set before its reply is asked for.
        assertEquals(List(4) { listOf("sampling", "prompt") }.flatten(), source.replyCalls)
    }

    @Test
    fun `a reply asked for from a scope of its own, as the finishing queue's, samples at the default inside a rerun's coroutine`() = runBlocking {
        val queue = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withContext(AiSampling(Sampling.GREEDY)) {
                queue.async { repository.generateInsight("the owner's own", "system", 8).toList() }.await()
            }
        } finally {
            queue.cancel()
        }

        assertEquals(listOf(Sampling.DEFAULT), source.samplings)
    }

    @Test
    fun `the prompt's hash is 8 hex digits of what the model reads, and tells calls that read the same from those that don't`() {
        val schema = OutputGrammar.JsonSchema("{\"type\":\"object\"}")
        val hash = repository.promptHash("You write notes.", "S1 [00:00] words", schema)

        // The SHA-256 of the system prompt, the user turn and the grammar with its kind, each in
        // UTF-8 and ended by a zero byte (docs/FIELD_LOG.md), so a reading can be checked by hand.
        assertEquals("6e53c134", hash)
        assertEquals("68fc7b6e", repository.promptHash("You write notes.", "S1 [00:00] words", null))
        assertEquals("709e80c8", repository.promptHash("", "", null))
        assertEquals(hash, repository.promptHash("You write notes.", "S1 [00:00] words", OutputGrammar.JsonSchema("{\"type\":\"object\"}")))
        for (other in listOf(
            repository.promptHash("You write notes!", "S1 [00:00] words", schema),
            repository.promptHash("You write notes.", "S1 [00:00] word", schema),
            repository.promptHash("You write notes.", "S1 [00:00] words", OutputGrammar.Gbnf("{\"type\":\"object\"}")),
            // The same characters, cut elsewhere between the parts.
            repository.promptHash("You write notes.S", "1 [00:00] words", schema)
        )) {
            assertNotEquals(hash, other)
        }
    }

    @Test
    fun `only the first answer after each load waits for memory, before its first native call, and a reload waits again`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        // MemFree under the floor for three polls, then back at the resume level, for each wait.
        var polls = 0
        val floor = AnswerFloor(
            floor = MemoryFloor.fallback(),
            debuggable = false,
            read = { throw AssertionError("a release build read $it") },
            memory = {
                source.calls += "memory read"
                FreeMemory(if (polls++ % 4 == 3) 210L else 120L, 2_000L - polls * 10L)
            },
            log = { lines += it },
            warn = { lines += "W $it" },
            clockNs = { polls * 50_000_000L },
            pause = {}
        )
        val repository = LlmRepositoryImpl(source, NoSettings(), ServicesContext(), answerFloor = floor) { Long.MAX_VALUE }
        assertTrue(repository.initialize(live.path, loadImmediately = true).isSuccess)

        repository.generateInsight("words", systemPrompt = "Notes", maxTokens = 16, grammar = null).toList()
        repository.generateInsight("more words", systemPrompt = "Name", maxTokens = 16, grammar = null).toList()

        assertEquals(listOf("memory read", "memory read", "memory read", "memory read", "system prompt", "prompt", "system prompt", "prompt"), source.calls)
        assertEquals(listOf("First answer on live.gguf waited 150 ms for memory: MemFree 120 MB as it waited, 210 MB as it began (floor 150 MB)"), lines)

        // Out and in again: its first answer waits again.
        repository.cleanup()
        assertTrue(repository.reloadModel().isSuccess)
        source.calls.clear()
        repository.generateInsight("words", systemPrompt = "Notes", maxTokens = 16, grammar = null).toList()
        assertEquals(listOf("memory read", "memory read", "memory read", "memory read", "system prompt", "prompt"), source.calls)
        assertEquals(2, lines.size)
    }

    @Test
    fun `a cancel while the first answer waits for memory ends it with no native call, and the next answer waits instead`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val floor = AnswerFloor(
            floor = MemoryFloor.fallback(),
            debuggable = false,
            read = { throw AssertionError("a release build read $it") },
            memory = { FreeMemory(if (goOn.isCompleted) 210L else 120L, 2_000L) },
            log = {},
            warn = {},
            clockNs = { 0L },
            pause = {
                waiting.complete(Unit)
                kotlinx.coroutines.delay(10)
            }
        )
        val repository = LlmRepositoryImpl(source, NoSettings(), ServicesContext(), answerFloor = floor) { Long.MAX_VALUE }
        assertTrue(repository.initialize(live.path, loadImmediately = true).isSuccess)

        val answer = async(Dispatchers.Default) { repository.generateInsight("words", "Notes", 16, null).toList() }
        waiting.await()
        answer.cancel()
        assertTrue(runCatching { withTimeout(5_000) { answer.await() } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(source.calls.toString(), source.calls.isEmpty())

        goOn.complete(Unit)
        withTimeout(5_000) { repository.generateInsight("words", "Notes", 16, null).toList() }
        assertEquals(listOf("system prompt", "prompt"), source.calls)
    }

    /**
     * A context whose battery and activity services answer with the stubs' defaults, for an answer's
     * own lines (PerfLogger); the stubs' constructors aren't public, so they are made by reflection.
     */
    private class ServicesContext : ContextWrapper(null) {
        private val battery: BatteryManager = made()
        private val activity: ActivityManager = made()

        override fun getSystemService(name: String): Any? = when (name) {
            Context.BATTERY_SERVICE -> battery
            Context.ACTIVITY_SERVICE -> activity
            else -> null
        }

        private inline fun <reified T> made(): T = T::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    /** A sparse file of [bytes] that starts like a GGUF model. */
    private fun gguf(name: String, bytes: Long): File = File(folder.root, name).also { file ->
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("GGUF".toByteArray(Charsets.US_ASCII))
            raf.setLength(bytes)
        }
    }

    /**
     * Records the loads and unloads; [failing] fails to load, and [hold] makes a load wait. A clear
     * of the prompt cache waits for [clearHold], as the engine's does for its call under way, and
     * then fails with [clearFailure] or is [cleared]. Each reply's sampling goes to [samplings], and
     * [replyCalls] has the sampling and the prompt in the order they were asked for.
     */
    private class RecordingLoader : LlmDataSource {
        val loads = CopyOnWriteArrayList<String>()

        // The native calls of the answers, in order (and a test's own entries).
        val calls = CopyOnWriteArrayList<String>()
        val unloads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val clearAsked = CompletableDeferred<Unit>()
        val cleared = CompletableDeferred<Unit>()

        @Volatile var failing: String? = null
        @Volatile var settings: String? = null
        @Volatile var hold: CompletableDeferred<Unit>? = null
        @Volatile var clearHold: CompletableDeferred<Unit>? = null
        @Volatile var clearFailure: Exception? = null

        override suspend fun loadModel(modelPath: String, systemPrompt: String?, nThreadsHint: Int): Result<Unit> {
            loads += modelPath
            started.complete(Unit)
            hold?.await()
            return if (modelPath == failing) Result.failure(IllegalStateException(LOAD_FAILED)) else Result.success(Unit)
        }

        override val loadSettings: String?
            get() = settings

        val samplings = CopyOnWriteArrayList<Sampling>()
        val replyCalls = CopyOnWriteArrayList<String>()

        override suspend fun useSampling(sampling: Sampling) {
            samplings += sampling
            replyCalls += "sampling"
        }

        override fun sendPrompt(prompt: String, maxTokens: Int, grammar: OutputGrammar?): Flow<String> {
            replyCalls += "prompt"
            calls += "prompt"
            return emptyFlow()
        }

        override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> {
            calls += "system prompt"
            return Result.success(Unit)
        }

        override suspend fun cleanup() {
            unloads.incrementAndGet()
        }

        override suspend fun forgetPromptCache() {
            clearAsked.complete(Unit)
            clearHold?.await()
            clearFailure?.let { throw it }
            cleared.complete(Unit)
        }

        companion object {
            const val LOAD_FAILED = "the native load failed"
        }
    }

    /** The default settings, and no writes. */
    private class NoSettings : SettingsRepository {
        override fun getSettings(): Flow<AppSettings> = flowOf(AppSettings())
        override suspend fun updateSettings(settings: AppSettings) = Unit
        override suspend fun setHomeCategoryId(id: String) = Unit
        override suspend fun setMemoryConstrainedDetected(detected: Boolean) = Unit
        override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = Unit
        override suspend fun clearLlmModelPath() = Unit
    }

    private companion object {
        const val MB = 1_048_576L
        const val MARGIN = 600L * 1_048_576L
    }
}
