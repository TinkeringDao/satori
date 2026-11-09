package com.satori.domain.usecase.mindmap

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.readingLanguage
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.PromptRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The finishing step [com.satori.domain.usecase.finish.FinishStep.Map]: links what one
 * recording is about into the mind map. It is MapifyAI's transcript, key points, hierarchy, done on
 * the phone and as JSON. The model only names topics and their points, and code decides where they
 * go, so a 1B model can't invent the map.
 *
 * What it does, in this order:
 * 1. Reads the recording. The model gets its notes (each note's title and summary) or, when it has
 *    none, its complete transcript, cut to the batch chunk size ([MindTopics.input]). A recording
 *    with less complete transcript than [MindMapRepository.MIN_TRANSCRIPT_CHARS] ([hasEnoughText],
 *    counted the way [MindMapRepository.unmappedSessionIds] counts it) is skipped without a model
 *    call.
 * 2. Asks the model for at most 3 topics, each with a label of 1 to 4 words and at most 3 points of
 *    one short sentence. The answer is held to a JSON schema ([MindTopics.SCHEMA]),
 *    {"topics": [{"label": "...", "points": ["..."]}]}, and the prompt is
 *    [ResourceProvider.getMindTopicsPrompt] in the language the recording is read in, the one its
 *    name is written in (com.satori.domain.usecase.finish.readingLanguage): a Translate
 *    recording's target when the app has that language, otherwise what [NotesLanguageResolver]
 *    chooses for the text the model reads, as for the other finishing steps.
 * 3. Keeps a topic only if the content words of its label occur in the transcript or the notes
 *    (case-insensitive, German umlauts folded, simple plural and case endings tolerated): the model
 *    must not invent topics. Points are trimmed, at most 20 words, and without repeats
 *    ([MindTopics.kept]). Before that, since 0.7.3, a topic whose label gives back the rules of
 *    the topics prompt ([PromptRules]) goes, and so do the other topics' points that do
 *    ([MindTopics.screened]); [mapLog] counts them.
 * 4. Finds the place of each kept topic with [ThemeMatcher], in the order of the answer, so a
 *    theme one topic makes is there for the next: the topic with the same match key, an existing
 *    theme whose key overlaps the topic's enough, or a new theme with the topic as its first topic.
 * 5. Stores the result, in one transaction ([MindMapRepository.inTransaction]), so the map is never
 *    seen half stored and a failure on the way changes nothing. [MindMapRepository.forgetSession]
 *    comes first, so a recording mapped again replaces its links instead of adding to them; its
 *    nodes stay, so it finds its themes again, with their pins and summaries. Then come the new
 *    nodes ([MindMapRepository.upsertNode]) and one link per topic ([MindMapRepository.link]) with
 *    its points and the segments that hold the most of its label's words
 *    ([MindTopics.segmentIds]); two topics that land on one topic node make one link with the
 *    points of both, without repeats and at most 3. The themes the recording is linked to are
 *    marked stale ([MindMapRepository.markStale]), and [MindMapRepository.cleanup] removes the
 *    nodes no recording holds up any more. A recording in which no topic held up is marked as read
 *    ([MindMapRepository.markReadWithoutTopics]) with the length its transcript had when it was
 *    read, so "Map N older recordings" doesn't read it again while its transcript stays as it
 *    was; one whose answer can't be read is not, since another try may give a readable one.
 *
 * A Malayalam recording is mapped from its notes, which are English like its topics. Since 0.8.0
 * (design R6) its transcript is the one the AI steps read, each Malayalam line as its English
 * reading and none without one ([TranscriptionRepository.getLinesWithReadings]): the topics are
 * checked against the readings and link to the lines they read, and they are written in the
 * language its transcript as it was said gives (D8), English. Without notes and without readings
 * that hold enough to map (none made, or too few of its lines read: [hasEnoughText] of the
 * readings), it is marked as read without topics at once, and the model isn't asked, as one whose
 * transcript is in Malayalam script always was: "Map N older recordings" offers it again once its
 * transcript changes, and the finishing maps it once its lines are read. Notes that hold a
 * Malayalam letter or sign (an older version's, written of the lines as they were said, or ones the
 * owner edited) are not read either: it is mapped from its readings then. A recording without a
 * line the reader reads is mapped as before.
 *
 * Summaries are not written here: [RefreshThemeSummariesUseCase] writes them for the themes this
 * returns, so the finishing step and [MapOlderRecordingsUseCase] each decide how many.
 *
 * Best effort, like the other finishing steps: it never fails the recording. Nothing is stored
 * before the answer is read, so a model that can't be loaded or run, or an answer that can't be
 * read, leaves the recording's links as they were. Only cancellation is thrown. No transcript,
 * label or point text is logged, only counts and timings.
 *
 * @property transcriptionRepository Where the recording, its segments and its notes are read
 * @property mindMapRepository Where the map is read and stored
 * @property llmRepository Runs the model; loaded on demand ([LlmRepository.reloadModel])
 * @property resourceProvider Supplies the topics prompt in the notes language
 * @property notesLanguage Chooses the notes language
 * @property now Clock for the nodes and links
 * @property newId Makes the id of each new node
 * @property mapLog Where the counts of the topics and points left out go
 */
class MapRecordingUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val mindMapRepository: MindMapRepository,
    private val llmRepository: LlmRepository,
    private val resourceProvider: ResourceProvider,
    private val notesLanguage: NotesLanguageResolver,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val mapLog: MindMapLog = MindMapLog.NONE
) {
    companion object {
        // Three topics of a label and three points of 20 words are about 400 tokens; the rest is
        // room for the JSON and the whitespace the grammar allows.
        private const val MAX_TOKENS = 768

        /**
         * Whether [segments] hold enough to be mapped: their complete ones, each with the spaces
         * at its ends removed, have at least [MindMapRepository.MIN_TRANSCRIPT_CHARS] characters,
         * the rule [MindMapRepository.unmappedSessionIds] applies in the database.
         */
        fun hasEnoughText(segments: List<TranscriptionSegment>): Boolean =
            segments.filter { it.isComplete }.sumOf { it.text.trim(' ').length } >= MindMapRepository.MIN_TRANSCRIPT_CHARS

        /**
         * The length of the complete transcript of [segments] as the database counts it for
         * [MindMapRepository.markReadWithoutTopics]: each complete segment with the spaces at its
         * ends removed, in characters as SQLite's LENGTH counts them (one for a letter that
         * takes two UTF-16 units).
         */
        private fun transcriptChars(segments: List<TranscriptionSegment>): Int =
            segments.filter { it.isComplete }.sumOf { segment -> segment.text.trim(' ').let { it.codePointCount(0, it.length) } }

        private fun transcriptOf(segments: List<TranscriptionSegment>): String = segments.joinToString(" ") { it.text.trim() }.trim()
    }

    /** What mapping a recording came to. */
    sealed interface Outcome {
        /** Nothing was read: the recording is gone, or its transcript is too short to map. */
        data object Skipped : Outcome

        /**
         * The recording was read and its links stored: it is linked to [themeIds] now, each once,
         * in the order of its topics. Empty when no topic held up; the recording is then marked
         * as read without topics.
         */
        data class Mapped(val themeIds: List<String>) : Outcome

        /** The model answered, but not with JSON that could be read (cut off, for one); nothing changed. */
        data object Unreadable : Outcome

        /** The model could not be loaded or run; nothing changed, and the next recording would fail too. */
        data object ModelFailed : Outcome

        /**
         * The recording or the map could not be read or written (a database error); the map is as
         * it was, since it is stored in one transaction.
         */
        data object Failed : Outcome
    }

    /**
     * @param sessionId The recording to map
     * @return What happened; throws only [kotlinx.coroutines.CancellationException]
     */
    suspend operator fun invoke(sessionId: String): Outcome {
        return try {
            val session = transcriptionRepository.getSession(sessionId).first() ?: return Outcome.Skipped
            val lines = transcriptionRepository.getLinesWithReadings(sessionId)
            // As the database counts it for "Map N older recordings": the transcript as it was said.
            val said = lines.said.filter { it.isComplete }.sortedBy { it.timestamp }
            if (!hasEnoughText(said)) return Outcome.Skipped
            // What the AI reads: a line only the reader reads as its reading, none without one.
            val segments = lines.readable.filter { it.isComplete }.sortedBy { it.timestamp }
            val transcript = transcriptOf(segments)
            // Notes that hold a Malayalam letter or sign (an older version's of the lines as they
            // were said, or ones the owner edited) are no input for the model, which never reads
            // Malayalam (D7): it reads the readable transcript instead.
            val stored = transcriptionRepository.getInsightsBySession(sessionId).first()
            val notes = stored.takeUnless { ReadableSegments.needsReader(MindTopics.input(session, it, transcript)) }.orEmpty()
            val input = MindTopics.input(session, notes, transcript)
            val spoken = MindTopics.spoken(segments)
            if (ReadableSegments.needsReader(input) || (!MindTopics.readsNotes(session, notes) && !hasEnoughText(segments))) {
                // Nothing the model may read holds enough: a Malayalam transcript without notes it
                // may read (no line of it read into English, or too few readings). It is read without
                // topics, without the model, as one read by it would be; the finishing maps it once
                // its lines are read (design R9).
                return Outcome.Mapped(store(sessionId, emptyList(), spoken, readChars = transcriptChars(said)))
            }
            // Its topics are in the language the transcript as it was said gives (D8).
            val language = notesLanguage.readingLanguage(session, if (lines.readByReader) transcriptOf(said) else input)
            val systemPrompt = resourceProvider.getMindTopicsPrompt(language)
            val answer = llmRepository.answerOrNull(
                text = input,
                systemPrompt = systemPrompt,
                maxTokens = MAX_TOKENS,
                grammar = OutputGrammar.JsonSchema(MindTopics.SCHEMA)
            ) ?: return Outcome.ModelFailed
            val answered = MindTopics.parse(answer) ?: return Outcome.Unreadable
            // A cancel that came as the last token was written must not let the links change.
            currentCoroutineContext().ensureActive()
            // What was said, not the notes the model may read: a point of its words is the speaker's.
            val screened = MindTopics.screened(answered, AnswerChecks(rules = PromptRules.of(systemPrompt), spoken = transcript))
            log(sessionId, MindMapLog.What.TOPICS, screened.droppedTopics)
            log(sessionId, MindMapLog.What.POINTS, screened.droppedPoints)
            val topics = MindTopics.kept(screened.topics, MindTopics.vocabulary(spoken, notes))
            Outcome.Mapped(store(sessionId, topics, spoken, readChars = transcriptChars(said)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed
        }
    }

    /** Tells [mapLog] how many of [what] of the recording [sessionId] were left out, for each reason; nothing for none. */
    private fun log(sessionId: String, what: MindMapLog.What, drops: Map<AnswerDrop, Int>) {
        for (why in AnswerDrop.entries) drops[why]?.let { mapLog.dropped(sessionId, it, what, why) }
    }

    /**
     * Replaces the recording's links with those of [topics] in one transaction (step 5 of the
     * class description) and returns the themes it is linked to now, each once, in the order of
     * its topics.
     *
     * @param readChars The length the recording's complete transcript had when it was read
     *   ([transcriptChars]), for the mark of a recording in which no topic held up
     */
    private suspend fun store(
        sessionId: String,
        topics: List<MindTopics.Topic>,
        spoken: List<MindTopics.Spoken>,
        readChars: Int
    ): List<String> = mindMapRepository.inTransaction {
        val at = now()
        mindMapRepository.forgetSession(sessionId)
        val themeIds = LinkedHashSet<String>()
        if (topics.isEmpty()) {
            mindMapRepository.markReadWithoutTopics(sessionId, readChars)
        } else {
            val matcher = ThemeMatcher(mindMapRepository.allNodesNow())
            // One link per topic node, in the order of the topics; a node two topics land on gets both.
            val links = LinkedHashMap<String, MutableList<MindTopics.Topic>>()
            for (topic in topics) {
                val (node, theme) = place(topic, matcher, at)
                themeIds += theme.id
                links.getOrPut(node.id) { mutableListOf() } += topic
            }
            for ((topicId, linked) in links) {
                mindMapRepository.link(
                    topicId = topicId,
                    sessionId = sessionId,
                    points = MindTopics.points(linked.flatMap { it.points }),
                    segmentIds = MindTopics.segmentIds(MindWords.contentStems(linked.first().label).toSet(), spoken),
                    at = at
                )
            }
            mindMapRepository.markStale(themeIds)
        }
        mindMapRepository.cleanup()
        themeIds.toList()
    }

    /**
     * The topic node [topic] goes onto and its theme, as [matcher] decides; nodes it makes are
     * stored and added to [matcher].
     */
    private suspend fun place(topic: MindTopics.Topic, matcher: ThemeMatcher, at: Long): Pair<MindNode, MindNode> =
        when (val place = matcher.placeOf(topic.key)) {
            is ThemeMatcher.Place.OnTopic -> place.topic to place.theme
            is ThemeMatcher.Place.UnderTheme -> newNode(topic, MindNodeKind.TOPIC, place.theme.id, matcher, at) to place.theme
            ThemeMatcher.Place.NewTheme -> {
                val theme = newNode(topic, MindNodeKind.THEME, parentId = null, matcher, at)
                newNode(topic, MindNodeKind.TOPIC, theme.id, matcher, at) to theme
            }
        }

    private suspend fun newNode(
        topic: MindTopics.Topic,
        kind: MindNodeKind,
        parentId: String?,
        matcher: ThemeMatcher,
        at: Long
    ): MindNode {
        val node = MindNode(
            id = newId(),
            kind = kind,
            label = topic.label,
            matchKey = topic.key,
            parentId = parentId,
            createdAt = at,
            updatedAt = at
        )
        mindMapRepository.upsertNode(node)
        matcher.add(node)
        return node
    }
}
