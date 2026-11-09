package com.satori.presentation.settings

import android.content.ContextWrapper
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.quickkey.QuickKeySettings
import com.satori.data.quickkey.QuickKeySettingsRepository
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.sessions.HomeSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Models and storage reads again at each change that can change what it shows, not only after a
 * download, a deleted file or a setting: the power key's language (Malayalam's use), the switch of
 * AI for finished recordings (Gemma 4 E2B's), and what runs, so a model's "in use now" ends with
 * the recording, the finishing or the AI work that held it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelStorageChangesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = KeySettings()
    private val finishingSwitch = FinishingSwitch()
    private val queue = BusyQueue()
    private val processing = Holders()
    private val recorders = ActiveRecorder()
    private val settings = HomeSettings()

    @Test
    fun `each change that can change the screen reads it again`() = runTest(UnconfinedTestDispatcher()) {
        val reader = ModelStorageReader(
            modelDownloadManager = ModelDownloadManager(FilesIn(tmp.newFolder("files"))),
            settingsRepository = settings,
            quickKeySettingsRepository = keys,
            finishingModelRepository = finishingSwitch,
            activeRecorder = recorders,
            finishingQueue = queue,
            processing = processing
        )
        var reads = 0
        val following = launch { reader.changes().collect { reads++ } }
        runCurrent()
        assertEquals(1, reads)

        val changes: List<Pair<String, () -> Unit>> = listOf(
            "the power key's language" to { keys.settings.update { it.copy(language = RecordingLanguage.MALAYALAM) } },
            "AI for finished recordings" to { finishingSwitch.on.value = true },
            "a recording" to { recorders.open(this, "s1", ActiveRecorder.Activity.RECORDING) },
            "the finishing" to { queue.busyNow.value = true },
            "the AI work" to { processing.held.value = setOf("transcribe:s1") },
            "a setting" to { settings.settings.update { it.copy(llmEnabled = !it.llmEnabled) } }
        )
        for ((what, change) in changes) {
            val before = reads
            change()
            runCurrent()
            assertTrue(what, reads > before)
        }
        following.cancel()
    }

    @Test
    fun `the Malayalam model's entry holds the reader's files, every build, and its removal takes them too`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = ModelDownloadManager(FilesIn(tmp.newFolder("files")))
        val reader = ModelStorageReader(downloads, settings, keys, finishingSwitch, recorders, queue, processing)
        val malayalam = SpeechModels.of(SpeechModelId.INDICCONFORMER_ML).files.first().spec.name
        File(downloads.speechModelDir(SpeechModelId.INDICCONFORMER_ML).apply { mkdirs() }, malayalam).writeBytes(ByteArray(100))
        File(downloads.readerModelDir(ReaderModelId.INT8).apply { mkdirs() }, "config.json").writeBytes(ByteArray(30))
        File(downloads.readerModelDir(ReaderModelId.FP16).apply { mkdirs() }, "encoder_model.onnx.data.partial").writeBytes(ByteArray(20))

        val entry = reader.modelsOnPhone().single { it.ref == ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML) }
        assertEquals(150L, entry.bytes)

        ModelRemoval(downloads, settings, SettingsEditor(settings, backgroundScope)) { LlmModelVariant.Q8_0 }
            .removeSpeech(SpeechModelId.INDICCONFORMER_ML)

        assertFalse(downloads.readerModelDir(ReaderModelId.INT8).exists())
        assertFalse(downloads.readerModelDir(ReaderModelId.FP16).exists())
        assertTrue(reader.modelsOnPhone().none { it.ref == ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML) })
    }

    @Test
    fun `the reader's files alone show under the Malayalam entry, as part of a download, and its download holds that entry`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = ModelDownloadManager(FilesIn(tmp.newFolder("files")))
        val reader = ModelStorageReader(downloads, settings, keys, finishingSwitch, recorders, queue, processing)
        File(downloads.readerModelDir(ReaderModelId.INT8).apply { mkdirs() }, "config.json").writeBytes(ByteArray(30))

        assertEquals(
            listOf(ModelOnPhone(ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML), 30L, whole = false)),
            reader.modelsOnPhone()
        )
        val running = DownloadState.Downloading(DownloadProgress(1L, 10L, 10))
        assertEquals(
            setOf(ModelRef.Speech(SpeechModelId.INDICCONFORMER_ML)),
            ModelStorageReader.downloadingOf(DownloadState.Idle, null, DownloadState.Idle, null, reader = running)
        )
    }

    private class FilesIn(private val dir: File) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File = dir
    }

    private class KeySettings : QuickKeySettingsRepository {
        val settings = MutableStateFlow(QuickKeySettings())
        override fun observe(): Flow<QuickKeySettings> = settings
        override suspend fun setCategoryId(categoryId: String) = settings.update { it.copy(categoryId = categoryId) }
        override suspend fun setStopOnSecondPress(stop: Boolean) = settings.update { it.copy(stopOnSecondPress = stop) }
        override suspend fun setLanguage(language: RecordingLanguage) = settings.update { it.copy(language = language) }
    }

    private class FinishingSwitch : FinishingModelRepository {
        val on = MutableStateFlow(false)
        override fun observeEnabled(): Flow<Boolean> = on
        override suspend fun setEnabled(enabled: Boolean) {
            on.value = enabled
        }
        override suspend fun enabledModelPath(): String? = null
    }

    private class BusyQueue : FinishingQueue {
        val busyNow = MutableStateFlow(false)
        override val statuses = MutableStateFlow<List<FinishingStatus>>(emptyList())
        override val busy: StateFlow<Boolean> = busyNow
        override suspend fun captureStarted(sessionId: String) = Unit
        override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) = Unit
        override suspend fun cancel(sessionId: String) = Unit
        override fun skipBetterTranscript(sessionId: String) = Unit
        override fun resume(mayStartForeground: Boolean) = Unit
        override fun runUnderWakeJob(done: () -> Unit) = Unit
        override fun wakeJobStopped() = Unit
    }

    private class Holders : LlmProcessingServiceController {
        val held = MutableStateFlow<Set<String>>(emptySet())
        override val heldBy: StateFlow<Set<String>> = held
        override val protected = MutableStateFlow(false)
        override fun startProcessing(holder: String): Boolean = true
        override fun stopProcessing(holder: String) = Unit
    }
}
