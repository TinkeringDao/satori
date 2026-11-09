package com.satori.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.satori.domain.usecase.todo.ReminderPrompt
import com.satori.domain.usecase.todo.ReminderPrompts
import com.satori.presentation.sessions.NotificationAsk
import com.satori.ui.R
import com.satori.ui.design.ConfirmSheet

/**
 * What the To do section says about notifications, which reminders need (Android 13 and later
 * ask for them at run time).
 *
 * The first time an open to-do has a reminder and notifications are off, this shows a sheet that
 * says in one sentence why they are needed, and asks Android when the user agrees. It asks once:
 * after that, a refusal leaves [ReminderPrompt.TurnedOff], for which the screen shows one quiet
 * line that leads to the app's notification settings ([openNotificationSettings]). The line waits
 * while Android's own dialog is open, so it shows only after a refusal. The state is read again
 * whenever the screen resumes, so coming back from those settings clears the line.
 *
 * "Once" is the one flag it shares with the ping offer on home's status strip ([NotificationAsk]):
 * once either has asked, the other doesn't. The sheet follows the flag as it changes, so a ping
 * taken on the strip doesn't bring the sheet up after Android's window.
 *
 * @param hasReminders Some open to-do has a reminder time
 * @param mayAsk Whether the sheet may show now: home holds it back, as its strip's offers, while a
 *   recorder is live and for a minute after a Stop; it shows once that is over
 * @param onAllowed Called when notifications go from off to on while the screen shows, so the
 *   reminders held back meanwhile can be posted at once
 */
@Composable
fun rememberReminderPrompt(hasReminders: Boolean, mayAsk: Boolean = true, onAllowed: () -> Unit = {}): ReminderPrompt {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var wasOn by remember { mutableStateOf(notificationsOn) }
    val allowed by rememberUpdatedState(onAllowed)
    LaunchedEffect(notificationsOn) {
        if (notificationsOn && !wasOn) allowed()
        wasOn = notificationsOn
    }
    var asked by remember { mutableStateOf(NotificationAsk.asked(context)) }
    DisposableEffect(context) {
        val preferences = NotificationAsk.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NotificationAsk.KEY_ASKED) asked = NotificationAsk.asked(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Android's dialog is open; saved, as the answer can come back to a recreated activity.
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleResumeEffect(context) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    // Android 12 has no run-time permission: there notifications can only be turned on in settings.
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val canAsk = permission != null &&
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    val prompt = ReminderPrompts.decide(hasReminders, notificationsOn, canAsk, asked)

    if (prompt == ReminderPrompt.Ask && permission != null && mayAsk) {
        val markAsked = {
            NotificationAsk.markAsked(context)
            asked = true
        }
        ConfirmSheet(
            title = stringResource(R.string.g_todo_notify_title),
            text = stringResource(R.string.g_todo_notify_body),
            confirmLabel = stringResource(R.string.g_todo_notify_allow),
            onConfirm = {
                markAsked()
                requesting = true
                launcher.launch(permission)
            },
            onDismiss = markAsked,
            dismissLabel = stringResource(R.string.g_todo_notify_later)
        )
    }
    return if (requesting || (prompt == ReminderPrompt.Ask && !mayAsk)) ReminderPrompt.None else prompt
}

/** Opens the app's notification settings, where reminders can be allowed again. */
fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )
}
