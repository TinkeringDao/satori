package com.satori.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.satori.ui.MainActivity
import com.satori.ui.NotificationTaps
import com.satori.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the notification shown while the on-device LLM works ([LlmProcessingService]).
 *
 * While recordings are being finished in the background it says how many and the step under way
 * ([FinishingNotificationText]); for other AI work (Rewrite notes, the mind map) it keeps the
 * generic text. It never shows a recording's name or what was said: besides the shade, a
 * notification reaches the lock screen, the cover screen and every app with notification access.
 * A tap opens Satori.
 *
 * Once a recording's notes are ready while the app is out of sight, a notification of their own
 * says so ([showNotesReady]), on a channel of its own that pings; [FirstRunCues] decides when. A
 * tap on it opens that recording's page.
 */
@Singleton
class LlmProcessingNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "llm_processing_channel"
        const val NOTIFICATION_ID = 1006
        private const val REQUEST_CODE_OPEN_APP = 1007

        const val NOTES_READY_CHANNEL_ID = "notes_ready_channel"

        /** One for all recordings: a newer one replaces it, so they never pile up. */
        const val NOTES_READY_NOTIFICATION_ID = 1008
        private const val REQUEST_CODE_NOTES_READY = 1009

        /**
         * String extra on the [MainActivity] intent of a tapped "Your notes are ready": the id of
         * the recording whose page opens. The intent has FLAG_ACTIVITY_NEW_TASK, CLEAR_TOP and
         * SINGLE_TOP, so a running MainActivity receives it in onNewIntent and a new one in onCreate.
         */
        const val EXTRA_NOTES_SESSION_ID = "com.satori.extra.NOTES_SESSION_ID"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Creates the notification channel for LLM processing notifications. */
    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_llm_processing_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_llm_processing_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Builds the LLM processing notification.
     *
     * @param finishing The recordings being finished; null for the generic text
     */
    internal fun createNotification(finishing: FinishingNotificationText?): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_insights)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            // A new step replaces the text in place.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true) // Indeterminate — we don't know token count in advance
        if (finishing == null) {
            builder
                .setContentTitle(context.getString(R.string.notification_llm_processing_title))
                .setContentText(context.getString(R.string.notification_llm_processing_text))
        } else {
            builder.setContentTitle(context.resources.getQuantityString(finishing.title, finishing.count, finishing.count))
            finishing.stepText?.let { builder.setContentText(context.getString(it)) }
        }
        return builder.build()
    }

    /**
     * Shows [finishing] in the notification of the running service. Only [LlmProcessingService]
     * calls this, while it is in the foreground: a notification posted after that would stay
     * behind on its own.
     */
    internal fun update(finishing: FinishingNotificationText?) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(finishing))
    }

    /**
     * Whether "Your notes are ready" would reach the owner: the app's notifications are allowed
     * (on Android 13 and later only once he said yes to them) and their channel isn't switched off.
     */
    fun notesReadyAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = notificationManager.getNotificationChannel(NOTES_READY_CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Posts "Your notes are ready" for the recording [sessionId], which goes when tapped and opens
     * that recording's page ([EXTRA_NOTES_SESSION_ID]). It names no recording and holds nothing
     * said, as it reaches the lock screen and the cover screen too. As there is one for all
     * recordings, the newest one's tap opens the newest recording.
     *
     * The intent is shaped like the launcher's (ACTION_MAIN, CATEGORY_LAUNCHER, no identifier), as a
     * tapped reminder's is: when a tap creates Satori's task this intent becomes the task's root,
     * and a later tap on the launcher icon would otherwise stack a second MainActivity.
     */
    fun showNotesReady(sessionId: String) {
        val channel = NotificationChannel(
            NOTES_READY_CHANNEL_ID,
            context.getString(R.string.g_notif_channel_notes_ready),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.g_notif_channel_notes_ready_desc) }
        notificationManager.createNotificationChannel(channel)
        // What the intent holds is NotificationTaps', which reads it back as the tap comes in.
        val tap = NotificationTaps.notesReadyTap(sessionId)
        val intent = Intent(tap.action).setClass(context, MainActivity::class.java).addFlags(tap.flags)
        tap.categories.forEach { intent.addCategory(it) }
        tap.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE_NOTES_READY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, NOTES_READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_insights)
            .setContentTitle(context.getString(R.string.g_notif_notes_ready))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTES_READY_NOTIFICATION_ID, notification)
    }
}
