package com.satori.ui.debug

import com.satori.data.monitor.ProbeRead
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pace probe from adb: the model's files lose their cached pages and are read in, under the
 * AI's gate and service as the memtest runs, with its rules, and a recording that starts wins; its
 * line says how much it read, and whether it gave up waiting for memory.
 */
class DebugPrewarmTest {

    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val gate = AiWorkGate()
    private val finishingBusy = MutableStateFlow(false)
    private val service = HeldService()
    private val events = mutableListOf<String>()
    private var now = 0L

    private val parakeet = listOf(File("/stt/encoder.int8.onnx"), File("/stt/decoder.int8.onnx"), File("/stt/tokens.txt"))

    private fun prewarm() = DebugPrewarm(recorder, gate, finishingBusy, service, clock = { now }, serviceWaitMs = 50)

    @Test
    fun `the files lose their cached pages, then are read in under the AI's gate and service, and the run is reported`() = runBlocking {
        val report = prewarm().run(
            DebugPrewarm.Model.PARAKEET,
            files = { parakeet },
            drop = { file ->
                events += "drop ${file.name}"
                file.name != "tokens.txt"
            },
            read = { files ->
                events += "read ${files.size} files under ${gate.holder.value} with ${service.holders}"
                now += 5_400
                ProbeRead(newBytes = 640L * MIB, totalBytes = 651L * MIB, gaveUp = false)
            }
        )

        assertEquals(
            DebugPrewarm.Outcome.Done(files = 3, dropped = 2, ms = 5_400, read = ProbeRead(640L * MIB, 651L * MIB, gaveUp = false)),
            report.outcome
        )
        assertEquals(
            listOf("drop encoder.int8.onnx", "drop decoder.int8.onnx", "drop tokens.txt", "read 3 files under prewarm with [prewarm]"),
            events
        )
        assertEquals(
            "Prewarm parakeet done in 5400 ms: 640 MB of 651 MB read; cached pages dropped first for 2 of 3 files; AI service in the foreground",
            report.line(DebugPrewarm.Model.PARAKEET)
        )
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a read that waited 10 s in all for memory says it gave up, and how far it came`() = runBlocking {
        val report = prewarm().run(
            DebugPrewarm.Model.E2B,
            files = { listOf(File("/models/gemma-4-E2B_q4_0-it.gguf")) },
            drop = { true },
            read = {
                now += 14_210
                ProbeRead(newBytes = 501L * MIB, totalBytes = 3_194L * MIB, gaveUp = true)
            }
        )

        assertEquals(
            "Prewarm e2b gave up after 14210 ms: MemFree under 150 MB for 10 s; 501 MB of 3194 MB read; " +
                "cached pages dropped first for 1 of 1 files; AI service in the foreground",
            report.line(DebugPrewarm.Model.E2B)
        )
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())

        // The floor a wait that stood still lowered is the one the line names.
        val lowered = prewarm().run(
            DebugPrewarm.Model.E2B,
            files = { listOf(File("/models/gemma-4-E2B_q4_0-it.gguf")) },
            drop = { true },
            read = { ProbeRead(newBytes = 832L * MIB, totalBytes = 3_194L * MIB, gaveUp = true, floorMb = 64) }
        )
        assertTrue(lowered.line(DebugPrewarm.Model.E2B), lowered.line(DebugPrewarm.Model.E2B).contains(": MemFree under 64 MB for 10 s; 832 MB of 3194 MB read;"))
    }

    @Test
    fun `it doesn't start beside a recorder, while a recording waits to be finished, or while the AI is held`() = runBlocking {
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.RECORDING)
        val besideRecorder = prewarm().run(DebugPrewarm.Model.E2B, files = { error("looked") }, drop = { error("dropped") }, read = { error("read") })
        recorder.value = null
        finishingBusy.value = true
        val whileFinishing = prewarm().run(DebugPrewarm.Model.LIVE, files = { error("looked") }, drop = { error("dropped") }, read = { error("read") })

        assertEquals(DebugPrewarm.Outcome.Refused("a recorder is busy"), besideRecorder.outcome)
        assertEquals("Prewarm 1b refused: the AI is busy", whileFinishing.line(DebugPrewarm.Model.LIVE))
        assertTrue(service.log.isEmpty())
    }

    @Test
    fun `a model that isn't on the phone is said so, and nothing is read`() = runBlocking {
        val report = prewarm().run(DebugPrewarm.Model.COHERE, files = { null }, drop = { error("dropped") }, read = { error("read") })

        assertEquals(DebugPrewarm.Outcome.NoFiles, report.outcome)
        assertEquals("Prewarm cohere not run: Cohere Transcribe is not on the phone; AI service in the foreground", report.line(DebugPrewarm.Model.COHERE))
        assertNull(gate.holder.value)
    }

    @Test
    fun `a recording that starts during the read wins, the read is cancelled and the gate and service go`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val run = async {
            prewarm().run(
                DebugPrewarm.Model.E2B,
                files = { listOf(File("/models/gemma-4-E2B_q4_0-it.gguf")) },
                drop = { true },
                read = {
                    reading.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            )
        }
        reading.await()
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.STARTING)

        val report = run.await()

        assertEquals(DebugPrewarm.Outcome.RecorderStarted, report.outcome)
        assertTrue(cancelled.isCompleted)
        assertEquals("Prewarm e2b cancelled: a recording started; AI service in the foreground", report.line(DebugPrewarm.Model.E2B))
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a read that fails is reported with its kind, and the gate and service still go`() = runBlocking {
        val report = prewarm().run(
            DebugPrewarm.Model.LIVE,
            files = { listOf(File("/models/gemma-3-1b-it-Q8_0.gguf")) },
            drop = { true },
            read = { throw java.io.IOException("gone") }
        )

        assertEquals("Prewarm 1b failed: IOException; AI service in the foreground", report.line(DebugPrewarm.Model.LIVE))
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a probe says it has started once past its rules and holding the service, a refused one never`() = runBlocking {
        val started = mutableListOf<String>()
        prewarm().run(DebugPrewarm.Model.PARAKEET, files = { parakeet }, drop = { true }, read = { ProbeRead(1L, 1L, gaveUp = false) }) {
            started += "started holding ${service.holders} under ${gate.holder.value}"
        }
        recorder.value = ActiveRecorder.Snapshot("r", ActiveRecorder.Activity.RECORDING)
        prewarm().run(DebugPrewarm.Model.PARAKEET, files = { parakeet }, drop = { true }, read = { error("read") }) { started += "refused one started" }

        assertEquals(listOf("started holding [prewarm] under prewarm"), started)
    }

    @Test
    fun `the names memtest_sh gives are the models it knows`() {
        assertEquals(DebugPrewarm.Model.E2B, DebugPrewarm.Model.of("e2b"))
        assertEquals(DebugPrewarm.Model.LIVE, DebugPrewarm.Model.of("1b"))
        assertEquals(DebugPrewarm.Model.PARAKEET, DebugPrewarm.Model.of("parakeet"))
        assertEquals(DebugPrewarm.Model.COHERE, DebugPrewarm.Model.of("cohere"))
        assertEquals(DebugPrewarm.Model.MALAYALAM, DebugPrewarm.Model.of("ml"))
        assertNull(DebugPrewarm.Model.of("whisper"))
    }

    private companion object {
        const val MIB = 1_048_576L
    }

    private class HeldService : LlmProcessingServiceController {
        val holders = mutableSetOf<String>()
        val log = mutableListOf<String>()
        private val inForeground = MutableStateFlow(false)
        override val protected: StateFlow<Boolean> = inForeground

        override fun startProcessing(holder: String): Boolean {
            log += "start $holder"
            holders += holder
            inForeground.value = true
            return true
        }

        override fun stopProcessing(holder: String) {
            log += "stop $holder"
            holders -= holder
            if (holders.isEmpty()) inForeground.value = false
        }
    }
}
