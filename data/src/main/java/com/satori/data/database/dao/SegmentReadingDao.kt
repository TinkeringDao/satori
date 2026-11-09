package com.satori.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.satori.data.database.entity.SegmentReadingEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity

/** A recording's lines, ordered by timestamp, and the readings of its lines, read in one transaction. Data layer only. */
data class LinesAndReadings(val lines: List<TranscriptionSegmentEntity>, val readings: List<SegmentReadingEntity>)

/**
 * DAO for the readings of transcript lines (segment_readings, see the domain `SegmentReading`). A
 * line's reading goes with the line (its foreign key cascades), so nothing here deletes one.
 */
@Dao
abstract class SegmentReadingDao {

    /** Part of [save]: a reading in place of the line's earlier one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(reading: SegmentReadingEntity)

    /** Part of [save]: whether the line [segmentId] is there, a line of [sessionId]. */
    @Query("SELECT EXISTS(SELECT 1 FROM transcription_segments WHERE id = :segmentId AND session_id = :sessionId)")
    abstract suspend fun isLineOf(segmentId: String, sessionId: String): Boolean

    /**
     * Stores [reading] in place of its line's earlier one, in one transaction, if the line is there
     * and is a line of the reading's recording: a reading of a line that went meanwhile (its
     * transcript replaced), or of another recording's line, is never written.
     *
     * @return Whether it was stored
     */
    @Transaction
    open suspend fun save(reading: SegmentReadingEntity): Boolean {
        if (!isLineOf(reading.segmentId, reading.sessionId)) return false
        upsert(reading)
        return true
    }

    /** Every reading of the lines of [sessionId], in no order. */
    @Query("SELECT * FROM segment_readings WHERE session_id = :sessionId")
    abstract suspend fun readingsOf(sessionId: String): List<SegmentReadingEntity>

    /** Part of [linesAndReadings]: the lines of [sessionId] ordered by timestamp, as TranscriptionSegmentDao gives them. */
    @Query("SELECT * FROM transcription_segments WHERE session_id = :sessionId ORDER BY timestamp ASC")
    abstract suspend fun linesOf(sessionId: String): List<TranscriptionSegmentEntity>

    /**
     * The lines of [sessionId] and their readings, in one transaction: a transcript replaced
     * meanwhile is read old or new, with its own readings, never the one's lines with the other's.
     */
    @Transaction
    open suspend fun linesAndReadings(sessionId: String): LinesAndReadings = LinesAndReadings(linesOf(sessionId), readingsOf(sessionId))
}
