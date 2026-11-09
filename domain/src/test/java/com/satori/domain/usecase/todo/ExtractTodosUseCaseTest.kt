package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.time.RuleBasedTimeParser
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.time.TimePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ExtractTodosUseCaseTest {

    // Not the JVM's default zone, so code that reads a time in the default zone instead of the
    // recording's fails here.
    private val zone: ZoneId = listOf("Asia/Tokyo", "America/New_York").map(ZoneId::of)
        .first { it.rules != ZoneId.systemDefault().rules }
    private var ids = 0

    private fun useCase(llm: LlmRepository, parser: TimeExpressionParser = FakeTimeParser()) = FoundTodos(extraction(llm, parser))

    private fun extraction(llm: LlmRepository, parser: TimeExpressionParser = FakeTimeParser(), answers: AnswerLog = AnswerLog.NONE) =
        ExtractTodosUseCase(llm, TodoResources(), parser, now = { NOW }, newId = { "todo-${++ids}" }, answerLog = answers)

    /** The segment lines of a call's user message, below its header. */
    private fun segmentsOf(text: String): String = text.substringAfter("\n\n")

    private fun segmentsShown(llm: FakeLlmRepository): String = segmentsOf(llm.prompts.single().first)

    /** What the header of a call's user message lists as noted. */
    private fun notedIn(text: String): String = text.lines()[1].substringAfter("said for it: ")

    private fun notedShown(llm: FakeLlmRepository): String = notedIn(llm.prompts.single().first)

    /** One item of today's answers: a to-do, or with type idea an idea. */
    private fun item(segment: String, text: String, type: String = "task", whenId: String = "none", capture: String = "commitment") =
        if (type == "idea") """{"segment": "$segment", "type": "idea", "text": "$text"}"""
        else """{"segment": "$segment", "type": "$type", "text": "$text", "when": "$whenId", "capture": "$capture"}"""

    private fun items(vararg items: String) = """{"items": [${items.joinToString(", ")}]}"""

    /** Epoch ms of a wall-clock time in [zone], on Wednesday 14 October 2026 unless [day] says otherwise. */
    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 14): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun ms(dateTime: LocalDateTime): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long, complete: Boolean = true, session: String = "s1") =
        TranscriptionSegment(id = id, sessionId = session, text = text, timestamp = time, isComplete = complete)

    /** One to-do as answers of earlier builds gave it, which still read: most tests here use them. */
    private fun todo(
        segment: String,
        text: String,
        whenId: String = "none",
        kind: String = "task",
        capture: String = "commitment",
        certainty: String = "confirmed"
    ) = """{"segment": "$segment", "text": "$text", "when": "$whenId", "kind": "$kind", """ +
        """"capture": "$capture", "certainty": "$certainty"}"""

    private fun answer(vararg todos: String) = """{"todos": [${todos.joinToString(", ")}]}"""

    /** A todo the user already has, by default from another recording. */
    private fun known(
        text: String,
        status: TodoStatus = TodoStatus.OPEN,
        dueAt: Long? = null,
        dueHasTime: Boolean = false,
        segments: List<String> = listOf("elsewhere"),
        session: String = "old"
    ) = Todo(
        id = "known-$text", sessionId = session, text = text, status = status, dueAt = dueAt,
        dueHasTime = dueHasTime, sourceSegmentIds = segments, createdAt = 0L, updatedAt = 0L
    )

    @Test
    fun `two recordings get the same system prompt, their start and what is noted only in their own user messages`() = runBlocking {
        val llm = FakeLlmRepository(answer = items())

        useCase(llm)("s1", listOf(seg("a", "I need to book the hall for the party.", at(10, 41))), zone, "en")
        useCase(llm)(
            "s2",
            listOf(seg("b", "I need to water the plants on the balcony before we book the hall.", at(18, 5, day = 15), session = "s2")),
            zone,
            "en",
            existingTodos = listOf(known("Book the hall"))
        )

        val (first, second) = llm.prompts.map { it.first }
        assertEquals(listOf("todo prompt (en)", "todo prompt (en)"), llm.prompts.map { it.second })
        assertEquals("The recording started on 2026-10-14 (Wednesday) at 10:41.", first.lines()[0])
        assertEquals("The recording started on 2026-10-15 (Thursday) at 18:05.", second.lines()[0])
        assertEquals("-", notedIn(first))
        assertEquals("\"Book the hall\"", notedIn(second))
    }

    @Test
    fun `english note - the model reads numbered segments with their times and its todos come back resolved`() = runBlocking {
        val segments = listOf(
            seg("a", "Okay, a quick note before I forget.", at(10, 41, 0)),
            seg("b", "I have to call the bank tomorrow about the loan.", at(10, 41, 20)),
            seg("c", "And the dentist is on Friday at 9.", at(10, 41, 40)),
            seg("d", "Maybe we could repaint the kitchen at some point.", at(10, 42, 5))
        )
        val parser = FakeTimeParser()
            .on("tomorrow", LocalDate.of(2026, 10, 15))
            .at("Friday at 9", LocalDateTime.of(2026, 10, 16, 9, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S2", "Call the bank about the loan", "T1"),
                todo("S3", "Dentist", "T2", kind = "appointment", capture = "explicit"),
                todo("S4", "Repaint the kitchen", capture = "inferred", certainty = "tentative")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        val (text, systemPrompt) = llm.prompts.single()
        assertEquals(
            """
            The recording started on 2026-10-14 (Wednesday) at 10:41.
            Already noted, do not repeat unless a new time is said for it: -

            S1 Okay, a quick note before I forget.
            S2 I have to call the bank tomorrow about the loan.
              T1 = 'tomorrow' -> Thu 15 Oct (Thursday)
            S3 And the dentist is on Friday at 9.
              T2 = 'Friday at 9' -> Fri 16 Oct 09:00 (Friday)
            S4 Maybe we could repaint the kitchen at some point.
            """.trimIndent(),
            text
        )
        // The same for every call: nothing of this recording is in it.
        assertEquals("todo prompt (en)", systemPrompt)
        assertEquals(1, llm.reloads)

        val tomorrowAtNine = ms(LocalDateTime.of(2026, 10, 15, 9, 0))
        val fridayAtNine = ms(LocalDateTime.of(2026, 10, 16, 9, 0))
        val fridayAtEight = ms(LocalDateTime.of(2026, 10, 16, 8, 0))
        assertEquals(
            listOf(
                Todo(
                    id = "todo-1", sessionId = "s1", text = "Call the bank about the loan",
                    kind = TodoKind.TASK, status = TodoStatus.OPEN, certainty = TodoCertainty.CONFIRMED,
                    capture = TodoCapture.COMMITMENT, dueAt = tomorrowAtNine, dueHasTime = false,
                    timePhrase = "tomorrow", remindAt = tomorrowAtNine, sourceSegmentIds = listOf("b"),
                    createdAt = NOW, updatedAt = NOW
                ),
                Todo(
                    id = "todo-2", sessionId = "s1", text = "Dentist",
                    kind = TodoKind.APPOINTMENT, status = TodoStatus.OPEN, certainty = TodoCertainty.CONFIRMED,
                    capture = TodoCapture.EXPLICIT, dueAt = fridayAtNine, dueHasTime = true,
                    timePhrase = "Friday at 9", remindAt = fridayAtEight, sourceSegmentIds = listOf("c"),
                    createdAt = NOW, updatedAt = NOW
                ),
                // "we could" puts it forward as an idea, which nobody said they would do.
                Todo(
                    id = "todo-3", sessionId = "s1", text = "Repaint the kitchen",
                    kind = TodoKind.TASK, status = TodoStatus.IDEA, certainty = TodoCertainty.CONFIRMED,
                    capture = TodoCapture.INFERRED, dueAt = null, dueHasTime = false,
                    timePhrase = null, remindAt = null, sourceSegmentIds = listOf("d"),
                    createdAt = NOW, updatedAt = NOW
                )
            ),
            todos
        )
    }

    @Test
    fun `the answer is constrained to the segments and times of this call`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank tomorrow.", at(10, 0)),
            seg("b", "Nothing else.", at(10, 1)),
            seg("c", "Dentist on Friday at 9.", at(10, 2))
        )
        val parser = FakeTimeParser()
            .on("tomorrow", LocalDate.of(2026, 10, 15))
            .at("Friday at 9", LocalDateTime.of(2026, 10, 16, 9, 0))
        val llm = FakeLlmRepository(answer = answer())

        useCase(llm, parser)("s1", segments, zone, "en")

        val schema = Json.parseToJsonElement((llm.grammars.single() as OutputGrammar.JsonSchema).schema).jsonObject
        val items = schema.obj("properties").obj("items")
        assertEquals("array", items["type"]!!.jsonPrimitive.content)
        val (todo, idea) = items.obj("items")["anyOf"]!!.jsonArray.map { it.jsonObject.obj("properties") }
        assertEquals(listOf("S1", "S2", "S3"), todo.enumOf("segment"))
        assertEquals(listOf("none", "T1", "T2"), todo.enumOf("when"))
        assertEquals(listOf("task", "appointment"), todo.enumOf("type"))
        assertEquals(listOf("explicit", "commitment", "asked", "inferred"), todo.enumOf("capture"))
        assertEquals(listOf("S1", "S2", "S3"), idea.enumOf("segment"))
        assertEquals(listOf("idea"), idea.enumOf("type"))
    }

    @Test
    fun `the prompt shows a range with its end, a run of days, a part of the day by name, and no hour that wasn't said`() = runBlocking {
        val german = listOf(
            seg("a", "Am Freitag von 10 bis 12 Uhr ist der Zahnarzt.", at(10, 0)),
            seg("b", "Nächste Woche muss ich die Steuer machen.", at(10, 1)),
            seg("c", "Freitagabend gehen wir essen.", at(10, 2)),
            seg("d", "Morgen rufe ich die Bank an.", at(10, 3))
        )
        val english = listOf(
            seg("a", "On Friday from 10 to 11 am I see the dentist.", at(10, 0)),
            seg("b", "Next week I have to do my taxes.", at(10, 1)),
            seg("c", "I need to call mom tonight.", at(10, 2)),
            seg("d", "Tomorrow I call the bank.", at(10, 3))
        )
        val germanLlm = FakeLlmRepository(answer = answer())
        val englishLlm = FakeLlmRepository(answer = answer())

        useCase(germanLlm, RuleBasedTimeParser())("s1", german, zone, "de")
        useCase(englishLlm, RuleBasedTimeParser())("s1", english, zone, "en")

        assertEquals(
            """
            S1 Am Freitag von 10 bis 12 Uhr ist der Zahnarzt.
              T1 = 'Am Freitag von 10 bis 12 Uhr' -> Fr. 16. Okt. 10:00-12:00 (Freitag)
            S2 Nächste Woche muss ich die Steuer machen.
              T2 = 'Nächste Woche' -> Mo. 19. Okt. - So. 25. Okt.
            S3 Freitagabend gehen wir essen.
              T3 = 'Freitagabend' -> Fr. 16. Okt. abends (Freitag)
            S4 Morgen rufe ich die Bank an.
              T4 = 'Morgen' -> Do. 15. Okt. (Donnerstag)
            """.trimIndent(),
            segmentsShown(germanLlm)
        )
        assertEquals(
            """
            S1 On Friday from 10 to 11 am I see the dentist.
              T1 = 'On Friday from 10 to 11 am' -> Fri 16 Oct 10:00-11:00 (Friday)
            S2 Next week I have to do my taxes.
              T2 = 'Next week' -> Mon 19 Oct - Sun 25 Oct
            S3 I need to call mom tonight.
              T3 = 'tonight' -> Wed 14 Oct evening (Wednesday)
            S4 Tomorrow I call the bank.
              T4 = 'Tomorrow' -> Thu 15 Oct (Thursday)
            """.trimIndent(),
            segmentsShown(englishLlm)
        )
    }

    @Test
    fun `german note - german prompt and dates, and the time words leave the todo text`() = runBlocking {
        val segments = listOf(
            seg("a", "Ich muss morgen die Steuererklärung abschicken.", at(18, 5, 0)),
            seg("b", "Freitag um halb neun ist der Zahnarzttermin.", at(18, 5, 30))
        )
        val parser = FakeTimeParser()
            .on("morgen", LocalDate.of(2026, 10, 15))
            .at("Freitag um halb neun", LocalDateTime.of(2026, 10, 16, 8, 30))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Ich muss morgen die Steuererklärung abschicken", "T1"),
                todo("S2", "Zahnarzttermin", "T2", kind = "appointment", capture = "explicit")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "de")

        val (text, systemPrompt) = llm.prompts.single()
        assertEquals("todo prompt (de)", systemPrompt)
        assertTrue(text, text.startsWith("The recording started on 2026-10-14 (Mittwoch) at 18:05."))
        val lines = segmentsOf(text).lines()
        assertEquals("S1 Ich muss morgen die Steuererklärung abschicken.", lines[0])
        assertTrue(lines[1], lines[1].startsWith("  T1 = 'morgen' -> ") && lines[1].endsWith(" (Donnerstag)"))
        assertEquals("S2 Freitag um halb neun ist der Zahnarzttermin.", lines[2])
        assertTrue(lines[3], lines[3].startsWith("  T2 = 'Freitag um halb neun' -> ") && lines[3].endsWith(" 08:30 (Freitag)"))

        assertEquals(listOf("Die Steuererklärung abschicken", "Zahnarzttermin"), todos.map { it.text })
        val tax = todos[0]
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), tax.dueAt)
        assertEquals(false, tax.dueHasTime)
        assertEquals("morgen", tax.timePhrase)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), tax.remindAt)
        assertEquals(TodoStatus.OPEN, tax.status)
        val dentist = todos[1]
        assertEquals(TodoKind.APPOINTMENT, dentist.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 8, 30)), dentist.dueAt)
        assertEquals(true, dentist.dueHasTime)
        assertEquals("an appointment reminds an hour before", ms(LocalDateTime.of(2026, 10, 16, 7, 30)), dentist.remindAt)
    }

    @Test
    fun `an appointment with a clock time reminds an hour before, a timed task at its time, a date-only todo at the policy hour`() = runBlocking {
        val segments = listOf(
            seg("a", "Dentist on Friday at 3.", at(10, 0)),
            seg("b", "I have to send the report at 5.", at(10, 1)),
            seg("c", "The plumber comes on Thursday.", at(10, 2))
        )
        val parser = FakeTimeParser()
            .at("Friday at 3", LocalDateTime.of(2026, 10, 16, 15, 0))
            .at("at 5", LocalDateTime.of(2026, 10, 14, 17, 0))
            .on("on Thursday", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"),
                todo("S2", "Send the report", "T2"),
                todo("S3", "Plumber visit", "T3", kind = "appointment", capture = "explicit")
            )
        )

        val (dentist, report, plumber) = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(TodoKind.APPOINTMENT, dentist.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 15, 0)), dentist.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 14, 0)), dentist.remindAt)
        assertEquals(TodoKind.TASK, report.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 17, 0)), report.dueAt)
        assertEquals(report.dueAt, report.remindAt)
        assertEquals(TodoKind.APPOINTMENT, plumber.kind)
        assertEquals(false, plumber.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), plumber.dueAt)
        assertEquals(plumber.dueAt, plumber.remindAt)
    }

    @Test
    fun `an appointment less than an hour away when it was said reminds at its start`() = runBlocking {
        val parser = FakeTimeParser().at("at 3", LocalDateTime.of(2026, 10, 14, 15, 0))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call with Bob", "T1", kind = "appointment")))

        val call = useCase(llm, parser)("s1", listOf(seg("a", "The call with Bob is at 3.", at(14, 30))), zone, "en").single()

        assertEquals(TodoKind.APPOINTMENT, call.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 15, 0)), call.dueAt)
        assertEquals(call.dueAt, call.remindAt)
    }

    @Test
    fun `a day picked while its clock time was said apart in the same sentence gets that clock time`() = runBlocking {
        val segments = listOf(
            seg("a", "Tomorrow I have the dentist at 3.", at(10, 0)),
            seg("b", "Tomorrow I also have the haircut at 5.", at(10, 1))
        )
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            .at("at 3", LocalDateTime.of(2026, 10, 15, 15, 0))
            .at("at 5", LocalDateTime.of(2026, 10, 15, 17, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                // The day, though the sentence also says the clock time.
                todo("S1", "Dentist at 3", "T1", kind = "appointment", capture = "explicit"),
                // The clock time, with the day still in the text.
                todo("S2", "Haircut tomorrow", "T4", kind = "appointment", capture = "explicit")
            )
        )

        val (dentist, haircut) = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals("Dentist", dentist.text)
        assertEquals(TodoKind.APPOINTMENT, dentist.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(true, dentist.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Tomorrow at 3", dentist.timePhrase)
        assertEquals(listOf("a"), dentist.sourceSegmentIds)

        assertEquals("Haircut", haircut.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 17, 0)), haircut.dueAt)
        assertEquals("Tomorrow at 5", haircut.timePhrase)
    }

    @Test
    fun `german - a day and a clock time said apart in one sentence give the todo the clock time`() = runBlocking {
        val segments = listOf(
            seg("a", "Morgen habe ich um 15 Uhr Zahnarzt.", at(18, 0)),
            seg("b", "Am Freitag muss ich um 9 beim Amt sein.", at(18, 1))
        )
        val parser = FakeTimeParser()
            .on("Morgen", LocalDate.of(2026, 10, 15))
            .at("um 15 Uhr", LocalDateTime.of(2026, 10, 15, 15, 0))
            .on("Am Freitag", LocalDate.of(2026, 10, 16))
            .at("um 9", LocalDateTime.of(2026, 10, 16, 9, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Morgen Zahnarzt", "T1", kind = "appointment", capture = "explicit"),
                todo("S2", "Am Freitag muss ich beim Amt sein", "T3", kind = "appointment")
            )
        )

        val (dentist, office) = useCase(llm, parser)("s1", segments, zone, "de")

        assertEquals("Zahnarzt", dentist.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(true, dentist.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Morgen um 15 Uhr", dentist.timePhrase)

        assertEquals("Beim Amt sein", office.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), office.dueAt)
        assertEquals(true, office.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 8, 0)), office.remindAt)
        assertEquals("Am Freitag um 9", office.timePhrase)
    }

    @Test
    fun `a sentence that runs on into the next segment lends that segment's clock time its day`() = runBlocking {
        // The next segment starts in lower case, three seconds on.
        val segments = listOf(
            seg("a", "Tomorrow I have the dentist", at(10, 0, 0)),
            seg("b", "at 3, so I leave work early.", at(10, 0, 3))
        )
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            // The real parser gives a bare clock time the day said earlier in its sentence.
            .at("at 3", LocalDateTime.of(2026, 10, 15, 15, 0), onlyWith = "Tomorrow")
            .at("at 3", LocalDateTime.of(2026, 10, 14, 15, 0))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit")))

        val dentist = useCase(llm, parser)("s1", segments, zone, "en").single()

        assertEquals("Tomorrow I have the dentist at 3, so I leave work early.", parser.calls[1].first)
        assertEquals(
            """
            S1 Tomorrow I have the dentist
              T1 = 'Tomorrow' -> Thu 15 Oct (Thursday)
            S2 at 3, so I leave work early.
              T2 = 'at 3' -> Thu 15 Oct 15:00 (Thursday)
            """.trimIndent(),
            segmentsShown(llm)
        )
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(true, dentist.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Tomorrow at 3", dentist.timePhrase)
        assertEquals(listOf("a", "b"), dentist.sourceSegmentIds)
    }

    @Test
    fun `with the real parser, a sentence split over two segments keeps its day and clock time`() = runBlocking {
        val german = listOf(seg("a", "Morgen muss ich", at(10, 0, 0)), seg("b", "um 9 beim Arzt sein.", at(10, 0, 2)))
        val english = listOf(seg("a", "Tomorrow I have the dentist", at(10, 0, 0)), seg("b", "at 3, so I leave work early.", at(10, 0, 3)))
        // The model picks the day each time.
        val pickDay = answer(todo("S2", "Beim Arzt sein", "T1", kind = "appointment", capture = "explicit"))
        val pickDayEn = answer(todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"))

        val germanLlm = FakeLlmRepository(answer = pickDay)
        val doctor = useCase(germanLlm, RuleBasedTimeParser())("s1", german, zone, "de").single()
        val dentist = useCase(FakeLlmRepository(answer = pickDayEn), RuleBasedTimeParser())("s1", english, zone, "en").single()

        val shown = segmentsShown(germanLlm).lines()
        assertTrue(shown.joinToString("\n"), shown[3].startsWith("  T2 = 'um 9' -> ") && shown[3].endsWith(" 09:00 (Donnerstag)"))
        assertEquals("Beim Arzt sein", doctor.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), doctor.dueAt)
        assertEquals(true, doctor.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 8, 0)), doctor.remindAt)
        assertEquals("Morgen um 9", doctor.timePhrase)
        assertEquals(listOf("a", "b"), doctor.sourceSegmentIds)

        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Tomorrow at 3", dentist.timePhrase)
    }

    @Test
    fun `a clock time from another sentence of the segment is not added to a day`() = runBlocking {
        // Said at 16:00, "At 3" alone is the next 3 o'clock: tomorrow as well, but another sentence.
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            .at("At 3", LocalDateTime.of(2026, 10, 15, 15, 0))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit")))

        val dentist = useCase(llm, parser)(
            "s1", listOf(seg("a", "Tomorrow I have the dentist. At 3 I call Bob.", at(16, 0))), zone, "en"
        ).single()

        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), dentist.dueAt)
        assertEquals(false, dentist.dueHasTime)
        assertEquals("Tomorrow", dentist.timePhrase)
    }

    @Test
    fun `with the real parser, a next segment that starts with a digit goes on with the sentence`() = runBlocking {
        val segments = listOf(seg("a", "Morgen muss ich", at(10, 0, 0)), seg("b", "9 Uhr beim Arzt sein.", at(10, 0, 2)))
        val llm = FakeLlmRepository(answer = answer(todo("S2", "Beim Arzt sein", "T2", kind = "appointment", capture = "explicit")))

        val doctor = useCase(llm, RuleBasedTimeParser())("s1", segments, zone, "de").single()

        assertEquals("  T2 = '9 Uhr' -> Do. 15. Okt. 09:00 (Donnerstag)", segmentsShown(llm).lines()[3])
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), doctor.dueAt)
        assertEquals(true, doctor.dueHasTime)
        assertEquals("Morgen 9 Uhr", doctor.timePhrase)
    }

    @Test
    fun `with the real parser, a long next segment stamped at its end goes on with the sentence`() = runBlocking {
        // Stamped four seconds on, but the stamp is where it ends: it began about a second after "Morgen muss ich".
        val segments = listOf(
            seg("a", "Morgen muss ich", at(10, 0, 0)),
            seg("b", "um 9 beim Arzt sein und danach noch zur Apotheke gehen.", at(10, 0, 4))
        )
        val llm = FakeLlmRepository(answer = answer(todo("S2", "Beim Arzt sein", "T2", kind = "appointment", capture = "explicit")))

        val doctor = useCase(llm, RuleBasedTimeParser())("s1", segments, zone, "de").single()

        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), doctor.dueAt)
        assertEquals("Morgen um 9", doctor.timePhrase)
    }

    @Test
    fun `a segment that ends its sentence lends the next one nothing`() = runBlocking {
        assertNothingLent(seg("a", "Tomorrow I have the dentist.", at(10, 0, 0)), seg("b", "at 3 I call Bob.", at(10, 0, 2)))
    }

    @Test
    fun `a segment said more than three seconds before lends the next one nothing`() = runBlocking {
        assertNothingLent(seg("a", "Tomorrow I have the dentist", at(10, 0, 0)), seg("b", "at 3 I call Bob.", at(10, 0, 4)))
    }

    @Test
    fun `a segment that starts with a capital starts a sentence of its own and borrows nothing`() = runBlocking {
        assertNothingLent(seg("a", "Tomorrow I have the dentist", at(10, 0, 0)), seg("b", "At 3 I call Bob.", at(10, 0, 2)))
    }

    /** "At 3" in [second] is read on its own: today at 15:00, and the dentist keeps the day alone. */
    private suspend fun assertNothingLent(first: TranscriptionSegment, second: TranscriptionSegment) {
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            .at("At 3", LocalDateTime.of(2026, 10, 15, 15, 0), onlyWith = "Tomorrow")
            .at("At 3", LocalDateTime.of(2026, 10, 14, 15, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"),
                todo("S2", "Call Bob", "T2")
            )
        )

        val (dentist, bob) = useCase(llm, parser)("s1", listOf(first, second), zone, "en")

        assertEquals(second.text, parser.calls[1].first)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), dentist.dueAt)
        assertEquals(false, dentist.dueHasTime)
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 15, 0)), bob.dueAt)
    }

    @Test
    fun `with the real parser, a time cut down to its unit word at a segment's start is not listed`() = runBlocking {
        val segments = listOf(
            seg("a", "Wir treffen uns morgen um 9", at(10, 0, 0)),
            seg("b", "uhr am Bahnhof, da muss ich pünktlich sein.", at(10, 0, 2))
        )
        val llm = FakeLlmRepository(answer = answer())

        useCase(llm, RuleBasedTimeParser())("s1", segments, zone, "de")

        assertEquals(
            listOf(
                "S1 Wir treffen uns morgen um 9",
                "  T1 = 'morgen um 9' -> Do. 15. Okt. 09:00 (Donnerstag)",
                "S2 uhr am Bahnhof, da muss ich pünktlich sein."
            ),
            segmentsShown(llm).lines()
        )
    }

    @Test
    fun `a clock time another todo of the answer uses is not added to a day`() = runBlocking {
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            .at("at 3", LocalDateTime.of(2026, 10, 15, 15, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T2", kind = "appointment", capture = "explicit"),
                todo("S1", "Call the bank", "T1")
            )
        )

        val (dentist, bank) = useCase(llm, parser)(
            "s1", listOf(seg("a", "Tomorrow I have the dentist at 3 and I have to call the bank.", at(10, 0))), zone, "en"
        )

        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Tomorrow at 3", dentist.timePhrase)
        assertEquals("Call the bank", bank.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), bank.dueAt)
        assertEquals(false, bank.dueHasTime)
        assertEquals("Tomorrow", bank.timePhrase)
    }

    @Test
    fun `with the real parser, a clock time from another clause is not added to the day a task picked`() = runBlocking {
        val german = listOf(seg("a", "Morgen muss ich die Steuererklärung abgeben, um 18 Uhr ist dann noch Training.", at(10, 0)))
        val english = listOf(seg("a", "Tomorrow I need to call the bank, and at 3 I watch the football.", at(10, 0)))
        val germanLlm = FakeLlmRepository(answer = answer(todo("S1", "Steuererklärung abgeben", "T1")))
        val englishLlm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank", "T1")))

        val tax = useCase(germanLlm, RuleBasedTimeParser())("s1", german, zone, "de").single()
        val bank = useCase(englishLlm, RuleBasedTimeParser())("s1", english, zone, "en").single()

        // The parser lends the clock times the day: they are there to be taken, but not by these todos.
        assertTrue(germanLlm.prompts.single().first.contains("T2 = 'um 18 Uhr' -> Do. 15. Okt. 18:00"))
        assertTrue(englishLlm.prompts.single().first.contains("T2 = 'at 3' -> Thu 15 Oct 15:00"))
        for (todo in listOf(tax, bank)) {
            assertEquals(todo.text, ms(LocalDateTime.of(2026, 10, 15, 9, 0)), todo.dueAt)
            assertEquals(todo.text, false, todo.dueHasTime)
            assertEquals(todo.text, todo.dueAt, todo.remindAt)
        }
        assertEquals("Morgen", tax.timePhrase)
        assertEquals("Tomorrow", bank.timePhrase)
    }

    @Test
    fun `with the real parser, a clock time another todo's text names is not given to the appointment`() = runBlocking {
        // "Call Bob at 3" names the clock time, though it picked none.
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"),
                todo("S1", "Call Bob at 3")
            )
        )

        val (dentist, bob) = useCase(llm, RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Tomorrow I have the dentist, and at 3 I call Bob.", at(10, 0))), zone, "en"
        )

        assertTrue(llm.prompts.single().first.contains("T2 = 'at 3' -> Thu 15 Oct 15:00"))
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), dentist.dueAt)
        assertEquals(false, dentist.dueHasTime)
        assertEquals("Tomorrow", dentist.timePhrase)
        assertEquals("Call Bob at 3", bob.text)
        assertNull(bob.dueAt)
    }

    @Test
    fun `with the real parser, a clock time goes to the todo whose text names it, else to the one appointment`() = runBlocking {
        // Two todos picked the day; the appointment takes the one clock time.
        val leave = listOf(seg("a", "Tomorrow I have the dentist at 3, so I have to leave work early.", at(10, 0)))
        val leaveLlm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"),
                todo("S1", "Leave work early", "T1")
            )
        )
        val (dentist, work) = useCase(leaveLlm, RuleBasedTimeParser())("s1", leave, zone, "en")
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Tomorrow at 3", dentist.timePhrase)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), work.dueAt)
        assertEquals(false, work.dueHasTime)

        // A task that names the clock time takes it; the other todo of the day doesn't.
        val bank = listOf(seg("a", "Tomorrow I need to call the bank at 3 and pick up the kids.", at(10, 0)))
        val bankLlm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank at 3", "T1"), todo("S1", "Pick up the kids", "T1")))
        val (call, kids) = useCase(bankLlm, RuleBasedTimeParser())("s1", bank, zone, "en")
        assertEquals("Call the bank", call.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), call.dueAt)
        assertEquals(call.dueAt, call.remindAt)
        assertEquals(false, kids.dueHasTime)
    }

    @Test
    fun `with the real parser, two clock times in a sentence go each to the todo that names it`() = runBlocking {
        val german = listOf(seg("a", "Morgen habe ich um 15 Uhr Zahnarzt und um 17 Uhr Friseur.", at(10, 0)))
        val named = FakeLlmRepository(
            answer = answer(
                todo("S1", "Zahnarzt um 15 Uhr", "T1", kind = "appointment", capture = "explicit"),
                todo("S1", "Friseur um 17 Uhr", "T1", kind = "appointment", capture = "explicit")
            )
        )
        val (dentist, hairdresser) = useCase(named, RuleBasedTimeParser())("s1", german, zone, "de")
        assertEquals("Zahnarzt", dentist.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentist.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 14, 0)), dentist.remindAt)
        assertEquals("Friseur", hairdresser.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 17, 0)), hairdresser.dueAt)

        val english = listOf(seg("a", "Tomorrow I have the dentist at 3 and the haircut at 5.", at(10, 0)))
        val englishNamed = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist at 3", "T1", kind = "appointment", capture = "explicit"),
                todo("S1", "Haircut at 5", "T1", kind = "appointment", capture = "explicit")
            )
        )
        val (dentistEn, haircut) = useCase(englishNamed, RuleBasedTimeParser())("s1", english, zone, "en")
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), dentistEn.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 17, 0)), haircut.dueAt)

        // Named by neither, two clock times can't be told apart: both stay date-only.
        val unnamed = FakeLlmRepository(
            answer = answer(
                todo("S1", "Dentist", "T1", kind = "appointment", capture = "explicit"),
                todo("S1", "Haircut", "T1", kind = "appointment", capture = "explicit")
            )
        )
        val both = useCase(unnamed, RuleBasedTimeParser())("s1", english, zone, "en")
        assertEquals(listOf(false, false), both.map { it.dueHasTime })
    }

    @Test
    fun `each segment's times are resolved from the moment it was spoken, in the recording's zone`() = runBlocking {
        val parser = FakeTimeParser()
        val llm = FakeLlmRepository(answer = answer())
        val segments = listOf(
            seg("late", "Still Wednesday.", at(23, 59, 30)),
            seg("early", "Already Thursday.", at(0, 0, 30, day = 15))
        )

        useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(
            listOf(
                "Still Wednesday." to ZonedDateTime.of(2026, 10, 14, 23, 59, 30, 0, zone),
                "Already Thursday." to ZonedDateTime.of(2026, 10, 15, 0, 0, 30, 0, zone)
            ),
            parser.calls
        )
        assertEquals("S1 Still Wednesday.\nS2 Already Thursday.", segmentsShown(llm))
        assertTrue(llm.prompts.single().first.startsWith("The recording started on 2026-10-14 (Wednesday) at 23:59."))
    }

    @Test
    fun `an empty answer gives no todos`() = runBlocking {
        val llm = FakeLlmRepository(answer = """{"todos": []}""")

        val todos = useCase(llm)("s1", listOf(seg("a", "Lovely weather today.", at(9, 0))), zone, "en")

        assertEquals(emptyList<Todo>(), todos)
        assertEquals(1, llm.prompts.size)
    }

    @Test
    fun `without spoken words of this recording the model is not loaded`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank")))
        val segments = listOf(
            seg("blank", "   ", at(9, 0)),
            seg("partial", "I have to call the bank", at(9, 1), complete = false),
            seg("other", "Call mom", at(9, 2), session = "s2")
        )

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertEquals(emptyList<Todo>(), todos)
        assertEquals(0, llm.reloads)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `only complete segments of this recording are shown, in the order they were spoken`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer())
        val segments = listOf(
            seg("second", "Then this.", at(9, 1)),
            seg("partial", "half a sent", at(9, 2), complete = false),
            seg("first", "First this.", at(9, 0)),
            seg("other", "Not mine.", at(9, 0), session = "s2")
        )

        useCase(llm)("s1", segments, zone, "en")

        assertEquals("S1 First this.\nS2 Then this.", segmentsShown(llm))
    }

    @Test
    fun `todos citing an unknown segment are dropped and an unknown time is ignored`() = runBlocking {
        val segments = listOf(
            seg("a", "I'll send the report on Friday.", at(9, 0)),
            seg("b", "Anna asked me to book the venue.", at(9, 1))
        )
        val parser = FakeTimeParser().on("Friday", LocalDate.of(2026, 10, 16))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S9", "Water the plants"),
                todo("S2", "Book the venue", "T5", kind = "appointment", capture = "asked"),
                todo("S1", "Send the report", "T1")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(listOf("Send the report", "Book the venue"), todos.map { it.text })
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), todos[0].dueAt)
        val venue = todos[1]
        assertEquals(listOf("b"), venue.sourceSegmentIds)
        assertNull(venue.dueAt)
        assertNull(venue.remindAt)
        assertNull(venue.timePhrase)
        assertEquals(TodoKind.TASK, venue.kind)
        assertEquals(TodoCapture.ASKED_BY_OTHER, venue.capture)
        assertEquals(TodoStatus.SUGGESTED, venue.status)
    }

    @Test
    fun `a time the model makes up, takes from far away or that lies in the past is not used`() = runBlocking {
        val segments = listOf(
            seg("a", "Tomorrow is the team lunch.", at(9, 0)),
            seg("b", "Yesterday I promised to fix the printer.", at(9, 1)),
            seg("c", "Some words about the plumber in between.", at(9, 2)),
            seg("d", "And I need to renew my passport.", at(9, 3)),
            seg("e", "At 8 this morning I was meant to email the landlord.", at(9, 4)),
            seg("f", "Today I still have to pay the rent.", at(10, 30))
        )
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(2026, 10, 15))
            .on("Yesterday", LocalDate.of(2026, 10, 13))
            .at("At 8 this morning", LocalDateTime.of(2026, 10, 14, 8, 0))
            .on("Today", LocalDate.of(2026, 10, 14))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S2", "Fix the printer", "T2"),
                todo("S3", "Call the plumber", "15:00"),
                todo("S4", "Renew my passport", "T1"),
                todo("S5", "Email the landlord", "T3"),
                todo("S6", "Pay the rent", "T4")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(
            listOf("Fix the printer", "Call the plumber", "Renew my passport", "Email the landlord", "Pay the rent"),
            todos.map { it.text }
        )
        todos.take(4).forEach { assertNull(it.text, it.dueAt) }
        val rent = todos[4]
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 9, 0)), rent.dueAt)
        assertEquals(false, rent.dueHasTime)
        assertNull("a reminder time that had passed when it was said", rent.remindAt)
    }

    @Test
    fun `with the real parser, a part of the day alone is due at its usual hour without a clock time`() = runBlocking {
        val segments = listOf(
            seg("a", "Ich muss heute Abend Mama anrufen.", at(10, 41)),
            seg("b", "Freitagabend gehe ich mit Anna essen.", at(10, 42)),
            seg("c", "Morgen früh muss ich den Müll rausbringen.", at(10, 43))
        )
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Mama anrufen", "T1"),
                todo("S2", "Mit Anna essen gehen", "T2", kind = "appointment"),
                todo("S3", "Müll rausbringen", "T3")
            )
        )

        val (mom, dinner, trash) = useCase(llm, RuleBasedTimeParser())("s1", segments, zone, "de")

        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 19, 0)), mom.dueAt)
        assertEquals(false, mom.dueHasTime)
        assertEquals("heute Abend", mom.timePhrase)
        assertEquals("a task reminds at the part's hour", mom.dueAt, mom.remindAt)
        assertEquals(TodoKind.APPOINTMENT, dinner.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 19, 0)), dinner.dueAt)
        assertEquals(false, dinner.dueHasTime)
        assertEquals("an appointment an hour before it", ms(LocalDateTime.of(2026, 10, 16, 18, 0)), dinner.remindAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 8, 0)), trash.dueAt)
        assertEquals(trash.dueAt, trash.remindAt)
        // English too.
        val tonight = useCase(FakeLlmRepository(answer = answer(todo("S1", "Call mom", "T1"))), RuleBasedTimeParser())(
            "s1", listOf(seg("a", "I need to call mom tonight.", at(15, 0))), zone, "en"
        ).single()
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 19, 0)), tonight.dueAt)
        assertEquals(tonight.dueAt, tonight.remindAt)
    }

    @Test
    fun `with the real parser, a part of the day whose reminder had passed when it was said keeps its day`() = runBlocking {
        val late = useCase(FakeLlmRepository(answer = answer(todo("S1", "Call mom", "T1"))), RuleBasedTimeParser())(
            "s1", listOf(seg("a", "I need to call mom tonight.", at(19, 30))), zone, "en"
        ).single()
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 19, 0)), late.dueAt)
        assertNull(late.remindAt)

        // Said half an hour before the evening: the hour before has passed, and the evening names no time to fall back on.
        val dinner = useCase(
            FakeLlmRepository(answer = answer(todo("S1", "Mit Anna essen gehen", "T1", kind = "appointment"))),
            RuleBasedTimeParser()
        )("s1", listOf(seg("a", "Heute Abend gehe ich mit Anna essen.", at(18, 30))), zone, "de").single()
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 19, 0)), dinner.dueAt)
        assertEquals(TodoKind.APPOINTMENT, dinner.kind)
        assertNull(dinner.remindAt)
    }

    @Test
    fun `with the real parser, a range of clock times is due at its start and counts until its end`() = runBlocking {
        // Said at nine, the visit from eight to twelve is still running.
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Handwerker kommt", "T1", kind = "appointment")))
        val visit = useCase(llm, RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Der Handwerker kommt heute zwischen 8 und 12 Uhr.", at(9, 0))), zone, "de"
        ).single()

        assertEquals("  T1 = 'heute zwischen 8 und 12 Uhr' -> Mi. 14. Okt. 08:00-12:00 (Mittwoch)", segmentsShown(llm).lines()[1])
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 8, 0)), visit.dueAt)
        assertEquals(true, visit.dueHasTime)
        assertEquals("heute zwischen 8 und 12 Uhr", visit.timePhrase)
        assertNull("the hour before and the start had both passed", visit.remindAt)

        // Over when it was said: no time.
        val bill = useCase(FakeLlmRepository(answer = answer(todo("S1", "Rechnung bezahlen", "T1"))), RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Heute von 13 bis 14 Uhr war ich beim Arzt, die Rechnung muss ich bezahlen.", at(15, 0))), zone, "de"
        ).single()
        assertNull(bill.dueAt)
    }

    @Test
    fun `with the real parser, numbers that count something after a day are no hours and stay in the text`() = runBlocking {
        val slidesLlm = FakeLlmRepository(answer = answer(todo("S1", "Am Montag 2 bis 3 Folien vorbereiten", "T1")))
        val slides = useCase(slidesLlm, RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Am Montag 2 bis 3 Folien vorbereiten.", at(10, 0))), zone, "de"
        ).single()
        assertEquals("  T1 = 'Am Montag' -> Mo. 19. Okt. (Montag)", segmentsShown(slidesLlm).lines()[1])
        assertEquals("2 bis 3 Folien vorbereiten", slides.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 19, 9, 0)), slides.dueAt)
        assertEquals(false, slides.dueHasTime)
        assertEquals("Am Montag", slides.timePhrase)

        val boardLlm = FakeLlmRepository(answer = answer(todo("S1", "Prepare 2 to 3 slides for the board", "T1")))
        val board = useCase(boardLlm, RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Friday 2 to 3 slides for the board.", at(10, 0))), zone, "en"
        ).single()
        assertEquals("Prepare 2 to 3 slides for the board", board.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), board.dueAt)
        assertEquals(false, board.dueHasTime)
    }

    @Test
    fun `with the real parser, a run of days is due on its likely day, tentatively`() = runBlocking {
        val taxLlm = FakeLlmRepository(answer = answer(todo("S1", "Die Steuer machen", "T1")))
        // Said on a Thursday, the end of the week is Thursday and Friday: due Friday.
        val tax = useCase(taxLlm, RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Bis Ende der Woche muss ich die Steuer machen.", at(10, 41, day = 15))), zone, "de"
        ).single()
        assertEquals("  T1 = 'Ende der Woche' -> Do. 15. Okt. - Fr. 16. Okt.", segmentsShown(taxLlm).lines()[1])
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), tax.dueAt)
        assertEquals(false, tax.dueHasTime)
        assertEquals(tax.dueAt, tax.remindAt)
        assertEquals(TodoCertainty.TENTATIVE, tax.certainty)

        val rent = useCase(FakeLlmRepository(answer = answer(todo("S1", "Miete überweisen", "T1"))), RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Die Miete muss bis Ende des Monats überwiesen sein.", at(10, 41))), zone, "de"
        ).single()
        assertEquals(ms(LocalDateTime.of(2026, 10, 31, 9, 0)), rent.dueAt)
        assertEquals(rent.dueAt, rent.remindAt)
        assertEquals(TodoCertainty.TENTATIVE, rent.certainty)

        // Said on its last day, the end of the week is still due that day.
        val friday = useCase(FakeLlmRepository(answer = answer(todo("S1", "Die Steuer machen", "T1"))), RuleBasedTimeParser())(
            "s1", listOf(seg("a", "Bis Ende der Woche muss ich die Steuer machen.", at(10, 41, day = 16))), zone, "de"
        ).single()
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), friday.dueAt)
        assertNull(friday.remindAt)
    }

    @Test
    fun `a run of days counts until its last day`() = runBlocking {
        // Read on a Wednesday as Monday to Sunday, most likely Monday.
        val parser = FakeTimeParser()
            .days("diese Woche", LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 18), likely = LocalDate.of(2026, 10, 12))
            .days("letzte Woche", LocalDate.of(2026, 10, 5)..LocalDate.of(2026, 10, 11), likely = LocalDate.of(2026, 10, 5))
        val segments = listOf(
            seg("a", "Ich muss diese Woche die Steuer machen.", at(10, 0)),
            seg("b", "Ich wollte letzte Woche den Keller aufräumen.", at(10, 1))
        )
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Die Steuer machen", "T1"), todo("S2", "Den Keller aufräumen", "T2")))

        val (tax, cellar) = useCase(llm, parser)("s1", segments, zone, "de")

        assertEquals("  T1 = 'diese Woche' -> Mo. 12. Okt. - So. 18. Okt.", segmentsShown(llm).lines()[1])
        assertEquals(ms(LocalDateTime.of(2026, 10, 12, 9, 0)), tax.dueAt)
        assertEquals("diese Woche", tax.timePhrase)
        assertNull("its likely day had passed", tax.remindAt)
        assertNull("all of it was over", cellar.dueAt)
    }

    @Test
    fun `a time said in the next segment belongs to the todo and both segments are its source`() = runBlocking {
        val segments = listOf(
            seg("a", "I still need to finish the slides,", at(9, 0, 0)),
            seg("b", "by Friday at noon at the latest.", at(9, 0, 10))
        )
        val parser = FakeTimeParser().at("Friday at noon", LocalDateTime.of(2026, 10, 16, 12, 0))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Finish the slides", "T1")))

        val todo = useCase(llm, parser)("s1", segments, zone, "en").single()

        assertEquals(listOf("a", "b"), todo.sourceSegmentIds)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 12, 0)), todo.dueAt)
        assertEquals(true, todo.dueHasTime)
        assertEquals(todo.dueAt, todo.remindAt)
    }

    @Test
    fun `an ambiguous reading or a hedged time is tentative`() = runBlocking {
        val segments = listOf(
            seg("a", "I'll visit grandma next Friday.", at(9, 0)),
            seg("b", "Maybe Monday at 10 I'll call the tax office.", at(9, 1))
        )
        val parser = FakeTimeParser()
            .on("next Friday", LocalDate.of(2026, 10, 23), confidence = 0.4f)
            .at("Monday at 10", LocalDateTime.of(2026, 10, 19, 10, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Visit grandma", "T1", certainty = "confirmed"),
                todo("S2", "Call the tax office", "T2", certainty = "tentative")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(listOf(TodoCertainty.TENTATIVE, TodoCertainty.TENTATIVE), todos.map { it.certainty })
    }

    @Test
    fun `the caller's time policy reaches the parser and sets the hour of date-only todos`() = runBlocking {
        val policy = TimePolicy(dateOnlyHour = 8, nextWeekdayMeansFollowingWeek = true)
        val parser = FakeTimeParser().on("on Friday", LocalDate.of(2026, 10, 16))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Water the plants", "T1")))

        val todo = useCase(llm, parser)(
            "s1", listOf(seg("a", "I'll water the plants on Friday.", at(9, 0))), zone, "en", policy = policy
        ).single()

        assertEquals(listOf(policy), parser.policies)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 8, 0)), todo.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 8, 0)), todo.remindAt)
    }

    @Test
    fun `a runaway answer adds no more todos than one call may hold`() = runBlocking {
        val names = listOf("Anna", "Ben", "Cleo", "Dan", "Eva", "Finn", "Gus", "Hana", "Ida", "Jon", "Kai", "Lea")
        val llm = FakeLlmRepository(answer = answer(*names.map { todo("S1", "Call $it") }.toTypedArray()))

        // Dictated as a note, so the todos stay open and only the call's limit holds them back.
        val todos = useCase(llm)("s1", listOf(seg("a", "Call ${names.joinToString(", ")} back.", at(9, 0))), zone, "en")

        assertEquals(names.take(TodoPrompt.MAX_ITEMS).map { "Call $it" }, todos.map { it.text })
    }

    @Test
    fun `the model's todos said without a cue are only suggested, and a recording keeps three at most`() = runBlocking {
        val names = listOf("Anna", "Ben", "Cleo", "Dan", "Eva")
        val llm = FakeLlmRepository(answer = answer(*names.map { todo("S1", "Call $it") }.toTypedArray()))

        // Said without a cue, so every todo is the model's reading of it.
        val todos = useCase(llm)("s1", listOf(seg("a", "Everyone is waiting for a call back.", at(9, 0))), zone, "en")

        assertEquals(names.take(TodoGate.MAX_SUGGESTIONS).map { "Call $it" }, todos.map { it.text })
        assertTrue(todos.all { it.status == TodoStatus.SUGGESTED && it.capture == TodoCapture.INFERRED })
    }

    @Test
    fun `feedback with four ideas and two to-dos gives four ideas, one open to-do and one suggestion`() = runBlocking {
        val segments = listOf(
            seg("a", "Old recordings take a lot of space. So there should be an option to remove older audio files, not the notes.", at(9, 0)),
            seg("b", "Also the buzz at the start is too weak. So it's should be much stronger.", at(9, 1)),
            seg("c", "It would be good to show the place where a note was made.", at(9, 2)),
            seg("d", "And maybe make the clock on the cover bigger.", at(9, 3)),
            seg("e", "I need to send the invoice to the landlord tomorrow.", at(9, 4)),
            seg("f", "The car is due for its service soon.", at(9, 5))
        )
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Option to remove older audio files", type = "idea"),
                // The model takes it for a to-do; its sentence puts it forward as an idea.
                item("S2", "Make the start buzz stronger", capture = "inferred"),
                item("S3", "Show the place of each note", type = "idea"),
                // No cue says so: the model's word alone makes it one.
                item("S4", "Bigger clock on the cover", type = "idea"),
                item("S5", "Send the invoice to the landlord", whenId = "T1"),
                item("S6", "Book the car service", capture = "inferred")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(
            listOf(
                "Option to remove older audio files" to TodoStatus.IDEA,
                "Make the start buzz stronger" to TodoStatus.IDEA,
                "Show the place of each note" to TodoStatus.IDEA,
                "Bigger clock on the cover" to TodoStatus.IDEA,
                "Send the invoice to the landlord" to TodoStatus.OPEN,
                "Book the car service" to TodoStatus.SUGGESTED
            ),
            todos.map { it.text to it.status }
        )
        // An idea has no time, no reminder and is no appointment.
        assertTrue(todos.take(4).all { it.dueAt == null && it.remindAt == null && it.timePhrase == null && it.kind == TodoKind.TASK })
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), todos[4].dueAt)
    }

    @Test
    fun `an idea takes no time, whatever time the answer names for it`() = runBlocking {
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(
            answer = """{"items": [{"segment": "S1", "type": "idea", "text": "Dark mode for the cover screen", "when": "T1"}]}"""
        )

        val idea = useCase(llm, parser)(
            "s1", listOf(seg("a", "It would be good to try a dark mode for the cover screen tomorrow.", at(9, 0))), zone, "en"
        ).single()

        assertEquals(TodoStatus.IDEA, idea.status)
        assertNull(idea.dueAt)
        assertNull(idea.remindAt)
        assertNull(idea.timePhrase)
        assertEquals(listOf("a"), idea.sourceSegmentIds)
    }

    @Test
    fun `the three suggestions a recording keeps are ranked, a due time first, then the asked, then in spoken order`() = runBlocking {
        val segments = listOf(
            seg("a", "The gutter looks blocked.", at(9, 0)),
            seg("b", "Anna asked about the photos.", at(9, 1)),
            seg("c", "The library books are due on Friday.", at(9, 2)),
            seg("d", "The printer is out of ink.", at(9, 3)),
            seg("e", "Tom asked for the report by Monday.", at(9, 4))
        )
        val parser = FakeTimeParser().on("on Friday", LocalDate.of(2026, 10, 16)).on("Monday", LocalDate.of(2026, 10, 19))
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Clear the gutter", capture = "inferred"),
                item("S2", "Send Anna the photos", capture = "asked"),
                item("S3", "Return the library books", whenId = "T1", capture = "inferred"),
                item("S4", "Buy printer ink", capture = "inferred"),
                item("S5", "Send Tom the report", whenId = "T2", capture = "asked")
            )
        )

        val found = extraction(llm, parser)("s1", segments, zone, "en")

        // Ranked: Tom's report and the library books (due), then Anna's photos (asked); kept in spoken order.
        assertEquals(listOf("Send Anna the photos", "Return the library books", "Send Tom the report"), found.todos.map { it.text })
        assertTrue(found.todos.all { it.status == TodoStatus.SUGGESTED })
        assertEquals(2, found.overCap)
    }

    @Test
    fun `with five inferred suggestions, the ones due come first and then the earliest said`() = runBlocking {
        val segments = listOf(
            seg("a", "The gutter looks blocked.", at(9, 0)),
            seg("b", "The printer is out of ink.", at(9, 1)),
            seg("c", "The library books are due on Friday.", at(9, 2)),
            seg("d", "The garden looks dry.", at(9, 3)),
            seg("e", "The parking permit runs out on Monday.", at(9, 4))
        )
        val parser = FakeTimeParser().on("on Friday", LocalDate.of(2026, 10, 16)).on("on Monday", LocalDate.of(2026, 10, 19))
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Clear the gutter", capture = "inferred"),
                item("S2", "Buy printer ink", capture = "inferred"),
                item("S3", "Return the library books", whenId = "T1", capture = "inferred"),
                item("S4", "Water the garden", capture = "inferred"),
                item("S5", "Renew the parking permit", whenId = "T2", capture = "inferred")
            )
        )

        val found = extraction(llm, parser)("s1", segments, zone, "en")

        assertEquals(listOf("Clear the gutter", "Return the library books", "Renew the parking permit"), found.todos.map { it.text })
        assertEquals(2, found.overCap)
    }

    @Test
    fun `a time is tentative by a hedge in its sentence or by the parser, never by the model's word`() = runBlocking {
        val parser = FakeTimeParser().on("on Friday", LocalDate.of(2026, 10, 16))
        fun certaintyOf(answer: String, vararg segments: TranscriptionSegment) = runBlocking {
            useCase(FakeLlmRepository(answer = answer), parser)("s1", segments.toList(), zone, "en").single().certainty
        }
        // No cue for the spotter here: the time and its hedge are the model's to-do's alone.
        val report = items(item("S1", "Send the report", whenId = "T1", capture = "inferred"))

        assertEquals(TodoCertainty.TENTATIVE, certaintyOf(report, seg("a", "The report goes out, maybe on Friday.", at(9, 0))))
        // The hedge said with the time, in a sentence of its own.
        assertEquals(
            TodoCertainty.TENTATIVE,
            certaintyOf(report, seg("a", "The report goes out.", at(9, 0)), seg("b", "Maybe on Friday.", at(9, 0, 20)))
        )
        // An answer of an earlier build calling a firm time tentative changes nothing.
        assertEquals(
            TodoCertainty.CONFIRMED,
            certaintyOf(
                answer(todo("S1", "Send the report", "T1", capture = "inferred", certainty = "tentative")),
                seg("a", "The report goes out on Friday.", at(9, 0))
            )
        )
    }

    @Test
    fun `an idea repeats a known idea and no to-do, and a known idea is no pending to-do`() = runBlocking {
        val segments = listOf(
            seg("a", "There should be an option to remove older audio files.", at(9, 0)),
            seg("b", "It would be good to have a dark mode.", at(9, 1)),
            seg("c", "I need to sort the notes by place.", at(9, 2))
        )
        val existing = listOf(
            known("Option to remove older audio files", TodoStatus.IDEA),
            known("Dark mode", TodoStatus.OPEN),
            known("Sort the notes by place", TodoStatus.IDEA)
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Option to remove older audio files", type = "idea"),
                item("S2", "Dark mode", type = "idea"),
                item("S3", "Sort the notes by place")
            )
        )

        val todos = useCase(llm)("s1", segments, zone, "en", existingTodos = existing)

        assertEquals(listOf("Dark mode" to TodoStatus.IDEA, "Sort the notes by place" to TodoStatus.OPEN), todos.map { it.text to it.status })
        // The known ideas are shown as noted, before the pending to-dos, so the model doesn't give them again.
        assertEquals("\"Option to remove older audio files\"; \"Sort the notes by place\"; \"Dark mode\"", notedShown(llm))
    }

    @Test
    fun `an idea and a to-do are stored without the hesitations and stammers said in them`() = runBlocking {
        val segments = listOf(
            seg("a", "So the uh costs, pros and cons, should go just under uh uh each plan.", at(9, 0)),
            seg("b", "And I need to, um, send the the slides to Anna.", at(9, 1))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Costs pros and cons uh uh under each plan", type = "idea"),
                item("S2", "Send the the slides to um Anna", capture = "commitment")
            )
        )

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertEquals(listOf("Costs pros and cons under each plan", "Send the slides to Anna"), todos.map { it.text })
    }

    @Test
    fun `the known ideas are shown before the other pending to-dos, which may not all fit`() = runBlocking {
        val pending = (1..25).map { known("Pending task number $it") }
        val ideas = (1..3).map { known("Idea number $it", TodoStatus.IDEA) }
        val llm = FakeLlmRepository(answer = items())

        // It speaks of each of them, so each one can be said again in it.
        val part = seg("a", "Another pending task number came up, and an idea number too.", at(9, 0))
        useCase(llm)("s1", listOf(part), zone, "en", existingTodos = pending + ideas)

        val noted = notedShown(llm).split("; ")
        assertEquals(TodoPrompt.MAX_NOTED, noted.size)
        assertEquals((1..3).map { "\"Idea number $it\"" } + "\"Pending task number 1\"", noted.take(4))
    }

    /** What the model's one item [text] with [capture], said in [sentence], is stored as: status and capture. */
    private fun storedAs(sentence: String, text: String, capture: String, language: String = "en"): List<Pair<TodoStatus, TodoCapture>> =
        runBlocking {
            val llm = FakeLlmRepository(answer = items(item("S1", text, capture = capture)))
            useCase(llm)("s1", listOf(seg("a", sentence, at(9, 0))), zone, language).map { it.status to it.capture }
        }

    @Test
    fun `a strong cue asked, denied or said as talk opens none of the model's to-dos, whatever its capture`() {
        val suggested = listOf(TodoStatus.SUGGESTED to TodoCapture.INFERRED)
        // Each was an open commitment through the cue alone.
        assertEquals(suggested, storedAs("Ich muss morgen nicht die Bank anrufen.", "Die Bank anrufen", "inferred", "de"))
        assertEquals(suggested, storedAs("Ich muss morgen nicht die Bank anrufen.", "Die Bank anrufen", "commitment", "de"))
        assertEquals(suggested, storedAs("I have to say the hotel by the lake was lovely.", "Book the hotel by the lake again", "inferred"))
        assertEquals(suggested, storedAs("Ich muss sagen, das Hotel am See war wunderbar.", "Hotel am See wieder buchen", "inferred", "de"))
        // A question, and a diary line after "I have to say", given back word for word: sentences, no to-dos.
        assertEquals(emptyList<Pair<TodoStatus, TodoCapture>>(), storedAs("Do I need to call the bank about the loan?", "Call the bank about the loan", "commitment"))
        assertEquals(
            emptyList<Pair<TodoStatus, TodoCapture>>(),
            storedAs("I have to say the walk along the river was lovely.", "The walk along the river was lovely", "inferred")
        )
    }

    @Test
    fun `a strong cue said with the model's to-do opens it with the cue's capture, the model's own words open nothing`() {
        // The spotter finds no thing to do here: no infinitive ends the plan.
        assertEquals(
            listOf(TodoStatus.OPEN to TodoCapture.COMMITMENT),
            storedAs("Ich muss morgen früh zur Bank.", "Zur Bank gehen", "inferred", "de")
        )
        assertEquals(
            listOf(TodoStatus.SUGGESTED to TodoCapture.INFERRED),
            storedAs("The keys are on the hall table.", "Don't forget the keys", "inferred")
        )
    }

    @Test
    fun `a request to be reminded said inside a wish for the app is an idea`() {
        assertEquals(
            listOf(TodoStatus.IDEA to TodoCapture.INFERRED),
            storedAs("It would be nice if the app could remind me to drink water every hour.", "Drink water every hour", "explicit")
        )
    }

    @Test
    fun `speaking of a to-do that was missed gives no open to-do, whatever capture the model gives it`() {
        val meta = "In the note before, I saw that one of the to do to iron the shirts in the evening was not picked up."
        for (capture in listOf("explicit", "commitment", "inferred")) {
            assertEquals(capture, listOf(TodoStatus.SUGGESTED to TodoCapture.INFERRED), storedAs(meta, "Iron the shirts", capture))
        }
    }

    @Test
    fun `a to-do with a clock time keeps its time when its sentence says how something should be`() = runBlocking {
        val parser = FakeTimeParser()
            .at("tomorrow at 8", LocalDateTime.of(2026, 10, 15, 8, 0))
            .at("at 5 today", LocalDateTime.of(2026, 10, 14, 17, 0))
        val plumber = useCase(
            FakeLlmRepository(answer = items(item("S1", "Plumber visit", type = "appointment", whenId = "T1", capture = "explicit"))),
            parser
        )("s1", listOf(seg("a", "The plumber comes tomorrow at 8, it should be fixed then.", at(9, 0))), zone, "en").single()
        val parcel = useCase(FakeLlmRepository(answer = items(item("S1", "Pick up the parcel", whenId = "T1"))), parser)(
            "s1", listOf(seg("a", "I'm picking up the parcel at 5 today, that should be enough time.", at(9, 0))), zone, "en"
        ).single()

        assertEquals(TodoStatus.OPEN to TodoKind.APPOINTMENT, plumber.status to plumber.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 8, 0)), plumber.dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 7, 0)), plumber.remindAt)
        // No cue says the parcel is the speaker's to do: a suggestion, with its time.
        assertEquals(TodoStatus.SUGGESTED, parcel.status)
        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 17, 0)), parcel.dueAt)
    }

    @Test
    fun `diary lines the model gives back as tasks give nothing, wish and all`() = runBlocking {
        val lines = listOf(
            "Yesterday we walked along the river and it would be nice to go hiking again.",
            "The weather was grey all day, maybe we could have stayed home.",
            "In the evening we watched a film, it should be on again next month.",
            "What if the summer comes back for a week."
        )
        val segments = lines.mapIndexed { i, text -> seg("d$i", text, at(21, i)) }
        val llm = FakeLlmRepository(
            answer = items(*lines.mapIndexed { i, text -> item("S${i + 1}", text.trimEnd('.'), capture = "inferred") }.toTypedArray())
        )

        val found = extraction(llm)("s1", segments, zone, "en")

        assertEquals(emptyList<Todo>(), found.todos)
        assertEquals(0, found.overCap)
    }

    @Test
    fun `a recording read again keeps three suggestions in all, and one dismissed makes room`() = runBlocking {
        val segments = listOf(
            seg("a", "The gutter looks blocked.", at(9, 0)),
            seg("b", "The printer is out of ink.", at(9, 1)),
            seg("c", "The garden looks dry.", at(9, 2)),
            seg("d", "The bike tyres look flat.", at(9, 3)),
            seg("e", "The car is dusty.", at(9, 4))
        )
        val answer = items(
            item("S1", "Clear the gutter", capture = "inferred"),
            item("S2", "Buy printer ink", capture = "inferred"),
            item("S3", "Water the garden", capture = "inferred"),
            item("S4", "Pump up the bike tyres", capture = "inferred"),
            item("S5", "Wash the car", capture = "inferred")
        )
        val first = extraction(FakeLlmRepository(answer = answer))("s1", segments, zone, "en")
        assertEquals(listOf("Clear the gutter", "Buy printer ink", "Water the garden"), first.todos.map { it.text })
        assertEquals(2, first.overCap)

        val again = extraction(FakeLlmRepository(answer = answer))("s1", segments, zone, "en", existingTodos = first.todos)
        assertEquals(emptyList<Todo>(), again.todos)
        assertEquals(2, again.overCap)

        val dismissed = first.todos.mapIndexed { i, todo -> if (i == 0) todo.copy(status = TodoStatus.DISMISSED) else todo }
        val room = extraction(FakeLlmRepository(answer = answer))("s1", segments, zone, "en", existingTodos = dismissed)
        assertEquals(listOf("Pump up the bike tyres"), room.todos.map { it.text })
        assertEquals(1, room.overCap)
    }

    @Test
    fun `the gate decides before the repeat check, so a to-do it makes an idea repeats a known idea`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Dark mode for the cover")))

        val todos = useCase(llm)(
            "s1", listOf(seg("a", "There should be a dark mode for the cover.", at(9, 0))), zone, "en",
            existingTodos = listOf(known("Dark mode for the cover", TodoStatus.IDEA))
        )

        assertEquals(emptyList<Todo>(), todos)
    }

    @Test
    fun `an idea and an open to-do of the same words are never both, the open to-do stays`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Dark mode for the cover", type = "idea")))

        val todos = useCase(llm)(
            "s1",
            listOf(seg("a", "There should be a dark mode for the cover.", at(9, 0)), seg("b", "Note to self: dark mode for the cover.", at(9, 1))),
            zone, "en"
        )

        // Until 0.7.1 both stayed; the same text is never both a to-do and an idea now.
        assertEquals(listOf("Dark mode for the cover" to TodoStatus.OPEN), todos.map { it.text to it.status })
    }

    @Test
    fun `an idea said as one keeps no time when the model gives one to the same words`() = runBlocking {
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(answer = items(item("S1", "A dark mode for the cover screen", whenId = "T1")))

        val idea = useCase(llm, parser)("s1", listOf(seg("a", "Idea: a dark mode for the cover screen tomorrow.", at(9, 0))), zone, "en").single()

        assertEquals(TodoStatus.IDEA, idea.status)
        assertNull(idea.dueAt)
        assertNull(idea.remindAt)
        assertNull(idea.timePhrase)
    }

    @Test
    fun `the model's idea keeps the time words it names and cites only its own segment`() = runBlocking {
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(
            answer = """{"items": [{"segment": "S1", "type": "idea", "text": "Dark mode for the cover from tomorrow", "when": "T1"}]}"""
        )

        val idea = useCase(llm, parser)(
            "s1",
            listOf(seg("a", "It would be good to have a dark mode for the cover.", at(9, 0)), seg("b", "Maybe from tomorrow.", at(9, 0, 20))),
            zone, "en"
        ).single()

        assertEquals("Dark mode for the cover from tomorrow", idea.text)
        assertEquals(listOf("a"), idea.sourceSegmentIds)
        assertNull(idea.dueAt)
    }

    @Test
    fun `an answer of an earlier build gives what the same answer of today gives`() = runBlocking {
        // Nothing here for the spotter: what is found is the model's alone.
        val parser = FakeTimeParser().at("on Friday at 8", LocalDateTime.of(2026, 10, 16, 20, 0))
        val segments = listOf(
            seg("a", "The concert starts on Friday at 8.", at(9, 0)),
            seg("b", "The car is due for its service soon.", at(9, 1))
        )
        val earlier = answer(
            todo("S1", "Concert", "T1", kind = "appointment", capture = "explicit"),
            todo("S2", "Book the car service", capture = "inferred", certainty = "tentative")
        )
        val today = items(
            item("S1", "Concert", type = "appointment", whenId = "T1", capture = "explicit"),
            item("S2", "Book the car service", capture = "inferred")
        )

        val fromEarlier = useCase(FakeLlmRepository(answer = earlier), parser)("s1", segments, zone, "en").map { it.copy(id = "") }
        val fromToday = useCase(FakeLlmRepository(answer = today), parser)("s1", segments, zone, "en").map { it.copy(id = "") }

        assertEquals(listOf("Concert" to TodoStatus.OPEN, "Book the car service" to TodoStatus.SUGGESTED), fromToday.map { it.text to it.status })
        assertEquals(TodoKind.APPOINTMENT, fromToday.first().kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 20, 0)), fromToday.first().dueAt)
        assertEquals(fromToday, fromEarlier)
    }

    @Test
    fun `each part's answer goes to the answer log as the model gave it, a failed part's not`() = runBlocking {
        val talk = "We talked about the budget and the plans for the coming year in great detail. ".repeat(5).trim()
        val segments = (0 until 40).map { i -> seg("s$i", talk, at(9, 0) + i * 20_000L) }
        val answered = items(item("S1", "Book the train", capture = "explicit"))
        val llm = ScriptedLlmRepository(listOf(null, answered))
        val answers = RecordedAnswers()

        extraction(llm, answers = answers)("s1", segments, zone, "en")

        assertTrue("parts: ${llm.prompts.size}", llm.prompts.size > 2)
        assertEquals((1 until llm.prompts.size).map { Triple("s1", it, answered) }, answers.answers)
    }

    @Test
    fun `the model's fragments and copies of diary lines are dropped, what was said outright stays`() = runBlocking {
        val segments = listOf(
            seg("a", "It had been", at(9, 0)),
            seg("b", "An amazing journey.", at(9, 1)),
            seg("c", "Remind me to call the bank tomorrow.", at(9, 2))
        )
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(
            answer = answer(todo("S1", "It had been"), todo("S2", "An amazing journey"), todo("S3", "Call the bank", "T1"))
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(listOf("Call the bank"), todos.map { it.text })
        assertEquals(TodoStatus.OPEN, todos.single().status)
    }

    @Test
    fun `a repeat of a known todo is dropped and two mentions in one answer become one`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank tomorrow.", at(9, 0)),
            seg("b", "And send the slides to Anna, she needs them tomorrow.", at(9, 1))
        )
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val bank = known("Call the bank", dueAt = ms(LocalDateTime.of(2026, 10, 15, 9, 0)))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Call the bank tomorrow", "T1"),
                todo("S2", "Send Anna the slides"),
                todo("S2", "Send the slides to Anna", "T2")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en", existingTodos = listOf(bank))

        assertEquals(listOf("Send Anna the slides"), todos.map { it.text })
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), todos.single().dueAt)
        assertEquals("\"Call the bank\"", notedShown(llm))
    }

    @Test
    fun `only a pending todo with near-identical words is a repeat, or one read before from the same words`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call mom.", at(9, 0)),
            seg("b", "And water the plants.", at(9, 1)),
            seg("c", "Anna asked me to book the venue, so I'll book the venue.", at(9, 2)),
            seg("d", "Maybe fix the bike.", at(9, 3)),
            seg("e", "I need to buy milk.", at(9, 4)),
            seg("f", "And pay the rent.", at(9, 5))
        )
        val existing = listOf(
            // Done or dismissed in another recording: it can come up again.
            known("Call mom", TodoStatus.DONE),
            known("Water the plants", TodoStatus.DISMISSED),
            // Only suggested so far: the speaker now commits to it.
            known("Book the venue", TodoStatus.SUGGESTED),
            // Pending, and the new one adds nothing.
            known("Fix the bike", TodoStatus.SUGGESTED),
            known("Remember to buy milk", TodoStatus.OPEN),
            // Taken from these very words before, as when a recording is read again.
            known("Pay the rent", TodoStatus.DONE, segments = listOf("f"))
        )
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Call mom"),
                todo("S2", "Water the plants"),
                todo("S3", "Book the venue"),
                todo("S4", "Fix the bike", capture = "inferred"),
                todo("S5", "Buy milk"),
                todo("S6", "Pay the rent")
            )
        )

        val todos = useCase(llm)("s1", segments, zone, "en", existingTodos = existing)

        assertEquals(listOf("Call mom", "Water the plants", "Book the venue"), todos.map { it.text })
        assertEquals(TodoStatus.OPEN, todos[2].status)
        assertEquals("\"Book the venue\"; \"Fix the bike\"; \"Remember to buy milk\"", notedShown(llm))
    }

    @Test
    fun `a todo that adds a due time or another day to a known one is kept`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank tomorrow.", at(9, 0)),
            seg("b", "Send the invoice tomorrow.", at(9, 1)),
            seg("c", "Book the table tomorrow at 7.", at(9, 2)),
            seg("d", "Call the plumber on Friday.", at(9, 3))
        )
        val parser = FakeTimeParser()
            .at("tomorrow at 7", LocalDateTime.of(2026, 10, 15, 19, 0))
            .on("tomorrow", LocalDate.of(2026, 10, 15))
            .on("on Friday", LocalDate.of(2026, 10, 16))
        val thursdayAtNine = ms(LocalDateTime.of(2026, 10, 15, 9, 0))
        val existing = listOf(
            known("Call the bank"),
            known("Send the invoice", dueAt = ms(LocalDateTime.of(2026, 10, 15, 16, 0)), dueHasTime = true),
            known("Book the table", dueAt = thursdayAtNine),
            known("Call the plumber", dueAt = thursdayAtNine)
        )
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Call the bank", "T1"),
                todo("S2", "Send the invoice", "T2"),
                todo("S3", "Book the table", "T3"),
                todo("S4", "Call the plumber", "T4")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en", existingTodos = existing)

        assertEquals(listOf("Call the bank", "Book the table", "Call the plumber"), todos.map { it.text })
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 19, 0)), todos[1].dueAt)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), todos[2].dueAt)
    }

    @Test
    fun `todos that add words or fall on another day are not repeats of each other`() = runBlocking {
        val segments = listOf(
            seg("a", "Pay the rent.", at(9, 0)),
            seg("b", "And pay the electricity bill and the rent.", at(9, 1)),
            seg("c", "Morgen Anna anrufen.", at(9, 2)),
            seg("d", "Am Freitag Anna noch mal anrufen.", at(9, 3)),
            seg("e", "Update the website.", at(9, 4)),
            seg("f", "Then update the website and send the newsletter to all customers.", at(9, 5)),
            seg("g", "Prepare slides for the Monday meeting.", at(9, 6)),
            seg("h", "And prepare slides for the Friday meeting.", at(9, 7))
        )
        val parser = FakeTimeParser()
            .on("Morgen", LocalDate.of(2026, 10, 15))
            .on("Am Freitag", LocalDate.of(2026, 10, 16))
        val texts = listOf(
            "Pay the rent", "Pay the electricity bill and the rent", "Anna anrufen", "Anna noch mal anrufen",
            "Update the website", "Update the website and send the newsletter to all customers",
            "Prepare slides for the Monday meeting", "Prepare slides for the Friday meeting"
        )
        val llm = FakeLlmRepository(
            answer = answer(
                *texts.mapIndexed { index, text ->
                    todo("S${index + 1}", text, if (index == 2) "T1" else if (index == 3) "T2" else "none")
                }.toTypedArray()
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "de")

        assertEquals(texts, todos.map { it.text })
        assertEquals(
            listOf(null, null, ms(LocalDateTime.of(2026, 10, 15, 9, 0)), ms(LocalDateTime.of(2026, 10, 16, 9, 0))) +
                List(4) { null },
            todos.map { it.dueAt }
        )
    }

    @Test
    fun `two mentions of one todo become one that keeps the clock time, the commitment and both sources`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank tomorrow.", at(9, 0)),
            seg("b", "Right, call them at 3 tomorrow.", at(9, 1)),
            seg("c", "Anna asked me to book the venue.", at(9, 2)),
            seg("d", "Okay, I'll book the venue on Friday.", at(9, 3))
        )
        val parser = FakeTimeParser()
            .at("at 3 tomorrow", LocalDateTime.of(2026, 10, 15, 15, 0))
            .on("tomorrow", LocalDate.of(2026, 10, 15))
            .on("on Friday", LocalDate.of(2026, 10, 16))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Call the bank", "T1"),
                todo("S2", "Call the bank", "T2"),
                todo("S3", "Book the venue", capture = "asked"),
                todo("S4", "Book the venue", "T3")
            )
        )

        val (bank, venue) = useCase(llm, parser)("s1", segments, zone, "en")

        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 15, 0)), bank.dueAt)
        assertEquals(true, bank.dueHasTime)
        assertEquals("at 3 tomorrow", bank.timePhrase)
        assertEquals(bank.dueAt, bank.remindAt)
        assertEquals(listOf("a", "b"), bank.sourceSegmentIds)

        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 9, 0)), venue.dueAt)
        assertEquals(TodoStatus.OPEN, venue.status)
        assertEquals(TodoCapture.COMMITMENT, venue.capture)
        assertEquals(listOf("c", "d"), venue.sourceSegmentIds)
    }

    @Test
    fun `reading a recording again brings back no done or dismissed todo and no twin of an open one`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank about the loan.", at(9, 0)),
            seg("b", "Ich muss die Bank wegen des Kredits anrufen.", at(9, 1)),
            seg("c", "And book the venue for the party.", at(9, 2)),
            seg("d", "The dentist is tomorrow at 3.", at(9, 3))
        )
        val parser = FakeTimeParser().at("tomorrow at 3", LocalDateTime.of(2026, 10, 15, 15, 0))
        val tomorrowAtNine = ms(LocalDateTime.of(2026, 10, 15, 9, 0))
        // What the first reading found, since then done, dismissed or still open.
        val existing = listOf(
            known("Call the bank about the loan", TodoStatus.DONE, segments = listOf("a"), session = "s1"),
            known("Bank wegen des Kredits anrufen", TodoStatus.DISMISSED, segments = listOf("b"), session = "s1"),
            known("Book the venue for the party", TodoStatus.OPEN, segments = listOf("c"), session = "s1"),
            known("Dentist", TodoStatus.DONE, dueAt = tomorrowAtNine, segments = listOf("d"), session = "s1")
        )
        // The second reading words them differently and links the dentist's clock time.
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Call the bank"),
                todo("S2", "Die Bank anrufen"),
                todo("S3", "Book the venue"),
                todo("S4", "Dentist", "T1", kind = "appointment", capture = "explicit")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "en", existingTodos = existing)

        assertEquals(emptyList<Todo>(), todos)
        assertEquals(
            "\"Call the bank about the loan\"; \"Bank wegen des Kredits anrufen\"; \"Book the venue for the party\"; \"Dentist\"",
            notedShown(llm)
        )
    }

    @Test
    fun `the same words in another recording are a new todo`() = runBlocking {
        val existing = listOf(
            known("Call the bank about the loan", TodoStatus.DONE, segments = listOf("x")),
            known("Call the bank about the card", TodoStatus.OPEN, segments = listOf("y"))
        )
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank")))

        val todos = useCase(llm)("s1", listOf(seg("a", "I have to call the bank.", at(9, 0))), zone, "en", existingTodos = existing)

        assertEquals(listOf("Call the bank"), todos.map { it.text })
        assertEquals("\"Call the bank about the card\"", notedShown(llm))
    }

    @Test
    fun `a todo said again in more words in one recording becomes one with the fuller text and the due time`() = runBlocking {
        val segments = listOf(
            seg("a", "I have to call the bank.", at(9, 0)),
            seg("b", "Right, call the bank about the loan tomorrow.", at(9, 1))
        )
        val parser = FakeTimeParser().on("tomorrow", LocalDate.of(2026, 10, 15))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank"), todo("S2", "Call the bank about the loan", "T1")))

        val bank = useCase(llm, parser)("s1", segments, zone, "en").single()

        assertEquals("Call the bank about the loan", bank.text)
        assertEquals(ms(LocalDateTime.of(2026, 10, 15, 9, 0)), bank.dueAt)
        assertEquals(listOf("a", "b"), bank.sourceSegmentIds)
    }

    @Test
    fun `cancelling an appointment or another owner's thing is not that todo said again`() = runBlocking {
        val segments = listOf(
            seg("a", "Ich habe am Freitag um 15 Uhr einen Termin beim Zahnarzt.", at(9, 0)),
            seg("b", "Den Termin beim Zahnarzt muss ich absagen.", at(9, 1)),
            seg("c", "I have to call Anna.", at(9, 2)),
            seg("d", "And call Anna's mother.", at(9, 3))
        )
        val parser = FakeTimeParser().at("am Freitag um 15 Uhr", LocalDateTime.of(2026, 10, 16, 15, 0))
        val llm = FakeLlmRepository(
            answer = answer(
                todo("S1", "Termin beim Zahnarzt", "T1", kind = "appointment", capture = "explicit"),
                todo("S2", "Termin beim Zahnarzt absagen"),
                todo("S3", "Call Anna"),
                todo("S4", "Call Anna's mother")
            )
        )

        val todos = useCase(llm, parser)("s1", segments, zone, "de")

        assertEquals(
            listOf("Termin beim Zahnarzt", "Termin beim Zahnarzt absagen", "Call Anna", "Call Anna's mother"),
            todos.map { it.text }
        )
        val (appointment, cancel) = todos
        assertEquals(TodoKind.APPOINTMENT, appointment.kind)
        assertEquals(ms(LocalDateTime.of(2026, 10, 16, 15, 0)), appointment.dueAt)
        assertEquals(TodoKind.TASK, cancel.kind)
        assertNull(cancel.dueAt)
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d")), todos.map { it.sourceSegmentIds })
    }

    @Test
    fun `with the real parser, two mentions on one day keep the part of the day over the day alone`() = runBlocking {
        val segments = listOf(seg("a", "I have to call mom today.", at(10, 0)), seg("b", "Yes, call mom tonight.", at(10, 1)))
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call mom", "T1"), todo("S2", "Call mom", "T2")))

        val mom = useCase(llm, RuleBasedTimeParser())("s1", segments, zone, "en").single()

        assertEquals(ms(LocalDateTime.of(2026, 10, 14, 19, 0)), mom.dueAt)
        assertEquals(false, mom.dueHasTime)
        assertEquals("tonight", mom.timePhrase)
        assertEquals(mom.dueAt, mom.remindAt)
        assertEquals(listOf("a", "b"), mom.sourceSegmentIds)
    }

    @Test
    fun `with the real parser, a part of the day adds to a known todo with only the day`() = runBlocking {
        val segments = listOf(seg("a", "I need to call mom tonight.", at(10, 0)))
        val answer = answer(todo("S1", "Call mom", "T1"))
        val dayOnly = known("Call mom", dueAt = ms(LocalDateTime.of(2026, 10, 14, 9, 0)))
        val evening = known("Call mom", dueAt = ms(LocalDateTime.of(2026, 10, 14, 19, 0)))

        val added = useCase(FakeLlmRepository(answer = answer), RuleBasedTimeParser())(
            "s1", segments, zone, "en", existingTodos = listOf(dayOnly)
        )
        val repeated = useCase(FakeLlmRepository(answer = answer), RuleBasedTimeParser())(
            "s1", segments, zone, "en", existingTodos = listOf(evening)
        )

        assertEquals(listOf(ms(LocalDateTime.of(2026, 10, 14, 19, 0))), added.map { it.dueAt })
        assertEquals(emptyList<Todo>(), repeated)
    }

    @Test
    fun `a long recording is read in parts, and each part is told the todos found before`() = runBlocking {
        val talk = "We talked about the budget and the plans for the coming year in great detail. ".repeat(5).trim()
        val segments = (0 until 40).map { i ->
            seg("s$i", if (i == 0) "Remind me to call the bank. $talk" else talk, at(9, 0) + i * 20_000L)
        }
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank", capture = "explicit")))

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertTrue("prompts: ${llm.prompts.size}", llm.prompts.size > 1)
        val segmentLine = Regex("""S\d+ .*""")
        assertEquals(40, llm.prompts.sumOf { (text, _) -> text.lines().count { it.matches(segmentLine) } })
        assertTrue(llm.prompts.all { (text, _) -> segmentsOf(text).startsWith("S1 ") })
        assertTrue(llm.prompts.drop(1).all { (text, _) -> notedIn(text) == "\"Call the bank\"" })
        // The system prompt is the same for every part.
        assertEquals(setOf("todo prompt (en)"), llm.prompts.map { it.second }.toSet())
        assertEquals(listOf("Call the bank"), todos.map { it.text })
        // Each later part names it again, citing its own first segment, which doesn't say it: a
        // copy of what it was told, dropped.
        assertEquals(listOf("s0"), todos.single().sourceSegmentIds)
    }

    @Test
    fun `a failed part loses only its own todos`() = runBlocking {
        val talk = "We talked about the budget and the plans for the coming year, and we still have to book the train. ".repeat(4).trim()
        val segments = (0 until 40).map { i -> seg("s$i", talk, at(9, 0) + i * 20_000L) }
        val llm = ScriptedLlmRepository(listOf(null, answer(todo("S1", "Book the train", capture = "explicit"))))

        val todos = useCase(llm)("s1", segments, zone, "en")

        assertTrue(llm.prompts.size > 1)
        assertEquals(listOf("Book the train"), todos.map { it.text })
        assertNotEquals(listOf("s0"), todos.single().sourceSegmentIds)
    }

    @Test
    fun `a model that fails or can't be loaded gives no todos of its own`() = runBlocking {
        // Said without a cue: only the model could have found a todo in it.
        val segments = listOf(seg("a", "The bank wants the papers.", at(9, 0)))

        val failing = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        assertEquals(emptyList<Todo>(), useCase(failing)("s1", segments, zone, "en"))

        val notLoaded = FakeLlmRepository(reloadFailure = IllegalStateException("no model"))
        assertEquals(emptyList<Todo>(), useCase(notLoaded)("s1", segments, zone, "en"))
        assertTrue(notLoaded.prompts.isEmpty())
    }

    @Test
    fun `the todos said outright stay when the model fails or can't be loaded`() = runBlocking {
        val segments = listOf(seg("a", "I have to call the bank.", at(9, 0)))

        val failing = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        assertEquals(listOf("Call the bank"), useCase(failing)("s1", segments, zone, "en").map { it.text })

        val notLoaded = FakeLlmRepository(reloadFailure = IllegalStateException("no model"))
        assertEquals(listOf("Call the bank"), useCase(notLoaded)("s1", segments, zone, "en").map { it.text })
        assertTrue(notLoaded.prompts.isEmpty())
    }

    @Test
    fun `a reading says whether the model read every part`() = runBlocking {
        val segments = listOf(seg("a", "I have to call the bank.", at(9, 0)))

        assertTrue(extraction(FakeLlmRepository(answer = items()))("s1", segments, zone, "en").answered)
        assertFalse(extraction(FakeLlmRepository(failWith = IllegalStateException("native crash")))("s1", segments, zone, "en").answered)
        assertFalse(extraction(FakeLlmRepository(reloadFailure = IllegalStateException("no model")))("s1", segments, zone, "en").answered)
        // Nothing to read is nothing left unread.
        assertTrue(extraction(FakeLlmRepository(failWith = IllegalStateException("native crash")))("s1", emptyList(), zone, "en").answered)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is passed on, not taken for a failure`() {
        runBlocking {
            val llm = FakeLlmRepository(failWith = CancellationException("stopped"))
            useCase(llm)("s1", listOf(seg("a", "I have to call the bank.", at(9, 0))), zone, "en")
        }
    }

    @Test
    fun `a parser error leaves its segment without times`() = runBlocking {
        val parser = FakeTimeParser().apply { failWith = IllegalArgumentException("bad input") }
        val llm = FakeLlmRepository(answer = answer(todo("S1", "Call the bank", "T1")))

        val todo = useCase(llm, parser)("s1", listOf(seg("a", "I have to call the bank tomorrow.", at(9, 0))), zone, "en").single()

        assertEquals("Call the bank", todo.text)
        assertNull(todo.dueAt)
        assertEquals("S1 I have to call the bank tomorrow.", segmentsShown(llm))
    }

    @Test
    fun `the recording's zone falls back to the phone's when it is missing or unknown`() {
        assertEquals(zone, ExtractTodosUseCase.zoneOf(zone.id))
        assertEquals(ZoneId.systemDefault(), ExtractTodosUseCase.zoneOf(null))
        assertEquals(ZoneId.systemDefault(), ExtractTodosUseCase.zoneOf("Mars/Olympus Mons"))
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.enumOf(key: String): List<String> =
        obj(key)["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        const val NOW = 1_000L
    }
}
