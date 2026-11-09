package com.satori.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.satori.data.datasource.LlmDataSource
import com.satori.data.datasource.ModelFileInspector
import com.satori.data.monitor.AnswerFloor
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.util.PerfLogger
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.modelfile.ModelDamagedException
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.currentSampling
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Implementation of LlmRepository that bridges data source to domain layer.
 *
 * Uses a single-threaded dispatcher for thread safety (llama.cpp requirement).
 *
 * Stateless inference: resets context (KV-cache) before every call so the
 * system prompt is always fresh and no chat history accumulates. This prevents
 * language drift, repetitive output, and context overflow in small models (2B)
 * that cannot maintain task fidelity across multi-turn conversations.
 *
 * One model is in memory at a time, the engine's limit. The one loaded is always the one stored
 * last ([initialize], [switchModel]): a load of another file unloads the model in memory first, so
 * Gemma 4 E2B, switched in for the work after Stop, can never be left to write the live notes.
 *
 * @property llmDataSource Data source for LLM operations
 * @property context Application context for PowerManager access
 * @property memoryReading The memory figures the checks after a load and after an inference read
 *   ([isMemoryConstrained]); a binder call into ActivityManager, so those checks read them off
 *   their caller's thread. A test sets it
 * @property memoryLines The memory line of each load and release of a model, in a debug build
 *   ([ModelMemoryLines]); off in a test unless it sets them
 * @property promptHashes Whether each inference's line gives its input's hash ([promptHash]): a
 *   debug build, where a comparison of samplings on the phone needs to know that two calls read
 *   the same; a test sets it
 * @property perfSnapshots Whether each inference reads the battery and memory before and after it
 *   for [PerfLogger]'s lines (`[llm-start]`, `[llm-done]`): the app always; a JVM test, whose
 *   context has no system services, doesn't
 * @property answerFloor The wait for memory of the first answer after each load (since 0.7.2), on
 *   the floor of the paced loads; none in a test unless it sets one
 * @property availableMemory The memory free for apps now, in bytes, which a switch measures
 *   ([ActivityManager.MemoryInfo.availMem]); a test sets it
 */
class LlmRepositoryImpl internal constructor(
    private val llmDataSource: LlmDataSource,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
    private val memoryReading: () -> ActivityManager.MemoryInfo = { memoryInfoOf(context) },
    private val memoryLines: ModelMemoryLines = ModelMemoryLines.OFF,
    private val promptHashes: Boolean = false,
    private val perfSnapshots: Boolean = false,
    private val answerFloor: AnswerFloor = AnswerFloor.NONE,
    private val availableMemory: () -> Long
) : LlmRepository {

    @Inject constructor(
        llmDataSource: LlmDataSource,
        settingsRepository: SettingsRepository,
        @ApplicationContext context: Context,
        pacing: ModelLoadPacing
    ) : this(
        llmDataSource,
        settingsRepository,
        context,
        memoryReading = { memoryInfoOf(context) },
        memoryLines = ModelMemoryLines.forApp(context, TAG),
        promptHashes = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        perfSnapshots = true,
        answerFloor = AnswerFloor.forApp(pacing.memoryFloor, context),
        availableMemory = { memoryInfoOf(context).availMem }
    )

    companion object {
        private const val TAG = "LlmRepositoryImpl"

        // Multiplier applied to ActivityManager.MemoryInfo.threshold to compute the free-RAM
        // floor below which we consider the device memory-constrained.
        //
        // `threshold` is the system's own low-memory level for the specific device (typically
        // 150-250 MB on modern Android). Unloading only when availMem < threshold would be
        // reactive (crisis already in progress), so we multiply to be proactive: we want to
        // free the LLM (~1.4 GB footprint) *before* kswapd starts reclaiming its mmap pages.
        //
        // threshold * 3 ≈ 0.45-0.75 GB on typical devices — frees the LLM only when RAM
        // is genuinely scarce (< ~2 GB free). A multiplier of 7 was too aggressive: on
        // high-end OEM devices (e.g. OnePlus) the system threshold can reach 600-700 MB,
        // causing the model to be freed after every batch chunk even with 4+ GB free.
        private const val LOW_MEMORY_THRESHOLD_MULTIPLIER = 3

        // Conservative thread count for long sessions on RAM-constrained devices.
        // 2 threads leave 2+ big cores free for STT, UI events, and system services,
        // eliminating the CPU saturation that causes ANR at the cost of ~1.6x slower inference.
        private const val N_THREADS_CONSERVATIVE = 2

        // Safety margin for the adaptive char threshold: when a constrained inference is
        // observed at X chars, the stored threshold is X * CHARS_THRESHOLD_SAFETY_MARGIN.
        // This means conservative threads are enabled 20% before the observed problem size,
        // giving headroom for variance in transcript length across sessions.
        private const val CHARS_THRESHOLD_SAFETY_MARGIN = 0.8

        private const val BYTES_PER_MB = 1_048_576L

        private fun memoryInfoOf(context: Context): ActivityManager.MemoryInfo {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        }
    }

    // Coroutine scope for async DataStore writes that must not block the LLM dispatcher, and for
    // clearing the prompt cache, which its caller doesn't wait for. Lives for the lifetime of this
    // singleton.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache of adaptive thread config — populated from DataStore at construction
    // and kept in sync by checkAndCacheMemoryConstraint() / recordConstrainedInference().
    // @Volatile ensures visibility across coroutine dispatchers.
    @Volatile private var cachedMemoryConstrained: Boolean = false
    @Volatile private var cachedCharThreshold: Int = Int.MAX_VALUE

    init {
        // Load persisted adaptive state from DataStore at startup so the thread hint is
        // applied correctly on the very first model load without waiting for a ViewModel call.
        repositoryScope.launch {
            val settings = settingsRepository.getSettings().first()
            cachedMemoryConstrained = settings.memoryConstrainedDetected
            cachedCharThreshold = settings.conservativeThreadsCharThreshold
            if (cachedMemoryConstrained) {
                nThreadsHint = N_THREADS_CONSERVATIVE
                Log.i(TAG, "Restored conservative thread mode from DataStore (memoryConstrainedDetected=true)")
            }
        }
    }

    // Single-threaded dispatcher for llama.cpp thread safety.
    // Dispatchers.Default is used instead of IO because LLM inference is pure CPU work —
    // the Default pool is tuned for sustained CPU computation, while IO is for blocking I/O.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    // Thread count passed to llama.cpp prepare() on the next model load.
    // -1 = auto (engine picks from CPU count, capped at N_THREADS_MAX=4).
    // N_THREADS_CONSERVATIVE = 2, set via useConservativeThreads() on RAM-constrained
    // devices or when isLargeContext() returns true before a reload.
    // @Volatile ensures cross-dispatcher visibility.
    @Volatile private var nThreadsHint: Int = -1

    // True while generateInsight() is actively collecting tokens on singleThreadDispatcher.
    // @Volatile ensures cross-dispatcher visibility (generateInsight runs on singleThreadDispatcher,
    // isGenerating is read from Main in stopStreaming()).
    @Volatile override var isGenerating: Boolean = false
        private set

    // The file of the model in memory, or null when none is. Written on singleThreadDispatcher
    // only; @Volatile because the memory checks read it from their callers' threads.
    @Volatile private var loadedModelPath: String? = null
    private val isModelLoaded: Boolean get() = loadedModelPath != null

    // The file switchModel() last put in memory. While it is the one in memory, the memory checks
    // learn nothing: the adaptive thread state is for the live model, and its char threshold only
    // ever goes down, so readings taken next to Gemma 4 E2B would slow the live notes for good.
    @Volatile private var switchedModelPath: String? = null

    // Like the fields below, only used on singleThreadDispatcher.
    private var lastModelPath: String? = null
    private var lastSystemPrompt: String? = null

    /** A model load under way: the file it loads, and its outcome once the engine is done. */
    private class Load(val path: String) {
        val result = CompletableDeferred<Result<Unit>>()
    }

    // The model load under way, if any. The load itself runs on the engine's own thread, which
    // leaves singleThreadDispatcher free for other calls in the meantime, cleanup() among them.
    // Written there only; @Volatile because isModelInMemory reads it from its callers' threads.
    @Volatile private var loadInProgress: Load? = null

    // Set by cleanup() during a load: the load unloads the model as soon as it is done, so
    // cleanup() returns at once instead of waiting 5-15 s (onCleared() calls it on the main
    // thread). A load requested after that clears it again, as it would follow the unload.
    private var unloadWhenLoaded = false

    // The file of the model a load has just put in memory, until its first answer has waited for
    // memory (answerFloor); null once it has, and while no model is loaded. Like the fields
    // above, only used on singleThreadDispatcher.
    private var firstAnswerOf: String? = null

    override suspend fun initialize(
        modelPath: String,
        systemPrompt: String?,
        loadImmediately: Boolean
    ): Result<Unit> = withContext(singleThreadDispatcher) {
        lastModelPath = modelPath
        lastSystemPrompt = systemPrompt
        if (!loadImmediately) {
            // Defer loading — model will be loaded lazily before the first LONG_MEETING inference
            Log.i(TAG, "Model initialization deferred (lazy load mode)")
            return@withContext Result.success(Unit)
        }
        if (loadedModelPath == modelPath) {
            // Model already loaded — two callers raced (normal init + post-download observer).
            // Treat as success so the second caller does not overwrite state. Another model in
            // memory is swapped out by loadModel instead.
            Log.i(TAG, "Model already loaded, skipping duplicate initialization")
            return@withContext Result.success(Unit)
        }
        val threadsDesc = if (nThreadsHint > 0) "$nThreadsHint (conservative)" else "auto (hint=$nThreadsHint)"
        Log.i(TAG, "Model load: threads=$threadsDesc cachedConstrained=$cachedMemoryConstrained charThreshold=$cachedCharThreshold")
        loadModel(modelPath, systemPrompt).also { result ->
            if (result.isSuccess) Log.i(TAG, "Model initialized")
        }
    }

    override fun useConservativeThreads() {
        if (nThreadsHint == N_THREADS_CONSERVATIVE) return
        nThreadsHint = N_THREADS_CONSERVATIVE
        Log.i(TAG, "Conservative thread mode enabled: next load will use $N_THREADS_CONSERVATIVE threads")
    }

    override suspend fun checkAndCacheMemoryConstraint() {
        if (switchedModelInMemory()) return
        val constrained = memoryConstrainedOffCaller()
        if (constrained == cachedMemoryConstrained) {
            // No change — ensure hint is consistent then skip DataStore write.
            if (constrained && nThreadsHint != N_THREADS_CONSERVATIVE) nThreadsHint = N_THREADS_CONSERVATIVE
            return
        }
        cachedMemoryConstrained = constrained
        nThreadsHint = if (constrained) N_THREADS_CONSERVATIVE else -1
        Log.i(TAG, "checkAndCacheMemoryConstraint: constrained=$constrained → nThreadsHint=$nThreadsHint")
        repositoryScope.launch {
            settingsRepository.setMemoryConstrainedDetected(constrained)
        }
    }

    override suspend fun recordConstrainedInference(inputChars: Int) {
        if (switchedModelInMemory() || inputChars <= 0 || !memoryConstrainedOffCaller()) return
        // Compute the new threshold with the safety margin applied.
        val newThreshold = (inputChars * CHARS_THRESHOLD_SAFETY_MARGIN).toInt()
        // Converge downward: take the minimum so the threshold tightens over time.
        if (newThreshold >= cachedCharThreshold) return
        cachedCharThreshold = newThreshold
        Log.i(TAG, "recordConstrainedInference: inputChars=$inputChars → new charThreshold=$newThreshold")
        repositoryScope.launch {
            settingsRepository.lowerConservativeThreadsCharThreshold(newThreshold)
        }
    }

    override fun isLargeContext(inputChars: Int): Boolean {
        val large = inputChars >= cachedCharThreshold
        if (large) {
            Log.i(TAG, "isLargeContext: inputChars=$inputChars >= threshold=$cachedCharThreshold → switching to conservative threads")
        }
        return large
    }

    override suspend fun reloadModel(): Result<Unit> = withContext(singleThreadDispatcher) {
        val path = lastModelPath ?: return@withContext Result.failure(
            IllegalStateException("reloadModel called before initialize")
        )
        if (loadedModelPath == path) {
            // Already loaded — no I/O needed (device has enough free RAM)
            return@withContext Result.success(Unit)
        }
        val threadsDesc = if (nThreadsHint > 0) "$nThreadsHint (conservative)" else "auto (hint=$nThreadsHint)"
        Log.i(TAG, "Model reload: threads=$threadsDesc cachedConstrained=$cachedMemoryConstrained charThreshold=$cachedCharThreshold")
        loadModel(path, lastSystemPrompt).also { result ->
            if (result.isSuccess) {
                Log.i(TAG, "Model reloaded")
            } else {
                Log.e(TAG, "Model reload failed: ${result.exceptionOrNull()}")
            }
        }
    }

    override suspend fun switchModel(modelPath: String): Result<Unit> = switchModel(modelPath, extraFreeBytes = 0L)

    override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> = withContext(singleThreadDispatcher) {
        val name = File(modelPath).name
        // Stored before the load, so a reloadModel() that comes in meanwhile waits for this load
        // instead of loading the old model behind it; put back unless the switch succeeds.
        val previous = lastModelPath
        lastModelPath = modelPath
        var switched = false
        try {
            val start = SystemClock.elapsedRealtime()
            // Never less than the file: a negative margin is none. 0.5.1 let Gemma 4 E2B load up to
            // 300 MB short of its file; with its file's size alone free (0.5.0) the phone already
            // went into direct reclaim (2026-10-13 06:40).
            loadModel(modelPath, lastSystemPrompt, freeBeyondFile = extraFreeBytes.coerceAtLeast(0L)).also { result ->
                switched = result.isSuccess
                if (switched) {
                    switchedModelPath = modelPath
                    // How long the switch took and the memory left: what the field log is read for.
                    Log.i(TAG, "Switched to $name in ${SystemClock.elapsedRealtime() - start} ms")
                    PerfLogger.logSnapshot(context, "model-switched")
                } else {
                    Log.w(TAG, "Could not switch to $name: ${result.exceptionOrNull()}")
                }
            }
        } finally {
            if (!switched) lastModelPath = previous
        }
    }

    /**
     * Loads the model in [path] in place of any other one in memory, or waits for the load of it
     * already under way: the engine takes one load at a time and refuses a second, so the load of
     * another file is let end first. Call on [singleThreadDispatcher].
     *
     * @param freeBeyondFile Refuse to load unless at least as much memory is available as the
     *   file is big plus this (0 or more: [switchModel] passes no less), measured once the model in
     *   memory is out, with a [NotEnoughMemoryException]. Null loads without the check: the models
     *   loaded for the live notes are a third of the size of the one switched in and are not held
     *   to it.
     */
    private suspend fun loadModel(path: String, systemPrompt: String?, freeBeyondFile: Long? = null): Result<Unit> {
        val file = File(path)
        // Every load passes here, and llama.cpp can abort the whole process on a file that is
        // truncated or not a model, which nothing in Kotlin can catch. A file that fails its
        // check never reaches the loader, and the model in memory stays.
        if (ModelFileInspector.aiModel(file) != ModelFileState.Intact) {
            Log.e(TAG, "Not loading the model: its file is missing, damaged or incomplete")
            return Result.failure(ModelDamagedException("The AI model file is damaged or incomplete"))
        }
        while (true) {
            val load = loadInProgress ?: break
            if (load.path == path) {
                unloadWhenLoaded = false
                Log.i(TAG, "Model load already in progress, waiting for it")
                return load.result.await()
            }
            Log.i(TAG, "Another model is loading, waiting for it before loading ${file.name}")
            load.result.await()
        }
        if (loadedModelPath == path) return Result.success(Unit)
        if (isModelLoaded) unloadForSwap().onFailure { return Result.failure(it) }
        if (freeBeyondFile != null) {
            val available = availableMemory()
            val needed = file.length() + freeBeyondFile
            if (available < needed) {
                Log.w(TAG, "Not loading ${file.name}: ${available / BYTES_PER_MB} MB available, " +
                    "${needed / BYTES_PER_MB} MB needed (the file is ${file.length() / BYTES_PER_MB} MB)")
                return Result.failure(NotEnoughMemoryException(available, needed))
            }
        }
        val load = Load(path)
        loadInProgress = load
        var result: Result<Unit> = Result.failure(IllegalStateException("Model load did not finish"))
        try {
            // NonCancellable: the native load runs to the end even when its caller is cancelled
            // (Cancel or Back while the model loads), so its outcome must always be recorded.
            // Otherwise the engine holds a model this repository thinks is not loaded: cleanup()
            // skips it, about 1 GB stays in RAM and the engine refuses every later load.
            result = withContext(NonCancellable) {
                val measuring = memoryLines.begin()
                val loaded = llmDataSource.loadModel(path, systemPrompt, nThreadsHint)
                memoryLines.end(
                    measuring,
                    ModelMemoryLines.Event.LOAD,
                    file.name,
                    failed = loaded.isFailure,
                    settings = llmDataSource.loadSettings
                )
                if (loaded.isSuccess) {
                    loadedModelPath = path
                    firstAnswerOf = file.name
                }
                if (loaded.isSuccess && unloadWhenLoaded) unloadAfterLoad() else loaded
            }
        } finally {
            unloadWhenLoaded = false
            loadInProgress = null
            load.result.complete(result)
        }
        return result
    }

    /**
     * Unloads the model in memory so another one can be loaded. Call on [singleThreadDispatcher].
     *
     * @return failure when the engine refused, busy with a reply; the model stays loaded and tracked
     */
    private suspend fun unloadForSwap(): Result<Unit> {
        val name = loadedModelPath?.let { File(it).name }
        return try {
            freeModel()
            loadedModelPath = null
            Log.i(TAG, "Unloaded $name to load another model")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not unload $name, not loading another model", e)
            Result.failure(e)
        }
    }

    /** Carries out a cleanup() that came in while the model was loading. */
    private suspend fun unloadAfterLoad(): Result<Unit> = try {
        freeModel()
        loadedModelPath = null
        Log.i(TAG, "Model unloaded right after loading: cleanup() was called during the load")
        Result.failure(IllegalStateException("The model was unloaded while it loaded"))
    } catch (e: Exception) {
        // The model stays loaded and tracked, so the next cleanup() frees it.
        Log.e(TAG, "Unloading after the load failed", e)
        Result.success(Unit)
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo = memoryReading()

    /**
     * [isMemoryConstrained], read on an IO thread: the live notes and the recorder check after
     * each load and each note, from the main thread, and the read is a binder call of the kind
     * that took up to 0.9 s on the razr just after the AI's service started (2026-10-13).
     */
    private suspend fun memoryConstrainedOffCaller(): Boolean = withContext(Dispatchers.IO) { isMemoryConstrained() }

    /** Whether the model in memory is the one [switchModel] put there (see switchedModelPath). */
    private fun switchedModelInMemory(): Boolean {
        val loaded = loadedModelPath ?: return false
        return loaded == switchedModelPath
    }

    override fun isMemoryConstrained(): Boolean {
        val memInfo = memoryInfo()
        val floor = memInfo.threshold * LOW_MEMORY_THRESHOLD_MULTIPLIER
        val constrained = memInfo.availMem < floor
        Log.d(TAG, "isMemoryConstrained: availMem=${memInfo.availMem / BYTES_PER_MB}MB " +
            "threshold=${memInfo.threshold / BYTES_PER_MB}MB " +
            "floor=${floor / BYTES_PER_MB}MB → constrained=$constrained")
        return constrained
    }

    override fun beginInference() { isGenerating = true }

    override fun endInference() { isGenerating = false }

    // [grammar] constrains this call only: the native sampler drops it when the reply ends, so
    // later calls without one are free text. A grammar llama.cpp rejects gives free text too.
    // With a grammar, [text] goes to the model as is (see buildPrompt).
    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): Flow<String> = generateInsight(text, systemPrompt, maxTokens, grammar, lead = null)

    // [lead] goes before the text, outside the quotes of a free-text call (see buildPrompt).
    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?,
        lead: String?
    ): Flow<String> = flow {
        // Stateless inference: reset context before each call so the system
        // prompt is always the dominant signal. The KV-cache clear + system
        // prompt re-encoding (~200 tokens) costs ~50-100ms — negligible vs
        // the 8-15s generation that follows. This eliminates:
        // - Language drift from accumulated English chat history
        // - Repetitive output from pattern reinforcement
        // - Context overflow risk (only system prompt + 1 exchange in context)

        // If the caller provides a system prompt (e.g. GenerateBatchInsightUseCase), use it;
        // otherwise fall back to the last prompt set via updateSystemPrompt / initialize.
        if (systemPrompt != null) {
            lastSystemPrompt = systemPrompt
        }
        // The first answer after a load waits while MemFree is under the floor of the paced loads,
        // 10 s at most, before its first native call (AnswerFloor); a cancel ends the wait, and the
        // next answer then waits instead.
        firstAnswerOf?.let { model ->
            answerFloor.await(model)
            firstAnswerOf = null
        }
        llmDataSource.updateSystemPrompt(lastSystemPrompt ?: "")
        // The caller's sampling (a debug build's rerun of a finishing step, AiSampling) or the
        // default, set before every reply, so no call samples with one an earlier call left; the
        // engine makes no native call for the setting it has. flowOn keeps the collector's context
        // elements, so the caller's reaches this block; the finishing queue's own scope never
        // carries one.
        val sampling = currentSampling()
        llmDataSource.useSampling(sampling)

        val prompt = buildPrompt(text, grammar, lead)
        val estimatedTokens = text.length / 4
        val threadsDesc = if (nThreadsHint > 0) "$nThreadsHint (conservative)" else "auto"
        // The prompts carry the transcript and the user's topic, so only their sizes are logged,
        // and in a debug build a hash that tells two inputs apart without saying anything of them.
        // The same goes for the grammar, whose enums can quote the transcript.
        val hash = if (promptHashes) " promptHash=${promptHash(lastSystemPrompt.orEmpty(), prompt, grammar)}" else ""
        Log.d(TAG, "=== LLM INFERENCE === threads=$threadsDesc inputChars=${text.length} " +
            "systemPromptChars=${lastSystemPrompt?.length ?: 0} userPromptChars=${prompt.length} " +
            "grammar=${describe(grammar)} sampling=${sampling.describe()}$hash")
        Log.d(TAG, "Tokens: ~$estimatedTokens input + $maxTokens output budget")

        val batteryBefore = if (perfSnapshots) PerfLogger.getBatteryPct(context) else 0
        if (perfSnapshots) PerfLogger.logSnapshot(context, "llm-start")
        val inferenceStartMs = System.currentTimeMillis()
        var tokenCount = 0

        llmDataSource.sendPrompt(prompt, maxTokens, grammar)
            .collect { token ->
                tokenCount++
                emit(token)
            }

        val durationMs = System.currentTimeMillis() - inferenceStartMs
        if (perfSnapshots) PerfLogger.logInference(context, durationMs, tokenCount, batteryBefore, PerfLogger.getBatteryPct(context))
    }
    .onCompletion { isGenerating = false }
    .flowOn(singleThreadDispatcher)

    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> =
        withContext(singleThreadDispatcher) {
            lastSystemPrompt = systemPrompt
            if (isModelLoaded) {
                llmDataSource.updateSystemPrompt(systemPrompt).also { result ->
                    if (result.isSuccess) {
                        Log.i(TAG, "System prompt updated successfully")
                    } else {
                        Log.e(TAG, "Failed to update system prompt: ${result.exceptionOrNull()}")
                    }
                }
            } else {
                Log.w(TAG, "Model not loaded, system prompt will be applied on next initialization")
                Result.success(Unit)
            }
        }

    // A load under way counts: the engine is taking the memory as this is read.
    override val isModelInMemory: Boolean
        get() = isModelLoaded || loadInProgress != null

    // Both paths are @Volatile, so any thread may read it; a caller reads it once its load is done.
    override val isSwitchedModelInMemory: Boolean
        get() = switchedModelInMemory()

    override suspend fun cleanup() {
        withContext(singleThreadDispatcher) {
            if (loadInProgress != null) {
                unloadWhenLoaded = true
                Log.i(TAG, "cleanup called during a model load; unloading as soon as it is loaded")
                return@withContext
            }
            if (!isModelLoaded) {
                Log.w(TAG, "cleanup called but model is not loaded, skipping")
                return@withContext
            }
            freeModel()
            loadedModelPath = null
        }
    }

    /**
     * Frees the model in memory through the engine, with its memory line in a debug build
     * ([memoryLines]). Throws what the engine throws; the caller decides what that means.
     */
    private suspend fun freeModel() {
        val name = loadedModelPath?.let { File(it).name } ?: "the model"
        firstAnswerOf = null
        memoryLines.measured(ModelMemoryLines.Event.RELEASE, name) { llmDataSource.cleanup() }
    }

    // Runs on singleThreadDispatcher, where every load is stored and begun, so none can be half
    // begun as this reads loadInProgress.
    override suspend fun forgetModel() {
        withContext(singleThreadDispatcher) {
            if (loadInProgress != null) {
                Log.i(TAG, "forgetModel called during a model load, keeping the model stored")
                return@withContext
            }
            lastModelPath = null
            lastSystemPrompt = null
            switchedModelPath = null
            Log.i(TAG, "Stored model forgotten")
        }
    }

    // Neither on singleThreadDispatcher nor waited for: the engine clears its files on its own
    // thread once the call under way there has ended, which can be a model load (5-15 s) or a
    // whole reply, and a delete must not wait for that. Between two engine calls only files go;
    // the state in memory that a step works on stays.
    override fun forgetPromptCache() {
        repositoryScope.launch {
            try {
                llmDataSource.forgetPromptCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "System prompt cache not cleared: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Emits thermal throttle signals based on [PowerManager] thermal status.
     *
     * minSdk = 34 guarantees the thermal API is always available — no version check needed.
     *
     * When the device reaches THERMAL_STATUS_SEVERE or above, emits [ThermalThrottle.Reduced]
     * with a 1.5x interval multiplier so callers reduce their inference frequency.
     *
     * The listener is automatically unregistered when the flow's collector is cancelled.
     */
    override val thermalThrottleFlow: Flow<ThermalThrottle> = callbackFlow {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Emit initial state immediately
        trySend(ThermalThrottle.Normal)

        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val throttle = when {
                // THERMAL_STATUS_SEVERE = 5, THERMAL_STATUS_CRITICAL = 6, THERMAL_STATUS_EMERGENCY = 7
                // Factor 1.5 extends the inference interval by 50% when hot,
                // balancing thermal protection with UX (2.0 was too aggressive).
                status >= PowerManager.THERMAL_STATUS_SEVERE ->
                    ThermalThrottle.Reduced(factor = 1.5f)
                else -> ThermalThrottle.Normal
            }
            trySend(throttle)
            Log.d(TAG, "Thermal status changed: $status → $throttle")
        }

        powerManager.addThermalStatusListener(listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }

    /** Kind and size of [grammar] for logs, never its text. */
    private fun describe(grammar: OutputGrammar?): String = when (grammar) {
        null -> "none"
        is OutputGrammar.Gbnf -> "gbnf(${grammar.text.length} chars)"
        is OutputGrammar.JsonSchema -> "json-schema(${grammar.schema.length} chars)"
    }

    /**
     * What the model reads in one call, as the first 8 hex digits of a SHA-256: of the system
     * prompt, the user turn and the grammar (its kind, "gbnf:" or "json-schema:", and its text;
     * nothing without one), each in UTF-8 and ended by a zero byte. Calls with the same hash read
     * the same, which a comparison of samplings needs; nothing of the text can be read from it.
     */
    internal fun promptHash(systemPrompt: String, userPrompt: String, grammar: OutputGrammar?): String {
        val grammarText = when (grammar) {
            null -> ""
            is OutputGrammar.Gbnf -> "gbnf:" + grammar.text
            is OutputGrammar.JsonSchema -> "json-schema:" + grammar.schema
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(systemPrompt, userPrompt, grammarText)) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * The user turn for [text]. A free-text call gets it quoted as a transcription. A call with a
     * [grammar] is structured and brings its own format, such as the todo prompt's segment lines
     * ("S3 [10:42] ..."), which its system prompt describes, so its text goes as is. A [lead], a
     * sentence about the text such as the language it is in, comes first, with a blank line, and
     * outside the quotes.
     */
    internal fun buildPrompt(text: String, grammar: OutputGrammar?, lead: String? = null): String {
        // The system prompt is held in llama.cpp's KV-cache via updateSystemPrompt()
        // and must NOT be duplicated here — doing so would inflate context usage
        // and cause the model to receive conflicting/doubled instructions.
        val turn = if (grammar != null) text else "Transcription: \"$text\""
        return if (lead.isNullOrBlank()) turn else "$lead\n\n$turn"
    }
}
