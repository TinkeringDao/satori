package com.satori.data.firstrun

import com.satori.data.device.DeviceTierDetector
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which model each part of the first run is: Parakeet v3 and the Malayalam model, the AI model
 * that suits the phone (Gemma 3 1B Q8_0 on the owner's), Gemma 4 E2B as the bigger one, and the
 * reader's int8 build (0.8.0).
 */
@Singleton
class FirstRunModels @Inject constructor(private val deviceTier: DeviceTierDetector) {

    /** The AI model the first run downloads, the one that suits this phone; read once. */
    val aiModel: LlmModelVariant by lazy { deviceTier.detectRecommendedVariant() }

    /** The speech model [part] is, or null for the AI models and the reader. */
    fun speechModel(part: FirstRunPart): SpeechModelId? = when (part) {
        FirstRunPart.SPEECH -> SpeechModelId.PARAKEET_V3
        FirstRunPart.MALAYALAM -> SpeechModelId.INDICCONFORMER_ML
        FirstRunPart.AI_MODEL, FirstRunPart.BIGGER_AI_MODEL, FirstRunPart.READER -> null
    }

    /** The AI model [part] is, or null for the speech models and the reader. */
    fun aiModel(part: FirstRunPart): LlmModelVariant? = when (part) {
        FirstRunPart.AI_MODEL -> aiModel
        FirstRunPart.BIGGER_AI_MODEL -> LlmModelVariant.GEMMA4_E2B_Q4_0
        FirstRunPart.SPEECH, FirstRunPart.MALAYALAM, FirstRunPart.READER -> null
    }

    /** The reader's build [part] is (the int8 one, design D12), or null for the other parts. */
    fun readerModel(part: FirstRunPart): ReaderModelId? = ReaderModelId.INT8.takeIf { part == FirstRunPart.READER }
}
