package com.satori.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.satori.ui.MainActivity
import com.satori.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for recording notification creation and updates.
 *
 * Features:
 * - Shows the elapsed time as a chronometer that Android keeps ticking
 * - Stop action button, which asks the recording screen to stop
 * - Tap notification to open app
 * - Low importance to minimize distraction
 * - Ongoing notification, posted again if the user swipes it away
 *
 * It never shows what is being said: besides the shade, a notification reaches the lock screen,
 * the cover screen and every app with notification access.
 */
@Singleton
class RecordingNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_OPEN_APP = 1002
        private const val REQUEST_CODE_STOP = 1003
        private const val REQUEST_CODE_REPOST = 1004
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Creates the notification channel for recording notifications.
     * Required for Android O (API 26) and above.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_recording_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates the notification shown while recording. Android ticks its chronometer, so it is
     * posted once per recording rather than per segment.
     *
     * @param startedAtMs Wall-clock time the recording started; the chronometer counts from it
     * @return The built notification
     */
    fun createRecordingNotification(startedAtMs: Long): Notification {
        // Asks the recording screen to stop (see RecordingService.onStopRequested)
        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_REQUEST_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = context.getString(R.string.notification_recording_title)
        return baseBuilder(title)
            .setWhen(startedAtMs)
            .setUsesChronometer(true)
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            // The same on a secure lock screen, minus Stop
            .setPublicVersion(baseBuilder(title).setWhen(startedAtMs).setUsesChronometer(true).build())
            .build()
    }

    /**
     * Creates the notification shown from a tap on Stop until the recording screen has saved the
     * last words and handed the recording to finishing, under a second as a rule and 15 s at most.
     * Finishing has its own notification (LlmProcessingNotificationManager).
     */
    fun createStoppingNotification(): Notification {
        val title = context.getString(R.string.g_notif_stopping)
        return baseBuilder(title)
            .setShowWhen(false)
            .setPublicVersion(baseBuilder(title).setShowWhen(false).build())
            .build()
    }

    /**
     * Creates the notification shown after the microphone is off while the place of the
     * recording is still read, half a minute at most (RecordingService.stayForPlace). Its icon is
     * the place, not the microphone.
     */
    fun createSavingPlaceNotification(): Notification {
        val title = context.getString(R.string.g_place_notif_saving)
        return baseBuilder(title)
            .setSmallIcon(R.drawable.ic_place)
            .setShowWhen(false)
            .setPublicVersion(baseBuilder(title).setSmallIcon(R.drawable.ic_place).setShowWhen(false).build())
            .build()
    }

    /**
     * The parts all notifications share. VISIBILITY_PRIVATE makes a secure lock screen show
     * the public version instead, which carries no actions.
     */
    private fun baseBuilder(title: String): NotificationCompat.Builder {
        // Create intent to open app when notification is tapped
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Android 14+ lets the user swipe even an ongoing service notification away; the service
        // then posts it again, so Stop and the timer stay reachable while recording.
        val repostPendingIntent = PendingIntent.getService(
            context,
            REQUEST_CODE_REPOST,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_REPOST_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(repostPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }

    /**
     * Updates an existing notification.
     *
     * @param notificationId The ID of the notification to update
     * @param notification The new notification content
     */
    fun updateNotification(notificationId: Int, notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }
}
