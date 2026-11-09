package com.satori.service

import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager that bridges ViewModel and Service to prevent AudioRecord duplication.
 *
 * Critical Component:
 * - ViewModel creates the STT stream using shareIn() and registers it here
 * - Service observes the same registered stream
 * - This ensures a single AudioRecord instance is shared between UI and Service
 * - Service passes stop requests from its notification back to the ViewModel
 *
 * Memory Efficiency:
 * - Stores only a reference to the Flow, NOT the transcription data
 * - Flow is hot (shared) and managed by ViewModel lifecycle
 */
@Singleton
class RecordingSessionManager @Inject constructor() {

    private val _sessionState = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    val sessionState: StateFlow<RecordingSessionState> = _sessionState.asStateFlow()

    // DROP_OLDEST: every request asks for the same thing, so a second tap may replace one not
    // yet collected, and tryEmit() never fails while someone is collecting.
    private val _stopRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stopRequests: Flow<Unit> = _stopRequests.asSharedFlow()

    private var transcriptionFlow: Flow<TranscriptionSegment>? = null

    // Counts registrations. A service that is shutting down clears only the registration it
    // started with, and a stop names the registration of its recording, so a stop quickly
    // followed by a new start can't end or lose the new one.
    @Volatile
    var registration: Long = 0L
        private set

    /**
     * Registers the shared transcription flow from ViewModel.
     * Must be called after ViewModel creates the stream with shareIn(), on the main thread.
     *
     * @param flow The shared transcription flow from SttRepository
     * @return The new [registration], which the recording's stop names
     */
    fun registerTranscriptionFlow(flow: Flow<TranscriptionSegment>): Long {
        transcriptionFlow = flow
        return ++registration
    }

    /**
     * Gets the registered transcription flow for Service observation.
     * Service collects this flow to update notifications.
     *
     * @return The registered transcription flow, or null if not registered
     */
    fun getTranscriptionFlow(): Flow<TranscriptionSegment>? {
        return transcriptionFlow
    }

    /**
     * Updates the current recording session state.
     * Called by ViewModel when state changes occur.
     *
     * @param state The new session state
     */
    fun updateState(state: RecordingSessionState) {
        _sessionState.value = state
    }

    /**
     * Asks the recording screen to stop the recording, as its own Stop button would.
     *
     * @return false if no screen collects [stopRequests]; the request is then dropped, and the
     *   caller has to stop on its own.
     */
    fun requestStop(): Boolean =
        _stopRequests.subscriptionCount.value > 0 && _stopRequests.tryEmit(Unit)

    /**
     * Clears the session data, unless a newer recording has registered its flow since.
     * Called when the recording service shuts down.
     *
     * @param ifRegistration The [registration] the stopping service started with
     */
    fun clearSession(ifRegistration: Long) {
        if (registration != ifRegistration) return
        transcriptionFlow = null
        _sessionState.value = RecordingSessionState.Idle
    }
}
