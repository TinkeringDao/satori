package com.satori.presentation.ideas

import com.satori.domain.model.TodoStatus
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
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

/** What the Ideas page's actions do to the stored ideas, and what the page lists after them. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdeasViewModelTest {

    private val main = StandardTestDispatcher()
    private val todos = IdeaTodos(
        listOf(
            idea("newer", "s1", createdAt = 2_000, segments = listOf("seg-2")),
            idea("older", "s1", createdAt = 1_000)
        )
    )
    private val reminders = CountedReminders()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.openPage(): IdeasViewModel {
        val viewModel = IdeasViewModel(
            todoRepository = todos,
            getAllSessionsUseCase = GetAllSessionsUseCase(ListedRecordings(listOf(recording("s1", createdAt = 500)))),
            todoActions = TodoActions(todos, reminders)
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private fun IdeasViewModel.listed(): List<String> = uiState.value.groups.flatMap { group -> group.ideas.map { it.id } }

    @Test
    fun `a deleted idea leaves the page, and Undo brings it back where it was`() = runTest(main) {
        val page = openPage()
        assertEquals(listOf("newer", "older"), page.listed())

        page.deleteIdea("newer")
        advanceUntilIdle()
        assertEquals(listOf("older"), page.listed())
        assertEquals(TodoStatus.DISMISSED, todos.status("newer"))

        page.restoreIdea("newer")
        advanceUntilIdle()
        assertEquals(listOf("newer", "older"), page.listed())
        assertEquals(TodoStatus.IDEA, todos.status("newer"))
        assertEquals(2, reminders.reschedules)
        assertNull(page.uiState.value.error)
    }

    @Test
    fun `Undo tapped while the delete is still being stored brings the idea back`() = runTest(main) {
        val page = openPage()
        // Each change reads the idea before it writes; run side by side, the Undo would read it
        // before the delete is stored, find it no deleted idea, and do nothing.
        todos.readLatencyMs = 10

        page.deleteIdea("newer")
        page.restoreIdea("newer")
        advanceUntilIdle()

        assertEquals(TodoStatus.IDEA, todos.status("newer"))
        assertEquals(listOf("newer", "older"), page.listed())
    }

    @Test
    fun `an idea made a to-do leaves the page as an open to-do`() = runTest(main) {
        val page = openPage()

        page.makeTodo("older")
        advanceUntilIdle()

        assertEquals(listOf("newer"), page.listed())
        assertEquals(TodoStatus.OPEN, todos.status("older"))
    }
}
