package com.satori.domain.usecase.transcription

import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Changes the language a recording is made in ([RecordingLanguage]): "Record as" while it is
 * recorded ([whileRecording]), and the same choice on its page ([onPage]). Whatever reads the
 * recording next goes by it: while it records, the next stretch of speech is transcribed by the
 * new language's live model; after Stop, the finishing, a rewrite of its notes and Transcribe again.
 *
 * Malayalam is a choice only while its model is on the phone ([SpeechModelRepository.isInstalled]):
 * the recording couldn't be transcribed in it otherwise. While it records, so is English or German,
 * whose live model takes over at the next utterance; on its page English or German always is, the
 * categories' own language, so a recording made in Malayalam can go back to it whatever is on the
 * phone (Transcribe again says what it can do with it). A Translate recording stays in English or
 * German, and no recording becomes one: its translation is written live by the small AI model,
 * which reads Malayalam poorly ([RecordingLanguage.writesLiveNotes]). [offered] says which ones can
 * change.
 *
 * @property transcriptionRepository Reads the recording and stores its language
 * @property speechModels Whether the language's model is on the phone
 */
class ChangeRecordingLanguageUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val speechModels: SpeechModelRepository
) {
    /** How a change ended. */
    sealed interface Outcome {
        /** The recording is in [language] now. */
        data class Changed(val language: RecordingLanguage) : Outcome

        /** It is in that language already: nothing was stored. */
        data object Unchanged : Outcome

        /**
         * Nothing changed: the recording is gone or translates, or the language's model isn't on
         * the phone (on the page, Malayalam's only).
         */
        data object Refused : Outcome

        /** Nothing changed: the recording couldn't be read, or the change couldn't be stored. */
        data class Failed(val cause: Exception) : Outcome
    }

    /**
     * The recording [sessionId], being recorded, is in [language] from now on. The transcript from
     * now on is the new language's live model's, so that model is stored as the transcript's maker
     * as well ([com.satori.domain.model.TranscriptionSession.transcriptModel]): the finishing
     * reads it as a live transcript either way, and a failure to store it only leaves the page's
     * caption naming the one before. Throws only a cancel.
     */
    suspend fun whileRecording(sessionId: String, language: RecordingLanguage): Outcome =
        change(sessionId, language, transcriptByItsModel = true)

    /**
     * The recording [sessionId], on its page, is in [language] from now on. Its transcript stays as
     * it was made, and so does the model it names: Transcribe again makes it anew. Throws only a
     * cancel.
     */
    suspend fun onPage(sessionId: String, language: RecordingLanguage): Outcome =
        change(sessionId, language, transcriptByItsModel = false)

    private suspend fun change(sessionId: String, language: RecordingLanguage, transcriptByItsModel: Boolean): Outcome = try {
        val session = transcriptionRepository.getSession(sessionId).first()
        when {
            session == null || !offered(session.mode) -> Outcome.Refused
            session.language == language -> Outcome.Unchanged
            needsItsModel(language, transcriptByItsModel) && !speechModels.isInstalled(language.liveModel) -> Outcome.Refused
            !transcriptionRepository.setLanguage(sessionId, language).getOrThrow() -> Outcome.Refused
            else -> {
                if (transcriptByItsModel) transcriptionRepository.setTranscriptModel(sessionId, language.liveModel.id)
                Outcome.Changed(language)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(e)
    }

    /**
     * Whether a change to [language] needs its live model on the phone: while the recording records
     * ([whileRecording]), as the capture transcribes with it from the next utterance on; on the page
     * only Malayalam, which is offered only with its model.
     */
    private fun needsItsModel(language: RecordingLanguage, whileRecording: Boolean): Boolean =
        whileRecording || language == RecordingLanguage.MALAYALAM

    companion object {
        /** Whether a recording in [mode] can change its language: every one but a translation, once its mode is known. */
        fun offered(mode: RecordingMode?): Boolean = mode != null && mode != RecordingMode.REAL_TIME_TRANSLATION
    }
}
