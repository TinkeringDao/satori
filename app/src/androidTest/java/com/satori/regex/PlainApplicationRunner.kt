package com.satori.regex

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs the app's instrumented tests in a plain [Application]: the app's own (Hilt, the field log,
 * the first run's coordinator) starts nothing that the regex check needs, would write to the
 * emulator's storage as the app does on the phone, and loads code that an APK built without the
 * native LLM library lacks.
 */
class PlainApplicationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, Application::class.java.name, context)

    /**
     * A receiver or a service of the app that Android starts in the test process meanwhile (the
     * reminders' receiver after a boot, an install or a change of the clock) needs the app's Hilt
     * graph, which a plain application lacks: its failure is logged and goes no further. The tests
     * use none of them.
     *
     * Android has reported a receiver's broadcast finished before it asks this, and finishes it
     * again afterwards unless the receiver's result is taken (goAsync): that second finish threw
     * "Broadcast already finished" and ended the test process. On the Android 15 emulator a
     * package's first start after its install gets BOOT_COMPLETED, so the first run on a fresh
     * install crashed before any test ran (0.7.4's merge).
     */
    override fun onException(obj: Any?, e: Throwable): Boolean {
        if (obj is BroadcastReceiver || obj is Service) {
            Log.w(TAG, "Not run in the tests: ${obj.javaClass.name}", e)
            // Already reported finished: taken, its result isn't finished a second time.
            if (obj is BroadcastReceiver) obj.goAsync()
            return true
        }
        return super.onException(obj, e)
    }

    private companion object {
        const val TAG = "PlainApplicationRunner"
    }
}
