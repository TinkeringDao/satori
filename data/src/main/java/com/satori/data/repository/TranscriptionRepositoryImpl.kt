package com.satori.data.repository

import android.util.Log
import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.SearchDao
import com.satori.data.database.dao.SegmentReadingDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.data.database.mapper.parseStringList
import com.satori.data.database.mapper.serializeStringList
import com.satori.data.database.mapper.toDomain
import com.satori.data.database.mapper.toEntity
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchMatchSource
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.LinesWithReadings
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.transcription.SearchSnippet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Implementation of TranscriptionRepository using Room database.
 *
 * Handles conversion between domain models and Room entities,
 * manages session lifecycle and updates lastModifiedAt timestamps.
 *
 * @property sessionDao DAO for session operations
 * @property segmentDao DAO for segment operations
 * @property insightDao DAO for insight operations
 * @property mindMapDao Deletes recordings, so the mind map is tidied in the same transaction
 * @property audio The audio kept of recordings, deleted with them
 * @property readingDao The English readings of the lines (0.8.0), which go with their lines
 */
class TranscriptionRepositoryImpl @Inject constructor(
    private val sessionDao: TranscriptionSessionDao,
    private val segmentDao: TranscriptionSegmentDao,
    private val insightDao: LlmInsightDao,
    private val searchDao: SearchDao,
    private val mindMapDao: MindMapDao,
    private val audio: RecordingAudioRepository,
    private val readingDao: SegmentReadingDao
) : TranscriptionRepository {

    private companion object {
        const val TAG = "TranscriptionRepository"
    }

    // ========== Session Management ==========

    /**
     * The session is inserted without a category, which is then set through the guarded
     * [TranscriptionSessionDao.updateCategory]. That update and a category's deletion each run
     * as one write, so a category deleted meanwhile leaves the session without one rather than
     * with a dangling id.
     */
    override suspend fun createSession(
        name: String?,
        mode: RecordingMode,
        outputLanguage: String?,
        insightStrategy: InsightStrategy,
        topic: String?,
        categoryId: String?,
        zoneId: String?,
        language: RecordingLanguage
    ): Result<TranscriptionSession> {
        return try {
            val session = newRecording(
                id = UUID.randomUUID().toString(),
                now = System.currentTimeMillis(),
                name = name,
                mode = mode,
                outputLanguage = outputLanguage,
                insightStrategy = insightStrategy,
                topic = topic,
                // The zone the recording is made in (the original's, for a copy), so "tomorrow" in
                // it resolves against the recording's own clock even after a trip or a time zone
                // change.
                zoneId = validZoneId(zoneId) ?: ZoneId.systemDefault().id,
                language = language
            )
            sessionDao.insert(session.toEntity())
            Result.success(session.copy(categoryId = categoryId?.let { label(session.id, it) }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** [zoneId] if it names a time zone this phone knows, else null. */
    private fun validZoneId(zoneId: String?): String? =
        zoneId?.let { runCatching { ZoneId.of(it).id }.getOrNull() }

    /**
     * Records the new session [sessionId] as [categoryId].
     *
     * @return [categoryId] if it was stored, null if that category doesn't exist (any more) or
     *   the write failed: a recording never fails to start over its label
     */
    private suspend fun label(sessionId: String, categoryId: String): String? = try {
        categoryId.takeIf { sessionDao.updateCategory(sessionId, it) == 1 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to store the category of session $sessionId", e)
        null
    }

    /**
     * Room re-runs the list query on every segment or note written, which is every few seconds
     * during a recording; the identical results are dropped here so the list does not redraw.
     */
    override fun getAllSessions(): Flow<List<SessionListItem>> {
        return sessionDao.getAllSessions()
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> {
        return sessionDao.getSession(sessionId)
            .map { it?.toDomain() }
    }

    override fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?> {
        return combine(
            sessionDao.getSession(sessionId),
            segmentDao.getSegmentsBySession(sessionId),
            insightDao.getInsightsBySession(sessionId)
        ) { sessionEntity, segmentEntities, insightEntities ->
            sessionEntity?.let { session ->
                SessionWithDetails(
                    session = session.toDomain(),
                    segments = segmentEntities.map { it.toDomain() },
                    insights = insightEntities.map { it.toDomain() }
                )
            }
        }
    }

    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> {
        return try {
            val trimmedName = newName?.trim()?.takeIf { it.isNotBlank() }
            sessionDao.updateName(sessionId, trimmedName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> {
        return try {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) return Result.success(false)
            Result.success(sessionDao.setNameIfUnnamed(sessionId, trimmedName) > 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> {
        return try {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) return Result.success(false)
            Result.success(sessionDao.replaceName(sessionId, expected, trimmedName) > 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun backfillCandidates(maxFailures: Int): List<String> = sessionDao.backfillCandidates(maxFailures)

    override suspend fun noteRedoDue(sessionId: String, due: Boolean): Result<Unit> {
        return try {
            sessionDao.setRedoDue(sessionId, due)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun keepBeforeRedo(sessionId: String): Result<Boolean> {
        return try {
            Result.success(insightDao.keepBeforeRedo(sessionId, System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun noteBackfillFailed(sessionId: String, failures: Int): Result<Unit> {
        return try {
            sessionDao.countBackfillFailures(sessionId, failures)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> {
        return try {
            val code = language.trim()
            if (code.isEmpty()) return Result.success(false)
            Result.success(sessionDao.setOutputLanguageIfUnset(sessionId, code) > 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> {
        return try {
            sessionDao.updateDuration(sessionId, durationMs)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> {
        return try {
            require(place == null || place.isOnEarth) { "Not a place on the earth" }
            sessionDao.updatePlace(
                sessionId = sessionId,
                latitude = place?.latitude,
                longitude = place?.longitude,
                accuracyMeters = place?.accuracyMeters,
                placeName = place?.name?.trim()?.takeIf { it.isNotEmpty() }
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> {
        return try {
            sessionDao.updateTranscriptModel(sessionId, modelId?.trim()?.takeIf { it.isNotEmpty() })
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setLanguage(sessionId: String, language: RecordingLanguage): Result<Boolean> {
        return try {
            Result.success(sessionDao.updateLanguage(sessionId, language.code) == 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setInputLanguage(sessionId: String, code: String): Result<Unit> {
        return try {
            sessionDao.updateInputLanguage(sessionId, code)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** One guarded statement: [TranscriptionSessionDao.updateCategoryAndMode]. */
    override suspend fun setCategory(sessionId: String, categoryId: String, mode: RecordingMode): Result<Boolean> {
        return try {
            Result.success(sessionDao.updateCategoryAndMode(sessionId, categoryId, mode.name) == 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes through [MindMapDao.deleteSessionAndTidyMap]: the themes that lose the recording are
     * marked stale and the nodes it leaves without links removed, in the delete's transaction.
     * The audio goes after the row: a delete that fails leaves the recording whole, audio
     * included, and the user can try again.
     */
    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        return try {
            mindMapDao.deleteSessionAndTidyMap(sessionId)
            audio.delete(sessionId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Deletes through [MindMapDao.deleteAllSessionsAndMap], the whole mind map with them. */
    override suspend fun deleteAllSessions(): Result<Unit> {
        return try {
            mindMapDao.deleteAllSessionsAndMap()
            audio.deleteAll()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== Segment Management ==========

    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> {
        return try {
            // Save the segment
            segmentDao.insert(segment.toEntity())

            // Update session's lastModifiedAt
            updateSessionModifiedTime(segment.sessionId)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> {
        return try {
            segmentDao.updateText(segmentId, newText.trim())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> {
        return segmentDao.getSegmentsBySession(sessionId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> {
        return try {
            require(segments.all { it.sessionId == sessionId }) { "Segments of another session" }
            segmentDao.replaceForSession(
                sessionId = sessionId,
                segments = segments.map { it.toEntity() },
                insightSources = insightSources.mapValues { (_, ids) -> serializeStringList(ids) },
                todoSources = todoSources.mapValues { (_, ids) -> serializeStringList(ids) },
                modifiedAt = System.currentTimeMillis(),
                linkSources = linkSources.mapValues { (_, ids) -> serializeStringList(ids) }
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> =
        segmentDao.linkSourcesOf(sessionId).associate { row -> row.nodeId to parseStringList(row.segmentIds) }

    // ========== Readings (0.8.0) ==========

    /** The lines and their readings in one transaction ([getLinesWithReadings]), then the domain's rule. */
    override suspend fun getReadableSegments(sessionId: String): List<TranscriptionSegment> =
        getLinesWithReadings(sessionId).readable

    /** The lines and their readings in one transaction ([SegmentReadingDao.linesAndReadings]). */
    override suspend fun getLinesWithReadings(sessionId: String): LinesWithReadings {
        val read = readingDao.linesAndReadings(sessionId)
        return LinesWithReadings(read.lines.map { it.toDomain() }, read.readings.map { it.toDomain() })
    }

    /**
     * One guarded transaction ([SegmentReadingDao.save]). It doesn't stamp the recording as
     * modified: a reading changes nothing the owner sees of it.
     */
    override suspend fun saveReading(reading: SegmentReading): Result<Unit> {
        return try {
            check(readingDao.save(reading.toEntity())) { "No such line of the recording to store a reading of" }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun readingsOf(sessionId: String): List<SegmentReading> =
        readingDao.readingsOf(sessionId).map { it.toDomain() }

    // ========== Insight Management ==========

    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> {
        return try {
            // Save the insight
            insightDao.insert(insight.toEntity())

            // Update session's lastModifiedAt
            updateSessionModifiedTime(insight.sessionId)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun replaceInsights(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Unit> {
        return try {
            require(insights.all { it.sessionId == sessionId }) { "Insights of another session" }
            insightDao.replaceForSession(
                sessionId = sessionId,
                insights = insights.map { it.toEntity() },
                strategy = strategy.name,
                modifiedAt = System.currentTimeMillis()
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** A note that is gone (notes made again replaced it, say) fails: nothing was saved. */
    override suspend fun updateInsight(
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit> {
        return try {
            val changed = insightDao.updateNote(insightId, newTitle?.trim()?.ifBlank { null }, newContent.trim(), newTasks, System.currentTimeMillis())
            if (changed == 0) Result.failure(NoSuchElementException("Note not found: $insightId")) else Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveEditedNote(
        sessionId: String,
        insightId: String,
        newTitle: String?,
        newContent: String,
        newTasks: String?
    ): Result<Unit> {
        return try {
            insightDao.saveEdit(insightId, sessionId, newTitle?.trim()?.ifBlank { null }, newContent.trim(), newTasks, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun replaceInsightsMadeAgain(
        sessionId: String,
        insights: List<LlmInsight>,
        strategy: InsightStrategy
    ): Result<Boolean> {
        return try {
            require(insights.all { it.sessionId == sessionId }) { "Insights of another session" }
            Result.success(
                insightDao.replaceForSessionUnlessEdited(
                    sessionId = sessionId,
                    insights = insights.map { it.toEntity() },
                    strategy = strategy.name,
                    modifiedAt = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> {
        return insightDao.getInsightsBySession(sessionId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    // ========== Search ==========

    /**
     * Every place [query] occurs, one [SearchResult] per line said, note, recording name and name
     * of the place a recording was made, newest recording first (lines of one recording in the
     * order they were said). The caller trims the query; it is matched as typed, ignoring case,
     * with `%` and `_` meaning themselves.
     */
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> {
        val pattern = escapeLike(query)
        val alternate = escapeLike(withFirstLetterFlipped(query))
        return combine(
            searchDao.searchSegments(pattern, alternate),
            searchDao.searchInsights(pattern, alternate),
            searchDao.searchSessionNames(pattern, alternate),
            searchDao.searchPlaces(pattern, alternate)
        ) { segments, insights, sessionNames, places ->
            val results = mutableListOf<SearchResult>()

            segments.forEach { row ->
                results += SearchResult(
                    sessionId = row.sessionId,
                    sessionName = row.sessionName,
                    categoryName = row.categoryName,
                    mode = modeOf(row.mode),
                    createdAt = row.createdAt,
                    snippet = SearchSnippet.of(row.snippetText, query),
                    matchQuery = query,
                    matchSource = SearchMatchSource.TRANSCRIPTION,
                    highlightId = row.id
                )
            }

            insights.forEach { row ->
                results += SearchResult(
                    sessionId = row.sessionId,
                    sessionName = row.sessionName,
                    categoryName = row.categoryName,
                    mode = modeOf(row.mode),
                    createdAt = row.createdAt,
                    snippet = SearchSnippet.of(row.snippetText, query, markdown = true),
                    matchQuery = query,
                    matchSource = SearchMatchSource.INSIGHT,
                    highlightId = row.id
                )
            }

            sessionNames.forEach { row ->
                results += SearchResult(
                    sessionId = row.sessionId,
                    sessionName = row.sessionName,
                    categoryName = row.categoryName,
                    mode = modeOf(row.mode),
                    createdAt = row.createdAt,
                    snippet = row.sessionName ?: "",
                    matchQuery = query,
                    matchSource = SearchMatchSource.SESSION_NAME
                )
            }

            places.forEach { row ->
                results += SearchResult(
                    sessionId = row.sessionId,
                    sessionName = row.sessionName,
                    categoryName = row.categoryName,
                    mode = modeOf(row.mode),
                    createdAt = row.createdAt,
                    snippet = row.placeName,
                    matchQuery = query,
                    matchSource = SearchMatchSource.PLACE
                )
            }

            results.sortedByDescending { it.createdAt }
        }
    }

    private fun modeOf(name: String): RecordingMode =
        runCatching { RecordingMode.valueOf(name) }.getOrDefault(RecordingMode.SIMPLE_LISTENING)

    /** [text] with LIKE's wildcards and the escape character escaped with a backslash. */
    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * [text] with the case of its first letter flipped ("über" becomes "Über"). SQLite's LIKE
     * ignores case only for ASCII letters unless the build has ICU, and German nouns, which start
     * with a capital, are what people look for.
     */
    private fun withFirstLetterFlipped(text: String): String {
        val first = text.firstOrNull() ?: return text
        val flipped = if (first.isLowerCase()) first.uppercaseChar() else first.lowercaseChar()
        return flipped + text.substring(1)
    }

    // ========== Helper Functions ==========

    /**
     * Update the lastModifiedAt timestamp of a session.
     *
     * Called when segments or insights are added to keep the session timestamp current.
     * Writes only last_modified_at: writing back a whole copy read a moment earlier would
     * restore a category deleted meanwhile (category_id has no foreign key to clear it) and
     * undo a rename or duration saved in between.
     */
    private suspend fun updateSessionModifiedTime(sessionId: String) {
        try {
            sessionDao.updateLastModified(sessionId, System.currentTimeMillis())
        } catch (e: Exception) {
            // Log but don't fail the operation if timestamp update fails
            Log.w(TAG, "Failed to update the modified time of session $sessionId", e)
        }
    }
}

/**
 * A recording made at [now] (epoch ms) in [language], as [TranscriptionRepositoryImpl.createSession]
 * stores it: its spoken language not known yet (the finishing stores the one it finds in the
 * transcript), a blank topic as none, and its transcript the live one, by the live model of its
 * language ([RecordingLanguage.liveModel]: AI4Bharat's for Malayalam), until Transcribe again
 * replaces it. The finisher and the lines joined after Stop read a transcript made by any other model
 * as a better one made after Stop.
 */
internal fun newRecording(
    id: String,
    now: Long,
    name: String?,
    mode: RecordingMode,
    outputLanguage: String?,
    insightStrategy: InsightStrategy,
    topic: String?,
    zoneId: String,
    language: RecordingLanguage
): TranscriptionSession = TranscriptionSession(
    id = id,
    name = name,
    createdAt = now,
    lastModifiedAt = now,
    mode = mode,
    inputLanguage = "",
    outputLanguage = outputLanguage,
    insightStrategy = insightStrategy,
    topic = topic?.takeIf { it.isNotBlank() },
    zoneId = zoneId,
    transcriptModel = language.liveModel.id,
    language = language
)
