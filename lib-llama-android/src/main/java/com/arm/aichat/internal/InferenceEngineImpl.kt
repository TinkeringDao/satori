package com.arm.aichat.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.isModelLoaded
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InferenceEngineImpl internal constructor(
    private val nativeLibDir: String,
    /** Keeps encoded system prompts between model loads (see [PromptCache]); null = no cache. */
    private val promptCache: PromptCache?,
    /**
     * Single-threaded coroutine dispatcher for LLama asynchronous operations: every JNI call
     * runs on it, one at a time. Uses Dispatchers.Default (CPU pool) instead of IO because
     * llama.cpp inference is pure CPU work; the Default pool is tuned for sustained computation.
     * A JVM test gives one of its own, which is why the constructor is internal, not private.
     */
    private val llamaDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /**
     * Whether the debug memtest's load and the loads of an armed window may take their settings
     * from the debug properties over their model's ([LoadSettings.forLoad], [withDebugLoadSettings],
     * [armDebugLoadSettings]): a debuggable build, the one the phone runs. A release build loads
     * every model with its own ([ModelDefaults]), as a debug build's own work does.
     */
    private val debugSwitches: Boolean = false,
    /** Reads a debug property, "" when it isn't set; null reads the phone's. A JVM test gives its own. */
    private val propertyReader: ((String) -> String)? = null,
    /** Milliseconds that never jump, for [armDebugLoadSettings]' window. A JVM test gives its own. */
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * llama.cpp's part of a model load, the native library's start, the sampler's settings and the
     * unload; null for the JNI calls. A JVM test, where the native library can't load, gives its own.
     */
    nativeLoader: NativeLoader? = null,
    /** Where each load's settings line goes ([LoadSettings.loadLine]), and its drop's ([CopiedDrop]); a JVM test gives its own. */
    private val loadLog: (String) -> Unit = { Log.i(TAG, it) },
    /** Where a load's warnings go (a drop of its copied pages that went only in part); a JVM test gives its own. */
    private val warnLog: (String) -> Unit = { Log.w(TAG, it) }
) : InferenceEngine {

    /**
     * llama.cpp's part of a model load ([loadModel]), the native library's start before any of it,
     * the sampler's settings ([setSamplerConfig]) and the unload ([cleanUp], [destroy]).
     */
    internal interface NativeLoader {
        /** Loads the native library and starts llama.cpp's backends. */
        fun start()
        fun directIoApplies(modelPath: String): Boolean
        fun dropFileCache(path: String): Boolean
        fun load(
            modelPath: String,
            useMmap: Boolean,
            useDirectIo: Boolean,
            useExtraBufts: Boolean,
            tiedOutputLayers: String?,
            pacer: InferenceEngine.WeightPacer?,
            populate: Boolean,
            dropCopied: Boolean
        ): Int
        /** The last load's drop of its copied tensors' pages, as ai_chat.cpp's lastDrop gives it; null when none was given back. */
        fun lastDrop(): LongArray? = null
        fun prepare(nThreadsHint: Int, nCtx: Int, swaFull: Boolean, kvQ8: Boolean, nOutputsMax: Int): Int

        /** The sampler's settings for the replies from now on; 0 when set. */
        fun updateSamplerConfig(temperature: Float, topK: Int, topP: Float, minP: Float, repeatPenalty: Float): Int

        /** Frees the model, its context and its sampler. */
        fun unload()
    }

    /**
     * One setting of the sampler, as [setSamplerConfig] takes it. [DEFAULT] is what each load's
     * prepare sets: ai_chat.cpp's DEFAULT_SAMPLER_TEMP with the defaults of its sampler_params(),
     * which a JVM test reads from the C++ so the two stay the same. The app's default sampling
     * (Sampling.DEFAULT, which LlamaAndroidDataSource sends with the repeat penalty off) is the same
     * again, so the app's own calls never need a native call.
     */
    internal data class SamplerSetting(val temperature: Float, val topK: Int, val topP: Float, val minP: Float, val repeatPenalty: Float) {
        companion object {
            val DEFAULT = SamplerSetting(temperature = 0.3f, topK = 40, topP = 0.95f, minP = 0.05f, repeatPenalty = 1.0f)
        }
    }

    /** The JNI calls, as the app runs them. */
    private inner class JniLoader : NativeLoader {
        override fun start() {
            System.loadLibrary("ai-chat")
            init(nativeLibDir)
            Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
        }

        override fun directIoApplies(modelPath: String) = this@InferenceEngineImpl.directIoApplies(modelPath)

        override fun dropFileCache(path: String) = this@InferenceEngineImpl.dropFileCache(path)

        override fun load(
            modelPath: String,
            useMmap: Boolean,
            useDirectIo: Boolean,
            useExtraBufts: Boolean,
            tiedOutputLayers: String?,
            pacer: InferenceEngine.WeightPacer?,
            populate: Boolean,
            dropCopied: Boolean
        ) = this@InferenceEngineImpl.load(modelPath, useMmap, useDirectIo, useExtraBufts, tiedOutputLayers, pacer, populate, dropCopied)

        override fun lastDrop(): LongArray? = this@InferenceEngineImpl.lastDrop()

        override fun prepare(nThreadsHint: Int, nCtx: Int, swaFull: Boolean, kvQ8: Boolean, nOutputsMax: Int) =
            this@InferenceEngineImpl.prepare(nThreadsHint, nCtx, swaFull, kvQ8, nOutputsMax)

        override fun updateSamplerConfig(temperature: Float, topK: Int, topP: Float, minP: Float, repeatPenalty: Float) =
            this@InferenceEngineImpl.updateSamplerConfig(temperature, topK, topP, minP, repeatPenalty)

        override fun unload() = this@InferenceEngineImpl.unload()
    }

    private val native: NativeLoader = nativeLoader ?: JniLoader()

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(
                        nativeLibDir,
                        promptCache(context),
                        debugSwitches = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    ).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }

        /**
         * The system prompt cache in the app's cache directory, which Android may clear at any time
         * (that costs one encode), or null when the package's install can't be read.
         *
         * The build tag is the versionCode and the lastUpdateTime: an install changes lastUpdateTime
         * even when the versionCode stays, so a new native build or llama.cpp version never reads a
         * state an older one wrote.
         */
        private fun promptCache(context: Context): PromptCache? = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            PromptCache(File(context.cacheDir, PROMPT_CACHE_DIR), "${info.longVersionCode}-${info.lastUpdateTime}")
        } catch (e: Exception) {
            Log.w(TAG, "No system prompt cache: package info unavailable (${e.javaClass.simpleName})")
            null
        }

        private const val PROMPT_CACHE_DIR = "llm-prompts"

        private const val BYTES_PER_MB = 1_048_576L
    }

    /**
     * JNI methods
     *
     * Plain JNI, not @FastNative: a garbage collection has to pass every thread's checkpoint, and
     * a thread inside a @FastNative call passes none until the call returns, so a model load
     * (seconds) held up every allocation that waited for a GC. The freezes at a recording's start
     * on the phone (4.7 s, 2.2 s and 1.9 s on the main thread) each ended 10-40 ms after the
     * model load returned. The native signatures are the same either way.
     *
     * @see ai_chat.cpp
     */
    private external fun init(nativeLibDir: String)

    /**
     * llama.cpp's model load with [LoadSettings]' model parameters; 0 when the model loaded.
     *
     * @param tiedOutputLayers With repack on, the tensors to send to KleidiAI's buffer type alone,
     *   the extra buffer types otherwise off ([LoadSettings.TIED_OUTPUT_LAYERS]); null leaves the
     *   choice to the extra buffer types, which repack the tied output too
     * @param pacer Called from the loader's progress before each tensor ([InferenceEngine.WeightPacer]);
     *   null leaves the copy unpaced
     * @param populate Whether the file is mapped with MAP_POPULATE, as every model's is ([LoadSettings.populate])
     * @param dropCopied Whether the copied tensors' pages go back to the kernel once the last tensor
     *   is loaded ([LoadSettings.dropCopied], lib-llama-android/patches/0003-mapped-hook.patch)
     */
    private external fun load(
        modelPath: String,
        useMmap: Boolean,
        useDirectIo: Boolean,
        useExtraBufts: Boolean,
        tiedOutputLayers: String?,
        pacer: InferenceEngine.WeightPacer?,
        populate: Boolean,
        dropCopied: Boolean
    ): Int

    /** The last load's drop of its copied tensors' pages ([CopiedDrop.of]); null when it gave none back. */
    private external fun lastDrop(): LongArray?

    /**
     * Drops the cached pages of the file at [path] (MADV_PAGEOUT, then posix_fadvise DONTNEED;
     * ai_chat.cpp's drop_cached_pages): a cold load ([LoadSettings.cold]), or the pre-read probe.
     */
    private external fun dropFileCache(path: String): Boolean

    /** The context, batch and sampler, with [LoadSettings]' context parameters; 0 when set up. */
    private external fun prepare(nThreadsHint: Int, nCtx: Int, swaFull: Boolean, kvQ8: Boolean, nOutputsMax: Int): Int

    /** The system property [name], "" when it isn't set. Only a debug build's memtest or armed window reads one. */
    private external fun systemProperty(name: String): String

    /** Whether [modelPath] opens with O_DIRECT, as llama.cpp's direct IO needs. */
    private external fun directIoApplies(modelPath: String): Boolean

    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    /**
     * Text for the model crosses JNI as UTF-8 bytes ([utf8]), not as a String: JNI reads a String as
     * Modified UTF-8, which writes a character outside the BMP, such as an emoji, as two surrogates.
     * A JSON schema holding one would not parse, and the model would read it as noise.
     *
     * @param cachePath The file for this prompt's encoded state ([PromptCache.pathFor]); null = no
     *   cache. Encoding a system prompt of about 1,000 characters takes Gemma 4 E2B about 3 s
     *   (1.5-5 s across the app's prompts), and each finishing run loads the model afresh, so the
     *   native side loads the state from this file when it holds the same tokens, and else encodes
     *   the prompt and saves its state there. It skips the file for models with M-RoPE (which
     *   can't rewind and get a full reset), for recurrent and hybrid models (a recurrent state
     *   beside the KV cache, never tried) and for a system turn the template defers to the first
     *   user prompt (nothing is encoded then, as with Gemma 3 1B). A cache problem never fails the
     *   prompt: it is encoded as without one.
     */
    private external fun processSystemPrompt(systemPrompt: ByteArray, cachePath: String?): Int

    private external fun updateSamplerConfig(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Int

    /**
     * @param userPrompt The prompt in UTF-8 (see [processSystemPrompt])
     * @param grammar GBNF or a JSON schema for this reply only, in UTF-8; null = free text
     * @param grammarIsJsonSchema true when [grammar] is a JSON schema
     */
    private external fun processUserPrompt(
        userPrompt: ByteArray,
        predictLength: Int,
        grammar: ByteArray?,
        grammarIsJsonSchema: Boolean
    ): Int

    /** [text] as the UTF-8 bytes the native side reads. */
    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private external fun generateNextToken(): String?

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false

    // Whether the native library loaded and initialised, which every native call needs.
    @Volatile
    private var nativeLoaded = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * The loaded model's file as its weights were read, part of [promptCache]'s key; null while no
     * model is loaded, or when the file changed during the load.
     */
    private var model: PromptCache.Model? = null

    /**
     * [LoadSettings.describe] of the load in memory, part of [promptCache]'s key, and of the last
     * load for [loadSettings].
     */
    @Volatile
    private var settings: String? = null

    override val loadSettings: String?
        get() = settings

    // The sampler's setting on the native side: SamplerSetting.DEFAULT from each load's prepare,
    // then what setSamplerConfig last applied. Only used on llamaDispatcher; while no model is
    // loaded nothing reads it, as setSamplerConfig needs one.
    private var sampler = SamplerSetting.DEFAULT

    // The blocks under withDebugLoadSettings now: the memtest's, which holds the AI work gate, so
    // no load of the app's own work can start beside it.
    private val debugLoads = AtomicInteger()

    override suspend fun <T> withDebugLoadSettings(block: suspend () -> T): T {
        debugLoads.incrementAndGet()
        try {
            return block()
        } finally {
            debugLoads.decrementAndGet()
        }
    }

    // The end of armDebugLoadSettings' window, on clockMs; 0 when none is open.
    @Volatile
    private var armedUntilMs = 0L

    override fun armDebugLoadSettings(forMs: Long) {
        armedUntilMs = if (debugSwitches && forMs > 0) clockMs() + forMs else 0L
    }

    /**
     * The settings of a load starting now of a model whose own are [defaults] ([LoadSettings.forLoad]):
     * the debug properties' over them only for a load under [withDebugLoadSettings] or in
     * [armDebugLoadSettings]' window in a debug build, else [defaults], with no property read.
     */
    internal fun settingsForLoad(defaults: LoadSettings): LoadSettings = LoadSettings.forLoad(
        defaults = defaults,
        debuggable = debugSwitches,
        armed = debugLoads.get() > 0 || clockMs() < armedUntilMs,
        read = propertyReader ?: { name -> systemProperty(name) },
        warn = { Log.w(TAG, it) }
    )

    /** The scope of [llamaDispatcher]'s work. */
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                native.start()
                nativeLoaded = true
                _state.value = InferenceEngine.State.Initialized

            } catch (e: Throwable) {
                // UnsatisfiedLinkError is a JVM Error (not Exception), so we must catch Throwable
                // to handle missing .so dependencies on budget ARM devices (TECNO, Infinix, ZTE).
                // Transition to Error state so callers can handle gracefully instead of crashing.
                Log.e(TAG, "Failed to load native library", e)
                val exception = if (e is Exception) e
                                else RuntimeException("Native library failed to load: ${e.message}", e)
                _state.value = InferenceEngine.State.Error(exception)
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, nThreadsHint: Int) =
        loadModel(pathToModel, nThreadsHint, object : InferenceEngine.LoadHooks {})

    /**
     * Load the LLM, with [hooks] around llama.cpp's own part: after the settings are chosen (the
     * model's own, [ModelDefaults], unless a debug build's memtest or armed window changes some),
     * their line is logged and a cold load has dropped the file's cached pages, so what the hooks
     * read in, at the settings' pre-read pace, stays cached. The native calls go back to
     * [llamaDispatcher] whichever thread the hooks call them from.
     *
     * A load without MAP_POPULATE (a debug build's `debug.satori.llm.populate=off`) tells the hooks
     * which byte ranges of the file it reads in place ([InPlaceTensors]); every other load, all of it.
     *
     * Once llama.cpp has the model and its context the engine is ready with it, also when the caller
     * was cancelled meanwhile and the hooks hand the cancel back: the model is in memory either way,
     * and an engine in its error state would drop it without unloading it, so the next load would
     * leave it behind (1-1.5 GB). Before that point a cancel leaves the engine as it was.
     */
    override suspend fun loadModel(pathToModel: String, nThreadsHint: Int, hooks: InferenceEngine.LoadHooks) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            // The file as the weights come from it, for promptCache's key, read before the load
            // and after it: a file replaced in between (a download renames its file over the
            // old one) gives this load no cache, since its states could be either file's.
            var modelBeforeLoad: PromptCache.Model? = null
            // Set once llama.cpp has the model and its context in memory.
            var inMemory = false
            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                modelBeforeLoad = PromptCache.Model.of(pathToModel)
                val fileName = File(pathToModel).name
                val model = ModelDefaults.of(fileName)
                val chosen = settingsForLoad(model.settings)
                val direct = chosen.directIo && native.directIoApplies(pathToModel)
                val described = chosen.describe(directIoApplies = direct)
                settings = described
                loadLog(LoadSettings.loadLine(fileName, model, chosen, described))
                if (chosen.cold) native.dropFileCache(pathToModel)
                val preRead = chosen.preReadMbPerSecond
                val readIn = if (preRead != LoadSettings.PACE_OFF && !chosen.populate && chosen.mmap && !direct) {
                    inPlaceRanges(pathToModel, chosen)
                } else {
                    null
                }
                hooks.aroundNativeLoad(readIn, preRead) {
                    withContext(llamaDispatcher) {
                        val started = SystemClock.elapsedRealtime()
                        val pacer = chosen.copyPaceMbPerSecond.takeIf { it != LoadSettings.PACE_OFF }
                            ?.let(hooks::weightPacer)
                            ?.let(::StoppingPacer)
                        val tiedOutputLayers = LoadSettings.TIED_OUTPUT_LAYERS.takeIf { chosen.tiedOutputMapped && chosen.repack }
                        val loaded = native.load(
                            pathToModel, chosen.mmap, chosen.directIo, chosen.repack, tiedOutputLayers, pacer, chosen.populate,
                            dropCopied = chosen.dropsCopied(direct)
                        )
                        pacer?.ended(loaded = loaded == 0)
                        if (loaded == 0) logDrop(fileName)
                        if (loaded != 0) {
                            if (pacer?.stopped == true) {
                                throw IOException("The weight copy stopped the load: the phone stayed short of memory for 10 s")
                            }
                            // TODO-han.yin: find a better way to pass other error codes
                            throw UnsupportedArchitectureException()
                        }
                        native.prepare(nThreadsHint, chosen.nCtx, chosen.swaFull, chosen.kvQ8, chosen.nOutputsMax).let {
                            if (it != 0) throw IOException("Failed to prepare resources")
                        }
                        // prepare made a new sampler with ai_chat.cpp's own settings.
                        sampler = SamplerSetting.DEFAULT
                        inMemory = true
                        Log.i(TAG, "Model loaded in ${SystemClock.elapsedRealtime() - started} ms, $described")
                    }
                }
                becomeReady(pathToModel, modelBeforeLoad)
            } catch (e: CancellationException) {
                if (inMemory) {
                    Log.i(TAG, "Model loaded while its caller went away; ready with it")
                    becomeReady(pathToModel, modelBeforeLoad)
                } else {
                    Log.i(TAG, "Model load cancelled before llama.cpp loaded it")
                    _state.value = InferenceEngine.State.Initialized
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    /**
     * The line of the load's drop of its copied tensors' pages ([CopiedDrop]), in every build, with
     * no text: none when nothing was given back. A drop that can't be read never fails the load.
     */
    private fun logDrop(fileName: String) {
        val drop = try {
            CopiedDrop.of(native.lastDrop())
        } catch (e: RuntimeException) {
            null
        } ?: return
        val (line, warning) = drop.line(fileName)
        if (warning) warnLog(line) else loadLog(line)
    }

    /** The engine with the model just loaded from [path]: ready for its system prompt. */
    private fun becomeReady(path: String, modelBeforeLoad: PromptCache.Model?) {
        model = PromptCache.Model.of(path)?.takeIf { it == modelBeforeLoad }
        if (promptCache != null && model == null) {
            Log.w(TAG, "No system prompt cache for this model: its file changed while it loaded")
        }
        _readyForSystemPrompt = true

        _cancelGeneration = false
        _state.value = InferenceEngine.State.ModelReady
    }

    /**
     * The byte ranges of the model at [path] that a load without MAP_POPULATE reads in place
     * ([InPlaceTensors]), with its layers' matrices copied when [settings] repack; null, the whole
     * file, when its tensor table can't be read, for whatever reason: the pre-read then reads too
     * much in, never too little.
     */
    private fun inPlaceRanges(path: String, settings: LoadSettings): List<LongRange>? = try {
        val file = File(path)
        InPlaceTensors.ranges(GgufTensors.read(file), layersCopied = settings.repack).also { ranges ->
            Log.i(TAG, "Without MAP_POPULATE: ${ranges.size} tensors, ${ranges.sumOf { it.last - it.first + 1 } / BYTES_PER_MB} MB of " +
                "the file's ${file.length() / BYTES_PER_MB} MB, are read in place; the app reads those in first")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Without MAP_POPULATE, but the file's tensor table wasn't read (${e.javaClass.simpleName}); the app reads all of it in first")
        null
    }

    /**
     * The app's [pacer] as llama.cpp's loader calls it, minding whether it stopped the load, so the
     * load's failure says so instead of naming the model's architecture.
     */
    private class StoppingPacer(private val pacer: InferenceEngine.WeightPacer) : InferenceEngine.WeightPacer {
        @Volatile
        var stopped = false
            private set

        override fun beforeTensor(doneBytes: Long): Boolean = pacer.beforeTensor(doneBytes).also { if (!it) stopped = true }

        override fun ended(loaded: Boolean) = pacer.ended(loaded)
    }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            val cachePath = model?.let { loaded -> settings?.let { promptCache?.pathFor(loaded, it, prompt) } }
            if (promptCache != null && model != null && cachePath == null) {
                Log.w(TAG, "No system prompt cache: its directory is unavailable")
            }
            processSystemPrompt(utf8(prompt), cachePath).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            cachePath?.let { promptCache?.afterUse(it) }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            // Allow subsequent setSystemPrompt calls (needed for stateless inference pattern
            // where the context is reset before every generateInsight call).
            _readyForSystemPrompt = true
            _state.value = InferenceEngine.State.ModelReady
        }

    /** On [llamaDispatcher], so never during a native call; false while the native library isn't loaded. */
    override suspend fun dropCachedPages(path: String): Boolean = withContext(llamaDispatcher) {
        nativeLoaded && native.dropFileCache(path)
    }

    /**
     * On [llamaDispatcher], so it comes after a save that processSystemPrompt has under way and
     * never runs beside one.
     */
    override suspend fun forgetPromptCache() {
        val cache = promptCache ?: return
        withContext(llamaDispatcher) {
            if (cache.clear()) {
                Log.i(TAG, "System prompt cache cleared")
            } else {
                Log.w(TAG, "System prompt cache not cleared: some of its files could not be deleted")
            }
        }
    }

    /**
     * The app sets the sampling before every reply, so a setting the sampler has already changes
     * nothing: no native call and no line. A load's prepare puts ai_chat.cpp's own back
     * ([SamplerSetting.DEFAULT]), which is what the app's default asks for, so only a debug
     * build's rerun at another temperature, and the call after it, reach the native side.
     */
    override suspend fun setSamplerConfig(
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float
    ): Unit = withContext(llamaDispatcher) {
        check(_state.value.isModelLoaded) {
            "Cannot update sampler config in ${_state.value.javaClass.simpleName}!"
        }
        val wanted = SamplerSetting(temperature, topK, topP, minP, repeatPenalty)
        if (wanted == sampler) return@withContext
        Log.i(TAG, "Updating sampler config: temp=$temperature topK=$topK topP=$topP minP=$minP repeatPenalty=$repeatPenalty")
        val result = native.updateSamplerConfig(temperature, topK, topP, minP, repeatPenalty)
        if (result == 0) {
            sampler = wanted
        } else {
            Log.e(TAG, "Failed to update sampler config: $result")
        }
    }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
        grammar: InferenceEngine.Grammar?,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        // The native side builds the grammar for this reply and drops it when the reply ends.
        val grammarText = when (grammar) {
            null -> null
            is InferenceEngine.Grammar.Gbnf -> grammar.text
            is InferenceEngine.Grammar.JsonSchema -> grammar.schema
        }
        val grammarIsJsonSchema = grammar is InferenceEngine.Grammar.JsonSchema

        try {
            // Grammar.toString() gives its size only, never its text.
            Log.i(TAG, if (grammar == null) "Sending user prompt..." else "Sending user prompt with $grammar...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            processUserPrompt(utf8(message), predictLength, grammarText?.let(::utf8), grammarIsJsonSchema).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    return@flow
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            // Re-enable system prompt updates so the next stateless inference can reset context.
            _readyForSystemPrompt = true
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _readyForSystemPrompt = true
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    native.unload()
                    model = null

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    model = null
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            model = null
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { native.unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
