package com.satori.feature.stt.datasource

import java.util.Locale

/**
 * The debug switches of a capture's microphone. On 2026-10-13 the owner's recordings came in at
 * -51 to -64 dBFS (90th percentile -44 to -50), every one with a known fold state made with the
 * phone closed, against about -22 dBFS the morning before; a 193 s recording kept 117 words. The
 * capture records from the bottom built-in microphone (AUDIO_DEVICE_IN_BUILTIN_MIC, address
 * "bottom") with the speech recognition input, which has no automatic gain, and the phone also
 * has a back one (AUDIO_DEVICE_IN_BACK_MIC, "back"). These switches let a session with the
 * owner's voice measure which helps.
 *
 * Read from system properties as each capture starts, in a debug build only ([forCapture]); a
 * release build records as it always has ([DEFAULT]). Set one with `adb shell setprop <name>
 * <value>`; an empty value is the default:
 * - `debug.satori.mic.device` auto|bottom|back: auto lets Android route the input, as always;
 *   bottom and back ask for the built-in microphone with that address
 *   (AudioRecord.setPreferredDevice, from AudioManager.getDevices(GET_DEVICES_INPUTS)).
 * - `debug.satori.mic.gain` off|<dB>|auto: a digital gain on the audio the voice detector and the
 *   recognizer get, never on the kept audio ([CaptureGain]). A number from 0 to 30 is a fixed
 *   gain in dB ("12", "+12", "12.5"); auto aims the speech at about -26 dBFS, at most +30 dB.
 *
 * A value it doesn't know is ignored with a warning, and that switch stays the default. A property
 * that can't be read at all leaves both at the default, also with a warning.
 */
internal data class MicSwitches(val device: Device = Device.AUTO, val gain: Gain = Gain.Off) {

    /** Which built-in microphone a capture asks for. */
    enum class Device(val address: String?) {
        /** None: Android routes the input as it would. */
        AUTO(null),

        /** The bottom built-in microphone, the one Android routes the speech input to on the razr. */
        BOTTOM("bottom"),

        /** The back built-in microphone. */
        BACK("back");

        /** As the switch is written: auto, bottom, back. */
        val value: String
            get() = name.lowercase(Locale.ROOT)
    }

    /** The gain a capture puts on what the voice detector and the recognizer hear. */
    sealed interface Gain {
        /** None, as always. */
        data object Off : Gain

        /** [db] dB on every chunk, less where a chunk would clip. */
        data class Fixed(val db: Double) : Gain

        /** What brings the speech to about -26 dBFS, at most +30 dB ([CaptureGain]). */
        data object Auto : Gain
    }

    /** The switches as a capture's start line names them: "asked=back gain=+12.0dB". */
    fun describe(): String = "asked=${device.value} gain=${gainText(gain)}"

    companion object {
        const val DEVICE_PROPERTY = "debug.satori.mic.device"
        const val GAIN_PROPERTY = "debug.satori.mic.gain"

        /** The highest gain, fixed or automatic. */
        const val MAX_GAIN_DB = 30.0

        /** How every capture of a release build records, and a debug build's with no switch set. */
        val DEFAULT = MicSwitches()

        /**
         * The switches of a capture starting now: [DEFAULT], with no property read, unless the
         * build is [debuggable]; then what the properties ask for ([fromProperties]).
         *
         * @param read Reads a system property: "" when it isn't set, null when it can't be read
         * @param warn Hears of a value that is ignored
         */
        fun forCapture(debuggable: Boolean, read: (String) -> String?, warn: (String) -> Unit): MicSwitches =
            if (debuggable) fromProperties(read, warn) else DEFAULT

        /** The switches the debug properties ask for; see [forCapture] for [read] and [warn]. */
        fun fromProperties(read: (String) -> String?, warn: (String) -> Unit): MicSwitches {
            val deviceText = read(DEVICE_PROPERTY)
            val gainText = read(GAIN_PROPERTY)
            if (deviceText == null || gainText == null) {
                warn("Ignoring debug.satori.mic.*: the properties can't be read")
                return DEFAULT
            }
            return MicSwitches(
                device = deviceOf(deviceText.trim().lowercase(Locale.ROOT), warn),
                gain = gainOf(gainText.trim().lowercase(Locale.ROOT), warn)
            )
        }

        private fun deviceOf(text: String, warn: (String) -> Unit): Device {
            if (text.isEmpty()) return DEFAULT.device
            return Device.entries.firstOrNull { it.value == text }
                ?: DEFAULT.device.also { warn("Ignoring $DEVICE_PROPERTY=$text: not auto, bottom or back") }
        }

        private fun gainOf(text: String, warn: (String) -> Unit): Gain {
            if (text.isEmpty() || text == "off") return Gain.Off
            if (text == "auto") return Gain.Auto
            val db = text.removePrefix("+").removeSuffix("db").trim().toDoubleOrNull()
            return when {
                db == null || db.isNaN() || db < 0.0 || db > MAX_GAIN_DB ->
                    DEFAULT.gain.also { warn("Ignoring $GAIN_PROPERTY=$text: not off, auto or 0 to ${MAX_GAIN_DB.toInt()} dB") }
                db == 0.0 -> Gain.Off
                else -> Gain.Fixed(db)
            }
        }

        /** How a line names [gain]: off, +12.0dB, auto. */
        fun gainText(gain: Gain): String = when (gain) {
            Gain.Off -> "off"
            Gain.Auto -> "auto"
            is Gain.Fixed -> String.format(Locale.ROOT, "%+.1fdB", gain.db)
        }
    }
}
