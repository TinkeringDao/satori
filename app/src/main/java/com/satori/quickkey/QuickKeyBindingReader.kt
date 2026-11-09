package com.satori.quickkey

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import com.satori.domain.settings.KeyRead

/**
 * Reads what the phone's own settings say of the power key ([KeyRead]): the intent Motorola's My
 * Key starts (`Settings.System` `my_key_tap_app`) and the double press's action
 * (`Settings.Secure` `double_tap_power_button_gesture`). Android 12 and later refuse an app the
 * hidden keys of their own with a SecurityException; whether Motorola's keys are among them is
 * UNVERIFIED on the phone, so each is read on its own and is null when refused. A binder call each:
 * not on the main thread.
 */
internal object QuickKeyBindingReader {

    private const val TAP_APP = "my_key_tap_app"
    private const val GESTURE = "double_tap_power_button_gesture"
    private const val TAG = "SatoriQuickKey"

    fun read(resolver: ContentResolver): KeyRead = KeyRead(
        tapApp = readOrNull(TAP_APP) { Settings.System.getString(resolver, TAP_APP).orEmpty() },
        gesture = readOrNull(GESTURE) { Settings.Secure.getInt(resolver, GESTURE) }
    )

    private fun <T> readOrNull(key: String, read: () -> T): T? = try {
        read()
    } catch (e: SecurityException) {
        Log.i(TAG, "Quick key setting $key can't be read")
        null
    } catch (e: Settings.SettingNotFoundException) {
        // Not set at all: the double press does what Android does by default.
        null
    }
}
