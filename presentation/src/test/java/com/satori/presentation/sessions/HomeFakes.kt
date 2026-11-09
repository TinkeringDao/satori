package com.satori.presentation.sessions

import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.AppSettings
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.presentation.ideas.ListedRecordings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// Test doubles for home (Part U1), beside the Ideas page's in IdeasFakes.kt. They hold only what
// home reads, and anything else fails the test that uses it.

private fun unused(): Nothing = error("not used by home")

/** The settings of a fresh install. */
class FreshSettings : SettingsRepository {
    override fun getSettings(): Flow<AppSettings> = flowOf(AppSettings())
    override suspend fun updateSettings(settings: AppSettings) = unused()
    override suspend fun setHomeCategoryId(id: String) = unused()
    override suspend fun setMemoryConstrainedDetected(detected: Boolean) = unused()
    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = unused()
    override suspend fun clearLlmModelPath() = unused()
}

/** No categories, as before the starter ones are stored. */
class NoCategories : CategoryRepository {
    override fun observeAll(): Flow<List<RecordingCategory>> = flowOf(emptyList())
    override suspend fun get(id: String): RecordingCategory? = unused()
    override suspend fun upsert(category: RecordingCategory) = unused()
    override suspend fun delete(id: String) = unused()
    override suspend fun ensureStarterCategories(names: Map<String, String>) = unused()
}

/** A finishing queue whose [statuses] the test sets; it finishes nothing. */
class StatusQueue : FinishingQueue {
    override val statuses = MutableStateFlow<List<FinishingStatus>>(emptyList())
    override val busy: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun captureStarted(sessionId: String) = unused()
    override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) = unused()
    override suspend fun cancel(sessionId: String) = unused()
    override fun skipBetterTranscript(sessionId: String) = unused()
    override fun resume(mayStartForeground: Boolean) = unused()
    override fun runUnderWakeJob(done: () -> Unit) = unused()
    override fun wakeJobStopped() = unused()
}

/**
 * The settings as home's Record as stores them: what the one-tap Record records as and in which
 * language, each write kept; the rest as a fresh install has it.
 */
class HomeSettings(settings: AppSettings = AppSettings()) : SettingsRepository {
    val settings = MutableStateFlow(settings)
    val languageWrites = mutableListOf<RecordingLanguage>()

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) = unused()

    override suspend fun setHomeCategoryId(id: String) {
        settings.value = settings.value.copy(homeCategoryId = id)
    }

    override suspend fun setHomeLanguage(language: RecordingLanguage) {
        languageWrites += language
        settings.value = settings.value.copy(homeLanguage = language)
    }

    override suspend fun setMemoryConstrainedDetected(detected: Boolean) = unused()
    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = unused()
    override suspend fun clearLlmModelPath() = unused()
}

/** The speech models on the phone: Parakeet v3, and the Malayalam model while [malayalam] says so. */
class SpeechModelsHere(@Volatile var malayalam: Boolean = false) : SpeechModelRepository {
    override suspend fun isInstalled(model: SpeechModelId): Boolean =
        model == SpeechModelId.PARAKEET_V3 || (model == SpeechModelId.INDICCONFORMER_ML && malayalam)

    override fun sizeBytes(model: SpeechModelId): Long = unused()
}

/** The recordings home creates, each with the arguments it was created with; none listed. */
class CreatedRecordings : TranscriptionRepository by ListedRecordings(emptyList()) {
    val created = mutableListOf<TranscriptionSession>()

    override suspend fun createSession(
        name: String?,
        mode: RecordingMode,
        outputLanguage: String?,
        insightStrategy: InsightStrategy,
        topic: String?,
        categoryId: String?,
        zoneId: String?,
        language: RecordingLanguage
    ): Result<TranscriptionSession> {
        val session = TranscriptionSession(
            id = "new${created.size + 1}", name = name, createdAt = 0L, lastModifiedAt = 0L, mode = mode,
            outputLanguage = outputLanguage, insightStrategy = insightStrategy, categoryId = categoryId, language = language
        )
        created += session
        return Result.success(session)
    }
}

