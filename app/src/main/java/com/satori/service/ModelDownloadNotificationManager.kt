package com.satori.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.satori.ui.MainActivity
import com.satori.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the notification the download service shows: what downloads and how it goes
 * ([DownloadNotificationText]), in the words the first run and Settings use for it.
 *
 * A separate channel from the recording channel is used so users can control
 * download notifications independently via system notification settings.
 */
@Singleton
class ModelDownloadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "model_download_channel"
        const val NOTIFICATION_ID = 1004
        private const val REQUEST_CODE_OPEN_APP = 1005
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Creates the notification channel for model download notifications. */
    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.g_dl_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.g_dl_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Builds the notification for [text]: its title and line, and a bar with the percent, or one
     * that runs without it while a file is checked or the size isn't known yet. A held download
     * keeps the bar where it stopped.
     */
    internal fun createNotification(text: DownloadNotificationText): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val line = if (text.args.isEmpty()) context.getString(text.text) else context.getString(text.text, *text.args.toTypedArray())

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(text.title))
            .setContentText(line)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, text.percent ?: 0, text.percent == null)
            .build()
    }

    /**
     * Posts or updates the download notification.
     *
     * @param notificationId ID of the notification to update.
     * @param notification   The new notification content.
     */
    fun updateNotification(notificationId: Int, notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }
}
