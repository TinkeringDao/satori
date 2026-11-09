package com.satori.presentation.main

import com.satori.presentation.main.ActiveRecorder.Activity
import com.satori.presentation.main.ActiveRecorder.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRecorderTest {

    private val ready = MainUiState(isInitializing = false, llmStatus = LlmStatus.Ready)

    @Test
    fun `what a recorder does follows its state, saving first`() {
        assertEquals(Activity.IDLE, ActiveRecorder.activityOf(ready))
        assertEquals(Activity.IDLE, ActiveRecorder.activityOf(MainUiState()))
        assertEquals(Activity.STARTING, ActiveRecorder.activityOf(MainUiState(isStartingRecording = true)))
        assertEquals(Activity.RECORDING, ActiveRecorder.activityOf(ready.copy(isRecording = true, isStartingRecording = true)))
        assertEquals(Activity.SAVING, ActiveRecorder.activityOf(ready.copy(isRecording = true, saving = true)))
    }

    @Test
    fun `a recorder that is done saving is idle while the screen still shows it`() {
        val saving = ready.copy(saving = true)
        assertEquals(Activity.SAVING, ActiveRecorder.activityOf(saving))
        for (deleted in listOf(false, true)) {
            for (startInFlight in listOf(false, true)) {
                val done = saving.afterHandOff(deleted = deleted, startInFlight = startInFlight)
                assertEquals(Activity.IDLE, ActiveRecorder.activityOf(done))
                // The screen itself still offers nothing until it has been replaced.
                assertTrue(done.isFinalizingSession)
            }
        }
    }

    @Test
    fun `a recorder says whether it records for Translate`() {
        val recorders = ActiveRecorder()
        val recorder = Any()
        recorders.open(recorder, "a", Activity.IDLE)
        assertFalse(recorders.current.value!!.translation)

        recorders.report(recorder, "a", Activity.RECORDING, translation = true)

        assertEquals(Snapshot("a", Activity.RECORDING, translation = true), recorders.current.value)
    }

    @Test
    fun `the newest recorder wins and an older one that is still closing changes nothing`() {
        val recorders = ActiveRecorder()
        val old = Any()
        val new = Any()
        recorders.open(old, "a", Activity.IDLE)
        recorders.open(new, "b", Activity.STARTING)
        recorders.report(old, "a", Activity.STARTING)
        assertEquals(Snapshot("b", Activity.STARTING), recorders.current.value)
        recorders.leave(old)
        assertEquals(Snapshot("b", Activity.STARTING), recorders.current.value)
        recorders.report(new, "b", Activity.RECORDING)
        assertEquals(Snapshot("b", Activity.RECORDING), recorders.current.value)
        recorders.leave(new)
        assertNull(recorders.current.value)
    }

    @Test
    fun `a closing recorder releases the models only while no newer one is open`() {
        val recorders = ActiveRecorder()
        val old = Any()
        val new = Any()
        recorders.open(old, "a", Activity.IDLE)
        assertTrue(recorders.mayRelease(old))
        recorders.open(new, "b", Activity.STARTING)
        assertFalse(recorders.mayRelease(old))
        assertTrue(recorders.mayRelease(new))
        recorders.leave(new)
        // Nothing else holds the models now.
        assertTrue(recorders.mayRelease(old))
    }

    @Test
    fun `a recorder a press navigated to counts as starting before it opens`() {
        val recorders = ActiveRecorder()
        val replaced = Any()
        recorders.open(replaced, "a", Activity.IDLE)
        recorders.expect("b")
        assertEquals(Snapshot("b", Activity.STARTING), recorders.current.value)
        // The idle recorder it replaces closes once the screen is on: it no longer reports, and it
        // leaves the models to the new one.
        recorders.report(replaced, "a", Activity.IDLE)
        assertFalse(recorders.mayRelease(replaced))
        recorders.leave(replaced)
        assertEquals(Snapshot("b", Activity.STARTING), recorders.current.value)

        val opened = Any()
        recorders.open(opened, "b", Activity.STARTING)
        recorders.report(opened, "b", Activity.RECORDING)
        assertEquals(Snapshot("b", Activity.RECORDING), recorders.current.value)
        assertTrue(recorders.mayRelease(opened))
    }

    @Test
    fun `a cancelled start that never opened leaves its recording to the caller`() {
        val recorders = ActiveRecorder()
        recorders.expect("b")
        assertFalse(recorders.cancelStart("other"))
        assertEquals(Snapshot("b", Activity.STARTING), recorders.current.value)
        assertTrue(recorders.cancelStart("b"))
        assertNull(recorders.current.value)
    }

    @Test
    fun `a cancelled start whose recorder opened no longer counts while it closes`() {
        val recorders = ActiveRecorder()
        val recorder = Any()
        recorders.expect("b")
        recorders.open(recorder, "b", Activity.STARTING)
        // Its own onCleared deletes an empty recording.
        assertFalse(recorders.cancelStart("b"))
        assertNull(recorders.current.value)
        recorders.report(recorder, "b", Activity.STARTING)
        assertNull(recorders.current.value)
        assertTrue(recorders.mayRelease(recorder))

        // A start that comes before it has closed keeps the models for the next recorder.
        recorders.expect("c")
        assertFalse(recorders.mayRelease(recorder))
        recorders.leave(recorder)
        assertEquals(Snapshot("c", Activity.STARTING), recorders.current.value)
    }

    @Test
    fun `a press waits until the recorder has saved its recording`() = runTest {
        val recorders = ActiveRecorder()
        val recorder = Any()
        recorders.open(recorder, "a", Activity.SAVING)
        val waited = async { recorders.awaitNotSaving(16_000) }
        runCurrent()
        assertFalse(waited.isCompleted)

        recorders.report(recorder, "a", Activity.IDLE)

        assertEquals(Snapshot("a", Activity.IDLE), waited.await())
    }

    @Test
    fun `a recorder that closes while a press waits leaves none`() = runTest {
        val recorders = ActiveRecorder()
        val recorder = Any()
        recorders.open(recorder, "a", Activity.SAVING)
        val waited = async { recorders.awaitNotSaving(16_000) }
        runCurrent()

        recorders.leave(recorder)
        runCurrent()

        // At once, not once the time it was given is up.
        assertTrue(waited.isCompleted)
        assertNull(waited.await())
        assertEquals(0, currentTime)
    }

    @Test
    fun `a press stops waiting after the time it was given, the recorder still saving`() = runTest {
        val recorders = ActiveRecorder()
        recorders.open(Any(), "a", Activity.SAVING)
        val waited = async { recorders.awaitNotSaving(16_000) }

        advanceTimeBy(15_999)
        runCurrent()
        assertFalse(waited.isCompleted)
        advanceTimeBy(2)

        assertEquals(Snapshot("a", Activity.SAVING), waited.await())
    }

    @Test
    fun `a press does not wait for a recorder that is not saving`() = runTest {
        val recorders = ActiveRecorder()
        assertNull(recorders.awaitNotSaving(16_000))

        recorders.open(Any(), "a", Activity.RECORDING)

        assertEquals(Snapshot("a", Activity.RECORDING), recorders.awaitNotSaving(16_000))
    }

    @Test
    fun `a start under way is told from its setter`() {
        val recorders = ActiveRecorder()
        assertFalse(recorders.startInFlight.value)

        recorders.setStartInFlight(true)
        assertTrue(recorders.startInFlight.value)

        recorders.setStartInFlight(false)
        assertFalse(recorders.startInFlight.value)
    }

    @Test
    fun `a start replaces the idle recorder it found only while that one is still idle`() {
        val found = Snapshot("a", Activity.IDLE)

        assertTrue(ActiveRecorder.mayReplace(found, found))
        // It closed meanwhile, or none was open and none opened.
        assertTrue(ActiveRecorder.mayReplace(found, null))
        assertTrue(ActiveRecorder.mayReplace(null, null))
        // Its record button was tapped meanwhile.
        for (activity in listOf(Activity.STARTING, Activity.RECORDING, Activity.SAVING)) {
            assertFalse(activity.name, ActiveRecorder.mayReplace(found, found.copy(activity = activity)))
        }
        // Another recorder opened meanwhile (home's record button).
        assertFalse(ActiveRecorder.mayReplace(found, Snapshot("b", Activity.IDLE)))
        assertFalse(ActiveRecorder.mayReplace(null, Snapshot("b", Activity.STARTING)))
    }
}
