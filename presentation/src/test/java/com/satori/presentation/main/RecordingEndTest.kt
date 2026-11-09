package com.satori.presentation.main

import com.satori.domain.repository.MicrophoneLostException
import com.satori.presentation.main.RecordingEnd.Facts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEndTest {

    private val notAsked: () -> Boolean = { error("the recognition is asked only for a capture that still runs") }

    private fun recording() = RecordingEnd().apply { opened(storedLengthMs = 0L, hasLines = false) }

    private fun RecordingEnd.factsAtClose(onScreen: String? = null, recognizerHeard: () -> Boolean = notAsked) =
        atClose(onScreen, recognizerHeard)

    @Test
    fun `a lost microphone left nothing untranscribed, and any other failure of the stream may have`() {
        val micLost = recording().apply {
            captureStarted()
            captureFailed(MicrophoneLostException("No audio for 5000 ms"), current = true, recognizerHeard = false)
        }
        val recognizerFailed = recording().apply {
            captureStarted()
            captureFailed(IllegalStateException("decoder failed"), current = true, recognizerHeard = false)
        }

        assertEquals(Facts(captured = true, heard = false, mayLackSpeech = false), micLost.factsAtClose())
        assertEquals(Facts(captured = true, heard = false, mayLackSpeech = true), recognizerFailed.factsAtClose())
        assertTrue(recognizerFailed.atStop().mayLackSpeech)
    }

    @Test
    fun `once Stop has handed the recording to finishing, the recorder closing leaves it be`() {
        val end = recording().apply {
            captureStarted()
            captureStopped(held = "", recognizerHeard = false)
        }
        // Cut short during the wait for the last words or a live note: the recorder decides.
        assertNotNull(end.factsAtClose())

        end.handedToFinishing()

        assertNull(end.factsAtClose())
    }

    @Test
    fun `a capture cut off as the recorder closes counts what its recognition still had open`() {
        val open = recording().apply { captureStarted() }
        val silent = recording().apply { captureStarted() }

        assertTrue(open.factsAtClose(recognizerHeard = { true })!!.heard)
        assertFalse(silent.factsAtClose(recognizerHeard = { false })!!.heard)
    }

    @Test
    fun `a capture that has ended is not asked about again, and what it heard stays heard`() {
        val stopped = recording().apply {
            captureStarted()
            captureStopped(held = "", recognizerHeard = true)
        }
        val failed = recording().apply {
            captureStarted()
            captureFailed(MicrophoneLostException("gone"), current = true, recognizerHeard = true)
        }

        assertTrue(stopped.factsAtClose()!!.heard)
        assertTrue(failed.factsAtClose()!!.heard)
    }

    @Test
    fun `the line on screen as the recorder closes counts as heard, a lone noise does not`() {
        assertTrue(recording().apply { captureStarted() }.factsAtClose(onScreen = "Call the bank", recognizerHeard = { false })!!.heard)
        assertFalse(recording().apply { captureStarted() }.factsAtClose(onScreen = " . ", recognizerHeard = { false })!!.heard)
    }

    @Test
    fun `at Stop the words held count, also behind a noise line`() {
        fun heldAtStop(held: String, recognizerHeard: Boolean = false) = recording().run {
            captureStarted()
            captureStopped(held, recognizerHeard)
            atStop().heard
        }

        assertTrue(heldAtStop(". Call the bank"))
        assertFalse(heldAtStop(". "))
        assertTrue(heldAtStop("", recognizerHeard = true))
    }

    @Test
    fun `a recording with a capture from before this recorder may lack speech`() {
        // Android ended the app while it recorded: the length saved, or lines, but no Stop.
        fun mayLack(lengthMs: Long, hasLines: Boolean) =
            RecordingEnd().apply { opened(lengthMs, hasLines) }.atStop().mayLackSpeech

        assertTrue(mayLack(12_000L, hasLines = false))
        assertTrue(mayLack(0L, hasLines = true))
        assertFalse(mayLack(0L, hasLines = false))
        // What the recorder's own capture saved is no capture from before.
        val own = RecordingEnd().apply {
            captureStarted()
            opened(storedLengthMs = 20_000L, hasLines = true)
        }
        assertFalse(own.atStop().mayLackSpeech)
    }

    @Test
    fun `a recorder that never recorded says so, and so the list doesn't explain the recording that went`() {
        val end = recording()

        assertEquals(Facts(captured = false, heard = false, mayLackSpeech = false), end.factsAtClose())
        assertFalse(end.hasCaptured)
        end.captureStarted()
        assertTrue(end.hasCaptured)
    }

    @Test
    fun `a recording the close deleted as silent is left be by the next close`() = runBlocking {
        // A capture that ended on an error, in which nothing was said: Back, then the recorder's onCleared.
        val end = recording().apply {
            captureStarted()
            captureFailed(MicrophoneLostException("No audio for 5000 ms"), current = true, recognizerHeard = false)
        }
        val steps = mutableListOf<String>()
        // The rule finds the recording gone at a second look, and keeps "it".
        var gone = false

        repeat(2) {
            end.close(
                onScreen = null,
                recognizerHeard = notAsked,
                deleteIfSilent = { steps += "rule"; (!gone).also { gone = true } },
                handOff = { steps += "handOff" }
            )
        }

        assertEquals(listOf("rule"), steps)
    }

    @Test
    fun `a kept recording is handed off as the recorder closes, again at the next close if that failed, and then left be`() = runBlocking {
        val end = recording().apply { captureStarted() }
        var handOffs = 0
        val failingOnce: suspend () -> Unit = {
            handOffs++
            // The first fails and reports nothing; the second queues it.
            if (handOffs > 1) end.handedToFinishing()
        }

        repeat(3) {
            end.close(onScreen = "Call the bank", recognizerHeard = { false }, deleteIfSilent = { false }, handOff = failingOnce)
        }

        assertEquals(2, handOffs)
    }

    @Test
    fun `a recorder that never recorded hands nothing off as it closes`() = runBlocking {
        var handOffs = 0

        val deleted = recording().close(onScreen = null, recognizerHeard = notAsked, deleteIfSilent = { false }, handOff = { handOffs++ })

        assertFalse(deleted)
        assertEquals(0, handOffs)
    }

    @Test
    fun `an earlier capture's late failure leaves the one that runs open`() {
        val end = recording().apply {
            captureStarted()
            captureFailed(IllegalStateException("old stream"), current = false, recognizerHeard = false)
        }

        val facts = end.factsAtClose(recognizerHeard = { true })!!

        assertTrue(facts.mayLackSpeech)
        assertTrue(facts.heard)
    }
}
