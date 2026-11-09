package com.satori.quickkey

import android.content.Context
import android.content.Intent
import com.satori.data.quickkey.QuickKeySettings
import com.satori.domain.model.RecordingLanguage
import com.satori.ui.MainActivity

/**
 * One quick key press, as [QuickKeyActivity] hands it to MainActivity. This is the contract
 * between the two; docs/QUICK_KEY.md has the rest of the quick key.
 *
 * ## The intent MainActivity receives
 * Built by [toMainActivityIntent]:
 * - Explicit component MainActivity, with [Intent.ACTION_MAIN] and [Intent.CATEGORY_LAUNCHER].
 *   Shaped like the launcher's intent on purpose: when a press creates Satori's task, this intent
 *   becomes the task's root intent, and a later tap on the launcher icon would stack a second
 *   MainActivity unless the two intents match (AOSP ActivityStarter.complyActivityFlags and
 *   Task.isSameIntentFilter).
 * - Flags NEW_TASK, CLEAR_TOP and SINGLE_TOP: a running MainActivity receives onNewIntent and
 *   stays the only instance. Activities above it in Satori's task close, such as an app opened
 *   from Satori's share sheet.
 * - [EXTRA_QUICK_RECORD_CATEGORY], String, never blank: id of the RecordingCategory to record as.
 * - [EXTRA_QUICK_STOP], Boolean: whether a press stops a running recording ([stopIfRecording]).
 * - [EXTRA_QUICK_RECORD_LANGUAGE], String: the code of the RecordingLanguage to record in ("ml"
 *   for Malayalam), absent for English or German ([language]).
 *
 * ## What a press does
 * With [stopIfRecording] the key is a toggle: a press starts a recording and the next one stops
 * it. Getting the recorder ready takes a moment (the microphone opens at once, the speech model
 * loads next to it), and Stop saves the last words before the recording is handed over to be
 * finished in the background: normally well under a second, at most the recorder's drain guard
 * of 15 s. The notes, the name and the to-dos are written afterwards, while the next recording
 * may already run. A press must not open a second recorder, and a stop must not be lost. So what
 * a press does depends on what Satori is doing, the [RecorderState], and [actionFor] gives the
 * [Action]:
 *
 * | RecorderState | stopIfRecording = true | stopIfRecording = false |
 * |---|---|---|
 * | NOT_SET_UP | SHOW | SHOW |
 * | IDLE | START | START |
 * | STARTING | CANCEL_START | SHOW |
 * | RECORDING | STOP | SHOW |
 * | SAVING | START | START |
 *
 * A press while STARTING cancels instead of waiting to stop the recording once it runs: that
 * would only add a recording of a second or two. A press while SAVING records again: it waits
 * for the recording to be saved (at most 16 s; still saving then, it only shows Satori), and
 * starts the next one in place of the recorder. MainActivity never sees a press that comes
 * within [QuickKeyRepeatFilter.WINDOW_MS] of the last one [QuickKeyActivity] forwarded: the
 * activity drops it as a repeat.
 *
 * ## What MainActivity does (MainActivity.receive and handleQuickKey in MainActivity.kt)
 * 1. Call [from] in onCreate only when savedInstanceState is null (a fold or a restored process
 *    hands the old intent over again), and in onNewIntent. Null means it isn't a quick key press.
 * 2. Handle presses one at a time, in order. A press can arrive before the navigation graph
 *    exists, so queue them, e.g. in a Channel that SatoriApp collects. Decide a press only after
 *    the one before has been carried out (for START: once it has navigated to its recorder,
 *    which ActiveRecorder counts as starting from then on, as it can't open while the phone is
 *    locked). Read the state and carry out STOP or CANCEL_START in the same main-thread step, so
 *    a recorder can't start in between; START creates its session first, so check that no other
 *    recorder has opened, and that the one it replaces is still idle, right before navigating.
 *    From the press until START has navigated, mark it under way (ActiveRecorder.startInFlight):
 *    the recorder it replaces then doesn't go home by itself, and Satori stays over the lock
 *    screen.
 * 3. Work out the [RecorderState] (each value says where to read it), call [actionFor] and carry
 *    out the [Action] (each value says how).
 * 4. Treat the extras as untrusted. MainActivity is exported for the launcher, so any app can
 *    send them without going through [QuickKeyActivity].
 *
 * @property categoryId Id of the RecordingCategory to record as, never blank
 * @property stopIfRecording Whether a press stops a running recording, and cancels one that is
 *   still getting ready
 * @property language The language to record in. Malayalam records in English or German while its
 *   model isn't on the phone (QuickRecordingViewModel.createRecording), as does a category that
 *   translates.
 */
data class QuickKeyRequest(
    val categoryId: String,
    val stopIfRecording: Boolean,
    val language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
) {
    init {
        require(categoryId.isNotBlank()) { "categoryId must not be blank" }
    }

    /**
     * What Satori is doing when a press arrives. Read the recorder's part from the recorder in
     * MainActivity's back stack (its MainViewModel), not from RecordingSessionManager.sessionState,
     * which never shows STARTING or SAVING and misses parts of RECORDING (see those values).
     */
    enum class RecorderState {
        /**
         * Satori can't record yet: the first run hasn't reached Ready, so the speech files aren't
         * in and checked, or the microphone isn't allowed (FirstRunFlow.quickKeyReady, asked
         * through FirstRunViewModel.quickKeyReady as the key is pressed). The stage is read
         * synchronously as the app starts and the microphone at the press, so the value is right
         * at once. The press only brings Satori to the front, on the first run's screen.
         */
        NOT_SET_UP,

        /** None of the others: no recorder is open, or the open one is at rest or done saving. */
        IDLE,

        /**
         * A recorder has a start pending and hasn't started recording. It was opened with
         * autostart=true (by an earlier press or the record button on home), or its record
         * button was tapped while the models load, and it is loading them, asking for the
         * microphone, or waiting to be on screen: MainUiState.isStartingRecording, which
         * ActiveRecorder reports as STARTING. A recorder an earlier press navigated to counts
         * from that moment, before it has opened (ActiveRecorder.expect). sessionState stays
         * Idle throughout.
         */
        STARTING,

        /**
         * A recording is running: MainUiState.isRecording of the open recorder. sessionState
         * isn't enough: RecordingService reports Recording only when a transcript segment
         * arrives, so it is still Idle in the first seconds of every recording and throughout a
         * recording of silence.
         */
        RECORDING,

        /**
         * A recording has been stopped and its last words are being saved before it is handed
         * over to be finished in the background: MainUiState.saving, which ActiveRecorder reports
         * as SAVING. Normally well under a second, at most the recorder's drain guard. A recorder
         * that is done saving is IDLE, also while it waits for the screen to take it home; the
         * finishing that follows doesn't hold up a press.
         */
        SAVING,
    }

    /** What MainActivity does with a press; [actionFor] picks it. */
    enum class Action {
        /** Nothing more: the press has already brought Satori to the front. */
        SHOW,

        /**
         * Start a quick recording. While the open recorder is SAVING, show Satori over the lock
         * screen and wait until it is IDLE or gone (at most 16 s; still SAVING, do no more). Look
         * [categoryId] up in CategoryRepository; if that category was deleted, use the first one
         * by sortOrder. Create the session with its id and mode, in [language] as far as the phone
         * can record in it, and navigate to
         * "recording/{sessionId}?autostart=true" with popUpTo("sessions"), so the new recorder is
         * the only one. An idle recorder that was open stays on show until then, so home doesn't
         * show in between, and that navigation closes it without waiting for it. It records as
         * soon as it is on screen.
         */
        START,

        /**
         * Cancel the start that is pending: close that recorder as Back would (pop its entry).
         * It never records, and MainViewModel.onCleared deletes its session if nothing was
         * recorded in it (deleteIfSilent). A recorder that never opened (the phone stayed
         * locked) has no MainViewModel, so its empty session is deleted for it
         * (QuickRecordingViewModel.cancelStart).
         */
        CANCEL_START,

        /**
         * Stop the recording the way the notification's Stop button does: send
         * RecordingService.ACTION_REQUEST_STOP to RecordingService. That asks the recorder to
         * stop, so the last words and the length are saved and the recording is finished, and
         * stops the service itself if no recorder takes the request.
         */
        STOP,
    }

    /** What this press does while Satori is in [state]; the table is in the class comment. */
    fun actionFor(state: RecorderState): Action = when (state) {
        RecorderState.NOT_SET_UP -> Action.SHOW
        RecorderState.IDLE, RecorderState.SAVING -> Action.START
        RecorderState.STARTING -> if (stopIfRecording) Action.CANCEL_START else Action.SHOW
        RecorderState.RECORDING -> if (stopIfRecording) Action.STOP else Action.SHOW
    }

    /** The intent [QuickKeyActivity] starts MainActivity with (see the class comment). */
    fun toMainActivityIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(EXTRA_QUICK_RECORD_CATEGORY, categoryId)
            .putExtra(EXTRA_QUICK_STOP, stopIfRecording)
            .apply { language.code?.let { putExtra(EXTRA_QUICK_RECORD_LANGUAGE, it) } }

    companion object {
        const val EXTRA_QUICK_RECORD_CATEGORY = "com.satori.extra.QUICK_RECORD_CATEGORY"
        const val EXTRA_QUICK_STOP = "com.satori.extra.QUICK_STOP"
        const val EXTRA_QUICK_RECORD_LANGUAGE = "com.satori.extra.QUICK_RECORD_LANGUAGE"

        /**
         * The press [intent] carries, or null if it carries none. Also null when Recents
         * relaunches the task (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY): Recents replays the task's
         * root intent, which can be an old press, and that must not start another recording.
         */
        fun from(intent: Intent?): QuickKeyRequest? {
            if (intent == null) return null
            if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
            val categoryId = intent.getStringExtra(EXTRA_QUICK_RECORD_CATEGORY)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return QuickKeyRequest(
                categoryId = categoryId,
                stopIfRecording = intent.getBooleanExtra(
                    EXTRA_QUICK_STOP,
                    QuickKeySettings.DEFAULT_STOP_ON_SECOND_PRESS
                ),
                // Untrusted too: a code this version doesn't know reads as English or German.
                language = RecordingLanguage.fromCode(intent.getStringExtra(EXTRA_QUICK_RECORD_LANGUAGE)),
            )
        }
    }
}
