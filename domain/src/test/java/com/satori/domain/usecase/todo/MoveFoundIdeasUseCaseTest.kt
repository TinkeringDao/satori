package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time move of the ideas among the to-dos found before ideas were told apart, on
 * recordings of the shapes the owner's feedback notes had, in other words.
 */
class MoveFoundIdeasUseCaseTest {

    private var clock = 0L

    private fun line(session: String, id: String, text: String) =
        TranscriptionSegment(id = id, sessionId = session, text = text, timestamp = ++clock * 1_000L, isComplete = true)

    private fun found(
        id: String,
        session: String?,
        text: String,
        cites: String,
        status: TodoStatus = TodoStatus.OPEN,
        capture: TodoCapture = TodoCapture.INFERRED,
        remindAt: Long? = null
    ) = Todo(
        id = id, sessionId = session, text = text, status = status, capture = capture, remindAt = remindAt,
        sourceSegmentIds = listOf(cites), createdAt = 0L, updatedAt = 0L
    )

    private val segments = listOf(
        // A screen setting; the cue is in the segment after the one the to-do cites.
        line("r1", "a1", "Test."),
        line("r1", "a2", "The display can switch off."),
        line("r1", "a3", "or switch on. It this should be offered in the settings for people to change."),
        // Keeping audio, and the buzz at the start.
        line("r2", "b1", "Right now every audio file is kept for good."),
        line("r2", "b2", "That eats up the storage over time. So there should be a way to shrink or clear out old audio files, but keep the notes."),
        line("r2", "b3", "uh and maybe after fourteen days."),
        line("r2", "b4", "or thirty or sixty days. A list of choices like that should be offered for keeping or deleting."),
        line("r2", "b5", "And second, when the quick key starts a recording the buzz is very weak. So it's should be much stronger."),
        line("r2", "b6", "that the recording is starting."),
        line("r2", "b7", "I'll add a widget for the cover screen, it would be good."),
        line("r2", "b8", "Note to self: there should be a backup of the notes."),
        // A to-do spoken of, not said.
        line("r3", "c1", "In the recording before, I saw that one of the to do to wash the car in the morning was missed."),
        line("r3", "c2", "And a lot of to dos are being"),
        // A sentence voice detection cut in two.
        line("r4", "d1", "The buzz is weak. So it's should be"),
        line("r4", "d2", "much stronger.")
    )

    private val todos = listOf(
        found("screen", "r1", "Or switch on. It this should be offered in the settings for people to change", cites = "a2", capture = TodoCapture.COMMITMENT),
        found("archive", "r2", "Add option to compress or remove older audio files", cites = "b2", remindAt = 123L),
        found("choices", "r2", "Offer choices for keeping or removing audio files", cites = "b3"),
        found("buzz", "r2", "Make the quick key buzz stronger", cites = "b5", status = TodoStatus.SUGGESTED),
        found("widget", "r2", "Add a widget for the cover screen", cites = "b7", capture = TodoCapture.COMMITMENT),
        found("backup", "r2", "Backup of the notes", cites = "b8", capture = TodoCapture.EXPLICIT),
        found("car", "r3", "Wash the car", cites = "c1", status = TodoStatus.SUGGESTED, remindAt = 456L),
        found("cut", "r4", "Make the buzz stronger", cites = "d2"),
        found("by hand", null, "There should be a dark mode", cites = "none")
    )

    private class Rig(todos: List<Todo>, segments: List<TranscriptionSegment>, failStatusAfter: Int = Int.MAX_VALUE) {
        val store = StoredTodos(*todos.toTypedArray(), failStatusAfter = failStatusAfter)
        val scheduler = CountingScheduler()
        val useCase = MoveFoundIdeasUseCase(store, FakeTranscriptionRepository(session(), segments), scheduler)

        fun statusOf(id: String) = store.stored.single { it.id == id }.status
    }

    @Test
    fun `the ideas move, with the cue in their segment or the next, and the to-dos stay`() = runBlocking {
        val rig = Rig(todos, segments)

        assertEquals(5, rig.useCase())

        for (id in listOf("screen", "archive", "choices", "buzz", "cut")) assertEquals(id, TodoStatus.IDEA, rig.statusOf(id))
        // Said in the first person, noted outright, spoken of, or added by hand: still to-dos.
        assertEquals(TodoStatus.OPEN, rig.statusOf("widget"))
        assertEquals(TodoStatus.OPEN, rig.statusOf("backup"))
        assertEquals(TodoStatus.SUGGESTED, rig.statusOf("car"))
        assertEquals(TodoStatus.OPEN, rig.statusOf("by hand"))
        // A moved to-do reminds no more; one that stays keeps its reminder.
        assertNull(rig.store.stored.single { it.id == "archive" }.remindAt)
        assertEquals(456L, rig.store.stored.single { it.id == "car" }.remindAt)
        assertEquals(1, rig.scheduler.calls)
    }

    @Test
    fun `a second run moves nothing and re-arms nothing`() = runBlocking {
        val rig = Rig(todos, segments)
        rig.useCase()

        assertEquals(0, rig.useCase())
        assertEquals(1, rig.scheduler.calls)
    }

    @Test
    fun `nothing to move re-arms nothing`() = runBlocking {
        val rig = Rig(todos.filter { it.id in setOf("widget", "car") }, segments)

        assertEquals(0, rig.useCase())
        assertEquals(0, rig.scheduler.calls)
    }

    @Test
    fun `a move that fails part way fails the run, and what moved before it still re-arms the reminders`() {
        val rig = Rig(todos, segments, failStatusAfter = 1)

        assertThrows(IllegalStateException::class.java) { runBlocking { rig.useCase() } }

        assertEquals(TodoStatus.IDEA, rig.statusOf("screen"))
        assertEquals(TodoStatus.OPEN, rig.statusOf("archive"))
        assertEquals(1, rig.scheduler.calls)
    }

    @Test
    fun `a move cut off part way leaves no idea with a reminder, and the next run finishes it`() = runBlocking {
        val rig = Rig(todos, segments, failStatusAfter = 1)
        assertThrows(IllegalStateException::class.java) { runBlocking { rig.useCase() } }

        // Its reminder went first: it is still a to-do, with nothing to ring.
        val cut = rig.store.stored.single { it.id == "archive" }
        assertEquals(TodoStatus.OPEN to null, cut.status to cut.remindAt)
        assertTrue(rig.store.stored.none { it.status == TodoStatus.IDEA && it.remindAt != null })

        val rerun = Rig(rig.store.stored, segments)
        assertEquals(4, rerun.useCase())
        assertEquals(TodoStatus.IDEA, rerun.statusOf("archive"))
    }

    @Test
    fun `a to-do noted outright stays, also beside an idea, and talk is no first-person cue`() = runBlocking {
        val lines = listOf(
            line("r5", "e1", "Reminder: there should be a backup of the notes."),
            line("r5", "e2", "I have to say, it should be a bigger clock on the cover.")
        )
        val rig = Rig(
            listOf(
                found("noted", "r5", "Backup of the notes", cites = "e1", capture = TodoCapture.EXPLICIT),
                found("clock", "r5", "Make the cover clock bigger", cites = "e2")
            ),
            lines
        )

        assertEquals(1, rig.useCase())

        assertEquals(TodoStatus.OPEN, rig.statusOf("noted"))
        assertEquals(TodoStatus.IDEA, rig.statusOf("clock"))
    }

    @Test
    fun `an idea is told by the reading of a line read into English, and a to-do of a line without one stays`() = runBlocking {
        // Made up: "The buzz at the start should be much stronger." / "The screen goes dark too soon."
        val buzz = line("m", "x1", "തുടക്കത്തിലെ വൈബ്രേഷൻ കൂടുതൽ ശക്തമായിരിക്കണം.")
        val screen = line("m", "x2", "സ്ക്രീൻ വളരെ വേഗം ഇരുളുന്നു.")
        val repository = FakeTranscriptionRepository(session(), listOf(buzz, screen)).apply {
            readings = listOf(
                SegmentReading("x1", "m", SegmentReading.sourceHashOf(buzz.text), "There should be an option to make the buzz at the start much stronger.", "reader", -0.4, createdAt = 1L)
            )
        }
        val store = StoredTodos(found("t1", "m", "Make the buzz stronger", cites = "x1"), found("t2", "m", "Fix the dark screen", cites = "x2"))

        val moved = MoveFoundIdeasUseCase(store, repository, CountingScheduler())()

        assertEquals(1, moved)
        assertEquals(listOf(TodoStatus.IDEA, TodoStatus.OPEN), store.stored.map { it.status })
    }
}
