package com.satori.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.satori.ui.R
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/** How much of the location the app may read. */
internal enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

/** What the "Save the place" row says under its title, instead of what the setting does. */
internal enum class SavePlaceNote {
    /** Only what the setting does. */
    NONE,

    /** Location was refused just now; Android's settings can still allow it. */
    REFUSED,

    /** Only the approximate location is allowed, so the place can be kilometres off. */
    APPROXIMATE,

    /** The phone's location is switched off, so no place can be read. */
    LOCATION_OFF
}

/**
 * The "Save the place" switch as it shows: [checked] only when the setting is on and location is
 * allowed, so a permission taken away in Android's settings shows the switch off; and the [note]
 * under its title.
 */
internal data class SavePlaceRowState(val checked: Boolean, val note: SavePlaceNote) {

    companion object {
        /**
         * @param savePlace The stored setting
         * @param access What the app may read now
         * @param locationOn Whether the phone's location is switched on
         * @param refused Whether the last request in this screen came back without location
         */
        fun of(savePlace: Boolean, access: LocationAccess, locationOn: Boolean, refused: Boolean): SavePlaceRowState =
            when {
                access == LocationAccess.NONE -> SavePlaceRowState(false, if (refused) SavePlaceNote.REFUSED else SavePlaceNote.NONE)
                !savePlace -> SavePlaceRowState(false, SavePlaceNote.NONE)
                !locationOn -> SavePlaceRowState(true, SavePlaceNote.LOCATION_OFF)
                access == LocationAccess.APPROXIMATE -> SavePlaceRowState(true, SavePlaceNote.APPROXIMATE)
                else -> SavePlaceRowState(true, SavePlaceNote.NONE)
            }
    }
}

/**
 * "Save the place", in the Recording section of Settings. Turning it on asks Android for the
 * location (precise and approximate together, as Android wants them asked) when it isn't allowed
 * yet; refused, the switch stays off and one line says why. A line also says when only the
 * approximate location is allowed or the phone's location is off, with the way to Android's
 * settings to change it. Permission and location are read again whenever the screen comes back,
 * and the phone's location also as it is switched: Quick Settings, pulled down over this screen,
 * don't pause it.
 */
@Composable
internal fun SavePlaceRow(savePlace: Boolean, onSavePlaceChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(locationAccess(context)) }
    var locationOn by remember { mutableStateOf(isLocationOn(context)) }
    // Saved: the answer can come back to a recreated activity.
    var refused by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        access = locationAccess(context)
        locationOn = isLocationOn(context)
        // Allowed in Android's settings meanwhile: the refusal is over.
        if (access != LocationAccess.NONE) refused = false
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val modeChanged = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                locationOn = isLocationOn(context)
            }
        }
        // Sent by Android itself, which reaches a receiver that isn't exported.
        ContextCompat.registerReceiver(
            context,
            modeChanged,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(modeChanged) }
    }
    val askForLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = locationAccess(context)
        refused = access == LocationAccess.NONE
        if (!refused) onSavePlaceChange(true)
    }
    val state = SavePlaceRowState.of(savePlace, access, locationOn, refused)

    Column(Modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            title = stringResource(R.string.g_place_save),
            supporting = stringResource(
                when (state.note) {
                    SavePlaceNote.NONE -> R.string.g_place_save_desc
                    SavePlaceNote.REFUSED -> R.string.g_place_denied
                    SavePlaceNote.APPROXIMATE -> R.string.g_place_approximate
                    SavePlaceNote.LOCATION_OFF -> R.string.g_place_location_off
                }
            ),
            checked = state.checked,
            onCheckedChange = { on ->
                when {
                    !on -> onSavePlaceChange(false)
                    access != LocationAccess.NONE -> onSavePlaceChange(true)
                    else -> askForLocation.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
        )
        val settingsPage = when (state.note) {
            SavePlaceNote.NONE -> null
            SavePlaceNote.REFUSED, SavePlaceNote.APPROXIMATE ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            SavePlaceNote.LOCATION_OFF -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        if (settingsPage != null) {
            // Android's page, named so: the owner is in Satori's Settings already.
            PillButton(
                text = stringResource(R.string.g_place_open_android_settings),
                onClick = { openSettingsPage(context, settingsPage) },
                modifier = Modifier.padding(start = GrilleSpace.margin, bottom = GrilleSpace.m),
                kind = PillKind.Outline
            )
        }
    }
}

private fun locationAccess(context: Context): LocationAccess = when {
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
        LocationAccess.PRECISE
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
        LocationAccess.APPROXIMATE
    else -> LocationAccess.NONE
}

private fun isLocationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

/** Opens one of Android's settings pages; a phone without it leaves the user to find it by hand. */
private fun openSettingsPage(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Nothing to open; the line above says what to change.
    }
}
