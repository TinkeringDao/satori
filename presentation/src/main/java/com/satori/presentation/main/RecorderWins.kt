package com.satori.presentation.main

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// The recorder wins: the owner records with the quick key at any moment, and a recording must never
// fail or stutter because of other work. Work that a screen started before a capture (Transcribe
// again, Write or Rewrite notes and Find to-dos and ideas again on a recording's page, the mind
// map's AI work) stops at its next safe point once a recorder leaves IDLE, keeps what was there
// before it, and says so once on its screen. On the phone a Transcribe again with a large speech
// model went on beside a recording a quick-key press had started: the recording dropped 4.5 % of
// its audio, its main thread waited 3.4 s for a garbage collection, and the Transcribe again never
// finished. The finisher of recordings keeps its own rules (RecordingFinisher).

/**
 * Whether this recorder has left IDLE: a recording is starting (its models load, or a quick-key
 * press waits for its screen), live, or being saved. An open recorder that is ready or done saving
 * leaves other work be, as does none at all.
 */
internal val ActiveRecorder.Snapshot?.capturing: Boolean
    get() = this != null && activity != ActiveRecorder.Activity.IDLE

/**
 * Runs [work] until it ends or a recorder leaves IDLE ([capturing]), whichever comes first. A
 * recorder that leaves IDLE cancels [work], which stops at its next suspension point, where its own
 * finally blocks keep what was there; a store it began under NonCancellable runs to its end. This
 * returns once [work] has ended.
 *
 * [onStopped] is called once, after [work] has ended, when a recorder stopped it: also when one had
 * left IDLE before [work] could begin (it doesn't begin then), and when the caller is cancelled
 * while [work] winds down, as a quick-key start closes the screen that runs it.
 *
 * @return true when [work] ran to its end; false when a recorder stopped it
 */
internal suspend fun StateFlow<ActiveRecorder.Snapshot?>.runUntilCapture(
    onStopped: () -> Unit,
    work: suspend () -> Unit
): Boolean {
    if (value.capturing) {
        onStopped()
        return false
    }
    var stopped = false
    try {
        coroutineScope {
            val running = launch { work() }
            val watcher = launch {
                first { it.capturing }
                // Work that has just ended isn't stopped.
                if (!running.isCompleted) {
                    stopped = true
                    running.cancel()
                }
            }
            running.join()
            watcher.cancel()
        }
    } finally {
        if (stopped) onStopped()
    }
    return !stopped
}

/**
 * The work a recorder stopped ([runUntilCapture]) that its screen has yet to tell, each once. A
 * quick-key start opens its recorder above home in place of the screens over it, so the screen that
 * ran the work is closing as the work stops: its view model lives on only until the screen's exit
 * transition has run (which waits while the screen is off), and a notice sent to that screen would
 * go with it. So the notice waits here, and another view model of the same screen takes it and
 * shows it ([stopsOn]): the next one to open, or one already open again, since the stop is told
 * only once the stopped work has wound down, which can take a model load (5-15 s). The view model
 * that ran the work never takes it. In memory only: after a process death the page shows what is
 * there.
 */
@Singleton
class StoppedWork @Inject constructor() {

    /** What was stopped, named by the screen that runs it. */
    enum class Kind {
        /** Transcribe again, on a recording's page. */
        TRANSCRIBE_AGAIN,

        /** Write or Rewrite notes, on a recording's page. */
        REWRITE_NOTES,

        /** Find to-dos and ideas again, on a recording's page. */
        FIND_TODOS,

        /** The mind map's AI work, on one of its views. */
        MAP
    }

    private data class Entry(val kind: Kind, val screen: Any)

    // What is yet to be told, each with the view model whose work it was.
    private val entries = MutableStateFlow<Map<Entry, Any>>(emptyMap())

    /**
     * [kind] was stopped on [screen] (a recording's id for its page, the view's key for the mind
     * map: a value with equality), the work of [poster], the view model that ran it. Told once
     * however often it is posted.
     */
    fun post(kind: Kind, screen: Any, poster: Any) {
        entries.update { it + (Entry(kind, screen) to poster) }
    }

    /**
     * What of [kinds] was stopped on [screen] by the work of another view model than [taker],
     * each once, as it is posted and what was posted before: the view model of a screen collects
     * it while it lives.
     */
    fun stopsOn(screen: Any, kinds: Set<Kind>, taker: Any): Flow<Kind> =
        entries.transform { take(screen, kinds, taker).forEach { emit(it) } }

    /** Takes what [stopsOn] gives now; a second call gets none of it. */
    internal fun take(screen: Any, kinds: Set<Kind>, taker: Any): Set<Kind> {
        var taken = emptySet<Kind>()
        entries.update { held ->
            val told = held.filter { (entry, poster) -> entry.screen == screen && entry.kind in kinds && poster !== taker }
            taken = told.keys.mapTo(mutableSetOf()) { it.kind }
            held - told.keys
        }
        return taken
    }
}
