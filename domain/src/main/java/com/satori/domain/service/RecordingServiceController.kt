package com.satori.domain.service

import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Platform-agnostic interface for controlling the recording service.
 * Provides a contract for starting/stopping recording service and observing session state.
 *
 * Android implementation will use foreground service with notifications.
 * Future iOS implementation will use appropriate platform mechanisms.
 */
interface RecordingServiceController {
    /**
     * Current state of the recording session.
     */
    val sessionState: StateFlow<RecordingSessionState>

    /**
     * Registers the shared transcription flow for service observation.
     * Must be called before starting the service.
     *
     * @param flow The shared transcription flow from the STT repository
     * @return The registration, which [stopService] names: a stop meant for an older recording
     *   then can't end a newer one
     */
    fun registerTranscriptionFlow(flow: Flow<TranscriptionSegment>): Long

    /**
     * Starts the recording service.
     * On Android, this starts a foreground service with notification.
     *
     * @return false if the platform refused to start it (for example, Android blocks starting a
     *   foreground service while the app is in the background); then nothing may be recorded.
     */
    fun startService(): Boolean

    /**
     * Stops the recording service.
     * Cleans up resources and removes notifications.
     *
     * @param registration What [registerTranscriptionFlow] returned for the recording that stops
     */
    fun stopService(registration: Long)

    /**
     * Checks if the recording service is currently running.
     *
     * @return true if service is active, false otherwise
     */
    fun isServiceRunning(): Boolean

    /**
     * Stop requests from outside the app's screens, such as the Stop button in the recording
     * notification. The recording screen collects them and runs its normal stop, so the last
     * insight, the AI name and the length are still saved. Emits nothing unless overridden.
     *
     * Handling a request must end in [stopService], also when there is nothing left to stop:
     * until then the notification says the recording is stopping, and on Android the service
     * gives up and stops by itself only after 30 s. While nothing collects, a request stops just
     * the service, as before.
     */
    val stopRequests: Flow<Unit>
        get() = emptyFlow()

    /**
     * The recording is stopping: the microphone is off, and the recorder saves the last words and
     * hands the recording to finishing (at most its 15 s drain guard) before [stopService]. On
     * Android the notification says so meanwhile.
     */
    fun markStopping() {}
}
