package com.satori.presentation.sessiondetails

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.AudioInUse
import com.satori.presentation.main.FakeProcessing
import com.satori.presentation.main.KeptAudio
import com.satori.presentation.main.Pause
import com.satori.presentation.main.RecordingsOnPhone
import com.satori.presentation.main.ScreenEngine
import com.satori.presentation.main.SpeechModelsOnPhone
import com.satori.presentation.main.SpeechOnPhone
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.TodosOnPhone
import com.satori.presentation.sessions.StatusQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transcribe again on the recording's page gives way to a recorder that leaves IDLE (the quick
 * key): it stops after the stretch the speech model is on and the transcript stays, or, stopped as
 * the new transcript is stored, lets the store end; the page says so once when it is opened again,
 * the first time only. A transcript in another language than the old one asks the page to make
 * the text again once the AI is free, where the page can, and leaves the old name and the to-dos
 * and ideas to that work, which replaces each once it is made (the 0.6.1 memory review). Built with
 * the use cases the app runs, over doubles of the speech model and the database, and a real
 * [ActiveRecorder] that leaves IDLE mid-run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingAudioViewModelTest {

    private val main = StandardTestDispatcher()
    private val gate = AiWorkGate()
    private val recorders = ActiveRecorder()
    private val stopped = StoppedWork()
    private val processing = FakeProcessing()
    private val usage = AudioInUse()
    private val queue = StatusQueue()
    private val todos = TodosOnPhone()
    private val audio = KeptAudio(RecordingAudio("s1", "s1.wav", durationMs = 20_000L))
    private val speech = SpeechOnPhone(
        listOf(
            SpokenLine("The workshop moves to the new building next week.", AudioSpan(0L, 4_000L)),
            SpokenLine("The old shelves go to the basement on Friday.", AudioSpan(4_500L, 8_000L))
        )
    )
    private val recordings = RecordingsOnPhone(TranscriptionSession("s1", "Workshop plans", createdAt = 1_000L, lastModifiedAt = 1_000L))
    private val before = "The work shop moves to the knew building next week."

    // The idea the old transcript gave, untouched, and a suggestion of it the owner kept.
    private val idea = Todo("idea", "s1", "Shelves for the knew building", status = TodoStatus.IDEA, capture = TodoCapture.INFERRED, createdAt = 2_000L, updatedAt = 2_000L)
    private val kept = Todo("kept", "s1", "Book the van for the move", status = TodoStatus.OPEN, capture = TodoCapture.INFERRED, createdAt = 2_000L, updatedAt = 300_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        recordings.said("s1", before)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The audio of recording s1 on its page, as navigation opens it, with the speech models [installed]. */
    private fun TestScope.page(vararg installed: SpeechModelId = arrayOf(SpeechModelId.PARAKEET_V3)): RecordingAudioViewModel {
        val page = RecordingAudioViewModel(
            audioRepository = audio,
            transcribeAgainUseCase = TranscribeAgainUseCase(speech, recordings, todos, audio),
            transcriptionRepository = recordings,
            speechModels = SpeechModelsOnPhone(*installed),
            llmRepository = ScreenEngine(gate),
            memory = DeviceMemory { 8L * 1024 * 1024 * 1024 },
            player = RecordingPlayer(ContextWrapper(null)),
            processingService = processing,
            aiGate = gate,
            finishingQueue = queue,
            audioUsage = usage,
            activeRecorder = recorders,
            stoppedWork = stopped,
            savedStateHandle = SavedStateHandle(mapOf("sessionId" to "s1"))
        )
        advanceUntilIdle()
        return page
    }

    /** What [page] shows once, collected as the screen collects it. */
    private fun TestScope.noticesOf(page: RecordingAudioViewModel): List<AudioNotice> {
        val told = mutableListOf<AudioNotice>()
        backgroundScope.launch { page.notices.collect { told += it } }
        // runCurrent, not advanceUntilIdle: that one leaves background work alone once no other is left.
        runCurrent()
        return told
    }

    /** Transcribe again, which holds the AI, has ended and let go of it, on whichever thread its parts ran. */
    private suspend fun TestScope.workEnded() {
        gate.holder.first { it == null }
        advanceUntilIdle()
        runCurrent()
    }

    @Test
    fun `a recording that starts stops Transcribe again mid-recognition, the transcript stays and the page opened again says so once`() = runTest(main) {
        val recognition = Pause().also { speech.recognition = it }
        val page = page()

        page.transcribeAgain(SpeechModelId.PARAKEET_V3)
        recognition.reached.await()
        // A quick-key press: its recorder counts as starting at once, before its screen is up.
        recorders.expect("new")
        workEnded()

        assertEquals(listOf(before), recordings.linesOf("s1").map { it.text })
        assertEquals(0, recordings.stores)
        assertTrue(usage.inUse().isEmpty())
        assertTrue(processing.holders.isEmpty())
        assertNull(page.uiState.value.transcription)
        assertNull(page.uiState.value.error)
        assertTrue(noticesOf(page).isEmpty())
        assertEquals(listOf(AudioNotice.TranscribeStopped), noticesOf(page()))
        assertTrue(noticesOf(page()).isEmpty())
    }

    @Test
    fun `a transcript made again in Malayalam asks for the text once the AI is free, and leaves the old name and finds to it`() = runTest(main) {
        todos.upsert(idea)
        todos.upsert(kept)
        val page = page(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML)
        val told = mutableListOf<Pair<AudioNotice, String?>>()
        // Unconfined: each notice is taken as it is sent, so the gate is seen as it is then.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { page.notices.collect { told += it to gate.holder.value } }

        page.showTranscribeChoice()
        val choice = page.uiState.first { it.transcribeChoice != null }.transcribeChoice.orEmpty()
        assertTrue(choice.single { it.model == SpeechModelId.INDICCONFORMER_ML }.makesTextAgain)
        assertFalse(choice.single { it.model == SpeechModelId.PARAKEET_V3 }.makesTextAgain)
        val recognition = Pause().also { speech.recognition = it }
        page.transcribeAgain(SpeechModelId.INDICCONFORMER_ML, makesTextAgain = true)
        recognition.reached.await()
        recognition.resume()
        workEnded()

        // The transcript is in, and the text is asked for only once the gate is open again.
        assertEquals(listOf(AudioNotice.Transcribed to AiWorkGate.TRANSCRIBE, AudioNotice.TextToMakeAgain to null), told)
        assertEquals(RecordingLanguage.MALAYALAM, recordings.sessionOf("s1")?.language)
        // Nothing is made in their place yet: the old name and finds stay for the page's work.
        assertEquals("Workshop plans", recordings.sessionOf("s1")?.name)
        assertEquals(listOf("idea", "kept"), todos.stored.map { it.id })
    }

    @Test
    fun `where the page can't make the text again, a transcript in Malayalam leaves the name and the finds`() = runTest(main) {
        todos.upsert(idea)
        val page = page(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML)
        val recognition = Pause().also { speech.recognition = it }

        page.transcribeAgain(SpeechModelId.INDICCONFORMER_ML, makesTextAgain = false)
        recognition.reached.await()
        recognition.resume()
        workEnded()

        assertEquals(listOf(AudioNotice.Transcribed), noticesOf(page))
        assertEquals("Workshop plans", recordings.sessionOf("s1")?.name)
        assertEquals(listOf("idea"), todos.stored.map { it.id })
    }

    @Test
    fun `a transcript made again by another model of the same language leaves the text as it is`() = runTest(main) {
        todos.upsert(idea)
        val page = page(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML)
        val recognition = Pause().also { speech.recognition = it }

        page.transcribeAgain(SpeechModelId.PARAKEET_V3, makesTextAgain = true)
        recognition.reached.await()
        recognition.resume()
        workEnded()

        assertEquals(listOf(AudioNotice.Transcribed), noticesOf(page))
        assertEquals("Workshop plans", recordings.sessionOf("s1")?.name)
        assertEquals(listOf("idea"), todos.stored.map { it.id })
    }

    @Test
    fun `a Malayalam recording whose transcript is Parakeet's has its text made again by the Malayalam model too`() = runTest(main) {
        // Its language was changed to Malayalam on its page, which left Parakeet's transcript.
        recordings.recorded(recordings.sessionOf("s1")!!.copy(language = RecordingLanguage.MALAYALAM))
        todos.upsert(idea)
        val page = page(SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML)
        val recognition = Pause().also { speech.recognition = it }

        page.showTranscribeChoice()
        val choice = page.uiState.first { it.transcribeChoice != null }.transcribeChoice.orEmpty()
        assertTrue(choice.all { it.makesTextAgain })
        assertFalse(choice.single { it.model == SpeechModelId.INDICCONFORMER_ML }.changesLanguage)
        page.transcribeAgain(SpeechModelId.INDICCONFORMER_ML, makesTextAgain = true)
        recognition.reached.await()
        recognition.resume()
        workEnded()

        assertEquals(listOf(AudioNotice.Transcribed, AudioNotice.TextToMakeAgain), noticesOf(page))
        assertEquals("Workshop plans", recordings.sessionOf("s1")?.name)
        assertEquals(listOf("idea"), todos.stored.map { it.id })
    }

    @Test
    fun `a recording that starts as the new transcript is stored lets the store end, and tells nothing`() = runTest(main) {
        val store = Pause().also { recordings.store = it }
        page().transcribeAgain(SpeechModelId.PARAKEET_V3)
        store.reached.await()
        recorders.expect("new")
        advanceUntilIdle()

        store.resume()
        workEnded()

        assertEquals(1, recordings.stores)
        assertTrue(recordings.linesOf("s1").none { it.text == before })
        assertEquals(1, audio.placed.size)
        assertTrue(usage.inUse().isEmpty())
        assertTrue(processing.holders.isEmpty())
        assertTrue(noticesOf(page()).isEmpty())
    }
}
