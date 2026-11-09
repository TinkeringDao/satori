package com.satori.presentation.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recorder that is open, if any, and what it is doing, for code outside the recorder screen:
 * the quick key decides what a press does from it, and the finisher of older recordings whether a
 * capture is live.
 *
 * Each [MainViewModel] [open]s as it is created and [leave]s at the end of onCleared. The newest
 * recorder to open is the one reported; an older one that is still closing (Navigation clears a
 * popped recorder only once its exit transition has run, which waits while the screen is off) no
 * longer reports, and its leaving changes nothing. The speech and AI models are shared by all
 * recorders, so a closing recorder releases them only while [mayRelease] says so: a newer
 * recorder may already be using them.
 *
 * A recorder that a quick key press has navigated to can't open while the activity is stopped
 * (the phone is locked or the screen off), since its screen is composed only once the activity
 * starts again. The press marks it [expect]ed, so it counts as starting from the moment it was
 * asked for, and a press that cancels a start drops the recorder at once ([cancelStart]). A press
 * that starts a recording while the open one is still being saved waits for that ([awaitNotSaving])
 * and says so meanwhile ([startInFlight]), so the recorder it replaces doesn't leave by itself.
 */
@Singleton
class ActiveRecorder @Inject constructor() {

    /** What the open recorder does. */
    enum class Activity {
        /**
         * Open, but not recording and not about to: ready, blocked by an error, or done saving
         * (it goes home next, which waits until the screen is on).
         */
        IDLE,

        /**
         * A start is pending: the models load, or the start waits for the permission or the
         * screen. Also a recorder that is [expect]ed and has not opened yet.
         */
        STARTING,

        /** The microphone is live. */
        RECORDING,

        /**
         * Stopped; the last words are being saved and the recording is handed over to be finished
         * in the background. Normally well under a second, at most the recorder's drain guard.
         */
        SAVING
    }

    /**
     * The open recorder: its recording and what it does.
     *
     * @property translation Whether it records for Translate, whose live translation runs on the
     *   AI model all through the capture, so finishing an older recording waits for it to end
     */
    data class Snapshot(val sessionId: String, val activity: Activity, val translation: Boolean = false)

    // The newest recorder, EXPECTED while the one a press asked for has not opened, or null.
    private var owner: Any? = null
    private val _current = MutableStateFlow<Snapshot?>(null)
    private val _startInFlight = MutableStateFlow(false)

    /** The open recorder, or null when none is open. */
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    /**
     * Whether a quick-key start is under way: from the press until it has navigated to its
     * recorder, also while it waits for the open recorder to be saved. The recorder it replaces
     * doesn't navigate meanwhile, and Satori stays over the lock screen.
     */
    val startInFlight: StateFlow<Boolean> = _startInFlight.asStateFlow()

    /** Says whether a quick-key start is under way ([startInFlight]). */
    fun setStartInFlight(inFlight: Boolean) {
        _startInFlight.value = inFlight
    }

    /** Called by a recorder as it is created, with what it does then. It is the newest one from now on. */
    @Synchronized
    fun open(owner: Any, sessionId: String, activity: Activity, translation: Boolean = false) {
        this.owner = owner
        _current.value = Snapshot(sessionId, activity, translation)
    }

    /** Called by a recorder whenever what it does changes. Ignored once a newer recorder has opened. */
    @Synchronized
    fun report(owner: Any, sessionId: String, activity: Activity, translation: Boolean = false) {
        if (this.owner !== owner) return
        _current.value = Snapshot(sessionId, activity, translation)
    }

    /**
     * Whether [owner], as it closes, may release the shared speech and AI models: it is still the
     * newest recorder, or none is open. False while a newer recorder is open or [expect]ed, which
     * uses the loaded models instead of loading them again.
     */
    @Synchronized
    fun mayRelease(owner: Any): Boolean = this.owner == null || this.owner === owner

    /** Called by a recorder as it closes, after it has released the models it may release. */
    @Synchronized
    fun leave(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        _current.value = null
    }

    /**
     * A recorder for [sessionId] has been navigated to and will open once its screen is composed:
     * it counts as [Activity.STARTING] until it [open]s or its start is cancelled ([cancelStart]).
     */
    @Synchronized
    fun expect(sessionId: String) {
        owner = EXPECTED
        _current.value = Snapshot(sessionId, Activity.STARTING)
    }

    /**
     * The recorder for [sessionId], which was starting, has been closed: it no longer counts,
     * also before it has finished closing or before it ever opened. Nothing changes if another
     * recorder is reported.
     *
     * @return true if it was [expect]ed and never opened, so no recorder deletes its empty
     *   recording; false otherwise
     */
    @Synchronized
    fun cancelStart(sessionId: String): Boolean {
        if (_current.value?.sessionId != sessionId) return false
        val neverOpened = owner === EXPECTED
        owner = null
        _current.value = null
        return neverOpened
    }

    /**
     * Waits until the open recorder is no longer [Activity.SAVING], at most [timeoutMs].
     *
     * @return The open recorder then, or null when none is open; still saving when it timed out
     */
    suspend fun awaitNotSaving(timeoutMs: Long): Snapshot? {
        withTimeoutOrNull(timeoutMs) { current.first { it?.activity != Activity.SAVING } }
        return current.value
    }

    companion object {
        // Stands for an expected recorder until its MainViewModel opens.
        private val EXPECTED = Any()

        /**
         * What a recorder in [state] does. A recorder that is done saving is idle, also while it
         * waits for the screen to take it home, which can't happen while the activity is stopped.
         */
        fun activityOf(state: MainUiState): Activity = when {
            state.saving -> Activity.SAVING
            state.isRecording -> Activity.RECORDING
            state.isStartingRecording -> Activity.STARTING
            else -> Activity.IDLE
        }

        /**
         * Whether a quick-key start may open its recorder, now that its recording is created, in
         * place of [replaced], the idle recorder it found open (null: none). [open] is the open
         * recorder now. The one it replaces stays on show until then, so home doesn't show in
         * between, over the lock screen too; meanwhile it can start recording itself (its record
         * button), or another recorder can open, and the start then leaves that one be.
         */
        fun mayReplace(replaced: Snapshot?, open: Snapshot?): Boolean =
            open == null || (open.sessionId == replaced?.sessionId && open.activity == Activity.IDLE)
    }
}
