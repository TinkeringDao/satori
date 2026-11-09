package com.satori.domain.repository

import kotlinx.coroutines.currentCoroutineContext
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * How the AI model picks each token of a reply, as llama.cpp's sampler does it: [topK], then
 * [topP], then [minP] cut the candidates, the [temperature] spreads what is left, and one is drawn
 * at random. llama.cpp seeds each sampler from the system's random source: a reply with a grammar
 * gets a sampler of its own, and a free-text reply uses the one made at each model load and at
 * each change of sampling. At temperature 0 the most likely token left is taken: the same answer
 * for the same input (greedy). A reply with a grammar ([OutputGrammar]) is sampled the same way
 * among the tokens its grammar allows. No reply has a repeat penalty.
 *
 * The ranges are the engine's (0-2, 0-100 where 0 is off, 0-1, 0-1); a value outside them is a
 * mistake of the caller, so the constructor refuses it. Every AI call samples with [DEFAULT] unless
 * it is made inside an [AiSampling] ([currentSampling]).
 */
data class Sampling(
    val temperature: Float,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f
) {
    init {
        require(temperature in 0f..2f) { "temperature must be 0-2, not $temperature" }
        require(topK in 0..100) { "top-k must be 0-100, not $topK" }
        require(topP in 0f..1f) { "top-p must be 0-1, not $topP" }
        require(minP in 0f..1f) { "min-p must be 0-1, not $minP" }
    }

    /** For the field log: "temp 0.30 top-k 40 top-p 0.95 min-p 0.05", the same in every locale. */
    fun describe(): String = String.format(Locale.ROOT, "temp %.2f top-k %d top-p %.2f min-p %.2f", temperature, topK, topP, minP)

    companion object {
        /**
         * Every call's, kept in 0.7.2: llama.cpp's usual top-k, top-p and min-p, and a temperature
         * of 0.3 against the 1.0 both Gemma model cards give for every use. Each model load sets
         * the same natively (ai_chat.cpp's DEFAULT_SAMPLER_TEMP and the defaults of its
         * sampler_params, which the engine's and the data source's tests read from the C++), so a
         * call with it makes no native call. No measurement says that greedy decoding gives better
         * notes, names, to-dos or ideas on these models, and "Rewrite notes" and "Find to-dos and
         * ideas again" need an answer that can differ; the phone comparison decides 0.7.3.
         */
        val DEFAULT = Sampling(temperature = 0.3f)

        /** Temperature 0: always the most likely token, so the same input gives the same answer. */
        val GREEDY = Sampling(temperature = 0f)
    }
}

/**
 * The [sampling] of every AI call made in the coroutine that carries this element, and in its
 * children: `withContext(AiSampling(Sampling.GREEDY)) { ... }`. A debug build's rerun of a
 * finishing step compares temperatures with it on the same input. It lives as long as its
 * coroutine and no longer: unlike a setting or a debug property, nothing left behind can change the
 * owner's own notes, as each call outside it samples with [Sampling.DEFAULT] again. Work handed to
 * another scope, such as the finishing queue's, doesn't carry it.
 */
class AiSampling(val sampling: Sampling) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<AiSampling>

    override fun toString(): String = "AiSampling(${sampling.describe()})"
}

/** The sampling of an AI call made now: the calling coroutine's [AiSampling], else [Sampling.DEFAULT]. */
suspend fun currentSampling(): Sampling = currentCoroutineContext()[AiSampling]?.sampling ?: Sampling.DEFAULT
