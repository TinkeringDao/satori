package com.satori.domain.usecase.llm

import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.reading.ReadableSegments

/**
 * Where the notes prompts give a recording's topic to the model: in the user message, above what
 * was said, as the to-do prompt gives its header ([ResourceProvider.getTodoHeaderTemplate]).
 *
 * The system prompt holds nothing of a recording, so it is the same for every recording of a kind
 * and language. The engine keeps a system prompt's encoded state on disk between model loads
 * (lib-llama-android's PromptCache) and loads it again in place of the 1.5-5 s encode, which only
 * a prompt that recurs can use; and a recording's name in such a file would outlive the
 * recording. The topic is a recording's own topic, or its name when the notes are rewritten
 * ([RewriteNotesUseCase]).
 *
 * The notes prompts end with the line that leads in to what was said ("What was said:"), so a
 * topic's sentence straight under it would read as something that was said, and the notes, which
 * add nothing that was not said, could make a point of it. The user message gives that line again
 * under the topic ([ResourceProvider.getTextLeadIn]): the topic stands outside what was said, as it
 * did when it came first in the system prompt. Without a topic the user message is the text alone.
 *
 * The line of instructions on the text's language goes there too, on a line of its own under the
 * topic and above the lead-in ([TextLanguageNote]): a Malayalam transcript's notes are written in
 * English. A call asked again, as its first answer left no point, has one more order at the end of
 * that line ([ResourceProvider.getNotesRetrySentence]), so its system prompt, and the state the
 * engine keeps of it, stay as they were. The lines above the lead-in, and the lead-in, are the
 * call's [Prompt.instructions], which a note must not repeat ([PromptEcho]).
 *
 * A topic in Malayalam letters (a name the owner typed in Malayalam, or one an older version's
 * model wrote of a Malayalam recording) is left out when the text holds none: a Malayalam
 * recording's text is its English readings since 0.8.0 (design 3.A), and the model that reads
 * them never reads Malayalam (D7), the topic included. The notes of English recordings are as before.
 */
internal object NotesTopic {

    /**
     * A notes call's user message, [text], and the lines of it that are no part of what was said
     * ([instructions]): the topic's sentence without the topic, the line of instructions and the
     * lead-in; none when the message is what was said alone. The topic itself is left out, as it
     * is the user's or the recording's name: a note may well say it again. A call asked again also
     * has the name of the notes' language among them, which its line names only as "its language"
     * when the text is in the notes' language: "The recording is in English." says nothing of the
     * subject either (the review of 0.7.2).
     */
    class Prompt(val text: String, val instructions: List<String>)

    /**
     * [text] as the user message of a notes call: the sentence naming [topic]
     * ([ResourceProvider.getTopicPrefix]) and, on the next line, the line of instructions on the
     * language of [text] when it isn't the notes' ([TextLanguageNote]), a blank line, the lead-in
     * to what was said ([ResourceProvider.getTextLeadIn]), all in the notes' [language], then
     * [text] on the next line. Without a language known they are in English, as the sentence was
     * in the system prompt, and there are no instructions. The topic itself goes as it is, never
     * translated: the user typed it, or it is the recording's name. [text] as it is with neither a
     * topic nor instructions, and for a translation, whose model would translate the sentences
     * with the rest.
     *
     * @param language The language of the notes ("en" or "de"), or null when none is known
     * @param retry The call is asked again, as its first answer left no point: the line of
     *   instructions ends with the order to write what is said about the subject
     *   ([ResourceProvider.getNotesRetrySentence]), in English when no language is known, and a
     *   translation, which no answer is filtered for, gets none
     */
    fun prompt(
        text: String,
        topic: String?,
        mode: RecordingMode,
        language: String?,
        resources: ResourceProvider,
        retry: Boolean = false
    ): Prompt {
        if (mode == RecordingMode.REAL_TIME_TRANSLATION) return Prompt(text, emptyList())
        val localeCode = if (!language.isNullOrEmpty()) language else "en"
        val named = topic?.takeIf { it.isNotBlank() && !(ReadableSegments.needsReader(it) && !ReadableSegments.needsReader(text)) }
        val instruction = if (retry) {
            val orders = TextLanguageNote.readingOrders(text, language, resources) + resources.getNotesRetrySentence(localeCode)
            TextLanguageNote.line(localeCode, orders, resources)
        } else {
            TextLanguageNote.of(text, language, resources)
        }
        val above = listOfNotNull(named?.let { resources.getTopicPrefix(localeCode).format(it) }, instruction)
        if (above.isEmpty()) return Prompt(text, emptyList())
        val leadIn = resources.getTextLeadIn(localeCode)
        // Never sent: what the retry's "its language" means, for the filter of the answer alone.
        val language = if (retry) resources.getLanguageName(localeCode, localeCode) else null
        val instructions = listOfNotNull(named?.let { resources.getTopicPrefix(localeCode).format("") }, instruction, leadIn, language)
        return Prompt(above.joinToString("\n") + "\n\n" + leadIn + "\n" + text, instructions)
    }
}
