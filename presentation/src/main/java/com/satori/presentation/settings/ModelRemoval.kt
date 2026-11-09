package com.satori.presentation.settings

import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.LlmModelChoice
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/** What changed in the settings with a model removed, which the screen says. */
sealed interface SettingChange {
    /** "Better transcript after Stop" named the speech model and is off now. */
    data object BetterTranscriptOff : SettingChange

    /** The AI model chosen for the notes is gone, and [variant], on the phone too, writes them now. */
    data class NotesBy(val variant: LlmModelVariant) : SettingChange

    /** The AI model chosen for the notes is gone, and no other is on the phone: no notes until one is back. */
    data object NoNotes : SettingChange
}

/**
 * Removes a model with what is left of its download, and switches off the setting that names it,
 * for Advanced settings' Delete and for Models and storage alike: "Better transcript after Stop"
 * goes off with the model it names, and the AI model chosen for the notes gives way to another one
 * on the phone, or, with none, the stored path is forgotten, so readers that only check it for blank
 * report no model. The Malayalam model names no setting: the power key and Record as record in
 * English or German until it is back. The reader (0.8.0), which reads only Malayalam, goes with it,
 * every build of it: the two are one entry of Models and storage (design R7), and the first run
 * plans the reader again when the Malayalam model is back. File and setting change together; the
 * caller runs this to its end, also when the screen is left meanwhile.
 *
 * @param recommended The AI model that suits this phone, preferred for the notes when another goes
 */
internal class ModelRemoval(
    private val modelDownloadManager: ModelDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val editor: SettingsEditor,
    private val recommended: () -> LlmModelVariant
) {
    /** Removes the speech model [model]; the setting it switched off, if one named it. */
    suspend fun removeSpeech(model: SpeechModelId): SettingChange? {
        modelDownloadManager.deleteSttModel(model)
        if (model == SpeechModelId.INDICCONFORMER_ML) ReaderModelId.entries.forEach(modelDownloadManager::deleteReaderModel)
        if (settingsRepository.getSettings().first().transcribeAfterStop != model) return null
        editor.update { it.withoutSpeechModel(model) }
        return SettingChange.BetterTranscriptOff
    }

    /** Removes the AI model [variant]; what changed for the notes, if it was the one chosen. */
    suspend fun removeAi(variant: LlmModelVariant): SettingChange? {
        modelDownloadManager.deleteLlmModel(modelConfigForVariant(variant).llmFilename)
        if (settingsRepository.getSettings().first().llmModelVariant != variant) return null
        val other = LlmModelChoice.pick(
            selected = variant,
            recommended = recommended(),
            installed = modelDownloadManager.installedLlmVariants()
        )
        if (other == null) {
            editor.clearLlmModelPath()
            return SettingChange.NoNotes
        }
        val path = modelDownloadManager.getLlmModelPath(modelConfigForVariant(other).llmFilename) ?: return SettingChange.NoNotes
        editor.update { it.copy(llmModelVariant = other, llmModelPath = path) }
        return SettingChange.NotesBy(other)
    }
}
