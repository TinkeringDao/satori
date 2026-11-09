package com.satori.presentation.firstrun

import android.content.SharedPreferences
import com.satori.domain.firstrun.FilesCheck
import com.satori.domain.firstrun.FirstRunDownloads
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NetworkWatch
import com.satori.domain.firstrun.SpeechFiles
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The registry's sizes of the first run's parts. */
val PART_BYTES = mapOf(
    FirstRunPart.SPEECH to 670_478_772L,
    FirstRunPart.MALAYALAM to 137_566_803L,
    FirstRunPart.AI_MODEL to 1_069_306_368L,
    FirstRunPart.BIGGER_AI_MODEL to 3_349_516_256L,
    FirstRunPart.READER to 241_989_173L
)

/**
 * The model files as a test sets them: speech recognition's files, the parts that pass their
 * check, the bytes of each on the phone, whether an AI model is there, the free space. A part in
 * [unread] is whole on the phone, its SHA-256 never read (the reader's files from the debug tools):
 * it isn't [checked] until a [verify] that isn't stopped reads it, which [reads] counts.
 */
class FakeFiles(
    var speech: SpeechFiles = SpeechFiles.MISSING,
    var aiModel: Boolean = false,
    var free: Long = 64_000_000_000L
) : FirstRunFiles {
    val checked = mutableSetOf<FirstRunPart>()
    val unread = mutableSetOf<FirstRunPart>()
    val onPhone = mutableMapOf<FirstRunPart, Long>()
    val discarded = mutableListOf<FirstRunPart>()
    val reads = mutableListOf<FirstRunPart>()

    override fun bytes(part: FirstRunPart): Long = PART_BYTES.getValue(part)
    override fun speech(check: Boolean): SpeechFiles = if (!check && speech == SpeechFiles.CHECKED) SpeechFiles.PRESENT else speech
    override fun checked(part: FirstRunPart): Boolean = part in checked && part !in unread

    override fun verify(part: FirstRunPart, stop: () -> Boolean): FilesCheck = when {
        part !in checked -> FilesCheck.FAILED
        part !in unread -> FilesCheck.PASSED
        stop() -> FilesCheck.NOT_NOW
        else -> {
            reads += part
            unread -= part
            FilesCheck.PASSED
        }
    }

    override fun bytesOnPhone(part: FirstRunPart): Long = onPhone[part] ?: 0L
    override fun aiModelOnPhone(): Boolean = aiModel
    override fun freeBytes(): Long = free

    override fun discard(part: FirstRunPart) {
        discarded += part
        onPhone.remove(part)
        checked.remove(part)
        unread.remove(part)
    }
}

/**
 * The downloads as a test drives them: each request is recorded in [calls] ("start SPEECH",
 * "stop SPEECH", "hold SPEECH NO_NETWORK") and the recorder's gate in [gate]; a start says
 * Downloading without a size, as the real one does first, and the test says how it goes on.
 */
class FakeDownloads : FirstRunDownloads {
    private val states = mutableMapOf<FirstRunPart, DownloadState>()
    private val version = MutableStateFlow(0)
    val calls = mutableListOf<String>()
    val gate = mutableListOf<Boolean>()
    var other = false

    override val changes: Flow<Unit> = version.map { }
    override fun state(part: FirstRunPart): DownloadState = states[part] ?: DownloadState.Idle
    override fun busyWithOther(part: FirstRunPart): Boolean = other

    override fun start(part: FirstRunPart) {
        calls += "start $part"
        set(part, DownloadState.Downloading(DownloadProgress(0, 0, 0)))
    }

    override fun stop(part: FirstRunPart) {
        calls += "stop $part"
        set(part, DownloadState.Idle)
    }

    override fun hold(part: FirstRunPart, reason: PauseReason, progress: DownloadProgress?) {
        calls += "hold $part $reason"
        set(part, DownloadState.Paused(reason, progress))
    }

    override fun holdForRecording(busy: Boolean) {
        gate += busy
    }

    override fun keepInForeground() {
        calls += "foreground"
    }

    fun set(part: FirstRunPart, state: DownloadState) {
        states[part] = state
        version.value++
    }

    fun progress(part: FirstRunPart, bytes: Long) {
        val total = PART_BYTES.getValue(part)
        set(part, DownloadState.Downloading(DownloadProgress(bytes, total, (bytes * 100 / total).toInt())))
    }

    fun fail(part: FirstRunPart, reason: DownloadFailure) = set(part, DownloadState.Error("failed", reason))

    /** The requests other than the service's, which every start makes. */
    val requests: List<String> get() = calls.filter { it != "foreground" }
}

/** The default network as a test sets it. */
class FakeNetwork(initial: NetworkFacts = NetworkFacts.WIFI) : NetworkWatch {
    override val facts = MutableStateFlow(initial)
}

/** The settings, AI notes on unless a test turns them off. */
class FirstRunSettings(settings: AppSettings = AppSettings()) : SettingsRepository {
    val settings = MutableStateFlow(settings)
    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }
    override suspend fun setHomeCategoryId(id: String) = Unit
    override suspend fun setHomeLanguage(language: RecordingLanguage) = Unit
    override suspend fun setMemoryConstrainedDetected(detected: Boolean) = Unit
    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = Unit
    override suspend fun clearLlmModelPath() = Unit
}

/**
 * The settings as the app's DataStore gives them: the first read takes [delayMs], as the real one
 * suspends on its file, and every read after it too.
 */
class SlowSettings(settings: AppSettings, private val delayMs: Long) : SettingsRepository by FirstRunSettings(settings) {
    private val settings = MutableStateFlow(settings)
    override fun getSettings(): Flow<AppSettings> = flow {
        delay(delayMs)
        emitAll(settings)
    }
}

/** SharedPreferences in memory, as the first run's store keeps them. */
class InMemoryPrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        private fun change(block: () -> Unit): SharedPreferences.Editor {
            block()
            return this
        }

        override fun putString(key: String, value: String?) = change { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = change { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = change { changes[key] = value }
        override fun putLong(key: String, value: Long) = change { changes[key] = value }
        override fun putFloat(key: String, value: Float) = change { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = change { changes[key] = value }
        override fun remove(key: String) = change { removed += key }
        override fun clear() = change { clear = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            removed.forEach(values::remove)
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
