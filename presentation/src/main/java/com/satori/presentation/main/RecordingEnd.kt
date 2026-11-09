package com.satori.presentation.main

import com.satori.domain.repository.MicrophoneLostException
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase

/**
 * What a recorder knows, as its recording ends, of speech that the saved lines may lack, and
 * whether the recording is still the recorder's to keep or delete. MainViewModel tells it what
 * happens and hands what it says to DiscardSilentRecordingUseCase: at Stop, before it hands a kept
 * recording to the finishing queue ([atStop]), and as the recorder closes without Stop ([close]).
 *
 * Kept out of the ViewModel so its rules are tested: which failure of the speech stream may have
 * left speech untranscribed, what counts as heard on each way out, and that a recording handed to
 * finishing, or deleted, is left be. All of it runs on the main thread.
 */
internal class RecordingEnd {

    /** What DiscardSilentRecordingUseCase is told; see its parameters. */
    data class Facts(val captured: Boolean, val heard: Boolean, val mayLackSpeech: Boolean)

    // A capture ran in this recorder.
    private var captured = false

    // The capture runs, so how it ends is not known yet.
    private var capturing = false

    // The recording held a capture before this recorder's, which no recorder saw end.
    private var heldBefore = false

    // The recording was dealt with: handed to finishing, or deleted as silent, at Stop or as the
    // recorder closed.
    private var dealtWith = false

    // The speech recognizer failed in a capture of this recorder.
    private var recognizerFailed = false

    // Words were heard, or may have been, that the saved lines may lack.
    private var heard = false

    /** Whether this recorder recorded: only then does the list explain a recording that went. */
    val hasCaptured: Boolean
        get() = captured

    /**
     * The recording as the recorder opened on it, before any capture of its own: a stored length
     * or lines mean it was recorded before, by a recorder Android ended with the app, so its last
     * words may be missing from the transcript.
     */
    fun opened(storedLengthMs: Long, hasLines: Boolean) {
        if (!captured) heldBefore = storedLengthMs > 0L || hasLines
    }

    /** The microphone opens for a capture. */
    fun captureStarted() {
        captured = true
        capturing = true
    }

    /**
     * The speech stream of a capture failed with [error], the [current] one or an earlier one of
     * this recorder. A microphone that stopped delivering audio left nothing untranscribed
     * ([MicrophoneLostException]); any other failure may have. [recognizerHeard] is what the
     * recognition knows (SttRepository.mayHaveHeardSpeech).
     */
    fun captureFailed(error: Throwable, current: Boolean, recognizerHeard: Boolean) {
        if (error !is MicrophoneLostException) recognizerFailed = true
        if (recognizerHeard) heard = true
        if (current) capturing = false
    }

    /**
     * Stop has stopped the capture and waited for its last words. [held] is the line that was on
     * screen at Stop, which counts however its line ends up. [recognizerHeard] is what the
     * recognition knows.
     */
    fun captureStopped(held: String, recognizerHeard: Boolean) {
        capturing = false
        if (recognizerHeard || DiscardSilentRecordingUseCase.holdsSpeech(held)) heard = true
    }

    /**
     * The recording was dealt with: handed to finishing, or deleted because nothing was said in
     * it, at Stop or as the recorder closed ([close]). Either way a later close leaves it be.
     */
    fun handedToFinishing() {
        dealtWith = true
    }

    /** What the silent rule is told at Stop, before a kept recording is handed to finishing. */
    fun atStop(): Facts = Facts(captured = true, heard = heard, mayLackSpeech = recognizerFailed || heldBefore)

    /**
     * What the recorder is told as it closes without Stop (Back, a way out of the microphone
     * dialog, the app closed while it records), or null once the recording was dealt with
     * ([handedToFinishing]). A capture that still runs is cut off: whatever its recognition still
     * had open ([recognizerHeard], read only then) counts as heard, as does the line on screen,
     * [onScreen], which never got a line of its own. Every other line the recorder heard was saved
     * as it came.
     */
    fun atClose(onScreen: String?, recognizerHeard: () -> Boolean): Facts? {
        if (dealtWith) return null
        if (capturing) {
            capturing = false
            if (recognizerHeard()) heard = true
        }
        if (DiscardSilentRecordingUseCase.holdsSpeech(onScreen.orEmpty())) heard = true
        return Facts(captured = captured, heard = heard, mayLackSpeech = recognizerFailed || heldBefore)
    }

    /**
     * The recorder closes without Stop: the silent rule, [deleteIfSilent], is told what [atClose]
     * says, and a kept recording this recorder recorded in goes to [handOff], which reports
     * [handedToFinishing] once the recording is queued. A recording deleted here is dealt with as
     * well, so the next close (the recorder's onCleared after Back) neither runs the rule again nor
     * hands off a recording that is gone; a hand-off that failed is tried again then. Nothing at
     * all once the recording was dealt with.
     *
     * @return true if the recording is gone
     */
    suspend fun close(
        onScreen: String?,
        recognizerHeard: () -> Boolean,
        deleteIfSilent: suspend (Facts) -> Boolean,
        handOff: suspend () -> Unit
    ): Boolean {
        val facts = atClose(onScreen, recognizerHeard) ?: return false
        val deleted = deleteIfSilent(facts)
        if (deleted) handedToFinishing() else if (facts.captured) handOff()
        return deleted
    }
}
