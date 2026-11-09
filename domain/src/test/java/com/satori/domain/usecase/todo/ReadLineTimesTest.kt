package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Design D9 (0.8.0): a due time read in a Malayalam line's English reading is kept only where the
 * line as it was said says when, for what the spotter reads outright as for the model's items, so a
 * day or an hour the reader made up sets no due time. The Malayalam and the readings are made up,
 * glossed above them.
 */
class ReadLineTimesTest {

    private val zone = ZoneOffset.UTC

    // Read as: "Remind me to call the bank tomorrow at ten."
    private val reading = TranscriptionSegment("seg1", "s1", "Remind me to call the bank tomorrow at ten.", timestamp = 1_000L, isComplete = true)

    // Read as: "The dentist appointment is on Friday."
    private val dentist = TranscriptionSegment("seg2", "s1", "The dentist appointment is on Friday at three.", timestamp = 2_000L, isComplete = true)

    private val times = FakeTimeParser()
        .at("tomorrow at ten", LocalDateTime.of(1970, 1, 2, 10, 0))
        .at("on Friday at three", LocalDateTime.of(1970, 1, 2, 15, 0))

    private val drops = mutableListOf<FoundTodosLog.Why>()

    private fun find(vararg asSaid: Pair<String, String>, lines: List<TranscriptionSegment> = listOf(reading)) = runBlocking {
        ExtractTodosUseCase(
            FakeLlmRepository(answer = """{"items": []}"""),
            TodoResources(),
            times,
            now = { 5L },
            newId = { "todo-1" },
            foundLog = { _, _, why -> drops += why }
        )("s1", lines, zone, "en", asSaid = asSaid.toMap()).todos
    }

    @Test
    fun `a reminder the spotter reads in a reading keeps its time where its line says when, and none where it doesn't`() {
        // Said: "Remind me to call the bank tomorrow at ten" (tomorrow, at ten o'clock).
        val saysWhen = find("seg1" to "നാളെ പത്ത് മണിക്ക് ബാങ്കിൽ വിളിക്കാൻ എന്നെ ഓർമ്മിപ്പിക്കുക.").single()
        // Said: "Remind me to call the bank", no day and no hour: the reading's are the reader's own.
        val saysNone = find("seg1" to "ബാങ്കിൽ വിളിക്കാൻ എന്നെ ഓർമ്മിപ്പിക്കുക.").single()
        // A line given as it was said, in English, is no read line: its time is the parser's as ever.
        val english = find().single()

        assertNotNull(saysWhen.dueAt)
        assertEquals(english.dueAt, saysWhen.dueAt)
        assertNull(saysNone.dueAt)
        assertNull(saysNone.remindAt)
        assertEquals(english.text, saysNone.text)
    }

    @Test
    fun `a clock time read in the next line, whose Malayalam says no hour, is no half of the day the model picked`() {
        // Read as: "Tomorrow I have to call the bank" / "at ten about the loan.", one sentence.
        val bank = TranscriptionSegment("seg3", "s1", "Tomorrow I have to call the bank", timestamp = 1_000L, isComplete = true)
        val loan = TranscriptionSegment("seg4", "s1", "at ten about the loan.", timestamp = 2_000L, isComplete = true)
        val parser = FakeTimeParser()
            .on("Tomorrow", LocalDate.of(1970, 1, 2))
            .at("at ten", LocalDateTime.of(1970, 1, 2, 10, 0))
        val picked = """{"items": [{"segment": "S1", "type": "task", "text": "Call the bank at ten about the loan", "when": "T1", "capture": "commitment"}]}"""
        fun found(vararg asSaid: Pair<String, String>) = runBlocking {
            // The model's item alone: the spotter, which reads the same line outright, keeps its own times (above).
            ExtractTodosUseCase(FakeLlmRepository(answer = picked), TodoResources(), parser, now = { 5L }, newId = { "todo-1" })(
                "s1", listOf(bank, loan), zone, "en", spotsOutright = false, asSaid = asSaid.toMap()
            ).todos.single()
        }

        // Said: "Tomorrow call the bank" / "about the loan": a day, and no hour in the second line.
        val noHour = found("seg3" to "നാളെ ബാങ്കിൽ വിളിക്കണം", "seg4" to "ലോണിനെ കുറിച്ച്.")
        // Said with the hour in the second line too: the day and the hour, as an English recording's.
        val withHour = found("seg3" to "നാളെ ബാങ്കിൽ വിളിക്കണം", "seg4" to "പത്ത് മണിക്ക് ലോണിനെ കുറിച്ച്.")

        assertEquals(false, noHour.dueHasTime)
        assertEquals(LocalDate.of(1970, 1, 2), noHour.dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
        assertEquals(true, withHour.dueHasTime)
        assertEquals(LocalDateTime.of(1970, 1, 2, 10, 0).toInstant(zone).toEpochMilli(), withHour.dueAt)
    }

    @Test
    fun `an appointment the spotter reads by its time in a reading whose line says no day has none to go by`() {
        // Said: "The dentist is there" (made up): no day, no hour.
        val found = find("seg2" to "ഡെന്റിസ്റ്റ് അവിടെ ഉണ്ട്.", lines = listOf(dentist))
        // The same in a line that says Friday: it stays an appointment, with its day.
        val friday = find("seg2" to "വെള്ളിയാഴ്ച മൂന്ന് മണിക്ക് ഡെന്റിസ്റ്റ് ഉണ്ട്.", lines = listOf(dentist))

        assertTrue(found.toString(), found.none { it.dueAt != null })
        assertTrue(drops.toString(), FoundTodosLog.Why.NO_USABLE_TIME in drops)
        assertEquals(LocalDate.of(1970, 1, 2), friday.single().dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
    }
}
