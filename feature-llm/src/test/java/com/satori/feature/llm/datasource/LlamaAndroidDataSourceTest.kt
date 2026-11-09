package com.satori.feature.llm.datasource

import com.arm.aichat.InferenceEngine
import com.satori.data.monitor.AiModelLoadPacing
import com.satori.data.monitor.PacedLoadStoppedException
import com.satori.data.monitor.PacedAiLoad
import com.satori.data.monitor.WeightCopy
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.Sampling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class LlamaAndroidDataSourceTest {

    private class Call(val message: String, val predictLength: Int, val grammar: InferenceEngine.Grammar?)

    /** Records what reaches the engine; the native side isn't loaded on the JVM. */
    private class FakeEngine : InferenceEngine {
        val calls = mutableListOf<Call>()
        var promptCacheClears = 0
        override var loadSettings: String? = null

        override val state: MutableStateFlow<InferenceEngine.State> =
            MutableStateFlow(InferenceEngine.State.ModelReady)

        val loads = mutableListOf<String>()

        override suspend fun loadModel(pathToModel: String, nThreadsHint: Int) {
            loads += "load $pathToModel"
        }

        // The copy's pace the engine chose for the model, at this rate; null for none.
        var copyPace: Int? = 120

        // The pre-read's pace the engine chose for the model.
        var preRead = 120

        // What a load without MAP_POPULATE reads in place; null for the whole file.
        var readIn: List<LongRange>? = null

        // What the pacer answered before each tensor.
        val goOn = mutableListOf<Boolean>()

        // Fails the load as the engine does once the pacer stops it, or as a native error does.
        var stopsWhenPaced = false
        var failure: Exception? = null

        override suspend fun loadModel(pathToModel: String, nThreadsHint: Int, hooks: InferenceEngine.LoadHooks) {
            loads += "settings and cold drop for $pathToModel"
            hooks.aroundNativeLoad(readIn, preRead) {
                val pacer = copyPace?.let(hooks::weightPacer)
                loads += "llama.cpp loads $pathToModel"
                pacer?.beforeTensor(0)?.let { goOn += it }
                pacer?.beforeTensor(1_048_576)?.let { goOn += it }
                pacer?.ended(loaded = true)
                failure?.let { throw it }
                if (stopsWhenPaced && false in goOn) {
                    throw IOException("The weight copy stopped the load: the phone stayed short of memory for 10 s")
                }
            }
        }
        override suspend fun setSystemPrompt(systemPrompt: String) = Unit

        // The temperature, top-k, top-p, min-p and repeat penalty of each setSamplerConfig call,
        // which throws [samplerFailure] when one is set, as the engine's own check does.
        val samplers = mutableListOf<List<Number>>()
        var samplerFailure: Exception? = null
        override suspend fun setSamplerConfig(
            temperature: Float,
            topK: Int,
            topP: Float,
            minP: Float,
            repeatPenalty: Float
        ) {
            samplerFailure?.let { throw it }
            samplers += listOf(temperature, topK, topP, minP, repeatPenalty)
        }

        override fun sendUserPrompt(
            message: String,
            predictLength: Int,
            grammar: InferenceEngine.Grammar?
        ): Flow<String> {
            calls += Call(message, predictLength, grammar)
            return flowOf("{\"todos\":", "[]}")
        }

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int) = ""

        override suspend fun forgetPromptCache() {
            promptCacheClears++
        }
        override fun cleanUp() = Unit
        override fun destroy() = Unit
    }

    private val engine = FakeEngine()
    private val dataSource = LlamaAndroidDataSource(engine)

    @Test
    fun `a sampling goes to the engine as it is, with the repeat penalty off`() = runBlocking {
        dataSource.useSampling(Sampling.DEFAULT)
        dataSource.useSampling(Sampling(temperature = 0f, topK = 20, topP = 0.9f, minP = 0f))

        assertEquals(listOf(listOf<Number>(0.3f, 40, 0.95f, 0.05f, 1.0f), listOf<Number>(0f, 20, 0.9f, 0f, 1.0f)), engine.samplers)
    }

    @Test
    fun `the app's default sampling is what ai_chat_cpp sets at every load, so the app's own calls change nothing there`() = runBlocking {
        val source = File("../lib-llama-android/src/main/cpp/ai_chat.cpp").readText()
        val temperature = Regex("""DEFAULT_SAMPLER_TEMP\s*=\s*([0-9.]+)f""").find(source)?.groupValues?.get(1)
            ?: error("no DEFAULT_SAMPLER_TEMP in ai_chat.cpp")
        val defaults = Regex(
            """sampler_params\(\s*float temp,\s*int top_k\s*=\s*(\d+),\s*float top_p\s*=\s*([0-9.]+)f,\s*""" +
                """float min_p\s*=\s*([0-9.]+)f,\s*float repeat_penalty\s*=\s*([0-9.]+)f"""
        ).find(source)?.groupValues ?: error("no defaults of sampler_params in ai_chat.cpp")
        assertTrue(source.contains("g_sampler_params = sampler_params(DEFAULT_SAMPLER_TEMP);"))

        dataSource.useSampling(Sampling.DEFAULT)

        assertEquals(
            listOf(listOf<Number>(temperature.toFloat(), defaults[1].toInt(), defaults[2].toFloat(), defaults[3].toFloat(), defaults[4].toFloat())),
            engine.samplers
        )
    }

    @Test
    fun `without a model in memory no sampling is set, and a model gone meanwhile leaves the reply's own failure to come`() = runBlocking {
        val warnings = mutableListOf<String>()
        val source = LlamaAndroidDataSource(engine, warn = { warnings += it })
        engine.state.value = InferenceEngine.State.Initialized
        source.useSampling(Sampling.GREEDY)
        assertTrue(engine.samplers.isEmpty())
        assertTrue(warnings.isEmpty())

        // Unloaded between the look and the engine's check: nothing is set, and nothing fails here.
        engine.state.value = InferenceEngine.State.ModelReady
        engine.samplerFailure = IllegalStateException("Cannot update sampler config in Initialized!")
        source.useSampling(Sampling.GREEDY)
        assertTrue(engine.samplers.isEmpty())
        assertEquals(listOf("Sampling not set, no model in memory: Cannot update sampler config in Initialized!"), warnings)

        // A cancel is never taken for one.
        engine.samplerFailure = CancellationException("the caller went away")
        assertTrue(runCatching { source.useSampling(Sampling.GREEDY) }.exceptionOrNull() is CancellationException)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `without the app's pacing the engine loads on its own`() = runBlocking {
        dataSource.loadModel("/models/gemma-3-1b-it-Q8_0.gguf")

        assertEquals(listOf("load /models/gemma-3-1b-it-Q8_0.gguf"), engine.loads)
    }

    /** The app's pacing as the data source uses it: one paced load per model load, its calls recorded. */
    private class RecordedPacing(private val answer: (Long) -> Boolean = { true }) : AiModelLoadPacing {
        val events = mutableListOf<String>()
        val readIns = mutableListOf<List<LongRange>?>()
        private var loads = 0

        override fun aiLoad(file: File): PacedAiLoad {
            val load = "load ${++loads}"
            events += "$load of ${file.name}"
            return object : PacedAiLoad {
                override suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) {
                    readIns += readIn
                    events += "$load: gate and pre-read at $preReadMbPerSecond MB/s"
                    nativeLoad()
                    events += "$load: gate back"
                }

                override fun weightCopy(mbPerSecond: Int): WeightCopy = object : WeightCopy {
                    override fun beforeTensor(doneBytes: Long): Boolean {
                        events += "$load: copy at $mbPerSecond MB/s, $doneBytes"
                        return answer(doneBytes)
                    }

                    override fun ended() {
                        events += "$load: copy ended"
                    }
                }
            }
        }
    }

    @Test
    fun `with the app's pacing llama_cpp's part of the load runs inside it, at the pre-read pace the engine chose`() = runBlocking {
        val pacing = RecordedPacing()
        engine.copyPace = null
        engine.preRead = 60

        LlamaAndroidDataSource(engine, pacing).loadModel("/models/gemma-4-E2B_q4_0-it.gguf")

        assertEquals(
            listOf(
                "settings and cold drop for /models/gemma-4-E2B_q4_0-it.gguf",
                "llama.cpp loads /models/gemma-4-E2B_q4_0-it.gguf"
            ),
            engine.loads
        )
        assertEquals(
            listOf("load 1 of gemma-4-E2B_q4_0-it.gguf", "load 1: gate and pre-read at 60 MB/s", "load 1: gate back"),
            pacing.events
        )
        assertEquals(listOf<List<LongRange>?>(null), pacing.readIns)
    }

    @Test
    fun `each load's pre-read and weight copy are one paced load's, tensor by tensor, and its end`() = runBlocking {
        // The second tensor finds the phone too short of memory.
        val pacing = RecordedPacing(answer = { doneBytes -> doneBytes == 0L })
        val source = LlamaAndroidDataSource(engine, pacing)

        source.loadModel("/models/gemma-3-1b-it-Q8_0.gguf")
        source.loadModel("/models/gemma-4-E2B_q4_0-it.gguf")

        assertEquals(
            listOf(
                "load 1 of gemma-3-1b-it-Q8_0.gguf",
                "load 1: gate and pre-read at 120 MB/s",
                "load 1: copy at 120 MB/s, 0",
                "load 1: copy at 120 MB/s, 1048576",
                "load 1: copy ended",
                "load 1: gate back",
                "load 2 of gemma-4-E2B_q4_0-it.gguf",
                "load 2: gate and pre-read at 120 MB/s",
                "load 2: copy at 120 MB/s, 0",
                "load 2: copy at 120 MB/s, 1048576",
                "load 2: copy ended",
                "load 2: gate back"
            ),
            pacing.events
        )
        // The pace's answer reaches llama.cpp: go on, then stop the load.
        assertEquals(listOf(true, false, true, false), engine.goOn)
    }

    @Test
    fun `a load the weight copy's pace stopped fails as a load stopped for want of memory`() = runBlocking {
        engine.stopsWhenPaced = true

        val stopped = LlamaAndroidDataSource(engine, RecordedPacing(answer = { false })).loadModel("/models/gemma-3-1b-it-Q8_0.gguf")

        val cause = stopped.exceptionOrNull()
        assertTrue(cause.toString(), cause is PacedLoadStoppedException && cause is LoadStoppedForMemoryException)
        assertEquals("The weight copy stopped the load: the phone stayed short of memory for 10 s", cause?.message)
    }

    @Test
    fun `a load that fails for another reason is no load stopped for memory`() = runBlocking {
        engine.failure = IOException("Failed to prepare resources")

        val failed = LlamaAndroidDataSource(engine, RecordedPacing()).loadModel("/models/gemma-3-1b-it-Q8_0.gguf")

        assertFalse(failed.exceptionOrNull().toString(), failed.exceptionOrNull() is LoadStoppedForMemoryException)
        assertEquals("Failed to prepare resources", failed.exceptionOrNull()?.message)
    }

    @Test
    fun `a load without MAP_POPULATE hands the app's pacing the ranges it reads in place`() = runBlocking {
        val pacing = RecordedPacing()
        engine.readIn = listOf(6_144L until 27_531_264L, 1_954_291_712L until 2_284_593_152L)

        LlamaAndroidDataSource(engine, pacing).loadModel("/models/gemma-4-E2B_q4_0-it.gguf")

        assertEquals(listOf(engine.readIn), pacing.readIns)
    }

    @Test
    fun `the engine's load settings are the source's, for the load's memory line`() {
        assertNull(dataSource.loadSettings)

        engine.loadSettings = "llama.cpp repack=off mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096"

        assertEquals(engine.loadSettings, dataSource.loadSettings)
    }

    @Test
    fun `no grammar reaches the engine as free text`() = runBlocking {
        dataSource.sendPrompt("prompt", maxTokens = 256).toList()

        assertEquals(1, engine.calls.size)
        assertEquals("prompt", engine.calls[0].message)
        assertEquals(256, engine.calls[0].predictLength)
        assertNull(engine.calls[0].grammar)
    }

    @Test
    fun `a GBNF grammar reaches the engine unchanged`() = runBlocking {
        val gbnf = "root ::= \"yes\" | \"no\""

        dataSource.sendPrompt("prompt", 16, OutputGrammar.Gbnf(gbnf)).toList()

        assertEquals(InferenceEngine.Grammar.Gbnf(gbnf), engine.calls.single().grammar)
    }

    @Test
    fun `a JSON schema reaches the engine unchanged`() = runBlocking {
        val schema = """{"type":"object","properties":{"when":{"enum":["none","T1"]}},"required":["when"]}"""

        dataSource.sendPrompt("prompt", 512, OutputGrammar.JsonSchema(schema)).toList()

        assertEquals(InferenceEngine.Grammar.JsonSchema(schema), engine.calls.single().grammar)
    }

    // The per-reply lifetime of a grammar in the native sampler is covered by the host test in
    // lib-llama-android/src/test/cpp (grammar_reply).
    @Test
    fun `the data source keeps no grammar between calls`() = runBlocking {
        dataSource.sendPrompt("structured", 512, OutputGrammar.JsonSchema("{}")).toList()
        dataSource.sendPrompt("free", 512).toList()

        assertEquals(InferenceEngine.Grammar.JsonSchema("{}"), engine.calls[0].grammar)
        assertNull(engine.calls[1].grammar)
    }

    @Test
    fun `clearing the prompt cache reaches the engine`() = runBlocking {
        dataSource.forgetPromptCache()

        assertEquals(1, engine.promptCacheClears)
    }

    @Test
    fun `tokens pass through unchanged`() = runBlocking {
        val tokens = dataSource.sendPrompt("prompt", 512, OutputGrammar.JsonSchema("{}")).toList()

        assertEquals(listOf("{\"todos\":", "[]}"), tokens)
    }

    @Test
    fun `engine grammars log their size, not their text`() {
        val secret = "root ::= \"Call Anna about the flat\""

        val gbnf = OutputGrammar.Gbnf(secret).toEngineGrammar().toString()
        val schema = OutputGrammar.JsonSchema(secret).toEngineGrammar().toString()

        assertFalse(gbnf.contains("Anna"))
        assertFalse(schema.contains("Anna"))
        assertEquals("Gbnf(${secret.length} chars)", gbnf)
        assertEquals("JsonSchema(${secret.length} chars)", schema)
    }
}
