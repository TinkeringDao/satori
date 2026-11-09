package com.satori.data.datasource

import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.Sampling
import kotlinx.coroutines.flow.Flow

/**
 * Data source interface for Local LLM operations.
 *
 * Provides low-level text generation functionality using llama.cpp.
 */
interface LlmDataSource {
    /**
     * Load an LLM model into memory.
     *
     * @param modelPath Absolute path to the GGUF model file
     * @param systemPrompt System prompt to set model behavior (optional)
     * @param nThreadsHint Number of inference threads; -1 = engine default (auto from CPU count).
     *   Pass a lower value (e.g. 2) for long sessions on RAM-constrained devices.
     * @return Result indicating success or failure
     */
    suspend fun loadModel(modelPath: String, systemPrompt: String? = null, nThreadsHint: Int = -1): Result<Unit>

    /**
     * How the last [loadModel] set the engine up (`llama.cpp repack=on mmap=on directio=off
     * swa_full=on kv=f16 fa=auto ctx=4096`), for the load's memory line in a debug build; null
     * before a load. The default has none.
     */
    val loadSettings: String?
        get() = null

    /**
     * Send a prompt to the LLM and stream the response.
     *
     * @param prompt Input prompt text
     * @param maxTokens Maximum number of tokens to generate. Callers should
     *   pass a mode-appropriate value (e.g. 256 for translation, 512+ for analysis).
     *   A grammar-constrained reply cut off by this budget is incomplete.
     * @param grammar Optional constraint on this response only (see [OutputGrammar]); null = free
     *   text. Later calls without one are free text again. A grammar llama.cpp rejects is logged
     *   without its text and the response is free text, so callers must still parse defensively.
     * @return Flow of generated tokens
     */
    fun sendPrompt(prompt: String, maxTokens: Int, grammar: OutputGrammar? = null): Flow<String>

    /**
     * Update the system prompt without reloading the model.
     *
     * @param systemPrompt New system prompt to set
     * @return Result indicating success or failure
     */
    suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit>

    /**
     * Clean up model and release resources.
     */
    suspend fun cleanup()

    /**
     * Deletes the system prompts' states the engine keeps on disk between model loads. Waits for
     * the engine's call under way, as the engine clears them on its own thread. The default keeps
     * none, so there is nothing to delete.
     */
    suspend fun forgetPromptCache() {}

    /**
     * Samples the replies from now on with [sampling], until another is given or a model load
     * sets the engine's own again ([Sampling.DEFAULT]'s). The repeat penalty stays off. Without a
     * model in memory it does nothing: the prompt that follows fails as it would. The default
     * samples as the engine does.
     */
    suspend fun useSampling(sampling: Sampling) {}
}
