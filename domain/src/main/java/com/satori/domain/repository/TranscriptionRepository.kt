package com.satori.domain.repository

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.LinesWithReadings
import com.satori.domain.reading.ReadableSegments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Repository interface for transcription session persistence.
 *
 * Manages storage and retrieval of transcription sessions along with their
 * associated segments and LLM insights. Each session represents an isolated
 * recording context.
 */
interface TranscriptionRepository {

    // ========== Session Management ==========

    /**
     * Create a new transcription session.
     *
     * Every session is created for a live recording, so it starts with the live model of its
     * [language] as its transcript's model ([RecordingLanguage.liveModel],
     * [TranscriptionSession.transcriptModel]): Parakeet v3 for English or German; a switch of the
     * language while recording and Transcribe again change it with [setTranscriptModel].
     *
     * @param name Optional user-provided name for the session
     * @param mode Recording mode determining insight frequency and format
     * @param outputLanguage Optional target language for translation mode
     * @param insightStrategy Whether insights are generated in real-time or at end of session
     * @param topic Optional main subject/topic for focused AI insights
     * @param categoryId The [com.satori.domain.model.RecordingCategory] to record it as,
     *   or null for none. An id with no category (deleted meanwhile) is stored as none, and the
     *   session is still created.
     * @param zoneId The time zone it is recorded in (e.g. "Europe/Berlin"), or null for the
     *   phone's current one. A copy of a recording passes the original's zone, so times said in
     *   it resolve the same way. An id that isn't a valid zone counts as null.
     * @param language The language it is recorded in; stored with the row, in the same insert
     * @return Result containing the created session (with the category and zone as stored) or
     *   an error
     */
    suspend fun createSession(
        name: String?,
        mode: RecordingMode = RecordingMode.SIMPLE_LISTENING,
        outputLanguage: String? = null,
        insightStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
        topic: String? = null,
        categoryId: String? = null,
        zoneId: String? = null,
        language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN
    ): Result<TranscriptionSession>

    /**
     * Get all transcription sessions ordered by creation time (most recent first), each with
     * what the recordings list needs to know about it: its category's name and whether a
     * transcript line or a note was kept.
     *
     * @return Flow emitting list of all sessions
     */
    fun getAllSessions(): Flow<List<SessionListItem>>

    /**
     * Get a specific session by ID.
     *
     * @param sessionId The session ID to retrieve
     * @return Flow emitting the session or null if not found
     */
    fun getSession(sessionId: String): Flow<TranscriptionSession?>

    /**
     * Get a session with all its associated segments and insights.
     *
     * @param sessionId The session ID to retrieve
     * @return Flow emitting session details or null if not found
     */
    fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?>

    /**
     * Delete a session and all its associated data (segments and insights).
     *
     * Uses CASCADE delete to ensure data consistency. Its mind-map links go the same way, and in
     * the same transaction the themes that lose it are marked stale and the nodes left without
     * links removed, as [MindMapRepository] describes. The audio kept of it
     * ([RecordingAudioRepository]) is deleted after the session, also on the ways that delete a
     * recording without asking: one in which nothing was said, deleted as it ends.
     *
     * @param sessionId The session ID to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteSession(sessionId: String): Result<Unit>

    /**
     * Rename a session: the owner's name, typed on its page, which is stored as his
     * ([TranscriptionSession.nameByOwner], since 0.8.0-dev3), so the app's own naming never replaces
     * it ([replaceSessionName]).
     *
     * Passing null or blank name clears the name (session becomes unnamed), and with it the mark.
     *
     * @param sessionId The session ID to rename
     * @param newName The new name, or null to clear
     * @return Result indicating success or failure
     */
    suspend fun renameSession(sessionId: String, newName: String?): Result<Unit>

    /**
     * Name a session only if it has no name yet, in one atomic step, so a name the user types
     * meanwhile is never overwritten.
     *
     * @param sessionId The session ID to name
     * @param name The name to set; trimmed before it is stored
     * @return Result with true if the name was set, false if the session already had one (or
     *         doesn't exist)
     */
    suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean>

    /**
     * Rename a session only while its name is still [expected] and the app's, in one atomic step, so
     * a name the user types meanwhile, or typed before ([TranscriptionSession.nameByOwner], since
     * 0.8.0-dev3), is never overwritten: Transcribe again in another language replaces the name the
     * old transcript gave a recording once the new one is written, not before, and so do the AI
     * steps made again over a recording's readings (design R9).
     *
     * @param sessionId The session ID to rename
     * @param expected The name it must still have, as it is stored
     * @param name The new name; trimmed before it is stored, and a blank one changes nothing
     * @return Result with true if the name was replaced, false if the session has another name by
     *         now, one the owner typed (or doesn't exist)
     */
    suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean>

    /**
     * The Malayalam recordings the backfill of readings is to make again (0.8.0, design R9): those
     * made in Malayalam ([TranscriptionSession.language]) or whose transcript the finishing found
     * Malayalam ([TranscriptionSession.inputLanguage] "ml"), whose redo is due ([noteRedoDue]: every
     * Malayalam recording of before version 13, and one finished without its AI steps for want of
     * its readings), without a finishing job, and with fewer than [maxFailures] failures counted
     * against them ([noteBackfillFailed]); oldest first. Whether they have lines the reader reads is
     * the caller's to tell. The default has none.
     *
     * @throws Exception when the database can't be read
     */
    suspend fun backfillCandidates(maxFailures: Int): List<String> = emptyList()

    /**
     * Whether the backfill of readings is to make [sessionId]'s AI steps again over its readings
     * ([backfillCandidates]): [due] once its finishing ended without them for want of its readings,
     * not once a finishing made again has written its notes or left them deliberately. The default
     * stores nothing.
     */
    suspend fun noteRedoDue(sessionId: String, due: Boolean): Result<Unit> = Result.success(Unit)

    /**
     * Keeps [sessionId]'s name and notes as they are now, before a finishing made again over its
     * readings replaces any of them (design R9): once, the first time, never written over, so a name
     * or notes the owner set before version 13 recorded it can be given back. The default keeps
     * nothing.
     *
     * @return whether they were kept now; false when they were kept before, or the recording is gone
     */
    suspend fun keepBeforeRedo(sessionId: String): Result<Boolean> = Result.success(false)

    /**
     * A finishing of [sessionId] ended with lines it has still never read: the reader failed, or the
     * Reading step was skipped after it began twice. [failures] more count against the recording's
     * backfill of readings ([backfillCandidates]' maxFailures). The default stores nothing.
     */
    suspend fun noteBackfillFailed(sessionId: String, failures: Int = 1): Result<Unit> = Result.success(Unit)

    /**
     * Store the language the AI writes this session's notes in, only if none is stored yet, in
     * one atomic step, so the first confident result stays whatever calls follow.
     *
     * @param sessionId The session ID
     * @param language "en" or "de"
     * @return Result with true if the language was set, false if the session already had one (or
     *         doesn't exist)
     */
    suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean>

    /**
     * Persist the total recording duration for a session.
     *
     * Called once when the recording stops, before the session is closed.
     *
     * @param sessionId The session ID to update
     * @param durationMs Total recording duration in milliseconds
     * @return Result indicating success or failure
     */
    suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit>

    /**
     * Store where a recording was made, or with null remove it. Its coordinates, accuracy and
     * name are written in one step, so a place is never half stored. The place is part of the
     * session's row and goes when the recording is deleted.
     *
     * A session that doesn't exist (any more) is left alone and the result is still a success: a
     * fix may come in after its recording was discarded.
     *
     * @param sessionId The session ID
     * @param place The place, or null to remove it; a blank name is stored as none
     * @return Result indicating success, or a failure when [place] is not on the earth
     *   ([RecordingPlace.isOnEarth]) or couldn't be stored; the session keeps what it had then
     */
    suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit>

    /**
     * Store which speech model made the transcript the session has now, after Transcribe again
     * has put it in place. Null forgets it, which reads as Parakeet v3
     * ([com.satori.domain.model.SpeechModelId.ofTranscript]).
     *
     * A session that doesn't exist (any more) is left alone and the result is still a success.
     *
     * @param sessionId The session ID
     * @param modelId A [com.satori.domain.model.SpeechModelId.id], or null; blank is
     *   stored as null
     * @return Result indicating success or failure
     */
    suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit>

    /**
     * Store what a recording is recorded as: the category [categoryId] and the [mode] it records
     * in, together in one statement, so a recording is never filed under one category while its
     * notes are written in another's mode. Whatever writes its notes next reads the mode: the live
     * notes, the finishing after Stop, a rewrite. A Translate recording keeps what it is, and none
     * becomes one: a translation's notes are its translation. Nor is a category stored that
     * doesn't exist or doesn't record in [mode] (it was deleted or edited meanwhile).
     *
     * The default stores nothing, for a repository that keeps no recordings of its own.
     *
     * @return true when it was stored; false, with nothing changed, when the recording or the
     *   category doesn't exist (any more), the category doesn't record in [mode], or one of them
     *   translates; a failure when it couldn't be stored
     */
    suspend fun setCategory(sessionId: String, categoryId: String, mode: RecordingMode): Result<Boolean> =
        Result.success(false)

    /**
     * Store the language a recording is in, as a switch while it records or on its page chose it.
     * Whatever reads it next goes by it: the speech model of the next stretch of speech, the
     * finishing after Stop, a rewrite of its notes. Which recordings may switch, and to what, is
     * the caller's rule; this stores what it is given.
     *
     * The default stores nothing, for a repository that keeps no recordings of its own.
     *
     * @return true when it was stored (also when the recording had that language already); false,
     *   with nothing changed, when the recording doesn't exist (any more); a failure when it
     *   couldn't be stored
     */
    suspend fun setLanguage(sessionId: String, language: RecordingLanguage): Result<Boolean> =
        Result.success(false)

    /**
     * Store the language that was spoken in a recording ([TranscriptionSession.inputLanguage]), as
     * the finishing finds it in the transcript: "en", "de" or "ml". One column, so it never undoes
     * another change meanwhile. The default stores nothing, for a repository that keeps no
     * recordings of its own.
     */
    suspend fun setInputLanguage(sessionId: String, code: String): Result<Unit> = Result.success(Unit)

    /**
     * Delete all sessions and their associated data, the whole mind map and the audio kept of them included.
     *
     * WARNING: This is irreversible.
     *
     * @return Result indicating success or failure
     */
    suspend fun deleteAllSessions(): Result<Unit>

    // ========== Segment Management ==========

    /**
     * Save a transcription segment to the database.
     *
     * Updates the session's lastModifiedAt timestamp.
     *
     * @param segment The segment to save
     * @return Result indicating success or failure
     */
    suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit>

    /**
     * Update the text of an existing transcription segment.
     *
     * @param segmentId The segment ID to update
     * @param newText Replacement text
     * @return Result indicating success or failure
     */
    suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit>

    /**
     * Get all segments for a specific session ordered by timestamp.
     *
     * @param sessionId The session ID
     * @return Flow emitting list of segments
     */
    fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>>

    /**
     * Replace the whole transcript of a session with [segments], in one transaction: afterwards
     * the session has exactly the new lines, and if anything fails (the session was deleted
     * meanwhile, say) it still has exactly the old ones. Its notes, name, to-dos and mind-map links
     * stay; in the same transaction the notes in [insightSources], the to-dos in [todoSources] and
     * the links in [linkSources] are pointed at the new lines they came from. Used by Transcribe
     * again and by the joining of lines after Stop.
     *
     * @param segments The new lines; each must have [TranscriptionSegment.sessionId] equal to
     *   [sessionId], otherwise nothing is changed and the result is a failure
     * @param insightSources New source line ids by note id; notes of the session not in it keep theirs
     * @param todoSources New source line ids by to-do id; to-dos of the session not in it keep theirs
     * @param linkSources New source line ids by mind-map node id, for the session's links to those
     *   nodes; links not in it keep theirs
     * @return Result indicating success or failure
     */
    suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>> = emptyMap(),
        todoSources: Map<String, List<String>> = emptyMap(),
        linkSources: Map<String, List<String>> = emptyMap()
    ): Result<Unit>

    /**
     * The source line ids of the session's mind-map links, by node id, as stored now: what
     * [replaceSegments] is to point at the new lines. Empty for a session without links.
     *
     * @throws Exception when the database can't be read
     */
    suspend fun getLinkSources(sessionId: String): Map<String, List<String>>

    // ========== Readings (0.8.0) ==========

    /**
     * The session's lines as the AI steps read them, ordered by timestamp ([ReadableSegments.of]):
     * each line the reader reads with its current reading's text, left out when it has none or an
     * empty one; every other line as it is. Complete lines and the others, as
     * [getSegmentsBySession] gives them. Read in one go, so a transcript replaced meanwhile is
     * read old or new, never mixed.
     *
     * The default reads the lines and [readingsOf] one after the other.
     *
     * @throws Exception when the database can't be read
     */
    suspend fun getReadableSegments(sessionId: String): List<TranscriptionSegment> =
        ReadableSegments.of(getSegmentsBySession(sessionId).first(), readingsOf(sessionId))

    /**
     * The session's lines as they were said, ordered by timestamp, with every reading of them
     * ([readingsOf]), read in one go: what the AI steps read ([LinesWithReadings.readable], as
     * [getReadableSegments] gives it) and the transcript as it was said, which the language they
     * write in comes from (D8), of one and the same transcript. Complete lines and the others, as
     * [getSegmentsBySession] gives them.
     *
     * The default reads the lines and [readingsOf] one after the other.
     *
     * @throws Exception when the database can't be read
     */
    suspend fun getLinesWithReadings(sessionId: String): LinesWithReadings =
        LinesWithReadings(getSegmentsBySession(sessionId).first(), readingsOf(sessionId))

    /**
     * Stores [reading] in place of any reading its line had. A line that is gone (its transcript was
     * replaced or its recording deleted meanwhile), or one of another recording than
     * [SegmentReading.sessionId], gets none.
     *
     * The default stores nothing, for a repository that keeps no recordings of its own.
     *
     * @return success once it is stored; a failure when the line is gone or the write failed
     */
    suspend fun saveReading(reading: SegmentReading): Result<Unit> = Result.success(Unit)

    /**
     * Every reading of the session's lines, of their texts now or of earlier ones (a line edited
     * since it was read); in no order. Empty for a session without readings, and by default.
     *
     * @throws Exception when the database can't be read
     */
    suspend fun readingsOf(sessionId: String): List<SegmentReading> = emptyList()

    // ========== Insight Management ==========

    /**
     * Save an LLM insight to the database.
     *
     * Updates the session's lastModifiedAt timestamp.
     *
     * @param insight The insight to save
     * @return Result indicating success or failure
     */
    suspend fun saveInsight(insight: LlmInsight): Result<Unit>

    /**
     * Replace every insight of a session with [insights], in one transaction: afterwards the
     * session has exactly the new ones, and if anything fails it still has exactly the old ones.
     *
     * The session is also marked with [strategy]. Notes written in one pass over the whole
     * transcript are [InsightStrategy.END_OF_SESSION] notes whatever the recording started as,
     * and the details screen pins the summary of those.
     *
     * @param insights The new insights; each must have [LlmInsight.sessionId] equal to
     *   [sessionId], otherwise nothing is changed and the result is a failure
     * @return Result indicating success or failure
     */
    suspend fun replaceInsights(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Unit>

    /**
     * Correct an existing insight by hand: set its title, content and tasks together. They are
     * written as one change, so if it fails the insight is exactly as it was.
     *
     * @param insightId The insight ID to update
     * @param newTitle Replacement title text, or null to clear
     * @param newContent Replacement content text
     * @param newTasks Replacement tasks JSON string, or null to clear
     * @return Result indicating success or failure
     */
    suspend fun updateInsight(
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit>

    /**
     * The owner's edit of the note [insightId] of [sessionId] in the note editor ([updateInsight]),
     * marked his ([LlmInsight.editedAt]). When the note is gone meanwhile (notes made again over the
     * recording's readings replaced it while the editor was open, design R9), what he wrote is kept
     * as a note of the recording of its own, marked his: his text is never dropped while the editor
     * says it saved. The default is [updateInsight].
     */
    suspend fun saveEditedNote(
        sessionId: String,
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit> = updateInsight(insightId, newTitle, newContent, newTasks)

    /**
     * [replaceInsights] for notes made again over a recording's readings (design R9), unless a note
     * of the recording was saved in the note editor ([LlmInsight.editedAt]), looked for in the same
     * write: the notes are the owner's then, and stay all as they are. The default looks first and
     * then writes, without one transaction.
     *
     * @return false when the notes were his, and nothing was written
     */
    suspend fun replaceInsightsMadeAgain(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Boolean> {
        if (getInsightsBySession(sessionId).first().any { it.editedAt != null }) return Result.success(false)
        return replaceInsights(sessionId, insights, strategy).map { true }
    }

    /**
     * Get all insights for a specific session ordered by timestamp.
     *
     * @param sessionId The session ID
     * @return Flow emitting list of insights
     */
    fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>>

    // ========== Search ==========

    /**
     * Text search across transcription segments, LLM insights, session names, and the names of
     * the places sessions were recorded ([TranscriptionSession.place]).
     *
     * Returns results sorted by session creation time (newest first).
     * A session may appear multiple times if the query matches in more than one source; the
     * caller groups them (see SearchGroups).
     *
     * @param query The search string, already trimmed (at least 2 characters).
     * @return Flow emitting a list of [SearchResult] ordered by [SearchResult.createdAt] DESC.
     */
    fun searchTranscriptions(query: String): Flow<List<SearchResult>>
}
