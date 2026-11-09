package com.satori.presentation.settings

import com.satori.domain.model.AiModelStatus
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpeechModelStatus
import com.satori.domain.modelfile.ModelFileState

/**
 * What the Models section of the Advanced settings shows: each speech model and each AI model,
 * and where it stands.
 *
 * @property speech One entry per [SpeechModelId], in the enum's order.
 * @property ai One entry per [LlmModelVariant], in the enum's order.
 * @property liveModelFiles What the check of Parakeet v3's files says (ModelDownloadManager's
 *   checkSttModel): only damaged or missing files offer it again ([repairOffered]). Intact until
 *   the check has run.
 */
data class ModelsUiState(
    val speech: List<SpeechModelState>,
    val ai: List<AiModelState>,
    val liveModelFiles: ModelFileState = ModelFileState.Intact
) {
    /**
     * Whether a model for the work after Stop (Gemma 4 E2B) is on the phone. Only then is the
     * switch for it shown: without the file there is nothing to choose.
     */
    val finishingModelOnPhone: Boolean
        get() = ai.any { !it.variant.writesLiveNotes && it.status is AiModelStatus.Installed }

    /**
     * Whether an AI model that writes the notes while recording is on the phone: one that can be
     * picked. Gemma 4 E2B alone is not, so the AI models' line then says to download one, as with
     * none at all, and the rows keep no room for selection dots.
     */
    val liveNotesModelOnPhone: Boolean
        get() = ai.any { it.variant.writesLiveNotes && it.status is AiModelStatus.Installed }

    /** Whether a speech model downloads now; one downloads at a time. */
    val speechDownloading: Boolean
        get() = speech.any { it.status is SpeechModelStatus.Downloading }

    /**
     * The models "Better transcript after Stop" can be set to: those on the phone that know
     * English or German, the languages of the recordings it makes a better transcript of, and
     * that the live recording doesn't use already. The Malayalam model is none of them: it
     * transcribes a Malayalam recording while it records and knows no other language, and no other
     * model knows Malayalam. Without any, the setting isn't shown.
     */
    val afterStopChoices: List<SpeechModelId>
        get() = speech.filter { it.model != SpeechModelId.LIVE && it.status == SpeechModelStatus.Installed }
            .map { it.model }
            .filter { it.understands("en") || it.understands("de") }

    /**
     * Whether [model] may be deleted: what a paused download of it left, or a model on the phone
     * while another one is on it too, so the app never ends up with none at all. Parakeet v3,
     * which every recording English or German transcribes with, never while it is whole: a phone
     * without it can't record, and two quiet buttons eight dp apart were all that stood between
     * the owner and that (the settings audit's V3-GUARD). The last one left stays whichever it is.
     */
    fun mayDelete(model: SpeechModelId): Boolean = when (statusOf(model)) {
        SpeechModelStatus.Paused -> true
        SpeechModelStatus.Installed ->
            (model != SpeechModelId.LIVE || OFFERS_LIVE_MODEL_ACTIONS_WHEN_HEALTHY) &&
                speech.any { it.model != model && it.status == SpeechModelStatus.Installed }
        else -> false
    }

    /**
     * Whether Parakeet v3 offers "Download again": only while its files are on the phone and the
     * check says they are damaged or missing, when downloading them again loses nothing.
     */
    val repairOffered: Boolean
        get() = statusOf(SpeechModelId.LIVE) == SpeechModelStatus.Installed &&
            (OFFERS_LIVE_MODEL_ACTIONS_WHEN_HEALTHY || liveModelFiles in REPAIRED)

    /**
     * What deleting the speech model [model] costs, for its question: recordings the power key and
     * Record as start in Malayalam are made in English or German until the Malayalam model is back.
     */
    fun deleteConsequence(model: SpeechModelId): DeleteConsequence =
        if (model == SpeechModelId.INDICCONFORMER_ML && statusOf(model) == SpeechModelStatus.Installed) {
            DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN
        } else {
            DeleteConsequence.NONE
        }

    /**
     * What deleting the AI model [variant] costs, for its question: deleting the only one on the
     * phone that writes live notes leaves new recordings without notes until one is back.
     */
    fun deleteConsequence(variant: LlmModelVariant): DeleteConsequence {
        val whole = ai.firstOrNull { it.variant == variant }?.status is AiModelStatus.Installed
        val another = ai.any { it.variant != variant && it.variant.writesLiveNotes && it.status is AiModelStatus.Installed }
        return if (variant.writesLiveNotes && whole && !another) DeleteConsequence.NO_NOTES else DeleteConsequence.NONE
    }

    private fun statusOf(model: SpeechModelId): SpeechModelStatus? = speech.firstOrNull { it.model == model }?.status

    companion object {
        /**
         * Whether a whole, healthy Parakeet v3 has Delete and Download again as before 0.7.0. No:
         * the owner's decision of 2026-10-16 (question 4 of the 0.7.0 brief, its default).
         * True brings both back as they were, deleting first, so recording doesn't work until the
         * download is done; the brief's swap beside the old files is not built.
         */
        const val OFFERS_LIVE_MODEL_ACTIONS_WHEN_HEALTHY = false

        /** The checks of Parakeet v3's files after which it is downloaded again. */
        private val REPAIRED = setOf(ModelFileState.Damaged, ModelFileState.Missing)
    }
}

/** What a question to delete a model says stops working. */
enum class DeleteConsequence {
    /** Nothing: what the model made stays, and it can be downloaded again. */
    NONE,

    /** The power key and Record as record in English or German until the Malayalam model is back. */
    MALAYALAM_IN_ENGLISH_OR_GERMAN,

    /** New recordings get no notes until a model for live notes is back. */
    NO_NOTES
}

/**
 * The settings once the speech model [model] is deleted: "Better transcript after Stop" is off if
 * it named that model, which can't run any more.
 */
internal fun AppSettings.withoutSpeechModel(model: SpeechModelId): AppSettings =
    if (transcribeAfterStop == model) copy(transcribeAfterStop = null) else this

/** One speech model and where it stands. */
data class SpeechModelState(val model: SpeechModelId, val status: SpeechModelStatus)

/** One AI model and where it stands. */
data class AiModelState(val variant: LlmModelVariant, val status: AiModelStatus)

/**
 * The words to recognize and the file they need: the vocabulary they are split with, which is
 * downloaded beside Parakeet v3 once there are words.
 *
 * @property words The words, as stored
 * @property vocabulary Where that file stands
 * @property liveModelOnPhone Whether Parakeet v3, which listens for the words, is on the phone
 * @property saveFailed Whether the last change of the words couldn't be stored (the phone is full,
 *   say): [words] are those from before it
 */
data class WordsUiState(
    val words: List<String> = emptyList(),
    val vocabulary: VocabularyStatus = VocabularyStatus.Missing,
    val liveModelOnPhone: Boolean = true,
    val saveFailed: Boolean = false
)

/** Where the vocabulary for words to recognize stands. */
sealed interface VocabularyStatus {
    /** It is on the phone, whole. */
    data object OnPhone : VocabularyStatus

    /** It is not on the phone and nothing downloads it. */
    data object Missing : VocabularyStatus

    /** It is being downloaded. */
    data object Downloading : VocabularyStatus

    /** Its download failed for [reason]. */
    data class Failed(val reason: DownloadFailure) : VocabularyStatus
}
