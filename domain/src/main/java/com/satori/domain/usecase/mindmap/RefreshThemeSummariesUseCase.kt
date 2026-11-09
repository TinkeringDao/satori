package com.satori.domain.usecase.mindmap

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.MindNode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.PromptRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * The learning step of the mind map: rewrites what the AI knows about a theme from the recordings
 * linked to it, the way a Hindsight mental model is refreshed when its sources change.
 *
 * For each theme, the model reads the theme's label on the first line, then one line for each of
 * its 8 most recent linked recordings, newest first: the recording's date, then its points
 * ([MindMapRepository.links], [ThemeSummaryPrompt]). It answers with 2 to 4 plain sentences on what
 * the speaker said, decided or still wonders about the theme. The answer is held to a JSON schema,
 * {"summary": "..."}, and the prompt is [ResourceProvider.getMindSummaryPrompt] in the notes
 * language, which [NotesLanguageResolver] chooses for the newest of those recordings from its own
 * points, as the other steps do. Only its own: the resolver may store what it finds on the
 * recording, and the other recordings' points could be in another language. Since 0.7.3 the
 * sentences of it that give back the rules of the summary prompt ([PromptRules]) are left out
 * ([ThemeSummaryPrompt.screened]), and [mapLog] counts them; a summary with none left is as one
 * that can't be read. The summary is stored with the time ([MindMapRepository.setSummary]), which
 * clears its stale mark.
 *
 * Which themes: the themeIds of [invoke] first, in their order, whether stale or not; then, while
 * the limit allows, the other themes that need a summary (stale, or without one yet) in the order
 * of [MindMapRepository.themes], most recordings first. The rest stay stale for the next time. A
 * theme without recordings, or whose recordings hold no points, has nothing to sum up: it is
 * passed over and takes no place within the limit, or it would take one at every finish.
 *
 * Its callers:
 * - The finishing step, right after [MapRecordingUseCase], with the themes the recording joined
 *   (none when it joined none) and [PER_FINISH]. Stale themes so catch up whenever a recording
 *   finishes while the model is loaded.
 * - The Update of a theme's page, with that theme and a limit of 1.
 * - [MapOlderRecordingsUseCase], once every older recording is read, with no limit. It gives way
 *   between two summaries to a recording waiting to be finished (shouldStop), and the themes left
 *   stay stale for its next run.
 *
 * It doesn't start the LLM processing service: the finishing step already runs under it, and the
 * other callers start it themselves. A theme whose answer can't be read is skipped and stays stale;
 * a model that can't be loaded or run ends the call ([Outcome.ModelFailed]). Only cancellation is
 * thrown, and a database error ends the call like a failed model. No point or summary text is
 * logged, only counts and timings.
 *
 * @property mindMapRepository Where the themes and their links are read and the summaries stored
 * @property llmRepository Runs the model; loaded on demand ([LlmRepository.reloadModel])
 * @property resourceProvider Supplies the summary prompt in the notes language
 * @property notesLanguage Chooses the notes language
 * @property now Clock for the time stored with each summary
 * @property zone The time zone the recordings' dates are given in; the phone's, read at each call
 * @property mapLog Where the counts of the summaries' sentences left out go
 */
class RefreshThemeSummariesUseCase(
    private val mindMapRepository: MindMapRepository,
    private val llmRepository: LlmRepository,
    private val resourceProvider: ResourceProvider,
    private val notesLanguage: NotesLanguageResolver,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val mapLog: MindMapLog = MindMapLog.NONE
) {
    companion object {
        /**
         * The most summaries one finished recording rewrites. Each is a model call of several
         * seconds after Stop, so the others wait, stale, for the next recording or for Update.
         */
        const val PER_FINISH = 3

        // A summary of at most ThemeSummaryPrompt.MAX_SUMMARY_CHARS characters is about 250
        // tokens; the rest is room for the JSON and the whitespace the grammar allows.
        private const val MAX_TOKENS = 320
    }

    /** How a call ended, unless it was cancelled. */
    sealed interface Outcome {
        /** [count] summaries were written; the themes past the limit stay stale. */
        data class Written(val count: Int) : Outcome

        /** The model could not be loaded or run, so the call stopped after [count] summaries. */
        data class ModelFailed(val count: Int) : Outcome
    }

    /**
     * @param themeIds The themes to rewrite first, in this order, whether stale or not
     * @param limit The most summaries to write in all
     * @param shouldStop Asked before each summary but the first: true ends the call there, with
     *   the summaries written so far ([Outcome.Written]); the themes left stay stale
     * @param onProgress Called as each summary begins, with its number (from 1) and how many are planned
     * @return How it ended; throws only [kotlinx.coroutines.CancellationException]
     */
    suspend operator fun invoke(
        themeIds: List<String> = emptyList(),
        limit: Int = PER_FINISH,
        shouldStop: () -> Boolean = { false },
        onProgress: (index: Int, total: Int) -> Unit = { _, _ -> }
    ): Outcome {
        var written = 0
        try {
            val plan = plan(themeIds, limit)
            for ((index, theme) in plan.withIndex()) {
                if (index > 0 && shouldStop()) break
                onProgress(index + 1, plan.size)
                // Read again: the links may have changed while the summaries before were written.
                val recordings = ThemeSummaryPrompt.recordings(mindMapRepository.links(theme.id).first())
                val newest = recordings.firstOrNull() ?: continue
                val language = notesLanguage.resolve(newest.sessionId, newest.points.joinToString(" "))
                val systemPrompt = resourceProvider.getMindSummaryPrompt(language)
                val answer = llmRepository.answerOrNull(
                    text = ThemeSummaryPrompt.input(theme.label, recordings, language, zone()),
                    systemPrompt = systemPrompt,
                    maxTokens = MAX_TOKENS,
                    grammar = OutputGrammar.JsonSchema(ThemeSummaryPrompt.SCHEMA)
                ) ?: return Outcome.ModelFailed(written)
                // The model reads the map's points, none of them said as it is: no word is the speaker's.
                val (summary, drops) = ThemeSummaryPrompt.parse(answer)
                    ?.let { ThemeSummaryPrompt.screened(it, AnswerChecks(rules = PromptRules.of(systemPrompt))) }
                    ?: continue
                for (why in AnswerDrop.entries) drops[why]?.let { mapLog.dropped(theme.id, it, MindMapLog.What.SUMMARY_SENTENCES, why) }
                if (summary == null) continue
                // A cancel that came as the last token was written must not store the summary.
                currentCoroutineContext().ensureActive()
                mindMapRepository.setSummary(theme.id, summary, now())
                written++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.ModelFailed(written)
        }
        return Outcome.Written(written)
    }

    /**
     * The themes to write, at most [limit]: [themeIds] that have recordings, in their order, then
     * the other themes with recordings that need a summary, in the order of the map; of these,
     * only those whose recordings hold points to sum up.
     */
    private suspend fun plan(themeIds: List<String>, limit: Int): List<MindNode> {
        if (limit <= 0) return emptyList()
        val themes = mindMapRepository.themes().first()
        val listed = themes.associate { it.node.id to it.node }
        val order = LinkedHashSet<String>()
        themeIds.filterTo(order) { it in listed }
        themes.filter { needsSummary(it.node) }.mapTo(order) { it.node.id }
        val plan = mutableListOf<MindNode>()
        for (id in order) {
            if (plan.size >= limit) break
            if (ThemeSummaryPrompt.recordings(mindMapRepository.links(id).first()).isNotEmpty()) plan += listed.getValue(id)
        }
        return plan
    }

    /** A summary that is stale, or none yet. */
    private fun needsSummary(theme: MindNode): Boolean = theme.summaryStale || theme.summary == null
}
