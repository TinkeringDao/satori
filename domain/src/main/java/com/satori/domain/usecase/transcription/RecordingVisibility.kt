package com.satori.domain.usecase.transcription

import com.satori.domain.model.SessionListItem

/**
 * Which recordings the home list shows.
 *
 * A recording row is created before anything is captured, so one that was never started, or
 * failed to, would sit in the list as an empty "Voice note 0:00". The list hides those; it never
 * deletes them (the recorder removes them at the source).
 */
object RecordingVisibility {

    /** A recording shorter than this that kept nothing is an attempt that captured nothing. */
    const val MIN_KEPT_DURATION_MS = 2_000L

    /** True when [item] has no transcript, no notes and is shorter than [MIN_KEPT_DURATION_MS]. */
    fun holdsNothing(item: SessionListItem): Boolean =
        !item.hasTranscript && !item.hasNotes && item.session.durationMs < MIN_KEPT_DURATION_MS

    /**
     * The id of the recording being made right now: the one the open recorder reports as recording
     * or as saving its last words. Null while none does.
     *
     * Not the newest recording while the recording service is busy, as it was before recordings
     * were finished in the background: a quick-key press can create the next recording while the
     * service still stops for the one before, and the newest is then not the one being made.
     *
     * @param capturingSessionId The recording of the open recorder while it records or saves,
     *   null while it starts, is idle, or no recorder is open
     */
    fun recordingNowId(capturingSessionId: String?): String? = capturingSessionId

    /** [items] without the empty ones, but with the recording being made ([recordingNowId]) kept. */
    fun visible(items: List<SessionListItem>, recordingNowId: String?): List<SessionListItem> =
        items.filter { it.session.id == recordingNowId || !holdsNothing(it) }
}
