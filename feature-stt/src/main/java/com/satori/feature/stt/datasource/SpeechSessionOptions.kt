package com.satori.feature.stt.datasource

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.satori.data.monitor.SystemProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime's options for the sessions of the speech models (Parakeet v3 and v2, live and on a
 * file, Cohere Transcribe, and AI4Bharat's Malayalam model, live and on a file), set through
 * sherpa-onnx's provider config file. Since 0.7.1 every build loads every speech model with
 * prepacking off: the provider is "cpu:<file>" ([providerForLoad]), as the memory plan's run 5 has
 * it (MEMORY_RESEARCH_PLAN, not yet measured on the phone). A debug build can load with ONNX
 * Runtime's defaults again, the provider "cpu", with `debug.satori.ort.prepack` on (`adb shell
 * setprop`, read at each load; empty is off).
 *
 * Off turns off ONNX Runtime's prepacking (`session.disable_prepacking=1`) and its memory pattern
 * (`EnableMemPattern=0`). On 2026-10-13 prepacking was what turned Cohere Transcribe's weights,
 * which ONNX Runtime maps from `encoder.int8.onnx.data`, into about 2 GB of packed copies in
 * anonymous memory at about 400 MB/s (RssAnon 453 -> 2,498 MB, 35 cached apps killed in 7 s), and
 * what took Parakeet v3's start to its peak of 1.25 GB (5 of its 6 kills at 12:02); on 2026-10-16
 * Parakeet v3's live load went from 57 to 1,261 MB as a recording started, and a cached app was
 * killed. Without it the int8 weights stay where ONNX Runtime put them: mapped from the file for
 * Cohere, the parsed heap copy for Parakeet, whose 0.65 GB of embedded weights no option moves
 * (the plan expects its peak at about 0.75 GB). With prepacking off and the memory pattern on, ONNX
 * Runtime reserves one block the size of all initializers (about 2.7 GB for Cohere's encoder), so
 * the pattern goes off with it. The cost is speed: every matrix product repacks its weights as it
 * runs, little for an encoder's batch of frames, more for the decoder's token-by-token steps
 * (estimated +10-20% for Cohere and +2-5% for Parakeet, not yet measured).
 *
 * sherpa-onnx (v1.13.8, csrc/session.cc) reads the file named after "cpu:" in
 * OfflineModelConfig.provider for all the sessions of a recognizer: `key=value` lines, `#` lines as
 * comments, the `SessionConfig.` ones handed on to ONNX Runtime's AddConfigEntry, and with
 * `DEBUG=1` it logs what it read (tag sherpa-onnx). Its other values are read leniently (a number
 * it can't parse is the default). Three of its ways of ending the whole process, which no Kotlin
 * code can catch, decide how the file is handled:
 * - A file that isn't there makes it call `_Exit(-1)`. The file is written as the app starts
 *   ([install]) and checked before every load ([providerForLoad]): one that is missing or has other
 *   text is written again, and one that can't be written (full storage, say) leaves the load of a
 *   model that runs live at ONNX Runtime's defaults ("cpu"), as every load had before 0.7.1, since
 *   a recording must never fail. A large one, Cohere Transcribe, is not loaded then
 *   ([PrepackingStaysOnException]): with prepacking on its weights become about 2 GB of packed
 *   copies, which ended 35 cached apps on 2026-10-13.
 * - A value ONNX Runtime can't parse, for a key it knows, makes it abort. The file's text is a
 *   constant ([CONTENT]) whose values are 1 and 0.
 * - Cohere Transcribe binds its outputs to a GPU when the provider string holds "cuda". A path
 *   with "cuda" in it is refused.
 *
 * @param dir Where the file is kept: the app's own files directory
 * @param debuggable Whether `debug.satori.ort.prepack` is read at all: a debuggable build
 * @param read Reads a system property: "" when it isn't set, null when it can't be read
 */
@Singleton
class SpeechSessionOptions internal constructor(
    private val dir: File,
    private val debuggable: Boolean,
    private val read: (String) -> String?,
    private val warn: (String) -> Unit
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        dir = File(context.filesDir, DIR),
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        read = SystemProperty::read,
        warn = { Log.w(TAG, it) }
    )

    /** The provider config file, [CONTENT] once written. */
    val file: File = File(dir, FILE_NAME)

    /**
     * Writes the provider config file, as the app starts, in every build. A failure is logged; the
     * loads then check the file again ([providerForLoad]).
     */
    fun install() {
        if (refusedPath() != null) return
        if (!intact() && !write()) warn("ORT options not written to ${file.path}; the speech models load with prepacking on")
    }

    /**
     * The provider for a speech model loading now: "cpu:<file>", prepacking off, once the file has
     * been checked and, if need be, written again; "cpu", ONNX Runtime's defaults, in a debug build
     * whose `debug.satori.ort.prepack` is on. A file that can't be written, or a path with "cuda" in
     * it, gives "cpu" with a warning, or for a [large] model (one that never runs live: Cohere
     * Transcribe) refuses the load ([PrepackingStaysOnException]); a value of the switch other than
     * on and off is ignored with one, as is a property that can't be read.
     *
     * @throws PrepackingStaysOnException for a [large] model whose load would have prepacking on
     */
    fun providerForLoad(large: Boolean = false): String {
        if (debuggable && prepackOn()) return CPU
        refusedPath()?.let { reason -> return fallback(reason, large) }
        if (!intact() && !write()) return fallback("${file.path} can't be written", large)
        return "$CPU:${file.absolutePath}"
    }

    // ONNX Runtime's defaults for a model that runs live; no load at all for a large one.
    private fun fallback(reason: String, large: Boolean): String {
        if (large) {
            warn("Not loading a large speech model with ORT prepacking on: $reason")
            throw PrepackingStaysOnException("ORT prepacking can't be turned off ($reason); a large speech model isn't loaded with it on")
        }
        warn("Not turning ORT prepacking off: $reason")
        return CPU
    }

    // A debug build's override: whether prepacking is to be on for this load; off unless the switch says on.
    private fun prepackOn(): Boolean {
        val text = read(PREPACK_PROPERTY)
        if (text == null) {
            warn("Ignoring $PREPACK_PROPERTY: the property can't be read")
            return false
        }
        return when (val value = text.trim().lowercase(Locale.ROOT)) {
            "on", "1", "true" -> true
            "", "off", "0", "false" -> false
            else -> false.also { warn("Ignoring $PREPACK_PROPERTY=$value: not on or off") }
        }
    }

    // Why the file's path can't be handed to sherpa-onnx, or null when it can.
    private fun refusedPath(): String? =
        if (file.absolutePath.contains("cuda", ignoreCase = true)) "the path of its config file holds \"cuda\" (${file.path})" else null

    private fun intact(): Boolean = try {
        file.isFile && file.readText() == CONTENT
    } catch (e: IOException) {
        false
    }

    // Written to a file of its own and renamed over the old one, so sherpa-onnx never reads half a
    // file, also with two loads checking at once.
    private fun write(): Boolean = try {
        dir.mkdirs()
        val tmp = File.createTempFile(FILE_NAME, ".tmp", dir)
        try {
            tmp.writeText(CONTENT)
            tmp.renameTo(file) && intact()
        } finally {
            tmp.delete()
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    companion object {
        const val PREPACK_PROPERTY = "debug.satori.ort.prepack"

        /** The provider with ONNX Runtime's defaults: a debug build's with prepacking on, or a load whose file can't be written. */
        const val CPU = "cpu"

        /**
         * The provider config file. Each value is one ONNX Runtime and sherpa-onnx parse; `DEBUG=1`
         * makes sherpa-onnx log the file's path and each line it read, which shows on the phone
         * that the options applied.
         */
        const val CONTENT = "# Satori: ONNX Runtime's options for the speech models, prepacking off\n" +
            "SessionConfig.session.disable_prepacking=1\n" +
            "EnableMemPattern=0\n" +
            "DEBUG=1\n"

        internal const val DIR = "ort"
        internal const val FILE_NAME = "prepack-off.conf"
        private const val TAG = "SpeechSessionOptions"
    }
}

/**
 * A large speech model (Cohere Transcribe) was not loaded, as ONNX Runtime's prepacking couldn't be
 * turned off for it ([SpeechSessionOptions.providerForLoad]): the load fails, as one that can't read
 * its files does, and the live transcript stays.
 */
class PrepackingStaysOnException(message: String) : IOException(message)
