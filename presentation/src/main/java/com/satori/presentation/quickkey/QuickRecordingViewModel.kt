package com.satori.presentation.quickkey

import android.util.Log
import androidx.lifecycle.ViewModel
import com.satori.domain.model.RecordRequest
import com.satori.domain.model.RecordTargets
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.presentation.main.ActiveRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * What the app needs to carry out a quick key press (see QuickKeyRequest in the app module): the
 * open recorder, and creating or discarding the recording a press starts.
 *
 * @property speechModels Whether the Malayalam speech model is on the phone, for a press set to
 *   record in Malayalam
 */
@HiltViewModel
class QuickRecordingViewModel @Inject constructor(
    private val activeRecorder: ActiveRecorder,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val discardSilentRecording: DiscardSilentRecordingUseCase,
    private val speechModels: SpeechModelRepository
) : ViewModel() {

    /** The open recorder and what it does, or null. */
    val recorder: StateFlow<ActiveRecorder.Snapshot?> = activeRecorder.current

    /** Whether a press is starting a recording ([ActiveRecorder.startInFlight]). */
    val startInFlight: StateFlow<Boolean> = activeRecorder.startInFlight

    /** Says whether a press is starting a recording, from the press until it has navigated. */
    fun setStartInFlight(inFlight: Boolean) = activeRecorder.setStartInFlight(inFlight)

    /**
     * Waits until the open recorder has saved its recording, at most [timeoutMs]
     * ([ActiveRecorder.awaitNotSaving]).
     *
     * @return The open recorder then, or null when none is open; still saving when it timed out
     */
    suspend fun awaitNotSaving(timeoutMs: Long): ActiveRecorder.Snapshot? = activeRecorder.awaitNotSaving(timeoutMs)

    /**
     * Creates the recording a press starts, in the category [categoryId], or the first category
     * when that one was deleted (an untrusted id is treated the same way), with the category's
     * mode and the note timing of the settings, in [language]: Malayalam only while its model is
     * on the phone, else English or German, as a category that translates always records. Null if
     * it can't be created.
     */
    suspend fun createRecording(categoryId: String, language: RecordingLanguage): String? = try {
        val settings = settingsRepository.getSettings().first()
        val target = RecordTargets.resolve(categoryId, categoryRepository.observeAll().first())
        val request = RecordRequest.of(target, settings)
        val recordsIn = recordingLanguage(language, request.mode)
        createSessionUseCase(
            name = null,
            mode = request.mode,
            outputLanguage = request.outputLanguage,
            insightStrategy = request.insightStrategy,
            categoryId = request.categoryId,
            language = recordsIn
        ).onFailure { Log.e(TAG, "Quick key: can't create the recording", it) }.getOrNull()?.id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Quick key: can't create the recording", e)
        null
    }

    /**
     * The language a press set to [wanted] records a recording in [mode] in: English or German for
     * a translation, and while the Malayalam model isn't on the phone (said in the field log).
     */
    private suspend fun recordingLanguage(wanted: RecordingLanguage, mode: RecordingMode): RecordingLanguage {
        if (wanted == RecordingLanguage.ENGLISH_OR_GERMAN || mode == RecordingMode.REAL_TIME_TRANSLATION) {
            return RecordingLanguage.ENGLISH_OR_GERMAN
        }
        val onPhone = speechModels.isInstalled(wanted.liveModel)
        if (!onPhone) Log.w(TAG, "Quick key: the speech model of $wanted isn't on the phone, recording in English or German")
        return wanted.usableWith(malayalamOnPhone = onPhone)
    }

    /**
     * Deletes a recording a press created but no recorder ever recorded in: one it did not open
     * (Satori was no longer idle), or one whose recorder never opened ([cancelStart]). A
     * recording that holds anything is kept.
     */
    suspend fun discard(sessionId: String) {
        // The reads behind it throw on a database error; a press must never crash the app.
        try {
            discardSilentRecording(sessionId, captured = false).onFailure { Log.w(TAG, "Quick key: can't delete $sessionId", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Quick key: can't delete $sessionId", e)
        }
    }

    /**
     * Counts the recorder a press is about to open for [sessionId] as starting until it opens,
     * also while the phone is locked and its screen can't come up yet ([ActiveRecorder.expect]).
     */
    fun expectRecorder(sessionId: String) = activeRecorder.expect(sessionId)

    /**
     * Ends the start of [sessionId] whose recorder a press has just closed: it no longer counts,
     * also before it has finished closing, which waits while the screen is off. If that recorder
     * never opened, nothing else deletes its recording, so this does.
     */
    suspend fun cancelStart(sessionId: String) {
        if (activeRecorder.cancelStart(sessionId)) discard(sessionId)
    }

    private companion object {
        const val TAG = "SatoriQuickKey"
    }
}
