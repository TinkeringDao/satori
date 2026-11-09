package com.satori.data.datasource

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.satori.data.config.ModelConfig
import com.satori.data.config.DefaultModelConfig
import com.satori.data.config.PinnedModelFile
import com.satori.data.config.ReaderModels
import com.satori.data.config.SpeechModelSpec
import com.satori.data.config.SpeechModels
import com.satori.domain.modelfile.ModelFileSpec
import com.satori.domain.modelfile.ModelFileState
import com.satori.data.config.modelConfigForVariant
import com.satori.data.speech.SpeechModelLoadCheck
import com.satori.domain.firstrun.FilesCheck
import com.satori.domain.model.DownloadException
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages downloading and verification of on-device model files.
 *
 * Model URLs and filenames are supplied via [ModelConfig] so that they can be changed
 * (e.g. for different build variants or A/B tests) without touching this class. The speech
 * models are those of the registry [SpeechModels], each in its folder under `models/stt`; the
 * reader's builds those of [ReaderModels], each in its folder under `models/reader`.
 *
 * Progress is told by time, not by percent ([ProgressThrottle]), and a speech model's file says
 * when its bytes are in and it is being checked ([DownloadProgress.verifying]), which holds the
 * numbers still for some seconds on a large file.
 *
 * @param clock A monotonic clock in ms, for the progress's cadence and the log's times
 */
class ModelDownloadManager(
    private val context: Context,
    private val modelConfig: ModelConfig = DefaultModelConfig.INSTANCE,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    // App-specific external storage: no special permissions needed, deleted on uninstall (Android 4.4+)
    private val modelsDir = File(context.getExternalFilesDir(null), "models")

    // The speech model directory: Parakeet v3's files, and a folder for each other speech model.
    private val sttDir = File(modelsDir, "stt")

    // The reader's directory: a folder for each build, as their graphs have the same names.
    private val readerDir = File(modelsDir, "reader")

    private val _modelFilesChanged = MutableStateFlow(0)

    // The reader's record of checked files is read and written under this lock, by the download and the checks.
    private val verifiedLock = Any()

    /**
     * Counts the times the app deleted a model file. A screen that shows which models are on the
     * phone recomputes when this changes; downloads announce themselves through the download
     * states instead.
     */
    val modelFilesChanged: StateFlow<Int> = _modelFilesChanged.asStateFlow()

    companion object {
        private const val TAG = "ModelDownloadManager"

        // Read and write the speech models in large steps: a 2.9 GB model is many small reads.
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val HASH_BUFFER_BYTES = 1024 * 1024

        private const val TIMEOUT_MS = 15_000

        /**
         * How long a connect may take. A connect to the model server takes well under a second on a
         * network Android validated; one that hasn't connected in 5 s is a network that drops what
         * Satori sends, as a firewall that denies the app does without a word. The first run reads
         * two such failures in a row on a validated network as blocked, so with the 15 s of a read
         * that stalled and its 5 s wait before the retry, it says so within 30 s (design C4).
         */
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    /**
     * How long a read waits for the next bytes before the download counts as cut off. The tests
     * shorten it, to see a stalled connection fail without waiting 15 s.
     */
    internal var readTimeoutMs = TIMEOUT_MS

    /** How long a connect may take before the download counts as without a connection ([CONNECT_TIMEOUT_MS]). */
    internal var connectTimeoutMs = CONNECT_TIMEOUT_MS

    init {
        modelsDir.mkdirs()
    }

    /**
     * Download progress state.
     *
     * @property verifying Every byte of a file is in and it is being checked, which holds the
     *   numbers still for some seconds on a large file; the next progress says it is done
     */
    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int,
        val verifying: Boolean = false
    )

    /**
     * Get the path to the LLM model file.
     *
     * @param filename The model filename to check; defaults to the injected [ModelConfig] filename.
     *                 Pass a variant-specific filename to check a non-default variant.
     */
    fun getLlmModelPath(filename: String = modelConfig.llmFilename): String? {
        val modelFile = File(modelsDir, filename)
        return if (modelFile.exists() && modelFile.length() > 0) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    /**
     * Check if the LLM model file is already downloaded.
     *
     * @param filename The model filename to check; defaults to the injected [ModelConfig] filename.
     */
    fun isLlmModelDownloaded(filename: String = modelConfig.llmFilename): Boolean {
        return getLlmModelPath(filename) != null
    }

    /** The AI models whose file is on the phone, whichever one is selected. */
    fun installedLlmVariants(): Set<LlmModelVariant> =
        LlmModelVariant.entries.filterTo(mutableSetOf()) {
            isLlmModelDownloaded(modelConfigForVariant(it).llmFilename)
        }

    /** Bytes of an unfinished download of [filename] (stopped, failed or paused); 0 when there is none. */
    fun llmPartialBytes(filename: String): Long {
        val partialFile = File(modelsDir, "$filename.partial")
        return if (partialFile.exists()) partialFile.length() else 0L
    }

    /** Deletes the model file [filename] and what is left of an unfinished download of it. */
    fun deleteLlmModel(filename: String) {
        File(modelsDir, filename).delete()
        File(modelsDir, "$filename.partial").delete()
        _modelFilesChanged.update { it + 1 }
    }

    /**
     * Download LLM model with progress updates and resume capability.
     *
     * With [spec], what the complete file looks like, an answer of the wrong length is refused
     * before a byte is written, as for the speech models: the login page of a hotel network
     * answers 200 with a page of its own, which would empty what the earlier tries fetched and
     * then be taken for the model. A file that ends short is kept to be continued; one that
     * comes out longer than the spec is no start for anything and goes.
     *
     * @param spec What the complete file looks like ([ModelConfig.llmSpec]); null checks nothing
     */
    fun downloadLlmModel(
        url: String = modelConfig.llmUrl,
        filename: String = modelConfig.llmFilename,
        spec: ModelFileSpec? = null
    ): Flow<DownloadProgress> = flow {
        val outputFile = File(modelsDir, filename)
        val partialFile = File(modelsDir, "$filename.partial")
        val exact = spec?.bytes

        // If already exists and is valid, don't re-download
        if (outputFile.exists() && outputFile.length() > 100_000_000) { // At least 100MB
            Log.i(TAG, "Model already exists: ${outputFile.absolutePath}")
            emit(DownloadProgress(outputFile.length(), outputFile.length(), 100))
            return@flow
        }

        // The registry's size is the one the file must have; a page's length is not. Without it,
        // the size comes from a HEAD request, which waits no longer than a read does.
        var totalBytes = exact ?: 0L
        if (exact == null) {
            Log.i(TAG, "Fetching actual file size...")
            var headConnection: HttpURLConnection? = null
            try {
                headConnection = URL(url).openConnection() as HttpURLConnection
                headConnection.requestMethod = "HEAD"
                headConnection.connectTimeout = connectTimeoutMs
                headConnection.readTimeout = readTimeoutMs
                headConnection.instanceFollowRedirects = true
                headConnection.connect()

                totalBytes = headConnection.contentLengthLong
                if (totalBytes <= 0) {
                    Log.w(TAG, "Could not get file size from HEAD request, will try during download")
                } else {
                    Log.i(TAG, "LLM model size: ${totalBytes / 1_000_000}MB")
                }
            } catch (e: Exception) {
                Log.w(TAG, "HEAD request failed, will get size during download", e)
            } finally {
                headConnection?.disconnect()
            }
        }

        // A part longer than the file is no start for it; one that holds all of it, as a
        // download that ended just before its rename leaves it, is only given its name: a Range
        // from its end would be refused (416).
        if (exact != null && partialFile.length() > exact) partialFile.delete()
        if (exact != null && partialFile.length() == exact) {
            moveIntoPlace(partialFile, outputFile)
            emit(DownloadProgress(exact, exact, 100))
            return@flow
        }

        // Check for partial download
        val startByte = if (partialFile.exists()) {
            val existingSize = partialFile.length()
            Log.i(TAG, "Found partial download: ${existingSize / 1_000_000}MB, resuming...")
            existingSize
        } else {
            0L
        }

        var connection: HttpURLConnection? = null
        try {
            Log.i(TAG, "Starting download from: $url (from byte $startByte)")
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            // Follow redirects (important for HuggingFace)
            connection.instanceFollowRedirects = true

            // Request range if resuming
            if (startByte > 0) {
                connection.setRequestProperty("Range", "bytes=$startByte-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw DownloadException(DownloadFailure.SERVER, "Server returned error code: $responseCode")
            }

            val isResuming = startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            // Before a byte is written: an answer that can't be the rest of the file, or the
            // file, is a page in its place. An answer that gives no length is not held against it.
            val sent = connection.contentLengthLong
            if (sent >= 0 && spec != null) {
                val wanted = exact?.let { if (isResuming) it - startByte else it }
                val whole = if (isResuming) startByte + sent else sent
                if ((wanted != null && sent != wanted) || (wanted == null && whole < spec.minBytes)) {
                    throw DownloadException(DownloadFailure.NO_CONNECTION, "$filename: the answer has $sent bytes, not ${wanted ?: "at least ${spec.minBytes}"}")
                }
            }

            // If we didn't get totalBytes from HEAD, get it from download response
            if (totalBytes <= 0) {
                totalBytes = if (startByte > 0 && responseCode == HttpURLConnection.HTTP_OK) {
                    // Server doesn't support resume, start from beginning
                    Log.w(TAG, "Server doesn't support resume, restarting download")
                    partialFile.delete()
                    connection.contentLengthLong
                } else if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // Resume supported, get total from Content-Range header
                    val contentRange = connection.getHeaderField("Content-Range")
                    if (contentRange != null && contentRange.contains("/")) {
                        contentRange.substringAfterLast("/").toLongOrNull() ?: (startByte + connection.contentLengthLong)
                    } else {
                        startByte + connection.contentLengthLong
                    }
                } else {
                    connection.contentLengthLong
                }
            }

            Log.i(TAG, "Total size: ${totalBytes / 1_000_000}MB, starting from: ${startByte / 1_000_000}MB")

            val throttle = ProgressThrottle(clock)
            connection.inputStream.use { input ->
                FileOutputStream(partialFile, isResuming).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var bytesRead: Int
                    // A server that ignores the Range sends the whole file again, from its start.
                    var totalBytesRead = if (isResuming) startByte else 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // A paused download stops at once, not at the next progress update.
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (throttle.due(totalBytesRead)) {
                            val percentage = if (totalBytes > 0) ((totalBytesRead * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                            emit(DownloadProgress(totalBytesRead, totalBytes, percentage))
                        }
                    }
                }
            }

            val got = partialFile.length()
            if (exact != null && got > exact) {
                // Not the file the registry names: fetching the rest of it again can't fix that.
                partialFile.delete()
                throw DownloadException(DownloadFailure.OTHER, "$filename came out at $got of $exact bytes")
            }
            if ((exact != null && got < exact) || (spec != null && got < spec.minBytes)) {
                throw DownloadException(DownloadFailure.NO_CONNECTION, "$filename ended at $got of ${exact ?: spec?.minBytes} bytes")
            }

            // Final progress
            emit(DownloadProgress(got, got, 100))
            moveIntoPlace(partialFile, outputFile)
        } catch (e: CancellationException) {
            // Paused or left: the partial file stays for the next start, and no one is told it failed.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed - partial file kept for resume", e)
            // Don't delete partial file - keep it for resume
            throw DownloadException(
                DownloadFailure.of(e),
                "Download failed. You can retry to resume from ${startByte / 1_000_000}MB",
                e
            )
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Gives a finished download [partialFile] its name, [outputFile]. */
    private fun moveIntoPlace(partialFile: File, outputFile: File) {
        if (partialFile.renameTo(outputFile)) {
            Log.i(TAG, "Download completed: ${outputFile.absolutePath}")
        } else {
            // If rename fails, copy and delete
            partialFile.copyTo(outputFile, overwrite = true)
            partialFile.delete()
            Log.i(TAG, "Download completed (via copy): ${outputFile.absolutePath}")
        }
    }

    // ── Speech models ────────────────────────────────────────────────────────

    /**
     * The folder of [model]'s files: the speech model directory itself for Parakeet v3, whose
     * files have been there from the start, and a folder of its own in it for each other model.
     */
    fun speechModelDir(model: SpeechModelId = SpeechModelId.LIVE): File {
        val folder = SpeechModels.of(model).folder
        return if (folder.isEmpty()) sttDir else File(sttDir, folder)
    }

    /**
     * Whether every file of [model] is there and not empty. [checkSttModel] says whether they
     * are the right ones.
     */
    fun isSttModelDownloaded(model: SpeechModelId = SpeechModelId.LIVE): Boolean {
        val dir = speechModelDir(model)
        return SpeechModels.of(model).files.all { file ->
            val onDisk = File(dir, file.spec.name)
            onDisk.exists() && onDisk.length() > 0
        }
    }

    /**
     * Deletes the files of [model] and what is left of an unfinished download of them, so that
     * the next [downloadSttModel] fetches them all again. Without this a damaged file counts as
     * downloaded for as long as it is not empty. Parakeet v3's folder holds the other models'
     * folders and the word list's vocabulary too, which stay.
     */
    fun deleteSttModel(model: SpeechModelId = SpeechModelId.LIVE) {
        val spec = SpeechModels.of(model)
        val dir = speechModelDir(model)
        if (spec.folder.isEmpty()) {
            spec.files.forEach { file ->
                File(dir, file.spec.name).delete()
                File(dir, partialName(file)).delete()
            }
        } else {
            dir.deleteRecursively()
        }
        _modelFilesChanged.update { it + 1 }
    }

    /**
     * The path of [model]'s folder if all its files are there, null otherwise
     * ([isSttModelDownloaded]).
     */
    fun getSttModelPath(model: SpeechModelId = SpeechModelId.LIVE): String? =
        if (isSttModelDownloaded(model)) speechModelDir(model).absolutePath else null

    /**
     * Whether the files of [model] are the right ones. [isSttModelDownloaded] only asks whether
     * they exist; this reads each file's size and first bytes (see [ModelFileInspector]), and for
     * a model whose registry entry says what sherpa-onnx reads from its files, that too: the
     * metadata of its ONNX file and its list of tokens ([SpeechModelLoadCheck]). This is what
     * decides whether sherpa-onnx may open them; one it refuses is [ModelFileState.Damaged], or
     * [ModelFileState.Unreadable] when a file couldn't be read, with a warning that says why. Cheap
     * (a few small reads), but file access: not on the main thread.
     */
    fun checkSttModel(model: SpeechModelId = SpeechModelId.LIVE): ModelFileState {
        val dir = speechModelDir(model)
        val spec = SpeechModels.of(model)
        val state = ModelFileInspector.speechModel(dir, spec.specs)
        if (state != ModelFileState.Intact && state != ModelFileState.Missing) {
            Log.w(TAG, "Speech model ${model.id} is $state: ${ModelFileInspector.speechModelReport(dir, spec.specs)}")
        }
        if (state != ModelFileState.Intact) return state
        val loadCheck = SpeechModelLoadCheck.check(dir, spec.files)
        if (loadCheck.state != ModelFileState.Intact) {
            Log.w(TAG, "Speech model ${model.id} refused, ${loadCheck.state}: ${loadCheck.reason}")
        }
        return loadCheck.state
    }

    /**
     * The bytes of [model]'s files on the phone, whole ones and what an unfinished download left:
     * more than 0 for a model that is partly there.
     */
    fun speechModelBytesOnPhone(model: SpeechModelId): Long {
        val dir = speechModelDir(model)
        return SpeechModels.of(model).files.sumOf { file ->
            File(dir, file.spec.name).length() + File(dir, partialName(file)).length()
        }
    }

    /**
     * The vocabulary words to recognize are split with ([SpeechModelSpec.vocabulary] of Parakeet
     * v3), when it is on the phone and whole; null otherwise. File access: not on the main thread.
     */
    fun speechVocabularyFile(): File? {
        val vocabulary = SpeechModels.PARAKEET_V3.vocabulary ?: return null
        val file = File(speechModelDir(SpeechModelId.PARAKEET_V3), vocabulary.spec.name)
        return file.takeIf { ModelFileInspector.inspect(it, vocabulary.spec) == ModelFileState.Intact }
    }

    /**
     * Downloads the files of [model] into its folder ([speechModelDir]), one after the other,
     * with progress over all of them; see [downloadFiles].
     */
    fun downloadSttModel(model: SpeechModelId = SpeechModelId.LIVE): Flow<DownloadProgress> =
        downloadFiles(speechModelDir(model), SpeechModels.of(model).files, model.id)

    /** Downloads Parakeet v3's vocabulary for words to recognize ([speechVocabularyFile]). */
    fun downloadSpeechVocabulary(): Flow<DownloadProgress> {
        val vocabulary = checkNotNull(SpeechModels.of(SpeechModelId.PARAKEET_V3).vocabulary) { "Parakeet v3 has no vocabulary" }
        return downloadFiles(speechModelDir(SpeechModelId.PARAKEET_V3), listOf(vocabulary), vocabulary.spec.name)
    }

    // ── The reader ───────────────────────────────────────────────────────────

    /**
     * The folder of the reader build [model]'s files, `models/reader/<id>`: a folder of its own for
     * each build, as the two builds' graphs have the same names.
     */
    fun readerModelDir(model: ReaderModelId = ReaderModelId.INT8): File = File(readerDir, model.id)

    /** Whether every file of [model] is there and not empty; [checkReaderModel] says whether they are the right ones. */
    fun isReaderModelDownloaded(model: ReaderModelId = ReaderModelId.INT8): Boolean {
        val dir = readerModelDir(model)
        return ReaderModels.of(model).files.all { file ->
            val onDisk = File(dir, file.spec.name)
            onDisk.exists() && onDisk.length() > 0
        }
    }

    /**
     * Whether the files of [model] are the right ones, before the reader loads them: each file's
     * exact size, and the first bytes of each ONNX graph ([ModelFileInspector.files]), with a
     * warning naming the files when they aren't ([ModelFileState.Missing] says nothing). Their
     * SHA-256 is the download's ([downloadFiles]) and [hashReaderModel]'s. Cheap, but file access:
     * not on the main thread.
     */
    fun checkReaderModel(model: ReaderModelId = ReaderModelId.INT8): ModelFileState {
        val dir = readerModelDir(model)
        val specs = ReaderModels.of(model).specs
        val state = ModelFileInspector.files(dir, specs)
        if (state != ModelFileState.Intact && state != ModelFileState.Missing) {
            Log.w(TAG, "Reader ${model.id} is $state: ${ModelFileInspector.filesReport(dir, specs)}")
        }
        return state
    }

    /**
     * The SHA-256 of each of [model]'s files on the phone ([names], or all of them), beside its
     * pin: the whole check of a download a debug tool asks for. It reads every byte, about 2 s for
     * the int8 build's 242 MB, and fills the page cache with them on the way. Cancellable between
     * reads; on IO.
     */
    suspend fun hashReaderModel(model: ReaderModelId = ReaderModelId.INT8, names: Collection<String>? = null): List<PinnedFileHash> =
        withContext(Dispatchers.IO) {
            val dir = readerModelDir(model)
            ReaderModels.of(model).files.filter { names == null || it.spec.name in names }.map { file ->
                val onDisk = File(dir, file.spec.name)
                val sha256 = if (onDisk.isFile) {
                    try {
                        sha256Of(onDisk)
                    } catch (e: IOException) {
                        null
                    }
                } else {
                    null
                }
                PinnedFileHash(file.spec.name, sha256, file.sha256)
            }
        }

    /**
     * The bytes of [model]'s files on the phone, whole ones and what an unfinished download left:
     * more than 0 for a build that is partly there.
     */
    fun readerModelBytesOnPhone(model: ReaderModelId = ReaderModelId.INT8): Long {
        val dir = readerModelDir(model)
        return ReaderModels.of(model).files.sumOf { file -> File(dir, file.spec.name).length() + File(dir, partialName(file)).length() }
    }

    /** Deletes [model]'s folder, its files and what an unfinished download of them left. */
    fun deleteReaderModel(model: ReaderModelId = ReaderModelId.INT8) {
        readerModelDir(model).deleteRecursively()
        _modelFilesChanged.update { it + 1 }
    }

    /**
     * Downloads the files of the reader build [model] into its folder ([readerModelDir]), one after
     * the other, with progress over all of them; see [downloadFiles]. Nothing here decides the
     * network: the caller holds it for Wi-Fi. Each file it fetches and finds the pinned one goes
     * into the folder's record of checked files ([readerModelVerified]); a file it keeps, as it was
     * whole already, is checked once by [verifyReaderModel].
     */
    fun downloadReaderModel(model: ReaderModelId = ReaderModelId.INT8): Flow<DownloadProgress> =
        downloadReaderFiles(readerModelDir(model), ReaderModels.of(model).files, model.id)

    /** [downloadReaderModel] of [files] into [dir]; internal for the tests, which serve files of their own. */
    internal fun downloadReaderFiles(dir: File, files: List<PinnedModelFile>, label: String): Flow<DownloadProgress> =
        downloadFiles(dir, files, label, kind = "Reader") { file, sha256 ->
            synchronized(verifiedLock) { VerifiedFiles.add(dir, file, sha256) }
        }

    /**
     * Whether [model]'s files are on the phone and the pinned ones (design R7): whole by their sizes
     * and first bytes ([checkReaderModel]), and each found once with its pinned SHA-256, as the
     * folder's record says ([VerifiedFiles]) for the file as it is now, its size and its last
     * change. Cheap: no file is read but the record and the graphs' first bytes, and it logs
     * nothing. Until its files' SHA-256 has been read ([verifyReaderModel]), a build put there
     * another way than its download says no.
     */
    fun readerModelVerified(model: ReaderModelId = ReaderModelId.INT8): Boolean =
        filesVerified(readerModelDir(model), ReaderModels.of(model).files)

    /**
     * [readerModelVerified], reading once the SHA-256 of each file the folder's record lacks (files
     * the debug tools fetched before 0.8.0-dev3 kept, or ones put there by hand) and keeping each
     * that is the pinned one in the record, so that a file is read in full once only. About 2 s for
     * the int8 build's 242 MB. [stop] is asked before each file and between reads: true leaves the
     * rest for a later call ([FilesCheck.NOT_NOW]), the files read so far kept in the record. A file
     * that isn't the pinned one is left where it is, for the first run's check to discard; its name
     * is logged, never its contents, and it is not read again in this process until it changes (its
     * size or last change). Blocking file access: not on the main thread.
     */
    fun verifyReaderModel(model: ReaderModelId = ReaderModelId.INT8, stop: () -> Boolean = { false }): FilesCheck =
        verifyFiles(readerModelDir(model), ReaderModels.of(model).files, "Reader ${model.id}", stop)

    /**
     * The files [verifyFiles] found not to be their pins in this process, by path, with their size
     * and last change then ([stamp]); held under [verifiedLock].
     */
    private val notThePin = HashMap<String, Pair<Long, Long>>()

    /** A file's size and last change, which a file written since changes. */
    private fun stamp(file: File): Pair<Long, Long> = file.length() to file.lastModified()

    /** [readerModelVerified] of [files] in [dir]; internal for the tests. */
    internal fun filesVerified(dir: File, files: List<PinnedModelFile>): Boolean = synchronized(verifiedLock) {
        if (ModelFileInspector.files(dir, files.map { it.spec }) != ModelFileState.Intact) return false
        val record = VerifiedFiles.read(dir)
        files.all { VerifiedFiles.covers(record, dir, it) }
    }

    /** [verifyReaderModel] of [files] in [dir], [label] naming them in the log; internal for the tests. */
    internal fun verifyFiles(dir: File, files: List<PinnedModelFile>, label: String, stop: () -> Boolean): FilesCheck =
        synchronized(verifiedLock) {
            val specs = files.map { it.spec }
            when (val state = ModelFileInspector.files(dir, specs)) {
                ModelFileState.Intact -> Unit
                ModelFileState.Missing -> return FilesCheck.FAILED
                else -> {
                    Log.w(TAG, "$label is $state: ${ModelFileInspector.filesReport(dir, specs)}")
                    return FilesCheck.FAILED
                }
            }
            val record = VerifiedFiles.read(dir)
            val unread = files.filterNot { VerifiedFiles.covers(record, dir, it) }
            if (unread.isEmpty()) return FilesCheck.PASSED
            val started = clock()
            for (file in unread) {
                // Found not to be the pin before, and as it was then: not read again (it would be
                // every few seconds while the first run looks at the disk), until it changes.
                if (notThePin[File(dir, file.spec.name).path] == stamp(File(dir, file.spec.name))) return FilesCheck.FAILED
                if (stop()) {
                    Log.i(TAG, "$label: the check of its files waits for a recorder, ${unread.size} unread")
                    return FilesCheck.NOT_NOW
                }
                val sha256 = try {
                    sha256Blocking(File(dir, file.spec.name), stop) ?: return FilesCheck.NOT_NOW.also {
                        Log.i(TAG, "$label: the check of its files waits for a recorder, ${file.spec.name} and after it unread")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "$label: ${file.spec.name} could not be read for its check (${e.javaClass.simpleName})")
                    return FilesCheck.FAILED
                }
                if (sha256 != file.sha256) {
                    Log.w(TAG, "$label: ${file.spec.name} is not the pinned file, its SHA-256 differs")
                    notThePin[File(dir, file.spec.name).path] = stamp(File(dir, file.spec.name))
                    return FilesCheck.FAILED
                }
                VerifiedFiles.add(dir, file, sha256)
            }
            Log.i(TAG, "$label: ${unread.size} of ${files.size} files found the pinned ones in ${clock() - started} ms")
            FilesCheck.PASSED
        }

    /**
     * Downloads [files] into [dir], one after the other, with progress over all of them.
     *
     * A file already there whole (its size and first bytes, [ModelFileInspector]) is kept; any
     * other file of that name goes. Each file is downloaded to a `.partial` beside it and renamed
     * only once it has its exact size and SHA-256 ([PinnedModelFile.sha256]): a file with the right
     * size but other content, say a page a proxy put in its place, is deleted and the download
     * fails. On any other failure or a cancel (Pause), the finished files and the `.partial` of the
     * one under way stay, so the next start goes on where this one stopped, with a Range request.
     * Progress comes by time ([ProgressThrottle]); while a file's SHA-256 is computed, which holds
     * still for seconds on the 652 MB encoder, the progress says so ([DownloadProgress.verifying]).
     *
     * Internal for the tests, which serve the files from a server of their own.
     *
     * @param label What the log calls the download: a model id or a file name, never personal
     * @param kind What the log calls the files' model: "Speech" for a speech model's, "Reader" for
     *   the reader's
     * @param onPinned Called with each file once it is in its place, found with its pinned SHA-256
     *   (the hash given), before the next one is fetched
     */
    internal fun downloadFiles(
        dir: File,
        files: List<PinnedModelFile>,
        label: String,
        kind: String = "Speech",
        onPinned: (PinnedModelFile, String) -> Unit = { _, _ -> }
    ): Flow<DownloadProgress> = flow {
        dir.mkdirs()
        val totalBytes = files.sumOf { it.spec.bytes ?: it.spec.minBytes }
        val pending = files.filter { file -> ModelFileInspector.inspect(File(dir, file.spec.name), file.spec) != ModelFileState.Intact }
        // A file of the right name that isn't whole is no start for its download: it goes, and so
        // does a part longer than its file.
        pending.forEach { file ->
            File(dir, file.spec.name).delete()
            File(dir, partialName(file)).takeIf { it.length() > (file.spec.bytes ?: file.spec.minBytes) }?.delete()
        }
        // The whole files and what unfinished downloads of the others left: a download that goes on
        // says so from its first numbers.
        var bytesDone = totalBytes - pending.sumOf { it.spec.bytes ?: it.spec.minBytes } +
            pending.sumOf { File(dir, partialName(it)).length() }
        val throttle = ProgressThrottle(clock)
        fun progress(verifying: Boolean = false): DownloadProgress {
            val percent = if (totalBytes > 0) (bytesDone * 100 / totalBytes).toInt().coerceIn(0, 99) else 0
            return DownloadProgress(bytesDone, totalBytes, percent, verifying)
        }
        emit(progress())
        throttle.told(bytesDone)
        Log.i(TAG, "$kind download $label: ${pending.size} of ${files.size} files to fetch, " +
            "${(totalBytes - bytesDone) / 1_000_000} MB")

        for ((index, file) in pending.withIndex()) {
            val expected = file.spec.bytes ?: file.spec.minBytes
            val partial = File(dir, partialName(file))
            var startByte = partial.length()

            if (startByte < expected) {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(file.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = connectTimeoutMs
                    connection.readTimeout = readTimeoutMs
                    connection.instanceFollowRedirects = true
                    if (startByte > 0) connection.setRequestProperty("Range", "bytes=$startByte-")
                    connection.connect()

                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        throw DownloadException(DownloadFailure.SERVER, "HTTP $responseCode for ${file.spec.name}")
                    }
                    // A server that ignores the Range sends the whole file again.
                    val resuming = startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
                    // Before a byte is written: the login page of a hotel network, say, answers 200
                    // with a page of its own, which would empty what the earlier tries fetched. An
                    // answer that gives no length is not held against it.
                    val sent = connection.contentLengthLong
                    val wanted = if (resuming) expected - startByte else expected
                    if (sent >= 0 && sent != wanted) {
                        throw DownloadException(DownloadFailure.NO_CONNECTION, "${file.spec.name}: the answer has $sent bytes, not $wanted")
                    }
                    if (startByte > 0 && !resuming) {
                        Log.w(TAG, "Server did not honour Range for ${file.spec.name}, restarting it")
                        bytesDone -= startByte
                        startByte = 0
                    }
                    Log.i(TAG, "Downloading ${file.spec.name}" + if (resuming) " from ${startByte / 1_000_000} MB" else "")

                    connection.inputStream.use { input ->
                        FileOutputStream(partial, resuming).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                // A paused download stops at once, not at the next progress update.
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, read)
                                bytesDone += read
                                if (throttle.due(bytesDone)) emit(progress())
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The finished files and this .partial stay, so the next try goes on from here.
                    throw DownloadException(DownloadFailure.of(e), "$kind download $label failed at ${file.spec.name}: ${e.message}", e)
                } finally {
                    connection?.disconnect()
                }
            }

            if (partial.length() != expected) {
                throw DownloadException(
                    DownloadFailure.NO_CONNECTION,
                    "${file.spec.name} ended at ${partial.length()} of $expected bytes"
                )
            }
            emit(progress(verifying = true))
            val checkStart = clock()
            val sha256 = sha256Of(partial)
            if (sha256 != file.sha256) {
                // Not the file the registry names: fetching the rest of it again can't fix that.
                partial.delete()
                throw DownloadException(DownloadFailure.OTHER, "${file.spec.name} has SHA-256 $sha256, not ${file.sha256}")
            }
            val target = File(dir, file.spec.name)
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onPinned(file, sha256)
            Log.i(TAG, "${file.spec.name} complete and checked (${expected / 1_000_000} MB), " +
                "file ${index + 1} of ${pending.size}, checked in ${clock() - checkStart} ms")
            // The next numbers say the check is over, so a screen stops saying it.
            emit(progress())
            throttle.told(bytesDone)
        }

        emit(DownloadProgress(totalBytes, totalBytes, 100))
        Log.i(TAG, "$kind download $label completed")
    }.flowOn(Dispatchers.IO)

    /**
     * The SHA-256 of [file] as lower-case hex, read on the caller's thread; null once [stop] says so,
     * which it is asked between reads.
     */
    private fun sha256Blocking(file: File, stop: () -> Boolean): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (stop()) return null
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The SHA-256 of [file] as lower-case hex; a cancel stops it between reads. */
    private suspend fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun partialName(file: PinnedModelFile): String = "${file.spec.name}.partial"
}

/**
 * The SHA-256 of a model file on the phone ([sha256], lower-case hex; null when there is no such
 * file or it couldn't be read) beside the one its registry pins ([pinned]).
 */
data class PinnedFileHash(val name: String, val sha256: String?, val pinned: String) {
    /** Whether the file on the phone is the pinned one. */
    val matches: Boolean get() = sha256 == pinned
}
