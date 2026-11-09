package com.satori.data.database.mapper

import com.satori.data.database.entity.LlmInsightEntity
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session mappers' place, transcript model and language, both ways: a place is its four
 * columns, and columns without both coordinates are no place; English or German is no language
 * code, and a code this version doesn't know reads as English or German. A line's reading maps
 * both ways too, with a score that is no finite number stored as none.
 */
class TranscriptionMappersTest {

    private val session = TranscriptionSession(id = "s1", name = "Walk", createdAt = 1L, lastModifiedAt = 2L)

    @Test
    fun aPlaceAndATranscriptModelMapBothWays() {
        val placed = session.copy(
            place = RecordingPlace(latitude = 50.9375, longitude = 6.9603, accuracyMeters = 12.5F, name = "Köln"),
            transcriptModel = "cohere-transcribe-14-lang-int8"
        )

        val entity = placed.toEntity()

        assertEquals(
            listOf(50.9375, 6.9603, 12.5F, "Köln", "cohere-transcribe-14-lang-int8"),
            listOf(entity.latitude, entity.longitude, entity.locationAccuracyMeters, entity.placeName, entity.transcriptModel)
        )
        assertEquals(placed, entity.toDomain())
    }

    @Test
    fun aPlaceWithoutAccuracyOrNameKeepsItsCoordinates() {
        val placed = session.copy(place = RecordingPlace(-33.8688, 151.2093, accuracyMeters = null, name = null))

        assertEquals(placed, placed.toEntity().toDomain())
    }

    @Test
    fun aSessionWithoutThemHasEmptyColumns() {
        val entity = session.toEntity()

        assertEquals(
            listOf(null, null, null, null, null),
            listOf(entity.latitude, entity.longitude, entity.locationAccuracyMeters, entity.placeName, entity.transcriptModel)
        )
        assertEquals(session, entity.toDomain())
    }

    @Test
    fun aMalayalamRecordingIsStoredAsMlAndReadBack() {
        val malayalam = session.copy(language = RecordingLanguage.MALAYALAM)

        val entity = malayalam.toEntity()

        assertEquals("ml", entity.language)
        assertEquals(malayalam, entity.toDomain())
    }

    @Test
    fun aNameTheOwnerTypedAndANoteHeChangedMapBothWays() {
        val owned = session.copy(name = "My name", nameByOwner = true)
        assertEquals(true, owned.toEntity().nameByOwner)
        assertEquals(owned, owned.toEntity().toDomain())
        assertEquals(false, session.toEntity().nameByOwner)

        // A note read from its row keeps when the owner saved it, or none (org.json, which writes
        // the row's list of lines, is Android's: the write is the instrumented MigrationTest's).
        val row = LlmInsightEntity(id = "i1", sessionId = "s1", title = null, content = "Milk.", tasks = null, timestamp = 1L, sourceSegmentIds = "[]", editedAt = 5L)
        assertEquals(5L, row.toDomain().editedAt)
        assertNull(row.copy(editedAt = null).toDomain().editedAt)
    }

    @Test
    fun englishOrGermanHasNoLanguageCode() {
        assertNull(session.toEntity().language)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, session.toEntity().toDomain().language)
    }

    @Test
    fun aLanguageCodeThisVersionDoesNotKnowReadsAsEnglishOrGerman() {
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, session.toEntity().copy(language = "ta").toDomain().language)
    }

    @Test
    fun oneCoordinateAloneIsNoPlace() {
        val entity = session.toEntity()

        assertNull(entity.copy(latitude = 50.9375, placeName = "Köln").toDomain().place)
        assertNull(entity.copy(longitude = 6.9603, locationAccuracyMeters = 5F).toDomain().place)
    }

    @Test
    fun aReadingMapsBothWaysAndAScoreThatIsNoNumberIsStoredAsNone() {
        val reading = SegmentReading(
            segmentId = "seg1", sessionId = "s1", sourceSha256 = SegmentReading.sourceHashOf("x"),
            text = "We will meet tomorrow.", model = "indictrans2-indic-en-dist-200m-int8", score = -0.42,
            windowId = null, createdAt = 7L
        )

        assertEquals(reading, reading.toEntity().toDomain())
        assertEquals("w1", reading.copy(windowId = "w1").toEntity().windowId)
        // A model that allowed nothing but the end gives minus infinity; SQLite keeps no NaN.
        assertNull(reading.copy(score = Double.NEGATIVE_INFINITY).toEntity().score)
        assertNull(reading.copy(score = Double.NaN).toEntity().score)
        assertNull(reading.copy(score = null).toEntity().score)
    }
}
