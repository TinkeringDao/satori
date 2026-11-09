package com.satori.feature.stt.datasource

import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import java.util.concurrent.atomic.AtomicLong

/**
 * What the live recognition of one capture knows of speech besides the lines it gives out, so the
 * recorder never deletes a recording in which something may have been said
 * (SttDataSource.mayHaveHeardSpeech): words it gave out, which may still not reach the database
 * (a save fails, or the recorder stopped taking lines); an utterance it heard begin whose line was
 * not out when the capture was cut off; audio it never transcribed; and audio recorded that it
 * hasn't heard yet. Written by [SpeechRecognition], the decode it runs and the capture that
 * records for it, read from the recorder's thread.
 */
internal class SpeechEvidence {
    // A result given out held a letter or a digit.
    @Volatile private var words = false

    // An utterance the voice detection heard begin has no line out yet.
    @Volatile private var openUtterance = false

    // A stretch of the audio was never transcribed.
    @Volatile private var dropped = false

    // Samples recorded for the recognition that its voice detection hasn't been over yet.
    private val unheard = AtomicLong()

    /**
     * Whether speech was heard, or may have been: words given out, an utterance left open, audio
     * dropped, or more audio waiting for the recognition than the read it is behind by as it keeps
     * pace. Read at any time: the recorder reads it as it closes, while the capture may still run.
     */
    val mayHaveHeardSpeech: Boolean
        get() = words || openUtterance || dropped || unheard.get() > KEEPING_PACE_SAMPLES

    /** A result, complete or partial, is about to be given out with [text]. */
    fun givingOut(text: String) {
        if (DiscardSilentRecordingUseCase.holdsSpeech(text)) words = true
    }

    /** The voice detection heard an utterance begin. */
    fun utteranceBegan() {
        openUtterance = true
    }

    /**
     * The utterance's line is out, or its audio decoded to nothing, while the recognition still
     * runs ([running]). One that was cancelled gives its lines to no one, so an utterance that was
     * open as it was cut off stays open. Not said at a forced split after 30 s of speech: the
     * speaker is still talking there, so the utterance goes on.
     */
    fun utteranceEnded(running: Boolean) {
        if (running) openUtterance = false
    }

    /** A step failed, or found the speech model released, and its stretch of audio was never transcribed. */
    fun audioDropped() {
        dropped = true
    }

    /**
     * The capture recorded [samples] samples for the recognition. They count as speech until the
     * recognition has heard them ([audioHeard]), as a capture cut off before then never
     * transcribes them: the microphone opens before the speech model loads, so seconds of audio
     * wait while it loads and while the recognition catches up afterwards.
     */
    fun audioRecorded(samples: Int) {
        unheard.addAndGet(samples.toLong())
    }

    /** The voice detection has been over [samples] more samples of the audio recorded. */
    fun audioHeard(samples: Int) {
        unheard.addAndGet(-samples.toLong())
    }

    private companion object {
        // One microphone read, 100 ms: the most the recognition is behind by while it keeps pace,
        // as it takes each read within milliseconds of its coming.
        const val KEEPING_PACE_SAMPLES = SpeechRecognition.SAMPLE_RATE_HZ / 10
    }
}
