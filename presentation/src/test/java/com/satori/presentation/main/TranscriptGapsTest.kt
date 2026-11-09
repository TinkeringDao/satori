package com.satori.presentation.main

import com.satori.domain.model.CaptureState
import com.satori.domain.repository.MicrophoneLostException
import com.satori.domain.repository.SpeechLeftForAfterStopException
import com.satori.presentation.main.RecordingEnd.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGapsTest {

    private val notAsked: () -> Boolean = { error("the recognition is asked only for a capture that only listened") }

    /** A recording this recorder records in, with nothing from before. */
    private fun recording() = RecordingEnd().apply {
        opened(storedLengthMs = 0L, hasLines = false)
        captureStarted()
    }

    @Test
    fun `a failure of the recognizer leaves a gap, a lost microphone none`() {
        val failedEnd = recording()
        val failed = TranscriptGaps(failedEnd)
        val micLostEnd = recording()
        val micLost = TranscriptGaps(micLostEnd)

        failed.captureFailed(IllegalStateException("decoder failed"), current = true, recognizerHeard = false)
        micLost.captureFailed(MicrophoneLostException("No audio for 5000 ms"), current = true, recognizerHeard = false)

        assertTrue(failed.recognizerFailed)
        assertTrue(failedEnd.atStop().mayLackSpeech)
        assertFalse(micLost.recognizerFailed)
        assertFalse(micLostEnd.atStop().mayLackSpeech)
    }

    @Test
    fun `a capture that Stop ended before its speech model was in leaves a gap for the finisher`() {
        val end = recording()
        val gaps = TranscriptGaps(end)

        gaps.captureFailed(SpeechLeftForAfterStopException("Stop came before the live speech model was in"), current = true, recognizerHeard = true)
        end.captureStopped(held = "", recognizerHeard = true)

        assertTrue(gaps.recognizerFailed)
        assertEquals(Facts(captured = true, heard = true, mayLackSpeech = true), end.atStop())
    }

    @Test
    fun `a capture whose end Stop gave up on may lack what it recorded`() {
        for (state in listOf(CaptureState.LISTENING, CaptureState.TRANSCRIBING, CaptureState.IDLE)) {
            val end = recording()
            val gaps = TranscriptGaps(end)

            gaps.drainTimedOut(state)
            end.captureStopped(held = "", recognizerHeard = false)

            assertTrue(state.name, gaps.recognizerFailed)
            // Kept for its length or its audio, though no line holds a word.
            assertEquals(state.name, Facts(captured = true, heard = false, mayLackSpeech = true), end.atStop())
        }
    }

    @Test
    fun `a capture that never heard the microphone lacks nothing when Stop gives up on it`() {
        val end = recording()
        val gaps = TranscriptGaps(end)

        gaps.drainTimedOut(CaptureState.OPENING)
        end.captureStopped(held = "", recognizerHeard = false)

        assertFalse(gaps.recognizerFailed)
        assertEquals(Facts(captured = true, heard = false, mayLackSpeech = false), end.atStop())
    }

    @Test
    fun `a recorder that closes while the speech model loads leaves a gap`() {
        val end = recording()
        val gaps = TranscriptGaps(end)

        gaps.closedWhileCapturing(CaptureState.LISTENING, recognizerHeard = { false })

        assertTrue(gaps.recognizerFailed)
        assertEquals(Facts(captured = true, heard = false, mayLackSpeech = true), end.atClose(null, notAsked))
    }

    @Test
    fun `what the recognition still had open counts as heard for a capture that only listened`() {
        val end = recording()

        TranscriptGaps(end).closedWhileCapturing(CaptureState.LISTENING, recognizerHeard = { true })

        assertTrue(end.atClose(null, notAsked)!!.heard)
    }

    @Test
    fun `a capture that transcribes as the recorder closes is left to what its recognition knows`() {
        for (state in listOf(CaptureState.OPENING, CaptureState.TRANSCRIBING, CaptureState.IDLE)) {
            val end = recording()
            val gaps = TranscriptGaps(end)

            gaps.closedWhileCapturing(state, recognizerHeard = notAsked)

            assertFalse(state.name, gaps.recognizerFailed)
            assertEquals(state.name, Facts(captured = true, heard = false, mayLackSpeech = false), end.atClose(null) { false })
        }
    }
}
