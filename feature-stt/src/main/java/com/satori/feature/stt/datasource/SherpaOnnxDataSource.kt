package com.satori.feature.stt.datasource

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VersionInfo
import com.satori.data.audio.RecordingAudioFiles
import com.satori.data.audio.Wav
import com.satori.data.datasource.RecognitionResult
import com.satori.data.datasource.SttDataSource
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.ReleasePurge
import com.satori.data.monitor.SystemProperty
import com.satori.data.monitor.withLoaded
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.MicSource
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.VadParameters
import com.satori.domain.model.VadProfile
import com.satori.domain.repository.MicrophoneLostException
import com.satori.domain.repository.SettingsRepository
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/**
 * Sherpa-ONNX implementation of SttDataSource.
 *
 * Handles audio recording, VAD (Voice Activity Detection), and ASR (Automatic Speech Recognition).
 * A recording transcribes with the live speech model of its language
 * ([com.satori.domain.model.RecordingLanguage.liveModel]: Parakeet v3 for English or German,
 * the Malayalam model for Malayalam), loaded once its microphone is open by a recording that finds
 * none or another language's, and releaseModel() can free its native heap (Parakeet's ~670 MB)
 * between sessions. A switch of the language while recording frees the one model and loads the
 * other between two utterances ([LiveCapture]), so the two are never in memory together; one while
 * the model's files are still read in stops that load first ([CaptureSpeechModel.load]). Every
 * recording starts with a new VAD, built from the voice detection settings as they are at that
 * moment ([VadProfile.LIVE]), and records from the microphone input they name (see [MicInput]);
 * each capture is a [LiveCapture], microphone first. The VAD and decoding steps are [SpeechRecognition], which
 * [transcribeFile] runs as well, on a recording's kept audio, with the speech model asked for and
 * voice detection values of its own ([VadParameters.FILE]); a recording can keep what the
 * recognizer hears ([com.satori.data.audio.CaptureAudioWriter]). Each run ends with one
 * summary line in the field log ([speechSummary]).
 *
 * @property context Android context
 * @property recognizers Builds a new recognizer of a speech model
 * @property vadFactory Builds the new VAD each capture and each file transcribed again starts with
 * @property settingsRepository Where each capture reads its microphone input and voice detection
 * @property audioManager System AudioManager used to activate Bluetooth SCO when available
 * @property memoryLines The memory line of each load and release of a speech model, in a debug
 *   build ([ModelMemoryLines]): the live model's and each whole-file run's own
 * @property releasePurge The purge of the native heap after each release of a speech model, the
 *   live model's and each whole-file run's own, in every build since 0.7.2 ([ReleasePurge]); none
 *   unless the app gives its own
 */
class SherpaOnnxDataSource(
    private val context: Context,
    private val recognizers: SpeechRecognizerFactory,
    private val vadFactory: VadFactory,
    private val settingsRepository: SettingsRepository,
    private val audioManager: AudioManager,
    private val memoryLines: ModelMemoryLines = ModelMemoryLines.forApp(context, TAG),
    private val releasePurge: ReleasePurge = ReleasePurge.NONE
) : SttDataSource {

    // Managed instances — null when model is not loaded, non-null during an active session.
    @Volatile private var activeVad: Vad? = null

    // Serializes all recognizer.decode() calls. OfflineRecognizer is NOT thread-safe for
    // concurrent decode() invocations: even though each call uses its own OfflineStream,
    // the underlying ONNX InferenceSession has shared internal state that causes SIGSEGV
    // (fault addr 0x0/0x8 in libonnxruntime decode+128) when two decode() calls run in
    // parallel. The mutex ensures only one decode runs at a time across all call sites:
    // forced split, partial inference (async launch), VAD segment, and post-loop flush.
    private val decodeMutex = Mutex()

    // Each chunk's level on the scale of the input it came from (meterLevel).
    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // How far the newest capture has come; each capture moves it through its own Progress.
    private val captureStates = CaptureStates()
    override val captureState: StateFlow<CaptureState> = captureStates.flow

    // The live speech model's recognizer, of the language a capture records in: loaded by
    // initialize() or by a capture that finds none or another one's, swapped by a switch of the
    // language, freed by releaseModel(). Whether it is in memory is what the finisher of recordings
    // waits on.
    private val liveRecognizer = liveSpeechRecognizer(
        memoryLines,
        load = { model, stop -> recognizers.create(model, RecognizerUse.LIVE, stop = stop) },
        free = OfflineRecognizer::release,
        purge = releasePurge
    )
    override val liveModelLoaded: StateFlow<Boolean> = liveRecognizer.loaded
    override val liveModelInMemory: SpeechModelId?
        get() = liveRecognizer.model

    /**
     * The live recognizer of [model], loaded now if it isn't (LiveRecognizer locks the load), on IO.
     * Inside the decode lock: a recognizer of another model is freed first, which a decode of a
     * capture that a newer one cut short could still be using.
     */
    private suspend fun liveRecognizerOf(model: SpeechModelId): OfflineRecognizer =
        decodeMutex.withLock { withContext(Dispatchers.IO) { liveRecognizer.get(model) } }

    /**
     * Replaces the VAD with a new one from [vadFactory], detecting with [parameters], and returns
     * it. The previous one is released, unless [releasePrevious] is false: a recording cut short
     * by a new one may still be reading it, and a native object freed under a running read
     * crashes, whereas an unreleased one is only freed when it is garbage collected.
     */
    private fun renewVad(parameters: VadParameters, releasePrevious: Boolean): Vad {
        val previous = activeVad
        val next = vadFactory.create(parameters)
        activeVad = next
        if (releasePrevious) previous?.release()
        return next
    }

    private companion object {
        private const val TAG = "SherpaOnnxDataSource"
        private const val SAMPLE_RATE_HZ = SpeechRecognition.SAMPLE_RATE_HZ
        private const val BUFFER_SIZE_MULTIPLIER = 4
        private const val INITIAL_BUFFER_CAPACITY = 160000 // ~10s pre-allocated
        // 100 ms of audio: one piece of a file transcribed again, as a microphone read is.
        private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 10
        // How far reading a file may run ahead of its recognition: 1.6 s, a few KB.
        private const val FILE_READ_AHEAD_CHUNKS = 16

        /** The Android input an AudioRecord records [MicSource] from. */
        private val MicSource.audioSource: Int
            get() = when (this) {
                MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                MicSource.MIC -> MediaRecorder.AudioSource.MIC
                MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

        /**
         * How the summary line names an audio effect of [type] ([InputReport.effects]): AEC, AGC
         * and NS for the echo cancelling, automatic gain control and noise suppression Android
         * defines, which the input for calls is meant to have; the type itself for any other.
         */
        private fun effectName(type: UUID?): String = when (type) {
            AudioEffect.EFFECT_TYPE_AEC -> "AEC"
            AudioEffect.EFFECT_TYPE_AGC -> "AGC"
            AudioEffect.EFFECT_TYPE_NS -> "NS"
            else -> type.toString()
        }
    }

    /**
     * Pre-allocated growable float buffer. Avoids Float boxing overhead
     * from ArrayList<Float> which causes GC pressure and latency spikes.
     *
     * The processing loop trims it after every segment and, with [trimBefore], while nobody
     * speaks, so it holds at most one segment (which the forced split caps) and a few seconds.
     */
    internal class SampleBuffer(initialCapacity: Int = INITIAL_BUFFER_CAPACITY) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(samples: FloatArray) {
            ensureCapacity(size + samples.size)
            samples.copyInto(data, size)
            size += samples.size
        }

        fun getRange(from: Int, to: Int): FloatArray = data.copyOfRange(from, to)

        fun clear() {
            size = 0
        }

        /**
         * Keep only the last [samplesToKeep] samples, shifting them to the start.
         * Used to carry over audio context across VAD segment boundaries.
         */
        fun keepTail(samplesToKeep: Int) {
            val toKeep = samplesToKeep.coerceAtMost(size)
            if (toKeep <= 0) {
                size = 0
                return
            }
            data.copyInto(data, 0, size - toKeep, size)
            size = toKeep
        }

        /**
         * Bounds the buffer while nobody speaks. Once [position], how far the VAD has read, is
         * more than twice [keep] samples in, drops all but the last [keep] samples before it
         * and shifts the rest to the start; the samples from [position] on, which the VAD has
         * yet to read, all stay. Returns where [position] is afterwards. Waiting for twice
         * [keep] means the copy runs only once every [keep] samples.
         */
        fun trimBefore(position: Int, keep: Int): Int {
            if (position <= 2 * keep) return position
            val drop = position - keep
            data.copyInto(data, 0, drop, size)
            size -= drop
            return keep
        }

        private fun ensureCapacity(minCapacity: Int) {
            if (minCapacity > data.size) {
                data = data.copyOf(maxOf(minCapacity, data.size * 2))
            }
        }
    }

    /**
     * A run of microphone reads that delivered no audio: an error code, or no data. A dead
     * AudioRecord (ERROR_DEAD_OBJECT, which this app has seen after the CPU slept mid-recording
     * and which an audioserver restart also leaves) can return its error at once, so without
     * this the capture loop would spin and flood the field log. The loop waits [RETRY_DELAY_MS]
     * after each failed read, warns at most once per [WARN_EVERY_MS], and ends the capture once
     * reads have failed for [GIVE_UP_AFTER_MS] in a row ([isSustained]); an outage shorter than
     * that only costs the audio it lasted.
     */
    internal class ReadFailures {
        private var failingSinceMs: Long? = null
        private var lastWarningMs: Long? = null
        private var unreported = 0

        /** How long reads had failed in a row at the last failed one; 0 after one with audio. */
        var failingForMs = 0L
            private set

        /** Reads have failed for [GIVE_UP_AFTER_MS] in a row: the microphone is gone. */
        val isSustained: Boolean
            get() = failingForMs >= GIVE_UP_AFTER_MS

        /**
         * Why the capture ends once reads have failed for [GIVE_UP_AFTER_MS] in a row
         * ([isSustained]), or null before then: the microphone is gone. The recorder tells that
         * from every other failure of the stream (a lost microphone left nothing untranscribed),
         * so it is always a [MicrophoneLostException]. [ret] is what the last read returned.
         */
        fun lostMicrophone(ret: Int?): MicrophoneLostException? =
            if (isSustained) MicrophoneLostException("No audio from the microphone for $failingForMs ms (read returned $ret)") else null

        /** A read delivered audio, which ends the run. */
        fun onSuccess() {
            failingSinceMs = null
            failingForMs = 0
        }

        /**
         * Counts a failed read at [nowMs], on a monotonic clock. Returns how many failed reads
         * to report in a warning now, this one included, or 0 while the last warning is less
         * than [WARN_EVERY_MS] old.
         */
        fun onFailure(nowMs: Long): Int {
            val since = failingSinceMs ?: nowMs
            failingSinceMs = since
            failingForMs = nowMs - since
            unreported++
            val last = lastWarningMs
            if (last != null && nowMs - last < WARN_EVERY_MS) return 0
            lastWarningMs = nowMs
            return unreported.also { unreported = 0 }
        }

        companion object {
            /** The wait after a failed read: one read's worth of audio. */
            const val RETRY_DELAY_MS = 100L
            const val WARN_EVERY_MS = 1_000L
            const val GIVE_UP_AFTER_MS = 5_000L
        }
    }

    // Tracks whether we successfully activated Bluetooth SCO for this recording session.
    // Used to tear it down symmetrically in stopRecording(), which runs on another thread.
    @Volatile private var bluetoothScoActive = false

    /**
     * Attempts to activate Bluetooth SCO so Android routes the BT headset mic as the
     * audio input. If no BT device is connected, or if the SCO handshake times out,
     * the method returns false and recording proceeds with the built-in device mic.
     *
     * Must be called on a coroutine (suspends up to [BLUETOOTH_SCO_CONNECT_TIMEOUT_MS]).
     */
    /**
     * Routes audio input to a connected Bluetooth HFP/HSP headset using the modern
     * setCommunicationDevice() API (available since API 31, replacing the deprecated
     * startBluetoothSco()). Searches availableCommunicationDevices for a BT headset
     * input device and activates it synchronously — no broadcast receiver needed.
     *
     * Falls back to the built-in device mic silently if no BT headset is available.
     *
     * @return true if a BT headset was selected, false if falling back to device mic.
     */
    private fun activateBluetoothSco(): Boolean {
        val btDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (btDevice == null) {
            Log.d(TAG, "No Bluetooth headset available — using device mic")
            return false
        }
        val success = audioManager.setCommunicationDevice(btDevice)
        return if (success) {
            // Not the device's name: people name headsets after themselves, and the field log keeps this.
            Log.i(TAG, "Bluetooth communication device set (type=${btDevice.type})")
            true
        } else {
            Log.w(TAG, "setCommunicationDevice() failed — using device mic")
            false
        }
    }

    /**
     * Clears the communication device previously set by [activateBluetoothSco].
     * Safe to call even if activation was never performed.
     */
    private fun deactivateBluetoothSco() {
        if (bluetoothScoActive) {
            audioManager.clearCommunicationDevice()
            bluetoothScoActive = false
            Log.i(TAG, "Bluetooth communication device cleared")
        }
    }

    // The running capture, or the last one: what stopRecording() stops. It is called from the
    // ViewModel/main thread while the capture reads on Dispatchers.IO. Each capture has its own
    // microphone (LiveCapture), so one cut short by a newer one can't keep it open.
    @Volatile private var currentCapture: LiveCapture? = null

    // Which microphone input each capture opens; it also knows whether a fallback was reported.
    private val micInput = MicInput()

    // The version of the sherpa-onnx library, for the summary lines; "?" if it can't tell.
    private val sherpaOnnxVersion: String by lazy { runCatching { VersionInfo.version }.getOrDefault("?") }

    // What the recognition of the running capture, or the last one, knows of speech its lines may
    // lack (mayHaveHeardSpeech). Replaced as each capture starts.
    @Volatile private var captureEvidence: SpeechEvidence? = null

    // Diagnostic metrics
    private var metrics = AudioMetrics()

    // Where each capture's audio comes from: a debug build's WAV feed, or an AudioRecord.
    private val device = object : CaptureDevice {
        override fun takeFeed(): ShortArray? = DebugAudioFeed.take(context)

        override fun open(source: MicSource, refusals: MutableList<String>, preferred: MicSwitches.Device): Microphone? =
            startMicrophone(source, refusals, preferred)?.let(::RecordingMicrophone)
    }

    // How the phone is folded, for each capture's lines in the field log.
    private val fold = PhoneFold(context)

    // A debug build reads the microphone's debug switches at each capture start; a release build never.
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override suspend fun initialize(): Result<Unit> = runCatching {
        // Eagerly create the recognizer of English and German recordings so any model-not-found
        // error surfaces here rather than mid-recording. If it is already loaded (re-use without
        // release) the existing one is kept. The VAD is built when the recording starts, not here.
        // On IO: loading the ~670 MB ONNX model from disk would block the main thread otherwise
        // (Choreographer: Skipped 228 frames).
        liveRecognizerOf(SpeechModelId.LIVE)
    }

    /**
     * Records from the microphone, or from a [DebugAudioFeed] file in a debug build, and emits
     * partial and complete transcriptions until [stopRecording], microphone first ([LiveCapture]):
     * the microphone opens as the capture starts, and the live speech model and a new VAD load only
     * once it is open, while the audio waits in a queue that the recognition catches up on. So what
     * is said right after the press is in the transcript, however long the load takes.
     *
     * - A Stop while the settings are read ends the capture before the microphone opens. One while
     *   the speech model loads closes the microphone, and what it recorded until then is still
     *   transcribed and emitted before the flow completes.
     * - The microphone input is the one the settings name, or the general one when that doesn't
     *   open ([MicInput]); when neither opens, nothing loads and the flow fails with a
     *   [MicrophoneLostException]. A microphone that delivers no audio for
     *   [ReadFailures.GIVE_UP_AFTER_MS] (a dead AudioRecord) ends the capture: what it delivered
     *   until then is still transcribed and emitted, then the flow fails with a
     *   [MicrophoneLostException] too.
     * - When the speech model or the VAD fails to load, the microphone and the audio file are
     *   closed, the audio keeps what was recorded, untranscribed, and the flow fails with a
     *   [com.satori.domain.repository.SpeechModelLoadException].
     *
     * With [audio], every chunk handed to the recognizer is also handed to a
     * [com.satori.data.audio.CaptureAudioWriter], which never blocks, and each complete
     * utterance is marked in its index under the result's id. The writer starts with the
     * microphone (or the feed), so the run's clock is that of the first sample, and is finished
     * when the flow ends; the flow waits a moment for it, so the file is complete by the time the
     * recording is.
     *
     * The capture moves [captureState] as it goes, and back to IDLE however its flow ends (see
     * [CaptureStates]).
     *
     * It transcribes with the live model of [language] as the model loads, and between two
     * utterances it changes to the model of the language switched to while it records: the model in
     * memory is freed and the other one loaded, while the microphone records on (see [LiveCapture]).
     */
    override fun startRecording(audio: RecordingAudioFiles?, language: CaptureLanguage): Flow<RecognitionResult> = channelFlow {
        val progress = captureStates.begin()
        try {
            runCapture(audio, language, progress)
        } finally {
            progress.end()
        }
    }

    /** The capture of [startRecording], which reports how far it has come to [progress]. */
    private suspend fun ProducerScope<RecognitionResult>.runCapture(
        audio: RecordingAudioFiles?,
        language: CaptureLanguage,
        progress: CaptureStates.Progress
    ) {
        // First, so a capture that fails to start never reports what the one before it heard.
        val evidence = SpeechEvidence().also { captureEvidence = it }
        // Ensure only one recording session at a time
        val interrupted = currentCapture?.isStopped == false
        if (interrupted) {
            // Force cleanup from previous session (defensive recovery)
            Log.w(TAG, "Recording already in progress - forcing cleanup from previous session")
            stopRecording()
        }
        // The version is looked at only as the capture ends, on IO: this runs on the main thread,
        // and the first look loads the native library.
        val capture = LiveCapture(device, micInput, progress, evidence, _audioLevel, library = { sherpaOnnxVersion }, posture = fold)
        currentCapture = capture

        // The microphone input and the voice detection are read from the settings as they are
        // now, so a change in Settings applies from this recording; so are a debug build's
        // switches of the microphone.
        val (settings, switches) = withContext(Dispatchers.IO) {
            settingsRepository.getSettings().first() to
                MicSwitches.forCapture(debuggable, read = SystemProperty::read, warn = { Log.w(TAG, it) })
        }
        val vadParameters = VadParameters.of(VadProfile.LIVE, settings)
        // Stop came while the settings were read: the recording ends here, before the headset is
        // made the communication device, which nothing would clear again, and before the
        // microphone opens for nothing.
        if (capture.isStopped) {
            Log.i(TAG, "Recording stopped before the microphone opened")
            return
        }

        // Route audio input to BT headset if available; falls back to device mic silently.
        bluetoothScoActive = activateBluetoothSco()

        // Reset metrics for new session
        metrics = AudioMetrics()

        Log.i(TAG, "Starting recording session (bluetoothSco=$bluetoothScoActive)")
        Log.i(TAG, "Device: ${metrics.deviceManufacturer} ${metrics.deviceModel}")
        Log.i(TAG, "CPU: ${metrics.cpuCores} cores, ${metrics.cpuArchitecture}")

        capture.run(
            micSource = settings.micSource,
            vad = vadParameters,
            speech = liveSpeech(vadParameters, releasePreviousVad = !interrupted, language, capture, evidence, keepsAudio = audio != null),
            audio = audio,
            metrics = metrics,
            // That Stop may have come before the headset was routed above. A newer capture routes
            // it for itself, so then it is left as it is.
            onStoppedWhileOpening = { if (currentCapture === capture) deactivateBluetoothSco() },
            switches = switches,
            emit = { send(it) }
        )
    }

    /**
     * The live speech model of [language] and a new VAD, detecting with [vadParameters], for one
     * capture ([LiveCapture] loads them once its microphone is open).
     *
     * The speech model loads if none is in memory (Stop released it with releaseModel()), or
     * another language's is: without it every decode goes through decodeShared(), finds no
     * recognizer and silently produces no output, the VAD detecting speech but decode always
     * returning null. A new VAD has no segments left over from the previous session, which would
     * make isSpeechDetected() malfunction silently, and no RNN state to reset; the capture keeps it
     * to itself, so one that interrupts it can't feed the same VAD. The one before it is released
     * unless [releasePreviousVad] is false: the capture it belonged to was cut short by this one and
     * may still read it ([renewVad]).
     *
     * Between two utterances [capture] changes the model when [language] asks for another one, while
     * it is the newest capture ([CaptureSpeechModel]). It decodes only with its own model: one that a
     * newer capture in another language cut short finds that capture's model in memory for the
     * words it still has to transcribe, which would come out in the other language's script; they
     * are left untranscribed instead, which its [evidence] notes, and its kept audio still has them.
     *
     * A Stop before the model's native load begins stops its load when the recording [keepsAudio]
     * ([CaptureSpeechModel.load]): the capture ends at once and the finisher transcribes the audio
     * after Stop. A recording that keeps none waits for the model, which alone can transcribe what
     * its queue holds.
     */
    private fun liveSpeech(
        vadParameters: VadParameters,
        releasePreviousVad: Boolean,
        language: CaptureLanguage,
        capture: LiveCapture,
        evidence: SpeechEvidence,
        keepsAudio: Boolean
    ) = object : LiveSpeech {
        private val speechModel = CaptureSpeechModel(
            liveRecognizer,
            language,
            decodeMutex,
            isNewest = { currentCapture === capture },
            stopsAtStop = { keepsAudio && capture.isStopped }
        )

        override suspend fun load(): VoiceDetector {
            speechModel.load() // loads the model unless it is in memory
            return SherpaVoiceDetector(renewVad(vadParameters, releasePreviousVad))
        }

        override suspend fun decode(samples: FloatArray, speed: DecodeSpeed?): String? =
            decodeShared(samples, speed, ownModel = speechModel.model(), evidence = evidence)

        override fun hintPerformance() = this@SherpaOnnxDataSource.hintPerformance()

        override fun model(): SpeechModelId = speechModel.model()

        override fun switchDue(): SpeechModelId? = speechModel.switchDue()

        override suspend fun switchTo(model: SpeechModelId): Boolean = speechModel.switchTo(model)
    }

    /**
     * A started AudioRecord as a capture's [Microphone]. What the phone does with its input comes
     * from the recording configuration the audio server keeps for it, which lists the effects on
     * the capture stream once it has registered the recording, and from its active microphones;
     * the device it records from from its routing, whose changes come on the main thread.
     */
    private class RecordingMicrophone(private val record: AudioRecord) : Microphone {
        @Volatile private var routing: AudioRouting.OnRoutingChangedListener? = null

        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

        override fun close() {
            routing?.let { listener -> runCatching { record.removeOnRoutingChangedListener(listener) } }
            record.stop()
            record.release()
        }

        override fun report(): InputReport = InputReport(effects = effects(), microphones = microphones())

        override fun channelCount(): Int = record.channelCount

        // Null also once Stop released the record, as the field log only says what is known.
        override fun routedDevice(): String? = runCatching { record.routedDevice?.let(::deviceName) }.getOrNull()

        override fun watchRoute(onChange: (String?) -> Unit) {
            val listener = AudioRouting.OnRoutingChangedListener { router -> onChange(router.routedDevice?.let(::deviceName)) }
            routing = listener
            runCatching { record.addOnRoutingChangedListener(listener, null) }
        }

        // Null until the audio server lists the recording. Each half says nothing when its call
        // fails, as both do on a record that Stop released meanwhile, so the other still counts.
        private fun effects(): List<String>? =
            try {
                record.activeRecordingConfiguration?.effects?.map { effectName(it.type) }
            } catch (e: RuntimeException) {
                null
            }

        // Throws IOException as declared, and IllegalStateException once the record is released.
        private fun microphones(): Int? =
            try {
                record.activeMicrophones.size
            } catch (e: Exception) {
                null
            }
    }

    override fun mayHaveHeardSpeech(): Boolean = captureEvidence?.mayHaveHeardSpeech == true

    /**
     * Hints the Android scheduler that the calling thread is performance-sensitive.
     * On big.LITTLE SoCs (Snapdragon, Dimensity, Exynos) without this hint
     * the scheduler may park the ASR thread on efficiency cores, causing
     * inference latency spikes. The 50ms target matches the 100ms audio read
     * cycle minus VAD overhead, keeping partial results timely.
     * minSdk = 31 guarantees PerformanceHintManager is always available (API 31+).
     * The session stays active for the lifetime of the calling block.
     */
    private fun hintPerformance() {
        val hintManager = context.getSystemService(PerformanceHintManager::class.java)
        val targetDurationNs = 50_000_000L // 50ms per VAD+ASR cycle
        hintManager?.createHintSession(intArrayOf(Process.myTid()), targetDurationNs)
    }

    /**
     * Decodes with the recording's shared recognizer; null once it has been released (Stop
     * releases it after the last words are in, see releaseModel), and null while it is another
     * model's than [ownModel], the capture's own (a newer capture in another language has it). A
     * decode that comes then leaves its audio untranscribed, which the capture's [evidence] notes.
     * With [speed], the decode is timed there, inside the lock, so the wait for a partial result's
     * decode isn't counted, and a complete utterance that finds no recognizer of its model is
     * counted as lost: the recorder stopped waiting for it before it came.
     *
     * Holds decodeMutex for the entire stream lifecycle: createStream() accesses the recognizer's
     * shared ONNX session just like decode(), so releaseModel() must not free the native heap
     * between the two, and two decode() calls must never overlap (see decodeMutex).
     */
    private suspend fun decodeShared(
        samples: FloatArray,
        speed: DecodeSpeed? = null,
        ownModel: SpeechModelId? = null,
        evidence: SpeechEvidence? = captureEvidence
    ): String? = decodeMutex.withLock {
        val r = liveRecognizer.loadedOrNull(ownModel)
        if (r == null) {
            evidence?.audioDropped()
            speed?.recordLost()
            return@withLock null
        }
        if (speed == null) decodeWith(r, samples) else speed.timed(samples.size) { decodeWith(r, samples) }
    }

    /**
     * One stretch of audio through [recognizer]: its text, or null for none. A model that is told
     * the language of each stream (Cohere Transcribe) is told [language].
     */
    private fun decodeWith(recognizer: OfflineRecognizer, samples: FloatArray, language: String? = null): String? {
        val stream = recognizer.createStream()
        try {
            if (language != null) stream.setOption("language", language)
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim().takeIf { it.isNotBlank() }
        } finally {
            stream.release()
        }
    }

    /**
     * Runs [wav] through [SpeechRecognition] as a recording would be, without partial results and
     * without real-time pacing: the file is read 100 ms at a time, at most
     * [FILE_READ_AHEAD_CHUNKS] ahead of the recognition, so memory stays flat however long it is.
     *
     * The recognizer and the VAD are new instances ([model] with the threads of
     * [RecognizerUse.FILE], and the voice detection values for files, [VadParameters.FILE]),
     * released when it ends, also on a cancel: the recording's own [liveRecognizer] is never
     * used, so a recorder's Stop can't pull the model out from under it, and releasing this one
     * never takes a recorder's. When it returns or throws, no decode runs any more: the
     * recognition, and with it the decoding, finishes before the coroutine does.
     */
    override suspend fun transcribeFile(
        wav: File,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<RecognitionResult> {
        val dataBytes = withContext(Dispatchers.IO) { Wav.dataBytes(wav) }
            ?: throw IOException("Not a recording's audio file")
        // Only a model that is told the language hears it; the others find it or know only one.
        val streamLanguage = language?.takeIf { model.languagePerStream }
        // The load waits its turn behind the one-load gate and reads its files in first at the
        // speech models' pace, and stops there when this is cancelled meanwhile, before the model
        // takes its memory.
        val job = currentCoroutineContext().job
        // Released also when a cancel comes during the load (withLoaded), which takes seconds.
        return withLoaded(
            Dispatchers.IO,
            load = {
                memoryLines.measured(ModelMemoryLines.Event.LOAD, model.id, peak = true) {
                    recognizers.create(model, RecognizerUse.FILE, streamLanguage, cancelled = { !job.isActive })
                }
            },
            release = { own -> speechModelReleased(memoryLines, releasePurge, model) { own.release() } }
        ) { ownRecognizer ->
            withLoaded(Dispatchers.IO, load = { vadFactory.create(VadParameters.FILE) }, release = Vad::release) { ownVad ->
                val speed = DecodeSpeed()
                val level = SpeechLevel()
                var error: Throwable? = null
                try {
                    val started = SystemClock.elapsedRealtime()
                    val results = mutableListOf<RecognitionResult>()
                    withContext(Dispatchers.Default) {
                        hintPerformance()
                        coroutineScope {
                            val samples = Channel<FloatArray>(FILE_READ_AHEAD_CHUNKS)
                            launch(Dispatchers.IO) { readSamples(wav, dataBytes, samples, onProgress) }
                            SpeechRecognition(
                                detector = LevelMeteringDetector(SherpaVoiceDetector(ownVad), level),
                                decode = { audio ->
                                    speed.timed(audio.size) { decodeWith(ownRecognizer, audio, streamLanguage) }
                                },
                                partials = false,
                                strict = true
                            ).run(samples) { results += it }
                        }
                    }
                    Log.i(TAG, "Transcribed ${Wav.durationMs(dataBytes)} ms of audio again with ${model.id} in " +
                            "${SystemClock.elapsedRealtime() - started} ms: ${results.size} utterances")
                    results
                } catch (e: Throwable) {
                    error = e
                    throw e
                } finally {
                    Log.i(TAG, speechSummary(
                        model.id, VadParameters.FILE, SummaryInput.FILE, InputReport.NONE, CaptureSetup.NONE, level, speed,
                        sherpaOnnxVersion, RunEnd.of(error)
                    ))
                }
            }
        }
    }

    /**
     * Sends the [dataBytes] of samples of [wav] to [into] 100 ms at a time, then closes it.
     * [onProgress] hears of each whole percent read.
     */
    private suspend fun readSamples(wav: File, dataBytes: Long, into: SendChannel<FloatArray>, onProgress: (Float) -> Unit) {
        DataInputStream(wav.inputStream().buffered()).use { input ->
            input.readFully(ByteArray(Wav.HEADER_BYTES))
            val bytes = ByteArray(CHUNK_SAMPLES * 2)
            var read = 0L
            var percent = 0
            while (read < dataBytes) {
                val size = minOf(bytes.size.toLong(), dataBytes - read).toInt()
                input.readFully(bytes, 0, size)
                into.send(Wav.toFloats(bytes, size / 2))
                read += size
                val now = (read * 100 / dataBytes).toInt()
                if (now != percent) {
                    percent = now
                    onProgress(now / 100f)
                }
            }
        }
        into.close()
    }

    /**
     * Opens [source] and starts recording from it, or returns null when the phone can't, with the
     * reason added to [refusals]: the AudioRecord can't be made, isn't initialised, or doesn't
     * start, which only its state then says. One that was made is released again. Its buffer is
     * four times the smallest the phone allows, so a read that comes late loses no audio. With a
     * [preferred] built-in microphone (a debug switch) it asks Android for that one first.
     */
    @SuppressLint("MissingPermission")
    private fun startMicrophone(source: MicSource, refusals: MutableList<String>, preferred: MicSwitches.Device): AudioRecord? {
        val numBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSizeBytes = numBytes * BUFFER_SIZE_MULTIPLIER
        Log.i(TAG, "Audio config: minBufferSize=${numBytes}bytes, actualBuffer=${bufferSizeBytes}bytes (${BUFFER_SIZE_MULTIPLIER}x)")
        val record = try {
            AudioRecord(source.audioSource, SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeBytes)
        } catch (e: Exception) {
            refusals += "$source: ${e.javaClass.simpleName} ${e.message}"
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            refusals += "$source: not initialised"
            record.release()
            return null
        }
        preferred.address?.let { address -> preferBuiltInMicrophone(record, address) }
        val started = try {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (e: IllegalStateException) {
            false
        }
        if (!started) {
            refusals += "$source: did not start"
            record.release()
            return null
        }
        return record
    }

    /**
     * Asks Android to record [record] from the built-in microphone at [address] ("bottom",
     * "back"), one of the inputs AudioManager lists. One the phone doesn't list, or a refusal,
     * leaves the routing to Android, and the field log says so with the built-in ones there are.
     */
    private fun preferBuiltInMicrophone(record: AudioRecord, address: String) {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtIn = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val wanted = builtIn.firstOrNull { it.address == address }
        if (wanted == null) {
            val there = builtIn.joinToString { deviceName(it) }.ifEmpty { "none" }
            Log.w(TAG, "No built-in microphone at $address (built-in: $there); Android routes the input")
            return
        }
        if (record.setPreferredDevice(wanted)) {
            Log.i(TAG, "Microphone asked for: ${deviceName(wanted)}")
        } else {
            Log.w(TAG, "Android refused the microphone ${deviceName(wanted)}; it routes the input")
        }
    }

    override suspend fun stopRecording() {
        val capture = currentCapture
        capture?.stop()
        _audioLevel.value = 0f
        deactivateBluetoothSco()

        // Stop came before the capture recorded anything; it logs that itself.
        val startedAt = capture?.startedAtMs ?: 0L
        if (startedAt == 0L) return
        val recordingDuration = (System.currentTimeMillis() - startedAt) / 1000.0
        Log.i(TAG, "Recording stopped after ${recordingDuration}s")

        // Calculate and log audio drop rate
        val dropRate = metrics.calculateAudioDropRate(recordingDuration)
        if (dropRate > 0.1) {
            Log.w(TAG, "⚠️ Audio drop rate: ${"%.2f".format(dropRate)}% (samples lost!)")
        }
    }

    override suspend fun releaseModel() {
        // Whichever live model is in memory, of the recording's language.
        // Acquire decodeMutex before releasing the native recognizer to prevent use-after-free.
        // JNI decode() calls cannot be cancelled cooperatively by coroutine cancellation:
        // once inside native code the JNI frame runs to completion regardless. If release()
        // frees the ONNX native heap while a decode() JNI call is still executing, the decode
        // reads freed memory → SIGSEGV (fault addr 0x0/0x8 in libonnxruntime decode+128).
        // Holding the mutex here guarantees release() only runs when no decode() is active,
        // which aligns model teardown with the Stop button click as the synchronization point.
        //
        // The Vad is NOT released here: the processing loop of the recording that just stopped
        // may still be reading it. startRecording() releases it when the next recording
        // replaces it with a new one. Its memory footprint is negligible until then.
        //
        // On IO: Stop calls this from the main thread.
        liveRecognizer.releaseWhenIdle(decodeMutex)
        Log.i(TAG, "STT model released from native heap")
    }
}

/**
 * How the field log names an audio device: its type ("builtin_mic", "wired_headset", "bt_sco",
 * ...), and for a built-in microphone its address as well ("builtin_mic/back"). Other devices'
 * addresses are left out: a Bluetooth headset's is its hardware address.
 */
internal fun deviceName(device: AudioDeviceInfo): String {
    val type = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
        AudioDeviceInfo.TYPE_FM_TUNER -> "fm_tuner"
        else -> "type${device.type}"
    }
    return if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && device.address.isNotEmpty()) "$type/${device.address}" else type
}
