package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own prompt strings as a [ResourceProvider], in English and German, read from the app's
 * res/values and res/values-de (strings_grille_ai.xml, strings_grille_todo_ai.xml and
 * strings_grille_mindmap_ai.xml) with their escapes read as aapt2 reads them: so the prompt
 * builders and the filters of the answers run with the sentences the phone sends. Gradle runs the
 * tests in the module's folder, next to the app's. Any locale but "de" reads the English strings.
 */
class ShippedPrompts : ResourceProvider {

    private val byFolder: Map<String, Map<String, String>> = listOf("values", "values-de").associateWith(::load)

    private fun load(folder: String): Map<String, String> =
        listOf("strings_grille_ai.xml", "strings_grille_todo_ai.xml", "strings_grille_mindmap_ai.xml").flatMap { name ->
            val file = File("../app/src/main/res/$folder/$name")
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) as Element }.map { element ->
                element.getAttribute("name") to element.textContent.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
            }
        }.toMap()

    /** The string [name] in the language of [localeCode]. */
    fun string(name: String, localeCode: String): String =
        byFolder.getValue(if (localeCode.startsWith("de")) "values-de" else "values")[name] ?: error("No string $name")

    override fun getSimpleListeningPrompt() = string("prompt_simple_listening", "en")
    override fun getShortMeetingPrompt() = string("prompt_short_meeting", "en")
    override fun getLongMeetingPrompt() = string("prompt_long_meeting", "en")
    override fun getTranslationPrompt() = string("prompt_translation", "en")

    override fun getPromptForMode(mode: RecordingMode, localeCode: String) = when (mode) {
        RecordingMode.SIMPLE_LISTENING -> string("prompt_simple_listening", localeCode)
        RecordingMode.SHORT_MEETING -> string("prompt_short_meeting", localeCode)
        RecordingMode.LONG_MEETING -> string("prompt_long_meeting", localeCode)
        RecordingMode.REAL_TIME_TRANSLATION -> string("prompt_translation", localeCode)
    }

    override fun getJsonFieldTitle() = string("json_field_title", "en")
    override fun getJsonFieldPoints() = string("json_field_points", "en")
    override fun getJsonFieldSummary() = string("json_field_summary", "en")
    override fun getJsonFieldActionItems() = string("json_field_action_items", "en")
    override fun getJsonFieldTitle(localeCode: String) = string("json_field_title", localeCode)
    override fun getJsonFieldPoints(localeCode: String) = string("json_field_points", localeCode)
    override fun getJsonFieldSummary(localeCode: String) = string("json_field_summary", localeCode)
    override fun getJsonFieldActionItems(localeCode: String) = string("json_field_action_items", localeCode)
    override fun getTopicPrefix(localeCode: String) = string("session_topic_prefix", localeCode)
    override fun getTextLeadIn(localeCode: String) = string("session_text_lead_in", localeCode)
    override fun getInstructionLead(localeCode: String) = string("prompt_instruction_lead", localeCode)
    override fun getReadLanguageSentence(localeCode: String) = string("prompt_read_language", localeCode)
    override fun getMalayalamLettersSentence(localeCode: String) = string("prompt_malayalam_letters", localeCode)
    override fun getTitleTopicSentence(localeCode: String) = string("prompt_title_topic", localeCode)
    override fun getNotesRetrySentence(localeCode: String) = string("prompt_notes_retry", localeCode)
    override fun getLanguageName(code: String, localeCode: String) =
        if (code in ResourceProvider.LANGUAGE_NAMES) string("prompt_language_$code", localeCode) else code
    override fun getSessionTitlePrompt(localeCode: String) = string("prompt_session_title", localeCode)
    override fun getTodoExtractionPrompt(localeCode: String) = string("prompt_todo_extraction", localeCode)
    override fun getTodoHeaderTemplate(localeCode: String) = string("prompt_todo_header", localeCode)
    override fun getTodoSaidInstruction(localeCode: String) = string("prompt_todo_said", localeCode)
    override fun getMindTopicsPrompt(localeCode: String) = string("prompt_mind_topics", localeCode)
    override fun getMindSummaryPrompt(localeCode: String) = string("prompt_mind_summary", localeCode)
}
