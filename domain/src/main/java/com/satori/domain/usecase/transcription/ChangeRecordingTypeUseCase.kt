package com.satori.domain.usecase.transcription

import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Changes what a recording is recorded as: "Record as" while it is being recorded, "Change type"
 * on its page. The recording takes the category and the mode that category records in, stored
 * together ([TranscriptionRepository.setCategory]), as a recording started in that category has
 * them. Whatever writes its notes next reads that mode: the live notes from their next interval,
 * the finishing after Stop (the meeting notes with their action items, say), a rewrite. What was
 * written before stays as it is.
 *
 * A Translate recording can't be changed, and no recording becomes one: a translation's notes are
 * its translation, written while it is said into the language chosen as it starts. [choices]
 * leaves those categories out.
 *
 * @property transcriptionRepository Reads the recording and stores what it is recorded as
 * @property categoryRepository Reads the category it is to be recorded as
 */
class ChangeRecordingTypeUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val categoryRepository: CategoryRepository
) {
    /** How a change ended. */
    sealed interface Outcome {
        /** The recording is recorded as [category] now. */
        data class Changed(val category: RecordingCategory) : Outcome

        /** It is recorded as that category already: nothing was stored. */
        data object Unchanged : Outcome

        /**
         * Nothing changed: the recording or the category is gone, one of them translates, or the
         * category no longer records in the mode it was read with.
         */
        data object Refused : Outcome

        /** Nothing changed: the recording or the category couldn't be read, or the change couldn't be stored. */
        data class Failed(val cause: Exception) : Outcome
    }

    /** Records the recording [sessionId] as the category [categoryId] from now on. Throws only a cancel. */
    suspend operator fun invoke(sessionId: String, categoryId: String): Outcome = try {
        val session = transcriptionRepository.getSession(sessionId).first()
        val category = categoryRepository.get(categoryId)
        when {
            session == null || category == null -> Outcome.Refused
            session.mode == RecordingMode.REAL_TIME_TRANSLATION || category.mode == RecordingMode.REAL_TIME_TRANSLATION -> Outcome.Refused
            session.categoryId == category.id && session.mode == category.mode -> Outcome.Unchanged
            else -> {
                val stored = transcriptionRepository.setCategory(sessionId, category.id, category.mode).getOrThrow()
                if (stored) Outcome.Changed(category) else Outcome.Refused
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(e)
    }

    companion object {
        /**
         * The categories a recording in [mode] can be recorded as, in their order: every one that
         * doesn't translate. None for a Translate recording, nor while its mode isn't known yet.
         */
        fun choices(mode: RecordingMode?, categories: List<RecordingCategory>): List<RecordingCategory> =
            if (mode == null || mode == RecordingMode.REAL_TIME_TRANSLATION) {
                emptyList()
            } else {
                categories.filter { it.mode != RecordingMode.REAL_TIME_TRANSLATION }
            }
    }
}
