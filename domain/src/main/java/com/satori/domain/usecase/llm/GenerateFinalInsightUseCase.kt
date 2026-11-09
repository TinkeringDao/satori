package com.satori.domain.usecase.llm

import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Generates a single final LLM insight from accumulated unprocessed transcription content.
 *
 * Called when recording stops, provided that enough words have accumulated since the
 * last periodic inference call. This ensures no meaningful content is lost at the tail
 * of a session.
 *
 * Applies the same prompt and parsing logic as [com.satori.domain.usecase.sync.SyncSttLlmUseCase]
 * but as a one-shot suspend call rather than a streaming Flow, and leaves out what of the answer
 * repeats the call's instructions ([PromptEcho]), which [echoLog] counts, and the title that names
 * only the kind of text, the lines with no word and, since 0.7.3, those that give back the rules
 * of the system prompt ([PromptRules]), which [notesLog] counts.
 *
 * A note is never left empty for want of a second try ([NotesAnswers]): an answer that leaves no
 * point (blank, cut off before its first, or every point left out) is asked once more, on the same
 * model in memory and by the same call, with the order to write what is said about the subject
 * at the end of the user message's line of instructions; the system prompt stays as it was. The
 * second answer is kept when it has a point; when neither has one there is no note, also none of a
 * title alone. A model that fails is not asked again: that is a failure, as before.
 *
 * @property llmRepository Repository for LLM inference
 * @property settingsRepository Repository for reading current app settings (prompts, intervals)
 * @property echoLog Where the count of the note's lines left out as echoes goes
 * @property notesLog Where the other lines left out, and a second try, are told
 * @property answerLog Where the raw answers go (`notes-final-0`, `notes-final-1`); a debug build's
 *   files, none in a release build
 */
class GenerateFinalInsightUseCase(
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val resourceProvider: ResourceProvider,
    private val echoLog: PromptEchoLog = PromptEchoLog.NONE,
    private val notesLog: NotesLog = NotesLog.NONE,
    private val answerLog: AnswerLog = AnswerLog.NONE
) {
    companion object {
        /**
         * Minimum word count in the pending buffer to justify a final inference call. Below this
         * threshold we assume the tail content is too sparse to be useful. The finishing after
         * Stop asks it before it chooses a model for the note
         * ([com.satori.domain.usecase.finish.FinishRecordingUseCase]).
         */
        const val MIN_WORDS_FOR_FINAL_INSIGHT = 20

        // Max tokens allocated for the final insight call (same as periodic calls per mode).
        private const val MAX_TOKENS_SIMPLE    = 512
        private const val MAX_TOKENS_SHORT     = 600
        private const val MAX_TOKENS_LONG      = 768
        private const val MAX_TOKENS_TRANSLATE = 256

        /**
         * Maximum characters in the user prompt sent to the LLM. Keeps the total prompt within
         * typical on-device model context windows (4k–8k tokens). The tail of the transcript is
         * kept because it contains the most recent content. After Stop a tail up to this long
         * gets one final note, and a longer one the whole map-reduce pass
         * ([com.satori.domain.usecase.finish.FinishRecordingUseCase]). Set for English
         * and German: a Malayalam text holds fewer characters in as many tokens
         * ([maxUserPromptChars]).
         */
        const val MAX_USER_PROMPT_CHARS = 6_000

        /**
         * The characters of [text] a final note reads at most: [MAX_USER_PROMPT_CHARS], or the
         * share of it a Malayalam text holds in as many tokens ([TextBudget.chars]), so a
         * Malayalam tail longer than that gets the whole map-reduce pass after Stop instead.
         */
        fun maxUserPromptChars(text: String): Int = TextBudget.chars(MAX_USER_PROMPT_CHARS, text)

        /** The tag of its answers for [answerLog]: `notes-final-0`, `notes-final-1`. */
        private const val CALL = "notes-final"
    }

    /**
     * Generate a final insight if [pendingText] contains enough content.
     *
     * @param pendingText Accumulated transcription text not yet covered by a periodic insight.
     * @param sessionId   Session to associate the insight with.
     * @param mode        Recording mode — drives prompt and output format.
     * @param outputLanguage The language of the output: a translation's target, or the notes'
     *   language ("en" or "de"); null for the Settings default and the app language's prompt.
     * @param topic Optional session topic, put above the text in the user message ([NotesTopic]),
     *   so the system prompt is the same for every recording.
     * @param sourceSegmentIds The lines [pendingText] is made of, stored as the note's sources:
     *   finishing gives the tail it covers ([com.satori.domain.usecase.finish.RecordingTail]),
     *   so a second run finds them cited and writes no second final note.
     * @param earlierNotes The recording's notes from before, the live ones, which stay: a note that
     *   mostly repeats one of them is none (since 0.7.3, [NoteRepeats]).
     * @return [Result.success] with a [LlmInsight] if generated, or [Result.success] with null
     *         if [pendingText] has fewer than [MIN_WORDS_FOR_FINAL_INSIGHT] words or neither answer
     *         has a point in it ([InsightOutputParser.ParsedInsight.hasPoint]), a translation that
     *         has nothing in it, or the note mostly repeats one of [earlierNotes]. [Result.failure]
     *         on LLM error.
     */
    suspend operator fun invoke(
        pendingText: String,
        sessionId: String,
        mode: RecordingMode,
        outputLanguage: String?,
        topic: String? = null,
        sourceSegmentIds: List<String> = emptyList(),
        earlierNotes: List<LlmInsight> = emptyList()
    ): Result<LlmInsight?> {
        val wordCount = pendingText.trim()
            .split(Regex("\\s+"))
            .count { it.isNotEmpty() }

        if (wordCount < MIN_WORDS_FOR_FINAL_INSIGHT) {
            return Result.success(null) // Not enough content — skip
        }

        return try {
            val settings = settingsRepository.getSettings().first()
            val systemPrompt = buildSystemPrompt(mode, settings, outputLanguage)
            // Cap to the most recent content so the prompt fits within the model's
            // context window even for very long sessions (e.g. 30-min recordings).
            val text = pendingText.trim().takeLast(maxUserPromptChars(pendingText))
            val maxTokens = maxTokensForMode(mode)
            val insightTimestamp = System.currentTimeMillis()

            // The keys, the grammar and the parser follow the language of the prompt.
            val keys = InsightFormat.keys(resourceProvider, outputLanguage)
            val grammar = InsightFormat.grammar(mode, keys)

            // Reload model if it was unloaded between sessions (no-op if already loaded).
            llmRepository.reloadModel().onFailure { e ->
                return Result.failure(e)
            }

            val answers = NotesAnswers(sessionId, notesLog, answerLog, earlierNotes)
            answerLog.clear(sessionId, CALL)
            val rules = PromptRules.of(systemPrompt)
            val parsed = answers.of(NotesLog.Part.Final, CALL, mode, keys) { retry ->
                val prompt = NotesTopic.prompt(text, topic, mode, outputLanguage, resourceProvider, retry)
                val builder = StringBuilder()
                llmRepository.generateInsight(prompt.text, systemPrompt, maxTokens, grammar)
                    .collect { token -> builder.append(token) }
                NotesAnswers.Asked(builder.toString().trim(), AnswerChecks(prompt.instructions, rules, spoken = text))
            }
            answers.logDrops(echoLog)
            if (parsed == null || parsed.isEmpty) {
                return Result.success(null)
            }
            Result.success(
                LlmInsight(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    title = parsed.title,
                    content = parsed.content,
                    tasks = parsed.tasks,
                    timestamp = insightTimestamp,
                    sourceSegmentIds = sourceSegmentIds
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(
        mode: RecordingMode,
        settings: AppSettings,
        targetLangCode: String?
    ): String {
        return when (mode) {
            RecordingMode.REAL_TIME_TRANSLATION -> {
                val code = targetLangCode ?: settings.translationTargetLanguage
                // The target's name in the prompt's own language ("auf Englisch"), the raw code if unknown.
                settings.translationSystemPrompt.replace("{target_language}", resourceProvider.getTranslationTargetName(code))
            }
            // The app's prompt in the notes' language, or in the app language as the settings hold it.
            else -> if (!targetLangCode.isNullOrEmpty()) {
                resourceProvider.getPromptForMode(mode, targetLangCode)
            } else {
                when (mode) {
                    RecordingMode.SHORT_MEETING -> settings.shortMeetingSystemPrompt
                    RecordingMode.LONG_MEETING  -> settings.longMeetingSystemPrompt
                    else                        -> settings.simpleListeningSystemPrompt
                }
            }
        }
    }

    private fun maxTokensForMode(mode: RecordingMode): Int = when (mode) {
        RecordingMode.SIMPLE_LISTENING      -> MAX_TOKENS_SIMPLE
        RecordingMode.SHORT_MEETING         -> MAX_TOKENS_SHORT
        RecordingMode.LONG_MEETING          -> MAX_TOKENS_LONG
        RecordingMode.REAL_TIME_TRANSLATION -> MAX_TOKENS_TRANSLATE
    }
}
