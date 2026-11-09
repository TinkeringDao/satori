package com.satori.service

import com.satori.domain.model.CaptureState
import com.satori.service.RecordingCues.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCuesTest {

    private val cues = RecordingCues()

    /** A recording whose setting is on and whose microphone went live: its start has buzzed. */
    private fun liveRecording() {
        cues.onStarted()
        cues.onSetting(enabled = true)
        assertEquals(Cue.START, cues.onLive())
    }

    @Test
    fun `the start buzzes as the microphone goes live, when the setting was read before`() {
        cues.onStarted()
        assertNull(cues.onSetting(enabled = true))

        assertEquals(Cue.START, cues.onLive())
    }

    @Test
    fun `the start buzzes once the setting is read, when the microphone went live before`() {
        cues.onStarted()
        assertNull(cues.onLive())

        assertEquals(Cue.START, cues.onSetting(enabled = true))
    }

    @Test
    fun `the start doesn't buzz at the service's start, only at the live microphone`() {
        cues.onStarted()
        cues.onSetting(enabled = true)

        assertNull(cues.onCapture(CaptureState.IDLE))
        assertNull(cues.onCapture(CaptureState.OPENING))
        assertEquals(Cue.START, cues.onCapture(CaptureState.LISTENING))
        assertNull(cues.onCapture(CaptureState.TRANSCRIBING))
    }

    @Test
    fun `a capture that is transcribing at once counts as live`() {
        cues.onStarted()
        cues.onSetting(enabled = true)

        assertEquals(Cue.START, cues.onCapture(CaptureState.TRANSCRIBING))
    }

    @Test
    fun `a recording buzzes twice as its stop is asked for`() {
        liveRecording()

        assertEquals(Cue.STOP, cues.onStopAsked())
    }

    @Test
    fun `a stop reported three times buzzes once`() {
        // "Stopping" from the recorder, the capture's end, then the service stopping after the hand-off.
        liveRecording()

        assertEquals(Cue.STOP, cues.onStopAsked())
        assertNull(cues.onCapture(CaptureState.IDLE))
        assertNull(cues.onEnded())
    }

    @Test
    fun `a capture that ends on an error while live buzzes as a stop`() {
        liveRecording()

        assertEquals(Cue.STOP, cues.onCapture(CaptureState.IDLE))
        assertNull(cues.onEnded())
    }

    @Test
    fun `the service stopping a live recording without a stop asked for buzzes as a stop`() {
        // The recorder closed while recording.
        liveRecording()

        assertEquals(Cue.STOP, cues.onEnded())
        assertNull(cues.onCapture(CaptureState.IDLE))
    }

    @Test
    fun `a capture that ends before it went live fails once the service is stopped without a stop asked for`() {
        // An error before the microphone delivered: the recorder stops the service, no "stopping".
        cues.onStarted()
        cues.onSetting(enabled = true)
        cues.onCapture(CaptureState.OPENING)

        assertNull(cues.onCapture(CaptureState.IDLE))
        assertEquals(Cue.FAILED, cues.onEnded())
        assertNull(cues.onEnded())
    }

    @Test
    fun `a stop asked for after a capture that never went live has ended is no failure`() {
        // The capture's end reached the service before the "stopping" the recorder sent first.
        cues.onStarted()
        cues.onSetting(enabled = true)
        cues.onCapture(CaptureState.OPENING)
        assertNull(cues.onCapture(CaptureState.IDLE))

        assertNull(cues.onStopAsked())
        assertNull(cues.onEnded())
    }

    @Test
    fun `a capture that went live and ended before the setting was read buzzes neither way`() {
        cues.onStarted()
        cues.onCapture(CaptureState.OPENING)
        cues.onCapture(CaptureState.LISTENING)
        cues.onCapture(CaptureState.IDLE)

        assertNull(cues.onSetting(enabled = true))
        assertNull(cues.onEnded())
    }

    @Test
    fun `a capture that went live, ended and was stopped before the setting was read buzzes neither way`() {
        cues.onStarted()
        cues.onCapture(CaptureState.LISTENING)
        cues.onCapture(CaptureState.IDLE)
        cues.onEnded()

        assertNull(cues.onSetting(enabled = true))
    }

    @Test
    fun `a live recording the service stopped before the setting was read buzzes neither way`() {
        // The recorder closed while recording, before the capture's end was seen.
        cues.onStarted()
        cues.onLive()
        assertNull(cues.onEnded())

        assertNull(cues.onSetting(enabled = true))
    }

    @Test
    fun `the next capture going live doesn't make an ended one live`() {
        // The capture failed, and the next one opened before the recorder's stop of this one came.
        cues.onStarted()
        cues.onSetting(enabled = true)
        cues.onCapture(CaptureState.OPENING)
        cues.onCapture(CaptureState.IDLE)

        assertNull(cues.onCapture(CaptureState.OPENING))
        assertNull(cues.onCapture(CaptureState.LISTENING))
        assertEquals(Cue.FAILED, cues.onEnded())
    }

    @Test
    fun `the service stopping a recording that never went live is a failed start`() {
        // The recorder gave up before the capture was seen to begin.
        cues.onStarted()
        cues.onSetting(enabled = true)

        assertEquals(Cue.FAILED, cues.onEnded())
    }

    @Test
    fun `a failed start is told once the setting is read`() {
        cues.onStarted()
        assertNull(cues.onEnded())

        assertEquals(Cue.FAILED, cues.onSetting(enabled = true))
    }

    @Test
    fun `a failed start whose setting isn't read yet keeps the service for it`() {
        cues.onStarted()
        cues.onCapture(CaptureState.OPENING)
        cues.onCapture(CaptureState.IDLE)
        assertFalse(cues.awaitsSetting) // The service has not been told to stop yet

        cues.onEnded()
        assertTrue(cues.awaitsSetting)

        cues.onSetting(enabled = false)
        assertFalse(cues.awaitsSetting)
    }

    @Test
    fun `only a failed start keeps the service for the setting`() {
        // A recording that went live: no cue is owed once it stopped without its setting.
        cues.onStarted()
        cues.onLive()
        cues.onEnded()
        assertFalse(cues.awaitsSetting)

        // A recording stopped while it opened: no failure either.
        cues.onStarted()
        cues.onStopAsked()
        cues.onEnded()
        assertFalse(cues.awaitsSetting)

        // A failed start whose setting is in: it has buzzed already.
        cues.onStarted()
        cues.onSetting(enabled = true)
        assertEquals(Cue.FAILED, cues.onEnded())
        assertFalse(cues.awaitsSetting)

        // No recording at all.
        assertFalse(RecordingCues().awaitsSetting)
    }

    @Test
    fun `a stop asked for while the microphone opens is no failure, also when the setting comes late`() {
        cues.onStarted()
        cues.onCapture(CaptureState.OPENING)
        cues.onStopAsked()
        cues.onEnded()

        assertFalse(cues.awaitsSetting)
        assertNull(cues.onSetting(enabled = true))
    }

    @Test
    fun `a recording stopped while it opened buzzes neither way`() {
        cues.onStarted()
        cues.onSetting(enabled = true)
        cues.onCapture(CaptureState.OPENING)

        assertNull(cues.onStopAsked())
        assertNull(cues.onCapture(CaptureState.LISTENING))
        assertNull(cues.onCapture(CaptureState.IDLE))
        assertNull(cues.onEnded())
    }

    @Test
    fun `no stop buzzes for a recording whose start didn't`() {
        // The microphone went live and stopped before the setting was read.
        cues.onStarted()
        cues.onLive()
        assertNull(cues.onStopAsked())

        assertNull(cues.onSetting(enabled = true))
        assertNull(cues.onEnded())
    }

    @Test
    fun `nothing buzzes with the setting off`() {
        cues.onStarted()
        assertNull(cues.onSetting(enabled = false))
        assertNull(cues.onLive())
        assertNull(cues.onStopAsked())
        assertNull(cues.onEnded())
    }

    @Test
    fun `a failed start is silent with the setting off`() {
        cues.onStarted()
        cues.onSetting(enabled = false)
        cues.onCapture(CaptureState.OPENING)

        assertNull(cues.onCapture(CaptureState.IDLE))
        assertNull(cues.onEnded())
    }

    @Test
    fun `nothing buzzes without a recording`() {
        assertNull(cues.onLive())
        assertNull(cues.onStopAsked())
        assertNull(cues.onEnded())
        assertNull(cues.onSetting(enabled = true))
    }

    @Test
    fun `a second reading of the setting doesn't buzz again`() {
        liveRecording()

        assertNull(cues.onSetting(enabled = true))
    }

    @Test
    fun `the cues follow the setting the recording was started with`() {
        // Settings can't be opened while recording, but the pair stays a pair either way.
        cues.onStarted()
        cues.onSetting(enabled = false)
        assertNull(cues.onSetting(enabled = true))
        assertNull(cues.onLive())
        assertNull(cues.onStopAsked())
    }

    @Test
    fun `each recording reads the setting again`() {
        cues.onStarted()
        cues.onSetting(enabled = false)
        cues.onLive()
        cues.onStopAsked()

        liveRecording()
        assertEquals(Cue.STOP, cues.onStopAsked())
    }

    @Test
    fun `a start after an unreported stop counts as a new recording`() {
        liveRecording()

        liveRecording()
        assertEquals(Cue.STOP, cues.onStopAsked())
        assertNull(cues.onEnded())
    }

    @Test
    fun `a new recording doesn't take the end of the last one's capture for its own`() {
        // The service starts the next recording before its capture has begun.
        liveRecording()
        cues.onStopAsked()

        cues.onStarted()
        cues.onSetting(enabled = true)
        assertNull(cues.onCapture(CaptureState.IDLE))
        cues.onCapture(CaptureState.OPENING)
        assertEquals(Cue.START, cues.onCapture(CaptureState.LISTENING))
    }
}
