package com.satori.presentation.sessions

import androidx.lifecycle.SavedStateHandle
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordTarget
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import com.satori.presentation.ideas.CountedReminders
import com.satori.presentation.ideas.IdeaTodos
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.CategoriesOnPhone
import com.satori.presentation.main.starterCategories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home's Record as in a language: a recording started from the sheet is made in the language
 * chosen above the categories, which the one-tap Record then records in; Malayalam only while its
 * model is on the phone, looked for again as home shows; Translate in English or German, leaving
 * the language as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecordLanguageTest {

    private val main = StandardTestDispatcher()
    private val recordings = CreatedRecordings()
    private val models = SpeechModelsHere(malayalam = true)
    private val meeting = RecordTarget.Category(starterCategories[1])

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.openHome(settings: HomeSettings = HomeSettings(), speechModels: SpeechModelRepository = models): SessionsViewModel {
        val todos = IdeaTodos(emptyList())
        val home = SessionsViewModel(
            getAllSessionsUseCase = GetAllSessionsUseCase(recordings),
            createSessionUseCase = CreateSessionUseCase(recordings),
            settingsRepository = settings,
            categoryRepository = CategoriesOnPhone(*starterCategories.toTypedArray()),
            todoRepository = todos,
            todoActions = TodoActions(todos, CountedReminders()),
            activeRecorder = ActiveRecorder(),
            finishingQueue = StatusQueue(),
            speechModels = speechModels,
            savedStateHandle = SavedStateHandle(),
            firstRun = FakeFirstRunCoordinator(),
            nudgeLedger = StoredNudgeLedger(),
            phone = TestPhone(clock = { testScheduler.currentTime })
        )
        backgroundScope.launch { home.uiState.collect {} }
        advanceUntilIdle()
        return home
    }

    private fun TestScope.start(home: SessionsViewModel, target: RecordTarget, language: RecordingLanguage?): String? {
        var started: String? = null
        home.startRecording(target, language, onSessionCreated = { started = it }, onFailed = {})
        advanceUntilIdle()
        return started
    }

    @Test
    fun `a recording started in Malayalam is made in it, and the one-tap Record records in it from then on`() = runTest(main) {
        val settings = HomeSettings()
        val home = openHome(settings)
        assertTrue(home.uiState.value.malayalamOnPhone)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, home.uiState.value.language)

        val started = start(home, meeting, RecordingLanguage.MALAYALAM)

        assertEquals("new1", started)
        val created = recordings.created.single()
        assertEquals(RecordingLanguage.MALAYALAM, created.language)
        assertEquals("meeting", created.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, created.mode)
        assertEquals(listOf(RecordingLanguage.MALAYALAM), settings.languageWrites)
        assertEquals(RecordingLanguage.MALAYALAM, home.uiState.value.language)
        assertEquals(meeting, home.uiState.value.target)
    }

    @Test
    fun `while the Malayalam model isn't on the phone the one-tap Record records in English or German, and again in Malayalam once it is back`() = runTest(main) {
        models.malayalam = false
        val home = openHome(HomeSettings(AppSettings(homeLanguage = RecordingLanguage.MALAYALAM)))

        assertFalse(home.uiState.value.malayalamOnPhone)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, home.uiState.value.language)
        // The one-tap Record: no language picked, the stored one, as far as the phone can record in it.
        start(home, meeting, language = null)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.created.single().language)

        // Downloaded in Settings: home looks again as it shows.
        models.malayalam = true
        home.homeStarted(nowMs = 60_000)
        advanceUntilIdle()

        assertTrue(home.uiState.value.malayalamOnPhone)
        assertEquals(RecordingLanguage.MALAYALAM, home.uiState.value.language)
        start(home, meeting, language = null)
        assertEquals(RecordingLanguage.MALAYALAM, recordings.created.last().language)
    }

    @Test
    fun `the one-tap Record records in the language stored and stores none`() = runTest(main) {
        val settings = HomeSettings(AppSettings(homeLanguage = RecordingLanguage.MALAYALAM))
        val home = openHome(settings)

        start(home, meeting, language = null)

        assertEquals(RecordingLanguage.MALAYALAM, recordings.created.single().language)
        assertTrue(settings.languageWrites.isEmpty())
    }

    @Test
    fun `Malayalam picked as its model goes records in English or German`() = runTest(main) {
        val home = openHome()
        models.malayalam = false
        home.homeStarted(nowMs = 60_000)
        advanceUntilIdle()

        start(home, meeting, RecordingLanguage.MALAYALAM)

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.created.single().language)
    }

    @Test
    fun `a model deleted since home last looked is looked for again as Record starts`() = runTest(main) {
        val home = openHome(HomeSettings(AppSettings(homeLanguage = RecordingLanguage.MALAYALAM)))
        assertTrue(home.uiState.value.malayalamOnPhone)

        // Deleted in Settings, and Record tapped before home looked again.
        models.malayalam = false
        start(home, meeting, language = null)

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordings.created.single().language)
        assertFalse(home.uiState.value.malayalamOnPhone)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, home.uiState.value.language)
        // Back on the phone: the next start records in Malayalam without waiting for home to look.
        models.malayalam = true
        start(home, meeting, RecordingLanguage.MALAYALAM)
        assertEquals(RecordingLanguage.MALAYALAM, recordings.created.last().language)
    }

    /** The Malayalam model on the phone, home's first look at it, as it opens, held until [firstLook] completes. */
    private class SlowFirstLook : SpeechModelRepository {
        val firstLook = CompletableDeferred<Unit>()
        private var looks = 0

        override suspend fun isInstalled(model: SpeechModelId): Boolean {
            if (looks++ == 0) firstLook.await()
            return true
        }

        override fun sizeBytes(model: SpeechModelId): Long = 0L
    }

    @Test
    fun `a one-tap Record right after home opens records in Malayalam before home's own look is done`() = runTest(main) {
        val slow = SlowFirstLook()
        val home = openHome(HomeSettings(AppSettings(homeLanguage = RecordingLanguage.MALAYALAM)), speechModels = slow)
        assertFalse(home.uiState.value.malayalamOnPhone)

        start(home, meeting, language = null)

        assertEquals(RecordingLanguage.MALAYALAM, recordings.created.single().language)
        slow.firstLook.complete(Unit)
        advanceUntilIdle()
        assertTrue(home.uiState.value.malayalamOnPhone)
    }

    @Test
    fun `Translate records in English or German and leaves the language as it was`() = runTest(main) {
        val settings = HomeSettings(AppSettings(homeLanguage = RecordingLanguage.MALAYALAM))
        val home = openHome(settings)

        start(home, RecordTarget.Translate, RecordingLanguage.MALAYALAM)

        val created = recordings.created.single()
        assertEquals(RecordingMode.REAL_TIME_TRANSLATION, created.mode)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, created.language)
        assertTrue(settings.languageWrites.isEmpty())
        assertEquals(RecordingLanguage.MALAYALAM, settings.settings.value.homeLanguage)
    }

    @Test
    fun `the language a start records in, by target and by the model on the phone`() {
        assertEquals(RecordingLanguage.MALAYALAM, recordingLanguageOf(meeting, RecordingLanguage.MALAYALAM, malayalamOnPhone = true))
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordingLanguageOf(meeting, RecordingLanguage.MALAYALAM, malayalamOnPhone = false))
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordingLanguageOf(RecordTarget.Translate, RecordingLanguage.MALAYALAM, malayalamOnPhone = true))
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recordingLanguageOf(meeting, RecordingLanguage.ENGLISH_OR_GERMAN, malayalamOnPhone = true))
    }
}
