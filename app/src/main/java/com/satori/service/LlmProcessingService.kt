package com.satori.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (DATA_SYNC type) that keeps LLM inference alive when the app
 * is sent to the background or the phone is locked.
 *
 * Architecture:
 * - Started by [LlmProcessingServiceControllerImpl] for the first piece of AI work that holds it
 *   and stopped for the last one: the finishing of recordings, which the recorder hands over while
 *   its recording service still runs, Rewrite notes, Transcribe again and the mind map.
 * - Each START and STOP carries a generation ([EXTRA_GENERATION]). A STOP that a newer START has
 *   overtaken is ignored while the service is in the foreground, and ends an instance that runs
 *   no START unless that START is on its way (ServiceStops.processingStop). A stop ends the
 *   service with stopSelfResult, so a START sent just before it survives it.
 * - Reports to the controller whether it is in the foreground (its `protected`): the finishing
 *   queue starts no step until it is, or until its JobScheduler job runs.
 * - Does not orchestrate inference itself; it only holds the foreground token and a partial
 *   wake lock, so the CPU keeps working with the screen off.
 * - While recordings are being finished its notification says how many, and the step under way
 *   ([FinishingNotificationText]); for other work it keeps the generic text.
 * - Android 15 and later give dataSync services 6 hours a day while the app stays out of sight,
 *   and end an app whose service runs on past that ([onTimeout]).
 *
 * Play Store compliance:
 * - Uses FOREGROUND_SERVICE_DATA_SYNC type (API 34 requirement).
 * - Shows a persistent, low-importance indeterminate-progress notification.
 * - Declares POST_NOTIFICATIONS in manifest (required for API 33+).
 */
@AndroidEntryPoint
class LlmProcessingService : Service() {

    companion object {
        private const val TAG = "LlmProcessingService"

        const val ACTION_START = "com.satori.action.START_LLM_PROCESSING"
        const val ACTION_STOP = "com.satori.action.STOP_LLM_PROCESSING"

        // On ACTION_START and ACTION_STOP: the generation of the START (ProcessingHolderSet), a Long
        const val EXTRA_GENERATION = "com.satori.extra.GENERATION"

        private const val WAKE_LOCK_TAG = "Satori:AiProcessing"

        // Only a backstop for a stop that never comes, set far above the time the AI is expected
        // to need for a long recording. The lock is released when the service stops.
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    @Inject
    lateinit var notificationManager: LlmProcessingNotificationManager

    @Inject
    lateinit var controller: LlmProcessingServiceControllerImpl

    // On the main thread, like every command: the notification is never posted after the service
    // has left the foreground, which would leave it behind.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: PowerManager.WakeLock? = null

    // Keeps the notification in step with the recordings being finished while in the foreground.
    private var noticeJob: Job? = null

    // What the notification says about them now; null for the generic text.
    private var shownFinishing: FinishingNotificationText? = null

    // The generation of the last START this service handled; 0 before the first.
    private var generation = 0L

    // Whether this instance is in the foreground: from a startForeground that went through until
    // it leaves (see stopProcessing).
    private var inForeground = false

    // The id of the last command, which stopSelfResult names (see leave).
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager.createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val commandGeneration = intent?.getLongExtra(EXTRA_GENERATION, 0L) ?: 0L
        when (intent?.action) {
            ACTION_START -> startProcessingForeground(commandGeneration)
            ACTION_STOP -> stopProcessing(commandGeneration)
        }
        // Do not restart if killed — the finishing queue resumes its jobs from the database.
        return START_NOT_STICKY
    }

    private fun startProcessingForeground(startGeneration: Long) {
        Log.d(TAG, "Starting LLM processing foreground service (start $startGeneration)")
        generation = startGeneration
        val finishing = controller.finishing.value
        try {
            startForeground(
                LlmProcessingNotificationManager.NOTIFICATION_ID,
                notificationManager.createNotification(finishing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The day's time for dataSync services is used up, or Android refused the start from
            // the background: the work goes on without the service, for as long as Android lets
            // the app run, and the finishing queue under its JobScheduler job.
            Log.w(TAG, "Foreground service refused, working without it", e)
            controller.onLeftForeground(startGeneration)
            stopSelfResult(lastStartId)
            return
        }
        inForeground = true
        shownFinishing = finishing
        acquireWakeLock()
        followFinishing()
        controller.onForeground(startGeneration)
    }

    private fun stopProcessing(stopGeneration: Long) {
        when (ServiceStops.processingStop(controller.stopHolds(stopGeneration), inForeground)) {
            ServiceStops.ProcessingStop.LEAVE -> {
                Log.d(TAG, "Stopping LLM processing service")
                leave()
            }
            // Work took the service after this STOP was sent. The service stays in the foreground
            // for it until the STOP of its own start, which ends it also if Android refused that
            // start.
            ServiceStops.ProcessingStop.STAY ->
                Log.i(TAG, "Stop of start $stopGeneration ignored, a newer start holds the service")
            // A newer START was sent, and this instance is not in the foreground for any: Android
            // refused that START, or it is still on its way. stopSelfResult ends the instance
            // only in the first case.
            ServiceStops.ProcessingStop.END_UNLESS_STARTED ->
                if (stopSelfResult(lastStartId)) {
                    Log.i(TAG, "Stop of start $stopGeneration: no start holds the service, it ends")
                } else {
                    Log.i(TAG, "A newer command is on its way, the service stays for it")
                }
        }
    }

    /**
     * Updates the notification whenever the recordings being finished change, until the service
     * leaves the foreground. Each update only replaces the text of the notification it shows.
     */
    private fun followFinishing() {
        if (noticeJob != null) return
        noticeJob = serviceScope.launch {
            controller.finishing.collect { finishing ->
                if (finishing == shownFinishing) return@collect
                shownFinishing = finishing
                notificationManager.update(finishing)
            }
        }
    }

    /**
     * The service's time for the day is used up (Android 15 and later), and Android ends the app
     * unless it stops within seconds. It stops; the work goes on for as long as Android lets the
     * app run.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time used up, stopping the service")
        leave()
    }

    /**
     * Leaves the foreground, which removes the notification, and stops, unless a command came in
     * after the last one this service handled: a START sent just before a STOP survives it.
     */
    private fun leave() {
        noticeJob?.cancel()
        noticeJob = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        controller.onLeftForeground(generation)
        if (!stopSelfResult(lastStartId)) Log.i(TAG, "A newer command is on its way, the service stays for it")
    }

    /** Keeps the CPU on while the AI works, also with the screen off. Safe to call again. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let {
            it.release()
            Log.d(TAG, "Wake lock released")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        controller.onLeftForeground(generation)
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent): IBinder? = null
}
