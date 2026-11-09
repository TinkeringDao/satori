package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.model.RecordingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerateFinalInsightUseCaseTest {

    private val tail = "Before we stop, the bakery order for Saturday has to go out tonight, and the oven " +
        "needs a check by the technician next week because it heats unevenly."

    private fun finalNote(answer: String, sources: List<String> = emptyList()) = runBlocking {
        GenerateFinalInsightUseCase(FakeLlmRepository(answer = answer), FakeSettingsRepository(), FakeResourceProvider())(
            pendingText = tail,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            sourceSegmentIds = sources
        ).getOrThrow()
    }

    @Test
    fun `the last note cites the lines it was written from`() {
        val note = finalNote("{\"title\": \"Bakery order and oven\", \"points\": [\"Send the order.\"]}", listOf("seg1", "seg2"))!!

        assertEquals(listOf("seg1", "seg2"), note.sourceSegmentIds)
    }

    @Test
    fun `the last note is the answer's title and points`() {
        val note = finalNote("{\"title\": \"Bakery order and oven\", \"points\": [\"Send the Saturday order tonight.\"]}")!!
        assertEquals("Bakery order and oven", note.title)
        assertEquals("- Send the Saturday order tonight.", note.content)
    }

    @Test
    fun `an answer with nothing in it makes no last note, which is not a failure`() {
        assertNull(finalNote("{\"title\": \"...\", \"points\": [\"...\"]}"))
    }
}
