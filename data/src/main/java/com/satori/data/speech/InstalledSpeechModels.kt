package com.satori.data.speech

import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.SpeechModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** [SpeechModelRepository] from the files on the phone and the sizes in the registry [SpeechModels]. */
class InstalledSpeechModels @Inject constructor(
    private val downloads: ModelDownloadManager
) : SpeechModelRepository {

    override suspend fun isInstalled(model: SpeechModelId): Boolean =
        withContext(Dispatchers.IO) { downloads.checkSttModel(model) == ModelFileState.Intact }

    override fun sizeBytes(model: SpeechModelId): Long = SpeechModels.of(model).bytes
}
