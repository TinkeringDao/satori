package com.satori.presentation.sessions

import androidx.lifecycle.SavedStateHandle
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import com.satori.presentation.ideas.CountedReminders
import com.satori.presentation.ideas.IdeaTodos
import com.satori.presentation.ideas.ListedRecordings
import com.satori.presentation.ideas.idea
import com.satori.presentation.ideas.recording
import com.satori.presentation.main.ActiveRecorder
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What home's ViewModel puts together: the count of new ideas from what the screen tells it, the
 * recordings being finished, the recording being made, and Move to ideas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val main = StandardTestDispatcher()
    private val todos = IdeaTodos(
        listOf(
            idea("seen-before", "s1", createdAt = 500),
            idea("found-since", "s1", createdAt = 2_000),
            Todo(
                id = "venue",
                sessionId = "s1",
                text = "Book the venue for the course",
                status = TodoStatus.OPEN,
                remindAt = 9_000,
                createdAt = 200,
                updatedAt = 200
            )
        )
    )
    private val queue = StatusQueue()
    private val recorder = ActiveRecorder()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Home over [recordings], whose owner last saw the ideas row up to 1,000 ([saved]). */
    private fun TestScope.openHome(
        recordings: List<SessionListItem> = listOf(recording("s1", createdAt = 100)),
        saved: SavedStateHandle = SavedStateHandle(mapOf(SessionsViewModel.KEY_IDEAS_SEEN_AT to 1_000L))
    ): SessionsViewModel {
        val store = ListedRecordings(recordings)
        val home = SessionsViewModel(
            getAllSessionsUseCase = GetAllSessionsUseCase(store),
            createSessionUseCase = CreateSessionUseCase(store),
            settingsRepository = FreshSettings(),
            categoryRepository = NoCategories(),
            todoRepository = todos,
            todoActions = TodoActions(todos, CountedReminders()),
            activeRecorder = recorder,
            finishingQueue = queue,
            speechModels = SpeechModelsHere(),
            savedStateHandle = saved,
            firstRun = FakeFirstRunCoordinator(),
            nudgeLedger = StoredNudgeLedger(),
            phone = TestPhone(clock = { testScheduler.currentTime })
        )
        backgroundScope.launch { home.uiState.collect {} }
        advanceUntilIdle()
        return home
    }

    private val SessionsViewModel.ideasRow: IdeasRow? get() = uiState.value.ideasRow

    private fun SessionsViewModel.listed(): List<String> = uiState.value.recordings.map { it.session.id }

    @Test
    fun `ideas the owner saw are no longer new once home was left, and those found meanwhile are`() = runTest(main) {
        val home = openHome()
        assertEquals(IdeasRow(count = 2, newCount = 1), home.ideasRow)

        home.homeStarted(nowMs = 3_000)
        home.ideasRowSeen(sinceMs = 3_000)
        home.homeStopped(nowMs = 4_000)
        todos.add(idea("found-while-away", "s1", createdAt = 5_000))
        home.homeStarted(nowMs = 60_000)
        advanceUntilIdle()

        assertEquals(IdeasRow(count = 3, newCount = 1), home.ideasRow)
    }

    @Test
    fun `a fold, which stops home for a moment, keeps the new count`() = runTest(main) {
        val home = openHome()
        home.homeStarted(nowMs = 3_000)
        home.ideasRowSeen(sinceMs = 3_000)

        home.homeStopped(nowMs = 4_000)
        home.homeStarted(nowMs = 4_300)
        advanceUntilIdle()
        assertEquals(IdeasRow(count = 2, newCount = 1), home.ideasRow)

        home.homeStopped(nowMs = 10_000)
        home.homeStarted(nowMs = 30_000)
        advanceUntilIdle()
        assertEquals(IdeasRow(count = 2, newCount = null), home.ideasRow)
    }

    @Test
    fun `home that never showed the row, as behind the lock screen, keeps its ideas new`() = runTest(main) {
        val home = openHome()

        home.homeStarted(nowMs = 3_000)
        home.homeStopped(nowMs = 3_200)
        home.homeStarted(nowMs = 60_000)
        advanceUntilIdle()

        assertEquals(IdeasRow(count = 2, newCount = 1), home.ideasRow)
    }

    @Test
    fun `opening the ideas from the row leaves none new`() = runTest(main) {
        val home = openHome()

        home.ideasRowTapped()
        home.homeStopped(nowMs = 4_000)
        advanceUntilIdle()

        assertEquals(IdeasRow(count = 2, newCount = null), home.ideasRow)
    }

    @Test
    fun `what was seen outlasts a restored process`() = runTest(main) {
        val saved = SavedStateHandle(mapOf(SessionsViewModel.KEY_IDEAS_SEEN_AT to 1_000L))
        val before = openHome(saved = saved)
        before.homeStarted(nowMs = 3_000)
        before.ideasRowSeen(sinceMs = 3_000)
        before.homeStopped(nowMs = 4_000)

        // Android ended the process in the background and brings home back with its saved state.
        val restored = openHome(saved = saved)
        restored.homeStarted(nowMs = 600_000)
        advanceUntilIdle()

        assertEquals(IdeasRow(count = 2, newCount = null), restored.ideasRow)
    }

    @Test
    fun `a recording being finished says how far it has come, and nothing once it is done`() = runTest(main) {
        val home = openHome()
        val notes = FinishingStatus("s1", FinishingStatus.Phase.RUNNING, step = FinishStep.Notes)
        val waiting = FinishingStatus("s2", FinishingStatus.Phase.WAITING, position = 1)

        queue.statuses.value = listOf(notes, waiting)
        advanceUntilIdle()
        assertEquals(mapOf("s1" to notes, "s2" to waiting), home.uiState.value.finishing)

        queue.statuses.value = emptyList()
        advanceUntilIdle()
        assertEquals(emptyMap<String, FinishingStatus>(), home.uiState.value.finishing)
    }

    @Test
    fun `the recording being made is listed while it holds nothing yet, and only then`() = runTest(main) {
        val home = openHome(listOf(recording("b", createdAt = 3_000, hasTranscript = false), recording("s1", createdAt = 100)))
        val owner = Any()
        assertEquals(listOf("s1"), home.listed())

        recorder.open(owner, "b", ActiveRecorder.Activity.STARTING)
        advanceUntilIdle()
        assertEquals(listOf("s1"), home.listed())

        recorder.report(owner, "b", ActiveRecorder.Activity.RECORDING)
        advanceUntilIdle()
        assertEquals(listOf("b", "s1"), home.listed())

        recorder.leave(owner)
        advanceUntilIdle()
        assertEquals(listOf("s1"), home.listed())
    }

    @Test
    fun `Move to ideas makes the open to-do an idea without its reminder, and closes its sheet`() = runTest(main) {
        val home = openHome()
        home.openTodo(home.uiState.value.openTodos.single())

        home.moveOpenTodoToIdeas()
        assertNull(home.sheetTodo.value)
        advanceUntilIdle()

        assertEquals(TodoStatus.IDEA, todos.status("venue"))
        assertNull(todos.remindAt("venue"))
        assertEquals(emptyList<Todo>(), home.uiState.value.openTodos)
        // It keeps the time it was found: one idea more, and no new one.
        assertEquals(IdeasRow(count = 3, newCount = 1), home.ideasRow)
    }
}
