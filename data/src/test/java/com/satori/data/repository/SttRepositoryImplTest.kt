package com.satori.data.repository

import com.satori.data.audio.FakeCodec
import com.satori.data.audio.RecordingAudioFiles
import com.satori.data.audio.RecordingAudioStore
import com.satori.data.audio.Wav
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.RecognitionResult
import com.satori.data.datasource.SttDataSource
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Transcribe again of a recording's audio: compressed audio goes to the recognizer through the
 * decoder. And whether the live speech model is in memory, which the finisher of recordings waits on.
 */
class SttRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The speech recognizer of a file: says what it was given, and whether that was a WAV Satori wrote. */
    private class Recognizer : SttDataSource {
        var given: File? = null
        var givenWasOurs = false
        var failure: Exception? = null

        /** The live speech model in memory, as the data source sets and clears it. */
        override val liveModelLoaded = MutableStateFlow(false)

        /** Which live speech model that is. */
        override var liveModelInMemory: SpeechModelId? = null

        override suspend fun transcribeFile(
            wav: File,
            model: SpeechModelId,
            language: String?,
            onProgress: (Float) -> Unit
        ): List<RecognitionResult> {
            given = wav
            givenWasOurs = wav.isFile && Wav.isOurs(wav.readBytes().copyOf(Wav.HEADER_BYTES))
            failure?.let { throw it }
            return listOf(RecognitionResult("Call the plumber about the leak", isComplete = true, span = AudioSpan(100, 900)))
        }

        override val audioLevel: StateFlow<Float> get() = unused()
        override val captureState: StateFlow<CaptureState> get() = unused()
        override suspend fun initialize(): Result<Unit> = unused()
        /** The audio files and the language each capture was started with. */
        val captures = mutableListOf<Pair<RecordingAudioFiles?, CaptureLanguage>>()

        override fun startRecording(audio: RecordingAudioFiles?, language: CaptureLanguage): Flow<RecognitionResult> {
            captures += audio to language
            return emptyFlow()
        }
        override fun mayHaveHeardSpeech(): Boolean = unused()
        override suspend fun stopRecording(): Unit = unused()
        override suspend fun releaseModel(): Unit = unused()

        private fun unused(): Nothing = error("not used by these tests")
    }

    private val recognizer = Recognizer()
    private val codec = FakeCodec()
    private lateinit var decodedDir: File
    private lateinit var repository: SttRepositoryImpl

    /** A second of audio, compressed. */
    private lateinit var compressed: RecordingAudio

    /** A second of audio, as the capture wrote it. */
    private lateinit var wav: RecordingAudio

    @Before
    fun setUp() = runBlocking {
        val dir = File(tmp.root, "audio").apply { mkdirs() }
        decodedDir = File(tmp.root, "cache/transcribe")
        val store = RecordingAudioStore(dir, decodedDir, codec, codec, info = {}, warn = { _, _ -> })
        repository = SttRepositoryImpl(recognizer, store)
        File(dir, "rec-1.wav").writeBytes(Wav.header(32_000) + ByteArray(32_000))
        File(dir, "rec-2.wav").writeBytes(Wav.header(32_000) + ByteArray(32_000))
        store.compress("rec-1").getOrThrow()
        compressed = checkNotNull(store.find("rec-1"))
        wav = checkNotNull(store.find("rec-2"))
    }

    @Test
    fun `compressed audio is decoded into the cache for the recognizer, and the copy is deleted after`() = runBlocking {
        assertEquals(AudioEncoding.AAC, compressed.encoding)

        val lines = repository.transcribeAudio(compressed, SpeechModelId.LIVE, language = null) {}

        assertEquals(File(decodedDir, "rec-1.wav"), recognizer.given)
        assertTrue(recognizer.givenWasOurs)
        assertEquals(listOf(File(compressed.path)), codec.decoded)
        assertFalse(File(decodedDir, "rec-1.wav").exists())
        assertEquals(listOf(SpokenLine("Call the plumber about the leak", AudioSpan(100, 900))), lines)
    }

    @Test
    fun `the decoded copy is deleted also when the recognizer fails`() = runBlocking {
        recognizer.failure = IOException("The speech model failed")

        val result = runCatching { repository.transcribeAudio(compressed, SpeechModelId.LIVE, language = null) {} }

        assertTrue(result.isFailure)
        assertFalse(File(decodedDir, "rec-1.wav").exists())
    }

    @Test
    fun `a decoded copy that a process death left behind goes with the next Transcribe again`() = runBlocking {
        decodedDir.mkdirs()
        val leftover = File(decodedDir, "rec-9.wav").apply { writeBytes(Wav.header(32_000) + ByteArray(32_000)) }

        repository.transcribeAudio(compressed, SpeechModelId.LIVE, language = null) {}

        assertFalse(leftover.exists())
        assertTrue(decodedDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a WAV goes to the recognizer as it is, and stays`() = runBlocking {
        repository.transcribeAudio(wav, SpeechModelId.LIVE, language = null) {}

        assertEquals(File(wav.path), recognizer.given)
        assertTrue(codec.decoded.isEmpty())
        assertTrue(File(wav.path).exists())
    }

    @Test
    fun `a capture is given the recorder's language, whose live model transcribes it, with the audio to keep or none`() = runBlocking {
        val malayalam = object : CaptureLanguage {
            override val current = RecordingLanguage.MALAYALAM
        }

        repository.startStreaming("rec-3", keepAudio = true, language = malayalam).toList()
        repository.startStreaming("rec-4", keepAudio = false).toList()

        val (kept, language) = recognizer.captures[0]
        assertEquals("rec-3", kept?.wav?.nameWithoutExtension)
        assertSame(malayalam, language)
        // Without a language, as before languages could be chosen: English or German.
        assertNull(recognizer.captures[1].first)
        assertSame(CaptureLanguage.ENGLISH_OR_GERMAN, recognizer.captures[1].second)
    }

    @Test
    fun `the live speech model can grow by Parakeet v3 less the Malayalam model, and not past Parakeet v3`() {
        val parakeet = SpeechModels.PARAKEET_V3.bytes
        val malayalam = SpeechModels.INDICCONFORMER_ML.bytes

        recognizer.liveModelInMemory = SpeechModelId.INDICCONFORMER_ML
        assertEquals(parakeet - malayalam, repository.liveModelGrowthBytes())
        // 508 MB as the logs count them: Parakeet v3's 670 million bytes of files, the Malayalam model's 138 million.
        assertEquals(508L, repository.liveModelGrowthBytes() / 1_048_576L)
        recognizer.liveModelInMemory = SpeechModelId.PARAKEET_V3
        assertEquals(0L, repository.liveModelGrowthBytes())
        // None in memory: a capture's whole model is still to come.
        recognizer.liveModelInMemory = null
        assertEquals(parakeet, repository.liveModelGrowthBytes())
    }

    @Test
    fun `whether the live speech model is in memory is the data source's, as it loads and as it is freed`() {
        assertFalse(repository.liveModelLoaded.value)

        recognizer.liveModelLoaded.value = true
        assertTrue(repository.liveModelLoaded.value)

        recognizer.liveModelLoaded.value = false
        assertFalse(repository.liveModelLoaded.value)
    }
}
