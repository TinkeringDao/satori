package com.satori.quickkey

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import com.satori.data.quickkey.QuickKeySettings
import com.satori.data.quickkey.QuickKeySettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Entry point of the quick key: Motorola's My Key starts this activity on a power-key
 * double-press (docs/QUICK_KEY.md). It has no UI. Inside onCreate it reads
 * [QuickKeySettings], starts MainActivity with a [QuickKeyRequest] and finishes. A press within
 * [QuickKeyRepeatFilter.WINDOW_MS] of the last forwarded one is dropped as its repeat.
 *
 * The manifest exports it without a permission because My Key is another app. Two ways in:
 * - My Key: Settings.System `my_key_tap_app` set to
 *   `intent:#Intent;action=com.satori.action.QUICK_KEY;launchFlags=0x10000000;component=com.satori/com.satori.quickkey.QuickKeyActivity;end`,
 *   which My Key runs while Settings.Secure `double_tap_power_button_gesture` is 2. The URI
 *   names the action because Intent.parseUri gives every `#Intent;` URI the action VIEW unless
 *   it names one, and VIEW doesn't match this activity's filter. Today that would still get
 *   through, since Android checks an explicit intent against the filters only when the
 *   component opts in, but the matching URI keeps working either way. Only adb can set the
 *   key: AOSP's SettingsProvider doesn't let Satori write `my_key_tap_app`, not even with
 *   WRITE_SECURE_SETTINGS (see the doc).
 * - The action [ACTION_QUICK_KEY] alone, e.g. `adb shell am start -a com.satori.action.QUICK_KEY`.
 *
 * The incoming intent is never read: a caller gets what the user configured and can't pick
 * the category or the language through extras.
 *
 * Each press it forwards is stored with its time, after MainActivity is started and off the main
 * thread ([pressWrites]): a press that reached Satori says the key is set up, which Settings' Power
 * key row reads ([com.satori.domain.settings.QuickKeyBinding]). Nothing waits for that write.
 */
@AndroidEntryPoint
class QuickKeyActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: QuickKeySettingsRepository

    @Inject
    lateinit var repeatFilter: QuickKeyRepeatFilter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!repeatFilter.accept(SystemClock.elapsedRealtime())) {
            Log.i(TAG, "Quick key: pressed again within ${QuickKeyRepeatFilter.WINDOW_MS} ms, ignored")
            finish()
            return
        }
        val settings = readSettings()
        val request = QuickKeyRequest(settings.categoryId, settings.stopOnSecondPress, settings.language)
        Log.i(TAG, "Quick key: record as ${request.categoryId} in ${request.language}, stop if recording: ${request.stopIfRecording}")
        startActivity(request.toMainActivityIntent(this))
        val pressedAt = System.currentTimeMillis()
        pressWrites.launch { settingsRepository.recordPress(pressedAt) }
        finish()
    }

    /**
     * Blocks on purpose. The file holds three values and the wait is capped. Finishing inside
     * onCreate means onStart and onResume never run, so no window is drawn and nothing can be
     * left on screen. Past the cap the defaults apply.
     */
    private fun readSettings(): QuickKeySettings =
        runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { settingsRepository.get() } }
            ?: QuickKeySettings().also {
                Log.w(TAG, "Quick key settings not read within $READ_TIMEOUT_MS ms, using the defaults")
            }

    companion object {
        /** Action of the activity's intent filter; must match AndroidManifest.xml. */
        const val ACTION_QUICK_KEY = "com.satori.action.QUICK_KEY"

        private const val TAG = "SatoriQuickKey"
        private const val READ_TIMEOUT_MS = 1_000L

        /**
         * Where the presses are stored: the process's, not the activity's, which finishes at
         * once. A write that fails costs only the proof of the press.
         */
        private val pressWrites = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Quick key press not stored", e) }
        )
    }
}
