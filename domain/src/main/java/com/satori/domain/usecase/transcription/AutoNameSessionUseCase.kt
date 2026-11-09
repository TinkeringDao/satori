package com.satori.domain.usecase.transcription

import com.satori.domain.model.LlmInsight
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.SessionTitles
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.flow.first

/**
 * Names a recording that has no name after what was said in it, in this order:
 * 1. The model's title from the recording's notes, or from its transcript when no note has text
 *    ([GenerateSessionTitleUseCase]).
 * 2. When that answer is no name (it repeats its prompt, names only the kind of text or holds
 *    nothing), and the recording holds enough words for notes
 *    ([GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH]), the model once more, from the transcript:
 *    a second answer of a sampling model, read from what was said rather than from notes that may
 *    have gone astray. On the model in memory, in the same step: no load, no other model.
 * 3. The first note title that names something, with its note as its subject: one the rules keep
 *    alone before one only its note's words keep ([SessionTitles.Verdict.Kept.bySubject]). Never one
 *    in another script than the Latin one, one of the line on a Malayalam recording's language, nor
 *    one that gives back the rules of the notes prompt ([GenerateSessionTitleUseCase.storedTitleDrop]:
 *    "Plain statements", "Goal: Provide"), as a note an earlier version stored may have (0.7.3); the
 *    field log counts those it passed over, when the note titles were looked at.
 * 4. None: the screens show the default name (the category's or the kind's), and the field log
 *    says so. The default is never stored, as a stored name is the owner's: the recording would
 *    never be named again, after Transcribe again say.
 *
 * A weak name of the model, one only the words of what it names keep ([GenerateSessionTitleUseCase.Attempt.Titled.weak]:
 * "Meeting Minutes" for a meeting whose notes say "minutes"), gives way to a note title the rules
 * keep alone ("Q3 Budget Review"), with the line of a name of the kind of text; with none it is the
 * name. The review of 0.7.2 found an ordinary meeting named "Meeting Minutes" where 0.7.1 took
 * its note's title.
 *
 * A model that fails is not asked again: a note title stands in, or the failure is returned. A
 * recording that has a name keeps it, including a name typed while this runs. Each way it ends is
 * one line of [notesLog] (counts and ids only), and the answers go to [answerLog] (`name-0`,
 * `name-1`), the ones of an earlier naming first forgotten. A recording whose name its text made
 * again replaces keeps that name when no new one is found, and its line says so.
 *
 * On 2026-10-17 the owner's Malayalam note about a voice-notes app got no name, and no line said
 * why: its note had no title left, and the one answer, made of the subject's own words ("notes",
 * "minutes"), was taken for a name of the kind of text.
 *
 * Since 0.8.0 the transcript it names a recording after is the one the AI steps read
 * ([TranscriptionRepository.getReadableSegments], design R6): a line only the reader reads (a
 * Malayalam line) as its English reading, and left out without one, so the model never reads
 * Malayalam, with the name's prompt of an English recording (D6), nor a note that holds a Malayalam
 * letter or sign (one an older version wrote of the lines as they were said, or one the owner
 * edited), which is left out as if the recording hadn't it. [languageCode] is the caller's, found in
 * the transcript as it was said (D8).
 *
 * @property transcriptionRepository Reads the recording and stores the name.
 * @property generateSessionTitle Asks the model for a title.
 * @property notesLog Where the field log hears where the name came from, or that there is none
 * @property answerLog Where the model's answers are kept, if anywhere
 */
class AutoNameSessionUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val generateSessionTitle: GenerateSessionTitleUseCase,
    private val notesLog: NotesLog = NotesLog.NONE,
    private val answerLog: AnswerLog = AnswerLog.NONE
) {
    /**
     * @param sessionId The recording to name.
     * @param languageCode "en" or "de": the language the title is written in.
     * @param notes The notes to name it after, in place of those it has; none names it after its
     *   transcript. Null reads its notes. Its text made again after a transcript in another language
     *   ([com.satori.domain.usecase.stt.MakeTextAgainUseCase]) is never named after the
     *   notes the old transcript gave it, which stay when the new ones can't be written.
     * @param replacing The name the old transcript gave the recording, which its text made again
     *   replaces ([com.satori.domain.usecase.stt.MakeTextAgainUseCase]): a recording that
     *   still has it gets the new title in its place, in one write that leaves a name typed
     *   meanwhile alone ([TranscriptionRepository.replaceSessionName]). It stays when no title can
     *   be found. Null names only a recording without a name.
     * @return The name given; null when the recording already had a name (another than
     *         [replacing]) or there was nothing to name it after; a failure when the model failed
     *         and no insight title could stand in.
     */
    suspend operator fun invoke(
        sessionId: String,
        languageCode: String,
        notes: List<LlmInsight>? = null,
        replacing: String? = null
    ): Result<String?> {
        val session = transcriptionRepository.getSession(sessionId).first() ?: return Result.success(null)
        val current = session.name?.takeIf { it.isNotBlank() }
        if (current != null && current != replacing) return Result.success(null)

        // As the AI steps read them: a line only the reader reads as its reading, none without one.
        val segments = transcriptionRepository.getReadableSegments(sessionId)
        // Nor a note in Malayalam letters (an older version's, or one the owner edited): with none
        // left, the name comes from the readings.
        val insights = (notes ?: transcriptionRepository.getInsightsBySession(sessionId).first()).filterNot(::holdsMalayalam)

        answerLog.clear(sessionId, GenerateSessionTitleUseCase.CALL)
        val found = title(session, segments, insights, languageCode)
        val title = found.title ?: run {
            // The name its text made again replaces stays; else the screens show the default.
            val oldNameStays = current != null
            when {
                found.failure != null -> notesLog.log(NotesLog.NameFailed(sessionId, found.failure.javaClass.simpleName, oldNameStays))
                found.answers > 0 -> notesLog.log(NotesLog.NoName(sessionId, found.answers, oldNameStays))
            }
            return found.failure?.let { Result.failure(it) } ?: Result.success(null)
        }

        val stored = if (current == null) {
            transcriptionRepository.nameSessionIfUnnamed(sessionId, title)
        } else {
            transcriptionRepository.replaceSessionName(sessionId, current, title)
        }
        return stored.map { named ->
            if (named) notesLog.log(NotesLog.Named(sessionId, found.source, found.answers))
            if (named) title else null
        }
    }

    /**
     * What a name was found from: its [title] and [source] after [answers] answers of the model,
     * or none, and the model's [failure] if it failed.
     */
    private class Found(
        val title: String?,
        val source: NotesLog.NameSource,
        val answers: Int,
        val failure: Throwable? = null
    )

    /** Steps 1 to 3 of the class's description. */
    private suspend fun title(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        insights: List<LlmInsight>,
        languageCode: String
    ): Found {
        val sessionId = session.id
        val fromNotes = insights.any { it.content.isNotBlank() }
        var answers = 0
        var attempt = generateSessionTitle.attempt(segments, insights, languageCode, answer = 0)
        if (attempt != GenerateSessionTitleUseCase.Attempt.NoInput) answers++
        var source = if (fromNotes) NotesLog.NameSource.NOTES else NotesLog.NameSource.TRANSCRIPT
        if (attempt is GenerateSessionTitleUseCase.Attempt.Rejected && wordsIn(segments) >= MIN_WORDS_FOR_SECOND_ANSWER) {
            notesLog.log(NotesLog.NameAgain(sessionId))
            attempt = generateSessionTitle.attempt(segments, emptyList(), languageCode, answer = 1, about = insights)
            answers++
            source = NotesLog.NameSource.TRANSCRIPT
        }
        val titled = attempt as? GenerateSessionTitleUseCase.Attempt.Titled
        // A name the rules keep alone is the name: the notes' titles are not looked at.
        if (titled != null && !titled.weak) return Found(titled.title, source, answers)
        val candidates = insights.sortedBy { it.timestamp }
            .mapNotNull { SessionTitles.verdict(it.title, subject = it.content) as? SessionTitles.Verdict.Kept }
        val checked = candidates.map { it to generateSessionTitle.storedTitleDrop(it.title, segments, languageCode, session.mode) }
        val noteTitles = checked.filter { (_, drop) -> drop == null }.map { (title, _) -> title }
        // Counted for the field log when the titles are looked at: no title's text, only how many and why.
        for (why in AnswerDrop.entries) {
            val count = checked.count { (_, drop) -> drop == why }
            if (count > 0) notesLog.log(NotesLog.Dropped(sessionId, count, NotesLog.Piece.TITLES_FOR_THE_NAME, why))
        }
        val specific = noteTitles.firstOrNull { !it.bySubject }?.title
        if (titled != null) {
            if (specific == null) return Found(titled.title, source, answers)
            // A name only the subject's words keep gives way to a note title the rules keep alone.
            notesLog.log(NotesLog.GenericNames(sessionId, 1))
            return Found(specific, NotesLog.NameSource.NOTE_TITLE, answers)
        }
        val noteTitle = specific ?: noteTitles.firstOrNull()?.title
        val failure = (attempt as? GenerateSessionTitleUseCase.Attempt.Failed)?.error
        return Found(noteTitle, NotesLog.NameSource.NOTE_TITLE, answers, failure.takeIf { noteTitle == null })
    }

    /** Whether a part of [note] the title's prompt reads holds a Malayalam letter or sign, which the model never reads (D7). */
    private fun holdsMalayalam(note: LlmInsight): Boolean =
        listOfNotNull(note.title, note.content, note.tasks).any(ReadableSegments::needsReader)

    /** The words the complete segments hold, as the notes' bar counts them. */
    private fun wordsIn(segments: List<TranscriptionSegment>): Int =
        segments.filter { it.isComplete }.sumOf { segment -> segment.text.split(WHITESPACE).count { it.isNotEmpty() } }

    private companion object {
        /** A recording with as many words as notes are written for is asked for its name twice. */
        const val MIN_WORDS_FOR_SECOND_ANSWER = GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH

        val WHITESPACE = Regex("\\s+")
    }
}
