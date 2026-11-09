package com.satori.feature.stt.datasource

import com.satori.domain.model.VadParameters
import com.satori.domain.repository.SpeechLeftForAfterStopException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * How fast the complete utterances of one run were decoded, as real-time factors: decode time over
 * the length of the audio decoded (0.1: a 10 s utterance in 1 s). Partial results are left out;
 * they decode the same audio again while it is said. A decode is timed inside the recording's
 * decode lock, so time spent waiting for a partial one to finish doesn't count. Also counts the
 * complete utterances that couldn't be decoded at all ([lost]), and notes the CPUs each timed
 * decode's thread was allowed ([cpus]), which say the scheduling group it ran in.
 *
 * @param allowedCpus The CPUs the calling thread may run on now, or null when unknown ([ThreadCpus])
 */
internal class DecodeSpeed(private val allowedCpus: () -> String? = ThreadCpus::current) {
    private val factors = mutableListOf<Double>()
    private val cpuSets = mutableListOf<String>()
    private var lostCount = 0

    /** How many decodes were timed. */
    val count: Int
        get() = synchronized(factors) { factors.size }

    /**
     * How many complete utterances found the speech model released, so they were never decoded:
     * they came after the recorder stopped waiting for the last words after Stop.
     */
    val lost: Int
        get() = synchronized(factors) { lostCount }

    /** Counts a complete utterance that found no speech model ([lost]). */
    fun recordLost() {
        synchronized(factors) { lostCount++ }
    }

    /** Counts a decode of [samples] samples (16 kHz) that took [nanos]. */
    fun record(nanos: Long, samples: Int) {
        if (samples <= 0) return
        val audioNanos = samples * NANOS_PER_SECOND / SpeechRecognition.SAMPLE_RATE_HZ
        synchronized(factors) { factors += nanos / audioNanos }
    }

    /** Runs [decode] on [samples] samples of audio and counts how long it took, and where it ran ([noteCpus]). */
    inline fun <T> timed(samples: Int, decode: () -> T): T {
        val started = System.nanoTime()
        return decode().also {
            record(System.nanoTime() - started, samples)
            noteCpus()
        }
    }

    /**
     * Notes the CPUs the calling thread, which has just decoded, may run on. Android sets them by
     * the app's scheduling group, which can change during a run: the screen goes off, a service
     * starts or ends. A read that fails notes nothing: it runs inside a live capture's decode,
     * and a diagnostic never costs the utterance just decoded.
     */
    fun noteCpus() {
        val cpus = try {
            allowedCpus()
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(factors) { if (cpus !in cpuSets) cpuSets += cpus }
    }

    /**
     * The CPUs the timed decodes ran on ([noteCpus]): one list, or several in the order the decodes
     * first ran on them, joined with "/"; "none" before any.
     */
    fun cpus(): String = synchronized(factors) { if (cpuSets.isEmpty()) "none" else cpuSets.joinToString("/") }

    /** The middle factor (for an even count, halfway between the two middle ones), or null before any. */
    fun median(): Double? {
        val sorted = synchronized(factors) { factors.sorted() }
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * The microphone input a summary names when a run had none: a debug build's WAV feed
 * ([DebugAudioFeed]), a recording's kept audio transcribed again, or nothing that opened.
 */
internal object SummaryInput {
    const val FEED = "feed"
    const val FILE = "file"
    const val NONE = "none"
}

/**
 * What the phone did with a capture's microphone input, for its summary line, so captures from the
 * three inputs can be compared: the types of the audio effects on the capture stream
 * (AudioRecordingConfiguration.getEffects), such as the noise suppression and echo cancelling of
 * the input for calls, and how many microphones recorded (AudioRecord.getActiveMicrophones). Null
 * for what the phone hasn't said: the audio server lists a recording only a moment after it
 * starts, and either call can fail.
 *
 * @property effects The effect types: AEC, AGC and NS for the echo cancelling, automatic gain
 *   control and noise suppression Android defines, the type's UUID for any other
 * @property microphones How many microphones recorded
 */
internal data class InputReport(val effects: List<String>?, val microphones: Int?) {

    /** Whether the phone has said both. */
    val isComplete: Boolean
        get() = effects != null && microphones != null

    /** What [newer] says, and what this one said where [newer] says nothing. */
    fun updatedBy(newer: InputReport): InputReport =
        InputReport(effects = newer.effects ?: effects, microphones = newer.microphones ?: microphones)

    companion object {
        /** A microphone that records, before the phone has said anything about it. */
        val UNKNOWN = InputReport(effects = null, microphones = null)

        /** No microphone: a debug feed, a recording's kept audio, or a capture whose input didn't open. */
        val NONE = InputReport(effects = emptyList(), microphones = 0)
    }
}

/**
 * Where a capture's audio came from beyond its input, for its summary line: the devices Android
 * recorded it from ([Microphone.routedDevice]), how the phone was folded ([PhonePosture]), and the
 * gain on what the recognizer heard ([CaptureGain.describe]). A device or a fold that changed
 * while it recorded is a list in the order they came, joined with ">"; "?" when the phone never
 * said.
 */
internal data class CaptureSetup(val device: String, val fold: String, val gain: String) {
    companion object {
        /** No capture: a recording's kept audio transcribed again. */
        val NONE = CaptureSetup(device = "none", fold = "none", gain = "off")
    }
}

/**
 * How a run of the speech recognition ended, for its summary line. A run that didn't get to the
 * end of its audio is logged too, with what it measured until then: one cut short is often the
 * slow one whose numbers are needed most.
 */
internal enum class RunEnd(val label: String) {
    /** It recognized all of its audio, up to Stop or the end of the file. */
    DONE("done"),

    /** It was cancelled first: the recorder closed while it still ran, or Transcribe again was left. */
    CANCELLED("cancelled"),

    /**
     * Stop came before its speech model was in, and the recording keeps its audio: what it
     * recorded is left for the finisher to transcribe after Stop
     * ([com.satori.domain.repository.SpeechLeftForAfterStopException]).
     */
    LEFT("left"),

    /** An error ended it. */
    FAILED("failed");

    companion object {
        /** How a run ended that threw [error], or nothing. */
        fun of(error: Throwable?): RunEnd = when (error) {
            null -> DONE
            is CancellationException -> CANCELLED
            is SpeechLeftForAfterStopException -> LEFT
            else -> FAILED
        }
    }
}

/**
 * The field log's line for one capture, or one Transcribe again: the speech model (or models: a
 * capture whose recording was switched to another language names each in turn, joined with ">"),
 * voice detection and microphone input that made the transcript, what the phone did with that
 * input ([report]), where its audio came from ([setup]: the device, the fold, the gain), how loud
 * the speech was as the recognizer heard it ([SpeechLevel], median and 90th percentile, after the
 * gain), how many complete utterances were decoded, and lost to a released model, their median
 * real-time factor and the CPUs their decoding thread was allowed ([DecodeSpeed], [ThreadCpus]),
 * and how the run [end]ed. Names, values and counts only, never what was said.
 * Numbers are written the same way whatever the phone's language, so lines from different days and
 * builds compare.
 *
 * The effects are listed in order of their names, joined by "+", or "none"; "?" stands for what
 * the phone didn't say, the effects or the number of microphones.
 *
 * @param model The id of the speech model ([com.satori.domain.model.SpeechModelId.id]), or
 *   the ids of a capture's models in the order they transcribed it
 *   ("parakeet-tdt-0.6b-v3-int8>indicconformer-ml-int8")
 * @param input The input used: a [com.satori.domain.model.MicSource] name, with
 *   "(fallback)" when it replaced the one asked for, or one of [SummaryInput]
 * @param report What the phone did with the input; [InputReport.NONE] for a run without one
 * @param setup The device, fold and gain of a capture; [CaptureSetup.NONE] for a run without one
 * @param library The sherpa-onnx version of the native library
 */
internal fun speechSummary(
    model: String,
    vad: VadParameters,
    input: String,
    report: InputReport,
    setup: CaptureSetup,
    level: SpeechLevel,
    speed: DecodeSpeed,
    library: String,
    end: RunEnd
): String {
    fun dbfs(value: Double?) = value?.let { String.format(Locale.ROOT, "%.1fdBFS", it) } ?: "none"
    val median = speed.median()?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "none"
    val effects = report.effects?.let { types -> if (types.isEmpty()) "none" else types.sorted().joinToString("+") } ?: "?"
    val microphones = report.microphones?.toString() ?: "?"
    return String.format(
        Locale.ROOT,
        "Speech summary: model=%s vad=%s threshold=%.2f minSilence=%.2fs maxSpeech=%.1fs mic=%s effects=%s " +
            "mics=%s device=%s fold=%s gain=%s speechLevel=%s p90=%s finals=%d lost=%d medianRtf=%s cpus=%s " +
            "sherpa-onnx=%s ended=%s",
        model, vad.profile, vad.threshold, vad.minSilenceDuration, vad.maxSpeechDuration, input, effects,
        microphones, setup.device, setup.fold, setup.gain, dbfs(level.percentile(50)), dbfs(level.percentile(90)),
        speed.count, speed.lost, median, speed.cpus(), library, end.label
    )
}
