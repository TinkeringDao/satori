package com.satori.data.firstrun

import com.satori.data.config.ReaderModels
import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.ModelFileInspector
import com.satori.domain.firstrun.FilesCheck
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.SpeechFiles
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run's parts on the phone ([FirstRunFiles]), from the model files themselves. The
 * reader's files count as on the phone once they are whole and each was found once with its pinned
 * SHA-256, wherever they came from ([ModelDownloadManager.readerModelVerified]): the int8 build the
 * debug tools fetched before 0.8.0-dev3 counts, after one read of its 242 MB, and nothing downloads.
 */
@Singleton
class FirstRunModelFiles @Inject constructor(
    private val files: ModelDownloadManager,
    private val storage: StorageCheck,
    private val models: FirstRunModels
) : FirstRunFiles {

    /**
     * The registry's size. An AI model it knows only by the least a file of it may have (Gemma 3
     * 1B IQ4_NL, the one for phones with less memory) is planned and listed at its size as the
     * registry describes it, until its download says the exact one.
     */
    override fun bytes(part: FirstRunPart): Long {
        models.speechModel(part)?.let { return SpeechModels.of(it).bytes }
        models.readerModel(part)?.let { return ReaderModels.of(it).bytes }
        val variant = checkNotNull(models.aiModel(part))
        val spec = modelConfigForVariant(variant).llmSpec
        return spec.bytes ?: APPROXIMATE_BYTES[variant] ?: spec.minBytes
    }

    override fun speech(check: Boolean): SpeechFiles = when {
        !files.isSttModelDownloaded(SpeechModelId.PARAKEET_V3) -> SpeechFiles.MISSING
        check && files.checkSttModel(SpeechModelId.PARAKEET_V3) == ModelFileState.Intact -> SpeechFiles.CHECKED
        else -> SpeechFiles.PRESENT
    }

    override fun checked(part: FirstRunPart): Boolean {
        models.speechModel(part)?.let { return files.checkSttModel(it) == ModelFileState.Intact }
        models.readerModel(part)?.let { return files.readerModelVerified(it) }
        val path = files.getLlmModelPath(modelConfigForVariant(checkNotNull(models.aiModel(part))).llmFilename) ?: return false
        return ModelFileInspector.aiModel(File(path)) == ModelFileState.Intact
    }

    /** The reader's SHA-256, read once ([ModelDownloadManager.verifyReaderModel]); the other parts as [checked]. */
    override fun verify(part: FirstRunPart, stop: () -> Boolean): FilesCheck {
        val reader = models.readerModel(part) ?: return if (checked(part)) FilesCheck.PASSED else FilesCheck.FAILED
        return files.verifyReaderModel(reader, stop)
    }

    override fun bytesOnPhone(part: FirstRunPart): Long {
        models.speechModel(part)?.let { return files.speechModelBytesOnPhone(it) }
        models.readerModel(part)?.let { return files.readerModelBytesOnPhone(it) }
        val filename = modelConfigForVariant(checkNotNull(models.aiModel(part))).llmFilename
        val whole = files.getLlmModelPath(filename)?.let { File(it).length() } ?: 0L
        return whole + files.llmPartialBytes(filename)
    }

    override fun aiModelOnPhone(): Boolean = files.installedLlmVariants().any { it.writesLiveNotes }

    override fun discard(part: FirstRunPart) {
        models.speechModel(part)?.let { return files.deleteSttModel(it) }
        models.readerModel(part)?.let { return files.deleteReaderModel(it) }
        files.deleteLlmModel(modelConfigForVariant(checkNotNull(models.aiModel(part))).llmFilename)
    }

    override fun freeBytes(): Long = storage.freeBytes()

    private companion object {
        /** "~650 MB", as LowEndModelConfig describes the IQ4_NL file. */
        val APPROXIMATE_BYTES = mapOf(LlmModelVariant.IQ4_NL to 650_000_000L)
    }
}
