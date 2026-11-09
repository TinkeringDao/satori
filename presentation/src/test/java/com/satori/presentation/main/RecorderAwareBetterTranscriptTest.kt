package com.satori.presentation.main

import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.finish.BetterTranscript
import com.satori.domain.usecase.finish.BetterTranscriptRun
import com.satori.domain.usecase.finish.BetterTranscriptStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The better transcript after Stop never runs beside a live capture of another recording. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderAwareBetterTranscriptTest {

    private val model = SpeechModelId.COHERE_TRANSCRIBE
    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val step = HeldStep(model)
    private val runs = mutableListOf<BetterTranscriptRun>()
    private val aware = RecorderAwareBetterTranscript(step, recorder)

    /**
     * A better transcript whose model runs until [done] is completed, with the new transcript, or
     * until it is skipped, which leaves the live one. As TranscribeAfterStopUseCase does, it says
     * [BetterTranscript.Replaced] at once for a transcript the model [made] already, and runs no
     * model when the models may not change. [asked] holds whether they may, for each call.
     */
    private class HeldStep(private val model: SpeechModelId) : BetterTranscriptStep {
        val asked = mutableListOf<Boolean>()
        var made = false
        var skipped = false
        val done = CompletableDeferred<BetterTranscript>()

        override suspend fun run(
            sessionId: String,
            mayChangeModel: Boolean,
            onStart: (BetterTranscriptRun) -> Unit,
            onProgress: (Float) -> Unit
        ): BetterTranscript {
            asked += mayChangeModel
            if (made) return BetterTranscript.Replaced(model)
            if (!mayChangeModel) return BetterTranscript.Skipped(model, BetterTranscript.Reason.NewerRecorder)
            onStart(
                BetterTranscriptRun(model) {
                    skipped = true
                    done.complete(BetterTranscript.Skipped(model, BetterTranscript.Reason.SkippedByOwner))
                }
            )
            return done.await()
        }
    }

    private suspend fun run(): BetterTranscript = aware.run("s1", mayChangeModel = true, onStart = { runs += it }, onProgress = {})

    @Test
    fun `a capture of another recording that is live as the step would start keeps the model from running`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)

        assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.NewerRecorder), run())

        assertEquals(listOf(false), step.asked)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a transcript the set model made already counts beside a capture, so its notes are rewritten from it`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        step.made = true

        assertEquals(BetterTranscript.Replaced(model), run())

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a recorder that is only starting counts as a capture, an idle one doesn't`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.STARTING)
        assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.NewerRecorder), run())

        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.IDLE)
        step.done.complete(BetterTranscript.Replaced(model))
        assertEquals(BetterTranscript.Replaced(model), run())
        assertEquals(listOf(false, true), step.asked)
    }

    @Test
    fun `without a live capture the step runs, and what it did comes back`() = runTest {
        val result = async { run() }
        runCurrent()

        step.done.complete(BetterTranscript.Replaced(model))

        assertEquals(BetterTranscript.Replaced(model), result.await())
        assertEquals(listOf(model), runs.map { it.model })
    }

    @Test
    fun `a capture that starts while the model runs stops it, and the result says why`() = runTest {
        val result = async { run() }
        runCurrent()

        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        runCurrent()

        assertTrue(step.skipped)
        assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.NewerRecorder), result.await())
    }

    @Test
    fun `a new transcript complete before the capture starts is kept`() = runTest {
        val result = async { run() }
        runCurrent()

        step.done.complete(BetterTranscript.Replaced(model))
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)

        assertEquals(BetterTranscript.Replaced(model), result.await())
    }

    @Test
    fun `the recording's own recorder doesn't count`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("s1", ActiveRecorder.Activity.RECORDING)
        val result = async { run() }
        runCurrent()

        step.done.complete(BetterTranscript.Replaced(model))

        assertEquals(BetterTranscript.Replaced(model), result.await())
        assertFalse(step.skipped)
    }

    @Test
    fun `the owner's own skip still reads as the owner's`() = runTest {
        val result = async { run() }
        runCurrent()

        runs.single().skip()

        assertEquals(BetterTranscript.Skipped(model, BetterTranscript.Reason.SkippedByOwner), result.await())
    }

    /** A step that notes what it is told of the live transcript, and makes none. */
    private class Told : BetterTranscriptStep {
        val told = mutableListOf<Pair<Boolean, Boolean>>()

        override suspend fun run(
            sessionId: String,
            mayChangeModel: Boolean,
            onStart: (BetterTranscriptRun) -> Unit,
            onProgress: (Float) -> Unit
        ): BetterTranscript = run(sessionId, mayChangeModel, onStart, onProgress, mayLackSpeech = false)

        override suspend fun run(
            sessionId: String,
            mayChangeModel: Boolean,
            onStart: (BetterTranscriptRun) -> Unit,
            onProgress: (Float) -> Unit,
            mayLackSpeech: Boolean
        ): BetterTranscript {
            told += mayChangeModel to mayLackSpeech
            return BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
        }
    }

    @Test
    fun `the step is told when the live transcript may lack speech, with and without a capture beside it`() = runTest {
        val told = Told()
        val aware = RecorderAwareBetterTranscript(told, recorder)

        aware.run("s1", mayChangeModel = true, onStart = {}, onProgress = {}, mayLackSpeech = true)
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        aware.run("s1", mayChangeModel = true, onStart = {}, onProgress = {}, mayLackSpeech = true)
        aware.run("s1", mayChangeModel = true, onStart = {}, onProgress = {})

        assertEquals(listOf(true to true, false to true, false to false), told.told)
    }

    @Test
    fun `beside a capture the step still says why it makes none, as with no model set`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        val off = BetterTranscriptStep { _, _, _, _ -> BetterTranscript.Skipped(null, BetterTranscript.Reason.Off) }

        val result = RecorderAwareBetterTranscript(off, recorder).run("s1", true, {}, {})

        assertEquals(BetterTranscript.Skipped(null, BetterTranscript.Reason.Off), result)
    }

    @Test
    fun `the Malayalam model's transcript of a recording in Malayalam never runs beside another recording's capture`() = runTest {
        val malayalam = SpeechModelId.INDICCONFORMER_ML
        val redo = HeldStep(malayalam)
        val runsOfRedo = mutableListOf<BetterTranscriptRun>()
        val awareRedo = RecorderAwareBetterTranscript(redo, recorder)
        suspend fun runRedo() = awareRedo.run("s1", mayChangeModel = true, onStart = { runsOfRedo += it }, onProgress = {})

        // A capture live as it would start keeps the model out.
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        assertEquals(BetterTranscript.Skipped(malayalam, BetterTranscript.Reason.NewerRecorder), runRedo())
        assertTrue(runsOfRedo.isEmpty())

        // One that starts while it runs stops it.
        recorder.value = null
        val result = async { runRedo() }
        runCurrent()
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.STARTING)
        runCurrent()

        assertTrue(redo.skipped)
        assertEquals(BetterTranscript.Skipped(malayalam, BetterTranscript.Reason.NewerRecorder), result.await())
        assertEquals(listOf(false, true), redo.asked)
    }

    @Test
    fun `a cancel of the finishing is passed on`() = runTest {
        val result = async { run() }
        runCurrent()

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        assertFalse(step.skipped)
    }
}
