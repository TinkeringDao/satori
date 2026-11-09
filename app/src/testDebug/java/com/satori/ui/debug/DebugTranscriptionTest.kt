package com.satori.ui.debug

import com.satori.domain.repository.AudioUsage
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Transcribe again from adb keeps the rules of the recording's page, holds the AI's service as the
 * page does, and yields to a recording.
 */
class DebugTranscriptionTest {

    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val gate = AiWorkGate()
    private val finishingBusy = MutableStateFlow(false)
    private val audio = CountedUsage()
    private val service = HeldService()

    private fun transcription() = DebugTranscription(recorder, gate, finishingBusy, audio, service)

    @Test
    fun `it runs holding the AI with the audio in use, and lets go of both after`() = runBlocking {
        var heldThen: String? = null
        var inUseThen = emptySet<String>()

        val run = transcription().run("s1") {
            heldThen = gate.holder.value
            inUseThen = audio.inUse()
            "replaced"
        }

        assertEquals(DebugTranscription.Run.Done("replaced"), run)
        assertEquals(AiWorkGate.TRANSCRIBE, heldThen)
        assertEquals(setOf("s1"), inUseThen)
        assertNull(gate.holder.value)
        assertTrue(audio.inUse().isEmpty())
    }

    @Test
    fun `it holds the AI's service under the page's key while it runs, and gives it back after`() = runBlocking {
        var heldThen = emptySet<String>()

        transcription().run("s1") { heldThen = service.holders.toSet() }

        assertEquals(setOf("transcribe:s1"), heldThen)
        assertTrue(service.holders.isEmpty())
        assertEquals(listOf("start transcribe:s1", "stop transcribe:s1"), service.log)
    }

    @Test
    fun `a service Android refuses still runs the transcription, and is given back after`() = runBlocking {
        service.refused = true

        val run = transcription().run("s1") { "replaced" }

        assertEquals(DebugTranscription.Run.Done("replaced"), run)
        assertEquals(listOf("start transcribe:s1", "stop transcribe:s1"), service.log)
    }

    @Test
    fun `it doesn't start beside a recorder, while the AI is held, or while a recording waits to be finished`() = runBlocking {
        var ran = false
        val work: suspend () -> Unit = { ran = true }

        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)
        val besideRecorder = transcription().run("s1", work)
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.IDLE)
        finishingBusy.value = true
        val whileFinishing = transcription().run("s1", work)
        finishingBusy.value = false
        gate.tryEnter(AiWorkGate.MAP)
        val whileHeld = transcription().run("s1", work)

        assertEquals(DebugTranscription.Run.Refused("a recorder is busy"), besideRecorder)
        assertEquals(DebugTranscription.Run.Refused("the AI is busy"), whileFinishing)
        assertEquals(DebugTranscription.Run.Refused("the AI is busy"), whileHeld)
        assertFalse(ran)
        assertEquals(AiWorkGate.MAP, gate.holder.value)
        assertTrue(audio.inUse().isEmpty())
        assertTrue(service.log.isEmpty())
    }

    @Test
    fun `a recording that starts meanwhile cancels it, and the AI is free once it has ended`() = runBlocking {
        val began = CompletableDeferred<Unit>()
        var cancelled = false
        val run = async {
            transcription().run("s1") {
                began.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        began.await()

        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)

        assertEquals(DebugTranscription.Run.RecorderStarted, run.await())
        assertTrue(cancelled)
        assertNull(gate.holder.value)
        assertTrue(audio.inUse().isEmpty())
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a transcription that fails passes its failure on, and gives back the AI, the audio and the service`() = runBlocking {
        val thrown = runCatching { transcription().run("s1") { throw IOException("The speech model failed") } }.exceptionOrNull()

        // The same failure (coroutines may hand on a copy with a longer stack trace).
        assertTrue("$thrown", thrown is IOException)
        assertEquals("The speech model failed", thrown?.message)
        assertNull(gate.holder.value)
        assertTrue(audio.inUse().isEmpty())
        assertTrue(service.holders.isEmpty())
        assertEquals(listOf("start transcribe:s1", "stop transcribe:s1"), service.log)
    }

    @Test
    fun `a run cancelled from outside, as when the debug screen goes, gives back the AI, the audio and the service`() = runBlocking {
        val began = CompletableDeferred<Unit>()
        var cancelled = false
        val caller = launch {
            transcription().run("s1") {
                began.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        began.await()

        caller.cancelAndJoin()

        assertTrue(cancelled)
        assertNull(gate.holder.value)
        assertTrue(audio.inUse().isEmpty())
        assertTrue(service.holders.isEmpty())
        assertEquals(listOf("start transcribe:s1", "stop transcribe:s1"), service.log)
    }

    /** The AI's processing service: its holders, and what it was asked, in order. A start Android [refused] holds nothing. */
    private class HeldService : LlmProcessingServiceController {
        val holders = mutableSetOf<String>()
        val log = mutableListOf<String>()
        var refused = false
        override val protected: StateFlow<Boolean> = MutableStateFlow(false)

        override fun startProcessing(holder: String): Boolean {
            log += "start $holder"
            if (!refused) holders += holder
            return !refused
        }

        override fun stopProcessing(holder: String) {
            log += "stop $holder"
            holders -= holder
        }
    }

    /** The audio in use, counted as the app's AudioInUse counts it. */
    private class CountedUsage : AudioUsage {
        private val counts = mutableMapOf<String, Int>()

        override fun begin(sessionId: String) {
            counts[sessionId] = (counts[sessionId] ?: 0) + 1
        }

        override fun end(sessionId: String) {
            val left = (counts[sessionId] ?: return) - 1
            if (left > 0) counts[sessionId] = left else counts.remove(sessionId)
        }

        override fun inUse(): Set<String> = counts.keys.toSet()
    }
}
