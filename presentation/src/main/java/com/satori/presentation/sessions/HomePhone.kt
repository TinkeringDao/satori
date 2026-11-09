package com.satori.presentation.sessions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.LlmModelVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What home's strip reads from the phone for its offers (com.satori.domain.firstrun.NudgeFacts),
 * as it was when last read.
 *
 * @property notificationsAllowed The app may post notifications
 * @property notificationsAskable Android's notification window can ask: Android 13 or later, and
 *   the permission isn't granted
 * @property notificationsAsked The window was asked for before ([NotificationAsk])
 * @property notesMissed How many times a recording's notes were ready and "Your notes are ready"
 *   couldn't be posted, as notifications aren't allowed ([NotificationAsk.markNotesMissed])
 * @property charging The phone is charging
 * @property freeBytes The free space where the models go
 * @property biggerModelOnPhone Gemma 4 E2B's file is on the phone, or part of it from a download
 *   that stopped
 */
data class PhoneFacts(
    val notificationsAllowed: Boolean = false,
    val notificationsAskable: Boolean = false,
    val notificationsAsked: Boolean = false,
    val notesMissed: Int = 0,
    val charging: Boolean = false,
    val freeBytes: Long = 0L,
    val biggerModelOnPhone: Boolean = false
)

/** The phone as home's strip reads it, and the clock it reads it by. */
interface HomePhone {

    /** The wall clock (epoch ms): a nudge goes a day after it was shown, also across restarts. */
    fun nowMs(): Long

    /** The phone's facts now. It looks at files, so it reads off the main thread. */
    suspend fun read(): PhoneFacts

    /** Android's notification window is about to be asked for, from the ping offer ([NotificationAsk]). */
    fun markNotificationsAsked()

    /**
     * Each change of the one flag the ping and the to-do reminders' sheet share ([NotificationAsk]):
     * the sheet answered, or a window was asked for; and each notes-ready notification that couldn't
     * be posted. None by default.
     */
    fun notificationAskChanges(): Flow<Unit> = emptyFlow()
}

/** [HomePhone] from Android: the notification settings, the battery, the free space and the model files. */
class AndroidHomePhone @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelDownloadManager
) : HomePhone {

    override fun nowMs(): Long = System.currentTimeMillis()

    override suspend fun read(): PhoneFacts = withContext(Dispatchers.IO) {
        val battery = context.getSystemService(BatteryManager::class.java)
        val biggerModel = modelConfigForVariant(LlmModelVariant.GEMMA4_E2B_Q4_0).llmFilename
        PhoneFacts(
            notificationsAllowed = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true,
            // Android 12 has no run-time permission: there notifications can only be turned on in settings.
            notificationsAskable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
            notificationsAsked = NotificationAsk.asked(context),
            notesMissed = NotificationAsk.notesMissed(context),
            charging = battery?.isCharging == true,
            freeBytes = freeBytes(),
            biggerModelOnPhone = models.isLlmModelDownloaded(biggerModel) || models.llmPartialBytes(biggerModel) > 0L
        )
    }

    override fun markNotificationsAsked() = NotificationAsk.markAsked(context)

    override fun notificationAskChanges(): Flow<Unit> = callbackFlow {
        val preferences = NotificationAsk.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NotificationAsk.KEY_ASKED || key == NotificationAsk.KEY_NOTES_MISSED) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Free space where the models go (ModelDownloadManager keeps them in the app's external files); 0 when it can't be read. */
    private fun freeBytes(): Long {
        val folder = context.getExternalFilesDir(null) ?: return 0L
        return try {
            StatFs(folder.path).availableBytes
        } catch (e: IllegalArgumentException) {
            0L
        }
    }
}
