package com.satori.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a transcription session.
 *
 * Each session represents an isolated recording context with its own
 * transcription segments and LLM insights.
 *
 * Never write this entity back whole to change one field (read, copy, `@Update`): a concurrent
 * change to another column, such as a category deleted meanwhile, would be overwritten. Use the
 * single-column updates in [com.satori.data.database.dao.TranscriptionSessionDao].
 */
@Entity(
    tableName = "transcription_sessions",
    indices = [
        Index(value = ["category_id"])
    ]
)
data class TranscriptionSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_modified_at")
    val lastModifiedAt: Long,

    @ColumnInfo(name = "mode")
    val mode: String = "SIMPLE_LISTENING",

    @ColumnInfo(name = "input_language")
    val inputLanguage: String = "",

    @ColumnInfo(name = "output_language")
    val outputLanguage: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "insight_strategy")
    val insightStrategy: String = "REAL_TIME",

    @ColumnInfo(name = "topic")
    val topic: String? = null,

    /** Time zone id the recording was made in (e.g. "Europe/Berlin"); null before version 7. */
    @ColumnInfo(name = "zone_id")
    val zoneId: String? = null,

    /**
     * The `categories.id` it was recorded as, or null.
     *
     * Deliberately no foreign key: with one, creating or updating a session whose category was
     * just deleted (a quick key still bound to it, say) would fail with a constraint error, and a
     * recording must never fail to start because of its label. ON DELETE SET NULL is done in
     * code instead ([com.satori.data.database.dao.CategoryDao.deleteAndUnlink]), and
     * code reading this must treat an id with no category row as no category.
     */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,

    /**
     * Where the recording started, in degrees (WGS 84), or null when no place is stored; null
     * before version 9. Latitude, longitude, accuracy and name are written together
     * ([com.satori.data.database.dao.TranscriptionSessionDao.updatePlace]), so a place
     * is never half stored.
     */
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    /** How far off the position may be, in metres, or null when the fix gave none. */
    @ColumnInfo(name = "location_accuracy_m")
    val locationAccuracyMeters: Float? = null,

    /**
     * The name of the place it is named after (RecordingPlace.name), or null when none was near
     * or it wasn't looked up.
     */
    @ColumnInfo(name = "place_name")
    val placeName: String? = null,

    /**
     * The id of the speech model that made the transcript (SpeechModelId.id). Null before
     * version 9, when Parakeet v3 was the only speech model.
     */
    @ColumnInfo(name = "transcript_model")
    val transcriptModel: String? = null,

    /**
     * The language the recording is made in (RecordingLanguage.code): null for English or German,
     * the categories' languages, and "ml" for Malayalam. Null before version 11, when every
     * recording was English or German. Not [inputLanguage], the language that was spoken in it.
     */
    @ColumnInfo(name = "language")
    val language: String? = null,

    /**
     * Whether [name] is one the owner typed (since version 13): 1 while it is, set by the recording
     * page's Rename, the one place a name is typed; 0 for a name the app gave (its naming, which
     * never replaces one the owner typed) and for none. A name stored before version 13 is 0: no
     * column recorded who typed it.
     */
    @ColumnInfo(name = "name_by_owner", defaultValue = "0")
    val nameByOwner: Boolean = false,

    /**
     * The failures counted against the backfill of readings of this recording (0.8.0, design R9):
     * its finishing, a hand-off's or one made again over its readings, ended with lines it has still
     * never read, as the reader failed as it loaded or read (one), or as its Reading step was skipped
     * after it began twice (two at once). The backfill leaves a recording alone at two (since
     * version 13).
     */
    @ColumnInfo(name = "backfill_failures", defaultValue = "0")
    val backfillFailures: Int = 0,

    /**
     * Whether the backfill of readings is to make this recording's AI steps again over its readings
     * (0.8.0, design R9; since version 13): 1 for every Malayalam recording of before (the migration
     * to 13 sets it, as an older version's AI model read their lines as they were said or none did),
     * and for one whose finishing ended without its AI steps for want of its readings; 0 once a
     * finishing made again over its readings has written its notes or left them deliberately, and
     * for every other recording. The backfill picks its recordings by it, not by whether their lines
     * were read, which other work does too (the page's, the map's, a debug rerun).
     */
    @ColumnInfo(name = "redo_due", defaultValue = "0")
    val redoDue: Boolean = false
)
