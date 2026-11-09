package com.satori.domain.usecase.llm

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.AppSettings
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.monitor.ThermalMonitor
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Generates a single comprehensive LLM insight at the end of a session using a
 * chunked map-reduce pipeline.
 *
 * Algorithm:
 * 1. Concatenate all segment texts ordered by timestamp.
 * 2. If total <= MAX_CHUNK_CHARS, run a single inference (skip chunking).
 * 3. Otherwise split at segment boundaries into chunks of <= MAX_CHUNK_CHARS.
 * 4. Map phase: call LLM for each chunk, emitting [BatchInsightProgress.Mapping] updates.
 *    For REAL_TIME_TRANSLATION, each chunk is translated and results are concatenated directly.
 * 5. Reduce phase: if > 1 chunk, merge the chunks' titles and points with the same mode prompt as
 *    the map phase, emitting [BatchInsightProgress.Reducing]. For translation mode, skip the merge
 *    LLM call.
 * 6. Parse final output via [InsightOutputParser] and return a single [LlmInsight].
 *
 * Every call but a translation's is held to the notes grammar of the output language
 * ([InsightFormat]). What of an answer repeats its call's instructions is left out ([PromptEcho]):
 * from each part before it is kept or merged, and from the merge; [echoLog] hears how many lines
 * the pass left out, once, and [notesLog] how many titles named only the kind of text, how many
 * lines held no word and, since 0.7.3, how many gave back the rules of the system prompt
 * ([PromptRules]).
 *
 * A part or a merge whose answer leaves no point is asked once more ([NotesAnswers]), on the model
 * in memory, through the same call, with the order to write what is said about the subject on the
 * user message's line of instructions, after a cooling gap when the phone is hot; the second answer
 * is kept when it has a point. A part with no point in either answer gives no note and nothing to
 * merge, and a merge with none gives no summary: the parts stay the notes. Since 0.7.3 a part whose
 * note mostly repeats an earlier part's keeps only what is new, or is none with nothing new
 * ([EarlierNotes]; for a Malayalam recording it is none whole, as the model can't read it and says
 * one made-up subject again), and a pass of several parts with one note left has that part's note
 * alone, never the same note again as its summary. A part's words said together are the speaker's
 * as they stand in its own text; the merge's as they stand in the whole recording's ([PromptRules]).
 *
 * @property llmRepository Repository for LLM inference
 * @property settingsRepository Repository for reading current app settings
 * @property resourceProvider Provider for the mode prompts, the topic prefix and the JSON field names
 * @property echoLog Where the count of the notes' lines left out as echoes goes
 * @property notesLog Where the other lines left out, and a second try, are told
 * @property answerLog Where the raw answers go (`notes-part1-0`, `notes-part1-1`, `notes-merge-0`, ...);
 *   a debug build's files, none in a release build. A pass first forgets the notes' answers kept
 *   before, as its notes replace all the recording has
 */
class GenerateBatchInsightUseCase(
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val resourceProvider: ResourceProvider,
    private val thermalMonitor: ThermalMonitor? = null,
    private val echoLog: PromptEchoLog = PromptEchoLog.NONE,
    private val notesLog: NotesLog = NotesLog.NONE,
    private val answerLog: AnswerLog = AnswerLog.NONE
) {
    companion object {
        // Maximum characters sent per chunk in the map phase (~1 200 tokens at 4.14 chars/token).
        // Smaller chunks give the model a more focused context window, improving summary quality.
        // Counted in English characters (TextBudget.cost): a part of a Malayalam transcript holds
        // 3,000 of its characters, as many tokens.
        private const val MAX_CHUNK_CHARS = 5_000

        // Maximum characters in the reduce-phase input (~2 000 tokens).
        private const val MAX_MERGE_INPUT_CHARS = 8_200

        // Max LLM output tokens per phase. A part of a meeting gets up to 8 points and 8 action
        // items, as many as a live meeting note: a budget cut short ends them early.
        private const val MAX_TOKENS_MAP = 600
        private const val MAX_TOKENS_REDUCE = 768 // comprehensive merged output
        private const val MAX_TOKENS_TRANSLATE = 256

        // Minimum word count to justify batch inference — mirrors GenerateFinalInsightUseCase.
        // RewriteNotesUseCase.hasEnoughText asks for as many words, so that a rewrite is never
        // offered for a transcript this pipeline would answer with nothing.
        const val MIN_WORDS_FOR_BATCH = 20

        // Cooling gap inserted between consecutive LLM runs when the device reports thermal
        // pressure (MODERATE status or above). Allows the SoC to re-enter thermal budget
        // without interrupting the pipeline; 1 s is enough to recover ~2-3 °C on most SoCs.
        private const val COOLING_GAP_MS = 1_000L

        // The tags of a pass's answers for answerLog start with it: "notes-part1-0", "notes-merge-1".
        private const val NOTES_CALLS = "notes"
    }

    /**
     * Run the batch insight pipeline on the given [segments].
     *
     * @param segments All completed transcription segments for this session (ordered by timestamp).
     * @param sessionId Session to associate the resulting insight with.
     * @param mode Recording mode — drives prompt choice and parsing.
     * @param outputLanguage The language of the output: a translation's target, or the notes'
     *   language ("en" or "de"); null for the Settings default and the app language's prompt.
     * @param topic Optional session topic, put above the text of each part and of the merge in the
     *   user message ([NotesTopic]), so the system prompt is the same for every recording. A
     *   translation gets none.
     * @param onProgress Callback invoked at each pipeline state transition.
     * @param onChunkInsight Callback invoked for each intermediate chunk insight (map phase only).
     *        Intermediate insights have non-empty [LlmInsight.sourceSegmentIds] and a timestamp
     *        matching the last segment in the chunk. The caller is responsible for persisting them.
     * @return [Result.success] with an [LlmInsight] if generated, [Result.success] with null if
     *         the transcript is too short or the answers have nothing in them, or
     *         [Result.failure] on error.
     */
    suspend operator fun invoke(
        segments: List<TranscriptionSegment>,
        sessionId: String,
        mode: RecordingMode,
        outputLanguage: String?,
        topic: String? = null,
        onProgress: (BatchInsightProgress) -> Unit = {},
        onChunkInsight: suspend (LlmInsight) -> Unit = {}
    ): Result<LlmInsight?> {
        val fullText = segments.joinToString(" ") { it.text.trim() }.trim()

        val wordCount = fullText.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (wordCount < MIN_WORDS_FOR_BATCH) {
            return Result.success(null)
        }

        return try {
            val settings = settingsRepository.getSettings().first()
            // The keys, the grammar and the parser follow the language of the prompt.
            val keys = InsightFormat.keys(resourceProvider, outputLanguage)

            // Reload model before starting — no-op if already loaded.
            // For lazy-init flows (END_OF_SESSION, history insight) this is the first actual load.
            llmRepository.reloadModel().onFailure { return Result.failure(it) }
            // Post-load check: model is now in memory so the RAM reading is accurate.
            llmRepository.checkAndCacheMemoryConstraint()

            val insight = if (mode == RecordingMode.REAL_TIME_TRANSLATION) {
                runTranslationBatch(fullText, sessionId, mode, settings, outputLanguage, onProgress)
            } else {
                runAnalysisBatch(segments, sessionId, mode, settings, keys, outputLanguage, topic, onProgress, onChunkInsight)
            }

            onProgress(BatchInsightProgress.Complete)
            Result.success(insight)
        } catch (e: CancellationException) {
            // A cancel is not a failure: let it end the caller's coroutine.
            throw e
        } catch (e: Exception) {
            onProgress(BatchInsightProgress.Error(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    // ─── Translation batch ────────────────────────────────────────────────────

    private suspend fun runTranslationBatch(
        fullText: String,
        sessionId: String,
        mode: RecordingMode,
        settings: AppSettings,
        outputLanguage: String?,
        onProgress: (BatchInsightProgress) -> Unit
    ): LlmInsight? {
        val systemPrompt = buildSystemPrompt(mode, settings, outputLanguage)
        // For translation, split the plain text since segment-level IDs are not needed.
        val chunks = splitTextIntoChunks(fullText)
        val translations = mutableListOf<String>()

        chunks.forEachIndexed { index, chunk ->
            onProgress(BatchInsightProgress.Mapping(index + 1, chunks.size))
            // As a live translation reads: without quotes wrapped around all of it.
            val translation = InsightOutputParser.translationText(runInference(chunk, systemPrompt, MAX_TOKENS_TRANSLATE, grammar = null))
            if (translation.isNotBlank()) translations.add(translation)

            coolIfNeeded()
        }

        val combinedTranslation = translations.joinToString("\n\n")
        if (combinedTranslation.isBlank()) return null

        return LlmInsight(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = null,
            content = combinedTranslation,
            tasks = null,
            timestamp = System.currentTimeMillis(),
            sourceSegmentIds = emptyList()
        )
    }

    // ─── Analysis batch ───────────────────────────────────────────────────────

    private suspend fun runAnalysisBatch(
        segments: List<TranscriptionSegment>,
        sessionId: String,
        mode: RecordingMode,
        settings: AppSettings,
        keys: InsightFormat.Keys,
        outputLanguage: String?,
        topic: String?,
        onProgress: (BatchInsightProgress) -> Unit,
        onChunkInsight: suspend (LlmInsight) -> Unit
    ): LlmInsight? {
        // Use the same mode-specific system prompt as real-time inference so the output format
        // and style match exactly what the parser and UI expect. The topic goes with each text.
        val modeSystemPrompt = buildSystemPrompt(mode, settings, outputLanguage)
        val grammar = InsightFormat.grammar(mode, keys)
        // What every call of the pass is held to besides its own instruction lines: the rules of
        // its system prompt. What was said, whose words said together are the speaker's, is each
        // part's own text for the part, and the whole recording for the merge, which says again
        // what the parts said.
        val wholeText = segments.joinToString(" ") { it.text.trim() }
        val call = Call(modeSystemPrompt, PromptRules.of(modeSystemPrompt), grammar)
        val segmentChunks = splitSegmentsIntoChunks(segments)
        val chunkSummaries = mutableListOf<InsightOutputParser.ParsedInsight>()
        // A part of a recording the model can't read that mostly says an earlier part again goes whole.
        val answers = NotesAnswers(sessionId, notesLog, answerLog, wholeRepeats = LanguageDetector.isMalayalam(wholeText))
        // Its notes replace all the recording has: the answers kept of those go.
        answerLog.clear(sessionId, NOTES_CALLS)

        // Map phase: summarize each chunk, emit intermediate insights.
        segmentChunks.forEachIndexed { index, chunkSegments ->
            onProgress(BatchInsightProgress.Mapping(index + 1, segmentChunks.size))
            val chunkText = chunkSegments.joinToString(" ") { it.text.trim() }
            val part = NotesLog.Part.Pass(index + 1, segmentChunks.size)
            // A part whose answers have no point in them, or nothing but what their prompt said,
            // gives no note and nothing to merge.
            val parsedChunk = answers.of(part, "$NOTES_CALLS-part${index + 1}", mode, keys) { retry ->
                ask(chunkText, topic, mode, outputLanguage, call, spoken = chunkText, MAX_TOKENS_MAP, retry)
            }
            if (parsedChunk != null) {
                chunkSummaries.add(parsedChunk)

                // Emit intermediate insight only when there are multiple chunks — a single chunk
                // produces only the final insight, so an intermediate would be redundant.
                if (segmentChunks.size > 1) {
                    onChunkInsight(
                        LlmInsight(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            title = parsedChunk.title,
                            content = parsedChunk.content,
                            tasks = parsedChunk.tasks,
                            // Timestamp matches the last segment in this chunk so that chronological
                            // interleaving in the session detail view places it correctly.
                            timestamp = chunkSegments.last().timestamp,
                            sourceSegmentIds = chunkSegments.map { it.id }
                        )
                    )
                }
            }

            coolIfNeeded()
        }

        if (chunkSummaries.isEmpty()) {
            answers.logDrops(echoLog)
            return null
        }

        // Reduce phase: merge the parts' notes. A pass of one part has its note as its summary; a
        // pass of more with one note left gave it as a part already, which it doesn't repeat.
        val parsed = if (chunkSummaries.size == 1) {
            chunkSummaries[0].takeIf { segmentChunks.size == 1 }
        } else {
            onProgress(BatchInsightProgress.Reducing)
            val mergeInput = chunkSummaries
                .map(::mergeInputOf)
                .joinToString("\n\n---\n\n")
                .take(MAX_MERGE_INPUT_CHARS)
            answers.of(NotesLog.Part.Merge, "$NOTES_CALLS-merge", mode, keys) { retry ->
                ask(mergeInput, topic, mode, outputLanguage, call, spoken = wholeText, MAX_TOKENS_REDUCE, retry)
            }
        }
        answers.logDrops(echoLog)
        if (parsed == null || parsed.isEmpty) return null

        return LlmInsight(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = parsed.title,
            content = parsed.content,
            tasks = parsed.tasks,
            timestamp = System.currentTimeMillis(),
            // Empty list marks this as the final (reduce) insight, distinguishing it
            // from intermediate (map) insights that carry their source segment IDs.
            sourceSegmentIds = emptyList()
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * What every notes call of a pass shares: its [systemPrompt] and the [rules] of it
     * ([PromptRules]), and the [grammar] of its answer.
     */
    private class Call(val systemPrompt: String, val rules: PromptRules, val grammar: OutputGrammar?)

    /**
     * One notes call of the pass on [text] ([NotesTopic.prompt]), whose answer's words said
     * together in [spoken] are the speaker's: asked [retry] when the call's first answer left no
     * point, then after the cooling gap of a hot phone, as between two parts.
     */
    private suspend fun ask(
        text: String,
        topic: String?,
        mode: RecordingMode,
        outputLanguage: String?,
        call: Call,
        spoken: String,
        maxTokens: Int,
        retry: Boolean
    ): NotesAnswers.Asked {
        if (retry) coolIfNeeded()
        val prompt = NotesTopic.prompt(text, topic, mode, outputLanguage, resourceProvider, retry)
        val answer = runInference(prompt.text, call.systemPrompt, maxTokens, call.grammar)
        return NotesAnswers.Asked(answer, AnswerChecks(prompt.instructions, call.rules, spoken))
    }

    private suspend fun runInference(
        userPrompt: String,
        systemPrompt: String,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): String {
        // Proactively switch to conservative threads if this context size has previously
        // caused RAM pressure on this device (applies before any freeModelIfConstrained reload).
        if (llmRepository.isLargeContext(userPrompt.length)) {
            llmRepository.useConservativeThreads()
        }
        llmRepository.reloadModel()
        val builder = StringBuilder()
        llmRepository.generateInsight(userPrompt, systemPrompt, maxTokens, grammar)
            .collect { token -> builder.append(token) }
        // Record the inference size for adaptive threshold learning.
        llmRepository.recordConstrainedInference(userPrompt.length)
        return InsightOutputParser.stripCodeFences(builder.toString().trim())
    }

    /**
     * Inserts a [COOLING_GAP_MS] delay when the device SoC is under thermal pressure.
     * This prevents sustained token-generation load from deep-throttling the CPU,
     * which would otherwise degrade token/s across the entire batch pipeline.
     * No-op when [thermalMonitor] is null or the device is not overheating.
     */
    private suspend fun coolIfNeeded() {
        if (thermalMonitor?.isOverheating() == true) {
            delay(COOLING_GAP_MS)
        }
    }

    /**
     * Split plain [text] into chunks of at most [MAX_CHUNK_CHARS] characters.
     * Used for translation mode where segment boundaries are irrelevant.
     */
    private fun splitTextIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + MAX_CHUNK_CHARS, text.length)
            if (end < text.length) {
                val spaceIdx = text.lastIndexOf(' ', end)
                if (spaceIdx > start) end = spaceIdx
            }
            chunks.add(text.substring(start, end).trim())
            start = end
            if (start < text.length && text[start] == ' ') start++
        }
        return chunks
    }

    /**
     * Split [segments] into groups of at most [MAX_CHUNK_CHARS] combined characters, each segment
     * counted by what its script costs in tokens ([TextBudget.cost]), so a Malayalam transcript
     * comes in smaller parts. Splits at segment boundaries so each chunk retains its source
     * segment IDs and timestamps.
     */
    private fun splitSegmentsIntoChunks(segments: List<TranscriptionSegment>): List<List<TranscriptionSegment>> {
        if (segments.isEmpty()) return emptyList()

        val chunks = mutableListOf<MutableList<TranscriptionSegment>>()
        var currentChunk = mutableListOf<TranscriptionSegment>()
        var currentChunkChars = 0

        for (segment in segments) {
            val segLen = TextBudget.cost(segment.text.trim()) + 1 // +1 for the joining space
            if (currentChunk.isNotEmpty() && currentChunkChars + segLen > MAX_CHUNK_CHARS) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentChunkChars = 0
            }
            currentChunk.add(segment)
            currentChunkChars += segLen
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk)

        return chunks
    }

    private fun buildSystemPrompt(
        mode: RecordingMode,
        settings: AppSettings,
        outputLanguage: String?
    ): String {
        return when (mode) {
            RecordingMode.REAL_TIME_TRANSLATION -> {
                val code = outputLanguage ?: settings.translationTargetLanguage
                // The target's name in the prompt's own language ("auf Englisch"), the raw code if unknown.
                settings.translationSystemPrompt.replace("{target_language}", resourceProvider.getTranslationTargetName(code))
            }
            // The app's prompt in the notes' language, or in the app language as the settings hold it.
            else -> if (!outputLanguage.isNullOrEmpty()) {
                resourceProvider.getPromptForMode(mode, outputLanguage)
            } else {
                when (mode) {
                    RecordingMode.SHORT_MEETING -> settings.shortMeetingSystemPrompt
                    RecordingMode.LONG_MEETING  -> settings.longMeetingSystemPrompt
                    else                        -> settings.simpleListeningSystemPrompt
                }
            }
        }
    }

    /**
     * One part's note as the merge reads it: its title and its points ("- ..."), as text, without
     * what of its answer repeated its prompt.
     *
     * The JSON around them would be a shape the model copies instead of merging, and the merge
     * prompt asks for the whole recording's action items again, so a part's own only take room
     * within [MAX_MERGE_INPUT_CHARS].
     */
    private fun mergeInputOf(part: InsightOutputParser.ParsedInsight): String =
        listOfNotNull(part.title, part.content.takeIf { it.isNotBlank() }).joinToString("\n")
}
