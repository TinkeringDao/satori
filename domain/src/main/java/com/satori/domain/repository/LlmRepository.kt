package com.satori.domain.repository

import com.satori.domain.model.ThermalThrottle
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Local Large Language Model operations.
 *
 * Provides text generation capabilities using llama.android (llama.cpp).
 */
interface LlmRepository {
    /**
     * Initialize the LLM engine with a model.
     *
     * @param modelPath Absolute path to the GGUF model file
     * @param systemPrompt System prompt to set model behavior (optional)
     * @param loadImmediately If false, stores path/prompt but defers model loading.
     *   Use for LONG_MEETING where the model will be loaded lazily before each inference.
     * @return Result indicating success or failure
     */
    suspend fun initialize(
        modelPath: String,
        systemPrompt: String? = null,
        loadImmediately: Boolean = true
    ): Result<Unit>

    /**
     * Reload the model using the last stored modelPath and systemPrompt.
     * Idempotent: if the model is already loaded, returns success immediately (no I/O).
     * Used by LONG_MEETING to reload after unloading between inferences.
     *
     * A load already in progress is waited for rather than repeated. A load runs to the end
     * even if its caller is cancelled, since the native load can't be interrupted. If another
     * model's file is in memory (a [switchModel] that the stored path has replaced since), it is
     * unloaded first: the model loaded is always the one stored last.
     *
     * @return Result indicating success or failure
     */
    suspend fun reloadModel(): Result<Unit>

    /**
     * Puts the model in [modelPath] in memory in place of the one there, and makes it the model
     * [reloadModel] loads from then on. It is for work that wants another model than the live
     * notes, after Stop (see FinishingModelRepository).
     *
     * A load under way ends first, as the engine takes one at a time. The file is checked before
     * anything is unloaded, so a missing or damaged one leaves the model in memory as it was. The
     * model in memory, if it is another one, is then unloaded, and [modelPath] is loaded only if at
     * least as much memory is free as its file is big; otherwise, or if the load fails, no model is
     * in memory. On any failure the model stored before stays the one [reloadModel] loads.
     *
     * @return success once [modelPath] is in memory; a cancelled caller gets the cancellation
     */
    suspend fun switchModel(modelPath: String): Result<Unit>

    /**
     * [switchModel], with [extraFreeBytes] more memory free than the file is big as the bar for
     * the load, measured the same way once the model in memory is out: every load of Gemma 4 E2B
     * keeps that room free (LoadFinishingModelUseCase), so the load doesn't push the phone into
     * memory pressure, and beside a capture it is the room for its speech model too. Too little
     * fails with [NotEnoughMemoryException], and no model is in memory then. A negative margin
     * counts as none: the file's size must always be free.
     *
     * The default ignores the margin, for repositories that never measure memory.
     */
    suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> = switchModel(modelPath)

    /**
     * Forgets the model stored last ([initialize], [switchModel]), so [reloadModel] loads nothing
     * until one is stored again: once the finisher of recordings is done, Gemma 4 E2B must not be
     * what the next live note loads. Call it after [cleanup]. While a load runs it changes nothing,
     * as that load's caller still wants its model. The default does nothing.
     */
    suspend fun forgetModel() {}

    /**
     * Deletes the system prompts' encoded states that the engine keeps on disk from one model load
     * to the next (the prompt cache), for a recording that is deleted. The system prompts hold
     * nothing of a recording, so no such file holds any of it: this is a second safeguard. It
     * returns at once and never throws, and the engine clears the files on its own thread once the
     * call under way there has ended; a prompt sent again then costs one encode. The default does
     * nothing.
     */
    fun forgetPromptCache() {}

    /**
     * Returns true if available RAM is below the threshold needed to keep the LLM
     * loaded between inferences without risking kswapd pressure.
     * Used by LONG_MEETING to decide whether to unload after each inference.
     */
    fun isMemoryConstrained(): Boolean

    /**
     * Generate insight/response based on input text.
     *
     * @param text Input text (transcription) to analyze
     * @param systemPrompt System prompt instructions to include in this request (ensures small models remember instructions)
     * @param maxTokens Maximum number of tokens to generate; caller supplies a mode-appropriate budget
     * @param grammar Optional constraint on the output (see [OutputGrammar]); null = free text
     * @return Flow of generated tokens. Collect to build complete response.
     */
    fun generateInsight(
        text: String,
        systemPrompt: String? = null,
        maxTokens: Int = 512,
        grammar: OutputGrammar? = null
    ): Flow<String>

    /**
     * [generateInsight] with [lead] before [text] in the user message: a sentence about the text,
     * not part of it, such as the note that a transcript is in Malayalam and the answer is to be in
     * English. A call without a grammar gets its text quoted as a transcription, and [lead] stands
     * before that, outside the quotes, so it isn't read as something that was said. A blank [lead]
     * is none. Callers with no lead use the plain [generateInsight].
     *
     * The default puts [lead] and a blank line before [text], for repositories that send the text
     * as it is. A test double that delegates the interface to another one (`by`) hands this call
     * to that one, past its own [generateInsight]: it overrides this one too where a lead is sent.
     */
    fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?,
        lead: String?
    ): Flow<String> = generateInsight(if (lead.isNullOrBlank()) text else "$lead\n\n$text", systemPrompt, maxTokens, grammar)

    /**
     * Update the system prompt without reloading the model.
     *
     * @param systemPrompt New system prompt to set
     * @return Result indicating success or failure
     */
    suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit>

    /**
     * Switch to conservative thread count (2 threads) for subsequent model loads.
     * Takes effect on the next [reloadModel] or [initialize] call.
     */
    fun useConservativeThreads()

    /**
     * Call immediately after a successful model load.
     * Checks RAM while the model is in memory (accurate reading) and updates the
     * persisted cached state:
     * - If constrained: persists true and enables conservative threads (sticky-true).
     * - If not constrained and previously detected: resets to false.
     * This is the only correct point to evaluate RAM pressure because the model weight
     * footprint (~650 MB–1 GB) is fully accounted for in the reading.
     * Nothing is learned while a model put in by [switchModel] is in memory: the state is for
     * the live model, and readings next to a model three times its size would mislead it.
     */
    suspend fun checkAndCacheMemoryConstraint()

    /**
     * Call after each inference completes with the input prompt character count.
     * If the device is RAM-constrained at that moment, records [inputChars] as the
     * calibration point: future inferences with chars >= [inputChars] * safety margin
     * will proactively enable conservative threads before the next model load.
     * Takes the minimum with any previously recorded threshold (converges downward).
     * Like [checkAndCacheMemoryConstraint], it learns nothing while a model put in by
     * [switchModel] is in memory.
     */
    suspend fun recordConstrainedInference(inputChars: Int)

    /**
     * Returns true if [inputChars] meets or exceeds the learned char threshold above
     * which conservative threads should be proactively enabled before the next load.
     * Callers should invoke [useConservativeThreads] when this returns true.
     */
    fun isLargeContext(inputChars: Int): Boolean

    /**
     * Returns true from when inference is scheduled (before model reload) until the
     * flow completes or is cancelled. Covers the full inference window including
     * model loading (LONG_MEETING lazy load) so callers can reliably detect an
     * in-flight inference even before token generation begins.
     *
     * Set via [beginInference] / reset via [endInference] by the scheduling use case.
     * [generateInsight]'s onCompletion also resets it as a safety net for normal flow.
     */
    val isGenerating: Boolean

    /**
     * Marks the start of a scheduled inference cycle, including any preceding model
     * reload. Must be called before [reloadModel] so that [isGenerating] covers the
     * full window (model load + token generation). Pair with [endInference].
     */
    fun beginInference()

    /**
     * Resets [isGenerating] to false. Called in the finally block of the scheduling
     * use case to handle the case where the inference coroutine is cancelled before
     * [generateInsight] is ever collected (so onCompletion never fires).
     */
    fun endInference()

    /**
     * Clean up LLM resources and release memory.
     *
     * During a model load it returns at once, and the model is freed as soon as the load ends;
     * a load requested after this call keeps the model instead.
     */
    suspend fun cleanup()

    /**
     * Whether a model is in memory, or on its way there. A large speech model only runs while
     * none is (Transcribe again with Cohere Transcribe): the two together are more than the phone
     * has free. False for a repository that never holds a model, which is the default.
     */
    val isModelInMemory: Boolean
        get() = false

    /**
     * Whether the model in memory is one [switchModel] put there: Gemma 4 E2B, for the work after
     * Stop, and not the live model (Gemma 3 1B, or another small one). The search for to-dos holds
     * the small model's to-dos of a Malayalam line to a cue in it, which E2B's need not be
     * (ExtractTodosUseCase). False with no model in memory, and by default.
     */
    val isSwitchedModelInMemory: Boolean
        get() = false

    /**
     * Flow of device thermal state signals.
     *
     * Emits [ThermalThrottle.Normal] by default. When the device is thermally stressed,
     * emits [ThermalThrottle.Reduced] so callers can increase the inference interval
     * to reduce CPU/GPU load and protect the battery.
     *
     * Backed by [android.os.PowerManager.OnThermalStatusChangedListener] on API 29+.
     */
    val thermalThrottleFlow: Flow<ThermalThrottle>
}
