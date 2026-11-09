package com.satori.ui.debug

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.satori.data.config.ModelSource
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.firstrun.FirstRunPrefs
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import java.io.File
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL

/**
 * The first run's debug actions, started over adb like the others of [DebugToolsActivity]
 * (`am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action ...`). All but
 * dump-settings refuse to run on a real phone, which never gets demo state.
 *
 * - `firstrun-reset`: the app as just installed, but for its recordings: every preference and
 *   setting cleared, the models folder deleted; then the process ends. Android is asked to take
 *   the runtime permissions back as it ends, which it may not do when the process ends itself at
 *   once (seen on the emulator: the microphone stayed allowed), so the log line gives the commands
 *   that do it ([resetReport]): `pm revoke` for each one granted, and for a microphone refused
 *   twice `pm clear-permission-flags com.satori android.permission.RECORD_AUDIO user-set user-fixed`,
 *   which the app can't do itself.
 * - `firstrun-stage --es stage GET_READY|DOWNLOADING|READY|DONE`: stores the stage with what it
 *   implies (READY shows Ready, not home; DONE has its first recording), then the process ends, so
 *   the next start opens there.
 * - `firstrun-source --es url http://10.0.2.2:8099/`: every model download comes from that test
 *   server, with the hashes of its manifest.txt, until `--es url off`; see [ModelSource].
 * - `notes-ready [--es session <id>] [--ei after_s <0-600>]`: after that many seconds the notes of
 *   that recording ([NOTES_READY_SESSION] without one) are ready, as the finisher says once a
 *   recording's notes are stored: the first notes' buzz, "Your notes are ready" when allowed and
 *   out of sight, and with notifications not allowed the count that makes home's strip offer the
 *   ping. The emulator makes no recording with notes, having no models; the delay lets the tester
 *   leave Satori first, as notes come while the owner is away. Leave with HOME a second or two
 *   after the start: HOME while the process still starts (after firstrun-reset or firstrun-stage,
 *   which end it) can hold the action back until Satori's next start. The delay runs while Satori
 *   is the app before the launcher; once another app has been opened Android may freeze the
 *   cached process, which holds the delay too. For home's strip to show the ping, nothing may be
 *   left to download, as a download comes first on the strip: setup's 11-byte placeholders read as
 *   damaged. On an emulator without recordings (pm clear): `firstrun-stage --es stage DONE`, the
 *   synthetic files of `~/satori-audit/tools/throttle_server.py --write <dir>` pushed into
 *   `Android/data/com.satori/files/models/`, then notes-ready, HOME, and Satori opened again
 *   (0.7.2's check: `trigger=notes_missed missed=1 recordings=0`). Demo recordings (seed) bring
 *   to-dos with reminders, whose sheet asks first; once it is answered the ping has nothing left
 *   to ask.
 * - `dump-settings`: every setting, preference, permission and model on the phone as
 *   `key=value` lines under SatoriFirstRun, also written to files/debug/settings.txt in the app's
 *   external files. Values only: a long text gives its length, a path its file name. The reader's
 *   builds (0.8.0) say their files' check and, once the first run found them with their pinned
 *   SHA-256, `, pinned`: `model.indictrans2-indic-en-dist-200m-int8=Intact, pinned`.
 */
internal object FirstRunDebug {

    const val TAG = "SatoriFirstRun"

    /** The runtime permissions a reset takes back; notifications ask for one from Android 13 on. */
    private val PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** A manifest larger than this is no test server's. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    private const val TIMEOUT_MS = 10_000

    /** A value longer than this is given as its length. */
    private const val MAX_VALUE_CHARS = 40

    /** The recording notes-ready names without `--es session`; on a fresh emulator there is none. */
    const val NOTES_READY_SESSION = "emu-1"

    /** The longest delay notes-ready takes (s): long enough to lock the emulator and wake it. */
    const val NOTES_READY_MAX_S = 600

    /** notes-ready's delay of [given] seconds, or null outside 0 to [NOTES_READY_MAX_S]. */
    fun notesReadyAfterSeconds(given: Int): Int? = given.takeIf { it in 0..NOTES_READY_MAX_S }

    /**
     * Clears what a fresh install doesn't have, but the recordings: every SharedPreferences file and
     * DataStore, the models folder; and asks Android to take the runtime permissions back once the
     * process ends (Android 13 and later). The caller ends the process. Returns what was done, for
     * the log.
     */
    fun reset(context: Context): String {
        val models = File(context.getExternalFilesDir(null), "models")
        val modelFiles = models.walkBottomUp().count { it.isFile }
        models.deleteRecursively()
        val prefs = File(context.dataDir, "shared_prefs").listFiles { file -> file.name.endsWith(".xml") }.orEmpty()
        prefs.forEach { context.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        val stores = File(context.filesDir, "datastore")
        val storeFiles = stores.listFiles().orEmpty().size
        stores.deleteRecursively()
        val granted = PERMISSIONS.filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.revokeSelfPermissionsOnKill(granted)
        }
        return resetReport(modelFiles, prefs.size, storeFiles, granted, context.packageName)
    }

    /**
     * The reset's log line: what it deleted, and what Android was asked to do with the [granted]
     * permissions, which it may not have done: the commands that take them back for sure, and the
     * one a microphone refused twice needs. Ids, counts and commands only.
     */
    internal fun resetReport(modelFiles: Int, prefs: Int, stores: Int, granted: List<String>, packageName: String): String {
        val revoke = granted.joinToString("; ") { "pm revoke $packageName $it" }
        val asked = if (granted.isEmpty()) {
            "No runtime permission was granted. "
        } else {
            "Android was asked to take back ${granted.size} permissions as the process ends, which it may not do; " +
                "to be sure, run: $revoke. "
        }
        return "First run reset: $modelFiles model files, $prefs preference files and $stores data stores deleted. " + asked +
            "After a microphone refused twice also run pm clear-permission-flags $packageName " +
            "android.permission.RECORD_AUDIO user-set user-fixed"
    }

    /**
     * Stores the stage called [name] with what it implies, synchronously; null when [name] is no
     * stage. The caller ends the process, so the next start reads it: commit, not apply, as the
     * process ends right after.
     */
    @SuppressLint("ApplySharedPref")
    fun setStage(context: Context, name: String?): FirstRunStage? {
        val stage = FirstRunStage.entries.firstOrNull { it.name == name?.trim()?.uppercase() } ?: return null
        val prefs = context.getSharedPreferences(FirstRunPrefs.FILE, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (stage == FirstRunStage.GET_READY) edit.clear()
        edit.putString(FirstRunPrefs.STAGE, stage.name)
            .putBoolean(FirstRunPrefs.ONBOARDING_COMPLETE, FirstRunFlow.onboardingComplete(stage))
        if (stage != FirstRunStage.GET_READY && !prefs.contains(FirstRunPrefs.CONSENT_AT)) {
            edit.putLong(FirstRunPrefs.CONSENT_AT, System.currentTimeMillis())
        }
        if (stage == FirstRunStage.DONE) {
            edit.putBoolean(FirstRunPrefs.FIRST_RECORDING_SAVED, true)
        } else {
            edit.remove(FirstRunPrefs.LOOKED_AROUND).remove(FirstRunPrefs.FIRST_RECORDING_SAVED).remove(FirstRunPrefs.AI_DOWNLOAD_STARTED)
        }
        edit.commit()
        return stage
    }

    /**
     * Points the model downloads at the test server at [url], with its manifest.txt, or back at the
     * registry for "off". Network: not on the main thread. Returns the line to log.
     */
    fun setSource(context: Context, url: String?): String {
        val given = url?.trim().orEmpty()
        if (given.isEmpty()) return "firstrun-source needs --es url <test server> or --es url off"
        if (given == "off") {
            ModelSource.clear(context)
            return "Model source: the registry"
        }
        val base = if (given.endsWith("/")) given else "$given/"
        return try {
            ModelSource.set(context, base, fetch(base + "manifest.txt"))
            val server = checkNotNull(ModelSource.server)
            "Model source: ${server.base}, ${server.files.size} files, ${server.files.values.sumOf { it.bytes } / 1_000_000} MB"
        } catch (e: Exception) {
            "Model source not changed: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
    }

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(MAX_MANIFEST_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = reader.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            }
            check(text.length <= MAX_MANIFEST_BYTES) { "the manifest is over $MAX_MANIFEST_BYTES characters" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Every setting ([settings]), preference file, permission and model file as `key=value` lines,
     * in a stable order. File access: not on the main thread.
     */
    fun dump(context: Context, settings: AppSettings, models: ModelDownloadManager): List<String> {
        val lines = mutableListOf<String>()
        AppSettings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .forEach { field ->
                field.isAccessible = true
                lines += "settings.${field.name}=${shown(field.get(settings))}"
            }
        val onboarding = context.getSharedPreferences(FirstRunPrefs.FILE, Context.MODE_PRIVATE)
        lines += "firstrun.stage=" + FirstRunStage.stored(
            onboarding.getString(FirstRunPrefs.STAGE, null),
            onboarding.getBoolean(FirstRunPrefs.ONBOARDING_COMPLETE, false)
        )
        File(context.dataDir, "shared_prefs").listFiles { file -> file.name.endsWith(".xml") }.orEmpty()
            .map { it.name.removeSuffix(".xml") }
            .sorted()
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (key, value) ->
                    lines += "prefs.$name.$key=${shown(value)}"
                }
            }
        PERMISSIONS.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            lines += "permission.${permission.substringAfterLast('.')}=" + if (granted) "granted" else "denied"
        }
        SpeechModelId.entries.forEach { model ->
            lines += "model.${model.id}=${models.checkSttModel(model)}"
        }
        // The reader's builds (0.8.0): their files' check, and whether each was found with its
        // pinned SHA-256 (the first run's one read), which this reads no file for.
        ReaderModelId.entries.forEach { model ->
            lines += "model.${model.id}=${models.checkReaderModel(model)}" + if (models.readerModelVerified(model)) ", pinned" else ""
        }
        LlmModelVariant.entries.forEach { variant ->
            val filename = modelConfigForVariant(variant).llmFilename
            val path = models.getLlmModelPath(filename)
            val partial = models.llmPartialBytes(filename)
            lines += "model.${variant.name}=" + when {
                path != null -> "${File(path).length() / 1_000_000} MB"
                partial > 0 -> "partial ${partial / 1_000_000} MB"
                else -> "absent"
            }
        }
        lines += "source=" + (ModelSource.server?.let { "${it.base} (${it.files.size} files)" } ?: "registry")
        return lines
    }

    /** Writes [lines] to files/debug/settings.txt in the app's external files; returns the file. */
    fun write(context: Context, lines: List<String>): File {
        val file = File(context.getExternalFilesDir(null), "debug/settings.txt")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    /** A value as dump-settings gives it: never a long text, never a whole path. */
    private fun shown(value: Any?): String = when (value) {
        null -> "null"
        is String -> when {
            '/' in value -> value.substringAfterLast('/')
            value.length > MAX_VALUE_CHARS -> "<${value.length} chars>"
            else -> value
        }
        is Collection<*> -> "<${value.size} items>"
        else -> value.toString().let { if (it.length > MAX_VALUE_CHARS) "<${it.length} chars>" else it }
    }
}
