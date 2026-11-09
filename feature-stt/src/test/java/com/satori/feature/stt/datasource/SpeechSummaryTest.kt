package com.satori.feature.stt.datasource

import com.satori.domain.model.AppSettings
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.VadParameters
import com.satori.domain.model.VadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class SpeechSummaryTest {

    private companion object {
        const val SECOND = 16_000 // samples
        const val MS = 1_000_000L // nanoseconds
    }

    @Test
    fun `a decode's real-time factor is its time over the length of its audio`() {
        val speed = DecodeSpeed()

        speed.record(nanos = 150 * MS, samples = 2 * SECOND)

        assertEquals(0.075, speed.median()!!, 1e-9)
        assertEquals(1, speed.count)
    }

    @Test
    fun `the median is the middle factor, or halfway between the two middle ones`() {
        val odd = DecodeSpeed()
        // 0.1, 0.3 and 0.05 over 1 s utterances
        listOf(100L, 300L, 50L).forEach { odd.record(it * MS, SECOND) }
        assertEquals(0.1, odd.median()!!, 1e-9)

        val even = DecodeSpeed()
        listOf(100L, 300L, 50L, 200L).forEach { even.record(it * MS, SECOND) }
        assertEquals(0.15, even.median()!!, 1e-9)
    }

    @Test
    fun `no decodes, no median, and a decode of no audio isn't counted`() {
        val speed = DecodeSpeed()
        speed.record(nanos = 5 * MS, samples = 0)

        assertNull(speed.median())
        assertEquals(0, speed.count)
    }

    @Test
    fun `a timed decode gives its own result back`() {
        val speed = DecodeSpeed()

        val text = speed.timed(SECOND) { "the text" }

        assertEquals("the text", text)
        assertEquals(1, speed.count)
    }

    @Test
    fun `the line names model, voice detection and input, how loud the speech was and how fast it was decoded`() {
        val speed = DecodeSpeed()
        listOf(81L, 75L, 90L).forEach { speed.record(it * MS, SECOND) }
        val level = SpeechLevel()
        (-50..-31).forEach { level.record(it.toDouble()) }

        val line = speechSummary(
            SpeechModelId.LIVE.id, VadParameters.of(VadProfile.LIVE, AppSettings()), "VOICE_RECOGNITION",
            InputReport(effects = emptyList(), microphones = 1), CaptureSetup("builtin_mic/bottom", "closed", "off"), level,
            speed, "1.13.8", RunEnd.DONE
        )

        assertEquals(
            "Speech summary: model=parakeet-tdt-0.6b-v3-int8 vad=LIVE threshold=0.40 minSilence=0.50s " +
                "maxSpeech=20.0s mic=VOICE_RECOGNITION effects=none mics=1 device=builtin_mic/bottom fold=closed gain=off " +
                "speechLevel=-41.0dBFS p90=-33.0dBFS finals=3 lost=0 medianRtf=0.081 cpus=none sherpa-onnx=1.13.8 ended=done",
            line
        )
    }

    /** The part of a capture's summary line that says what the phone did with its input. */
    private fun inputPart(input: String, report: InputReport) = speechSummary(
        SpeechModelId.LIVE.id, VadParameters.of(VadProfile.LIVE, AppSettings()), input, report, CaptureSetup.NONE, SpeechLevel(),
        DecodeSpeed(), "1.13.8", RunEnd.DONE
    ).substringAfter("maxSpeech=20.0s ").substringBefore(" device=")

    @Test
    fun `the input for calls names its effects in order, and how many microphones recorded`() {
        val calls = InputReport(effects = listOf("NS", "AGC", "AEC"), microphones = 2)

        assertEquals("mic=VOICE_COMMUNICATION effects=AEC+AGC+NS mics=2", inputPart("VOICE_COMMUNICATION", calls))
    }

    @Test
    fun `an input without effects says none, and an effect Android doesn't define is named by its type`() {
        assertEquals(
            "mic=VOICE_RECOGNITION effects=none mics=1",
            inputPart("VOICE_RECOGNITION", InputReport(effects = emptyList(), microphones = 1))
        )
        val vendor = "ae3c653b-be18-4ab8-8938-418f0a7f06ac"
        assertEquals(
            "mic=MIC(fallback) effects=NS+$vendor mics=1",
            inputPart("MIC(fallback)", InputReport(effects = listOf(vendor, "NS"), microphones = 1))
        )
    }

    @Test
    fun `what the phone didn't say is a question mark`() {
        assertEquals("mic=VOICE_RECOGNITION effects=? mics=?", inputPart("VOICE_RECOGNITION", InputReport.UNKNOWN))
        assertEquals(
            "mic=VOICE_RECOGNITION effects=? mics=2",
            inputPart("VOICE_RECOGNITION", InputReport(effects = null, microphones = 2))
        )
    }

    @Test
    fun `a run without a microphone has no effects and no microphones`() {
        assertEquals("mic=feed effects=none mics=0", inputPart(SummaryInput.FEED, InputReport.NONE))
        assertEquals("mic=none effects=none mics=0", inputPart(SummaryInput.NONE, InputReport.NONE))
    }

    @Test
    fun `a later report fills in what an earlier one lacked and keeps what it knew`() {
        val effectsFirst = InputReport.UNKNOWN.updatedBy(InputReport(effects = listOf("NS"), microphones = null))
        assertFalse(effectsFirst.isComplete)

        val both = effectsFirst.updatedBy(InputReport(effects = null, microphones = 1))

        assertEquals(InputReport(effects = listOf("NS"), microphones = 1), both)
        assertTrue(both.isComplete)
        assertTrue(InputReport.NONE.isComplete)
    }

    @Test
    fun `a run without speech says so`() {
        val line = speechSummary(
            SpeechModelId.LIVE.id, VadParameters.FILE, SummaryInput.FILE, InputReport.NONE, CaptureSetup.NONE, SpeechLevel(),
            DecodeSpeed(), "1.13.8", RunEnd.DONE
        )

        assertEquals(
            "Speech summary: model=parakeet-tdt-0.6b-v3-int8 vad=FILE threshold=0.30 minSilence=0.70s " +
                "maxSpeech=20.0s mic=file effects=none mics=0 device=none fold=none gain=off speechLevel=none p90=none " +
                "finals=0 lost=0 medianRtf=none cpus=none sherpa-onnx=1.13.8 ended=done",
            line
        )
    }

    @Test
    fun `a run cut short says how it ended`() {
        fun ended(end: RunEnd) = speechSummary(
            SpeechModelId.LIVE.id, VadParameters.FILE, SummaryInput.NONE, InputReport.NONE, CaptureSetup.NONE, SpeechLevel(),
            DecodeSpeed(), "1.13.8", end
        ).substringAfter("sherpa-onnx=1.13.8 ")

        assertEquals("ended=cancelled", ended(RunEnd.CANCELLED))
        assertEquals("ended=failed", ended(RunEnd.FAILED))
    }

    @Test
    fun `utterances that found the speech model released are counted as lost, apart from the decoded ones`() {
        val speed = DecodeSpeed()
        speed.record(80 * MS, SECOND)
        repeat(2) { speed.recordLost() }

        assertEquals(1, speed.count)
        assertEquals(2, speed.lost)
        assertEquals(0.08, speed.median()!!, 1e-9)
        val line = speechSummary(
            SpeechModelId.LIVE.id, VadParameters.FILE, SummaryInput.NONE, InputReport.NONE, CaptureSetup.NONE, SpeechLevel(), speed,
            "1.13.8", RunEnd.DONE
        )
        assertEquals("finals=1 lost=2 medianRtf=0.080 cpus=none", line.substringAfter("p90=none ").substringBefore(" sherpa-onnx"))
    }

    @Test
    fun `the line names the CPUs the timed decodes ran on, each set once, in the order it first came`() {
        // Started in front (top-app), then the screen went off under a service (foreground).
        val seen = ArrayDeque(listOf("0-7", "0-7", "0-6", null, "0-7", "0-6"))
        val speed = DecodeSpeed(allowedCpus = { seen.removeFirst() })

        repeat(6) { speed.timed(SECOND) { "text" } }

        assertEquals("0-7/0-6", speed.cpus())
        val line = speechSummary(
            SpeechModelId.COHERE_TRANSCRIBE.id, VadParameters.FILE, SummaryInput.FILE, InputReport.NONE, CaptureSetup.NONE,
            SpeechLevel(), speed, "1.13.8", RunEnd.DONE
        )
        assertEquals("cpus=0-7/0-6", line.substringAfter("medianRtf=").substringAfter(" ").substringBefore(" sherpa-onnx"))
    }

    @Test
    fun `a decode whose thread can't be read adds no CPUs, and a run without timed decodes has none`() {
        val unread = DecodeSpeed(allowedCpus = { null })
        unread.timed(SECOND) { "text" }
        // Lost utterances and decodes counted by hand were never timed on a decoding thread.
        val untimed = DecodeSpeed(allowedCpus = { "0-2" }).apply {
            recordLost()
            record(80 * MS, SECOND)
        }

        assertEquals("none", unread.cpus())
        assertEquals("none", untimed.cpus())
    }

    @Test
    fun `a read of the CPUs that throws costs the decode nothing, its text comes back and is timed`() {
        // Inside a live capture's decode: a diagnostic that threw would lose the utterance.
        val reads = ArrayDeque(listOf<() -> String?>({ throw IllegalStateException("no status") }, { "0-6" }))
        val speed = DecodeSpeed(allowedCpus = { reads.removeFirst()() })

        assertEquals("first", speed.timed(SECOND) { "first" })
        assertEquals("second", speed.timed(SECOND) { "second" })

        assertEquals(2, speed.count)
        assertEquals("0-6", speed.cpus())
    }

    @Test
    fun `a run ends done without an error, cancelled by a cancellation, failed by anything else`() {
        assertEquals(RunEnd.DONE, RunEnd.of(null))
        assertEquals(RunEnd.CANCELLED, RunEnd.of(CancellationException("the recorder closed")))
        assertEquals(RunEnd.FAILED, RunEnd.of(IllegalStateException("model failed")))
        assertEquals(RunEnd.FAILED, RunEnd.of(OutOfMemoryError()))
    }

    @Test
    fun `numbers are written the same on a German phone`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val speed = DecodeSpeed().apply { record(81 * MS, SECOND) }
            val level = SpeechLevel().apply { record(-41.83) }
            val line = speechSummary(
                SpeechModelId.LIVE.id, VadParameters.FILE, SummaryInput.FEED, InputReport.NONE,
                CaptureSetup("none", "closed>open", "auto(+12.5/+30.0dB)"), level, speed, "1.13.8", RunEnd.DONE
            )

            assertEquals(
                "Speech summary: model=parakeet-tdt-0.6b-v3-int8 vad=FILE threshold=0.30 minSilence=0.70s " +
                    "maxSpeech=20.0s mic=feed effects=none mics=0 device=none fold=closed>open gain=auto(+12.5/+30.0dB) " +
                    "speechLevel=-41.8dBFS p90=-41.8dBFS finals=1 lost=0 medianRtf=0.081 cpus=none sherpa-onnx=1.13.8 ended=done",
                line
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
