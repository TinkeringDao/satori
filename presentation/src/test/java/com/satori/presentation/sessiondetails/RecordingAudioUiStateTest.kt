package com.satori.presentation.sessiondetails

import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.RecordingAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioUiStateTest {

    private val ready = RecordingAudioUiState(
        audio = RecordingAudio(sessionId = "s1", path = "/files/audio/s1.wav", durationMs = 60_000),
        speechModel = true
    )

    @Test
    fun `transcribe again needs the audio, a speech model, and nothing else using them`() {
        assertTrue(ready.canTranscribe)
        assertFalse(ready.copy(audio = null).canTranscribe)
        assertFalse(ready.copy(speechModel = false).canTranscribe)
        assertFalse(ready.copy(recorderBusy = true).canTranscribe)
        assertFalse(ready.copy(transcription = TranscriptionProgress(fraction = 0.3f)).canTranscribe)
    }

    @Test
    fun `transcribe again waits while the AI is in use, and is still offered`() {
        val busy = ready.copy(aiBusy = true)

        assertFalse(busy.canTranscribe)
        assertTrue(busy.offersTranscribe)
        assertFalse(busy.copy(transcription = TranscriptionProgress()).offersTranscribe)
        assertFalse(busy.copy(recorderBusy = true).offersTranscribe)
    }

    @Test
    fun `the player says how much room the audio takes, once that is known`() {
        assertEquals(1_920_044L, ready.copy(audio = ready.audio?.copy(sizeBytes = 1_920_044L)).sizeBytes)
        assertNull(ready.sizeBytes)
        assertNull(RecordingAudioUiState().sizeBytes)
    }

    @Test
    fun `compressed audio says so, a WAV doesn't`() {
        assertTrue(ready.copy(audio = ready.audio?.copy(encoding = AudioEncoding.AAC)).compressed)
        assertFalse(ready.compressed)
        assertFalse(RecordingAudioUiState().compressed)
    }

    @Test
    fun `play goes on from where it was paused`() {
        assertNull(RecordingPlayer.startPosition(requestedMs = null, positionMs = 12_000, durationMs = 60_000))
    }

    @Test
    fun `play starts again from the start once the audio has played to its end`() {
        assertEquals(0L, RecordingPlayer.startPosition(requestedMs = null, positionMs = 60_000, durationMs = 60_000))
        assertEquals(0L, RecordingPlayer.startPosition(requestedMs = null, positionMs = 59_900, durationMs = 60_000))
    }

    @Test
    fun `a place asked for is played from, within the audio`() {
        assertEquals(3_100L, RecordingPlayer.startPosition(requestedMs = 3_100, positionMs = 60_000, durationMs = 60_000))
        assertEquals(60_000L, RecordingPlayer.startPosition(requestedMs = 75_000, positionMs = 0, durationMs = 60_000))
        assertEquals(0L, RecordingPlayer.startPosition(requestedMs = -5, positionMs = 0, durationMs = 60_000))
    }
}
