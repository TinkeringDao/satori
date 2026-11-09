package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNameSessionUseCaseTest {

    private val talk = listOf(segment("We need to book the venue for the wedding in May", at = 1))

    private fun useCase(repo: FakeTranscriptionRepository, llm: FakeLlmRepository) =
        AutoNameSessionUseCase(repo, GenerateSessionTitleUseCase(llm, FakeResourceProvider()))

    @Test
    fun `names an unnamed recording after the model's title`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(), talk)
        val result = useCase(repo, FakeLlmRepository(answer = "Wedding venue booking"))("s1", "en")
        assertEquals("Wedding venue booking", result.getOrNull())
        assertEquals("Wedding venue booking", repo.session?.name)
    }

    @Test
    fun `leaves a named recording alone without asking the model`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = "My own name"), talk)
        val llm = FakeLlmRepository(answer = "Wedding venue booking")
        val result = useCase(repo, llm)("s1", "en")
        assertNull(result.getOrNull())
        assertEquals("My own name", repo.session?.name)
        assertTrue(llm.prompts.isEmpty())
        assertEquals(0, repo.conditionalWrites)
    }

    @Test
    fun `falls back to the first specific insight title`() = runBlocking {
        val repo = FakeTranscriptionRepository(
            session(),
            talk,
            listOf(
                insight("Audio Transcription Summary", "…", at = 5),
                insight("Venue for the May wedding", "…", at = 9)
            )
        )
        val result = useCase(repo, FakeLlmRepository(answer = "Summary"))("s1", "en")
        assertEquals("Venue for the May wedding", result.getOrNull())
        assertEquals("Venue for the May wedding", repo.session?.name)
    }

    @Test
    fun `uses an insight title when the model fails`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(), talk, listOf(insight("Wedding planning", "…", at = 5)))
        val result = useCase(repo, FakeLlmRepository(failWith = IllegalStateException("boom")))("s1", "en")
        assertEquals("Wedding planning", result.getOrNull())
    }

    @Test
    fun `reports the failure when nothing can stand in`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(), talk)
        val result = useCase(repo, FakeLlmRepository(failWith = IllegalStateException("boom")))("s1", "en")
        assertTrue(result.isFailure)
        assertNull(repo.session?.name)
    }

    @Test
    fun `stays unnamed when there is nothing specific to go on`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(), talk, listOf(insight("Summary", "…", at = 5)))
        val result = useCase(repo, FakeLlmRepository(answer = "Audio notes"))("s1", "en")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertNull(repo.session?.name)
    }

    // The atomic "only if unnamed" write is the repository's job (an SQL WHERE clause); this
    // checks that the use case trusts its answer and reports no name.
    @Test
    fun `keeps a name the repository finds already set when writing`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(), talk).apply { nameTypedMeanwhile = "Typed by hand" }
        val result = useCase(repo, FakeLlmRepository(answer = "Wedding venue booking"))("s1", "en")
        assertNull(result.getOrNull())
        assertEquals("Typed by hand", repo.session?.name)
    }

    // ─── The name the old transcript gave a recording whose text is made again ──────

    @Test
    fun `the name to replace gives way to the new title once it is written`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk)
        val result = useCase(repo, FakeLlmRepository(answer = "Wedding venue booking"))("s1", "en", replacing = "Workflow Kettle Challenges")
        assertEquals("Wedding venue booking", result.getOrNull())
        assertEquals("Wedding venue booking", repo.session?.name)
    }

    @Test
    fun `the name to replace stays when no title can be found`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk)
        val result = useCase(repo, FakeLlmRepository(failWith = IllegalStateException("boom")))("s1", "en", replacing = "Workflow Kettle Challenges")
        assertTrue(result.isFailure)
        assertEquals("Workflow Kettle Challenges", repo.session?.name)
    }

    @Test
    fun `a name other than the one to replace stays without asking the model`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = "Errands for mum"), talk)
        val llm = FakeLlmRepository(answer = "Wedding venue booking")
        val result = useCase(repo, llm)("s1", "en", replacing = "Workflow Kettle Challenges")
        assertNull(result.getOrNull())
        assertEquals("Errands for mum", repo.session?.name)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a name typed while the title is found stays in place of the one to replace`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk).apply { nameTypedMeanwhile = "Errands for mum" }
        val result = useCase(repo, FakeLlmRepository(answer = "Wedding venue booking"))("s1", "en", replacing = "Workflow Kettle Challenges")
        assertNull(result.getOrNull())
        assertEquals("Errands for mum", repo.session?.name)
        assertEquals(1, repo.conditionalWrites)
    }

    @Test
    fun `a recording whose name was to be replaced but has none now is named`() = runBlocking {
        val repo = FakeTranscriptionRepository(session(name = null), talk)
        val result = useCase(repo, FakeLlmRepository(answer = "Wedding venue booking"))("s1", "en", replacing = "Workflow Kettle Challenges")
        assertEquals("Wedding venue booking", result.getOrNull())
        assertEquals("Wedding venue booking", repo.session?.name)
    }
}
