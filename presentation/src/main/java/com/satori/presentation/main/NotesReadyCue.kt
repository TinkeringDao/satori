package com.satori.presentation.main

/**
 * Tells the owner that the notes of a recording are ready (design 3.9 and the NOTIFY_PING nudge):
 * the finisher calls it once a recording's finishing ends with notes stored. The first notes ever
 * get a buzz of their own, and any recording a "notes are ready" notification while the app is out
 * of sight; never while a recording is live. Implemented in the app, which has the vibrator and the
 * notifications. Main-safe, never throws.
 */
fun interface NotesReadyCue {

    /** The finishing of [sessionId] has ended, and the recording has notes. */
    fun notesReady(sessionId: String)
}
