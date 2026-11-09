package com.satori.domain.usecase.finish

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A debug build's rerun of one finishing step on a stored recording (DebugToolsActivity's
 * `rerun`, since 0.7.2), so the main session can see on the phone what the model makes of a real
 * recording again and again, at the default sampling or at another: its notes, its name or its
 * to-dos, asked as the finishing asks them and read, filtered and logged as it reads them, on the
 * model the caller has put in memory. Nothing is stored ([run]) unless the caller asks for it
 * afterwards ([store]). The caller holds the AI work gate and the model; the use cases given here
 * keep their raw answers under the rerun's own tags ([RerunAnswerLog]).
 *
 * - Notes: the whole pass ([GenerateBatchInsightUseCase]) for a recording whose notes the pass
 *   wrote ([InsightStrategy.END_OF_SESSION], as for every recording finished after Stop's
 *   whole-file pass) or whose transcript is longer than a final note reads, otherwise the final
 *   note ([GenerateFinalInsightUseCase]) on the whole transcript; in the language the finishing
 *   writes them in.
 * - Name: one answer of [GenerateSessionTitleUseCase], from the notes (as the finishing's first
 *   answer, or from the transcript when no note has text) or from the transcript (as its second).
 * - To-dos: [ExtractSessionTodosUseCase] with the to-dos known at the finishing, storing nothing.
 * - Reading (0.8.0): every line only the reader reads, read again as the Reading step reads one
 *   ([ReadingStep.readAgain]), D5's rules applied, storing nothing; the lines go back with the run
 *   ([Ran.Readings], [Ran.Readings.answer]) for the rerun's answer files. Its lines are read one by
 *   one, or two at a time, design D1's windows. The reader loads only once the AI model is out: the
 *   step lets it go first.
 *
 * Since 0.8.0 (design R6) the notes, the name and the to-dos read the lines as the finishing reads
 * them: each line only the reader reads as its reading, none without one, which the caller makes
 * first (EnsureReadingsUseCase). With the reader off ([run]'s reader) they read the lines as they
 * were said, as 0.7.3 did, for comparison only: never to be stored.
 */
class FinishingStepRerun(
    private val transcriptionRepository: TranscriptionRepository,
    private val notesLanguage: NotesLanguageResolver,
    private val finalInsight: GenerateFinalInsightUseCase,
    private val batchInsight: GenerateBatchInsightUseCase,
    private val sessionTitle: GenerateSessionTitleUseCase,
    private val sessionTodos: ExtractSessionTodosUseCase,
    private val reading: ReadingStep
) {
    /** A step that can be run again, by the word adb gives for it. */
    enum class Step(val id: String) {
        NOTES("notes"),
        NAME("name"),
        TODOS("todos"),
        READING("reading");

        companion object {
            fun of(id: String?): Step? = entries.firstOrNull { it.id == id?.trim() }
        }
    }

    /** What a name is asked from. */
    enum class From(val id: String) {
        /** The notes, or the transcript when no note has text: the finishing's first answer. */
        NOTES("notes"),

        /** The transcript: the finishing's second answer. */
        TRANSCRIPT("transcript");

        companion object {
            fun of(id: String?): From? = entries.firstOrNull { it.id == id?.trim() }
        }
    }

    /** What one run came to, with its counts for the log ([line]): never a text of the recording's. */
    sealed interface Ran {
        /** The log's counts of the run. */
        fun line(): String

        /** The recording isn't there, or has no line with words. */
        data class Nothing(val why: String) : Ran {
            override fun line() = why
        }

        /** The notes the step wrote: the parts and the merge of a [pass], or the final note; none when the answers had none. */
        data class Notes(val notes: List<LlmInsight>, val pass: Boolean) : Ran {
            override fun line(): String {
                if (notes.isEmpty()) return if (pass) "no note from the pass" else "no note"
                val points = notes.sumOf { pointsOf(it.content) }
                val titled = notes.count { !it.title.isNullOrBlank() }
                val actions = notes.count { it.tasks != null }
                val what = if (pass) "${notes.size} ${plural(notes.size, "note")} of the pass" else "a final note"
                return "$what, $points ${plural(points, "point")}, $titled titled, $actions with action items"
            }
        }

        /**
         * The name's one answer, as the finishing would have read it; a weak one gives way to a
         * note title the rules keep alone there ([GenerateSessionTitleUseCase.Attempt.Titled.weak]).
         */
        data class Name(val attempt: GenerateSessionTitleUseCase.Attempt) : Ran {
            override fun line(): String = when (attempt) {
                is GenerateSessionTitleUseCase.Attempt.Titled ->
                    "a name kept (${attempt.title.length} chars${if (attempt.weak) ", generic but in its subject's words" else ""})"
                is GenerateSessionTitleUseCase.Attempt.Rejected -> "no name, ${attempt.why.name.lowercase().replace('_', ' ')}"
                GenerateSessionTitleUseCase.Attempt.NoInput -> "no name, too few words to ask for one"
                is GenerateSessionTitleUseCase.Attempt.Failed -> "no name, the model failed (${attempt.error.javaClass.simpleName})"
            }
        }

        /**
         * What a reading again came to ([Step.READING]): each line the reader read, with what D5 made
         * of it ([lines], for the answer files only: they hold the line's own text), and how the
         * reading ended. Its log line has counts only, of windows when its lines were read [window]
         * at a time (design D1).
         */
        data class Readings(val lines: List<ReadingStep.LineRead>, val outcome: ReadingStep.Outcome, val window: Int = 1) : Ran {
            override fun line(): String {
                val counts = outcome.counts
                val how = when (outcome) {
                    is ReadingStep.Outcome.Read -> "read"
                    is ReadingStep.Outcome.Stopped -> "stopped for a capture"
                    is ReadingStep.Outcome.NotLoaded -> "not loaded (${outcome.why.name.lowercase().replace('_', ' ')})"
                    is ReadingStep.Outcome.LoadStopped -> "stopped for want of memory"
                    is ReadingStep.Outcome.Failed -> "failed (${outcome.cause.javaClass.simpleName})"
                }
                val read = if (window > 1) plural(counts.lines, "window") + " of $window lines" else plural(counts.lines, "line")
                return "${counts.lines} $read $how in ${counts.ms} ms, ${counts.kept} kept, ${counts.empty} empty " +
                    "(${counts.fragments} fragments, ${counts.floor} under the floor, ${counts.band} outside the band, " +
                    "${counts.repeats} repeating, ${counts.blank} blank, ${counts.notRead} not read)"
            }

            /**
             * The run's answer file (V3), never a log: it holds what was said. A header, then one
             * tab-separated row a line read, in order: its number from 1, its id, what D5 made of it
             * (`kept`, `fragment`, `floor`, `band`, `repeats`, `blank`, `not_read`), its source pieces
             * and English tokens, its mean log-probability, whether D15's cap cut it, the line as it was
             * said, and the reader's English, also one D5 emptied, so it can be rated (none for a
             * fragment or a line not read). A window of lines read together has the ids of its lines,
             * by commas, and their texts one after the other. A tab or a line break inside a text reads
             * as a space.
             */
            fun answer(): String = buildString {
                append(ANSWER_COLUMNS).append('\n')
                lines.forEachIndexed { index, read ->
                    val output = read.output
                    val row = listOf(
                        "${index + 1}",
                        read.window.joinToString(",") { it.id },
                        read.verdict.name.lowercase(),
                        output?.pieces?.toString().orEmpty(),
                        output?.tokens?.toString().orEmpty(),
                        output?.meanLogProb?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty(),
                        output?.capped?.toString().orEmpty(),
                        oneLine(read.window.joinToString(" ") { it.text.trim() }),
                        oneLine(output?.text.orEmpty())
                    )
                    append(row.joinToString("\t")).append('\n')
                }
            }
        }

        /** What the search for to-dos and ideas came to. */
        data class Todos(val outcome: ExtractSessionTodosUseCase.Outcome) : Ran {
            override fun line(): String = when (val found = outcome) {
                is ExtractSessionTodosUseCase.Outcome.Found ->
                    "${found.count} found, ${found.ideas} ideas, ${found.suggested} suggested, ${found.overCap} over the suggestions' cap"
                ExtractSessionTodosUseCase.Outcome.Skipped -> "skipped: the AI is off or nothing was said"
                ExtractSessionTodosUseCase.Outcome.Failed -> "the search failed"
            }
        }
    }

    /**
     * Runs [step] of [sessionId] once, storing nothing: what the finishing would make now. A
     * failure of the model is passed on, as the step's use cases pass it on.
     *
     * @param from What a name is asked from; only for [Step.NAME]
     * @param stores For [Step.TODOS] only: the search stores what it finds, as Find to-dos and ideas
     *   again does, for the last of a rerun's runs asked to store
     * @param reader For the notes, the name and the to-dos (0.8.0): whether the model reads the
     *   lines as the finishing's AI steps read them, each line only the reader reads as its stored
     *   reading and none without one (the default, design R6), or false the lines as they were said,
     *   Malayalam and all, as 0.7.3 read them: the baseline to compare with (V4). Its language is the
     *   transcript's as it was said either way (D8)
     * @param window For [Step.READING] only: the lines read together, 1 (each alone, as the
     *   finishing reads them) or 2 (design D1's windows, which V3 compares)
     */
    suspend fun run(sessionId: String, step: Step, from: From = From.NOTES, stores: Boolean = false, reader: Boolean = true, window: Int = 1): Ran {
        val session = transcriptionRepository.getSession(sessionId).first() ?: return Ran.Nothing("no such recording")
        val lines = transcriptionRepository.getLinesWithReadings(sessionId)
        val said = lines.said.filter { it.isComplete && it.text.isNotBlank() }.sortedBy { it.timestamp }
        // What the AI reads: as the finishing reads it, or with the reader off the lines as they were said.
        val segments = if (reader) lines.readable.filter { it.isComplete && it.text.isNotBlank() }.sortedBy { it.timestamp } else said
        if (said.isEmpty()) return Ran.Nothing("no line with words")
        if (segments.isEmpty() && step != Step.READING) return Ran.Nothing("no line read")
        val transcript = segments.joinToString(" ") { it.text.trim() }.trim()
        // The language the AI writes in is the transcript's as it was said (D8), the reader on or off.
        val spoken = said.joinToString(" ") { it.text.trim() }.trim()
        return when (step) {
            Step.NOTES -> notes(session, segments, transcript, spoken)
            Step.NAME -> {
                val insights = transcriptionRepository.getInsightsBySession(sessionId).first()
                val language = notesLanguage.readingLanguage(session, spoken)
                Ran.Name(
                    when (from) {
                        From.NOTES -> sessionTitle.attempt(segments, insights, language, answer = 0)
                        From.TRANSCRIPT -> sessionTitle.attempt(segments, emptyList(), language, answer = 1, about = insights)
                    }
                )
            }
            Step.TODOS -> Ran.Todos(sessionTodos(sessionId, store = stores, readings = reader))
            Step.READING -> {
                val read = mutableListOf<ReadingStep.LineRead>()
                val windowed = window.coerceAtLeast(1)
                val outcome = reading.readAgain(sessionId, onLine = { read += it }, window = windowed)
                Ran.Readings(read, outcome, windowed)
            }
        }
    }

    private suspend fun notes(session: TranscriptionSession, segments: List<TranscriptionSegment>, transcript: String, spoken: String): Ran.Notes {
        val language = if (session.mode == RecordingMode.REAL_TIME_TRANSLATION) session.outputLanguage else notesLanguage.resolve(session.id, spoken)
        val pass = session.insightStrategy == InsightStrategy.END_OF_SESSION ||
            transcript.length > GenerateFinalInsightUseCase.maxUserPromptChars(transcript)
        if (!pass) {
            val note = finalInsight(transcript, session.id, session.mode, language, session.topic, segments.map { it.id }).getOrThrow()
            return Ran.Notes(listOfNotNull(note), pass = false)
        }
        val parts = mutableListOf<LlmInsight>()
        val summary = batchInsight(segments, session.id, session.mode, language, session.topic, onChunkInsight = { parts += it }).getOrThrow()
        return Ran.Notes(parts + listOfNotNull(summary), pass = true)
    }

    /**
     * Stores what [ran] made for [sessionId], when it made something: notes in place of all the
     * recording has, as Rewrite notes stores them, and a name only into a recording without one,
     * so a name the owner typed stays. To-dos are stored by their run itself ([run]'s stores).
     *
     * @return Whether something was stored
     */
    suspend fun store(sessionId: String, ran: Ran): Boolean = withContext(NonCancellable) {
        when (ran) {
            is Ran.Notes -> ran.notes.isNotEmpty() &&
                transcriptionRepository.replaceInsights(sessionId, ran.notes, InsightStrategy.END_OF_SESSION).isSuccess
            is Ran.Name -> (ran.attempt as? GenerateSessionTitleUseCase.Attempt.Titled)?.let {
                transcriptionRepository.nameSessionIfUnnamed(sessionId, it.title).getOrDefault(false)
            } ?: false
            is Ran.Todos, is Ran.Readings, is Ran.Nothing -> false
        }
    }

    private companion object {
        /** The header of a reading's answer file ([Ran.Readings.answer]). */
        const val ANSWER_COLUMNS = "line\tsegment\tverdict\tpieces\ttokens\tmean_log_prob\tcapped\tsaid\treading"

        fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"

        /** [text] on one line of a tab-separated file: each tab, line break or carriage return a space. */
        fun oneLine(text: String): String = text.map { if (it == '\t' || it == '\n' || it == '\r') ' ' else it }.joinToString("")

        /** A note's points: its lines of a Markdown list, or 1 for a summary or prose, 0 for none. */
        fun pointsOf(content: String): Int {
            val listed = content.lines().count { it.trimStart().startsWith("- ") }
            return if (listed > 0) listed else if (content.isBlank()) 0 else 1
        }
    }
}

/**
 * The raw answers of a rerun ([FinishingStepRerun]), kept beside the finishing's own under the
 * rerun's tags: `rerun-<step>-<run>-<the call's own tag>` ("rerun-notes-2-final-1", "rerun-name-3-0",
 * "rerun-todos-1-0" for a to-do part), so a rerun never overwrites nor clears what the finishing
 * kept. Its first run clears the step's earlier reruns ([begin]). Set [run] before each run.
 *
 * @param step The step's word, "notes", "name", "todos" or "reading"
 */
class RerunAnswerLog(private val kept: AnswerLog, private val step: String) : AnswerLog {

    /** The run the answers belong to, from 1. */
    @Volatile var run: Int = 1

    /** Forgets the answers of this step's earlier reruns of [sessionId]. */
    fun begin(sessionId: String) = kept.clear(sessionId, "rerun-$step")

    override fun todoAnswer(sessionId: String, window: Int, answer: String) = kept.answer(sessionId, "rerun-$step-$run-$window", answer)

    override fun answer(sessionId: String, call: String, answer: String) =
        kept.answer(sessionId, "rerun-$step-$run-" + call.substringAfter('-', call), answer)

    // The step's own use cases clear the finishing's tags: never for a rerun.
    override fun clear(sessionId: String, calls: String) = Unit

    override fun forget(sessionId: String) = kept.forget(sessionId)
}
