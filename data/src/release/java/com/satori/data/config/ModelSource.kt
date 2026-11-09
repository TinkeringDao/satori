package com.satori.data.config

import com.satori.domain.model.ReaderModelId

/**
 * Where the model files come from: in a release build the registry's addresses, always. The debug
 * build's ModelSource can point them at a test server instead; none of that is in this build.
 */
internal object ModelSource {

    /** [spec] as the registry has it. */
    fun speech(spec: SpeechModelSpec): SpeechModelSpec = spec

    /** [config] as the registry has it. */
    fun llm(config: ModelConfig): ModelConfig = config

    /** The reader build [spec] of [id] as the registry has it. */
    @Suppress("UNUSED_PARAMETER")
    fun reader(id: ReaderModelId, spec: ReaderModelSpec): ReaderModelSpec = spec
}
