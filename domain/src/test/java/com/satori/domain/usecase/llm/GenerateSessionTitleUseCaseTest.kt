package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateSessionTitleUseCaseTest {

    private val shortTalk = listOf(
        segment("Remind me to call the plumber", at = 2),
        segment("about the leaking tap tomorrow.", at = 3),
        segment("ignored partial", at = 4, complete = false)
    )

    @Test
    fun `too few words give no input, with notes or without`() {
        assertNull(GenerateSessionTitleUseCase.titleInput(listOf(segment("Hello there", at = 1)), emptyList()))
        assertNull(GenerateSessionTitleUseCase.titleInput(listOf(segment("Hello there", at = 1)), listOf(insight("Greeting", "- Hello.", at = 2))))
    }

    @Test
    fun `a short transcript without notes is read as it is, in order, without partial lines`() {
        val input = GenerateSessionTitleUseCase.titleInput(shortTalk.reversed(), emptyList())
        assertEquals("Remind me to call the plumber about the leaking tap tomorrow.", input)
    }

    @Test
    fun `a recording with notes is named after them, however short its transcript`() {
        val notes = listOf(insight("Leaking tap", "- Call the plumber tomorrow.\n- Turn off the water.", at = 5))
        assertEquals(
            "- Leaking tap: Call the plumber tomorrow; Turn off the water",
            GenerateSessionTitleUseCase.titleInput(shortTalk, notes)
        )
        // A note with nothing in it is none: the transcript is read.
        assertEquals(
            "Remind me to call the plumber about the leaking tap tomorrow.",
            GenerateSessionTitleUseCase.titleInput(shortTalk, listOf(insight("Empty", " ", at = 5)))
        )
    }

    @Test
    fun `a long transcript is replaced by its insights`() {
        val long = (1..400).map { segment("word$it and more words here", at = it.toLong()) }
        val insights = listOf(
            insight("Second part", "Later they agreed on the date.", at = 20),
            insight("First part", "{\"summary\": \"They talked about the venue.\"}", at = 10)
        )
        val input = GenerateSessionTitleUseCase.titleInput(long, insights)!!
        assertEquals(
            "- First part: They talked about the venue.\n- Second part: Later they agreed on the date.",
            input
        )
    }

    @Test
    fun `notes written as points are read as one line each`() {
        val long = (1..400).map { segment("word$it and more words here", at = it.toLong()) }
        val insights = listOf(
            insight("Garage tidy-up", "- Sort the screws by size.\n- Give the old bike away.", at = 10),
            insight(null, "- Paint the door", at = 20),
            // A note someone wrote by hand stays as it is.
            insight("Ideas", "Paint the door green\n- maybe blue", at = 30)
        )
        val input = GenerateSessionTitleUseCase.titleInput(long, insights)!!
        assertEquals(
            "- Garage tidy-up: Sort the screws by size; Give the old bike away\n- Paint the door\n" +
                "- Ideas: Paint the door green - maybe blue",
            input
        )
    }

    @Test
    fun `a long transcript without insights is cut to its start`() {
        val long = (1..400).map { segment("word$it and more words here", at = it.toLong()) }
        val input = GenerateSessionTitleUseCase.titleInput(long, emptyList())!!
        assertEquals(GenerateSessionTitleUseCase.MAX_INPUT_CHARS, input.length)
        assertTrue(input.startsWith("word1 and more words here word2"))
    }

    @Test
    fun `asks the model in the title language and cleans its answer`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Title: \"Call the plumber.\"")
        val result = GenerateSessionTitleUseCase(llm, FakeResourceProvider())(shortTalk, emptyList(), "de")
        assertEquals("Call the plumber", result.getOrNull())
        assertEquals(1, llm.reloads)
        assertEquals("title prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `two recordings get the same system prompt, each with its own text in the user message`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Leaking tap")
        val partyTalk = listOf(segment("We still have to book the hall for the party next month.", at = 2))
        val notes = listOf(insight("Party", "- Book the hall.", at = 5))

        GenerateSessionTitleUseCase(llm, FakeResourceProvider())(shortTalk, emptyList(), "en")
        GenerateSessionTitleUseCase(llm, FakeResourceProvider())(partyTalk, notes, "en")

        assertEquals(listOf("title prompt (en)", "title prompt (en)"), llm.prompts.map { it.second })
        assertEquals(
            listOf("Remind me to call the plumber about the leaking tap tomorrow.", "- Party: Book the hall"),
            llm.prompts.map { it.first }
        )
    }

    @Test
    fun `a generic answer gives no title`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Audio Transcription Summary")
        val result = GenerateSessionTitleUseCase(llm, FakeResourceProvider())(shortTalk, emptyList(), "en")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `does not load the model when there is nothing to name`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Anything")
        val result = GenerateSessionTitleUseCase(llm, FakeResourceProvider())(emptyList(), emptyList(), "en")
        assertNull(result.getOrNull())
        assertEquals(0, llm.reloads)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `model errors are reported as a failure`() = runBlocking {
        val llm = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        val result = GenerateSessionTitleUseCase(llm, FakeResourceProvider())(shortTalk, emptyList(), "en")
        assertTrue(result.isFailure)

        val notLoaded = FakeLlmRepository(reloadFailure = IllegalStateException("no model"))
        assertTrue(GenerateSessionTitleUseCase(notLoaded, FakeResourceProvider())(shortTalk, emptyList(), "en").isFailure)
        assertTrue(notLoaded.prompts.isEmpty())
    }
}
