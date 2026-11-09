package com.satori.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.satori.data.service.DownloadServiceRules
import com.satori.data.service.DownloadStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (DATA_SYNC type) that keeps model downloads alive when the app
 * is sent to the background.
 *
 * Architecture:
 * - Started by [com.satori.data.service.AndroidDownloadManager] when a download begins.
 * - Observes [DownloadStateManager] to show in its notification what downloads and how it goes
 *   ([DownloadNotificationText]), per part: speech recognition, Malayalam, the AI model, the reader.
 * - Runs while a download runs, a file is being checked, or one is held for what clears by itself:
 *   a recording, the network, Wi-Fi, the server, the space ([DownloadServiceRules.keepsServiceRunning]),
 *   so the app stays alive to go on. It stops for a download that waits for a tap, and when none
 *   is left.
 * - Holds a partial wake lock while bytes come or a file is checked: a speech model is up to
 *   2.9 GB, and with the screen off the CPU would otherwise sleep between the packets it reads.
 *   A held download needs no CPU, so it lets go of the lock meanwhile.
 * - Android 15 and later give dataSync services 6 hours a day while the app stays out of sight,
 *   and end an app whose service runs on past that ([onTimeout]).
 *
 * Play Store compliance:
 * - Uses FOREGROUND_SERVICE_DATA_SYNC type (API 34 requirement for data-sync work).
 * - Shows a persistent, low-importance progress notification; cannot be dismissed.
 * - Declares POST_NOTIFICATIONS in manifest (required for API 33+).
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"

        const val ACTION_START = "com.satori.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.satori.action.STOP_DOWNLOAD"

        private const val WAKE_LOCK_TAG = "Satori:ModelDownload"

        // Only a backstop for a stop that never comes: the time Android gives the service a day.
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /**
         * How long the service stays once nothing keeps it: a failed download is followed within
         * milliseconds by the first run's hold for it, which keeps the service, and Android
         * wouldn't let it start again from the background.
         */
        private const val STOP_AFTER_MS = 3_000L
    }

    @Inject
    lateinit var notificationManager: ModelDownloadNotificationManager

    @Inject
    lateinit var downloadStateManager: DownloadStateManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        notificationManager.createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownloadForeground()
            ACTION_STOP -> stopSelf()
        }
        // Not restarted by Android if killed: the first run's coordinator goes on with the
        // downloads at the next start of the app.
        return START_NOT_STICKY
    }

    /** The notification the downloads ask for now; null when none keeps the service. */
    private fun currentText(): DownloadNotificationText? = DownloadNotificationText.of(
        downloadStateManager.sttDownloadState.value,
        downloadStateManager.sttModel.value,
        downloadStateManager.llmDownloadState.value,
        downloadStateManager.llmVariant.value,
        downloadStateManager.readerDownloadState.value
    )

    private fun startDownloadForeground() {
        // In the foreground first, as Android asks of a service started for it, also when the
        // download ended before the service came up: the observer below then stops it at once.
        try {
            startForeground(
                ModelDownloadNotificationManager.NOTIFICATION_ID,
                notificationManager.createNotification(currentText() ?: DownloadNotificationText.STARTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The day's time for dataSync services is used up: the download goes on without the
            // service, for as long as Android lets the app run.
            Log.w(TAG, "Foreground service refused, downloading without it", e)
            stopSelf()
            return
        }

        // Follows the downloads: the notification says how they go, the wake lock is held while
        // bytes come, and the service stops once none has kept it for a moment.
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            combine(
                downloadStateManager.sttDownloadState,
                downloadStateManager.sttModel,
                downloadStateManager.llmDownloadState,
                downloadStateManager.llmVariant,
                downloadStateManager.readerDownloadState
            ) { _, _, _, _, _ -> currentText() }
                .distinctUntilChanged()
                .collectLatest { shown ->
                    if (shown == null) {
                        releaseWakeLock()
                        delay(STOP_AFTER_MS)
                        Log.d(TAG, "No active downloads — stopping service")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        // Nothing is posted after the notification went.
                        this@launch.cancel()
                        return@collectLatest
                    }
                    val running = DownloadServiceRules.isRunning(downloadStateManager.sttDownloadState.value) ||
                        DownloadServiceRules.isRunning(downloadStateManager.llmDownloadState.value) ||
                        DownloadServiceRules.isRunning(downloadStateManager.readerDownloadState.value)
                    if (running) acquireWakeLock() else releaseWakeLock()
                    notificationManager.updateNotification(
                        ModelDownloadNotificationManager.NOTIFICATION_ID,
                        notificationManager.createNotification(shown)
                    )
                }
        }
    }

    /**
     * The service's time for the day is used up (Android 15 and later), and Android ends the app
     * unless it stops within seconds. It stops; the downloads go on without it for as long as
     * Android lets the app run, and what they fetched stays. The first run's coordinator brings
     * the service back when the app is opened.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time used up, the downloads go on without the service")
        observerJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Keeps the CPU on while a download runs, also with the screen off. Safe to call again. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        releaseWakeLock()
        observerJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
