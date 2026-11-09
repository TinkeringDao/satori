package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.provider.ResourceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The line of instructions that tells the model to read a text in another language than its
 * answer: a Malayalam transcript, written about in English, with the English words in Malayalam
 * letters; none for a text in the answer's language or one whose language isn't sure. Every
 * sentence is an order, and none says anything of the text, which the 1B gave back as notes.
 */
class TextLanguageNoteTest {

    // Tomorrow morning call the doctor, then send the meeting's report.
    private val malayalam = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ മീറ്റിംഗിന്റെ റിപ്പോർട്ട് അയക്കണം"
    private val english = "We agreed to move the launch to March, and the team will update the plan before Friday."
    private val german = "Wir haben vereinbart, den Start auf März zu verschieben, und das Team aktualisiert den Plan vor Freitag."

    private val malayalamLine = "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
        "Read English words written in Malayalam letters as English."

    /** The sentences the app ships in German. */
    private val germanSentences = object : ResourceProvider by FakeResourceProvider() {
        override fun getInstructionLead(localeCode: String) =
            if (localeCode == "de") shipped("values-de", "prompt_instruction_lead") else ResourceProvider.INSTRUCTION_LEAD

        override fun getReadLanguageSentence(localeCode: String) =
            if (localeCode == "de") shipped("values-de", "prompt_read_language") else ResourceProvider.READ_LANGUAGE

        override fun getMalayalamLettersSentence(localeCode: String) =
            if (localeCode == "de") shipped("values-de", "prompt_malayalam_letters") else ResourceProvider.MALAYALAM_LETTERS

        override fun getTitleTopicSentence(localeCode: String) =
            if (localeCode == "de") shipped("values-de", "prompt_title_topic") else ResourceProvider.TITLE_TOPIC

        override fun getLanguageName(code: String, localeCode: String) =
            if (localeCode == "de") mapOf("ml" to "Malayalam", "en" to "Englisch", "de" to "Deutsch").getValue(code)
            else ResourceProvider.LANGUAGE_NAMES.getValue(code)
    }

    @Test
    fun `a Malayalam text written about in English gets the orders to read it, its English words in Malayalam letters too`() {
        assertEquals(malayalamLine, TextLanguageNote.of(malayalam, "en", FakeResourceProvider()))
    }

    @Test
    fun `the line is in the answer's language`() {
        assertEquals(
            "Anweisung, nie in der Antwort zu wiederholen: was unten auf Malayalam steht, lesen und auf Deutsch schreiben. " +
                "Englische Wörter in Malayalam-Schrift als Englisch lesen.",
            TextLanguageNote.of(malayalam, "de", germanSentences)
        )
        assertEquals(
            "Anweisung, nie in der Antwort zu wiederholen: was unten auf Englisch steht, lesen und auf Deutsch schreiben.",
            TextLanguageNote.of(english, "de", germanSentences)
        )
    }

    @Test
    fun `German written about in English gets the order without the Malayalam letters`() {
        assertEquals(
            "Instruction, never to be repeated in your answer: read the German below and write in English.",
            TextLanguageNote.of(german, "en", FakeResourceProvider())
        )
        assertEquals("de", TextLanguageNote.spokenOtherThan(german, "EN"))
    }

    @Test
    fun `no line for a text in the answer's language, one of no sure language, or an answer in no language the app writes`() {
        val resources = FakeResourceProvider()
        assertNull(TextLanguageNote.of(english, "en", resources))
        assertNull(TextLanguageNote.of(german, "de", resources))
        // Too short for the detector to be sure.
        assertNull(TextLanguageNote.of("Call Anna.", "de", resources))
        assertNull(TextLanguageNote.of(malayalam, null, resources))
        assertNull(TextLanguageNote.of(malayalam, "it", resources))
    }

    @Test
    fun `a Malayalam recording's name is asked for as its topic in 2 to 5 words, from its transcript or its English notes`() {
        val resources = FakeResourceProvider()
        val topic = "Name the topic of the text below in 2 to 5 words, in English."

        assertEquals("$malayalamLine $topic", TextLanguageNote.forTitle(malayalam, "en", malayalam = true, resources))
        // Its notes are English: only the order on the topic, after the lead's colon.
        assertEquals(
            "Instruction, never to be repeated in your answer: name the topic of the text below in 2 to 5 words, in English.",
            TextLanguageNote.forTitle("- Doctor and report: Call the doctor tomorrow morning", "en", malayalam = true, resources)
        )
        // An English recording's name gets no line, a German text named in English the order to read it alone.
        assertNull(TextLanguageNote.forTitle(english, "en", malayalam = false, resources))
        assertEquals(TextLanguageNote.of(german, "en", resources), TextLanguageNote.forTitle(german, "en", malayalam = false, resources))
    }

    @Test
    fun `no sentence of the shipped line says what the text is, in either language`() {
        for (folder in listOf("values", "values-de")) {
            val sentences = listOf("prompt_instruction_lead", "prompt_read_language", "prompt_malayalam_letters", "prompt_title_topic")
                .map { shipped(folder, it) }
            // The note of 0.7.0 and before, which the 1B gave back as points: "The text is in Malayalam."
            assertFalse(folder, sentences.any { it.startsWith("The text is") || it.startsWith("Der Text ist") })
        }
        assertEquals(ResourceProvider.INSTRUCTION_LEAD, shipped("values", "prompt_instruction_lead"))
        assertEquals(ResourceProvider.READ_LANGUAGE, shipped("values", "prompt_read_language"))
        assertEquals(ResourceProvider.MALAYALAM_LETTERS, shipped("values", "prompt_malayalam_letters"))
        assertEquals(ResourceProvider.TITLE_TOPIC, shipped("values", "prompt_title_topic"))
    }

    @Test
    fun `the name call's system prompt asks for as many words as its topic line, in either language`() {
        // Review of 0.7.1: the system prompt said 2 to 6 while the topic line said 2 to 5.
        val count = Regex("""\d+ (?:to|bis) \d+ (?:words|Wörtern)""")
        for (folder in listOf("values", "values-de")) {
            val system = count.find(shipped(folder, "prompt_session_title"))?.value
            val topic = count.find(shipped(folder, "prompt_title_topic"))?.value
            assertEquals(folder, topic, system)
            assertEquals(folder, if (folder == "values") "2 to 5 words" else "2 bis 5 Wörtern", topic)
        }
    }

    /**
     * The string [name] of the app's res/[folder]/strings_grille_ai.xml, with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder, next to the app's.
     */
    private fun shipped(folder: String, name: String): String {
        val file = File("../app/src/main/res/$folder/strings_grille_ai.xml")
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val raw = (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
    }
}
