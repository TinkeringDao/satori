package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecheckFoundTodosUseCaseTest {

    private fun line(id: String, text: String, at: Long) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = at, isComplete = true)

    private fun found(id: String, text: String, segment: String) =
        Todo(id = id, sessionId = "s1", text = text, status = TodoStatus.OPEN, sourceSegmentIds = listOf(segment), createdAt = 0L, updatedAt = 0L)

    @Test
    fun `an idea among the found to-dos becomes a suggestion, for the idea move to tell, and a cued to-do stays`() = runBlocking {
        val segments = listOf(
            line("a", "There should be an option to remove older audio files.", 1L),
            line("b", "I need to call the bank about the loan.", 2L),
            line("c", "It had been", 3L)
        )
        val store = StoredTodos(
            found("idea", "Option to remove older audio files", "a"),
            found("bank", "Call the bank about the loan", "b"),
            found("fragment", "It had been", "c")
        )
        val scheduler = CountingScheduler()

        val changed = RecheckFoundTodosUseCase(store, FakeTranscriptionRepository(session(), segments), scheduler)()

        assertEquals(2, changed)
        assertEquals(
            listOf(TodoStatus.SUGGESTED, TodoStatus.OPEN, TodoStatus.DISMISSED),
            store.stored.map { it.status }
        )
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `a to-do of a line read into English is judged by its reading, one of a line without a reading as it was said`() = runBlocking {
        // Made up: "There should be a way to delete the old audio files." / "The bank must be called."
        val idea = line("a", "പഴയ ഓഡിയോ ഫയലുകൾ ഡിലീറ്റ് ചെയ്യാൻ ഒരു വഴി വേണം.", 1L)
        val bank = line("b", "ബാങ്കിൽ വിളിക്കണം.", 2L)
        val repository = FakeTranscriptionRepository(session(), listOf(idea, bank)).apply {
            readings = listOf(
                SegmentReading("a", "s1", SegmentReading.sourceHashOf(idea.text), "There should be an option to delete the old audio files.", "reader", -0.4, createdAt = 1L)
            )
        }
        val store = StoredTodos(found("idea", "Option to delete the old audio files", "a"), found("bank", "Call the bank", "b"))

        val changed = RecheckFoundTodosUseCase(store, repository, CountingScheduler())()

        // The reading puts an idea forward; the line without one is Malayalam, no cue of which the gate reads.
        assertEquals(1, changed)
        assertEquals(listOf(TodoStatus.SUGGESTED, TodoStatus.OPEN), store.stored.map { it.status })
    }
}
