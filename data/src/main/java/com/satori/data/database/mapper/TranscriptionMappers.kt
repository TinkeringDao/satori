package com.satori.data.database.mapper

import com.satori.data.database.dao.SessionListRow
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.SegmentReadingEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import org.json.JSONArray

/**
 * Mappers for converting between Room entities and domain models.
 *
 * These extension functions enable clean separation between the data layer
 * (Room entities) and the domain layer (business models).
 */

// ========== Session Mappers ==========

/**
 * Convert Room entity to domain model.
 */
fun TranscriptionSessionEntity.toDomain(): TranscriptionSession {
    return TranscriptionSession(
        id = id,
        name = name,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
        mode = try {
            RecordingMode.valueOf(mode)
        } catch (e: IllegalArgumentException) {
            RecordingMode.SIMPLE_LISTENING // Safe fallback
        },
        inputLanguage = inputLanguage,
        outputLanguage = outputLanguage,
        durationMs = durationMs,
        insightStrategy = try {
            InsightStrategy.valueOf(insightStrategy)
        } catch (e: IllegalArgumentException) {
            InsightStrategy.REAL_TIME // Safe fallback for legacy rows
        },
        topic = topic,
        zoneId = zoneId,
        categoryId = categoryId,
        place = placeOf(latitude, longitude, locationAccuracyMeters, placeName),
        transcriptModel = transcriptModel,
        language = RecordingLanguage.fromCode(language),
        nameByOwner = nameByOwner
    )
}

/**
 * The place stored in a session's columns, or null without both coordinates: they are written
 * together, so one alone is no place.
 */
private fun placeOf(latitude: Double?, longitude: Double?, accuracyMeters: Float?, name: String?): RecordingPlace? =
    if (latitude == null || longitude == null) null else RecordingPlace(latitude, longitude, accuracyMeters, name)

/**
 * Convert domain model to Room entity.
 */
fun TranscriptionSession.toEntity(): TranscriptionSessionEntity {
    return TranscriptionSessionEntity(
        id = id,
        name = name,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
        mode = mode.name,
        inputLanguage = inputLanguage,
        outputLanguage = outputLanguage,
        durationMs = durationMs,
        insightStrategy = insightStrategy.name,
        topic = topic,
        zoneId = zoneId,
        categoryId = categoryId,
        latitude = place?.latitude,
        longitude = place?.longitude,
        locationAccuracyMeters = place?.accuracyMeters,
        placeName = place?.name,
        transcriptModel = transcriptModel,
        language = language.code,
        nameByOwner = nameByOwner
    )
}

/**
 * Convert a row of the recordings list to its domain model.
 */
fun SessionListRow.toDomain(): SessionListItem {
    return SessionListItem(
        session = session.toDomain(),
        categoryName = categoryName,
        hasTranscript = hasTranscript,
        hasNotes = hasNotes
    )
}

// ========== Segment Mappers ==========

/**
 * Convert Room entity to domain model.
 */
fun TranscriptionSegmentEntity.toDomain(): TranscriptionSegment {
    return TranscriptionSegment(
        id = id,
        sessionId = sessionId,
        text = text,
        timestamp = timestamp,
        isComplete = isComplete
    )
}

/**
 * Convert domain model to Room entity.
 */
fun TranscriptionSegment.toEntity(): TranscriptionSegmentEntity {
    return TranscriptionSegmentEntity(
        id = id,
        sessionId = sessionId,
        text = text,
        timestamp = timestamp,
        isComplete = isComplete
    )
}

// ========== Reading Mappers ==========

/** Convert a stored reading to the domain's. */
fun SegmentReadingEntity.toDomain(): SegmentReading = SegmentReading(
    segmentId = segmentId,
    sessionId = sessionId,
    sourceSha256 = sourceSha256,
    text = text,
    model = model,
    score = score,
    windowId = windowId,
    createdAt = createdAt
)

/**
 * Convert a reading to the row stored. A score that is no finite number (a model that allowed only
 * the end) is stored as none: SQLite keeps no NaN, and a mean of minus infinity says nothing more.
 */
fun SegmentReading.toEntity(): SegmentReadingEntity = SegmentReadingEntity(
    segmentId = segmentId,
    sessionId = sessionId,
    sourceSha256 = sourceSha256,
    text = text,
    model = model,
    score = score?.takeIf { it.isFinite() },
    windowId = windowId,
    createdAt = createdAt
)

// ========== Insight Mappers ==========

/**
 * Convert Room entity to domain model.
 *
 * Parses the JSON string of source segment IDs back to a list.
 */
fun LlmInsightEntity.toDomain(): LlmInsight {
    return LlmInsight(
        id = id,
        sessionId = sessionId,
        title = title,
        content = content,
        tasks = tasks,
        timestamp = timestamp,
        sourceSegmentIds = parseStringList(sourceSegmentIds),
        editedAt = editedAt
    )
}

/**
 * Convert domain model to Room entity.
 *
 * Serializes the list of source segment IDs to a JSON string.
 */
fun LlmInsight.toEntity(): LlmInsightEntity {
    return LlmInsightEntity(
        id = id,
        sessionId = sessionId,
        title = title,
        content = content,
        tasks = tasks,
        timestamp = timestamp,
        sourceSegmentIds = serializeStringList(sourceSegmentIds),
        editedAt = editedAt
    )
}

// ========== Helper Functions ==========

/**
 * Serialize a list of strings (segment IDs, a mind-map link's points) to a JSON array string.
 *
 * Example: ["id1","id2","id3"]. TodoDao.observeForSession matches segment ids as `"id"` inside
 * this text, so keep each item a quoted JSON string.
 */
internal fun serializeStringList(items: List<String>): String {
    val jsonArray = JSONArray()
    items.forEach { jsonArray.put(it) }
    return jsonArray.toString()
}

/**
 * Parse a JSON array string written by [serializeStringList] back to its list.
 *
 * Returns empty list if parsing fails.
 */
internal fun parseStringList(json: String): List<String> {
    return try {
        val jsonArray = JSONArray(json)
        List(jsonArray.length()) { index ->
            jsonArray.getString(index)
        }
    } catch (e: Exception) {
        emptyList()
    }
}
