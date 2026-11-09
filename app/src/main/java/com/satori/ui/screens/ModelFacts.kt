package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.ui.R

// What the Advanced settings and the onboarding steps both say about the models: their names,
// sizes, and why a download failed.

/**
 * The size of the download of [variant], e.g. "1.1 GB", from the registry's bytes as the first run
 * and Models and storage write it ([modelSizeLabel]), so one model has one size everywhere. The two
 * the registry knows only by their least bytes keep their written size ([roughSize]).
 */
@Composable
internal fun modelSize(variant: LlmModelVariant): String {
    val spec = modelConfigForVariant(variant).llmSpec
    val locale = LocalConfiguration.current.locales[0]
    spec.bytes?.let { return modelSizeLabel(it, locale) }
    return roughSize(variant)?.let { stringResource(it) } ?: modelSizeLabel(spec.minBytes, locale)
}

/** The written size of an AI model the registry has no exact bytes of. */
@StringRes
internal fun roughSize(variant: LlmModelVariant): Int? = when (variant) {
    LlmModelVariant.IQ4_NL -> R.string.g_settings_size_iq4
    LlmModelVariant.QWEN3_5_Q8_0 -> R.string.g_settings_size_qwen
    LlmModelVariant.Q8_0, LlmModelVariant.GEMMA4_E2B_Q4_0 -> null
}

/**
 * The size of the download of the speech model [model] (its files together), from the registry's
 * bytes as the first run writes it: Parakeet v3, which every recording uses, unless another is named.
 */
@Composable
internal fun speechModelSize(model: SpeechModelId = SpeechModelId.LIVE): String =
    modelSizeLabel(SpeechModels.of(model).bytes, LocalConfiguration.current.locales[0])

/** The name of the speech model [model], as the settings and the details page show it. */
@Composable
internal fun speechModelName(model: SpeechModelId): String = stringResource(
    when (model) {
        SpeechModelId.PARAKEET_V3 -> R.string.g_speech_v3
        SpeechModelId.PARAKEET_V2_EN -> R.string.g_speech_v2
        SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_speech_cohere
        SpeechModelId.INDICCONFORMER_ML -> R.string.g_speech_ml
    }
)

/** What the speech model [model] is good for and how big it is, in one line. */
@Composable
internal fun speechModelDescription(model: SpeechModelId): String = stringResource(
    when (model) {
        SpeechModelId.PARAKEET_V3 -> R.string.g_speech_v3_desc
        SpeechModelId.PARAKEET_V2_EN -> R.string.g_speech_v2_desc
        SpeechModelId.COHERE_TRANSCRIBE -> R.string.g_speech_cohere_desc
        SpeechModelId.INDICCONFORMER_ML -> R.string.g_speech_ml_desc
    },
    speechModelSize(model)
)

@Composable
internal fun modelName(variant: LlmModelVariant): String = stringResource(
    when (variant) {
        LlmModelVariant.Q8_0 -> R.string.g_settings_model_q8
        LlmModelVariant.IQ4_NL -> R.string.g_settings_model_iq4
        LlmModelVariant.QWEN3_5_Q8_0 -> R.string.g_settings_model_qwen
        LlmModelVariant.GEMMA4_E2B_Q4_0 -> R.string.g_settings_model_e2b
    }
)

/** What the model is good for and how big it is, in one or two short sentences. */
@Composable
internal fun modelDescription(variant: LlmModelVariant): String = stringResource(
    when (variant) {
        LlmModelVariant.Q8_0 -> R.string.g_settings_model_q8_desc
        LlmModelVariant.IQ4_NL -> R.string.g_settings_model_iq4_desc
        LlmModelVariant.QWEN3_5_Q8_0 -> R.string.g_settings_model_qwen_desc
        LlmModelVariant.GEMMA4_E2B_Q4_0 -> R.string.g_settings_model_e2b_desc
    },
    modelSize(variant)
)

/** What went wrong, in a few words: "No internet connection". */
@Composable
internal fun DownloadFailure.title(): String = stringResource(
    when (this) {
        DownloadFailure.NO_CONNECTION -> R.string.g_onb_failed_connection_title
        DownloadFailure.NOT_ENOUGH_STORAGE -> R.string.g_onb_failed_storage_title
        DownloadFailure.SERVER -> R.string.g_onb_failed_server_title
        // The first run's words: the phone is online, Satori isn't let out.
        DownloadFailure.BLOCKED -> R.string.g_fr_err_blocked_title
        DownloadFailure.OTHER -> R.string.g_onb_failed_title
    }
)

/** What to do about it; [size] is the download's size, for the storage message. */
@Composable
internal fun DownloadFailure.body(size: String): String = when (this) {
    DownloadFailure.NO_CONNECTION -> stringResource(R.string.g_onb_failed_connection_body)
    DownloadFailure.NOT_ENOUGH_STORAGE -> stringResource(R.string.g_onb_failed_storage_body, size)
    DownloadFailure.SERVER -> stringResource(R.string.g_onb_failed_server_body)
    DownloadFailure.BLOCKED -> stringResource(R.string.g_fr_err_blocked_body)
    DownloadFailure.OTHER -> stringResource(R.string.g_onb_failed_body)
}
