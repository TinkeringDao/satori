package com.satori.domain.fakes

import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** The file of the live model in these tests. */
const val LIVE_MODEL = "live.gguf"

/** The file of Gemma 4 E2B in these tests. */
const val LARGE_MODEL = "e2b.gguf"

/**
 * The choice of the model for the work after Stop, in memory: [path] is the file of Gemma 4 E2B,
 * null while it is not on the phone. [readFailure] makes reading the choice fail.
 */
class FakeFinishingModelRepository(
    var enabled: Boolean = true,
    var path: String? = null,
    var readFailure: Exception? = null
) : FinishingModelRepository {
    override fun observeEnabled(): Flow<Boolean> = flowOf(enabled)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override suspend fun enabledModelPath(): String? {
        readFailure?.let { throw it }
        return path?.takeIf { enabled }
    }
}

/**
 * Keeps one model in memory and one registered, under the rules of [LlmRepository]: [reloadModel]
 * loads the registered model in place of the one in memory, and [switchModel] registers and loads
 * a file, or on failure keeps the registration it had. Each prompt is answered from [answers] in
 * order (the last one repeats; null fails that call), by the model in memory, so a test can tell
 * which model did each step.
 *
 * @param switchFailure Makes every [switchModel] fail with it; [switchUnloads] says whether the
 *   model in memory was unloaded before the failure (too little free memory, a native error) or
 *   kept (a damaged file is refused before anything is unloaded)
 * @param log What happened, in order: "switch <file>", "load <file>", "prompt <file>", "unload";
 *   a test can pass the list its other events go to
 */
class TwoModelLlmRepository(
    private val answers: List<String?>,
    private val switchFailure: Throwable? = null,
    private val switchUnloads: Boolean = true,
    val log: MutableList<String> = mutableListOf()
) : LlmRepository by FakeLlmRepository() {

    /** The file [reloadModel] loads. */
    var registered: String? = LIVE_MODEL
        private set

    /** The file in memory, or null. */
    var loaded: String? = LIVE_MODEL
        private set

    /** The model in memory at each prompt, in call order. */
    val answeredBy = mutableListOf<String>()

    override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> {
        registered = modelPath
        return if (loadImmediately) reloadModel() else Result.success(Unit)
    }

    override suspend fun reloadModel(): Result<Unit> {
        val path = registered ?: return Result.failure(IllegalStateException("nothing registered"))
        if (loaded != path) {
            loaded = path
            log += "load $path"
        }
        return Result.success(Unit)
    }

    override suspend fun switchModel(modelPath: String): Result<Unit> {
        log += "switch $modelPath"
        switchFailure?.let { failure ->
            if (switchUnloads) loaded = null
            return Result.failure(failure)
        }
        registered = modelPath
        loaded = modelPath
        return Result.success(Unit)
    }

    /**
     * The switch, with the margin ignored, as [LlmRepository]'s default does for a repository that
     * never measures memory. Without this the delegation would hand it to [FakeLlmRepository],
     * which switches nothing.
     */
    override suspend fun switchModel(modelPath: String, extraFreeBytes: Long): Result<Unit> = switchModel(modelPath)

    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): Flow<String> = flow {
        val model = loaded ?: throw IllegalStateException("no model in memory")
        val answer = answers[minOf(answeredBy.size, answers.lastIndex)]
        answeredBy += model
        log += "prompt $model"
        emit(answer ?: throw IllegalStateException("native crash"))
    }

    /**
     * A call with a lead (a line of instructions), sent as [LlmRepository]'s default sends it.
     * Without this the delegation would hand it to [FakeLlmRepository], past this model's answers.
     */
    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?,
        lead: String?
    ): Flow<String> = generateInsight(if (lead.isNullOrBlank()) text else "$lead\n\n$text", systemPrompt, maxTokens, grammar)

    override suspend fun cleanup() {
        loaded = null
        log += "unload"
    }
}
