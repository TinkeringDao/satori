package com.satori.domain.repository

/**
 * Constrains what the model may write in one reply; llama.cpp applies it while sampling. Describe
 * the expected format in the prompt as well: the grammar isn't shown to the model.
 *
 * The reply can still fail to parse, so parse it defensively:
 * - llama.cpp may reject the grammar (GBNF that doesn't parse, a schema outside the subset it
 *   converts). That reply is then free text, and only logcat says so.
 * - A reply cut off by the token budget is incomplete.
 *
 * With a grammar, [LlmRepository.generateInsight] passes its `text` to the model as is, so the
 * prompt brings its own format; without one, `text` is quoted as a transcription. The reply is
 * sampled without the repeat penalty from the settings, since a structured answer repeats keys
 * and values.
 */
sealed interface OutputGrammar {
    /** A GBNF grammar (llama.cpp's grammar format). */
    data class Gbnf(val text: String) : OutputGrammar

    /** A JSON schema, converted to a grammar by llama.cpp (supported subset only). */
    data class JsonSchema(val schema: String) : OutputGrammar
}
