package com.satori.domain.language

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesLanguageResolverTest {

    private val german = "Wir müssen den Termin mit dem Vermieter nicht verschieben, weil die Heizung noch kaputt ist."
    private val english = "We agreed to move the launch to March, and Anna will update the plan before Friday."
    private val tooShort = "Okay, danke dir"
    // Go to the shop this evening and buy milk and bread.
    private val malayalam = "ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം"

    private fun resolver(
        repository: FakeTranscriptionRepository,
        setting: NotesLanguage = NotesLanguage.AUTO,
        device: String = "en"
    ) = NotesLanguageResolver(FakeSettingsRepository(AppSettings(notesLanguage = setting)), repository) { device }

    @Test
    fun `the language of a confident text is used and stored on the recording`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        assertEquals("de", resolver(repository).resolve("s1", german))
        assertEquals("de", repository.session?.outputLanguage)
    }

    @Test
    fun `the first confident result stays when the talk changes language`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        val resolver = resolver(repository)
        assertEquals("de", resolver.resolve("s1", german))
        // The next call still goes by its own text, but does not rewrite the stored language.
        assertEquals("en", resolver.resolve("s1", english))
        assertEquals("de", repository.session?.outputLanguage)
    }

    @Test
    fun `a text that says nothing uses the stored language`() = runBlocking {
        val repository = FakeTranscriptionRepository(session().copy(outputLanguage = "de"))
        assertEquals("de", resolver(repository, device = "en").resolve("s1", tooShort))
    }

    @Test
    fun `a text that says nothing on a new recording uses the phone's language and stores nothing`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        assertEquals("de", resolver(repository, device = "de").resolve("s1", tooShort))
        assertNull(repository.session?.outputLanguage)
    }

    @Test
    fun `a phone in another language gives English`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        assertEquals("en", resolver(repository, device = "fr").resolve("s1", tooShort))
    }

    @Test
    fun `a forced language ignores the text and stores nothing`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        assertEquals("en", resolver(repository, setting = NotesLanguage.EN).resolve("s1", german))
        assertEquals("de", resolver(repository, setting = NotesLanguage.DE).resolve("s1", english))
        assertNull(repository.session?.outputLanguage)
    }

    @Test
    fun `a translation keeps its target and is not given the spoken language`() = runBlocking {
        val repository = FakeTranscriptionRepository(
            session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "en")
        )
        // German is spoken: the answer is German, and the target stays what it was.
        assertEquals("de", resolver(repository).resolve("s1", german))
        assertEquals("en", repository.session?.outputLanguage)
        // Nothing is said that tells the language: the target is not mistaken for it.
        assertEquals("de", resolver(repository, device = "de").resolve("s1", tooShort))
    }

    @Test
    fun `a language the app does not write in is not overwritten`() = runBlocking {
        val repository = FakeTranscriptionRepository(session().copy(outputLanguage = "it"))
        assertEquals("de", resolver(repository).resolve("s1", german))
        assertEquals("it", repository.session?.outputLanguage)
    }

    @Test
    fun `a Malayalam text gives English on a German phone and stores nothing`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        val resolver = resolver(repository, device = "de")

        assertEquals("en", resolver.resolve("s1", malayalam))
        assertNull(repository.session?.outputLanguage)
        // Transcribed again in German later, its own language is stored as before.
        assertEquals("de", resolver.resolve("s1", german))
        assertEquals("de", repository.session?.outputLanguage)
    }

    @Test
    fun `a Malayalam text gives English with German chosen in the settings too, and stores nothing`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        val resolver = resolver(repository, setting = NotesLanguage.DE, device = "de")

        assertEquals("en", resolver.resolve("s1", malayalam))
        assertNull(repository.session?.outputLanguage)
        // English or German recordings still get the language chosen.
        assertEquals("de", resolver.resolve("s1", english))
    }

    @Test
    fun `a recording that is gone still gets an answer`() = runBlocking {
        val repository = FakeTranscriptionRepository(null)
        assertEquals("de", resolver(repository).resolve("s1", german))
    }
}
