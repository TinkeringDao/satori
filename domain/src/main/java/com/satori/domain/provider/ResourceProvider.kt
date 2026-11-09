package com.satori.domain.provider

/**
 * Provider for accessing Android resources in a platform-agnostic way.
 *
 * This interface allows domain and presentation layers to access localized strings
 * without depending on Android framework directly, maintaining Clean Architecture.
 */
interface ResourceProvider {
    /**
     * Get localized system prompts for each recording mode.
     */
    fun getSimpleListeningPrompt(): String
    fun getShortMeetingPrompt(): String
    fun getLongMeetingPrompt(): String
    fun getTranslationPrompt(): String

    /**
     * Get the system prompt for a specific recording mode translated into the given locale.
     *
     * @param mode Recording mode determining which prompt key to load
     * @param localeCode BCP-47 locale code (e.g. "it", "de") for the desired language
     * @return Prompt string in the requested locale; falls back to device locale on error
     */
    fun getPromptForMode(mode: com.satori.domain.model.RecordingMode, localeCode: String): String

    /**
     * JSON output field names for the device locale.
     * Used when no explicit output language is selected (auto/device language).
     */
    fun getJsonFieldTitle(): String
    fun getJsonFieldPoints(): String
    fun getJsonFieldSummary(): String
    fun getJsonFieldActionItems(): String

    /**
     * JSON output field names for a specific locale.
     *
     * Each locale's prompt schema uses its own locale-specific field names (German: "titel",
     * "punkte", "aktionen"; English: "title", "points", "action_items"). The prompts no longer
     * ask for a summary; its name is kept to read an answer that gives one anyway. Parsing and
     * the grammar MUST use the same field names as the prompt that produced the output.
     *
     * @param localeCode BCP-47 locale code matching the active output language
     */
    fun getJsonFieldTitle(localeCode: String): String
    fun getJsonFieldPoints(localeCode: String): String
    fun getJsonFieldSummary(localeCode: String): String
    fun getJsonFieldActionItems(localeCode: String): String

    /**
     * Returns the localized sentence that names a recording's topic, put above what was said in
     * the user message of a notes call (never into the system prompt, which holds nothing of a
     * recording, so its encoded state can be kept), with [getTextLeadIn] between the two. The
     * returned string has one %1$s placeholder for the topic.
     *
     * @param localeCode BCP-47 locale code matching the active output language
     */
    fun getTopicPrefix(localeCode: String): String

    /**
     * The line that leads in to what was said, in the language of [localeCode]: the last line of
     * the notes prompts. The user message of a notes call gives it again under a recording's topic
     * ([getTopicPrefix]), so that the topic stands outside what was said. [TEXT_LEAD_IN] unless the
     * implementation has its own.
     *
     * @param localeCode BCP-47 locale code matching the active output language
     */
    fun getTextLeadIn(localeCode: String): String = TEXT_LEAD_IN

    /**
     * What the line of instructions in a call's user message starts with: "Instruction, never to be
     * repeated in your answer:". The sentences of [getReadLanguageSentence],
     * [getMalayalamLettersSentence] and [getTitleTopicSentence] follow it on that line, the first
     * of them in lower case. The line goes in the user message, never in the system prompt, which
     * stays the same for every recording of a kind and language. In the language of [localeCode],
     * the answer's. [INSTRUCTION_LEAD] unless the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the answer's language ("en" or "de")
     */
    fun getInstructionLead(localeCode: String): String = INSTRUCTION_LEAD

    /**
     * The order to read the text a call reads in its language and to answer in another one: "Read
     * the %1$s below and write in %2$s.", with %1$s for the name of the text's language and %2$s
     * for the answer's ([getLanguageName]). An order, never a sentence about the text, which a
     * small model turns into a point of its notes. [READ_LANGUAGE] unless the implementation has
     * its own.
     *
     * @param localeCode BCP-47 locale code of the answer's language ("en" or "de")
     */
    fun getReadLanguageSentence(localeCode: String): String = READ_LANGUAGE

    /**
     * The order that follows [getReadLanguageSentence] for a Malayalam text: the Malayalam speech
     * model writes Malayalam script only, so English words said in it come out in Malayalam
     * letters. [MALAYALAM_LETTERS] unless the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the answer's language ("en" or "de")
     */
    fun getMalayalamLettersSentence(localeCode: String): String = MALAYALAM_LETTERS

    /**
     * The order for a Malayalam recording's name: "Name the topic of the text below in 2 to 5
     * words, in %1$s.", with %1$s for the name of the answer's language ([getLanguageName]).
     * [TITLE_TOPIC] unless the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the answer's language ("en" or "de")
     */
    fun getTitleTopicSentence(localeCode: String): String = TITLE_TOPIC

    /**
     * The order a notes call is asked again with when its first answer left no point: "Write what
     * is said about the subject, never about this recording, its text or its language." It goes on
     * the line of instructions in the user message, after the orders on the text's language if
     * there are any, never in the system prompt, whose encoded state is kept. "This recording", not
     * "the recording": a recording about recordings has them for its subject. [NOTES_RETRY] unless
     * the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the notes' language ("en" or "de")
     */
    fun getNotesRetrySentence(localeCode: String): String = NOTES_RETRY

    /**
     * The name of the language [code] ("ml", "en", "de") in the language of [localeCode], as the
     * note on the text's language names it: "Malayalam", "German". [code] itself for a language
     * the app has no name for. English names unless the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the answer's language ("en" or "de")
     */
    fun getLanguageName(code: String, localeCode: String): String = LANGUAGE_NAMES[code] ?: code

    /**
     * The name of the language [code] ("en", "de") as the translation prompt
     * ([getTranslationPrompt]) says it, in that prompt's own language, the app's: "Englisch" in the
     * German prompt, which then reads "auf Englisch übersetzen", not "ins English". The English name
     * of [com.satori.domain.model.SupportedLanguages] unless the implementation has its own.
     */
    fun getTranslationTargetName(code: String): String =
        com.satori.domain.model.SupportedLanguages.getByCode(code)?.englishName ?: code

    /**
     * System prompt that asks for a short title naming what a recording is about, written in
     * the language of [localeCode].
     *
     * @param localeCode BCP-47 locale code of the title language ("en" or "de")
     */
    fun getSessionTitlePrompt(localeCode: String): String

    /**
     * System prompt that asks for the to-dos and the ideas in a transcript, written in the
     * language of [localeCode]. It holds no placeholder and is the same for every call, so the
     * model's state after reading it can be kept; what changes from call to call is in the header
     * above the segments ([getTodoHeaderTemplate]).
     *
     * @param localeCode BCP-47 locale code of the todo language ("en" or "de")
     */
    fun getTodoExtractionPrompt(localeCode: String): String

    /**
     * What the user message of the to-do prompt says above the segments, in the language of
     * [localeCode], with four placeholders the caller fills in: {date}, {weekday} and {time} (when
     * the recording started) and {noted} (to-dos and ideas already known). [TODO_HEADER] unless
     * the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the todo language ("en" or "de")
     */
    fun getTodoHeaderTemplate(localeCode: String): String = TODO_HEADER

    /**
     * The order the to-do header's line of instructions ends with when the segments are in
     * another language than the to-dos are written in (a Malayalam recording's to-dos are
     * English): each item is to give the words of its segment it was read from, as they are
     * written there, in the answer's "said" field, so the app can find them in the segment. In the
     * language of [localeCode]. [TODO_SAID] unless the implementation has its own.
     *
     * @param localeCode BCP-47 locale code of the todo language ("en" or "de")
     */
    fun getTodoSaidInstruction(localeCode: String): String = TODO_SAID

    /**
     * System prompt that asks for the topics of one recording for the mind map, at most 3, each
     * with a label of 1 to 4 words from the text and up to 3 short points written in the language
     * of [localeCode]. The answer is JSON held to a schema, {"topics": [{"label": "...",
     * "points": ["..."]}]}, with English keys in every language. The model reads the recording's
     * notes, or its transcript when it has none, as the text of the call; the prompt has no
     * placeholders.
     *
     * @param localeCode BCP-47 locale code of the notes language ("en" or "de")
     */
    fun getMindTopicsPrompt(localeCode: String): String

    /**
     * System prompt that asks what the recordings of one mind-map theme say about it: 2 to 4
     * plain sentences in the language of [localeCode], as JSON held to a schema,
     * {"summary": "..."}, with the English key in every language. The text of the call names the
     * theme on its first line, then has one line per recording, newest first: its date, then its
     * points. The prompt has no placeholders.
     *
     * @param localeCode BCP-47 locale code of the notes language ("en" or "de")
     */
    fun getMindSummaryPrompt(localeCode: String): String

    companion object {
        /** The English header of the to-do prompt, as in values/strings_grille_todo_ai.xml (prompt_todo_header). */
        const val TODO_HEADER = "The recording started on {date} ({weekday}) at {time}.\n" +
            "Already noted, do not repeat unless a new time is said for it: {noted}\n\n"

        /** The English order on the "said" field, as in values/strings_grille_todo_ai.xml (prompt_todo_said). */
        const val TODO_SAID = "For said, copy up to 8 words of the item's segment that say it."

        /** The English lead-in to what was said, as in values/strings_grille_ai.xml (session_text_lead_in). */
        const val TEXT_LEAD_IN = "What was said:"

        /** The English start of the line of instructions, as in values/strings_grille_ai.xml (prompt_instruction_lead). */
        const val INSTRUCTION_LEAD = "Instruction, never to be repeated in your answer:"

        /** The English order to read one language and write another, as in values/strings_grille_ai.xml (prompt_read_language). */
        const val READ_LANGUAGE = "Read the %1\$s below and write in %2\$s."

        /** The English order on English words in Malayalam letters, as in values/strings_grille_ai.xml (prompt_malayalam_letters). */
        const val MALAYALAM_LETTERS = "Read English words written in Malayalam letters as English."

        /** The English order for a Malayalam recording's name, as in values/strings_grille_ai.xml (prompt_title_topic). */
        const val TITLE_TOPIC = "Name the topic of the text below in 2 to 5 words, in %1\$s."

        /** The English order of a notes call asked again, as in values/strings_grille_ai.xml (prompt_notes_retry). */
        const val NOTES_RETRY = "Write what is said about the subject, never about this recording, its text or its language."

        /** The English names of the languages the note names, as in values/strings_grille_ai.xml (prompt_language_*). */
        val LANGUAGE_NAMES: Map<String, String> = mapOf("ml" to "Malayalam", "en" to "English", "de" to "German")
    }
}
