package com.satori.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.satori.data.place.RecordingPlaceCapture
import com.satori.data.service.WakeLockManager
import com.satori.domain.model.RecordingSessionState
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.usecase.place.TakeLocationFixUseCase
import dagger.hilt.android.AndroidEntryPoint
import com.satori.data.util.PerfLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service for maintaining recording when screen is off.
 *
 * Architecture:
 * - Does NOT manage AudioRecord directly
 * - Observes transcription flow from RecordingSessionManager to track the session state
 * - Shows an ongoing notification with the elapsed time and a Stop button
 * - Maintains wake lock to keep CPU active during processing
 * - Buzzes once as the microphone goes live and twice as the recording stops, however it stops,
 *   and three times when it could not start ([RecordingCues]), so that a recording started or
 *   stopped with the power key in a pocket is felt
 *
 * Lifecycle:
 * - Started by MainViewModel via RecordingServiceController as its capture begins
 * - Kept by the recorder after the microphone is off while it saves the last words (at most its
 *   15 s drain guard). The recorder then hands the recording to the finishing queue, which takes
 *   the AI's service (LlmProcessingService) while this one still runs, and only then stops this
 *   one. The notification's Stop button asks the recorder to stop (see onStopRequested)
 * - A stop names the registration of its recording and is ignored for another one, and the
 *   service ends with stopSelfResult: the next recording can start 1-2 s after Stop, before the
 *   stop of the last one has come, and neither stop ends it. A new start takes the service over
 *   from a recording that still runs here
 * - Told to stop while the place of its recording is still read, it stays up to 35 s more with
 *   the location type alone, until the place is in (see stayForPlace)
 * - Told to stop a recording that could not start before its vibration setting was read, it
 *   stays up to half a second for the setting, so the failure is still felt (see stayForCue)
 * - Service stops itself when app is killed (prevents orphaned recordings)
 */
@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START_RECORDING = "com.satori.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.satori.action.STOP_RECORDING"
        // The notification's Stop button
        const val ACTION_REQUEST_STOP = "com.satori.action.REQUEST_STOP"
        // The recording screen has begun stopping: show "stopping" while it saves the last words
        const val ACTION_SHOW_STOPPING = "com.satori.action.SHOW_STOPPING"
        // The user swiped the notification away (Android 14+ allows it): post it again
        const val ACTION_REPOST_NOTIFICATION = "com.satori.action.REPOST_NOTIFICATION"
        // On ACTION_STOP_RECORDING: the registration (RecordingServiceController) of the
        // recording that stops, a Long
        const val EXTRA_REGISTRATION = "com.satori.extra.REGISTRATION"

        // The registration of a service that has started no recording, and of a stop without one.
        private const val NO_REGISTRATION = -1L

        // Interval between periodic performance snapshots logged to PerfLogger.
        private const val PERF_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        // How long the recording screen has to act on a stop request before the service stops
        // on its own. Before stopping this service, the screen saves the last words (its drain
        // guard is 15 s) and hands the recording to the finishing queue.
        private const val STOP_REQUEST_TIMEOUT_MS = 30_000L

        // How long the service stays after a recording that could not start for its vibration
        // setting. The setting is read in milliseconds; this only covers a recorder that gave up
        // sooner, and keeps the service from waiting on a read that hangs.
        private const val CUE_STAY_MS = 500L

        // How long the service stays after its recording for the place: the wait for a new
        // position started with the recording, so this is more than enough for it to end.
        private const val PLACE_STAY_MS = TakeLocationFixUseCase.CURRENT_FIX_TIMEOUT_MS + 5_000L
    }

    @Inject
    lateinit var sessionManager: RecordingSessionManager

    @Inject
    lateinit var notificationManager: RecordingNotificationManager

    @Inject
    lateinit var wakeLockManager: WakeLockManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sttRepository: SttRepository

    @Inject
    lateinit var haptics: RecordingHaptics

    @Inject
    lateinit var recordingPlace: RecordingPlaceCapture

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectionJob: Job? = null
    private var perfHeartbeatJob: Job? = null
    private var stopRequestTimeoutJob: Job? = null
    // Reads the vibration setting for the recording that started last (see followCues)
    private var cueSettingJob: Job? = null
    // Follows the capture of the recording that started last, for its cues (see followCues)
    private var captureJob: Job? = null
    // Keeps the service after its recording until the setting for its cue is read (see stayForCue)
    private var cueStayJob: Job? = null
    // Drops the location type once the place of the recording is read (see dropLocationOnceRead)
    private var placeJob: Job? = null
    // Keeps the service after its recording until the place is read (see stayForPlace)
    private var placeStayJob: Job? = null
    // Whether the service holds the location type now; used on the main thread only
    private var holdsLocation = false
    // When to buzz; used on the main thread only
    private val cues = RecordingCues()
    private var startTime: Long = 0
    private var segmentCount: Int = 0
    // Set once the recording is being stopped; decides which notification a re-post shows
    private var stopping = false
    // The session manager registration this recording started with (see clearSession)
    private var registration = NO_REGISTRATION
    // The id of the last command, which stopSelfResult names (see leaveForeground)
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager.createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                Log.d(TAG, "Starting recording session")
                startRecording()
            }
            ACTION_STOP_RECORDING -> {
                val requested = intent.getLongExtra(EXTRA_REGISTRATION, NO_REGISTRATION)
                if (ServiceStops.endsRecording(requested, current = registration)) {
                    Log.d(TAG, "Stopping recording session")
                    stopRecording()
                } else {
                    // The stop of an older recording, which came after the next one started.
                    Log.i(TAG, "Stop for registration $requested ignored, recording registration $registration")
                    stopIfIdle()
                }
            }
            ACTION_REQUEST_STOP -> {
                Log.d(TAG, "Stop requested from the notification")
                onStopRequested()
            }
            ACTION_SHOW_STOPPING -> showStopping()
            ACTION_REPOST_NOTIFICATION -> repostNotification()
        }

        // Don't restart service if killed - prevents orphaned recordings
        return Service.START_NOT_STICKY
    }

    private fun startRecording() {
        // A recording that starts while the last one still runs here (its stop late or lost), or
        // while the last one's place is still read, takes the service over: nothing of the last
        // one keeps running next to it, and no stop asked for it can end this one.
        cancelRecordingJobs()
        cueSettingJob?.cancel()
        cueSettingJob = null
        cueStayJob?.cancel()
        cueStayJob = null
        placeStayJob?.cancel()
        placeStayJob = null

        // Initialize tracking
        startTime = System.currentTimeMillis()
        segmentCount = 0
        stopping = false
        registration = sessionManager.registration

        // Start foreground service with notification; its chronometer counts from startTime
        val notification = notificationManager.createRecordingNotification(startedAtMs = startTime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            holdsLocation = recordingPlace.reading.value && startForegroundWithLocation(notification)
            if (!holdsLocation) {
                startForeground(
                    RecordingNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
        } else {
            startForeground(RecordingNotificationManager.NOTIFICATION_ID, notification)
        }

        // Acquire wake lock to keep CPU active
        wakeLockManager.acquireWakeLock()

        // Log an initial snapshot, then repeat every PERF_HEARTBEAT_INTERVAL_MS. It returns at once:
        // its reads, which took 0.2-0.9 s here after a finishing began, run on PerfLogger's thread.
        PerfLogger.logSnapshot(this, "session-start")
        perfHeartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(PERF_HEARTBEAT_INTERVAL_MS)
                val elapsedMin = (System.currentTimeMillis() - startTime) / 60_000
                PerfLogger.logSnapshot(this@RecordingService, "heartbeat t+${elapsedMin}min")
            }
        }

        // Observe transcription flow from SessionManager
        val transcriptionFlow = sessionManager.getTranscriptionFlow()
        if (transcriptionFlow == null) {
            Log.e(TAG, "No transcription flow registered - stopping service")
            stopRecording()
            return
        }

        collectionJob = serviceScope.launch {
            transcriptionFlow
                .catch { e ->
                    Log.e(TAG, "Error collecting transcription flow", e)
                }
                .collect {
                    // Update tracking and session state
                    segmentCount++
                    sessionManager.updateState(
                        RecordingSessionState.Recording(
                            segmentCount = segmentCount,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    )

                    // The text is what was said, so only the count is logged.
                    Log.d(TAG, "Segment collected: #$segmentCount")
                }
        }

        followCues()
    }

    /**
     * Starts in the foreground with the microphone and the location type, while the place of the
     * recording is read (RecordingPlaceCapture). Android serves a position to an app that is off
     * screen only through a foreground service of that type, and a recording started with the
     * quick key often has its screen turned off at once.
     *
     * @return false if Android refused the location type; the caller starts with the microphone
     *   alone, so the place never keeps a recording from starting
     */
    private fun startForegroundWithLocation(notification: Notification): Boolean = try {
        startForeground(
            RecordingNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        dropLocationOnceRead()
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "No location type for this recording; its place is read only while the screen is on", e)
        false
    }

    /**
     * Drops the location type once the place of the recording is read, so the recording claims no
     * location it doesn't use.
     */
    private fun dropLocationOnceRead() {
        placeJob?.cancel()
        placeJob = serviceScope.launch(Dispatchers.Main) {
            recordingPlace.reading.first { !it }
            if (collectionJob == null) return@launch // Stopped meanwhile
            try {
                startForeground(
                    RecordingNotificationManager.NOTIFICATION_ID,
                    currentNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                holdsLocation = false
            } catch (e: RuntimeException) {
                // The location type then stays until the recording ends, which does no harm.
                Log.w(TAG, "Could not drop the location type", e)
            }
        }
    }

    /**
     * Buzzes for the recording that has just started, if the setting says so: once as its
     * microphone goes live, which the capture reports a moment after this start, also with the
     * speech model still loading; three times if this service is told to stop it before that and
     * no stop was asked for (see stopRecording). The setting is read for each recording, so a
     * change in Settings applies from the next one.
     */
    private fun followCues() {
        cues.onStarted()
        cueSettingJob = serviceScope.launch(Dispatchers.Main) {
            val vibrate = try {
                settingsRepository.getSettings().first().vibrateOnStartStop
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vibration setting not read, no buzz for this recording", e)
                false
            }
            play(cues.onSetting(vibrate))
        }
        // From the start of the service on, so the state the capture is in already counts too.
        captureJob = serviceScope.launch(Dispatchers.Main) {
            sttRepository.captureState.collect { state -> play(cues.onCapture(state)) }
        }
    }

    /**
     * Plays [cue], if there is one. Called while this service is still in the foreground, where
     * Android lets the app vibrate.
     */
    private fun play(cue: RecordingCues.Cue?) {
        if (cue == null) return
        Log.i(TAG, "Buzz $cue, ${System.currentTimeMillis() - startTime} ms after the service started")
        haptics.play(cue)
    }

    /**
     * The notification's Stop button. Stopping only this service would leave the microphone,
     * the transcription and the recording screen running, so the screen is asked to stop; its
     * stop saves the last words and the length, hands the recording to finishing, then stops this
     * service. With no screen to ask, the service stops itself as before.
     */
    private fun onStopRequested() {
        if (collectionJob == null) {
            // Not recording (a tap that arrived after the session ended): nothing to stop. While
            // the service stays for the place, it ends by itself.
            stopIfIdle()
            return
        }
        if (stopRequestTimeoutJob != null) return // Already asked; the screen is stopping
        if (!sessionManager.requestStop()) {
            Log.w(TAG, "No recording screen took the stop request; stopping the service only")
            // The owner asked for this stop: a recording that never went live didn't fail.
            play(cues.onStopAsked())
            stopRecording()
            return
        }
        showStopping()
        // The wake lock is held until this service stops, so its end can't rest on the screen alone.
        stopRequestTimeoutJob = serviceScope.launch(Dispatchers.Main) {
            delay(STOP_REQUEST_TIMEOUT_MS)
            Log.w(TAG, "Stop request not handled within ${STOP_REQUEST_TIMEOUT_MS / 1000} s; stopping the service")
            stopRecording()
        }
    }

    /**
     * The microphone is already off while the screen saves the last words; the notification says
     * so instead of still showing "recording".
     */
    private fun showStopping() {
        if (collectionJob == null) {
            stopIfIdle() // Not recording
            return
        }
        stopping = true
        // The microphone is off now: the record button, the notification and the power key all
        // come through here, before the recording is handed to finishing and this service stops.
        play(cues.onStopAsked())
        notificationManager.updateNotification(
            RecordingNotificationManager.NOTIFICATION_ID,
            notificationManager.createStoppingNotification()
        )
    }

    /** Puts back a notification the user swiped away, so Stop and the timer stay reachable. */
    private fun repostNotification() {
        if (collectionJob == null) {
            stopIfIdle() // Not recording: let it go
            return
        }
        notificationManager.updateNotification(RecordingNotificationManager.NOTIFICATION_ID, currentNotification())
    }

    /** The notification of the recording as it is now: recording, or stopping while it saves. */
    private fun currentNotification(): Notification = if (stopping) {
        notificationManager.createStoppingNotification()
    } else {
        notificationManager.createRecordingNotification(startedAtMs = startTime)
    }

    private fun stopRecording() {
        // A recording that ends without being stopped first (an error, the recorder closed while
        // recording) buzzes here, before this service leaves the foreground. So does one that
        // never went live and that no "stopping" came for: the recorder sends it before this
        // stop, so it has been handled by now.
        play(cues.onEnded())

        // Cancel flow collection, perf heartbeat, the stop request's timeout and the cues' capture.
        // The setting is still read for a cue that waits for it (see stayForCue).
        cancelRecordingJobs()
        PerfLogger.logSnapshot(this, "session-end")

        // Update session state
        sessionManager.updateState(RecordingSessionState.Stopping)

        if (stayForPlace()) {
            Log.d(TAG, "Recording session stopped, staying while its place is read")
            return
        }
        if (stayForCue()) {
            Log.d(TAG, "Recording session stopped, staying for its vibration setting")
            return
        }
        leaveForeground()

        Log.d(TAG, "Recording session stopped")
    }

    /**
     * Stays in the foreground after a recording that could not start before its vibration setting
     * was read, until the setting is in and its three pulses have played ([RecordingCues.awaitsSetting]),
     * [CUE_STAY_MS] at most. Ended now, the service would cancel the read with it, and the failure
     * would go unfelt. The read takes milliseconds, so this only happens when the recorder gave up
     * sooner than that.
     *
     * @return false when no cue waits for the setting; the service then stops as usual
     */
    private fun stayForCue(): Boolean {
        if (cueStayJob != null) return true // Staying already
        val settingRead = cueSettingJob
        if (!cues.awaitsSetting || settingRead == null || !settingRead.isActive) return false
        cueStayJob = serviceScope.launch(Dispatchers.Main) {
            val read = withTimeoutOrNull(CUE_STAY_MS) { settingRead.join() } != null
            if (!read) Log.w(TAG, "Vibration setting not read within $CUE_STAY_MS ms of the stop, no buzz for the failed start")
            cueStayJob = null
            leaveForeground()
            Log.d(TAG, "Recording service stopped after the cue")
        }
        return true
    }

    /**
     * Ends what runs for the recording: the collection, the heartbeat, the stop request's timeout,
     * the drop of the location type and the capture's cues.
     */
    private fun cancelRecordingJobs() {
        collectionJob?.cancel()
        collectionJob = null
        perfHeartbeatJob?.cancel()
        perfHeartbeatJob = null
        stopRequestTimeoutJob?.cancel()
        stopRequestTimeoutJob = null
        placeJob?.cancel()
        placeJob = null
        captureJob?.cancel()
        captureJob = null
    }

    /**
     * Stays in the foreground after the recording, with the location type alone and the wake
     * lock, while its place is still read (RecordingPlaceCapture), [PLACE_STAY_MS] at most. Satori
     * asks for no background location, so Android serves a position only to an app on screen or
     * to a foreground service with that type. After Stop the recorder is often off screen (the
     * quick key, the screen turned off at once), and LlmProcessingService, which takes over for
     * the AI, can't get the type from the background. A service that stays in the foreground
     * keeps the type it was allowed as the recording started, so the type is changed, not taken
     * again.
     *
     * @return false when no place is being read with the location type, or Android refused the
     *   change; the service then stops as usual
     */
    private fun stayForPlace(): Boolean {
        if (placeStayJob != null) return true // Staying already
        if (!holdsLocation || !recordingPlace.reading.value) return false
        try {
            startForeground(
                RecordingNotificationManager.NOTIFICATION_ID,
                notificationManager.createSavingPlaceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not keep the location type after the recording; its place is read only while the screen is on", e)
            return false
        }
        // The recording is over: home and the mind map see no recorder at work meanwhile.
        sessionManager.clearSession(ifRegistration = registration)
        placeStayJob = serviceScope.launch(Dispatchers.Main) {
            val read = withTimeoutOrNull(PLACE_STAY_MS) { recordingPlace.reading.first { !it } } != null
            if (!read) Log.w(TAG, "Place still read ${PLACE_STAY_MS / 1000} s after the recording, stopping anyway")
            placeStayJob = null
            leaveForeground()
            Log.d(TAG, "Recording service stopped after the place")
        }
        return true
    }

    /**
     * Releases the wake lock and leaves the foreground, which removes the notification, and stops,
     * unless a command came in after the last one this service handled: the start of the next
     * recording, sent 1-2 s after Stop, must not end with this one. stopSelf() would stop the
     * service also under a start that Android has taken and this service not yet seen.
     */
    private fun leaveForeground() {
        holdsLocation = false
        wakeLockManager.releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!stopSelfResult(lastStartId)) Log.i(TAG, "A newer command is on its way, the service stays for it")
    }

    /**
     * Ends a service that records nothing and stays for no place and no cue, which a late command
     * (a stop of an older recording, a tap on a notification that is gone) would otherwise leave
     * running. A stay ends the service by itself.
     */
    private fun stopIfIdle() {
        if (collectionJob == null && placeStayJob == null && cueStayJob == null) stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Ensure cleanup
        collectionJob?.cancel()
        wakeLockManager.releaseWakeLock()
        sessionManager.clearSession(ifRegistration = registration)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent): IBinder? {
        // Not a bound service
        return null
    }
}
