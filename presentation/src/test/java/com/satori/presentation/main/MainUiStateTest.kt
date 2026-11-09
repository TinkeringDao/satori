package com.satori.presentation.main

import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.presentation.firstrun.MicrophoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {

    private val ready = MainUiState(isInitializing = false, llmStatus = LlmStatus.Ready)
    private val saving = ready.copy(saving = true)

    @Test
    fun `a recorder that is checking its models or waiting to start is preparing`() {
        assertEquals(RecorderPhase.Preparing, MainUiState().phase)
        assertEquals(RecorderPhase.Preparing, ready.copy(isStartingRecording = true).phase)
    }

    @Test
    fun `a recorder with nothing to wait for is ready`() {
        assertEquals(RecorderPhase.Ready, ready.phase)
    }

    @Test
    fun `recording and saving come before everything else`() {
        assertEquals(RecorderPhase.Recording, ready.copy(isRecording = true, isStartingRecording = true).phase)
        assertEquals(RecorderPhase.Saving, saving.phase)
        assertEquals(RecorderPhase.Saving, saving.copy(error = RecorderError.SpeechModelFailed).phase)
    }

    @Test
    fun `a microphone not allowed or an error that blocks recording blocks the recorder`() {
        for (microphone in MicrophoneState.entries.filter { it != MicrophoneState.ALLOWED }) {
            assertEquals(microphone.name, RecorderPhase.Blocked, ready.copy(microphone = microphone).phase)
            assertEquals(microphone.name, RecorderPhase.Blocked, MainUiState(microphone = microphone).phase)
            // Blocked also while Android's window is up for the start the recorder was opened for.
            assertEquals(microphone.name, RecorderPhase.Blocked, ready.copy(microphone = microphone, isStartingRecording = true).phase)
        }
        for (error in RecorderError.entries.filter { it.recordingBlocked }) {
            assertEquals(error.name, RecorderPhase.Blocked, ready.copy(error = error).phase)
        }
    }

    @Test
    fun `the microphone read as the recorder opens or comes back`() {
        val allowed = MicrophoneState.ALLOWED
        // As it opens, or after it was taken back while open: refused before or never asked.
        assertEquals(MicrophoneState.NOT_ALLOWED, MainUiState.microphoneRead(allowed, granted = false, refusedBefore = false))
        assertEquals(MicrophoneState.REFUSED_ONCE, MainUiState.microphoneRead(allowed, granted = false, refusedBefore = true))
        // Allowed in the app's settings page, then back: allowed at once.
        for (before in MicrophoneState.entries) {
            assertEquals(allowed, MainUiState.microphoneRead(before, granted = true, refusedBefore = false))
        }
        // Back without allowing it: a refusal stays what it was.
        assertEquals(MicrophoneState.REFUSED_FOR_GOOD, MainUiState.microphoneRead(MicrophoneState.REFUSED_FOR_GOOD, granted = false, refusedBefore = false))
        assertEquals(MicrophoneState.REFUSED_ONCE, MainUiState.microphoneRead(MicrophoneState.REFUSED_ONCE, granted = false, refusedBefore = true))
        assertEquals(MicrophoneState.NOT_ALLOWED, MainUiState.microphoneRead(MicrophoneState.NOT_ALLOWED, granted = false, refusedBefore = false))
    }

    // ─── The first run ───────────────────────────────────────────────────────────

    private fun words(count: Int, complete: Boolean = true, at: Long = 1L) = TranscriptionSegment(
        id = "w$at", sessionId = "s1", text = List(count) { "word" }.joinToString(" "), timestamp = at, isComplete = complete
    )

    @Test
    fun `the first recording's coach line shows while it records, until 20 words or 90 s, and never for a later one`() {
        val first = ready.copy(isRecording = true, firstRecordingCoach = true, recordingDurationMillis = 10_000L)

        assertTrue(first.showsCoach)
        assertTrue(first.copy(completedSegments = listOf(words(12)), currentPartialSegment = words(7, complete = false, at = 2L)).showsCoach)
        // The 20th word heard, also in the line still being heard.
        assertFalse(first.copy(completedSegments = listOf(words(12)), currentPartialSegment = words(8, complete = false, at = 2L)).showsCoach)
        assertFalse(first.copy(completedSegments = listOf(words(MainUiState.COACH_WORDS))).showsCoach)
        assertTrue(first.copy(recordingDurationMillis = MainUiState.COACH_MS - 1_000L).showsCoach)
        assertFalse(first.copy(recordingDurationMillis = MainUiState.COACH_MS).showsCoach)
        // Not before the microphone is live, nor once Stop is pressed, nor for a recording after the first.
        assertFalse(first.copy(isRecording = false).showsCoach)
        assertFalse(first.copy(saving = true).showsCoach)
        assertFalse(first.copy(firstRecordingCoach = false).showsCoach)
        assertEquals(20, MainUiState.COACH_WORDS)
        assertEquals(90_000L, MainUiState.COACH_MS)
    }

    @Test
    fun `a first recording made as a translation is not coached`() {
        val translating = ready.copy(
            isRecording = true,
            firstRecordingCoach = true,
            sessionMode = RecordingMode.REAL_TIME_TRANSLATION
        )
        assertFalse(translating.showsCoach)
    }

    @Test
    fun `a missing AI model is on its way only while the first run downloads it and AI notes are on`() {
        val missing = ready.copy(llmStatus = LlmStatus.Missing)

        assertTrue(missing.copy(aiModelPending = true).aiModelComing)
        assertFalse(missing.aiModelComing)
        // AI notes off: their own sentence, not the download's.
        assertFalse(missing.copy(aiModelPending = true, settings = AppSettings(llmEnabled = false)).aiModelComing)
        // A model on the phone, or one that didn't load, is no model on its way.
        assertFalse(ready.copy(aiModelPending = true).aiModelComing)
        assertFalse(ready.copy(llmStatus = LlmStatus.Unavailable, aiModelPending = true).aiModelComing)
    }

    @Test
    fun `an error that can be tried again leaves the recorder ready`() {
        assertEquals(RecorderPhase.Ready, ready.copy(error = RecorderError.ServiceRefused).phase)
        assertEquals(RecorderPhase.Ready, ready.copy(error = RecorderError.TranscriptionFailed).phase)
    }

    @Test
    fun `only the errors that need a fix block recording`() {
        assertEquals(
            setOf(
                RecorderError.SpeechModelMissing,
                RecorderError.SpeechModelDamaged,
                RecorderError.SpeechModelFailed
            ),
            RecorderError.entries.filter { it.recordingBlocked }.toSet()
        )
    }

    @Test
    fun `a recorder on its way out is still saving until the screen is gone`() {
        assertFalse(ready.isLeaving)
        assertFalse(ready.isFinalizingSession)
        assertTrue(saving.isFinalizingSession)
        for (exit in RecorderExit.entries) {
            val leaving = ready.copy(exit = exit)
            assertTrue(exit.name, leaving.isLeaving)
            assertTrue(exit.name, leaving.isFinalizingSession)
            assertEquals(exit.name, RecorderPhase.Saving, leaving.phase)
        }
    }

    @Test
    fun `a saved recording sends the recorder home`() {
        val done = saving.afterHandOff(deleted = false, startInFlight = false)

        assertEquals(RecorderExit.HOME, done.exit)
        assertFalse(done.saving)
    }

    @Test
    fun `Back while saving sends the recorder back once the recording is saved`() {
        val done = saving.copy(leaveWhenSaved = true).afterHandOff(deleted = false, startInFlight = false)

        assertEquals(RecorderExit.BACK, done.exit)
        assertFalse(done.saving)
    }

    @Test
    fun `a recording that was deleted sends the recorder home with the reason, Back or not`() {
        for (back in listOf(false, true)) {
            val done = saving.copy(leaveWhenSaved = back).afterHandOff(deleted = true, startInFlight = false)
            assertEquals(RecorderExit.NOTHING_SAID, done.exit)
        }
    }

    @Test
    fun `a quick-key start that waits for the recording leaves the recorder where it is`() {
        for (deleted in listOf(false, true)) {
            for (back in listOf(false, true)) {
                val done = saving.copy(leaveWhenSaved = back).afterHandOff(deleted = deleted, startInFlight = true)
                assertEquals(RecorderExit.REPLACED, done.exit)
                assertFalse(done.saving)
            }
        }
    }

    @Test
    fun `a recorder left for a quick-key start that was given up goes home itself`() {
        val replaced = saving.afterHandOff(deleted = false, startInFlight = true)

        assertEquals(RecorderExit.HOME, replaced.afterStartGivenUp().exit)
        // Any other way out, or none yet, stays as it is.
        for (exit in RecorderExit.entries.filter { it != RecorderExit.REPLACED } + null) {
            val state = saving.copy(exit = exit)
            assertEquals(exit.toString(), state, state.afterStartGivenUp())
        }
    }

    @Test
    fun `an error from before stays when the recording is handed off`() {
        val done = saving.copy(error = RecorderError.TranscriptionFailed).afterHandOff(deleted = false, startInFlight = false)

        assertEquals(RecorderError.TranscriptionFailed, done.error)
        assertEquals(RecorderExit.HOME, done.exit)
    }

    @Test
    fun `AI notes switched on after the recorder found them off need their model prepared`() {
        val off = ready.copy(llmStatus = LlmStatus.Off, settings = AppSettings(llmEnabled = false))
        assertFalse(off.isAiPreparationDue)
        assertTrue(off.copy(settings = AppSettings(llmEnabled = true)).isAiPreparationDue)
    }

    @Test
    fun `a model that was looked at is not prepared again`() {
        for (status in LlmStatus.entries.filter { it != LlmStatus.Off }) {
            assertFalse(status.name, ready.copy(llmStatus = status).isAiPreparationDue)
        }
    }

    @Test
    fun `no model is prepared once Stop was pressed`() {
        val switchedOn = ready.copy(llmStatus = LlmStatus.Off)
        assertTrue(switchedOn.isAiPreparationDue)
        assertFalse(switchedOn.copy(saving = true).isAiPreparationDue)
        assertFalse(switchedOn.copy(exit = RecorderExit.HOME).isAiPreparationDue)
    }

    @Test
    fun `the AI has no problem while it works or loads`() {
        assertNull(ready.aiProblem)
        assertNull(ready.copy(llmStatus = LlmStatus.Loading).aiProblem)
    }

    @Test
    fun `a model that is missing or unusable is a problem of its own`() {
        assertEquals(LlmStatus.Missing, ready.copy(llmStatus = LlmStatus.Missing).aiProblem)
        assertEquals(LlmStatus.Unavailable, ready.copy(llmStatus = LlmStatus.Unavailable).aiProblem)
        assertEquals(LlmStatus.Off, ready.copy(llmStatus = LlmStatus.Off).aiProblem)
    }

    @Test
    fun `switching AI notes off in Settings is a problem whatever the model says`() {
        val off = ready.copy(settings = AppSettings(llmEnabled = false))
        assertEquals(LlmStatus.Off, off.aiProblem)
        assertEquals(LlmStatus.Off, off.copy(llmStatus = LlmStatus.Missing).aiProblem)
    }

    @Test
    fun `the transcript shows the line being heard after the completed ones`() {
        val done = TranscriptionSegment("a", "s", "Done.", 1L, isComplete = true)
        val heard = TranscriptionSegment("b", "s", "Still talking", 2L, isComplete = false)
        val state = ready.copy(completedSegments = listOf(done), currentPartialSegment = heard)
        assertEquals(listOf(done, heard), state.allSegments)
        assertEquals(listOf(done), ready.copy(completedSegments = listOf(done)).allSegments)
    }

    @Test
    fun `notes are in the order they were written`() {
        val first = LlmInsight("1", "s", "First", "…", timestamp = 10L, sourceSegmentIds = emptyList())
        val second = LlmInsight("2", "s", "Second", "…", timestamp = 20L, sourceSegmentIds = emptyList())
        assertEquals(listOf(first, second), ready.copy(insights = listOf(second, first)).sortedInsights)
    }
}
