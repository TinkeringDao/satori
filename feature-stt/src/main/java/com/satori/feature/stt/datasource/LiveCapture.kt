package com.satori.feature.stt.datasource

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.satori.data.audio.CaptureAudioWriter
import com.satori.data.audio.RecordingAudioFiles
import com.satori.data.datasource.RecognitionResult
import com.satori.domain.model.MicSource
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.VadParameters
import com.satori.domain.repository.MicrophoneLostException
import com.satori.domain.repository.SpeechLeftForAfterStopException
import com.satori.domain.repository.SpeechModelLoadException
import com.satori.feature.stt.datasource.SherpaOnnxDataSource.ReadFailures
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a capture's audio comes from: the phone's microphone inputs, or a debug build's WAV feed.
 * [SherpaOnnxDataSource] records with an AudioRecord; a test gives audio of its own.
 */
internal interface CaptureDevice {
    /**
     * The samples of a debug build's WAV feed to record instead of the microphone, used up by this
     * call ([DebugAudioFeed]), or null to record from the microphone. Reads a file.
     */
    fun takeFeed(): ShortArray?

    /**
     * Opens [source] and starts recording from it, or returns null when the phone can't, with the
     * reason added to [refusals]. With a [preferred] built-in microphone other than
     * [MicSwitches.Device.AUTO] (a debug switch) it asks Android for that one.
     */
    fun open(source: MicSource, refusals: MutableList<String>, preferred: MicSwitches.Device): Microphone?
}

/** A microphone input that records: read by the capture loop, closed from any thread. */
internal interface Microphone {
    /**
     * Reads the next audio into [buffer], waiting until there is some: how many samples, or 0 or
     * an error code (below 0) when there are none.
     */
    fun read(buffer: ShortArray): Int

    /** Stops recording and frees the input; a [read] it cuts short returns. */
    fun close()

    /**
     * What the phone does with this input as it records, as far as it says so now. Asked by the
     * capture loop while another thread may close the input; only for the field log, so the
     * capture goes on however this ends.
     */
    fun report(): InputReport

    /** How many channels it records, for the field log. */
    fun channelCount(): Int = 1

    /**
     * The device Android records this input from now ([deviceName], "builtin_mic/bottom"), or
     * null while it hasn't said; for the field log.
     */
    fun routedDevice(): String? = null

    /**
     * Calls [onChange] with the device ([routedDevice]) each time Android routes this input anew,
     * until it is closed; for the field log.
     */
    fun watchRoute(onChange: (String?) -> Unit) = Unit
}

/**
 * How the phone is held while a capture records, for its field log lines: open, closed, or half
 * open, as far as the phone says. On 2026-10-13 every quiet recording whose fold state is known
 * was made with the phone closed. [SherpaOnnxDataSource] reads the phone's hinge or its displays
 * ([PhoneFold]); a test says what it likes.
 */
internal interface PhonePosture {
    /** "open", "closed", "half", or "unknown" when the phone doesn't say (its screens are off, say). */
    fun fold(): String

    /** Calls [onChange] with each new [fold] until the handle it returns is closed. */
    fun watchFold(onChange: (String) -> Unit): AutoCloseable

    /** A phone that never says. */
    object Unknown : PhonePosture {
        override fun fold(): String = "unknown"

        override fun watchFold(onChange: (String) -> Unit): AutoCloseable = AutoCloseable {}
    }
}

/**
 * The scheduling priority of the thread a capture records a microphone on: [raise]d as the
 * microphone loop starts, so a loaded phone drops no audio, and [restore]d as it ends.
 */
internal interface AudioThreadPriority {
    /** Raises the calling thread to the audio priority, and returns the priority it had. */
    fun raise(): Int

    /** Gives the calling thread [previous], the priority [raise] returned. */
    fun restore(previous: Int)

    /** Android's priority for a thread that keeps pace with a microphone. */
    object UrgentAudio : AudioThreadPriority {
        override fun raise(): Int {
            val previous = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            return previous
        }

        override fun restore(previous: Int) = Process.setThreadPriority(previous)
    }
}

/**
 * The recognizer side of a capture: the live speech model, which the data source holds for every
 * capture, of the recording's language, and the capture's own voice detector. [SherpaOnnxDataSource]
 * uses sherpa-onnx; a test decodes in its own way.
 */
internal interface LiveSpeech {
    /**
     * Loads the speech model of the recording's language unless it is loaded, and a new voice
     * detector for this capture. Takes seconds when the speech model loads; when the language is
     * switched while the model's files are read in, that load stops and the new language's model
     * loads instead.
     */
    suspend fun load(): VoiceDetector

    /**
     * [samples] decoded: their text, or null for none, also once the speech model is released.
     * With [speed], a complete utterance, which is timed there; without, a partial one.
     */
    suspend fun decode(samples: FloatArray, speed: DecodeSpeed?): String?

    /** Tells the phone that the calling thread, which runs the recognition, is to keep pace. */
    fun hintPerformance()

    /**
     * The speech model the capture transcribes with: the one [load] is to load until it has, then
     * the one in memory for it. For the field log.
     */
    fun model(): SpeechModelId

    /**
     * The model the capture is to change to now, because the recording's language was switched,
     * or null while it has the right one
     * ([com.satori.domain.model.RecordingLanguage.liveModel]). Quick: asked before each
     * chunk of audio while no utterance is open.
     */
    fun switchDue(): SpeechModelId?

    /**
     * Frees the model in memory and loads [model] in its place, between two utterances, which takes
     * seconds. True once it is in; false when it didn't load and the model before is in again, which
     * the recorder is told ([com.satori.domain.model.CaptureLanguage.refused]).
     *
     * @throws Throwable when neither loads: the capture can't go on
     */
    suspend fun switchTo(model: SpeechModelId): Boolean
}

/**
 * One capture of a recording, microphone first ([SherpaOnnxDataSource.startRecording]). The
 * microphone opens as the capture starts, and only once it is open do the speech model and the
 * capture's voice detector load, while the audio waits in a queue without limit: what is said
 * right after the press is recorded however long the load takes (1-3 s for the live speech model
 * after Stop released it). The recognition then works through the queue and catches up within a
 * few seconds, as 3 s of audio decode in about 0.2 s on the razr (real-time factor 0.075).
 *
 * - A Stop while they load closes the microphone. What it recorded is still transcribed once they
 *   are in, its last words come out, and [run] returns. A Stop that comes while the speech model's
 *   files are still read in or room is made for it, in a recording that keeps its audio, stops the
 *   load instead ([LiveSpeech.load]): [run] fails at once with a [SpeechLeftForAfterStopException],
 *   the audio file keeps what was recorded and the [SpeechEvidence] notes it as never transcribed,
 *   and the finisher transcribes it after Stop.
 * - When they fail to load, also with an Error of the native library, the microphone is closed,
 *   then the audio file, which keeps what was recorded, and [run] fails with a
 *   [SpeechModelLoadException]. The capture's [SpeechEvidence] notes that audio as never
 *   transcribed, so the recording isn't taken for a silent one.
 * - Until the recognition has heard the audio, the evidence counts it as speech: a capture cut off
 *   meanwhile, as the recorder closes during the load, never transcribes it.
 * - Any other failure ends the capture loop at once, as a cancel does, which closes the microphone.
 * - When nothing is recorded, as no input opened or Stop came while it opened, nothing loads.
 *
 * It tells [progress] the first audio (LISTENING) and that the speech model and the voice detector
 * are ready (TRANSCRIBING, once both have happened). One instance is one capture: [run] is called
 * once, [stop] from any thread.
 *
 * A switch of the recording's language changes the speech model between two utterances
 * ([LiveSpeech.switchDue], [LanguageSwitch]): the recognition waits while the model of the new
 * language loads in place of the old one, and the microphone, the queue and the audio file go on
 * as they are, so what is said meanwhile is transcribed by the new model once it is in. A switch
 * while the model loads is made as soon as the load ends, before the recognition starts, so the
 * model no longer wanted is freed at once and decodes nothing (one that came while its files were
 * read in stopped its load already, [LiveSpeech.load]). Not after Stop: the last words are decoded
 * by the model they began with. When the new model doesn't load, the old one is loaded again and
 * the capture goes on; only when neither loads does it end, as one whose model failed to load at
 * the start, with what was recorded kept in the audio file.
 *
 * As its audio starts it logs where it comes from: the input, its channels, the device Android
 * routes it from, how the phone is folded and the debug switches in use ([MicSwitches]); then each
 * change of the device or the fold while it records, and the summary line names them all.
 *
 * @param device Where the audio comes from
 * @param micInput Which input opens; shared by the captures, so a fallback is reported once
 * @param evidence What the recognition of this capture knows of speech its lines may lack
 * @param audioLevel Where the level of each chunk goes, for the recorder's level display
 * @param library The sherpa-onnx version, for the summary line; read on IO as the capture ends,
 *   as the first look at it loads the native library when nothing has yet
 * @param logSummary Where the summary line goes: the field log
 * @param wallClock Milliseconds of the wall clock: when the audio began, how long a read took
 * @param monotonicClock Milliseconds that never jump: how long reads failed in a row, the pace of
 *   a debug feed, how long the load took
 * @param audioPriority The priority of the thread the microphone loop records on
 * @param posture How the phone is folded, for the field log
 * @param logLine Where the lines of where the audio comes from go: the field log
 */
internal class LiveCapture(
    private val device: CaptureDevice,
    private val micInput: MicInput,
    private val progress: CaptureStates.Progress,
    private val evidence: SpeechEvidence,
    private val audioLevel: MutableStateFlow<Float>,
    private val library: () -> String,
    private val logSummary: (String) -> Unit = { Log.i(TAG, it) },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val audioPriority: AudioThreadPriority = AudioThreadPriority.UrgentAudio,
    private val posture: PhonePosture = PhonePosture.Unknown,
    private val logLine: (String) -> Unit = { Log.i(TAG, it) }
) {
    private val capture = Capture<Microphone> { it.close() }

    // The audio on its way from the capture loop to the recognition, which takes from it only once
    // the speech model is in: nothing may be dropped while it loads, so nothing limits it.
    private val samples = Channel<FloatArray>(Channel.UNLIMITED)

    // How much audio the capture loop has handed over so far, in samples.
    private val recordedSamples = AtomicLong()

    // Completed by the capture loop: true once audio comes (the microphone is open or the feed
    // started), false when none will, as no input opened or Stop came while it opened.
    private val recording = CompletableDeferred<Boolean>()

    // Set by the capture loop when the microphone stopped delivering audio, or didn't open. Thrown
    // once what it delivered before has been transcribed, so the last words are emitted and then
    // the stream fails like it does on any other error. Always a lost microphone, which the
    // recorder tells from a failure that may have left speech untranscribed.
    private val captureFailure = AtomicReference<MicrophoneLostException?>(null)

    // Keeps the audio, if asked to; set by the capture loop as the microphone starts and read by
    // the recognition, which marks each complete utterance in it.
    private val audioWriter = AtomicReference<CaptureAudioWriter?>(null)

    // What the capture records from, and what the phone does with it, for the summary line.
    @Volatile private var input = SummaryInput.NONE
    @Volatile private var report = InputReport.NONE

    // The devices Android routed the input from and the fold states, in the order they came, and
    // the gain on what the recognizer heard; set as the audio starts. And the speech models that
    // transcribed it, one more for each switch of the recording's language.
    private val devices = Trail()
    private val folds = Trail()
    private val models = Trail()
    @Volatile private var gain = CaptureGain(MicSwitches.Gain.Off)

    // The start line is logged: later changes of the device or the fold get a line of their own.
    @Volatile private var started = false

    /** Stop came: the capture reads no more audio. */
    val isStopped: Boolean
        get() = capture.isStopped

    /** When the capture's audio began, on the wall clock; 0 until it does. */
    @Volatile var startedAtMs = 0L
        private set

    /**
     * Stop: the microphone closes now, or as soon as it has opened, and [run] returns once what it
     * recorded is transcribed.
     */
    fun stop() = capture.stop()

    /**
     * Records from [micSource], or a debug feed, until [stop], and hands the results of the
     * recognition to [emit]: partial ones while something is said, and complete ones in order,
     * each marked under its id in the index of [audio] when the audio is kept. Ends with the
     * summary line, however it ends.
     *
     * @param vad The voice detection values of [speech]'s detector, for the summary line
     * @param audio Where to keep the audio, or null to keep none
     * @param metrics Where the field log's capture metrics are counted, or null for none
     * @param onStoppedWhileOpening Called when Stop came while the microphone opened, so nothing
     *   was recorded
     * @param switches The debug switches of this capture: the built-in microphone asked for, and
     *   the gain on what the recognizer hears ([CaptureGain]); the kept audio is the microphone's
     * @throws MicrophoneLostException when no input opened, or the microphone stopped delivering
     *   audio; what it delivered until then is emitted first
     * @throws SpeechModelLoadException when the speech model or the voice detector didn't load
     */
    suspend fun run(
        micSource: MicSource,
        vad: VadParameters,
        speech: LiveSpeech,
        audio: RecordingAudioFiles?,
        metrics: AudioMetrics?,
        onStoppedWhileOpening: () -> Unit,
        switches: MicSwitches = MicSwitches.DEFAULT,
        emit: suspend (RecognitionResult) -> Unit
    ): Unit = coroutineScope {
        gain = CaptureGain(switches.gain, log = logLine)
        models.restart(speech.model().id)
        val recordingJob = launch(Dispatchers.IO) { record(micSource, switches, audio, metrics, onStoppedWhileOpening) }
        val speed = DecodeSpeed()
        val level = SpeechLevel()
        var error: Throwable? = null
        try {
            if (recording.await()) {
                val detector = load(speech, recordingJob)
                val modelSwitch = LanguageSwitch(speech, recordingJob)
                // Switched while the model loaded: the model goes now, before any audio is decoded.
                if (modelSwitch.due()) modelSwitch.switch(atMs = 0)
                progress.ready()
                recognize(LevelMeteringDetector(detector, level), speech, speed, metrics, modelSwitch, emit)
            }
        } catch (e: Throwable) {
            error = e
            // Nothing takes the audio any more, and the finally below waits for the capture loop,
            // which would otherwise record on until Stop: it ends now. A cancel of the run has
            // cancelled it already, and a load that failed has stopped it.
            recordingJob.cancel()
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // Also for a capture cancelled before its last words were in, or whose load
                // failed, with what it measured. On IO, as the caller's thread is the main one
                // and the first look at the library's version loads it when no load has yet.
                val setup = CaptureSetup(device = devices.toString(), fold = folds.toString(), gain = gain.describe())
                logSummary(speechSummary(models.toString(), vad, input, report, setup, level, speed, library(), RunEnd.of(error)))
                // Every utterance is marked by now, and the capture loop ends at once once the
                // queue is closed or the flow cancelled, so after it no more audio comes and the
                // file can be finished. Waiting for it also finishes a writer it made after a
                // cancel came while the microphone opened, which would otherwise stay open.
                recordingJob.join()
                audioWriter.get()?.let { writer ->
                    writer.close()
                    withTimeoutOrNull(AUDIO_FINISH_WAIT_MS) { writer.join() }
                }
            }
        }
        val failure = captureFailure.get()
        if (failure != null) throw failure
    }

    /**
     * Loads [speech]'s speech model and voice detector on IO while the microphone records. When
     * that fails, the microphone is closed and the capture loop has ended before the
     * [SpeechModelLoadException] is thrown, so the audio file gets nothing more before [run]
     * finishes it, and the audio the loop queued, which nothing will transcribe, is noted in the
     * [evidence]. That holds for an Error too: sherpa-onnx loads its native library as its first
     * recognizer or voice detector is made, and a library that doesn't load throws
     * UnsatisfiedLinkError there, then NoClassDefFoundError at every later try. A load that Stop
     * stopped ([StoppedBeforeModelException]) ends the same way, with a
     * [SpeechLeftForAfterStopException]: the microphone is closed already.
     */
    private suspend fun load(speech: LiveSpeech, recordingJob: Job): VoiceDetector {
        val started = monotonicClock()
        val detector = try {
            withContext(Dispatchers.IO) { speech.load() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StoppedBeforeModelException) {
            // Stop closed the microphone; the loop ends with the read it cut short.
            withContext(NonCancellable) { recordingJob.join() }
            if (recordedSamples.get() > 0) evidence.audioDropped()
            logLine("Stop came ${monotonicClock() - started} ms into the load of ${e.model.id}: nothing loaded, ${recordedMs()} ms " +
                "of audio kept untranscribed for after Stop")
            throw SpeechLeftForAfterStopException("Stop came before the live speech model was in")
        } catch (e: Throwable) {
            capture.stop()
            // Ends at once, with the read the Stop cut short; also when the recorder closes now,
            // so that read is counted below.
            withContext(NonCancellable) { recordingJob.join() }
            if (recordedSamples.get() > 0) evidence.audioDropped()
            Log.e(TAG, "Speech model failed to load after ${monotonicClock() - started} ms (${e.javaClass.simpleName}); " +
                    "${recordedMs()} ms of audio recorded and not transcribed")
            throw SpeechModelLoadException("The live speech model failed to load", e)
        }
        // Named here as well, as a model already in memory has no load line.
        models.restart(speech.model().id)
        Log.i(TAG, "Speech model ready after ${monotonicClock() - started} ms, with ${recordedMs()} ms of audio waiting (${speech.model().id})")
        return detector
    }

    /**
     * Runs the recognition over the queue, on the Default dispatcher, until the capture loop has
     * closed it; between two utterances [modelSwitch] changes the speech model when the recording's
     * language was switched.
     */
    private suspend fun recognize(
        detector: VoiceDetector,
        speech: LiveSpeech,
        speed: DecodeSpeed,
        metrics: AudioMetrics?,
        modelSwitch: ModelSwitch,
        emit: suspend (RecognitionResult) -> Unit
    ): Unit = withContext(Dispatchers.Default) {
        speech.hintPerformance()

        val thread = Thread.currentThread()
        metrics?.processingThreadId = thread.id
        metrics?.processingThreadName = thread.name
        Log.i(TAG, "Processing thread: ${thread.name} (${thread.id})")

        SpeechRecognition(
            detector = detector,
            decode = { speech.decode(it, speed = null) },
            partials = true,
            strict = false,
            metrics = metrics,
            decodeFinal = { speech.decode(it, speed) },
            evidence = evidence,
            modelSwitch = modelSwitch
        ).run(samples) { result ->
            emit(result)
            val span = result.span
            if (span != null) audioWriter.get()?.markLine(result.id, span.startMs, span.endMs)
        }

        Log.i(TAG, "Processing completed, generating metrics report")
        if (metrics != null) AudioMetrics.logReport(metrics)
    }

    /**
     * The change of the speech model after a switch of the recording's language, between two
     * utterances ([ModelSwitch]): [speech] frees the model in memory and loads the new language's in
     * its place, on IO, while the capture loop records on into the queue and the audio file. Due only
     * while the capture records: after Stop the last words go to the model they began with.
     *
     * The capture is LISTENING meanwhile, as while its model loads at the start ([CaptureStates]).
     * When the new model doesn't load, [speech] has the old one in again and the capture goes on with
     * it. When neither loads, nothing can transcribe what the microphone records any more: the
     * microphone is closed and the capture loop ended, as when the model doesn't load at the start,
     * the audio file keeps what was recorded, the evidence notes the audio that waited, and the
     * capture fails with a [SpeechModelLoadException].
     */
    private inner class LanguageSwitch(private val speech: LiveSpeech, private val recordingJob: Job) : ModelSwitch {
        override fun due(): Boolean = !capture.isStopped && speech.switchDue() != null

        override suspend fun switch(atMs: Long) {
            val model = speech.switchDue() ?: return
            val from = speech.model()
            val started = monotonicClock()
            // Listening again while the model changes, as at the start: the finishing of an older
            // recording waits with its next step, and a recorder that closes meanwhile knows that
            // the audio waiting was never transcribed.
            progress.reloading()
            val switched = try {
                withContext(Dispatchers.IO) { speech.switchTo(model) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                capture.stop()
                withContext(NonCancellable) { recordingJob.join() }
                if (recordedMs() > atMs) evidence.audioDropped()
                Log.e(TAG, "No live speech model after a switch: neither ${model.id} nor ${from.id} loaded " +
                        "(${e.javaClass.simpleName}); ${recordedMs() - atMs} ms of audio recorded and not transcribed")
                throw SpeechModelLoadException("No live speech model loaded after a switch of the recording's language", e)
            }
            progress.ready()
            val took = monotonicClock() - started
            if (switched) {
                models.add(model.id)
                logLine("Live speech model switched to ${model.id} (was ${from.id}) in $took ms, at $atMs ms of audio, " +
                        "with ${recordedMs() - atMs} ms of audio waiting")
            } else {
                Log.w(TAG, "Live speech model ${model.id} didn't load; back on ${from.id} after $took ms, at $atMs ms of audio")
            }
        }
    }

    /**
     * The capture loop, on IO: records from the debug feed or the microphone into the queue until
     * Stop, a microphone that stopped delivering, or a cancel, and closes the microphone and the
     * queue however it ends. Says through [recording] whether audio comes.
     */
    private suspend fun record(
        micSource: MicSource,
        switches: MicSwitches,
        audio: RecordingAudioFiles?,
        metrics: AudioMetrics?,
        onStoppedWhileOpening: () -> Unit
    ) {
        // The thread's priority before the microphone loop raised it, given back as the loop ends.
        var priorityBefore: Int? = null
        // Watched from the start, so a fold while the microphone opens is in the start line.
        val foldWatch = posture.watchFold(::foldChanged)
        try {
            val thread = Thread.currentThread()
            metrics?.recordingThreadId = thread.id
            metrics?.recordingThreadName = thread.name
            Log.i(TAG, "Recording thread: ${thread.name} (${thread.id})")

            // Debug builds can replace the microphone with a pushed WAV file (DebugAudioFeed).
            val feed = device.takeFeed()
            if (feed != null) {
                input = SummaryInput.FEED
                startedAtMs = wallClock()
                logStart(SummaryInput.FEED, channels = 1, routed = SummaryInput.NONE, switches)
                // Its first chunk goes out at once.
                progress.heard()
                val writer = startAudio(audio)
                recording.complete(true)
                feedFromFile(feed, writer, metrics)
                return
            }

            // URGENT_AUDIO, so the kernel prefers this thread over background work when scheduling:
            // a loaded phone (busy foreground apps, GC pauses, a model loading) then drops no audio.
            // Only for a microphone: its loop never suspends, so it keeps this thread to its end,
            // while a debug feed's delay() resumes on other threads.
            priorityBefore = audioPriority.raise()
            val refusals = mutableListOf<String>()
            val opened = micInput.open(micSource) { source -> device.open(source, refusals, switches.device) }
            if (opened == null) {
                // Ends as a microphone that stops delivering does: the flow fails with this.
                // Nothing was recorded, so nothing is left untranscribed either.
                val failure = MicrophoneLostException("No microphone input opened: ${refusals.joinToString("; ")}")
                Log.e(TAG, "${failure.message}")
                captureFailure.set(failure)
                return
            }
            if (opened.reportFallback) {
                Log.w(TAG, "Recording from ${opened.source}, as $micSource doesn't open " +
                        "(${refusals.joinToString("; ")}); not said again until the app restarts")
            }
            val microphone = opened.input
            // A Stop while the input opened found no microphone to close: attach() closes it now.
            if (!capture.attach(microphone)) {
                Log.i(TAG, "Recording stopped while the microphone opened")
                onStoppedWhileOpening()
                return
            }
            startedAtMs = wallClock()
            Log.i(TAG, "Microphone input: ${opened.source}")
            input = if (opened.fellBack) "${opened.source}(fallback)" else opened.source.name
            report = InputReport.UNKNOWN
            // Android may route the input only once it records: the first route is a change then.
            microphone.watchRoute(::routeChanged)
            logStart(opened.source.name, microphone.channelCount(), microphone.routedDevice(), switches)
            val writer = startAudio(audio)
            recording.complete(true)
            listen(microphone, opened.source, writer, metrics)
        } finally {
            foldWatch.close()
            // The thread goes back to the coroutine pool. Raised, the work it runs next, and the
            // native threads that work starts (ONNX Runtime's, llama.cpp's), would run ahead of
            // the screen's threads.
            priorityBefore?.let(audioPriority::restore)
            // After a Stop the microphone is closed already; a loop that ended by itself (the
            // microphone stopped delivering, the flow was cancelled) closes it here.
            capture.end()
            audioLevel.value = 0f
            samples.close()
            recording.complete(false)
        }
    }

    /**
     * The line of where the capture's audio comes from, as it starts: the input ([source]), its
     * [channels], the device Android routes it from ([routed], "pending" while it hasn't said),
     * how the phone is folded, and the debug switches. The device and the fold start the summary
     * line's lists here; later changes get lines of their own ([routeChanged], [foldChanged]).
     */
    private fun logStart(source: String, channels: Int, routed: String?, switches: MicSwitches) {
        routed?.let(devices::restart)
        val fold = posture.fold()
        folds.restart(fold)
        started = true
        logLine("Capture start: source=$source channels=$channels device=${routed ?: "pending"} fold=$fold ${switches.describe()}")
    }

    /** Android routes the input from [routed] now ([Microphone.watchRoute]). */
    private fun routeChanged(routed: String?) {
        val now = routed ?: "none"
        val was = devices.add(now) ?: return
        if (started) logLine("Capture route: device=$now (was ${was.ifEmpty { "pending" }})")
    }

    /** The phone is folded as [fold] says now ([PhonePosture.watchFold]). */
    private fun foldChanged(fold: String) {
        val was = folds.add(fold) ?: return
        if (started) logLine("Capture fold: $fold (was ${was.ifEmpty { "unknown" }})")
    }

    /** The writer that keeps the audio from now on, when it is to be kept. */
    private fun startAudio(audio: RecordingAudioFiles?): CaptureAudioWriter? =
        audio?.let { CaptureAudioWriter(it, startedAt = wallClock()) }?.also { audioWriter.set(it) }

    /**
     * Reads [microphone], the input [source], 100 ms at a time into the queue and the audio file,
     * until Stop, a microphone that stopped delivering, or a cancel.
     */
    private suspend fun listen(microphone: Microphone, source: MicSource, writer: CaptureAudioWriter?, metrics: AudioMetrics?) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        val readFailures = ReadFailures()
        var reportAsks = 0

        // Until Stop, or until the flow is cancelled: then nobody takes the audio any more.
        while (!capture.isStopped && currentCoroutineContext().isActive) {
            val readStartTime = wallClock()
            val ret = microphone.read(buffer)
            val readLatency = wallClock() - readStartTime

            if (ret > 0) {
                readFailures.onSuccess()
                progress.heard()
                metrics?.recordAudioRead(ret, readLatency, success = true)
                // The audio server lists a recording a moment after it starts, so the phone is
                // asked again at the next reads until it has said what it does with the input;
                // not after Stop, which releases the input, even for the read it cut short.
                if (!report.isComplete && reportAsks < REPORT_ASKS && !capture.isStopped) {
                    reportAsks++
                    report = report.updatedBy(askReport(microphone))
                }

                val chunk = FloatArray(ret) { buffer[it] / 32768.0f }
                audioLevel.value = meterLevel(levelDbfs(chunk), source)
                if (chunk.all { it.isFinite() }) {
                    queue(gain.apply(chunk))
                    // The samples as the microphone gave them, without the gain, for the audio file.
                    writer?.write(buffer, ret)
                } else {
                    Log.w(TAG, "Invalid samples detected in audio read")
                }
            } else {
                metrics?.recordAudioRead(0, readLatency, success = false)
                // The read that stop() cut short: the capture ends, nothing failed.
                if (capture.isStopped) break
                audioLevel.value = 0f
                val toReport = readFailures.onFailure(monotonicClock())
                val failure = readFailures.lostMicrophone(ret)
                if (failure != null) {
                    Log.e(TAG, "${failure.message}, ending the capture")
                    captureFailure.set(failure)
                    break
                }
                if (toReport > 0) {
                    Log.w(TAG, "Audio read failed: ret=$ret ($toReport failed reads, " +
                            "no audio for ${readFailures.failingForMs} ms)")
                }
                // A dead AudioRecord fails at once: wait instead of spinning. Sleep, not delay():
                // delay() can resume on another IO thread, which lacks the audio priority set
                // above, and this one would go back to the pool with it.
                Thread.sleep(ReadFailures.RETRY_DELAY_MS)
            }

            if (metrics != null && metrics.totalAudioReads % LOG_INTERVAL_READS == 0) {
                val elapsedSec = (wallClock() - startedAtMs) / 1000.0
                Log.d(TAG, "Recording stats: ${metrics.totalAudioReads} reads, " +
                        "${metrics.totalSamplesRead / SpeechRecognition.SAMPLE_RATE_HZ.toDouble()}s audio, " +
                        "avgLatency=${"%.1f".format(metrics.avgReadLatencyMs)}ms, " +
                        "elapsed=${elapsedSec}s")
            }
        }
        Log.i(TAG, "Recording job completed")
    }

    /**
     * Sends [feed] in 100 ms chunks at real-time pace, then silence, until Stop: the same stream
     * the microphone loop produces, so the rest of the pipeline can't tell them apart. [writer]
     * keeps it as the recording's audio, as it would the microphone's.
     */
    private suspend fun feedFromFile(feed: ShortArray, writer: CaptureAudioWriter?, metrics: AudioMetrics?) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        var position = 0
        var due = monotonicClock()
        while (!capture.isStopped) {
            for (i in chunk.indices) chunk[i] = feed.getOrElse(position + i) { 0 }
            val chunkSamples = FloatArray(chunk.size) { i -> chunk[i] / 32768.0f }
            position += chunk.size
            // On the scale the meter had before the input could be chosen.
            audioLevel.value = meterLevel(levelDbfs(chunkSamples), MicSource.MIC)
            metrics?.recordAudioRead(chunk.size, 0L, success = true)
            // The gain as on a microphone's audio, so a feed can try it; the file keeps the feed.
            queue(gain.apply(chunkSamples))
            writer?.write(chunk)
            due += 100L
            val wait = due - monotonicClock()
            if (wait > 0) delay(wait)
        }
        Log.i(TAG, "Recording job completed (debug feed)")
    }

    /**
     * What [microphone] says the phone does with it now. A Stop on another thread can release the
     * input at any moment, and asking a released AudioRecord throws; as the report is only for the
     * field log, a failed ask says nothing new and the capture goes on.
     */
    private fun askReport(microphone: Microphone): InputReport =
        try {
            microphone.report()
        } catch (e: Exception) {
            InputReport.UNKNOWN
        }

    /** Hands [chunk] to the recognition, which takes it once the speech model is in. */
    private suspend fun queue(chunk: FloatArray) {
        // Before the recognition can take it: the evidence counts it as speech until it is heard.
        evidence.audioRecorded(chunk.size)
        samples.send(chunk)
        recordedSamples.addAndGet(chunk.size.toLong())
    }

    private fun recordedMs(): Long = recordedSamples.get() * 1000 / SpeechRecognition.SAMPLE_RATE_HZ

    /**
     * Values in the order they came, a value that repeats the last one left out: the devices or
     * the fold states of a capture, which the summary line names as "closed>open". Its callers
     * are the capture loop and the phone's callbacks, so it locks.
     */
    private class Trail {
        private val values = mutableListOf<String>()

        /** Starts again from [value] alone. */
        @Synchronized
        fun restart(value: String) {
            values.clear()
            values += value
        }

        /** Adds [value]; returns the one before it ("" for none), or null when [value] repeats it. */
        @Synchronized
        fun add(value: String): String? {
            val last = values.lastOrNull()
            if (last == value) return null
            values += value
            return last ?: ""
        }

        /** The values joined with ">", or "?" for none. */
        @Synchronized
        override fun toString(): String = if (values.isEmpty()) "?" else values.joinToString(">")
    }

    private companion object {
        // The recognizer's tag, which the field log is read by.
        const val TAG = "SherpaOnnxDataSource"

        // 100 ms of audio: one microphone read, one chunk of a debug feed.
        const val CHUNK_SAMPLES = SpeechRecognition.SAMPLE_RATE_HZ / 10

        const val LOG_INTERVAL_READS = 50

        // How many reads ask what the phone does with the input before the summary line says it
        // doesn't know: those of the first 2 s.
        const val REPORT_ASKS = 20

        // How long the end of a recording waits for its audio file to be complete; it takes
        // milliseconds unless the storage stalls, and the writer finishes on its own regardless.
        const val AUDIO_FINISH_WAIT_MS = 1_000L
    }
}
