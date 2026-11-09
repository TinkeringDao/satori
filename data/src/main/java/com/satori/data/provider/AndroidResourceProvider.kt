package com.satori.data.provider

import android.content.Context
import android.content.res.Configuration
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Android implementation of ResourceProvider using Context to access string resources.
 *
 * @property context Application context for accessing resources
 */
class AndroidResourceProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : ResourceProvider {

    // A fixed locale's strings never change while the app runs, and each lookup builds a
    // configuration context. Every note the AI writes looks up its prompt and four JSON keys.
    private val localeStrings = ConcurrentHashMap<String, String>()

    override fun getSimpleListeningPrompt(): String {
        return getStringResource("prompt_simple_listening")
    }

    override fun getShortMeetingPrompt(): String {
        return getStringResource("prompt_short_meeting")
    }

    override fun getLongMeetingPrompt(): String {
        return getStringResource("prompt_long_meeting")
    }

    override fun getTranslationPrompt(): String {
        return getStringResource("prompt_translation")
    }

    override fun getJsonFieldTitle(): String = getStringResource("json_field_title")
    override fun getJsonFieldPoints(): String = getStringResource("json_field_points")
    override fun getJsonFieldSummary(): String = getStringResource("json_field_summary")
    override fun getJsonFieldActionItems(): String = getStringResource("json_field_action_items")

    override fun getJsonFieldTitle(localeCode: String): String =
        getStringResourceForLocale("json_field_title", localeCode)

    override fun getJsonFieldPoints(localeCode: String): String =
        getStringResourceForLocale("json_field_points", localeCode)

    override fun getJsonFieldSummary(localeCode: String): String =
        getStringResourceForLocale("json_field_summary", localeCode)

    override fun getJsonFieldActionItems(localeCode: String): String =
        getStringResourceForLocale("json_field_action_items", localeCode)

    override fun getTopicPrefix(localeCode: String): String =
        getStringResourceForLocale("session_topic_prefix", localeCode)

    override fun getTextLeadIn(localeCode: String): String =
        getStringResourceForLocale("session_text_lead_in", localeCode)

    override fun getInstructionLead(localeCode: String): String =
        getStringResourceForLocale("prompt_instruction_lead", localeCode)

    override fun getReadLanguageSentence(localeCode: String): String =
        getStringResourceForLocale("prompt_read_language", localeCode)

    override fun getMalayalamLettersSentence(localeCode: String): String =
        getStringResourceForLocale("prompt_malayalam_letters", localeCode)

    override fun getTitleTopicSentence(localeCode: String): String =
        getStringResourceForLocale("prompt_title_topic", localeCode)

    override fun getNotesRetrySentence(localeCode: String): String =
        getStringResourceForLocale("prompt_notes_retry", localeCode)

    // A language the app has no name for is named by its code, as the interface's default does.
    override fun getLanguageName(code: String, localeCode: String): String =
        if (code in ResourceProvider.LANGUAGE_NAMES) getStringResourceForLocale("prompt_language_$code", localeCode) else code

    // The translation prompt is in the app language (getTranslationPrompt), so is the name.
    override fun getTranslationTargetName(code: String): String =
        if (code in ResourceProvider.LANGUAGE_NAMES) getStringResource("prompt_language_$code") else super.getTranslationTargetName(code)

    override fun getSessionTitlePrompt(localeCode: String): String =
        getStringResourceForLocale("prompt_session_title", localeCode)

    override fun getTodoExtractionPrompt(localeCode: String): String =
        getStringResourceForLocale("prompt_todo_extraction", localeCode)

    override fun getTodoHeaderTemplate(localeCode: String): String =
        getStringResourceForLocale("prompt_todo_header", localeCode)

    override fun getTodoSaidInstruction(localeCode: String): String =
        getStringResourceForLocale("prompt_todo_said", localeCode)

    override fun getMindTopicsPrompt(localeCode: String): String =
        getStringResourceForLocale("prompt_mind_topics", localeCode)

    override fun getMindSummaryPrompt(localeCode: String): String =
        getStringResourceForLocale("prompt_mind_summary", localeCode)

    override fun getPromptForMode(mode: RecordingMode, localeCode: String): String {
        val resourceName = when (mode) {
            RecordingMode.SIMPLE_LISTENING      -> "prompt_simple_listening"
            RecordingMode.SHORT_MEETING         -> "prompt_short_meeting"
            RecordingMode.LONG_MEETING          -> "prompt_long_meeting"
            RecordingMode.REAL_TIME_TRANSLATION -> "prompt_translation"
        }
        return getStringResourceForLocale(resourceName, localeCode)
    }

    private fun getStringResourceForLocale(resourceName: String, localeCode: String): String =
        localeStrings.getOrPut("$resourceName/$localeCode") { loadStringResourceForLocale(resourceName, localeCode) }

    private fun loadStringResourceForLocale(resourceName: String, localeCode: String): String {
        return try {
            val locale = Locale.forLanguageTag(localeCode)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)
            val resourceId = localizedContext.resources.getIdentifier(
                resourceName, "string", context.packageName
            )
            if (resourceId != 0) localizedContext.resources.getString(resourceId)
            else getStringResource(resourceName)
        } catch (e: Exception) {
            getStringResource(resourceName)
        }
    }

    private fun getStringResource(resourceName: String): String {
        return try {
            val resourceId = context.resources.getIdentifier(
                resourceName,
                "string",
                context.packageName
            )
            if (resourceId != 0) {
                context.resources.getString(resourceId)
            } else {
                // Fallback to English if resource not found
                getFallbackPrompt(resourceName)
            }
        } catch (e: Exception) {
            getFallbackPrompt(resourceName)
        }
    }

    private fun getFallbackPrompt(resourceName: String): String {
        return when (resourceName) {
            // Short forms of values/strings_grille_ai.xml; they keep its keys, which the grammar enforces.
            "prompt_simple_listening" -> """
                You turn a voice note into short notes: what was said, written down as your own notes, one idea per point, never "the speaker says".
                Write in English: at most 6 points of under 20 words, and a title of 2 to 6 words naming their subject.
                Answer with this JSON:
                {"title": "...", "points": ["...", "..."]}
                What was said:
            """.trimIndent()
            "prompt_short_meeting", "prompt_long_meeting" -> """
                You turn what was said in a meeting into short notes: decisions, facts, plans and open questions, one idea per point, never "the speaker says".
                Write in English: at most 8 points of under 20 words, the tasks someone takes on as action items, and a title of 2 to 6 words naming the subject.
                Answer with this JSON:
                {"title": "...", "points": ["...", "..."], "action_items": ["...", "..."]}
                What was said:
            """.trimIndent()
            "json_field_title" -> "title"
            "json_field_points" -> "points"
            "json_field_summary" -> "summary"
            "json_field_action_items" -> "action_items"
            "prompt_translation" -> """
                You are a professional translator.
                Translate the text into {target_language}.
                Maintain tone and context. Output translation only, no explanations.
            """.trimIndent()
            "session_topic_prefix" -> "The topic is \"%1\$s\"."
            "session_text_lead_in" -> ResourceProvider.TEXT_LEAD_IN
            "prompt_instruction_lead" -> ResourceProvider.INSTRUCTION_LEAD
            "prompt_read_language" -> ResourceProvider.READ_LANGUAGE
            "prompt_malayalam_letters" -> ResourceProvider.MALAYALAM_LETTERS
            "prompt_title_topic" -> ResourceProvider.TITLE_TOPIC
            "prompt_notes_retry" -> ResourceProvider.NOTES_RETRY
            "prompt_language_ml", "prompt_language_en", "prompt_language_de" ->
                ResourceProvider.LANGUAGE_NAMES.getValue(resourceName.removePrefix("prompt_language_"))
            "prompt_session_title" ->
                "You name voice recordings. Read the text and reply with a title of 2 to 5 words " +
                "that names its subject, like the subject line of an email, not starting with \"The\". " +
                "Write the title in English. Reply with the title alone, with nothing before it such as \"Title:\"."
            // Short form of values/strings_grille_todo_ai.xml; keeps its keys and their order, and
            // like it holds no placeholder: they are in the header.
            "prompt_todo_extraction" -> """
                You find the to-dos and the ideas in a voice recording.
                Lines "S3 words" are segments; a line "T2 = '...' -> ..." under one is a time said in it.
                A to-do is something the speaker has to do or is asked to do; an idea is a suggestion for how something could be, that nobody has said they will do.
                Each item must come from the words of the segment it gives; greetings and headings of a list are none.
                For each one give segment, type ("task", "appointment" or "idea") and text (in English, at most 10 words, without the time);
                for a to-do also when (the T of its due time, or "none"; never make up a time)
                and capture ("explicit", "commitment", "asked" or "inferred").
                Answer with JSON only, in this form:
                {"items": [{"segment": "S2", "type": "task", "text": "...", "when": "T1", "capture": "commitment"}, {"segment": "S4", "type": "idea", "text": "..."}]}
            """.trimIndent()
            "prompt_todo_header" -> ResourceProvider.TODO_HEADER
            "prompt_todo_said" -> ResourceProvider.TODO_SAID
            // Short forms of values/strings_grille_mindmap_ai.xml; they keep its JSON keys.
            "prompt_mind_topics" -> """
                You find the topics of a voice recording for a mind map. The text below is its notes, or what was said in it.
                Give up to 3 topics, the most important first, each with label (1 to 4 words from the text)
                and points (up to 3 short sentences in English, at most 20 words each). Use only what the text says.
                Answer with JSON only, for example:
                {"topics": [{"label": "Garden shed", "points": ["The roof of the shed leaks."]}]}
            """.trimIndent()
            "prompt_mind_summary" -> """
                You sum up one theme of the voice recordings of one person. The first line names the theme;
                each line after it is one recording, newest first: its date, then what was said about the theme.
                Write 2 to 4 plain sentences in English on what was said and decided, and what is still open.
                When recordings disagree, the newest one counts. Answer with JSON only, for example:
                {"summary": "The roof of the garden shed leaks. The repair is planned for spring."}
            """.trimIndent()
            else -> "AI assistant analyzing transcriptions."
        }
    }
}
