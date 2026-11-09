package com.satori.data.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.satori.data.config.ModelSource
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.TestFileServer
import com.satori.data.firstrun.MemoryPrefs
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The recorder's gate over a real download from a server of the test's own (debug builds only,
 * whose model source can point at one): a download that runs stops at once and says why, one
 * asked for meanwhile waits, and both go on from what is on the phone once the recording is
 * saved, with a Range request.
 */
class RecordingGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val requests = CopyOnWriteArrayList<String>()

    // The start of the encoder, sent slowly, as if the rest were still to come.
    private val encoder = Random(3).nextBytes(3_000_000)
    private val encoderBytes = 652_184_281L
    private val encoderPath = "/stt/${SpeechModelId.PARAKEET_V3.id}/encoder.int8.onnx"

    private lateinit var server: TestFileServer
    private lateinit var context: TestContext
    private lateinit var states: DownloadStateManager
    private lateinit var manager: AndroidDownloadManager

    @Before
    fun setUp() {
        server = TestFileServer { request ->
            requests += "${request.path} ${request.range ?: "whole"}"
            if (request.path != encoderPath) return@TestFileServer TestFileServer.Answer(404)
            val from = request.range?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
            TestFileServer.Answer(
                status = if (from > 0) 206 else 200,
                body = encoder.copyOfRange(0, encoder.size - from.toInt().coerceAtMost(encoder.size)),
                headers = if (from > 0) mapOf("Content-Range" to "bytes $from-${encoderBytes - 1}/$encoderBytes") else emptyMap(),
                length = encoderBytes - from,
                end = TestFileServer.End.STALL,
                stallMs = 10_000L,
                pieceBytes = 64 * 1024,
                pieceDelayMs = 20L
            )
        }
        context = TestContext(tmp.root)
        ModelSource.set(context, "http://127.0.0.1:${server.port}/", "${"a".repeat(64)} $encoderBytes ${encoderPath.removePrefix("/")}")
        states = DownloadStateManager()
        manager = AndroidDownloadManager(context, states, ModelDownloadManager(context), NoSettings())
    }

    @After
    fun tearDown() {
        manager.cancelSttDownload()
        ModelSource.clear(context)
        server.close()
    }

    private val partial get() = File(tmp.root, "models/stt/encoder.int8.onnx.partial")

    /** Waits, in real time, until [condition] holds; fails after [ms]. */
    private fun waitFor(what: String, ms: Long = 10_000L, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            Thread.sleep(20L)
        }
    }

    /** The partial file's size, once it has held still for a moment. */
    private fun settledBytes(): Long {
        var before: Long
        var after = partial.length()
        do {
            before = after
            Thread.sleep(300L)
            after = partial.length()
        } while (after != before)
        return after
    }

    @Test
    fun `a download that runs stops at once for a recording and goes on from where it was after`() {
        manager.startSttDownload(SpeechModelId.PARAKEET_V3)
        waitFor("bytes on the phone") { partial.length() >= 300_000L }

        manager.holdForRecording(true)

        val held = states.sttDownloadState.value
        assertTrue("held: $held", held is DownloadState.Paused && held.reason == PauseReason.RECORDING)
        assertEquals(SpeechModelId.PARAKEET_V3, states.sttModel.value)
        val stoppedAt = settledBytes()
        Thread.sleep(500L)
        assertEquals("no bytes while recording", stoppedAt, partial.length())

        manager.holdForRecording(false)

        waitFor("the download going on") { partial.length() > stoppedAt }
        assertEquals(listOf("$encoderPath whole", "$encoderPath bytes=$stoppedAt-"), requests)
        assertTrue(DownloadServiceRules.isRunning(states.sttDownloadState.value))
    }

    @Test
    fun `a download asked for during a recording waits for it`() {
        manager.holdForRecording(true)

        manager.startSttDownload(SpeechModelId.PARAKEET_V3)

        val waiting = states.sttDownloadState.value
        assertTrue("waiting: $waiting", waiting is DownloadState.Paused && waiting.reason == PauseReason.RECORDING)
        Thread.sleep(300L)
        assertEquals(emptyList<String>(), requests)

        manager.holdForRecording(false)
        waitFor("the download starting") { requests.isNotEmpty() }
        assertEquals(listOf("$encoderPath whole"), requests)
    }

    @Test
    fun `a download paused during a recording stays paused after it`() {
        manager.holdForRecording(true)
        manager.startSttDownload(SpeechModelId.PARAKEET_V3)

        manager.cancelSttDownload()
        manager.holdForRecording(false)

        Thread.sleep(300L)
        assertEquals(emptyList<String>(), requests)
        assertEquals(DownloadState.Idle, states.sttDownloadState.value)
    }

    /** A context whose files are in [dir], with preferences in memory. */
    private class TestContext(private val dir: File) : ContextWrapper(null) {
        private val prefs = MemoryPrefs()
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** No downloaded AI model is stored in these tests. */
    private class NoSettings : SettingsRepository {
        private val settings = MutableStateFlow(AppSettings())
        override fun getSettings(): Flow<AppSettings> = settings
        override suspend fun updateSettings(settings: AppSettings) = Unit
        override suspend fun setHomeCategoryId(id: String) = Unit
        override suspend fun setHomeLanguage(language: RecordingLanguage) = Unit
        override suspend fun setMemoryConstrainedDetected(detected: Boolean) = Unit
        override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = Unit
        override suspend fun clearLlmModelPath() = Unit
    }
}
