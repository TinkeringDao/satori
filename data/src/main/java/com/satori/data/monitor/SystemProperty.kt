package com.satori.data.monitor

/**
 * Reads a system property for a debug build's switches: the microphone's (MicSwitches), the speech
 * models' ONNX Runtime options (SpeechSessionOptions) and the pace of the speech models' loads
 * ([ModelLoadPacing]). android.os.SystemProperties is not in the SDK, so it is reached by
 * reflection; should Android refuse that, the read says so, and the switches stay at their defaults
 * with a warning. The AI models' loads read theirs in native code (LoadSettings).
 */
object SystemProperty {

    /** The value of the property [name]: "" when it isn't set, null when it can't be read. */
    fun read(name: String): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, name) as? String ?: ""
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    }
}
