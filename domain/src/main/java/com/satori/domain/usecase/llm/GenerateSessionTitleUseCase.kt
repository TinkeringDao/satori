package com.satori.domain.usecase.llm

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.CancellationException

/**
 * Asks the model for a short title that says what a recording is about.
 *
 * The model reads the recording's notes when it has some, since they already sum it up in far
 * fewer words, and the transcript only without them. The answer goes through
 * [SessionTitles.clean].
 *
 * A Malayalam recording is named in English: its notes are English already, and a transcript it
 * is named from (a note too short for notes, say) goes with the order to read the Malayalam and
 * write in English ([TextLanguageNote]), before the quoted transcript, not in it. Either way the
 * line of instructions asks for its topic in 2 to 5 words ([ResourceProvider.getTitleTopicSentence]):
 * on 2026-10-16 the 1B named one after the kind of text it was, not after what it was about. The
 * system prompt is the same for every recording. A title that repeats that line ([PromptEcho]), or
 * a Malayalam recording's that only names its language ("Malayalam Audio"), is no title, and
 * [echoLog] hears of it; one that names only the kind of text, or holds nothing, is none either
 * ([SessionTitles.verdict]), nor since 0.7.3 one that gives back the rules of the system prompt
 * ([PromptRules]: "Subject Line of an Email") or is in another script than the Latin one
 * ([WrongScript]), and [notesLog] hears of those; the name is held to them as it is kept and as
 * the model wrote it, so "Short Plain Notes" is no "Short Plain" (the review of 0.7.3). A title in
 * the words of what it names stays, generic or not: the text it was asked from and the
 * recording's notes are its subject ([attempt]); one only they keep is a weak name
 * ([Attempt.Titled.weak]).
 *
 * @property llmRepository Runs the model; loaded on demand.
 * @property resourceProvider Supplies the title prompt in the title's language.
 * @property echoLog Where a title left out as an echo is counted
 * @property notesLog Where a title left out as one of the kind of text, or as empty, is counted
 * @property answerLog Where the raw answers go (`name-0`, `name-1`); a debug build's files, none in a
 *   release build
 */
class GenerateSessionTitleUseCase(
    private val llmRepository: LlmRepository,
    private val resourceProvider: ResourceProvider,
    private val echoLog: PromptEchoLog = PromptEchoLog.NONE,
    private val notesLog: NotesLog = NotesLog.NONE,
    private val answerLog: AnswerLog = AnswerLog.NONE
) {

    /** What one answer of the model came to ([attempt]). */
    sealed interface Attempt {
        /**
         * [title] is the recording's name. [weak] when only the words of what it names keep it
         * ([SessionTitles.Verdict.Kept.bySubject]: "Meeting Minutes" for a meeting whose notes say
         * "minutes"): a specific note title goes before it (AutoNameSessionUseCase).
         */
        data class Titled(val title: String, val weak: Boolean = false) : Attempt

        /** The model answered, but its answer is no name, for [why]. */
        data class Rejected(val why: Rejection) : Attempt

        /** There was too little to name the recording after ([titleInput]): the model was not asked. */
        data object NoInput : Attempt

        /** The model couldn't be loaded or run ([error]). */
        data class Failed(val error: Throwable) : Attempt
    }

    /** Why an answer is no name. */
    enum class Rejection {
        /** It repeats the line of instructions ([PromptEcho]). */
        ECHO,

        /** It gives back the rules of the name's system prompt ([PromptRules], since 0.7.3): "Subject Line of an Email". */
        RULES,

        /** It is mostly in another script than the Latin one ([WrongScript], since 0.7.3): asked in English or German. */
        OTHER_SCRIPT,

        /** It names only the kind of text ([SessionTitles.Verdict.Generic], [SessionTitles.Verdict.KindAlone]). */
        GENERIC,

        /** It holds nothing to read ([SessionTitles.Verdict.Empty]). */
        EMPTY
    }
    companion object {
        /** Below this many transcribed words there is nothing to name a recording after. */
        const val MIN_WORDS = 4

        // About 750 tokens: quick to read on the phone's CPU, and plenty to find a subject in. Of
        // a Malayalam transcript, the share that costs as many tokens (TextBudget).
        internal const val MAX_INPUT_CHARS = 3_000

        // A title of six words is ten to twenty tokens.
        private const val MAX_TOKENS = 24

        /** The tag of its answers for answerLog: `name-0`, `name-1`. */
        const val CALL = "name"

        // Each insight line gets an equal share of the input, within these bounds.
        private const val MIN_SUMMARY_CHARS = 60
        private const val MAX_SUMMARY_CHARS = 600

        /**
         * What the model reads to name the recording, or null when the transcript has fewer
         * than [MIN_WORDS] words.
         *
         * - A recording with notes is named after them, whatever the transcript's length: one
         *   line each, title and the start of its points or summary, oldest first. They say its
         *   subject already, and the model reads them in a fraction of the time: from the
         *   transcript, the name of a 146 s note took 5.2 s to read in on the phone.
         * - Without notes the transcript is used, cut to its first [MAX_INPUT_CHARS], or to the
         *   share of them a Malayalam transcript holds in as many tokens ([TextBudget.chars]).
         */
        fun titleInput(segments: List<TranscriptionSegment>, insights: List<LlmInsight>): String? {
            val transcript = transcriptOf(segments)
            val words = transcript.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words < MIN_WORDS) return null

            val written = insights.filter { it.content.isNotBlank() }.sortedBy { it.timestamp }
            if (written.isEmpty()) return transcript.take(TextBudget.chars(MAX_INPUT_CHARS, transcript))

            val share = (MAX_INPUT_CHARS / written.size).coerceIn(MIN_SUMMARY_CHARS, MAX_SUMMARY_CHARS)
            return written.joinToString("\n") { insight ->
                val summary = summaryOf(insight.content).replace(Regex("\\s+"), " ").trim().take(share)
                val title = insight.title?.trim().orEmpty()
                if (title.isEmpty()) "- $summary" else "- $title: $summary"
            }.take(MAX_INPUT_CHARS)
        }

        /** The complete segments' text, in spoken order. */
        private fun transcriptOf(segments: List<TranscriptionSegment>): String =
            segments
                .filter { it.isComplete }
                .sortedBy { it.timestamp }
                .joinToString(" ") { it.text.trim() }
                .trim()

        /**
         * A note's text for its one line: older insights store JSON with a summary field, newer
         * ones their points as a Markdown list, read here as "first point; second point" so the
         * list markers don't run into the line's own "- ".
         */
        private fun summaryOf(content: String): String {
            if (content.trimStart().startsWith("{")) {
                return InsightOutputParser.extractJsonField(content, "summary")
                    ?: InsightOutputParser.extractJsonField(content, "zusammenfassung")
                    ?: content
            }
            val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || !lines.all { it.startsWith("- ") }) return content
            return lines.joinToString("; ") { it.removePrefix("- ").trim().trimEnd('.') }
        }
    }

    /**
     * @param segments The recording's transcript segments.
     * @param insights The recording's insights, in any order.
     * @param languageCode "en" or "de": the language the title is written in.
     * @return The title; null when there is too little to go on, the answer names nothing
     *         specific or repeats the line of instructions; a failure when the model can't be
     *         loaded or run.
     */
    suspend operator fun invoke(
        segments: List<TranscriptionSegment>,
        insights: List<LlmInsight>,
        languageCode: String
    ): Result<String?> = when (val attempt = attempt(segments, insights, languageCode)) {
        is Attempt.Titled -> Result.success(attempt.title)
        is Attempt.Rejected, Attempt.NoInput -> Result.success(null)
        is Attempt.Failed -> Result.failure(attempt.error)
    }

    /**
     * One answer of the model for the recording's name, from [insights] when one has text, else
     * from the transcript ([titleInput]), and what it came to. A title of the words of what it names
     * stays as written, generic or not ([SessionTitles.verdict]): the text it was asked from, and
     * the text of [about], the recording's notes, which name its subject in the language of the
     * name also when it is asked from a Malayalam transcript. The answer goes to [answerLog] as the
     * model gave it, under `name-<answer>`; one left out is counted for the field log.
     *
     * @param answer Which answer of the recording's naming this is, from 0, for [answerLog]'s tag
     * @param about The notes whose words a name may be made of, besides the text it is asked from
     */
    suspend fun attempt(
        segments: List<TranscriptionSegment>,
        insights: List<LlmInsight>,
        languageCode: String,
        answer: Int = 0,
        about: List<LlmInsight> = insights
    ): Attempt {
        val input = titleInput(segments, insights) ?: return Attempt.NoInput
        val sessionId = segments.firstOrNull()?.sessionId
        return try {
            llmRepository.reloadModel().onFailure { return Attempt.Failed(it) }
            val text = StringBuilder()
            val prompt = resourceProvider.getSessionTitlePrompt(languageCode)
            // A text in another language than the title, and any text of a Malayalam recording,
            // goes with the line of instructions, before the text.
            val malayalam = LanguageDetector.isMalayalam(transcriptOf(segments))
            val lead = TextLanguageNote.forTitle(input, languageCode, malayalam, resourceProvider)
            val tokens = lead?.let { llmRepository.generateInsight(input, prompt, MAX_TOKENS, grammar = null, lead = it) }
                ?: llmRepository.generateInsight(input, prompt, MAX_TOKENS)
            tokens.collect { token -> text.append(token) }
            sessionId?.let { answerLog.answer(it, AnswerLog.tag(CALL, answer), text.toString()) }
            val subject = (listOf(input) + about.map { it.content }).joinToString("\n")
            val verdict = SessionTitles.verdict(InsightOutputParser.stripThinkingBlock(text.toString()), subject)
            val kept = verdict as? SessionTitles.Verdict.Kept
                ?: return rejected(sessionId, if (verdict == SessionTitles.Verdict.Empty) Rejection.EMPTY else Rejection.GENERIC)
            // What was said, not the notes the name may be asked from: a name of its words is the speaker's.
            val checks = AnswerChecks(echoLines(lead, malayalam, languageCode), PromptRules.of(prompt), spoken = transcriptOf(segments))
            when (checks.dropOfAny(kept.title, SessionTitles.written(InsightOutputParser.stripThinkingBlock(text.toString())))) {
                AnswerDrop.OTHER_SCRIPT -> rejected(sessionId, Rejection.OTHER_SCRIPT)
                AnswerDrop.PROMPT -> rejected(sessionId, Rejection.ECHO)
                AnswerDrop.RULES -> rejected(sessionId, Rejection.RULES)
                null -> Attempt.Titled(kept.title, weak = kept.bySubject)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Attempt.Failed(e)
        }
    }

    /**
     * Why [title], the title of one of the recording's notes that an earlier version stored, can't
     * stand in for its name (AutoNameSessionUseCase), or null when it can: it is in another script
     * than the Latin one or one the transcript isn't in, it repeats the line on the language of a
     * Malayalam recording, or it gives back the rules of the notes prompt of the recording's
     * [mode] in [languageCode] (since the review of 0.7.3: until then only the rule phrases, so
     * "Goal: Provide" stood in).
     */
    fun storedTitleDrop(title: String, segments: List<TranscriptionSegment>, languageCode: String, mode: RecordingMode): AnswerDrop? {
        val transcript = transcriptOf(segments)
        val malayalam = LanguageDetector.isMalayalam(transcript)
        // A translation's notes have no title; the notes prompt of a voice note stands in for its own.
        val notesMode = if (mode == RecordingMode.REAL_TIME_TRANSLATION) RecordingMode.SIMPLE_LISTENING else mode
        val notesPrompt = resourceProvider.getPromptForMode(notesMode, languageCode)
        return AnswerChecks(echoLines(null, malayalam, languageCode), PromptRules.of(notesPrompt), spoken = transcript).dropOf(title)
    }

    /** An answer that is no name, for [why], told to the field log under the recording [sessionId], if it has one. */
    private fun rejected(sessionId: String?, why: Rejection): Attempt.Rejected {
        if (sessionId != null) {
            when (why) {
                Rejection.ECHO -> echoLog.dropped(sessionId, 1, PromptEchoLog.What.NAMES)
                Rejection.RULES -> notesLog.log(NotesLog.Dropped(sessionId, 1, NotesLog.Piece.NAMES, AnswerDrop.RULES))
                Rejection.OTHER_SCRIPT -> notesLog.log(NotesLog.Dropped(sessionId, 1, NotesLog.Piece.NAMES, AnswerDrop.OTHER_SCRIPT))
                Rejection.GENERIC -> notesLog.log(NotesLog.GenericNames(sessionId, 1))
                Rejection.EMPTY -> notesLog.log(NotesLog.EmptyNames(sessionId, 1))
            }
        }
        return Attempt.Rejected(why)
    }

    /**
     * What a name must not repeat ([PromptEcho]): the line of instructions [lead], and for a
     * [malayalam] recording the language's name, which the lead for its English notes doesn't say
     * ("name the topic of the text below in 2 to 5 words, in English"): "Malayalam Audio" names the
     * language and the kind of text, never the subject.
     */
    private fun echoLines(lead: String?, malayalam: Boolean, languageCode: String): List<String> =
        listOfNotNull(lead, resourceProvider.getLanguageName(LanguageDetector.MALAYALAM, languageCode).takeIf { malayalam })
}
