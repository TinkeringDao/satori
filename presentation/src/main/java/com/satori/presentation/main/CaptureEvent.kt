package com.satori.presentation.main

import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One event of a capture's stream as the recorder shares it among its collectors: a [Line] the
 * speech recognizer gave out, or the [End] of the stream.
 *
 * The recorder shares the stream (shareIn), and a shared flow never completes, so its collectors
 * could not tell that a capture had ended: Stop waited a fixed 4 s for the last words instead,
 * longer than they take and sometimes shorter. The stream now carries its own end after its last
 * line, so Stop waits as long as the last words take ([linesUntilEnd]).
 */
sealed interface CaptureEvent {

    /** A line of the transcript, partial or complete. */
    data class Line(val segment: TranscriptionSegment) : CaptureEvent

    /** The stream has ended: after its last line, or after a failure that was handled. */
    data object End : CaptureEvent

    companion object {
        /**
         * The events of a capture's [segments]: each one as a [Line], then [End] once the stream
         * ends by itself, also after a failure, which [onError] handles. A collector that is
         * cancelled gets no End.
         */
        fun of(segments: Flow<TranscriptionSegment>, onError: suspend (Throwable) -> Unit): Flow<CaptureEvent> =
            segments
                .catch { onError(it) }
                .map<TranscriptionSegment, CaptureEvent> { Line(it) }
                .onCompletion { cause -> if (cause == null) emit(End) }
    }
}

/** The lines of a capture until its [CaptureEvent.End], where this completes. */
fun Flow<CaptureEvent>.linesUntilEnd(): Flow<TranscriptionSegment> =
    takeWhile { it is CaptureEvent.Line }.map { (it as CaptureEvent.Line).segment }

/** The lines of a capture, for a collector that is stopped from outside: the recording service, the live notes. */
fun Flow<CaptureEvent>.lines(): Flow<TranscriptionSegment> =
    filterIsInstance<CaptureEvent.Line>().map { it.segment }

/**
 * Waits for [collector], which reads a stopped capture's lines to its end ([linesUntilEnd]), at
 * most [guardMs], and cancels it when the end doesn't come by then.
 *
 * @return Whether the capture ended within [guardMs]; false means that what it had not
 *   transcribed by then is missing from the transcript ([TranscriptGaps.drainTimedOut])
 */
internal suspend fun awaitCaptureEnd(collector: Job?, guardMs: Long): Boolean {
    val ended = withTimeoutOrNull(guardMs) {
        collector?.join()
        true
    } != null
    if (!ended) collector?.cancel()
    return ended
}
