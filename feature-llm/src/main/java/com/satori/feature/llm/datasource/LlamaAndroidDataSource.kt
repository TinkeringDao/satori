package com.satori.feature.llm.datasource

import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.satori.data.datasource.LlmDataSource
import com.satori.data.monitor.AiModelLoadPacing
import com.satori.data.monitor.PacedLoadStoppedException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.Sampling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.android implementation of LlmDataSource.
 *
 * Wraps the InferenceEngine from llama.cpp-based library.
 *
 * @property inferenceEngine The llama.android inference engine
 * @property pacing The app's model loads, one at a time and at a pace (ModelLoadPacing), in every
 *   build: around llama.cpp's part of each load, after a cold load has dropped the file's cached
 *   pages, the one-load gate and the pre-read of what the load reads from its mapping at the pace
 *   the engine chose for the model, and the pace of its weight copy, either of which may stop the
 *   load when the phone stays short of memory (10 s in all): the load then fails with a
 *   [PacedLoadStoppedException] either way. Null loads as the engine does on its own, which only a
 *   test does.
 * @property warn Where [useSampling]'s warning goes: logcat, or a JVM test's list
 */
class LlamaAndroidDataSource(
    private val inferenceEngine: InferenceEngine,
    private val pacing: AiModelLoadPacing? = null,
    private val warn: (String) -> Unit = { Log.w(TAG, it) }
) : LlmDataSource {

    companion object {
        private const val TAG = "LlamaAndroidDataSource"
    }

    override suspend fun loadModel(modelPath: String, systemPrompt: String?, nThreadsHint: Int): Result<Unit> = runCatching {
        // If the engine crashed to Error state (e.g. OOM during a previous inference),
        // attempt a cleanup to restore a loadable state before proceeding.
        // Without this, calling loadModel() in Error state throws immediately.
        if (inferenceEngine.state.value is InferenceEngine.State.Error) {
            Log.w(TAG, "Engine in Error state before loadModel; attempting cleanup recovery")
            try {
                inferenceEngine.cleanUp()
                delay(300) // Give the engine time to reset its internal state
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup during Error recovery threw: ${e.message}")
            }
        }

        // Wait for initialization (from InferenceEngineImpl.kt state machine)
        inferenceEngine.state.first { it !is InferenceEngine.State.Initializing }

        // After recovery, if still in Error, we cannot proceed — propagate the cause.
        val stateAfterRecovery = inferenceEngine.state.value
        if (stateAfterRecovery is InferenceEngine.State.Error) {
            throw stateAfterRecovery.exception
        }

        // Load the model
        if (pacing == null) {
            inferenceEngine.loadModel(modelPath, nThreadsHint)
        } else {
            // One per load: its pre-read and its weight copy share its waits for memory.
            val paced = pacing.aiLoad(File(modelPath))
            // Set on llama.cpp's thread as the copy's pace stops the load.
            val copyStopped = AtomicBoolean(false)
            try {
                inferenceEngine.loadModel(modelPath, nThreadsHint, object : InferenceEngine.LoadHooks {
                    override suspend fun aroundNativeLoad(readIn: List<LongRange>?, preReadMbPerSecond: Int, nativeLoad: suspend () -> Unit) =
                        paced.aroundNativeLoad(readIn, preReadMbPerSecond, nativeLoad)

                    override fun weightPacer(mbPerSecond: Int): InferenceEngine.WeightPacer {
                        val copy = paced.weightCopy(mbPerSecond)
                        return object : InferenceEngine.WeightPacer {
                            override fun beforeTensor(doneBytes: Long): Boolean =
                                copy.beforeTensor(doneBytes).also { goOn -> if (!goOn) copyStopped.set(true) }

                            override fun ended(loaded: Boolean) = copy.ended()
                        }
                    }
                })
            } catch (e: IOException) {
                // The copy's stop comes back as the engine's own failed load: it is the app's stop
                // for want of memory, as the pre-read's is, which work after Stop tries again later.
                if (copyStopped.get() && e !is PacedLoadStoppedException) {
                    throw PacedLoadStoppedException(e.message ?: "The weight copy stopped the load", e)
                }
                throw e
            }
        }

        // Wait for model to be ready
        inferenceEngine.state.first { it !is InferenceEngine.State.LoadingModel }

        // Check if we're in error state
        when (val currentState = inferenceEngine.state.value) {
            is InferenceEngine.State.Error -> throw currentState.exception
            is InferenceEngine.State.ModelReady -> {
                // Set system prompt (use provided or default)
                val promptToUse = systemPrompt ?: """
                    You are an AI assistant analyzing real-time speech transcriptions.
                    Respond in the same language as the transcription.
                    Provide concise insights, suggestions, or brief summaries.
                    Keep responses under 100 words. Be actionable and helpful.
                """.trimIndent()
                inferenceEngine.setSystemPrompt(promptToUse)
            }
            else -> throw IllegalStateException("Unexpected state: ${currentState}")
        }
    }.onFailure { e ->
        // A cancelled caller is not a failed load: the native load may well have finished.
        // Wrapped in a Result, the cancellation would read as "no model" to the caller.
        if (e is CancellationException) throw e
    }

    override val loadSettings: String?
        get() = inferenceEngine.loadSettings

    override fun sendPrompt(prompt: String, maxTokens: Int, grammar: OutputGrammar?): Flow<String> {
        // inferenceEngine.sendUserPrompt returns Flow<String> (token stream).
        // maxTokens is supplied by the caller (SyncSttLlmUseCase) based on recording mode.
        // The grammar applies to this reply only; the engine drops it when the reply ends.
        return inferenceEngine.sendUserPrompt(
            message = prompt,
            predictLength = maxTokens,
            grammar = grammar?.toEngineGrammar()
        )
    }

    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = runCatching {
        // Wait for model to be ready before updating
        inferenceEngine.state.first {
            it !is InferenceEngine.State.LoadingModel &&
            it !is InferenceEngine.State.Initializing
        }

        when (val currentState = inferenceEngine.state.value) {
            is InferenceEngine.State.Error -> throw currentState.exception
            is InferenceEngine.State.ModelReady -> {
                inferenceEngine.setSystemPrompt(systemPrompt)
            }
            else -> throw IllegalStateException("Cannot update system prompt in state: $currentState")
        }
    }

    override suspend fun cleanup() {
        inferenceEngine.cleanUp()
    }

    override suspend fun forgetPromptCache() {
        inferenceEngine.forgetPromptCache()
    }

    /**
     * The engine's sampler set to [sampling], with the repeat penalty off as it always was; the
     * engine changes nothing when it samples so already. Without a model in memory, nothing is
     * set: the prompt that follows fails as it always did, with the engine's own words. So does
     * one whose model went out between the look and the engine's own check.
     */
    override suspend fun useSampling(sampling: Sampling) {
        if (!inferenceEngine.state.value.isModelLoaded) return
        try {
            inferenceEngine.setSamplerConfig(sampling.temperature, sampling.topK, sampling.topP, sampling.minP, REPEAT_PENALTY_OFF)
        } catch (e: CancellationException) {
            // A CancellationException is an IllegalStateException too: the caller's cancel goes on.
            throw e
        } catch (e: IllegalStateException) {
            warn("Sampling not set, no model in memory: ${e.message}")
        }
    }
}

/** The repeat penalty of every reply: off, as ai_chat.cpp's sampler_params has it; a grammar reply has none anyway. */
private const val REPEAT_PENALTY_OFF = 1.0f

/** The engine's form of [OutputGrammar]; the text passes through unchanged. */
internal fun OutputGrammar.toEngineGrammar(): InferenceEngine.Grammar = when (this) {
    is OutputGrammar.Gbnf -> InferenceEngine.Grammar.Gbnf(text)
    is OutputGrammar.JsonSchema -> InferenceEngine.Grammar.JsonSchema(schema)
}
