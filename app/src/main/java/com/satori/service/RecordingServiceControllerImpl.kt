package com.satori.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.service.RecordingServiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of RecordingServiceController.
 *
 * Responsibilities:
 * - Starts/stops RecordingService via Intent; a stop names the registration of its recording,
 *   so the stop of an older recording that comes late can't end a newer one
 * - Exposes session state from RecordingSessionManager
 * - Delegates transcription flow registration to SessionManager
 * - Relays the notification's stop requests from SessionManager
 * - Provides service running status check
 */
@Singleton
class RecordingServiceControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: RecordingSessionManager
) : RecordingServiceController {

    companion object {
        private const val TAG = "RecordingServiceCtrl"
    }

    override val sessionState: StateFlow<RecordingSessionState>
        get() = sessionManager.sessionState

    override val stopRequests: Flow<Unit>
        get() = sessionManager.stopRequests

    override fun registerTranscriptionFlow(flow: Flow<TranscriptionSegment>): Long {
        val registration = sessionManager.registerTranscriptionFlow(flow)
        Log.d(TAG, "Registered transcription flow $registration")
        return registration
    }

    override fun startService(): Boolean {
        Log.d(TAG, "Starting recording service")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Recording service start command sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording service", e)
            false
        }
    }

    override fun stopService(registration: Long) {
        Log.d(TAG, "Stopping recording service (registration $registration)")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
            putExtra(RecordingService.EXTRA_REGISTRATION, registration)
        }

        try {
            context.startService(intent)
            Log.d(TAG, "Recording service stop command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording service", e)
        }
    }

    override fun isServiceRunning(): Boolean {
        return sessionState.value is RecordingSessionState.Recording
    }

    override fun markStopping() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_SHOW_STOPPING
        }
        try {
            // A running foreground service accepts this even from the background.
            context.startService(intent)
        } catch (e: Exception) {
            // Only the notification text is at stake; the stop itself goes on.
            Log.w(TAG, "Could not show the stopping notification", e)
        }
    }
}
