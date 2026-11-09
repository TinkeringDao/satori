package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.time.RuleBasedTimeParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The todos said outright, found without the model ([ExplicitTodoSpotter]) and joined with the
 * model's in [ExtractTodosUseCase], with the real time parser and a fake model.
 *
 * The anchor: the recordings were made on Saturday 10 October 2026 just before midnight, Berlin
 * time, so "tomorrow" is Sunday 11 October and "Friday" is 16 October.
 */
class ExplicitTodoSpotterTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private var ids = 0

    private fun useCase(llm: LlmRepository) =
        FoundTodos(ExtractTodosUseCase(llm, FakeResourceProvider(), RuleBasedTimeParser(), now = { NOW }, newId = { "todo-${++ids}" }))

    /** Epoch ms of a wall-clock time in Berlin, on Saturday 10 October 2026 unless [day] says otherwise. */
    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 10): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun ms(day: Int, hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = time, isComplete = true)

    private fun todo(
        segment: String,
        text: String,
        whenId: String = "none",
        kind: String = "task",
        capture: String = "commitment",
        certainty: String = "confirmed"
    ) = """{"segment": "$segment", "text": "$text", "when": "$whenId", "kind": "$kind", "capture": "$capture", "certainty": "$certainty"}"""

    private fun answer(vararg todos: String) = """{"todos": [${todos.joinToString(", ")}]}"""

    private fun nothing() = FakeLlmRepository(answer = answer())

    /** The todos in [texts], one segment each, said 20 seconds apart from Saturday 23:00, with a model that finds none. */
    private fun spotted(vararg texts: String, language: String = "en"): List<Todo> = runBlocking {
        val segments = texts.mapIndexed { i, text -> seg("s$i", text, at(23, 0) + i * 20_000L) }
        useCase(nothing())("s1", segments, zone, language)
    }

    /** The texts found in each of [sentences] on its own. */
    private fun textsOf(vararg sentences: String, language: String = "en"): List<List<String>> =
        sentences.map { sentence -> spotted(sentence, language = language).map { it.text } }

    /** The one todo found in [sentence] on its own. */
    private fun one(sentence: String, language: String = "en"): Todo = spotted(sentence, language = language).single()

    private fun Todo.withoutId() = copy(id = "")

    // --- The recordings from the phone, 10 October 2026 ------------------------------------------

    private val english = listOf(
        seg(
            "en1",
            "Okay, quick note after the call with the landlord. He agreed to fix the heating before the end of the " +
                "month, and the rent stays the same until March. Remind me to call the bank tomorrow at ten about the loan.",
            at(23, 59, 0)
        ),
        seg("en2", "The dentist appointment is on Friday at 3 pm. I also need to buy a birthday present for Anna this weekend.", at(23, 59, 25))
    )

    private val german = listOf(
        seg(
            "de1",
            "Kurze Notiz nach dem Gespräch mit dem Vermieter. Die Heizung wird bis Ende des Monats repariert, und die " +
                "Miete bleibt bis März gleich. Ich muss morgen um neun beim Arzt anrufen.",
            at(23, 59, 0)
        ),
        seg("de2", "Nächsten Freitag ist das Teammeeting um halb drei. Außerdem soll ich Anna bis Sonntag die Fotos vom Urlaub schicken.", at(23, 59, 25))
    )

    private fun found(
        text: String,
        segment: String,
        kind: TodoKind = TodoKind.TASK,
        capture: TodoCapture,
        dueAt: Long?,
        dueHasTime: Boolean,
        timePhrase: String?,
        remindAt: Long?,
        certainty: TodoCertainty = TodoCertainty.CONFIRMED
    ) = Todo(
        id = "", sessionId = "s1", text = text, kind = kind, status = TodoStatus.OPEN, certainty = certainty,
        capture = capture, dueAt = dueAt, dueHasTime = dueHasTime, timePhrase = timePhrase, remindAt = remindAt,
        sourceSegmentIds = listOf(segment), createdAt = NOW, updatedAt = NOW
    )

    private val englishTodos = listOf(
        found(
            "Call the bank about the loan", "en1", capture = TodoCapture.EXPLICIT,
            dueAt = ms(11, 10), dueHasTime = true, timePhrase = "tomorrow at ten", remindAt = ms(11, 10)
        ),
        found(
            "Dentist appointment", "en2", kind = TodoKind.APPOINTMENT, capture = TodoCapture.EXPLICIT,
            dueAt = ms(16, 15), dueHasTime = true, timePhrase = "on Friday at 3 pm", remindAt = ms(16, 14)
        ),
        // A weekend that has begun is due on its first day, today; its reminder had passed when it was said.
        found(
            "Buy a birthday present for Anna", "en2", capture = TodoCapture.COMMITMENT,
            dueAt = ms(10, 9), dueHasTime = false, timePhrase = "this weekend", remindAt = null,
            certainty = TodoCertainty.TENTATIVE
        )
    )

    private val germanTodos = listOf(
        found(
            "Beim Arzt anrufen", "de1", capture = TodoCapture.COMMITMENT,
            dueAt = ms(11, 9), dueHasTime = true, timePhrase = "morgen um neun", remindAt = ms(11, 9)
        ),
        found(
            "Teammeeting", "de2", kind = TodoKind.APPOINTMENT, capture = TodoCapture.EXPLICIT,
            dueAt = ms(16, 14, 30), dueHasTime = true, timePhrase = "Nächsten Freitag um halb drei", remindAt = ms(16, 13, 30)
        ),
        found(
            "Anna die Fotos vom Urlaub schicken", "de2", capture = TodoCapture.COMMITMENT,
            dueAt = ms(11, 9), dueHasTime = false, timePhrase = "Sonntag", remindAt = ms(11, 9)
        )
    )

    @Test
    fun `english recording - a model that answers nothing still gets the three todos said outright`() = runBlocking {
        val todos = useCase(nothing())("s1", english, zone, "en")

        assertEquals(englishTodos, todos.map { it.withoutId() })
        assertEquals(listOf("todo-1", "todo-2", "todo-3"), todos.map { it.id })
    }

    @Test
    fun `english recording - the model's mangled copy joins the spotter's appointment and adds nothing wrong`() = runBlocking {
        // What the phone showed: one todo, a copy of two sentences cut short, due Friday 15:00 (T3).
        val llm = FakeLlmRepository(
            answer = answer(todo("S2", "The dentist appointment is. I also need to buy a birthday present for Anna this", "T3"))
        )

        val todos = useCase(llm)("s1", english, zone, "en")

        assertTrue(llm.prompts.single().first.contains("T3 = 'on Friday at 3 pm' -> Fri 16 Oct 15:00 (Friday)"))
        assertEquals(englishTodos, todos.map { it.withoutId() })
    }

    @Test
    fun `german recording - a model that answers nothing still gets the three todos said outright`() = runBlocking {
        val todos = useCase(nothing())("s1", german, zone, "de")

        assertEquals(germanTodos, todos.map { it.withoutId() })
    }

    @Test
    fun `german recording - the model's own wrong todo is only suggested, and the three said outright are added`() = runBlocking {
        // What the phone showed: "Heizung repariert", from the landlord's plan, the run of days to the month's end (T1).
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Heizung repariert", "T1")))

        val todos = useCase(llm)("s1", german, zone, "de")

        assertTrue(llm.prompts.single().first.contains("T1 = 'Ende des Monats' -> Mi. 21. Okt. - Sa. 31. Okt."))
        val heating = found(
            "Heizung repariert", "de1", capture = TodoCapture.INFERRED,
            dueAt = ms(31, 9), dueHasTime = false, timePhrase = "Ende des Monats", remindAt = ms(31, 9),
            certainty = TodoCertainty.TENTATIVE
        ).copy(status = TodoStatus.SUGGESTED)
        // The spotter doesn't overrule the model: its todo stays, said first in the segment, but the
        // "Ich muss" of the next sentence is no cue for it (TodoGate), so it is only a suggestion.
        assertEquals(listOf(heating) + germanTodos, todos.map { it.withoutId() })
    }

    @Test
    fun `both recordings - a model that fails or can't be loaded keeps the todos said outright`() = runBlocking {
        val failing = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        val notLoaded = FakeLlmRepository(reloadFailure = IllegalStateException("no model"))

        assertEquals(englishTodos, useCase(failing)("s1", english, zone, "en").map { it.withoutId() })
        assertEquals(germanTodos, useCase(notLoaded)("s1", german, zone, "de").map { it.withoutId() })
        assertTrue(notLoaded.prompts.isEmpty())
    }

    @Test
    fun `both recordings read again bring back none of their todos`() = runBlocking {
        val first = useCase(nothing())("s1", english, zone, "en") + useCase(nothing())("s1", german, zone, "de")
        // Since then one is done and one dismissed; the model now words them its own way.
        val existing = first.mapIndexed { i, todo ->
            todo.copy(status = if (i == 0) TodoStatus.DONE else if (i == 3) TodoStatus.DISMISSED else TodoStatus.OPEN)
        }
        val englishLlm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank", "T2"), todo("S2", "Dentist", "T3", kind = "appointment")))

        assertEquals(emptyList<Todo>(), useCase(englishLlm)("s1", english, zone, "en", existingTodos = existing))
        assertEquals(emptyList<Todo>(), useCase(nothing())("s1", german, zone, "de", existingTodos = existing))
    }

    // --- The cues ----------------------------------------------------------------------------------

    @Test
    fun `english requests to be reminded are explicit todos`() {
        val todos = spotted(
            "Remind me to water the plants tonight.",
            "Reminder to renew the passport next week.",
            "Don't forget to email Anna on Monday.",
            "Do not forget to pay the rent tomorrow.",
            "Remember to back up the laptop on Sunday.",
            "Note to self: book the hotel on Monday.",
            "To do: order the new glasses.",
            "Make sure to lock the garage tonight."
        )

        assertEquals(
            listOf(
                "Water the plants", "Renew the passport", "Email Anna", "Pay the rent", "Back up the laptop",
                "Book the hotel", "Order the new glasses", "Lock the garage"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN && it.kind == TodoKind.TASK })
        assertEquals("tonight", todos[0].timePhrase)
    }

    @Test
    fun `german requests to be reminded are explicit todos`() {
        val todos = spotted(
            "Erinnere mich daran, die Bank anzurufen.",
            "Erinner mich morgen daran, die Miete zu überweisen.",
            "Nicht vergessen, am Montag die Steuer abzugeben.",
            "Vergiss nicht, Brot zu kaufen.",
            "Denk daran, heute Abend Mama anzurufen.",
            "Notiz an mich: Reifen wechseln.",
            "Aufgabe: Steuererklärung machen.",
            "Todo: Fahrrad reparieren.",
            language = "de"
        )

        assertEquals(
            listOf(
                "Die Bank anrufen", "Die Miete überweisen", "Die Steuer abgeben", "Brot kaufen", "Mama anrufen",
                "Reifen wechseln", "Steuererklärung machen", "Fahrrad reparieren"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN })
        assertEquals(listOf(null, "morgen", "am Montag", null, "heute Abend"), todos.take(5).map { it.timePhrase })
    }

    @Test
    fun `english plans said in the first person are commitments`() {
        val todos = spotted(
            "I need to call the bank.",
            "I have to renew my passport.",
            "I've got to fix the bike.",
            "I must water the plants.",
            "I should email the landlord.",
            "I'll send the report.",
            "I will book the flight.",
            "I'm going to paint the fence.",
            "I want to learn Spanish.",
            "I really need to clean the car.",
            "I gotta pick up the parcel.",
            "I'm gonna call grandma."
        )

        assertEquals(
            listOf(
                "Call the bank", "Renew my passport", "Fix the bike", "Water the plants", "Email the landlord",
                "Send the report", "Book the flight", "Paint the fence", "Learn Spanish", "Clean the car",
                "Pick up the parcel", "Call grandma"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.COMMITMENT && it.status == TodoStatus.OPEN && it.kind == TodoKind.TASK })
    }

    @Test
    fun `german plans said in the first person are commitments, also with the verb put first`() {
        val todos = spotted(
            "Ich muss die Bank anrufen.",
            "Ich müsste den Keller aufräumen.",
            "Ich sollte Oma besuchen.",
            "Ich soll die Unterlagen schicken.",
            "Ich werde das Auto waschen.",
            "Ich will Spanisch lernen.",
            "Ich möchte die Garage streichen.",
            "Morgen muss ich die Steuer machen.",
            "Heute Abend soll ich Mama anrufen.",
            "Am Montag werde ich den Rasen mähen.",
            "Dann will ich die Fenster putzen.",
            language = "de"
        )

        assertEquals(
            listOf(
                "Die Bank anrufen", "Den Keller aufräumen", "Oma besuchen", "Die Unterlagen schicken", "Das Auto waschen",
                "Spanisch lernen", "Die Garage streichen", "Die Steuer machen", "Mama anrufen", "Den Rasen mähen",
                "Die Fenster putzen"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.COMMITMENT && it.status == TodoStatus.OPEN })
        assertEquals(listOf("Morgen", "Heute Abend", "Am Montag"), todos.subList(7, 10).map { it.timePhrase })
    }

    @Test
    fun `a german plan keeps what was put before its verb, but not what only sets the scene`() {
        val todos = spotted(
            "Den Termin beim Zahnarzt muss ich absagen.",
            "Die Folien muss ich bis Freitag fertig machen.",
            "Außerdem muss ich die Bank anrufen.",
            "Danach muss ich die Fenster putzen.",
            language = "de"
        )

        assertEquals(
            listOf("Den Termin beim Zahnarzt absagen", "Die Folien fertig machen", "Die Bank anrufen", "Die Fenster putzen"),
            todos.map { it.text }
        )
        assertEquals(TodoKind.TASK, todos[0].kind)
        assertEquals(ms(16, 9), todos[1].dueAt)
    }

    @Test
    fun `an order said of oneself is a statement, not a todo`() {
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf("I always remember to lock the door.", "I don't forget to feed the cat.", "Ich habe nicht vergessen, die Miete zu überweisen.")
        )
    }

    @Test
    fun `erinnere mich an names what to think of`() {
        assertEquals(listOf(listOf("Die Steuer")), textsOf("Erinnere mich an die Steuer.", language = "de"))
    }

    @Test
    fun `a german verb put first at the start of a sentence opens a question or a condition, not a todo`() {
        assertEquals(
            List(2) { emptyList<String>() },
            // The first is a question whose mark the transcript lost.
            textsOf("Soll ich morgen die Bank anrufen.", "Sollte ich morgen Zeit haben, rufe ich Anna an.", language = "de")
        )
    }

    // --- Appointments ------------------------------------------------------------------------------

    @Test
    fun `an appointment noun said with its time is an appointment that reminds an hour before`() {
        val todos = spotted(
            "I have a meeting with Bob tomorrow at 10.",
            "My flight to Berlin leaves on Friday at 6 am.",
            "The job interview is on Monday at 9:30.",
            "On Friday at 4, I have a doctor's appointment.",
            "I booked a flight to Rome for Tuesday at 7.",
            "Ich habe am Freitag um 15 Uhr einen Termin beim Zahnarzt.",
            "Morgen habe ich um 11 Uhr Zahnarzt.",
            "Die Besprechung mit dem Chef ist am Montag um 10.",
            "Am Mittwoch um 14 Uhr ist das Kundenmeeting.",
            "Der Flug nach Rom geht am Dienstag um 7."
        )

        assertEquals(
            listOf(
                "Meeting with Bob", "Flight to Berlin", "Job interview", "Doctor's appointment", "Flight to Rome",
                "Termin beim Zahnarzt", "Zahnarzt", "Besprechung mit dem Chef", "Kundenmeeting", "Flug nach Rom"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.kind == TodoKind.APPOINTMENT && it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN })
        val meeting = todos[0]
        assertEquals(ms(11, 10), meeting.dueAt)
        assertEquals(ms(11, 9), meeting.remindAt)
        assertEquals(ms(11, 11), todos[6].dueAt)
        assertEquals("Morgen um 11 Uhr", todos[6].timePhrase)
    }

    @Test
    fun `a hesitation or the polite you leave an appointment the speaker's`() {
        assertEquals(
            listOf(listOf("Meeting"), listOf("Termin beim Zahnarzt")),
            textsOf("Er, the meeting is on Friday at 3.", "Am Freitag um 10 haben Sie einen Termin beim Zahnarzt.")
        )
    }

    @Test
    fun `a cue that comes to nothing leaves the appointment it names`() {
        val meeting = spotted("I'll see you at the meeting on Friday at 3.").single()

        assertEquals("Meeting", meeting.text)
        assertEquals(TodoKind.APPOINTMENT, meeting.kind)
        assertEquals(ms(16, 15), meeting.dueAt)
    }

    @Test
    fun `an appointment noun that only says what kind another thing is is no appointment`() {
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("The meeting room is free on Friday at 3.", "The flight tickets arrive on Monday.")
        )
    }

    @Test
    fun `an appointment noun without a time, or with one that is over, is no todo`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf("The dentist was really nice.", "We need a new meeting room.", "Das Treffen hat Spaß gemacht.", "Dentist appointment yesterday at 3.")
        )
    }

    @Test
    fun `a german appointment noun with a verb after it, and one moved or booked, is an appointment`() {
        val sentences = listOf(
            "Das Treffen findet am Montag um 10 statt." to "de",
            "Der Zahnarzttermin wurde auf Freitag um 9 verlegt." to "de",
            "Der Termin wurde auf Donnerstag um 16 Uhr verschoben." to "de",
            "Die Besprechung am Montag wurde auf Dienstag um 14 Uhr verschoben." to "de",
            "Das Teammeeting läuft am Montag ab 10 Uhr." to "de",
            "The interview was rescheduled to Friday at 10." to "en",
            "I've already booked the flight to Rome for Tuesday at 7." to "en",
            "The meeting kicks off at 3 tomorrow." to "en"
        )

        val todos = sentences.map { (sentence, language) -> one(sentence, language) }

        assertEquals(
            listOf(
                "Treffen", "Zahnarzttermin", "Termin", "Besprechung", "Teammeeting", "Interview", "Flight to Rome", "Meeting"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.kind == TodoKind.APPOINTMENT && it.status == TodoStatus.OPEN })
        assertEquals(
            listOf(ms(12, 10), ms(16, 9), ms(15, 16), ms(13, 14), ms(12, 10), ms(16, 10), ms(13, 7), ms(11, 15)),
            todos.map { it.dueAt }
        )
        // What was is still over; an English noun before another still only says what kind.
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("The dentist appointment was on Friday at 3.", "The meeting room is free on Friday at 3.")
        )
    }

    @Test
    fun `a german verb is no appointment noun`() {
        // "treffen" is the verb; only the noun "Treffen" names an appointment.
        assertEquals(listOf(emptyList<String>()), textsOf("Wir treffen uns am Montag um 9.", language = "de"))
        assertEquals(listOf(listOf("Treffen mit Anna")), textsOf("Am Montag um 9 ist das Treffen mit Anna.", language = "de"))
    }

    @Test
    fun `a request or a plan made only of an appointment is that appointment`() {
        val todos = spotted(
            "Don't forget the team meeting on Monday at 10.",
            "Remind me about the dentist appointment on Friday at 3.",
            "I'm going to the doctor on Tuesday at 11.",
            "Ich muss am Montag um 9 zum Zahnarzt.",
            "Erinnere mich an den Termin beim Arzt am Dienstag um 10.",
            "Nicht vergessen: Besprechung am Mittwoch um 9."
        )

        assertEquals(
            listOf("Team meeting", "Dentist appointment", "Doctor", "Zahnarzt", "Termin beim Arzt", "Besprechung"),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.kind == TodoKind.APPOINTMENT })
        assertEquals(
            listOf(
                TodoCapture.EXPLICIT, TodoCapture.EXPLICIT, TodoCapture.COMMITMENT, TodoCapture.COMMITMENT,
                TodoCapture.EXPLICIT, TodoCapture.EXPLICIT
            ),
            todos.map { it.capture }
        )
        assertEquals(ms(12, 9), todos[0].remindAt)
    }

    @Test
    fun `a plan with a verb is a task, even with an appointment noun`() {
        val call = spotted("Ich muss am Montag um 10 beim Arzt anrufen.", language = "de").single()

        assertEquals("Beim Arzt anrufen", call.text)
        assertEquals(TodoKind.TASK, call.kind)
        assertEquals(call.dueAt, call.remindAt)
    }

    @Test
    fun `a plan whose words name no thing to do is none`() {
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("I'm going to the gym tomorrow.", "Ich will morgen ein Eis.")
        )
    }

    @Test
    fun `a request without a verb keeps the request as its text`() {
        val (keys, bank) = spotted("Don't forget the keys tomorrow.", "Remind me about the bank on Monday.")

        assertEquals("Don't forget the keys", keys.text)
        assertEquals(ms(11, 9), keys.dueAt)
        assertEquals("Remind me about the bank", bank.text)
    }

    // --- Not a todo ----------------------------------------------------------------------------------

    @Test
    fun `the past and what is done are no todos`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                // Without the past, each of these would be an appointment on a day still to come.
                "I had to go to the dentist on Monday at 9.",
                "I called the doctor on Friday.",
                "I should have called the bank on Monday.",
                "Remind me to call the bank, already done."
            )
        )
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "Ich habe am Montag beim Arzt angerufen.",
                "Ich war am Freitag um 10 beim Zahnarzt.",
                "Der Termin beim Zahnarzt am Freitag um 3 ist bereits erledigt.",
                "Nicht vergessen: Miete überweisen, schon erledigt.",
                language = "de"
            )
        )
    }

    @Test
    fun `other people's plans are no todos`() {
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf(
                "He needs to call the bank tomorrow.",
                "She has a doctor's appointment on Friday at 3.",
                "Anna has to go to the dentist on Friday at 3.",
                "His interview is on Monday at 10.",
                "Anna's flight to Rome is on Tuesday at 7."
            )
        )
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf(
                "Die Heizung wird bis Ende des Monats repariert.",
                "Er hat am Freitag um 15 Uhr einen Termin beim Arzt.",
                "Am Freitag hat sie um 10 einen Termin beim Arzt.",
                "Anna muss am Montag um 9 zum Zahnarzt.",
                "Sein Termin beim Arzt ist am Dienstag um 10.",
                language = "de"
            )
        )
    }

    @Test
    fun `questions are no todos, but asking to be reminded is`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "Do I need to call the bank tomorrow?",
                "Is the dentist appointment on Friday at 3?",
                "Muss ich morgen die Bank anrufen?",
                "Ist das Teammeeting am Freitag um 10?"
            )
        )
        val bank = spotted("Can you remind me to call the bank tomorrow?").single()
        assertEquals("Call the bank", bank.text)
        assertEquals(TodoCapture.EXPLICIT, bank.capture)
    }

    @Test
    fun `negations are no todos`() {
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf(
                "I don't need to call the bank tomorrow.",
                "I will not call the bank tomorrow.",
                "I'll never go to that dentist again.",
                "Don't remind me to call the bank.",
                "There is no meeting on Friday at 3."
            )
        )
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf(
                "Ich muss morgen nicht die Bank anrufen.",
                "Ich muss am Freitag keine Fotos schicken.",
                "Am Freitag ist kein Meeting um 10.",
                language = "de"
            )
        )
    }

    @Test
    fun `a cue that leaves nothing to do is no todo`() {
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf("Remind me to... tomorrow.", "I need to, um, tomorrow.", "Note to self: tomorrow.", "Nicht vergessen: morgen!", "Ich muss morgen.")
        )
    }

    @Test
    fun `a cue that leaves only talk is no todo, but telling someone something is`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf("I have to say, the food was great.", "I must admit I was wrong.", "Ich muss sagen, das Essen war toll.", "Ich muss zugeben, dass ich spät war.")
        )
        assertEquals(listOf(listOf("Tell Anna about the party")), textsOf("I need to tell Anna about the party."))
    }

    @Test
    fun `a cancelled appointment is no todo`() {
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("The meeting on Friday at 3 is cancelled.", "Das Meeting am Freitag um 10 fällt aus.")
        )
    }

    @Test
    fun `an appointment noun that only says where something comes from is no todo`() {
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("The invoice from the dentist comes on Friday.", "Die Rechnung vom Zahnarzt kommt am Freitag.")
        )
    }

    @Test
    fun `someone else's appointment, said with a name or a noun, is no todo`() {
        // "has", "'s got" and "hat" are never said of oneself.
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf(
                "Tom has a job interview on Monday at 9.",
                "My husband has a doctor's appointment on Friday at 10.",
                "My boss has a meeting with the board on Thursday at 2.",
                "Julia has a flight to Madrid on Wednesday at 7 am.",
                "Mike's got a dentist appointment on Friday at 3."
            )
        )
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "Lisa hat morgen um 10 einen Termin beim Zahnarzt.",
                "Mein Chef hat am Freitag um 10 eine Besprechung mit dem Vorstand.",
                "Meine Kollegin hat übermorgen um 14 Uhr einen Arzttermin.",
                "Frau Müller hat am Donnerstag um 14 Uhr einen Termin mit dem Steuerberater.",
                language = "de"
            )
        )
        assertEquals(
            emptyList<Todo>(),
            spotted("Kurze Notiz.", "Papa hat am Mittwoch um 11 einen Termin beim Arzt, sie fährt ihn hin.", language = "de")
        )
    }

    @Test
    fun `have, haben and going are the speaker's only with I, we, ich, wir or the polite Sie`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "The kids have a dentist appointment on Friday at 3.",
                "You have a meeting with the board on Thursday at 2.",
                "They've got a meeting on Friday at 3.",
                "Tom is going to the dentist on Friday at 3."
            )
        )
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf(
                "Am Freitag haben die Kinder um 10 einen Termin beim Zahnarzt.",
                "Du hast morgen um 10 einen Termin beim Zahnarzt.",
                "Anna geht am Freitag um 3 zum Zahnarzt.",
                language = "de"
            )
        )
        // The verb after the noun, the speaker as the subject, "there", or the speaker in it.
        assertEquals(
            listOf(
                listOf("Meeting"), listOf("Meeting"), listOf("Meeting"), listOf("Dentist appointment"),
                listOf("Meeting about the budget"), listOf("Meeting")
            ),
            textsOf(
                "The meeting has been moved to Friday at 3.",
                "We have a meeting on Friday at 3.",
                "Let's have a meeting on Friday at 3.",
                "I've got a dentist appointment on Friday at 3.",
                "There's a meeting at 3 about the budget.",
                "Bob wants a meeting with me on Friday at 3."
            )
        )
        assertEquals(
            listOf(listOf("Besprechung"), listOf("Besprechung")),
            textsOf("Wir haben am Freitag um 10 eine Besprechung.", "Am Freitag haben wir um 10 eine Besprechung.", language = "de")
        )
    }

    @Test
    fun `someone else's appointment leaves the model's suggestion for the speaker alone`() = runBlocking {
        val segments = listOf(seg("a", "My husband has a doctor's appointment on Friday at 10.", at(10, 0)))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Drive husband to the doctor", "T1", capture = "inferred")))

        val drive = useCase(llm)("s1", segments, zone, "en").single()

        assertEquals("Drive husband to the doctor", drive.text)
        assertEquals(TodoStatus.SUGGESTED, drive.status)
        assertEquals(ms(16, 10), drive.dueAt)
    }

    @Test
    fun `small talk and statements of fact are no plans`() {
        assertEquals(
            List(9) { emptyList<String>() },
            textsOf(
                "I must say the hotel was lovely.",
                "I have to admit the food was good.",
                "I'm going to miss you.",
                "I'll be right back.",
                "I'll have the salmon, please.",
                "I'll probably be late tomorrow.",
                "I'll be in Berlin next week.",
                "I want to thank you all for coming today.",
                // In a meeting
                "I'll be brief."
            )
        )
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf(
                "Ich werde morgen den ganzen Tag im Büro sein.",
                "Ich muss jetzt leider Schluss machen.",
                "Ich möchte mich bei allen für die tolle Zusammenarbeit bedanken.",
                language = "de"
            )
        )
    }

    @Test
    fun `a weaker plan needs a thing to do, and no plan is being something, a feeling, leaving or talk`() {
        assertEquals(
            List(12) { emptyList<String>() },
            textsOf(
                "I'll keep it short.",
                "I'll share my screen.",
                "I'll start with the budget.",
                "I'll give it a try.",
                "I'll call you later.",
                "I'll talk to you later.",
                "I must be dreaming.",
                "I need to think about it.",
                "I have to go now.",
                "I need to tell you something.",
                "I'll put you on hold.",
                "Don't forget, I'll be late tomorrow."
            )
        )
        assertEquals(
            List(6) { emptyList<String>() },
            textsOf(
                "Ich werde dich vermissen.", "Ich werde dich anrufen.", "Ich muss jetzt gehen.", "Ich muss jetzt auflegen.",
                "Ich möchte bitte zahlen.", "Ich muss vorsichtig sein.",
                language = "de"
            )
        )
        // A thing to do after a weaker cue, being somewhere after a stronger one, "I'll have to", "sein müssen".
        val todos = spotted(
            "I'll pick up the kids at 4.",
            "I'll email you the report.",
            "I'll just call the plumber.",
            "I need to be at the airport at 6.",
            "I need to say sorry to Anna.",
            "I'll have to call the bank.",
            "I'll have to be at the station at 6.",
            "Ich werde morgen die Garage aufräumen.",
            "Ich werde die Steuer machen müssen.",
            "Ich muss um 9 im Büro sein.",
            "Ich werde morgen beim Notar sein müssen."
        )
        assertEquals(
            listOf(
                "Pick up the kids", "Email you the report", "Call the plumber", "Be at the airport", "Say sorry to Anna",
                "Call the bank", "Be at the station", "Die Garage aufräumen", "Die Steuer machen", "Im Büro sein",
                "Beim Notar sein"
            ),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.COMMITMENT && it.status == TodoStatus.OPEN })
    }

    @Test
    fun `a plan doubted or denied before its cue, or said as the condition, is none`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "I don't think I need to call the bank after all.",
                "I'm not sure I'll have time to call the bank tomorrow.",
                "I doubt I'll make it to the gym this week.",
                "We talked about whether I should apply for the job in Munich."
            )
        )
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "I asked Anna, whether I should bring the cake.",
                "If I have to call the bank, I'll be late.",
                "Unless I have to work late, I'll cook dinner.",
                "Let me know if I need to bring anything."
            )
        )
        // What will be if something happens: none.
        assertEquals(listOf(emptyList<String>()), textsOf("Wenn ich im Lotto gewinne, werde ich sofort kündigen.", language = "de"))
    }

    @Test
    fun `what must be done if something happens is only a suggestion, what is done when something happens a plan`() {
        val windows = one("If the landlord doesn't call, I need to call the bank on Monday at 10.")
        assertEquals("Call the bank", windows.text)
        assertEquals(TodoCapture.INFERRED, windows.capture)
        assertEquals(TodoStatus.SUGGESTED, windows.status)
        assertEquals(TodoCertainty.TENTATIVE, windows.certainty)
        assertEquals(ms(12, 10), windows.dueAt)

        val suggested = listOf(
            one("If it rains tomorrow, I need to close the windows."),
            one("The bank might call, if they do, I need to send the papers."),
            one("Wenn ich nach Hause komme, muss ich die Katze füttern.", language = "de"),
            one("Sollte ich morgen Zeit haben, muss ich Anna anrufen.", language = "de")
        )
        assertEquals(listOf("Close the windows", "Send the papers", "Die Katze füttern", "Anna anrufen"), suggested.map { it.text })
        assertTrue(suggested.all { it.status == TodoStatus.SUGGESTED })

        val cat = one("When I get home, I need to feed the cat.")
        assertEquals("Feed the cat", cat.text)
        assertEquals(TodoStatus.OPEN, cat.status)
        assertEquals(TodoCapture.COMMITMENT, cat.capture)
    }

    @Test
    fun `what someone else says he will do is no todo`() {
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf("Okay, I'll take care of the invoices, said Tom.", "Remind me to call the bank, said Tom.")
        )
    }

    @Test
    fun `an appointment the sentence says the speaker won't go to is none, and skipping it no todo`() {
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf(
                "There's a meeting at 3 about the budget, but I'm not going.",
                "The meeting on Friday at 3 is optional, I'll skip it.",
                "The doctor's appointment is on Friday at 10, I can't make it."
            )
        )
        assertEquals(
            List(2) { emptyList<String>() },
            textsOf(
                "Am Freitag um 10 ist das Teammeeting, aber ich gehe nicht hin.",
                "Ich habe am Freitag um 10 einen Termin, den muss ich absagen.",
                language = "de"
            )
        )
        // Not going somewhere else, not skipping it, or someone else not going leaves it be.
        assertEquals(
            listOf(listOf("Dentist"), listOf("Meeting"), listOf("Meeting"), listOf("Meeting")),
            textsOf(
                "The dentist is on Friday at 3, but I'm not going to the gym.",
                "The meeting on Friday at 3 is important, I can't skip it.",
                "The meeting is at 3, and I don't need to bring anything.",
                "There's a meeting at 3, but Tom isn't going."
            )
        )
    }

    @Test
    fun `cue words said in a statement are no request`() {
        assertEquals(
            List(4) { emptyList<String>() },
            textsOf(
                "This is a reminder to everyone that the office is closed on Friday.",
                "There's nothing to do: the shop is closed.",
                "Just to make sure, the office is closed on Friday.",
                "Ich denke daran, nächstes Jahr nach Spanien zu ziehen."
            )
        )
        // "To make sure" only says why the time is said again; the meeting is an appointment.
        val meeting = one("To make sure, the meeting is on Friday at 3.")
        assertEquals("Meeting", meeting.text)
        assertEquals(TodoKind.APPOINTMENT, meeting.kind)
        assertEquals(ms(16, 15), meeting.dueAt)
        // A request still reads as one.
        assertEquals(listOf("Renew the passport"), spotted("Reminder to renew the passport next week.").map { it.text })
        assertEquals(emptyList<Todo>(), spotted("Remind me."))
    }

    @Test
    fun `a request of what is on a day names the thing, without the copula its time left`() {
        val (dentist, party, birthday) = spotted(
            "Reminder: the dentist appointment is on Friday at 3.",
            "Don't forget, the party is on Saturday.",
            "Note to self: Anna's birthday is on Friday."
        )

        assertEquals(listOf("Dentist appointment", "Don't forget the party", "Anna's birthday"), listOf(dentist, party, birthday).map { it.text })
        assertEquals(TodoKind.APPOINTMENT, dentist.kind)
        assertEquals(ms(16, 14), dentist.remindAt)
        assertEquals(listOf(ms(17, 9), ms(16, 9)), listOf(party.dueAt, birthday.dueAt))
    }

    @Test
    fun `denk dran, I mustn't forget and don't let me forget ask to be reminded`() {
        val todos = spotted(
            "Denk dran, am Freitag die Miete zu überweisen.",
            "I must not forget to water the plants tomorrow.",
            "I mustn't forget to send the invoice on Monday.",
            "Don't let me forget to pay the electricity bill on Friday.",
            "I can't forget to call the bank tomorrow."
        )

        assertEquals(
            listOf("Die Miete überweisen", "Water the plants", "Send the invoice", "Pay the electricity bill", "Call the bank"),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN })
        assertEquals(listOf(ms(16, 9), ms(11, 9), ms(12, 9), ms(16, 9), ms(11, 9)), todos.map { it.dueAt })

        // Alone and as the third segment of a note.
        val kids = one("Und denk dran, morgen die Kinder um vier vom Sport abzuholen.", language = "de")
        val inNote = spotted(
            "Kurze Notiz.", "Die Heizung ist kaputt.", "Und denk dran, morgen die Kinder um vier vom Sport abzuholen.", language = "de"
        ).single()
        for (todo in listOf(kids, inNote)) {
            assertEquals("Die Kinder vom Sport abholen", todo.text)
            assertEquals(TodoCapture.EXPLICIT, todo.capture)
            assertEquals(ms(11, 16), todo.dueAt)
        }
        assertEquals(listOf("s2"), inNote.sourceSegmentIds)
    }

    // --- The text ------------------------------------------------------------------------------------

    @Test
    fun `the text ends at a clause break, and what follows has a todo only of its own`() {
        assertEquals(
            listOf(
                listOf("Call the bank", "Dentist"),
                listOf("Call the bank"),
                listOf("Die Bank anrufen"),
                listOf("Die Bank anrufen"),
                listOf("Die Bank anrufen")
            ),
            textsOf(
                "Remind me to call the bank, and then the dentist is on Friday at 3.",
                "I need to call the bank and then pick up the kids.",
                "Ich muss die Bank anrufen und dann einkaufen gehen.",
                "Ich muss die Bank anrufen, aber erst nach dem Mittagessen.",
                "Ich muss die Bank anrufen und danach zur Post gehen."
            )
        )
    }

    @Test
    fun `a new subject or a request after a clause starts a todo of its own`() {
        val (dentist, bank) = spotted("Tomorrow I have the dentist at 3 and I have to call the bank.")
        assertEquals("Dentist", dentist.text)
        assertEquals(ms(11, 15), dentist.dueAt)
        assertEquals("Call the bank", bank.text)
        assertEquals(null, bank.dueAt)

        val (doctor, papers) = spotted("Tomorrow at 3 I have the dentist, remind me to bring the papers.")
        assertEquals(listOf("Dentist", "Bring the papers"), listOf(doctor.text, papers.text))
        assertEquals(listOf(ms(11, 15), null), listOf(doctor.dueAt, papers.dueAt))

        // German puts the verb after the word in front; a comma still ends the clause before.
        val (zahnarzt, bill) = spotted("Morgen um 10 bin ich beim Zahnarzt, die Rechnung muss ich bis Freitag bezahlen.", language = "de")
        assertEquals(listOf("Zahnarzt", "Die Rechnung bezahlen"), listOf(zahnarzt.text, bill.text))
        assertEquals(listOf(ms(11, 10), ms(16, 9)), listOf(zahnarzt.dueAt, bill.dueAt))
    }

    @Test
    fun `a dot after an abbreviation ends no sentence, and a possessive time stays in the text`() {
        assertEquals(
            listOf(listOf("Call Dr. Smith"), listOf("Prepare the slides for Friday's meeting")),
            textsOf("Remind me to call Dr. Smith tomorrow.", "Remind me to prepare the slides for Friday's meeting.")
        )
    }

    @Test
    fun `a comma before a new clause ends the text and its time, a comma after a time or a cue doesn't`() {
        val todos = spotted(
            "Remind me to call Bob, he wants the slides.",
            "Ich muss die Bank anrufen, damit die Karte nicht gesperrt wird.",
            "Morgen muss ich die Steuer machen, um 18 Uhr ist Training.",
            "Tomorrow I need to call the bank, at 3 I watch the football.",
            "Tomorrow, I need to call the plumber.",
            "Erinnere mich daran, dass ich morgen die Garage aufräumen muss."
        )

        assertEquals(
            listOf("Call Bob", "Die Bank anrufen", "Die Steuer machen", "Call the bank", "Call the plumber", "Die Garage aufräumen"),
            todos.map { it.text }
        )
        assertEquals(listOf(null, null, ms(11, 9), ms(11, 9), ms(11, 9), ms(11, 9)), todos.map { it.dueAt })
        assertTrue(todos.none { it.dueHasTime })
    }

    @Test
    fun `two things to do joined by and part, unless they share what they are done to`() {
        val texts = textsOf(
            "Tomorrow I need to call the bank at 3 and pick up the kids.",
            "I need to wash and dry the dishes.",
            "I need to buy milk and eggs.",
            "Ich muss die Bank anrufen und die Kinder abholen.",
            "Ich muss die Wäsche waschen und aufhängen.",
            "Ich muss Milch und Brot kaufen."
        )

        assertEquals(
            listOf(
                listOf("Call the bank"), listOf("Wash and dry the dishes"), listOf("Buy milk and eggs"),
                listOf("Die Bank anrufen"), listOf("Die Wäsche waschen und aufhängen"), listOf("Milch und Brot kaufen")
            ),
            texts
        )
        assertEquals(ms(11, 15), spotted("Tomorrow I need to call the bank at 3 and pick up the kids.").single().dueAt)
    }

    @Test
    fun `the text loses its time words, fillers and hedges, and english starts with its verb`() {
        val todos = spotted(
            "Remind me tomorrow at ten to call the bank.",
            "I need to book a table for Friday at 8 pm.",
            "I also need to call Bob too.",
            "I'll then please email the landlord.",
            "Außerdem muss ich dann bitte auch die Bank anrufen.",
            "Ich muss noch mal Anna anrufen.",
            "I'll probably call the dentist on Monday.",
            "Vielleicht muss ich morgen die Garage aufräumen."
        )

        assertEquals(
            listOf(
                "Call the bank", "Book a table", "Call Bob", "Email the landlord", "Die Bank anrufen", "Noch mal Anna anrufen",
                "Call the dentist", "Die Garage aufräumen"
            ),
            todos.map { it.text }
        )
        assertEquals(ms(11, 10), todos[0].dueAt)
        assertEquals(ms(16, 20), todos[1].dueAt)
        assertEquals(
            listOf(TodoCertainty.CONFIRMED, TodoCertainty.TENTATIVE, TodoCertainty.TENTATIVE),
            listOf(todos[0], todos[6], todos[7]).map { it.certainty }
        )
    }

    @Test
    fun `german zu infinitives and dass clauses after a request are made plain`() {
        val todos = spotted(
            "Nicht vergessen, am Montag die Miete zu überweisen.",
            "Denk daran, heute Abend Mama anzurufen.",
            "Erinnere mich daran, dass ich morgen die Bank anrufen muss.",
            "Remind me that I have to call the bank tomorrow."
        )

        assertEquals(listOf("Die Miete überweisen", "Mama anrufen", "Die Bank anrufen", "Call the bank"), todos.map { it.text })
    }

    @Test
    fun `german separable zu infinitives, daran after a time and dass ich made plain, and make sure I left out`() {
        val todos = spotted(
            "Erinnere mich morgen früh daran, den Müll rauszubringen.",
            "Ich muss dran denken, die Pakete abzuholen.",
            "Erinnere mich am Montag um 9 daran, den Vertrag zu kündigen.",
            "Erinnere mich daran, dass ich Oma anrufe.",
            "Make sure I call the insurance company.",
            language = "de"
        )

        assertEquals(
            listOf("Den Müll rausbringen", "Die Pakete abholen", "Den Vertrag kündigen", "Oma anrufen", "Call the insurance company"),
            todos.map { it.text }
        )
        assertEquals(ms(12, 9), todos[2].dueAt)
        assertEquals(true, todos[2].dueHasTime)
    }

    @Test
    fun `the text loses adverbs that only stress it, and keeps again`() {
        assertEquals(
            listOf(
                listOf("Die Bank anrufen"), listOf("Die Steuer machen"), listOf("Den Müll rausbringen"),
                listOf("Den Keller aufräumen"), listOf("Die Rechnung bezahlen"), listOf("Wieder meine Eltern anrufen")
            ),
            textsOf(
                "Ich muss unbedingt die Bank anrufen.",
                "Als Erstes muss ich die Steuer machen.",
                "Ich muss als Erstes den Müll rausbringen.",
                "Ich muss endlich den Keller aufräumen.",
                "Ich muss jetzt leider die Rechnung bezahlen.",
                "Ich muss wieder meine Eltern anrufen.",
                language = "de"
            )
        )
        // "coming" before "today" is the verb, not the word that ties a time ("the coming Friday").
        assertEquals(listOf(listOf("Thank Anna for coming")), textsOf("I need to thank Anna for coming today."))
    }

    @Test
    fun `a time said before a comma with only words that set the scene is the plan's`() {
        val (insurance, report) = spotted("First thing tomorrow, I need to call the insurance.", "By Friday, I need to send the report.")

        assertEquals(listOf("Call the insurance", "Send the report"), listOf(insurance.text, report.text))
        assertEquals(listOf(ms(11, 9), ms(16, 9)), listOf(insurance.dueAt, report.dueAt))
    }

    @Test
    fun `a plan cut off on a joining word is no todo`() {
        // The rest is said more than three seconds later, a sentence of its own.
        assertEquals(emptyList<Todo>(), spotted("I need to call the", "bank tomorrow at 10."))
        assertEquals(listOf(listOf("Book a table")), textsOf("I need to book a table for the"))
    }

    @Test
    fun `a long text is cut to the longest todo`() {
        val todo = spotted("Remind me to call the bank about the loan and the card and the account and the transfer and the fees and the rest.").single()

        assertEquals("Call the bank about the loan and the card and the account and the transfer", todo.text)
        assertEquals(TodoTexts.MAX_WORDS, todo.text.split(' ').size)
    }

    @Test
    fun `a sentence that runs on into the next segment is read whole`() = runBlocking {
        val segments = listOf(seg("a", "Morgen muss ich", at(10, 0, 0)), seg("b", "um 9 beim Arzt sein.", at(10, 0, 2)))

        val doctor = useCase(nothing())("s1", segments, zone, "de").single()

        assertEquals("Beim Arzt sein", doctor.text)
        assertEquals(ms(11, 9), doctor.dueAt)
        assertEquals("Morgen um 9", doctor.timePhrase)
        assertEquals(listOf("a", "b"), doctor.sourceSegmentIds)
    }

    @Test
    fun `a clause with two clock times gives its todo only the day`() {
        val dentist = spotted("Tomorrow I have the dentist at 3 and the haircut at 5.").single()

        assertEquals("Dentist", dentist.text)
        assertEquals(ms(11, 9), dentist.dueAt)
        assertEquals(false, dentist.dueHasTime)
    }

    // --- The spotter's todos with the model's --------------------------------------------------------

    @Test
    fun `the model's text of the same words is kept when it is shorter, not when longer or two sentences`() = runBlocking {
        val segments = listOf(seg("a", "I need to buy a birthday present for Anna this weekend.", at(10, 0)))
        fun withModel(text: String) = runBlocking {
            useCase(FakeLlmRepository(answer = answer(todo("S1", text, "T1"))))("s1", segments, zone, "en").single().text
        }

        assertEquals("Buy Anna a present", withModel("Buy Anna a present"))
        assertEquals("Buy a birthday present for Anna", withModel("Buy a birthday present for Anna and some flowers"))
        assertEquals("Buy a birthday present for Anna", withModel("Buy Anna a present. Then the flowers"))
    }

    @Test
    fun `a model todo that rewords the spotter's, due at the same moment, joins it with the spotter's words`() = runBlocking {
        val bank = listOf(seg("a", "Remind me to call the bank tomorrow at ten about the loan.", at(10, 0)))
        val phone = FakeLlmRepository(answer = answer(todo("S1", "Phone the bank regarding the loan", "T1", capture = "explicit")))

        val call = useCase(phone)("s1", bank, zone, "en").single()

        assertEquals("Call the bank about the loan", call.text)
        assertEquals(ms(11, 10), call.dueAt)
        assertEquals(ms(11, 10), call.remindAt)
        // Read again, it brings nothing back.
        assertEquals(emptyList<Todo>(), useCase(phone)("s1", bank, zone, "en", existingTodos = listOf(call)))

        val arzt = listOf(seg("a", "Ich muss morgen um neun beim Arzt anrufen.", at(10, 0)))
        val praxis = useCase(FakeLlmRepository(answer = answer(todo("S1", "Arztpraxis anrufen", "T1"))))("s1", arzt, zone, "de")
        assertEquals(listOf("Beim Arzt anrufen"), praxis.map { it.text })
        assertEquals(ms(11, 9), praxis.single().dueAt)
    }

    @Test
    fun `a model todo of another thing at the same moment stays apart, a shared verb is not enough`() = runBlocking {
        val segments = listOf(seg("a", "Tomorrow at 10 I need to call the bank.", at(10, 0)))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call Anna", "T1")))

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertEquals(listOf("Call the bank", "Call Anna"), todos.map { it.text })
        assertTrue(todos.all { it.dueAt == ms(11, 10) })
    }

    @Test
    fun `a model todo in other words at another moment stays apart`() = runBlocking {
        val segments = listOf(
            seg("a", "Remind me to call the bank tomorrow at ten about the loan.", at(10, 0, 0)),
            seg("b", "On Friday I have the haircut.", at(10, 0, 30))
        )
        // The model reads Friday (T2) for it.
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Phone the bank regarding the loan", "T2", capture = "explicit")))

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertEquals(listOf("Call the bank about the loan", "Phone the bank regarding the loan"), todos.map { it.text })
        assertEquals(listOf(ms(11, 10), ms(16, 9)), todos.map { it.dueAt })
    }

    @Test
    fun `the spotter's time is kept when both have one, the model's when only it has one`() = runBlocking {
        val bank = listOf(
            seg("a", "Remind me to call the bank tomorrow at ten.", at(10, 0, 0)),
            seg("b", "On Friday I have the haircut.", at(10, 0, 30))
        )
        // The model picks Friday, said in the next segment.
        val call = useCase(FakeLlmRepository(answer = answer(todo("S1", "Call the bank", "T2"))))("s1", bank, zone, "en").single()
        assertEquals(ms(11, 10), call.dueAt)
        assertEquals("tomorrow at ten", call.timePhrase)
        assertEquals(listOf("a", "b"), call.sourceSegmentIds)

        val slides = listOf(
            seg("a", "I still need to finish the slides,", at(10, 0, 0)),
            seg("b", "by Friday at noon at the latest.", at(10, 0, 10))
        )
        val finish = useCase(FakeLlmRepository(answer = answer(todo("S1", "Finish the slides", "T1"))))("s1", slides, zone, "en").single()
        assertEquals(ms(16, 12), finish.dueAt)
        assertEquals(TodoCapture.COMMITMENT, finish.capture)
    }

    @Test
    fun `the model's clock time counts when the spotter's words named more than one`() = runBlocking {
        val segments = listOf(seg("a", "Tomorrow I have the dentist at 3 and the haircut at 5.", at(10, 0)))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Dentist at 3", "T1", kind = "appointment", capture = "explicit")))

        val dentist = useCase(llm)("s1", segments, zone, "en").single()

        assertEquals(ms(11, 15), dentist.dueAt)
        assertEquals(ms(11, 14), dentist.remindAt)
    }

    @Test
    fun `a hedge in the sentence makes the spotter's time tentative when the model reads the same moment`() = runBlocking {
        // The hedge is in a clause of its own, which the spotter doesn't read for this todo.
        val segments = listOf(seg("a", "I'll call the tax office on Monday at 10, but maybe later.", at(10, 0)))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the tax office", "T1")))

        val call = useCase(llm)("s1", segments, zone, "en").single()

        assertEquals(ms(12, 10), call.dueAt)
        assertEquals(TodoCertainty.TENTATIVE, call.certainty)
        assertEquals(TodoCertainty.CONFIRMED, useCase(nothing())("s1", segments, zone, "en").single().certainty)

        // The model calling it tentative, in an answer of an earlier build, counts no more.
        val firm = listOf(seg("a", "I'll call the tax office on Monday at 10, if it works out.", at(10, 0)))
        val tentative = FakeLlmRepository(answer = answer(todo("S1", "Call the tax office", "T1", certainty = "tentative")))
        assertEquals(TodoCertainty.CONFIRMED, useCase(tentative)("s1", firm, zone, "en").single().certainty)
    }

    // --- Lists read out, and ideas ------------------------------------------------------------------

    @Test
    fun `an item of a list read out is an explicit todo`() {
        val todos = spotted(
            "And one of the to do is to bake bread in the evening.",
            "One of my to-dos is to renew the passport.",
            "My next to do is to call the bank.",
            "Another todo is to water the plants.",
            "To do number two is to pay the rent.",
            "The first to-do is booking the hotel."
        )

        assertEquals(
            listOf("Bake bread", "Renew the passport", "Call the bank", "Water the plants", "Pay the rent", "Booking the hotel"),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN && it.kind == TodoKind.TASK })

        // Said in the morning, "in the evening" is that day's evening, and reminds then.
        val bread = runBlocking {
            useCase(nothing())("s1", listOf(seg("a", "And one of the to do is to bake bread in the evening.", at(10, 0))), zone, "en")
        }.single()
        assertEquals("Bake bread", bread.text)
        assertEquals(ms(10, 19), bread.dueAt)
        assertEquals(false, bread.dueHasTime)
        assertEquals(ms(10, 19), bread.remindAt)
    }

    @Test
    fun `an item of a german list read out is an explicit todo`() {
        val todos = spotted(
            "Eine meiner Aufgaben ist, einen Raum für Freitag zu buchen.",
            "Einer der Aufgaben ist die Garage aufzuräumen.",
            "Die nächste Aufgabe ist, die Rechnung zu bezahlen.",
            "Meine Aufgabe ist es, die Bank anzurufen.",
            language = "de"
        )

        assertEquals(listOf("Einen Raum buchen", "Die Garage aufräumen", "Die Rechnung bezahlen", "Die Bank anrufen"), todos.map { it.text })
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN })
        assertEquals(ms(16, 9), todos[0].dueAt)
    }

    @Test
    fun `speaking of a to-do without its is is none, and nothing to do still gives nothing`() {
        assertEquals(
            List(5) { emptyList<String>() },
            textsOf(
                "I noticed that one of the to do to wash the car in the morning was missed in the last note.",
                "A lot of to dos were being missed.",
                "There's nothing to do: the shop is closed.",
                "One of the to-dos is already done.",
                "Eine meiner Aufgaben ist nicht, die Bank anzurufen."
            )
        )
    }

    @Test
    fun `a list's cue said of a to-do, not before one, gives nothing`() {
        // Feedback on the to-dos themselves: what follows "is" says how a to-do is, not what to do.
        assertEquals(
            List(11) { emptyList<String>() },
            textsOf(
                "One of the to-dos is not picked up.",
                "One of my to-dos is done.",
                "One of the to dos is missing from the list.",
                "The last to-do is wrong.",
                "So one of the to dos is that the reminders come too late.",
                "One of the to-dos is getting old.",
                "The first to-do is still open.",
                "My next to-do is to relax.",
                "One of the to-dos is set for Friday."
            ) + textsOf(
                "Meine Aufgabe ist schwierig.",
                "Eine der Aufgaben ist verschwunden.",
                language = "de"
            )
        )
        assertEquals(emptyList<String>(), spotted("Eine der Aufgaben ist vergessen worden.", language = "de").map { it.text })
    }

    @Test
    fun `a list's cue before a thing to do as one dictates it, or before an appointment, gives it`() {
        val todos = spotted(
            "One of my to-dos is: call the plumber.",
            "My next to-do is buy milk.",
            "The second to-do is calling Anna back.",
            "Another to-do is picking up the kids.",
            "Die nächste Aufgabe ist Steuer machen.",
            "One of the to-dos is the dentist appointment on Friday at 3."
        )

        assertEquals(
            listOf("Call the plumber", "Buy milk", "Calling Anna back", "Picking up the kids", "Steuer machen", "Dentist appointment"),
            todos.map { it.text }
        )
        assertTrue(todos.all { it.capture == TodoCapture.EXPLICIT && it.status == TodoStatus.OPEN })
        assertEquals(TodoKind.APPOINTMENT, todos.last().kind)
        assertEquals(ms(16, 15), todos.last().dueAt)
    }

    @Test
    fun `a request to be reminded said inside a wish for the app is none, one asked of someone stays`() {
        assertEquals(
            List(3) { emptyList<String>() },
            textsOf(
                "It would be nice if the app could remind me to drink water every hour.",
                "The phone should remind me to take a break.",
                "There should be an option to remind me before every meeting."
            )
        )
        assertEquals(
            listOf("Call the bank"),
            spotted("It would be great if you could remind me to call the bank.").map { it.text }
        )
        assertEquals(listOf("Call the bank"), spotted("Could you remind me to call the bank").map { it.text })
    }

    @Test
    fun `idea and Idee with a colon say an idea, which takes no time and keeps its time words`() {
        val english = one("Idea: a dark mode for the cover screen.")
        val german = one("Idee: die Notizen jeden Freitag zusammenfassen.", language = "de")

        assertEquals("A dark mode for the cover screen", english.text)
        assertEquals("Die Notizen jeden Freitag zusammenfassen", german.text)
        for (idea in listOf(english, german)) {
            assertEquals(TodoStatus.IDEA, idea.status)
            assertEquals(TodoKind.TASK, idea.kind)
            assertEquals(null, idea.dueAt)
            assertEquals(null, idea.remindAt)
        }
        // Without the colon it is no idea said as one: what it is, is the model's to say.
        assertEquals(emptyList<Todo>(), spotted("My idea a dark mode for the cover screen."))
    }

    @Test
    fun `a model's copied sentence loses its time, copula and article, and a second sentence goes`() = runBlocking {
        // Nothing here for the spotter: the texts are the model's alone.
        val segments = listOf(
            seg("a", "The plumber visit is on Friday at 3 pm.", at(10, 0, 0)),
            // It names the plumber: an item's words must be in the segment it cites.
            seg("b", "The boiler is broken, and the plumber has the key.", at(10, 0, 30))
        )
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "The plumber visit is on Friday at 3 pm", "T1", kind = "appointment", capture = "explicit"),
                todo("S2", "Call the plumber. Then email the landlord", capture = "inferred")
            )
        )

        val (visit, plumber) = useCase(llm)("s1", segments, zone, "en")

        assertEquals("Plumber visit", visit.text)
        assertEquals(ms(16, 15), visit.dueAt)
        assertEquals("Call the plumber", plumber.text)
    }

    @Test
    fun `a todo is an appointment when either the spotter or the model says so`() = runBlocking {
        val segments = listOf(seg("a", "I have to see the dentist on Friday at 3.", at(10, 0)))

        val alone = useCase(nothing())("s1", segments, zone, "en").single()
        val joined = useCase(FakeLlmRepository(answer = answer(todo("S1", "See the dentist", "T1", kind = "appointment"))))(
            "s1", segments, zone, "en"
        ).single()

        assertEquals(TodoKind.TASK, alone.kind)
        assertEquals(ms(16, 15), alone.remindAt)
        assertEquals(TodoKind.APPOINTMENT, joined.kind)
        assertEquals(ms(16, 14), joined.remindAt)
    }

    @Test
    fun `a run ends at a model that can't be loaded, keeping what was found up to there`() = runBlocking {
        val talk = "We talked about the budget and the plans for the coming year in great detail. ".repeat(5).trim()
        val segments = (0 until 40).map { i ->
            val text = when (i) {
                0 -> "Remind me to call the bank. $talk"
                39 -> "Remind me to water the plants. $talk"
                else -> talk
            }
            seg("s$i", text, at(9, 0) + i * 20_000L)
        }
        val failing = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        val notLoaded = FakeLlmRepository(reloadFailure = IllegalStateException("no model"))

        val everyPart = useCase(failing)("s1", segments, zone, "en")
        val firstPart = useCase(notLoaded)("s1", segments, zone, "en")

        assertTrue("parts: ${failing.prompts.size}", failing.prompts.size > 1)
        assertEquals(listOf("Call the bank", "Water the plants"), everyPart.map { it.text })
        assertEquals(listOf("Call the bank"), firstPart.map { it.text })
        assertEquals(1, notLoaded.reloads)
    }

    private companion object {
        const val NOW = 1_000L
    }
}
