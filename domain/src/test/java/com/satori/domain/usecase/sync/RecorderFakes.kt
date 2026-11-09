package com.satori.domain.usecase.sync

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A turn at the engine that is free while [free], and writes to [events] what is done with it:
 * "take" or "refused", "prepare" and "release". [onTake] runs as the turn is taken. A turn taken
 * and not given back would hold the engine for good: [taken] and [released] say how often each
 * happened.
 */
class ScriptedTurn(private val events: MutableList<String>, var free: Boolean = true) : LiveNoteTurn {
    var taken = 0
        private set
    var released = 0
        private set
    var onTake: () -> Unit = {}

    override fun tryTake(): Boolean {
        events += if (free) "take" else "refused"
        if (free) {
            taken++
            onTake()
        }
        return free
    }

    override suspend fun prepare() {
        events += "prepare"
    }

    override suspend fun release() {
        events += "release"
        released++
    }
}

/**
 * [fake], writing to [events] the calls of the live notes that touch the engine: "reload",
 * "updateSystemPrompt" and "generate". A generation waits for [hold] when one is given, and
 * [generating] completes as the first one begins.
 */
class LoggingLlm(
    private val events: MutableList<String>,
    private val fake: FakeLlmRepository,
    private val hold: CompletableDeferred<Unit>? = null
) : LlmRepository by fake {
    val generating = CompletableDeferred<Unit>()

    override suspend fun reloadModel(): Result<Unit> {
        events += "reload"
        return fake.reloadModel()
    }

    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> {
        events += "updateSystemPrompt"
        return fake.updateSystemPrompt(systemPrompt)
    }

    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): Flow<String> = flow {
        events += "generate"
        generating.complete(Unit)
        hold?.await()
        emitAll(fake.generateInsight(text, systemPrompt, maxTokens, grammar))
    }
}
