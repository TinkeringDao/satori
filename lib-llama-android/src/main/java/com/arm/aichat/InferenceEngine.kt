package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @param nThreadsHint Number of inference threads to use for this load. Pass -1 to let
     *   the engine choose automatically based on CPU core count. Use a lower value (e.g. 2)
     *   on RAM-constrained devices running long sessions to reduce CPU saturation.
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String, nThreadsHint: Int = -1)

    /**
     * [loadModel], with the app taking part in the load through [hooks]: the app takes its one-load
     * gate and reads the model's file in at the load's pre-read pace around llama.cpp's own part
     * ([LoadHooks.aroundNativeLoad]), and paces llama.cpp's copy of the weights ([LoadHooks.weightPacer]).
     * The default ignores them.
     */
    suspend fun loadModel(pathToModel: String, nThreadsHint: Int, hooks: LoadHooks) = loadModel(pathToModel, nThreadsHint)

    /**
     * Drops the pages of the file at [path] from the page cache, those no other mapping holds
     * (MADV_PAGEOUT on its cached pages, then posix_fadvise DONTNEED, which alone drops nothing of a
     * file under Android/data), so the next read of it comes from flash: a debug build's pre-read
     * probe starts cold with it. False when the drop failed; the default drops none.
     */
    suspend fun dropCachedPages(path: String): Boolean = false

    /**
     * How the last [loadModel] set llama.cpp up, as its memory line gives it (`llama.cpp repack=on
     * mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped
     * copypace=120`), also for a load that failed; null before the first. Each model has its own
     * settings; a debug build's memtest can change them from adb ([withDebugLoadSettings]), a
     * release build never does. The default has none.
     */
    val loadSettings: String?
        get() = null

    /**
     * Runs [block], and lets the model loads it makes take their settings from the debug
     * properties (`debug.satori.llm.*` and `debug.satori.load.pace`, a debug build only) over their
     * model's own, an empty property keeping the model's: the debug memtest's load, which
     * `memtest.sh` sets a variant up for. Every other load, and every load of a release build, has
     * its model's settings, so a property left set on the phone (a memtest.sh that lost the phone
     * before it could reset it) changes no load of the app's own work. The default runs [block]
     * as it is.
     */
    suspend fun <T> withDebugLoadSettings(block: suspend () -> T): T = block()

    /**
     * A debug build's whole-recording memory run: for [forMs] from now every model load, the app's
     * own work too, takes its settings from the debug properties over its model's, as the
     * memtest's own load does ([withDebugLoadSettings]); 0 ends it. The window is held in memory
     * only, so it ends by itself, or with the process, and a property left set afterwards changes no
     * load. A release build ignores it, and so does the default.
     */
    fun armDebugLoadSettings(forMs: Long) {}

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Deletes the system prompts' states that the engine keeps on disk between model loads. The
     * app's system prompts hold nothing of a recording (its topic or name goes in the user
     * prompt), and a state's file doesn't say which prompt it holds, so the app calls this when a
     * recording is deleted, as a second safeguard. Each prompt costs one encode again afterwards.
     * Waits for the engine's work under way, so a state being saved goes too: it runs between two
     * calls of the engine, and the state in memory stays.
     */
    suspend fun forgetPromptCache() {}

    /**
     * Updates the sampler configuration without reloading the model.
     * Takes effect on the next token generation.
     *
     * @param temperature Randomness (0.0–2.0)
     * @param topK Top-K candidates (1–100; 0 = disabled)
     * @param topP Nucleus sampling threshold (0.0–1.0)
     * @param minP Min probability relative to top token (0.0–1.0)
     * @param repeatPenalty Repetition penalty (1.0 = disabled; >1.0 discourages repeats). Not
     *   applied to a reply with a [Grammar].
     */
    suspend fun setSamplerConfig(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    )

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     *
     * @param grammar Constrains this reply only; null = free text. A grammar llama.cpp rejects is
     *   logged without its text and the reply is free text.
     */
    fun sendUserPrompt(
        message: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
        grammar: Grammar? = null
    ): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /** What the app does around a model load ([loadModel] with hooks). */
    interface LoadHooks {
        /**
         * Runs [nativeLoad], llama.cpp reading the model and making its context, once the load's
         * settings are chosen and a cold load (a debug build's `debug.satori.llm.cold`) has dropped
         * the file's cached pages, so what is read in here first stays cached for llama.cpp. The
         * engine's state is LoadingModel throughout. The default runs it as it is.
         *
         * @param readIn What of the file the load reads from its mapping: null for all of it, as
         *   MAP_POPULATE maps it in whole; for a load without MAP_POPULATE (a debug build's
         *   `debug.satori.llm.populate=off`) the byte ranges (first..last) of the tensors it
         *   reads in place, which inference reads in full, without those it copies and the
         *   embeddings it reads a few rows of
         * @param preReadMbPerSecond The pace at which to read [readIn] of the file in before
         *   [nativeLoad]: the model's own (120 MB/s), or a debug build's memtest's
         *   (`debug.satori.load.pace`); 0 reads nothing in first
         */
        suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) = nativeLoad()

        /**
         * The pacer of llama.cpp's weight copy for this load, at [mbPerSecond] (the model's own
         * copy pace, or a debug build's memtest's `debug.satori.llm.copypace`), or null to leave
         * it unpaced, the default. Asked for only when the load's copy is paced.
         */
        fun weightPacer(mbPerSecond: Int): WeightPacer? = null
    }

    /**
     * Paces llama.cpp's copy of a model's weights into its repacked buffers, from the loader's
     * progress, which comes before each tensor (llama_model_params.progress_callback). Called on the
     * loading thread, inside the native load: it may sleep, and nothing it throws stops the load (the
     * copy goes on unpaced); returning false does.
     */
    interface WeightPacer {
        /**
         * Before llama.cpp takes on its next tensor: [doneBytes] of the model's weights are behind
         * it, copied or left in the mapping, as the loader counts them (a duplicated tensor, such as
         * a tied output, twice). Returns once the pace lets the next tensor go: true to go on,
         * false to stop the load, which then loads nothing and fails.
         */
        fun beforeTensor(doneBytes: Long): Boolean

        /** The native load has ended, [loaded] or not. */
        fun ended(loaded: Boolean)
    }

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    /**
     * What the model may write in one reply, applied by llama.cpp while sampling. The prompt
     * still has to describe the format: the grammar isn't shown to the model.
     *
     * The reply is sampled with the temperature, top-k, top-p and min-p of [setSamplerConfig],
     * but without its repeat penalty: a structured reply repeats its keys and values by design,
     * and a penalty would push a value already written (a second todo's "task") below another
     * value the grammar also allows, a wrong answer that still parses.
     *
     * toString() gives the size only, since a grammar can quote user content.
     */
    sealed interface Grammar {
        /** A GBNF grammar (llama.cpp's grammar format) with a `root` rule. */
        data class Gbnf(val text: String) : Grammar {
            override fun toString() = "Gbnf(${text.length} chars)"
        }

        /** A JSON schema, converted to GBNF by llama.cpp's json_schema_to_grammar (supported subset only). */
        data class JsonSchema(val schema: String) : Grammar {
            override fun toString() = "JsonSchema(${schema.length} chars)"
        }
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
