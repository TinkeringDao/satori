package com.satori.data.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.PauseReason
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Action constants duplicated here to avoid a direct dependency on the :app module.
// The :data module must not reference app-layer classes such as ModelDownloadService.
private const val MODEL_DOWNLOAD_SERVICE_CLASS =
    "com.satori.service.ModelDownloadService"
private const val ACTION_START_DOWNLOAD = "com.satori.action.START_DOWNLOAD"
private const val ACTION_STOP_DOWNLOAD = "com.satori.action.STOP_DOWNLOAD"

/**
 * Manager for model downloads.
 *
 * Both STT and LLM downloads use ModelDownloadManager (HttpURLConnection) directly:
 * - No Android DownloadManager API is used for new downloads, avoiding the
 *   MediaProvider restriction that blocks writes to private app directories on
 *   Android 12+ ("Inserting private file … is not allowed").
 * - ModelDownloadManager preserves .partial files on any failure so that every
 *   retry resumes byte-accurately instead of restarting from zero.
 *
 * Legacy Android DownloadManager entries (from previous app versions) are cleaned
 * up in resumePendingDownloads() without starting new DM downloads.
 *
 * A download that stops because it was cancelled (paused) leaves its state to whoever cancelled
 * it; only a real failure becomes [DownloadState.Error], with the [DownloadFailure] behind it.
 * A finished AI model download becomes the model in use before the state says Completed. While
 * a speech model's file is checked, its state is [DownloadState.Verifying].
 *
 * Every model download waits while a recorder starts, records or saves ([holdForRecording]): one
 * that runs stops at once, its state [DownloadState.Paused] for [PauseReason.RECORDING], and goes
 * on once the recording is saved, as does one asked for meanwhile. A download that starts again
 * waits for the one before it in its place to have ended, so two never write the same file. There
 * are three places: one speech model at a time, one AI model, and the reader (0.8.0).
 */
@Singleton
class AndroidDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadStateManager: DownloadStateManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AndroidDownloadManager"

        // SharedPreferences keys kept only for cleaning up legacy DM entries
        private const val PREFS_NAME = "download_manager_prefs"
        private const val KEY_STT_DOWNLOAD_IDS = "stt_download_ids"
        private const val KEY_STT_CURRENT_FILE_INDEX = "stt_current_file_index"
        private const val KEY_LLM_DOWNLOAD_ID = "llm_download_id"
        private const val NO_DOWNLOAD_ID = -1L
    }

    // Kept only to cancel/clean up any legacy DM downloads on first run after update; asked for
    // only when there is one.
    private val downloadManager by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var sttProgressJob: Job? = null
    private var llmProgressJob: Job? = null
    private var readerProgressJob: Job? = null
    private var vocabularyJob: Job? = null

    // Starts, stops and the recorder's gate change the jobs and states below together.
    private val lock = Any()

    // The recorder's gate, and the downloads it holds until the recording is saved.
    private var heldForRecording = false
    private var heldSpeech: SpeechModelId? = null
    private var heldLlm: LlmModelVariant? = null
    private var heldReader: ReaderModelId? = null

    // Tracks a legacy DM download ID so resumeLlmDownload() can remove it before renaming
    private var llmDownloadId: Long? = null

    init {
        resumePendingDownloads()
    }

    // ── Foreground-service helpers ───────────────────────────────────────────

    /**
     * Starts [ModelDownloadService] as a foreground service so downloads survive
     * the app being backgrounded.
     *
     * Uses reflection-free string-based intent to avoid a circular :data → :app
     * dependency; the service class name is a stable internal constant. Android refuses it to an
     * app in the background (a retry after the network came back, say) and once the day's time
     * for data sync services is used up: the download then goes on without it, for as long as
     * Android lets the app run, and the service comes back when the app is opened
     * ([keepInForeground]).
     */
    private fun startDownloadService() {
        try {
            val serviceClass = Class.forName(MODEL_DOWNLOAD_SERVICE_CLASS)
            val intent = Intent(context, serviceClass).apply {
                action = ACTION_START_DOWNLOAD
            }
            context.startForegroundService(intent)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "ModelDownloadService not found — foreground protection unavailable", e)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one.
            Log.w(TAG, "Download service refused (${e.javaClass.simpleName}), downloading without it")
        }
    }

    /**
     * Stops [ModelDownloadService] when no download is active.
     * Safe to call even if the service is already stopped.
     */
    private fun stopDownloadService() {
        try {
            val serviceClass = Class.forName(MODEL_DOWNLOAD_SERVICE_CLASS)
            val intent = Intent(context, serviceClass).apply {
                action = ACTION_STOP_DOWNLOAD
            }
            context.startService(intent)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "ModelDownloadService not found — cannot send stop intent", e)
        }
    }

    /**
     * Starts the download service again for a download that goes on without it, after Android
     * refused it in the background or took it away for the day. Call it while the app is in
     * front, where Android allows it.
     */
    fun keepInForeground() {
        val running = listOf(
            downloadStateManager.sttDownloadState.value,
            downloadStateManager.llmDownloadState.value,
            downloadStateManager.readerDownloadState.value
        ).any(DownloadServiceRules::keepsServiceRunning)
        if (running) startDownloadService()
    }

    /**
     * Cancel any legacy Android DownloadManager entries persisted by a previous app version.
     */
    private fun resumePendingDownloads() {
        // Clean up legacy STT DM entry
        val savedSttId = prefs.getLong(KEY_STT_DOWNLOAD_IDS, NO_DOWNLOAD_ID)
        if (savedSttId != NO_DOWNLOAD_ID) {
            Log.i(TAG, "Removing legacy STT DM entry id=$savedSttId")
            downloadManager.remove(savedSttId)
            prefs.edit().remove(KEY_STT_DOWNLOAD_IDS).remove(KEY_STT_CURRENT_FILE_INDEX).apply()
        }

        // Clean up legacy LLM DM entry; remember the ID so resumeLlmDownload() can use it
        val savedLlmId = prefs.getLong(KEY_LLM_DOWNLOAD_ID, NO_DOWNLOAD_ID)
        if (savedLlmId != NO_DOWNLOAD_ID) {
            Log.i(TAG, "Found legacy LLM DM entry id=$savedLlmId")
            llmDownloadId = savedLlmId
            prefs.edit().remove(KEY_LLM_DOWNLOAD_ID).apply()
        }

        // An unfinished .partial LLM file is not turned into a download state here: a paused or
        // interrupted download is a fact about the file, which the Advanced settings read
        // (ModelDownloadManager.llmPartialBytes), and starting the model again continues it.
    }

    // ── The recorder's gate ─────────────────────────────────────────────────

    /**
     * Holds every model download while a recorder starts, records or saves ([busy]), so none
     * competes with a live capture: a download that runs stops within a read, what it fetched
     * stays, and its state says [PauseReason.RECORDING]; one asked for meanwhile waits too. Once
     * not [busy], each goes on from where it stopped. The 117 KB vocabulary isn't held.
     */
    fun holdForRecording(busy: Boolean) = synchronized(lock) {
        if (busy == heldForRecording) return@synchronized
        heldForRecording = busy
        if (busy) {
            if (DownloadServiceRules.isRunning(downloadStateManager.sttDownloadState.value)) {
                heldSpeech = downloadStateManager.sttModel.value
                sttProgressJob?.cancel()
                downloadStateManager.updateSttState(heldState(downloadStateManager.sttDownloadState.value))
            }
            if (DownloadServiceRules.isRunning(downloadStateManager.llmDownloadState.value)) {
                heldLlm = downloadStateManager.llmVariant.value
                llmProgressJob?.cancel()
                downloadStateManager.updateLlmState(heldState(downloadStateManager.llmDownloadState.value))
            }
            if (DownloadServiceRules.isRunning(downloadStateManager.readerDownloadState.value)) {
                heldReader = downloadStateManager.readerModel.value
                readerProgressJob?.cancel()
                downloadStateManager.updateReaderState(heldState(downloadStateManager.readerDownloadState.value))
            }
            Log.i(
                TAG,
                "Downloads held while recording: speech ${heldSpeech?.id ?: "none"}, AI ${heldLlm ?: "none"}, " +
                    "reader ${heldReader?.id ?: "none"}"
            )
        } else {
            val speech = heldSpeech
            val llm = heldLlm
            val reader = heldReader
            heldSpeech = null
            heldLlm = null
            heldReader = null
            Log.i(
                TAG,
                "Downloads go on after the recording: speech ${speech?.id ?: "none"}, AI ${llm ?: "none"}, reader ${reader?.id ?: "none"}"
            )
            speech?.let { launchStt(it) }
            llm?.let { launchLlm(it) }
            reader?.let { launchReader(it) }
        }
    }

    /**
     * Writes a running download's progress, unless it was stopped or held meanwhile: the stop and
     * the hold cancel the job under the same lock, so a progress that was on its way when they came
     * never undoes them.
     */
    private suspend fun report(write: () -> Unit) {
        val context = currentCoroutineContext()
        synchronized(lock) { if (context.isActive) write() }
    }

    /** [state] as the gate holds it: paused for the recording, where it stood. */
    private fun heldState(state: DownloadState): DownloadState =
        DownloadState.Paused(PauseReason.RECORDING, DownloadServiceRules.progressOf(state))

    // ── STT ─────────────────────────────────────────────────────────────────

    /**
     * Start the download of the speech model [model] via ModelDownloadManager (HttpURLConnection),
     * or continue an interrupted one: Parakeet v3, the live model, unless another is named.
     *
     * Downloads its files sequentially with aggregated progress. Uses
     * ModelDownloadManager instead of Android DownloadManager to avoid the
     * MediaProvider private-path restriction on Android 12+. One speech model downloads at a
     * time: starting another stops the one that runs, whose files stay to be continued. While a
     * recorder is busy it only waits ([holdForRecording]).
     */
    fun startSttDownload(model: SpeechModelId = SpeechModelId.LIVE) = synchronized(lock) {
        if (heldForRecording) {
            Log.i(TAG, "STT download of ${model.id} waits for the recording")
            sttProgressJob?.cancel()
            val before = downloadStateManager.sttDownloadState.value.takeIf { downloadStateManager.sttModel.value == model }
            heldSpeech = model
            downloadStateManager.setSttModel(model)
            downloadStateManager.updateSttState(DownloadState.Paused(PauseReason.RECORDING, before?.let(DownloadServiceRules::progressOf)))
            return@synchronized
        }
        launchStt(model)
    }

    private fun launchStt(model: SpeechModelId) {
        Log.i(TAG, "Starting STT download of ${model.id} via ModelDownloadManager")
        val previous = sttProgressJob
        previous?.cancel()

        downloadStateManager.setSttModel(model)
        downloadStateManager.updateSttState(
            DownloadState.Downloading(progress = DownloadProgress(0, 0, 0))
        )

        // Elevate to foreground so the download survives backgrounding.
        startDownloadService()

        sttProgressJob = scope.launch {
            // The download before it in this place writes nothing more once it has ended.
            previous?.join()
            try {
                modelDownloadManager.downloadSttModel(model).collect { p ->
                    val progress = DownloadProgress(p.bytesDownloaded, p.totalBytes, p.percentage)
                    report { downloadStateManager.updateSttState(if (p.verifying) DownloadState.Verifying(progress) else DownloadState.Downloading(progress)) }
                }
                downloadStateManager.updateSttState(DownloadState.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "STT download failed", e)
                downloadStateManager.updateSttState(
                    DownloadState.Error(e.message ?: "Download failed", DownloadFailure.of(e))
                )
            }
            // The service observes DownloadStateManager and stops itself when no
            // download is active; no explicit stop needed here.
        }
    }

    /**
     * Cancel an in-progress STT download. The state goes back to Idle and the files downloaded so
     * far stay, so starting the same model again continues where this one stopped: that is Pause.
     */
    fun cancelSttDownload() = synchronized(lock) {
        sttProgressJob?.cancel()
        heldSpeech = null
        downloadStateManager.resetSttState()
        // State reset above triggers the service observer → service stops itself.
    }

    /**
     * Says that the speech model [model] is held for [reason] where its download's state shows,
     * without running: the first run's queue holds a part this way while it waits for the
     * network, the space or a tap. Ignored while another speech model's download runs.
     */
    fun holdSttDownload(model: SpeechModelId, reason: PauseReason, progress: DownloadProgress?) = synchronized(lock) {
        val current = downloadStateManager.sttModel.value
        if (current != null && current != model && DownloadServiceRules.isRunning(downloadStateManager.sttDownloadState.value)) {
            return@synchronized
        }
        if (current == model) sttProgressJob?.cancel()
        if (heldSpeech == model) heldSpeech = null
        downloadStateManager.setSttModel(model)
        downloadStateManager.updateSttState(DownloadState.Paused(reason, progress))
    }

    /**
     * Downloads the vocabulary for words to recognize, 117 KB beside Parakeet v3. It takes a
     * moment, so it runs without the foreground service, and next to any model download.
     */
    fun startVocabularyDownload() {
        vocabularyJob?.cancel()
        downloadStateManager.updateVocabularyState(DownloadState.Downloading(DownloadProgress(0, 0, 0)))
        vocabularyJob = scope.launch {
            try {
                modelDownloadManager.downloadSpeechVocabulary().collect { p ->
                    downloadStateManager.updateVocabularyState(
                        DownloadState.Downloading(DownloadProgress(p.bytesDownloaded, p.totalBytes, p.percentage))
                    )
                }
                downloadStateManager.updateVocabularyState(DownloadState.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Vocabulary download failed", e)
                downloadStateManager.updateVocabularyState(
                    DownloadState.Error(e.message ?: "Download failed", DownloadFailure.of(e))
                )
            }
        }
    }

    // ── LLM ─────────────────────────────────────────────────────────────────

    /**
     * Start LLM model download for the given [variant] via ModelDownloadManager
     * (HttpURLConnection + Range headers).
     *
     * ModelDownloadManager auto-detects a .partial file from a previous interrupted
     * download and resumes from that byte offset, so this function also covers the
     * "fresh start that silently resumes" case. While a recorder is busy it only waits
     * ([holdForRecording]).
     *
     * @param variant The model variant to download; defaults to Q8_0 for backward compatibility.
     */
    fun startLlmDownload(variant: LlmModelVariant = LlmModelVariant.Q8_0) = synchronized(lock) {
        if (holdLlmForRecording(variant)) return@synchronized
        launchLlm(variant, fromBytes = 0L)
    }

    /**
     * Resume LLM download on retry for the given [variant].
     *
     * Checks for a .partial file (kept by ModelDownloadManager on any failure) and
     * resumes byte-accurately via Range headers. Also handles the legacy .download_*
     * temp file left by the Android DownloadManager in previous app versions,
     * renaming it to .partial before calling DM.remove() so it is not deleted.
     *
     * ModelDownloadManager.downloadLlmModel() detects the .partial automatically,
     * so starting the download here is sufficient for all retry scenarios.
     *
     * @param variant The model variant to resume; defaults to Q8_0 for backward compatibility.
     */
    fun resumeLlmDownload(variant: LlmModelVariant = LlmModelVariant.Q8_0) = synchronized(lock) {
        val config = modelConfigForVariant(variant)
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        val dmTempFile = File(modelsDir, ".download_${config.llmFilename}")
        val partialFile = File(modelsDir, "${config.llmFilename}.partial")

        // Rename DM temp file to .partial BEFORE calling downloadManager.remove(),
        // which would otherwise delete it by the path it stored internally.
        val partialBytes: Long = if (dmTempFile.exists() && dmTempFile.length() > 0) {
            val renamed = dmTempFile.renameTo(partialFile)
            if (!renamed) {
                try { dmTempFile.copyTo(partialFile, overwrite = true) } catch (_: Exception) {}
            }
            if (partialFile.exists()) partialFile.length() else 0L
        } else {
            partialFile.takeIf { it.exists() }?.length() ?: 0L
        }

        // Remove legacy DM entry (temp file already renamed, so remove() cannot delete it)
        llmDownloadId?.let { downloadManager.remove(it) }
        llmDownloadId = null

        if (holdLlmForRecording(variant)) return@synchronized
        if (partialBytes > 0) {
            Log.i(TAG, "Resuming LLM from ${partialBytes / 1_000_000}MB partial file")
        } else {
            Log.i(TAG, "No partial LLM data found, starting fresh (resume-capable on next failure)")
        }
        // The first state already says "Resuming from X MB", before the first progress arrives
        // (only meaningful when partialBytes > 0).
        launchLlm(variant, fromBytes = partialBytes)
    }

    /** While a recorder is busy, [variant]'s download waits for it: true when it does. */
    private fun holdLlmForRecording(variant: LlmModelVariant): Boolean {
        if (!heldForRecording) return false
        Log.i(TAG, "LLM download of $variant waits for the recording")
        llmProgressJob?.cancel()
        val before = downloadStateManager.llmDownloadState.value.takeIf { downloadStateManager.llmVariant.value == variant }
        heldLlm = variant
        downloadStateManager.setLlmVariant(variant)
        downloadStateManager.updateLlmState(DownloadState.Paused(PauseReason.RECORDING, before?.let(DownloadServiceRules::progressOf)))
        return true
    }

    private fun launchLlm(variant: LlmModelVariant) = launchLlm(variant, modelDownloadManager.llmPartialBytes(modelConfigForVariant(variant).llmFilename))

    private fun launchLlm(variant: LlmModelVariant, fromBytes: Long) {
        val config = modelConfigForVariant(variant)
        Log.i(TAG, "Starting LLM download: ${config.llmFilename}")
        val previous = llmProgressJob
        previous?.cancel()

        downloadStateManager.setLlmVariant(variant)
        downloadStateManager.updateLlmState(
            DownloadState.Downloading(progress = DownloadProgress(fromBytes, 0, 0))
        )

        // Elevate to foreground so the download survives backgrounding.
        startDownloadService()

        llmProgressJob = scope.launch {
            // The download before it in this place writes nothing more once it has ended.
            previous?.join()
            try {
                modelDownloadManager.downloadLlmModel(
                    url = config.llmUrl,
                    filename = config.llmFilename,
                    spec = config.llmSpec
                ).collect { p ->
                    report { downloadStateManager.updateLlmState(DownloadState.Downloading(DownloadProgress(p.bytesDownloaded, p.totalBytes, p.percentage))) }
                }
                useDownloaded(variant)
                downloadStateManager.updateLlmState(DownloadState.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LLM download failed, .partial file kept for resume", e)
                downloadStateManager.updateLlmState(
                    DownloadState.Error(e.message ?: "Download failed", DownloadFailure.of(e))
                )
            }
            // The service observes DownloadStateManager and stops itself when no
            // download is active; no explicit stop needed here.
        }
    }

    /**
     * Makes the model that was just downloaded the one in use: its variant and file path go into
     * the settings before the state says Completed, so whoever reacts to Completed reads them. A
     * model that downloaded fine is never reported as failed because the settings did not save.
     * A model that can't write live notes (Gemma 4 E2B) leaves the live model as it is: it is used
     * after Stop once it is on the phone (FinishingModelRepository).
     */
    private suspend fun useDownloaded(variant: LlmModelVariant) {
        if (!variant.writesLiveNotes) return
        val path = modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename) ?: return
        try {
            val current = settingsRepository.getSettings().first()
            settingsRepository.updateSettings(current.copy(llmModelVariant = variant, llmModelPath = path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The downloaded model could not be stored in the settings", e)
        }
    }

    /**
     * Cancel an in-progress LLM download. The state goes back to Idle and the .partial file
     * stays, so starting the same model again continues where this one stopped: that is Pause.
     */
    fun cancelLlmDownload() = synchronized(lock) {
        llmProgressJob?.cancel()
        heldLlm = null
        llmDownloadId?.let { downloadManager.remove(it) }
        llmDownloadId = null
        downloadStateManager.resetLlmState()
        // State reset above triggers the service observer → service stops itself.
    }

    /**
     * Says that the AI model [variant] is held for [reason] where its download's state shows,
     * without running; see [holdSttDownload]. Ignored while another AI model's download runs.
     */
    fun holdLlmDownload(variant: LlmModelVariant, reason: PauseReason, progress: DownloadProgress?) = synchronized(lock) {
        val current = downloadStateManager.llmVariant.value
        if (current != null && current != variant && DownloadServiceRules.isRunning(downloadStateManager.llmDownloadState.value)) {
            return@synchronized
        }
        if (current == variant) llmProgressJob?.cancel()
        if (heldLlm == variant) heldLlm = null
        downloadStateManager.setLlmVariant(variant)
        downloadStateManager.updateLlmState(DownloadState.Paused(reason, progress))
    }

    // ── The reader ──────────────────────────────────────────────────────────

    /**
     * Starts the download of the reader's build [model] (0.8.0, the first run's READER part), or
     * continues an interrupted one: its files one after the other into its folder, each checked
     * against its pinned SHA-256 before it is given its name (ModelDownloadManager's
     * downloadReaderModel), in a place of its own beside the speech and AI model downloads. While a
     * recorder is busy it only waits ([holdForRecording]). Nothing here decides the network: the
     * first run's queue holds it for Wi-Fi.
     */
    fun startReaderDownload(model: ReaderModelId = ReaderModelId.INT8) = synchronized(lock) {
        if (heldForRecording) {
            Log.i(TAG, "Reader download of ${model.id} waits for the recording")
            readerProgressJob?.cancel()
            val before = downloadStateManager.readerDownloadState.value.takeIf { downloadStateManager.readerModel.value == model }
            heldReader = model
            downloadStateManager.setReaderModel(model)
            downloadStateManager.updateReaderState(DownloadState.Paused(PauseReason.RECORDING, before?.let(DownloadServiceRules::progressOf)))
            return@synchronized
        }
        launchReader(model)
    }

    private fun launchReader(model: ReaderModelId) {
        Log.i(TAG, "Starting the reader download of ${model.id}")
        val previous = readerProgressJob
        previous?.cancel()

        downloadStateManager.setReaderModel(model)
        downloadStateManager.updateReaderState(DownloadState.Downloading(progress = DownloadProgress(0, 0, 0)))

        // Elevate to foreground so the download survives backgrounding.
        startDownloadService()

        readerProgressJob = scope.launch {
            // The download before it in this place writes nothing more once it has ended.
            previous?.join()
            try {
                modelDownloadManager.downloadReaderModel(model).collect { p ->
                    val progress = DownloadProgress(p.bytesDownloaded, p.totalBytes, p.percentage)
                    report {
                        downloadStateManager.updateReaderState(
                            if (p.verifying) DownloadState.Verifying(progress) else DownloadState.Downloading(progress)
                        )
                    }
                }
                downloadStateManager.updateReaderState(DownloadState.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reader download failed", e)
                downloadStateManager.updateReaderState(DownloadState.Error(e.message ?: "Download failed", DownloadFailure.of(e)))
            }
        }
    }

    /**
     * Cancels the reader's download. The state goes back to Idle and the files downloaded so far
     * stay, so starting it again continues where this one stopped.
     */
    fun cancelReaderDownload() = synchronized(lock) {
        readerProgressJob?.cancel()
        heldReader = null
        downloadStateManager.resetReaderState()
    }

    /**
     * Says that the reader's build [model] is held for [reason] where its download's state shows,
     * without running; see [holdSttDownload]. Ignored while another build's download runs.
     */
    fun holdReaderDownload(model: ReaderModelId, reason: PauseReason, progress: DownloadProgress?) = synchronized(lock) {
        val current = downloadStateManager.readerModel.value
        if (current != null && current != model && DownloadServiceRules.isRunning(downloadStateManager.readerDownloadState.value)) {
            return@synchronized
        }
        if (current == model) readerProgressJob?.cancel()
        if (heldReader == model) heldReader = null
        downloadStateManager.setReaderModel(model)
        downloadStateManager.updateReaderState(DownloadState.Paused(reason, progress))
    }

    /** Cancel all downloads and release resources. */
    fun cleanup() {
        sttProgressJob?.cancel()
        llmProgressJob?.cancel()
        readerProgressJob?.cancel()
        vocabularyJob?.cancel()
    }
}
