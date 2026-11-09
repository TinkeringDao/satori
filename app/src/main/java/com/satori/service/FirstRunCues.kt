package com.satori.service

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.repository.SettingsRepository
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.NotesReadyCue
import com.satori.presentation.sessions.NotificationAsk
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run's two cues (design 3.6 and 3.9), and "Your notes are ready", the notification the
 * NOTIFY_PING nudge offers:
 * - Ready has come, the setup's downloads checked ([FirstRunStage.READY] after
 *   [FirstRunStage.DOWNLOADING]), while the app is out of sight: one buzz of its own
 *   ([RecordingCues.Cue.SETUP_DONE]). In sight, the screen says it.
 * - The first notes of any recording are stored: one buzz of their own, once ever
 *   ([RecordingCues.Cue.NOTES_READY]), in sight or not; home's strip says it too.
 * - Any recording's notes are stored while the app is out of sight: the notification, when
 *   notifications are allowed, whose tap opens that recording's page. Nothing here asks for them;
 *   one that isn't allowed is counted ([markNotesMissed]), which makes home's strip offer the
 *   ping at the next look.
 *
 * The buzzes follow "Vibrate on start and stop". None of it comes while a recording is live: a
 * buzz then would be taken for the recording's own, and the recording's notification is the one
 * that matters. What each decision came to goes to logcat under SatoriFirstRun, names and counts
 * only: `cue=SETUP_DONE shown=true`, `notification=NOTES_READY shown=false reason=NOT_ALLOWED
 * missed=1`.
 *
 * It follows the first run from the moment the app's finisher of recordings is made, which every
 * start of the app's screens makes, as the setup's downloads only run after one.
 */
@Singleton
class FirstRunCues internal constructor(
    private val firstRun: Lazy<FirstRunCoordinator>,
    private val settings: SettingsRepository,
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val inFront: () -> Boolean,
    private val notificationsAllowed: () -> Boolean,
    private val buzz: (RecordingCues.Cue) -> Unit,
    private val notify: (sessionId: String) -> Unit,
    private val firstNotes: OnceFlag,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Counts one more notes-ready notification that couldn't be posted, as not allowed, and returns the count. */
    private val markNotesMissed: () -> Int
) : NotesReadyCue {

    @Inject constructor(
        @ApplicationContext context: Context,
        firstRun: Lazy<FirstRunCoordinator>,
        settings: SettingsRepository,
        activeRecorder: ActiveRecorder,
        haptics: RecordingHaptics,
        notifications: LlmProcessingNotificationManager
    ) : this(
        firstRun = firstRun,
        settings = settings,
        recorder = activeRecorder.current,
        inFront = ::appInFront,
        notificationsAllowed = notifications::notesReadyAllowed,
        buzz = haptics::play,
        notify = notifications::showNotesReady,
        firstNotes = OnceFlag.inPreferences(context, FIRST_NOTES_CUED),
        // A cue is never worth the app: what fails here is logged and dropped.
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "First run cue failed", e) }
        ),
        log = { Log.i(TAG, it) },
        markNotesMissed = { NotificationAsk.markNotesMissed(context) }
    )

    /** Something that is true once, the first time it is taken: the first notes' buzz, once ever. */
    fun interface OnceFlag {
        /** True the first time; false from then on, also after a restart. */
        fun take(): Boolean

        companion object {
            /** Kept as [key] in the cues' own preferences, so the first notes buzz once per install. */
            fun inPreferences(context: Context, key: String): OnceFlag {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                return OnceFlag {
                    synchronized(prefs) {
                        if (prefs.getBoolean(key, false)) {
                            false
                        } else {
                            prefs.edit().putBoolean(key, true).apply()
                            true
                        }
                    }
                }
            }
        }
    }

    init {
        scope.launch { followSetup() }
    }

    override fun notesReady(sessionId: String) {
        scope.launch {
            if (firstNotes.take()) cue(RecordingCues.Cue.NOTES_READY)
            val quiet = notificationQuiet(recordingLive(), inFront(), notificationsAllowed())
            if (quiet == null) notify(sessionId)
            // Only a refusal for want of the permission is what the ping is for: in front the
            // screen said it, and a live recording's notification is the one that matters.
            val missed = if (quiet == Quiet.NOT_ALLOWED) " missed=${markNotesMissed()}" else ""
            log(line("notification", "NOTES_READY", quiet) + missed)
        }
    }

    /** Plays Ready's buzz as the first run's stage goes from Getting ready to Ready ([setupDone]). */
    private suspend fun followSetup() {
        var before: FirstRunStage? = null
        firstRun.get().state.map { it.stage }.distinctUntilChanged().collect { stage ->
            if (setupDone(before, stage)) cue(RecordingCues.Cue.SETUP_DONE)
            before = stage
        }
    }

    /** Plays [cue] unless [cueQuiet] says why not, and logs which. */
    private suspend fun cue(cue: RecordingCues.Cue) {
        val vibrate = settings.getSettings().first().vibrateOnStartStop
        val quiet = cueQuiet(cue, recordingLive(), inFront(), vibrate)
        if (quiet == null) buzz(cue)
        log(line("cue", cue.name, quiet))
    }

    /** A recorder starts, records or saves. */
    private fun recordingLive(): Boolean = recorder.value.let { it != null && it.activity != ActiveRecorder.Activity.IDLE }

    /** Why a cue or the notification stays quiet. */
    enum class Quiet {
        /** A recording is live. */
        RECORDING,

        /** The app is in front, where the screen says it. */
        IN_FRONT,

        /** "Vibrate on start and stop" is off. */
        VIBRATION_OFF,

        /** Notifications aren't allowed, or their channel is switched off. */
        NOT_ALLOWED
    }

    companion object {
        private const val TAG = "SatoriFirstRun"
        private const val PREFS = "first_run_cues"
        private const val FIRST_NOTES_CUED = "first_notes_cued"

        /** Whether the stage went from [before] to [now] as the setup's downloads were checked: Getting ready turns into Ready. */
        internal fun setupDone(before: FirstRunStage?, now: FirstRunStage): Boolean =
            before == FirstRunStage.DOWNLOADING && now == FirstRunStage.READY

        /**
         * Why [cue] doesn't play, or null when it does: never while a recording is live, Ready's
         * only while the app is out of sight, neither with "Vibrate on start and stop" off.
         */
        internal fun cueQuiet(cue: RecordingCues.Cue, recordingLive: Boolean, inFront: Boolean, vibrate: Boolean): Quiet? = when {
            recordingLive -> Quiet.RECORDING
            cue == RecordingCues.Cue.SETUP_DONE && inFront -> Quiet.IN_FRONT
            !vibrate -> Quiet.VIBRATION_OFF
            else -> null
        }

        /** Why "Your notes are ready" isn't posted, or null when it is: only out of sight, allowed, and with no recording live. */
        internal fun notificationQuiet(recordingLive: Boolean, inFront: Boolean, allowed: Boolean): Quiet? = when {
            recordingLive -> Quiet.RECORDING
            inFront -> Quiet.IN_FRONT
            !allowed -> Quiet.NOT_ALLOWED
            else -> null
        }

        /** The field log's line: "cue=SETUP_DONE shown=true", or "... shown=false reason=IN_FRONT". */
        internal fun line(kind: String, name: String, quiet: Quiet?): String =
            if (quiet == null) "$kind=$name shown=true" else "$kind=$name shown=false reason=$quiet"

        /** Whether one of the app's screens is in front: its process is the foreground one. */
        private fun appInFront(): Boolean {
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
