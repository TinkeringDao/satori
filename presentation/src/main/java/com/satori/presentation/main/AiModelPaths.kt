package com.satori.presentation.main

import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmModelChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Where the AI model of the live notes is on the phone, for AI work outside a recorder: the
 * finisher of recordings registers it before each recording, as the model the steps fall back to
 * when Gemma 4 E2B can't be loaded.
 */
fun interface AiModelPaths {

    /**
     * The file of the model that writes the live notes: the one chosen in Settings if it is on the
     * phone, otherwise another that is ([LlmModelChoice]), as the recorder picks it. Null when none
     * is on the phone; Gemma 4 E2B alone is none.
     */
    suspend fun livePath(settings: AppSettings): String?
}

/** [AiModelPaths] from the model files in the app's storage. Looks at the files on IO. */
class InstalledAiModelPaths @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager
) : AiModelPaths {

    override suspend fun livePath(settings: AppSettings): String? = withContext(Dispatchers.IO) {
        val variant = LlmModelChoice.pick(
            selected = settings.llmModelVariant,
            recommended = null,
            installed = modelDownloadManager.installedLlmVariants()
        ) ?: return@withContext null
        modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename)
    }
}
