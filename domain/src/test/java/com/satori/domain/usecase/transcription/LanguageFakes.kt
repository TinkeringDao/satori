package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository

// Test doubles for the language a recording is made in ("Record as", on its page, the quick key).

/**
 * [recordings], storing a recording's language as the database does: for a recording that exists,
 * as it is given, each write kept in [languageWrites]. With [languageFailure] set it fails, and
 * the recording keeps what it had.
 */
class LanguagesStored(val recordings: FakeTranscriptionRepository) : TranscriptionRepository by recordings {
    val languageWrites = mutableListOf<Pair<String, RecordingLanguage>>()
    var languageFailure: Exception? = null

    override suspend fun setLanguage(sessionId: String, language: RecordingLanguage): Result<Boolean> {
        languageFailure?.let { return Result.failure(it) }
        languageWrites += sessionId to language
        val current = recordings.session?.takeIf { it.id == sessionId } ?: return Result.success(false)
        recordings.session = current.copy(language = language)
        return Result.success(true)
    }
}

/** The speech models on the phone: [installed], which a test can change as a download or a deletion does. */
class SpeechModelsOnPhone(vararg installed: SpeechModelId) : SpeechModelRepository {
    val installed: MutableSet<SpeechModelId> = installed.toMutableSet()

    override suspend fun isInstalled(model: SpeechModelId): Boolean = model in installed

    override fun sizeBytes(model: SpeechModelId): Long = 0L
}
