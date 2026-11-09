package com.satori.ui.components

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.ui.R
import com.satori.ui.screens.CopyRes

/**
 * What the microphone ask says: the lines over its pill, and the pill.
 *
 * @property caption Why the microphone is needed and which of Android's options to pick, or, after
 *   a refusal, that recording needs it and where it can still be allowed
 * @property primary "Allow microphone", which opens Android's window, or "Open settings" once
 *   Android no longer asks
 */
data class MicrophoneAskCopy(val caption: List<CopyRes>, val primary: CopyRes)

/**
 * The words of the microphone ask at [state] (design 3.5): before Android's window, why and which
 * option; refused once, that Satori can't record without it, and the window again; refused twice,
 * where the app's settings page allows it. Allowed, there is nothing to ask, and the words are the
 * first ones.
 */
fun microphoneAskCopy(state: MicrophoneState): MicrophoneAskCopy = when (state) {
    MicrophoneState.REFUSED_FOR_GOOD -> MicrophoneAskCopy(
        caption = listOf(CopyRes(R.string.g_fr_mic_refused), CopyRes(R.string.g_fr_mic_settings)),
        primary = CopyRes(R.string.g_rec_action_settings)
    )
    MicrophoneState.REFUSED_ONCE -> MicrophoneAskCopy(
        caption = listOf(CopyRes(R.string.g_fr_mic_refused), CopyRes(R.string.g_fr_mic_how)),
        primary = CopyRes(R.string.g_fr_mic_action)
    )
    MicrophoneState.NOT_ALLOWED, MicrophoneState.ALLOWED -> MicrophoneAskCopy(
        caption = listOf(CopyRes(R.string.g_fr_mic_why), CopyRes(R.string.g_fr_mic_how)),
        primary = CopyRes(R.string.g_fr_mic_action)
    )
}

/**
 * Android's microphone window and the app's settings page, for a screen that asks for the
 * microphone in place ([rememberMicrophoneRequest]).
 */
@Stable
class MicrophoneRequest internal constructor(
    private val launchWindow: () -> Unit,
    private val launchSettings: () -> Unit
) {
    /** Opens Android's window, which answers through the request's onAnswered. */
    fun ask() = launchWindow()

    /** Opens the app's page in Android's settings; the permission is read again on the way back. */
    fun openSettings() = launchSettings()
}

/**
 * Asks Android for the microphone and reads it again whenever the screen comes back (rule R16:
 * the screen follows the facts): back from the app's settings page, the pill turns into what comes
 * next with no tap, and an allowance Android took back ("Only this time") is asked for again.
 *
 * @param onAnswered Android's window answered: whether it was [granted], and whether Android would
 *   ask again ([canAskAgain], its shouldShowRequestPermissionRationale), which tells a first
 *   refusal from a second ([MicrophoneState.answered])
 * @param onRechecked The permission as read on every return to the screen, and as it first shows
 *   ([MicrophoneState.reread])
 */
@Composable
fun rememberMicrophoneRequest(
    onAnswered: (granted: Boolean, canAskAgain: Boolean) -> Unit,
    onRechecked: (granted: Boolean) -> Unit
): MicrophoneRequest {
    val context = LocalContext.current
    val answered by rememberUpdatedState(onAnswered)
    val rechecked by rememberUpdatedState(onRechecked)
    val window = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        val canAskAgain = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        answered(granted, canAskAgain)
    }
    LifecycleResumeEffect(context) {
        rechecked(microphoneAllowed(context))
        onPauseOrDispose { }
    }
    return remember(window, context) {
        MicrophoneRequest(
            launchWindow = { window.launch(Manifest.permission.RECORD_AUDIO) },
            launchSettings = { openAppSettings(context) }
        )
    }
}

/**
 * The microphone ask in a [DockedFooter] (design 3.5): one sentence on why and which option to
 * pick, then Android's window; after a first refusal one calm line and one more try; after a
 * second, the way to the app's settings page. A refusal never closes the screen. The first run
 * draws it while Getting ready runs and at Ready; the recorder draws it, with the same words in
 * the same place, when the microphone was taken back.
 *
 * @param request The window and the page it opens ([rememberMicrophoneRequest])
 * @param acceptsTap The tap shield ([DockedFooter])
 */
@Composable
fun MicrophoneAsk(
    state: MicrophoneState,
    request: MicrophoneRequest,
    modifier: Modifier = Modifier,
    acceptsTap: () -> Boolean = { true },
    primaryModifier: Modifier = Modifier
) {
    val copy = microphoneAskCopy(state)
    DockedFooter(
        primary = stringResource(copy.primary.id),
        onPrimary = if (state == MicrophoneState.REFUSED_FOR_GOOD) request::openSettings else request::ask,
        caption = copy.caption.map { stringResource(it.id) },
        acceptsTap = acceptsTap,
        modifier = modifier,
        primaryModifier = primaryModifier
    )
}

/** Whether the microphone is allowed now. */
fun microphoneAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Opens Android's settings page of this app, where the microphone can be allowed. */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // A phone without a settings page for apps: the permission is found by hand.
    }
}

/** The activity [this] context belongs to, through the wrappers Compose and Hilt put around it. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
