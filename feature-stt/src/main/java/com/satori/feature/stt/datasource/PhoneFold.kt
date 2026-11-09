package com.satori.feature.stt.datasource

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the phone is folded, for a capture's lines in the field log ([PhonePosture]). From the
 * hinge angle sensor when the phone has one for apps ([Sensor.TYPE_HINGE_ANGLE]: closed up to
 * [CLOSED_MAX_DEGREES], open from [OPEN_MIN_DEGREES], half between); otherwise, and until the
 * sensor's first reading, from the displays: the inner one (the default display) on means open;
 * it off and another one (the razr's cover, display 1) on or dozing, closed; every one off,
 * unknown. Whether the razr gives apps its hinge sensor is for its field log to show (`fold=`
 * follows the hinge then even with the screens off).
 *
 * Reading and watching it never fails a capture: what can't be read says unknown.
 */
internal class PhoneFold(context: Context) : PhonePosture {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // The last hinge reading, while one is watched; null without a sensor or before its first.
    @Volatile private var hinge: String? = null

    // How many watches run: a capture that a new one cuts short may end after the new one began.
    private val watching = AtomicInteger()

    override fun fold(): String = hinge ?: fromDisplays()

    override fun watchFold(onChange: (String) -> Unit): AutoCloseable {
        var last = fold()
        fun changed() {
            val now = fold()
            if (now == last) return
            last = now
            onChange(now)
        }

        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = changed()
            override fun onDisplayRemoved(displayId: Int) = changed()
            override fun onDisplayChanged(displayId: Int) = changed()
        }
        val hingeSensor = try {
            sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } catch (e: RuntimeException) {
            null
        }
        val hingeListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                hinge = event.values.firstOrNull()?.let(::ofAngle)
                changed()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        watching.incrementAndGet()
        runCatching { displays?.registerDisplayListener(displayListener, main) }
        if (hingeSensor != null) {
            runCatching { sensors?.registerListener(hingeListener, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL, main) }
        }
        return AutoCloseable {
            runCatching { displays?.unregisterDisplayListener(displayListener) }
            if (hingeSensor != null) runCatching { sensors?.unregisterListener(hingeListener) }
            // Unwatched, a reading would only go stale.
            if (watching.decrementAndGet() == 0) hinge = null
        }
    }

    /** What the displays say: see the class description. */
    private fun fromDisplays(): String {
        val all = try {
            displays?.displays
        } catch (e: RuntimeException) {
            null
        } ?: return UNKNOWN
        val lit = all.filter { it.state != Display.STATE_OFF && it.state != Display.STATE_UNKNOWN }
        return when {
            lit.any { it.displayId == Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON } -> OPEN
            lit.any { it.displayId != Display.DEFAULT_DISPLAY } -> CLOSED
            else -> UNKNOWN
        }
    }

    companion object {
        const val OPEN = "open"
        const val CLOSED = "closed"
        const val HALF = "half"
        const val UNKNOWN = "unknown"

        /** The hinge angle at or below which the phone counts as closed. */
        const val CLOSED_MAX_DEGREES = 30f

        /** The hinge angle from which the phone counts as open. */
        const val OPEN_MIN_DEGREES = 150f

        /** The fold of a hinge at [degrees]. */
        fun ofAngle(degrees: Float): String = when {
            degrees <= CLOSED_MAX_DEGREES -> CLOSED
            degrees >= OPEN_MIN_DEGREES -> OPEN
            else -> HALF
        }
    }
}
