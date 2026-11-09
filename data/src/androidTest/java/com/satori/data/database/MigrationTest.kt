package com.satori.data.database

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.dao.FinishingJobDao
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindNodeEntity
import com.satori.data.database.entity.SegmentReadingEntity
import com.satori.data.database.entity.TodoEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [AppDatabase.MIGRATION_6_7], [AppDatabase.MIGRATION_7_8], [AppDatabase.MIGRATION_8_9],
 * [AppDatabase.MIGRATION_9_10], [AppDatabase.MIGRATION_10_11], [AppDatabase.MIGRATION_11_12] and
 * [AppDatabase.MIGRATION_12_13] on databases built from schemas/6.json to 12.json with rows in every
 * table.
 * MigrationTestHelper checks each result against the exported schema of its version (tables,
 * columns, indices and foreign keys); the tests check that the rows survive, that the new tables
 * and columns are there and work, and that the app's own Room build, with
 * [AppDatabase.ALL_MIGRATIONS], opens files of each version.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    // ========== 6 -> 7 ==========

    @Test
    fun migrate6To7KeepsEveryRow() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)

        assertEquals(
            SESSIONS.map { it + listOf(null, null) },
            db.rows("SELECT ${SESSION_COLUMNS.joinToString()}, zone_id, category_id FROM transcription_sessions ORDER BY id")
        )
        assertEquals(SEGMENTS, db.rows("SELECT ${SEGMENT_COLUMNS.joinToString()} FROM transcription_segments ORDER BY id"))
        assertEquals(INSIGHTS, db.rows("SELECT ${INSIGHT_COLUMNS.joinToString()} FROM llm_insights ORDER BY id"))
    }

    @Test
    fun migrate6To7AddsTheNewTablesAndColumns() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)

        assertEquals(SESSION_COLUMNS + listOf("zone_id", "category_id"), db.columns("transcription_sessions"))
        assertEquals(TODO_COLUMNS, db.columns("todos"))
        assertEquals(CATEGORY_COLUMNS, db.columns("categories"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM todos"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM categories"))
        assertTrue(
            db.indices().toString(),
            db.indices().containsAll(
                listOf(
                    "index_transcription_sessions_category_id",
                    "index_todos_status",
                    "index_todos_due_at",
                    "index_todos_remind_at",
                    "index_todos_session_id"
                )
            )
        )
    }

    @Test
    fun aTodoInAMigratedDatabaseOutlivesItsRecording() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)
        // As Room's open helper does for a schema with foreign keys
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO todos (id, session_id, text, kind, status, certainty, capture, due_has_time, " +
                "remind_at, reminder_shown_at, source_segment_ids, created_at, updated_at) " +
                "VALUES ('t1', 's1', 'Call the bank', 'TASK', 'OPEN', 'CONFIRMED', 'EXPLICIT', 0, " +
                "1760090000000, NULL, '[\"s1-seg1\"]', 1760000500000, 1760000500000)"
        )

        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's1'")

        assertEquals(listOf(listOf<Any?>("t1", null)), db.rows("SELECT id, session_id FROM todos"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM transcription_segments WHERE session_id = 's1'"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM llm_insights WHERE session_id = 's1'"))
    }

    // ========== 7 -> 8 ==========

    @Test
    fun migrate7To8KeepsEveryRow() {
        helper.createDatabase(DB, 7).use { insertVersion7Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8)

        assertVersion7RowsKept(db)
    }

    @Test
    fun migrate7To8AddsTheMindMapTablesEmpty() {
        helper.createDatabase(DB, 7).use { insertVersion7Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8)

        assertEquals(
            listOf(
                "id", "kind", "label", "match_key", "parent_id", "summary", "summary_updated_at",
                "summary_stale", "pinned", "created_at", "updated_at"
            ),
            db.columns("mind_nodes")
        )
        assertEquals(listOf("node_id", "session_id", "points", "segment_ids", "created_at"), db.columns("mind_links"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_nodes"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_links"))
        assertTrue(
            db.indices().toString(),
            db.indices().containsAll(
                listOf("index_mind_nodes_parent_id", "index_mind_nodes_match_key", "index_mind_links_session_id")
            )
        )
    }

    @Test
    fun theMindMapTablesOfAMigratedDatabaseDefaultAndCascade() {
        helper.createDatabase(DB, 7).use { insertVersion7Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8)
        db.execSQL("PRAGMA foreign_keys = ON")
        // Without summary_stale and pinned, which default to 0
        db.execSQL(
            "INSERT INTO mind_nodes (id, kind, label, match_key, created_at, updated_at) " +
                "VALUES ('home', 'THEME', 'Home', 'home', 1, 1)"
        )
        db.execSQL(
            "INSERT INTO mind_nodes (id, kind, label, match_key, parent_id, created_at, updated_at) " +
                "VALUES ('heating', 'TOPIC', 'Heating', 'heating', 'home', 1, 1)"
        )
        db.execSQL(
            "INSERT INTO mind_links (node_id, session_id, points, segment_ids, created_at) " +
                "VALUES ('heating', 's1', '[\"Fixed this month\"]', '[\"s1-seg1\"]', 2), " +
                "('heating', 's2', '[]', '[]', 2)"
        )

        assertEquals(listOf(listOf<Any?>(0L, 0L)), db.rows("SELECT summary_stale, pinned FROM mind_nodes WHERE id = 'home'"))

        // A deleted recording takes its links along
        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's1'")
        assertEquals(listOf(listOf<Any?>("heating", "s2")), db.rows("SELECT node_id, session_id FROM mind_links"))

        // A deleted theme takes its topics along, and they their links
        db.execSQL("DELETE FROM mind_nodes WHERE id = 'home'")
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_nodes"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_links"))
    }

    // ========== 6 -> 8 ==========

    @Test
    fun migrate6To8RunsBothMigrations() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)

        assertEquals(
            SESSIONS.map { it + listOf(null, null) },
            db.rows("SELECT ${SESSION_COLUMNS.joinToString()}, zone_id, category_id FROM transcription_sessions ORDER BY id")
        )
        assertEquals(SEGMENTS, db.rows("SELECT ${SEGMENT_COLUMNS.joinToString()} FROM transcription_segments ORDER BY id"))
        assertEquals(INSIGHTS, db.rows("SELECT ${INSIGHT_COLUMNS.joinToString()} FROM llm_insights ORDER BY id"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_nodes"))
    }

    // ========== 8 -> 9 ==========

    @Test
    fun migrate8To9KeepsEveryRow() {
        helper.createDatabase(DB, 8).use { insertVersion8Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)

        assertVersion7RowsKept(db)
        assertEquals(MIND_NODES, db.rows("SELECT ${MIND_NODE_COLUMNS.joinToString()} FROM mind_nodes ORDER BY id"))
        assertEquals(MIND_LINKS, db.rows("SELECT ${MIND_LINK_COLUMNS.joinToString()} FROM mind_links ORDER BY node_id"))
    }

    @Test
    fun migrate8To9AddsThePlaceAndTheTranscriptModelEmpty() {
        helper.createDatabase(DB, 8).use { insertVersion8Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)

        assertEquals(SESSION_COLUMNS_7 + PLACE_AND_MODEL_COLUMNS, db.columns("transcription_sessions"))
        assertEquals(
            SESSIONS_7.map { List<Any?>(PLACE_AND_MODEL_COLUMNS.size) { null } },
            db.rows("SELECT ${PLACE_AND_MODEL_COLUMNS.joinToString()} FROM transcription_sessions ORDER BY id")
        )
    }

    @Test
    fun thePlaceAndTheModelOfAMigratedDatabaseKeepTheirTypesAndGoWithTheRecording() {
        helper.createDatabase(DB, 8).use { insertVersion8Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "UPDATE transcription_sessions SET latitude = 50.9375, longitude = 6.9603, " +
                "location_accuracy_m = 12.5, place_name = 'Köln', " +
                "transcript_model = 'cohere-transcribe-14-lang-int8' WHERE id = 's1'"
        )

        assertEquals(
            listOf(listOf<Any?>(50.9375, 6.9603, 12.5, "Köln", "cohere-transcribe-14-lang-int8")),
            db.rows("SELECT ${PLACE_AND_MODEL_COLUMNS.joinToString()} FROM transcription_sessions WHERE id = 's1'")
        )

        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's1'")
        assertEquals(
            listOf(listOf<Any?>(0L)),
            db.rows("SELECT COUNT(*) FROM transcription_sessions WHERE place_name IS NOT NULL OR latitude IS NOT NULL")
        )
    }

    // ========== 6 -> 9 ==========

    @Test
    fun migrate6To9RunsEveryMigration() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }

        val db = helper.runMigrationsAndValidate(
            DB, 9, true, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9
        )

        assertEquals(
            SESSIONS.map { it + List<Any?>(2 + PLACE_AND_MODEL_COLUMNS.size) { null } },
            db.rows(
                "SELECT ${(SESSION_COLUMNS_7 + PLACE_AND_MODEL_COLUMNS).joinToString()} " +
                    "FROM transcription_sessions ORDER BY id"
            )
        )
        assertEquals(SEGMENTS, db.rows("SELECT ${SEGMENT_COLUMNS.joinToString()} FROM transcription_segments ORDER BY id"))
        assertEquals(INSIGHTS, db.rows("SELECT ${INSIGHT_COLUMNS.joinToString()} FROM llm_insights ORDER BY id"))
    }

    // ========== 9 -> 10 ==========

    @Test
    fun migrate9To10KeepsEveryRow() {
        helper.createDatabase(DB, 9).use { insertVersion9Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10)

        assertVersion7RowsKept(db)
        assertEquals(MIND_NODES, db.rows("SELECT ${MIND_NODE_COLUMNS.joinToString()} FROM mind_nodes ORDER BY id"))
        assertEquals(MIND_LINKS, db.rows("SELECT ${MIND_LINK_COLUMNS.joinToString()} FROM mind_links ORDER BY node_id"))
        assertEquals(
            listOf(listOf<Any?>("s1", TRANSCRIPT_MODEL_9), listOf<Any?>("s2", null), listOf<Any?>("s3", null)),
            db.rows("SELECT id, transcript_model FROM transcription_sessions ORDER BY id")
        )
    }

    @Test
    fun migrate9To10AddsTheFinishingQueueAndTheMapSkipsEmpty() {
        helper.createDatabase(DB, 9).use { insertVersion9Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10)

        assertEquals(FINISHING_JOB_COLUMNS, db.columns("finishing_jobs"))
        assertEquals(listOf("session_id", "chars", "read_at"), db.columns("mind_map_skips"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM finishing_jobs"))
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM mind_map_skips"))
        assertTrue(db.indices().toString(), db.indices().contains("index_finishing_jobs_state_queued_at"))
    }

    @Test
    fun theNewTablesOfAMigratedDatabaseGoWithTheirRecording() {
        helper.createDatabase(DB, 9).use { insertVersion9Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO finishing_jobs (${FINISHING_JOB_COLUMNS.joinToString()}) VALUES " +
                "('s1', 'QUEUED', 'Notes', 1, 'process', 0, 0, 0, 1760000700000, 1760000600000), " +
                "('s2', 'CAPTURING', NULL, 0, 'process', 0, 0, 0, NULL, 1760100000000)"
        )
        db.execSQL("INSERT INTO mind_map_skips (session_id, chars, read_at) VALUES ('s1', 120, 1760000800000), ('s2', 9, 1760100100000)")

        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's1'")

        assertEquals(listOf(listOf<Any?>("s2")), db.rows("SELECT session_id FROM finishing_jobs"))
        assertEquals(listOf(listOf<Any?>("s2", 9L)), db.rows("SELECT session_id, chars FROM mind_map_skips"))
    }

    // ========== 10 -> 11 ==========

    @Test
    fun migrate10To11KeepsEveryRow() {
        helper.createDatabase(DB, 10).use { insertVersion10Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 11, true, AppDatabase.MIGRATION_10_11)

        assertVersion7RowsKept(db)
        assertEquals(MIND_NODES, db.rows("SELECT ${MIND_NODE_COLUMNS.joinToString()} FROM mind_nodes ORDER BY id"))
        assertEquals(MIND_LINKS, db.rows("SELECT ${MIND_LINK_COLUMNS.joinToString()} FROM mind_links ORDER BY node_id"))
        assertEquals(
            listOf(listOf<Any?>("s1", TRANSCRIPT_MODEL_9), listOf<Any?>("s2", null), listOf<Any?>("s3", null)),
            db.rows("SELECT id, transcript_model FROM transcription_sessions ORDER BY id")
        )
        assertEquals(FINISHING_JOBS_10, db.rows("SELECT ${FINISHING_JOB_COLUMNS.joinToString()} FROM finishing_jobs ORDER BY session_id"))
        assertEquals(MAP_SKIPS_10, db.rows("SELECT session_id, chars, read_at FROM mind_map_skips ORDER BY session_id"))
    }

    @Test
    fun migrate10To11AddsTheLanguageEmpty() {
        helper.createDatabase(DB, 10).use { insertVersion10Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 11, true, AppDatabase.MIGRATION_10_11)

        assertEquals(SESSION_COLUMNS_7 + PLACE_AND_MODEL_COLUMNS + "language", db.columns("transcription_sessions"))
        // Every recording made before is English or German, which NULL stands for.
        assertEquals(SESSIONS_7.map { listOf<Any?>(null) }, db.rows("SELECT language FROM transcription_sessions ORDER BY id"))
    }

    @Test
    fun theLanguageOfAMigratedDatabaseIsTextAndGoesWithTheRecording() {
        helper.createDatabase(DB, 10).use { insertVersion10Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 11, true, AppDatabase.MIGRATION_10_11)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("UPDATE transcription_sessions SET language = 'ml' WHERE id = 's2'")

        assertEquals(
            listOf(listOf<Any?>("s1", null), listOf<Any?>("s2", "ml"), listOf<Any?>("s3", null)),
            db.rows("SELECT id, language FROM transcription_sessions ORDER BY id")
        )

        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's2'")
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM transcription_sessions WHERE language IS NOT NULL"))
    }

    // ========== 11 -> 12 ==========

    @Test
    fun migrate11To12KeepsEveryRow() {
        helper.createDatabase(DB, 11).use { insertVersion11Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)

        assertVersion7RowsKept(db)
        assertEquals(MIND_NODES, db.rows("SELECT ${MIND_NODE_COLUMNS.joinToString()} FROM mind_nodes ORDER BY id"))
        assertEquals(MIND_LINKS, db.rows("SELECT ${MIND_LINK_COLUMNS.joinToString()} FROM mind_links ORDER BY node_id"))
        assertEquals(FINISHING_JOBS_10, db.rows("SELECT ${FINISHING_JOB_COLUMNS.joinToString()} FROM finishing_jobs ORDER BY session_id"))
        assertEquals(MAP_SKIPS_10, db.rows("SELECT session_id, chars, read_at FROM mind_map_skips ORDER BY session_id"))
        assertEquals(
            listOf(listOf<Any?>("s1", null), listOf<Any?>("s2", "ml"), listOf<Any?>("s3", null)),
            db.rows("SELECT id, language FROM transcription_sessions ORDER BY id")
        )
    }

    @Test
    fun migrate11To12AddsTheReadingsEmpty() {
        helper.createDatabase(DB, 11).use { insertVersion11Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)

        assertEquals(READING_COLUMNS, db.columns("segment_readings"))
        // No line was read before: the Reading step reads the Malayalam ones when it comes to them.
        assertEquals(listOf(listOf<Any?>(0L)), db.rows("SELECT COUNT(*) FROM segment_readings"))
        assertTrue(db.indices().toString(), db.indices().contains("index_segment_readings_session_id"))
    }

    @Test
    fun aReadingOfAMigratedDatabaseGoesWithItsLineAndWithItsRecording() {
        helper.createDatabase(DB, 11).use { insertVersion11Rows(it) }
        val db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)
        db.execSQL("PRAGMA foreign_keys = ON")
        READINGS_12.forEach { db.insertRow("segment_readings", READING_COLUMNS, it) }
        // A score kept as a REAL, a reading of no score as NULL.
        assertEquals(
            listOf(listOf<Any?>("s1-seg1", -0.38), listOf<Any?>("s1-seg2", null), listOf<Any?>("s2-seg1", -0.5)),
            db.rows("SELECT segment_id, score FROM segment_readings ORDER BY segment_id")
        )

        // A line replaced (Transcribe again, the joining after Stop) takes its reading with it.
        db.execSQL("DELETE FROM transcription_segments WHERE id = 's1-seg1'")
        // A recording deleted takes its lines, and their readings with them.
        db.execSQL("DELETE FROM transcription_sessions WHERE id = 's2'")

        assertEquals(listOf(listOf<Any?>("s1-seg2")), db.rows("SELECT segment_id FROM segment_readings"))
        // A reading of no line is refused.
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertRow("segment_readings", READING_COLUMNS, listOf("gone", "s1", "00", "Gone.", "reader", null, null, 1L))
        }
    }

    // ========== 12 -> 13 ==========

    @Test
    fun migrate12To13KeepsEveryRow() {
        helper.createDatabase(DB, 12).use { insertVersion12Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)

        assertVersion7RowsKept(db)
        assertEquals(MIND_LINKS, db.rows("SELECT ${MIND_LINK_COLUMNS.joinToString()} FROM mind_links ORDER BY node_id"))
        assertEquals(FINISHING_JOBS_10, db.rows("SELECT ${FINISHING_JOB_COLUMNS.joinToString()} FROM finishing_jobs ORDER BY session_id"))
        assertEquals(READINGS_12, db.rows("SELECT ${READING_COLUMNS.joinToString()} FROM segment_readings ORDER BY segment_id"))
    }

    @Test
    fun migrate12To13CountsEveryNameAndNoteOfBeforeAsTheAppsAndNoJobARedo() {
        helper.createDatabase(DB, 12).use { insertVersion12Rows(it) }

        val db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)

        assertEquals(
            SESSION_COLUMNS_7 + PLACE_AND_MODEL_COLUMNS + listOf("language", "name_by_owner", "backfill_failures", "redo_due"),
            db.columns("transcription_sessions")
        )
        assertEquals(INSIGHT_COLUMNS + "edited_at", db.columns("llm_insights"))
        assertEquals(FINISHING_JOB_COLUMNS + "redo", db.columns("finishing_jobs"))
        // Before version 13 nothing recorded who typed a name or changed a note.
        assertEquals(SESSIONS_7.map { listOf<Any?>(0L, 0L) }, db.rows("SELECT name_by_owner, backfill_failures FROM transcription_sessions ORDER BY id"))
        assertEquals(INSIGHTS.map { listOf<Any?>(null) }, db.rows("SELECT edited_at FROM llm_insights ORDER BY id"))
        assertEquals(FINISHING_JOBS_10.map { listOf<Any?>(0L) }, db.rows("SELECT redo FROM finishing_jobs ORDER BY session_id"))
    }

    @Test
    fun migrate12To13MakesEveryMalayalamRecordingOfBeforeDueToBeMadeAgainAndAddsTheKeepsEmpty() {
        helper.createDatabase(DB, 12).use { db ->
            insertVersion12Rows(db)
            // s3's transcript was found Malayalam, s2 is made in it; s1 is neither.
            db.execSQL("UPDATE transcription_sessions SET input_language = 'ml' WHERE id = 's3'")
        }

        val db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)

        assertEquals(listOf(listOf<Any?>("s1", 0L), listOf<Any?>("s2", 1L), listOf<Any?>("s3", 1L)), db.rows("SELECT id, redo_due FROM transcription_sessions ORDER BY id"))
        assertEquals(listOf("session_id", "name", "name_by_owner", "insight_strategy", "kept_at"), db.columns("kept_before_redo"))
        assertEquals(INSIGHT_COLUMNS + "edited_at", db.columns("notes_kept_before_redo"))
        assertTrue(db.rows("SELECT * FROM kept_before_redo").isEmpty())
        assertTrue(db.rows("SELECT * FROM notes_kept_before_redo").isEmpty())
        assertTrue(db.indices().contains("index_notes_kept_before_redo_session_id"))
    }

    @Test
    fun theAppOpensAVersion12DatabaseAndTellsTheOwnersEditsFromTheAppsOwnOutput() {
        helper.createDatabase(DB, 12).use { insertVersion12Rows(it) }

        withApp { app ->
            val sessions = app.transcriptionSessionDao()
            assertEquals(SESSIONS_7.size, sessions.getSessionCount())

            // The app's names, then the owner's, which the app's naming no longer replaces.
            assertEquals(1, sessions.setNameIfUnnamed("s2", "Groceries"))
            assertEquals(1, sessions.replaceName("s2", expected = "Groceries", name = "Grocery run"))
            assertFalse(sessions.getSession("s2").first()!!.nameByOwner)
            sessions.updateName("s2", "My own name")
            assertTrue(sessions.getSession("s2").first()!!.nameByOwner)
            assertEquals(0, sessions.replaceName("s2", expected = "My own name", name = "Something else"))
            assertEquals("My own name", sessions.getSession("s2").first()!!.name)
            // A cleared name is no one's, and the app may name it again.
            sessions.updateName("s2", null)
            assertFalse(sessions.getSession("s2").first()!!.nameByOwner)
            assertEquals(1, sessions.setNameIfUnnamed("s2", "Named again"))
            assertFalse(sessions.getSession("s2").first()!!.nameByOwner)

            // A note saved in the editor keeps when.
            val note = app.llmInsightDao().getInsightsBySession("s1").first().first()
            assertNull(note.editedAt)
            app.llmInsightDao().updateNote(note.id, "Title", "Content", null, editedAt = 1760300000000L)
            assertEquals(1760300000000L, app.llmInsightDao().getInsightsBySession("s1").first().first { it.id == note.id }.editedAt)

            // The backfill's: s2 is made in Malayalam and due to be made again since the migration; s1
            // is found Malayalam since and made due by its finishing, but waits to be finished; s3 is
            // German. One failure leaves s2 in, two take it out, and so does one counted as two.
            app.openHelper.writableDatabase.execSQL("UPDATE transcription_sessions SET input_language = 'ml' WHERE id = 's1'")
            assertEquals(listOf("s2"), sessions.backfillCandidates(maxFailures = 2))
            assertEquals(1, sessions.setRedoDue("s1", due = true))
            assertEquals(listOf("s2"), sessions.backfillCandidates(maxFailures = 2))
            app.finishingJobDao().delete("s1")
            assertEquals(listOf("s1", "s2"), sessions.backfillCandidates(maxFailures = 2))
            assertEquals(1, sessions.countBackfillFailures("s2", failures = 1))
            assertEquals(listOf("s1", "s2"), sessions.backfillCandidates(maxFailures = 2))
            assertEquals(1, sessions.countBackfillFailures("s2", failures = 1))
            assertEquals(listOf("s1"), sessions.backfillCandidates(maxFailures = 2))
            assertEquals(1, sessions.countBackfillFailures("s1", failures = 2))
            assertEquals(emptyList<String>(), sessions.backfillCandidates(maxFailures = 2))
            assertEquals(0, sessions.countBackfillFailures("gone", failures = 1))
        }
    }

    @Test
    fun aRedoIsQueuedOnlyForARecordingWithoutAJob() {
        helper.createDatabase(DB, 12).use { insertVersion12Rows(it) }

        withApp { app ->
            val jobs = app.finishingJobDao()
            // s1 waits to be finished already, and a recording that is gone gets none.
            assertFalse(jobs.queueRedo("s1", "Reading", now = 1760300000000L))
            assertFalse(jobs.queueRedo("gone", "Reading", now = 1760300000000L))
            assertTrue(jobs.queueRedo("s2", "Reading", now = 1760300000000L))
            assertFalse(jobs.queueRedo("s2", "Reading", now = 1760300001000L))

            val redo = jobs.get("s2")!!
            assertTrue(redo.redo)
            assertEquals(FinishingJobDao.QUEUED, redo.state)
            assertEquals("Reading", redo.nextStep)
            assertEquals(0, redo.attempts)
            assertEquals(1760300000000L, redo.queuedAt)
            // A capture's own job is no redo.
            assertFalse(jobs.get("s1")!!.redo)
        }
    }

    // ========== The app's own build ==========

    /**
     * Room refuses a file whose schema, after its own migrations, differs from the one compiled
     * into the app, so this also shows that the migrations end in the schema the code expects.
     */
    @Test
    fun theAppOpensAVersion6Database() {
        helper.createDatabase(DB, 6).use { insertVersion6Rows(it) }

        withApp { app ->
            assertEquals(SESSIONS.size, app.transcriptionSessionDao().getSessionCount())
            app.todoDao().upsert(
                TodoEntity(
                    id = "t1", sessionId = "s2", text = "Buy milk", kind = "TASK", status = "OPEN",
                    certainty = "CONFIRMED", capture = "EXPLICIT", dueAt = null, dueHasTime = false,
                    timePhrase = null, remindAt = null, reminderShownAt = null,
                    sourceSegmentIds = "[\"s2-seg1\"]", createdAt = 1760100100000,
                    updatedAt = 1760100100000, completedAt = null
                )
            )
            assertEquals("s2", app.todoDao().get("t1")?.sessionId)
            assertTheMapWorks(app.mindMapDao(), sessionId = "s2", recordedAt = 1760100000000L)
        }
    }

    @Test
    fun theAppOpensAVersion7Database() {
        helper.createDatabase(DB, 7).use { insertVersion7Rows(it) }

        withApp { app ->
            assertEquals(SESSIONS_7.size, app.transcriptionSessionDao().getSessionCount())
            assertEquals("s1", app.todoDao().get("t1")?.sessionId)
            assertTheMapWorks(app.mindMapDao(), sessionId = "s1", recordedAt = 1760000000000L)

            // Deleting a recording of the migrated file tidies the map and keeps its to-do
            app.mindMapDao().deleteSessionAndTidyMap("s1")

            assertNull(app.todoDao().get("t1")!!.sessionId)
            assertEquals(emptyList<MindNodeEntity>(), app.mindMapDao().observeAllNodes().first())
        }
    }

    @Test
    fun theAppOpensAVersion8Database() {
        helper.createDatabase(DB, 8).use { insertVersion8Rows(it) }

        withApp { app ->
            val sessions = app.transcriptionSessionDao()
            assertEquals(SESSIONS_7.size, sessions.getSessionCount())
            assertEquals(1, app.mindMapDao().observeThemes().first().single().recordingCount)
            assertEquals(listOf(null, null, null, null, null), placeAndModel(sessions.getSession("s1").first()!!))

            sessions.updatePlace("s1", latitude = 50.9375, longitude = 6.9603, accuracyMeters = 12.5F, placeName = "Köln")
            sessions.updateTranscriptModel("s1", "parakeet-tdt-0.6b-v2-int8")

            assertEquals(
                listOf(50.9375, 6.9603, 12.5F, "Köln", "parakeet-tdt-0.6b-v2-int8"),
                placeAndModel(sessions.getSession("s1").first()!!)
            )

            // Removing the place clears its four columns and keeps the model
            sessions.updatePlace("s1", latitude = null, longitude = null, accuracyMeters = null, placeName = null)

            assertEquals(
                listOf(null, null, null, null, "parakeet-tdt-0.6b-v2-int8"),
                placeAndModel(sessions.getSession("s1").first()!!)
            )
        }
    }

    @Test
    fun theAppOpensAVersion9Database() {
        helper.createDatabase(DB, 9).use { insertVersion9Rows(it) }

        withApp { app ->
            assertEquals(SESSIONS_7.size, app.transcriptionSessionDao().getSessionCount())
            assertEquals(TRANSCRIPT_MODEL_9, app.transcriptionSessionDao().getSession("s1").first()!!.transcriptModel)
            val jobs = app.finishingJobDao()

            jobs.captureStarted("s2", owner = "process", now = 1760200000000L)
            assertTrue(jobs.queue("s2", recognizerFailed = false, now = 1760200001000L))

            assertEquals("s2", jobs.claimNext("process")?.sessionId)
            assertEquals(1, jobs.beginStep("s2", "Notes"))
        }
    }

    @Test
    fun theAppOpensAVersion10Database() {
        helper.createDatabase(DB, 10).use { insertVersion10Rows(it) }

        withApp { app ->
            val sessions = app.transcriptionSessionDao()
            assertEquals(SESSIONS_7.size, sessions.getSessionCount())
            assertNull(sessions.getSession("s1").first()!!.language)

            assertEquals(1, sessions.updateLanguage("s1", "ml"))
            assertEquals("ml", sessions.getSession("s1").first()!!.language)
            // Back to English or German; a recording that isn't there changes nothing.
            assertEquals(1, sessions.updateLanguage("s1", null))
            assertNull(sessions.getSession("s1").first()!!.language)
            assertEquals(0, sessions.updateLanguage("gone", "ml"))

            // The queue of version 10 is still there: its job is claimed as before.
            assertEquals("s1", app.finishingJobDao().claimNext("process")?.sessionId)
        }
    }

    @Test
    fun theAppOpensAVersion11Database() {
        helper.createDatabase(DB, 11).use { insertVersion11Rows(it) }

        withApp { app ->
            assertEquals(SESSIONS_7.size, app.transcriptionSessionDao().getSessionCount())
            assertEquals("ml", app.transcriptionSessionDao().getSession("s2").first()!!.language)
            val readings = app.segmentReadingDao()

            assertTrue(readings.save(reading("s2-seg1", "s2", "Buy milk.")))
            // Read again: in place of the first, one row a line.
            assertTrue(readings.save(reading("s2-seg1", "s2", "Buy the milk.")))
            // A line that isn't there, or a line of another recording, gets no reading.
            assertFalse(readings.save(reading("gone", "s2", "Gone.")))
            assertFalse(readings.save(reading("s1-seg1", "s2", "Another recording's line.")))

            val read = readings.linesAndReadings("s2")
            assertEquals(listOf("s2-seg1", "s2-seg2"), read.lines.map { it.id })
            assertEquals(listOf("Buy the milk."), read.readings.map { it.text })
            assertEquals(read.readings, readings.readingsOf("s2"))
            assertTrue(readings.readingsOf("s1").isEmpty())
        }
    }

    // ========== Helpers ==========

    /** Opens [DB] the way the app does (DatabaseModule), runs [block] and closes it. */
    private fun withApp(block: suspend (AppDatabase) -> Unit) {
        val app = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            DB
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            runBlocking { block(app) }
        } finally {
            app.close()
        }
    }

    /** A theme, a topic under it and a link to [sessionId] are stored and read back counted. */
    private suspend fun assertTheMapWorks(dao: MindMapDao, sessionId: String, recordedAt: Long) {
        assertTrue(dao.upsertNode(mindNode("home", MindMapDao.THEME, parentId = null)))
        assertTrue(dao.upsertNode(mindNode("heating", MindMapDao.TOPIC, parentId = "home")))
        assertTrue(dao.link(MindLinkEntity("heating", sessionId, "[\"Fixed this month\"]", "[]", createdAt = 1L)))

        val themes = dao.observeThemes().first()

        assertEquals(listOf("home"), themes.map { it.node.id })
        assertEquals(1, themes.single().recordingCount)
        assertEquals(recordedAt, themes.single().lastRecordedAt)
    }

    private fun mindNode(id: String, kind: String, parentId: String?) = MindNodeEntity(
        id = id, kind = kind, label = id, matchKey = id, parentId = parentId, summary = null,
        summaryUpdatedAt = null, summaryStale = false, pinned = false, createdAt = 1L, updatedAt = 1L
    )

    private fun insertVersion6Rows(db: SupportSQLiteDatabase) {
        SESSIONS.forEach { db.insertRow("transcription_sessions", SESSION_COLUMNS, it) }
        SEGMENTS.forEach { db.insertRow("transcription_segments", SEGMENT_COLUMNS, it) }
        INSIGHTS.forEach { db.insertRow("llm_insights", INSIGHT_COLUMNS, it) }
    }

    private fun insertVersion7Rows(db: SupportSQLiteDatabase) {
        CATEGORIES.forEach { db.insertRow("categories", CATEGORY_COLUMNS, it) }
        SESSIONS_7.forEach { db.insertRow("transcription_sessions", SESSION_COLUMNS_7, it) }
        SEGMENTS.forEach { db.insertRow("transcription_segments", SEGMENT_COLUMNS, it) }
        INSIGHTS.forEach { db.insertRow("llm_insights", INSIGHT_COLUMNS, it) }
        TODOS.forEach { db.insertRow("todos", TODO_COLUMNS, it) }
    }

    /** Version 7's rows and a mapped recording: a theme, its topic and the topic's link to s1. */
    private fun insertVersion8Rows(db: SupportSQLiteDatabase) {
        insertVersion7Rows(db)
        MIND_NODES.forEach { db.insertRow("mind_nodes", MIND_NODE_COLUMNS, it) }
        MIND_LINKS.forEach { db.insertRow("mind_links", MIND_LINK_COLUMNS, it) }
    }

    /** Version 8's rows and the transcript model version 9 added, for s1. */
    private fun insertVersion9Rows(db: SupportSQLiteDatabase) {
        insertVersion8Rows(db)
        db.execSQL("UPDATE transcription_sessions SET transcript_model = ? WHERE id = 's1'", arrayOf(TRANSCRIPT_MODEL_9))
    }

    /** Version 9's rows and a recording waiting to be finished (s1) and one the map read without a topic (s2). */
    private fun insertVersion10Rows(db: SupportSQLiteDatabase) {
        insertVersion9Rows(db)
        FINISHING_JOBS_10.forEach { db.insertRow("finishing_jobs", FINISHING_JOB_COLUMNS, it) }
        MAP_SKIPS_10.forEach { db.insertRow("mind_map_skips", listOf("session_id", "chars", "read_at"), it) }
    }

    /** Version 10's rows, with s2 recorded in Malayalam. */
    private fun insertVersion11Rows(db: SupportSQLiteDatabase) {
        insertVersion10Rows(db)
        db.execSQL("UPDATE transcription_sessions SET language = 'ml' WHERE id = 's2'")
    }

    /** Version 11's rows and the readings of version 12. */
    private fun insertVersion12Rows(db: SupportSQLiteDatabase) {
        insertVersion11Rows(db)
        READINGS_12.forEach { db.insertRow("segment_readings", READING_COLUMNS, it) }
    }

    /** A reading of [segmentId] as the Reading step stores one: of the line's text now, by the int8 build. */
    private fun reading(segmentId: String, sessionId: String, text: String) = SegmentReadingEntity(
        segmentId = segmentId,
        sessionId = sessionId,
        sourceSha256 = "a3f1",
        text = text,
        model = "indictrans2-indic-en-dist-200m-int8",
        score = -0.4,
        windowId = null,
        createdAt = 1760300000000L
    )

    /** The place and the transcript model of [session], in the order of [PLACE_AND_MODEL_COLUMNS]. */
    private fun placeAndModel(session: TranscriptionSessionEntity): List<Any?> = listOf(
        session.latitude, session.longitude, session.locationAccuracyMeters, session.placeName, session.transcriptModel
    )

    private fun assertVersion7RowsKept(db: SupportSQLiteDatabase) {
        assertEquals(CATEGORIES, db.rows("SELECT ${CATEGORY_COLUMNS.joinToString()} FROM categories ORDER BY id"))
        assertEquals(
            SESSIONS_7,
            db.rows("SELECT ${SESSION_COLUMNS_7.joinToString()} FROM transcription_sessions ORDER BY id")
        )
        assertEquals(SEGMENTS, db.rows("SELECT ${SEGMENT_COLUMNS.joinToString()} FROM transcription_segments ORDER BY id"))
        assertEquals(INSIGHTS, db.rows("SELECT ${INSIGHT_COLUMNS.joinToString()} FROM llm_insights ORDER BY id"))
        assertEquals(TODOS, db.rows("SELECT ${TODO_COLUMNS.joinToString()} FROM todos ORDER BY id"))
    }

    private fun SupportSQLiteDatabase.insertRow(table: String, columns: List<String>, values: List<Any?>) {
        execSQL(
            "INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
            values.toTypedArray()
        )
    }

    private fun SupportSQLiteDatabase.rows(sql: String): List<List<Any?>> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).map { i ->
                        when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            else -> cursor.getString(i)
                        }
                    }
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.columns(table: String): List<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.indices(): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'index'").map { it.single() as String }

    private companion object {
        const val DB = "migration-test.db"

        val SESSION_COLUMNS = listOf(
            "id", "name", "created_at", "last_modified_at", "mode", "input_language",
            "output_language", "duration_ms", "insight_strategy", "topic"
        )
        val SESSION_COLUMNS_7 = SESSION_COLUMNS + listOf("zone_id", "category_id")
        /** The columns version 9 adds to transcription_sessions, in their order. */
        val PLACE_AND_MODEL_COLUMNS = listOf("latitude", "longitude", "location_accuracy_m", "place_name", "transcript_model")
        val SEGMENT_COLUMNS = listOf("id", "session_id", "text", "timestamp", "is_complete")
        val INSIGHT_COLUMNS = listOf("id", "session_id", "title", "content", "tasks", "timestamp", "source_segment_ids")
        val TODO_COLUMNS = listOf(
            "id", "session_id", "text", "kind", "status", "certainty", "capture", "due_at",
            "due_has_time", "time_phrase", "remind_at", "reminder_shown_at", "source_segment_ids",
            "created_at", "updated_at", "completed_at"
        )
        val CATEGORY_COLUMNS = listOf("id", "name", "mode", "sort_order", "created_at")
        val MIND_NODE_COLUMNS = listOf(
            "id", "kind", "label", "match_key", "parent_id", "summary", "summary_updated_at",
            "summary_stale", "pinned", "created_at", "updated_at"
        )
        val MIND_LINK_COLUMNS = listOf("node_id", "session_id", "points", "segment_ids", "created_at")
        val FINISHING_JOB_COLUMNS = listOf(
            "session_id", "state", "next_step", "attempts", "owner", "recognizer_failed", "recovered",
            "waits_for_recording", "queued_at", "created_at"
        )
        /** The transcript model of s1 in a version 9 file. */
        const val TRANSCRIPT_MODEL_9 = "parakeet-tdt-0.6b-v3-int8"

        /** A version 10 file's queue: s1 waits to be finished, its notes step begun once. */
        val FINISHING_JOBS_10: List<List<Any?>> = listOf(
            listOf("s1", "QUEUED", "Notes", 1L, "process", 0L, 0L, 0L, 1760000700000L, 1760000600000L)
        )
        /** A version 10 file's map skips: s2 was read for the map and gave no topic. */
        val MAP_SKIPS_10: List<List<Any?>> = listOf(listOf("s2", 9L, 1760100100000L))

        /** The columns of segment_readings, version 12's, in their order. */
        val READING_COLUMNS = listOf("segment_id", "session_id", "source_sha256", "text", "model", "score", "window_id", "created_at")

        /** Readings of two lines of s1 and one of s2, one of them with no score. */
        val READINGS_12: List<List<Any?>> = listOf(
            listOf("s1-seg1", "s1", "9f0a", "Call the bank at half past seven tomorrow.", "indictrans2-indic-en-dist-200m-int8", -0.38, null, 1760300000000L),
            listOf("s1-seg2", "s1", "1b2c", "", "indictrans2-indic-en-dist-200m-int8", null, null, 1760300000001L),
            listOf("s2-seg1", "s2", "77de", "Buy milk.", "indictrans2-indic-en-dist-200m-int8", -0.5, null, 1760300000002L)
        )

        val SESSIONS: List<List<Any?>> = listOf(
            listOf("s1", "Weekly sync", 1760000000000L, 1760000600000L, "SHORT_MEETING", "de", null, 600000L, "REAL_TIME", "Q4 plan"),
            listOf("s2", null, 1760100000000L, 1760100090000L, "SIMPLE_LISTENING", "en", null, 90000L, "END_OF_SESSION", null),
            listOf(
                "s3", "Lieferantengespräch übersetzt", 1760200000000L, 1760200340000L, "REAL_TIME_TRANSLATION",
                "de", "en", 340000L, "REAL_TIME", null
            )
        )
        /** [SESSIONS] as version 7 has them: with a zone, and two in a category. */
        val SESSIONS_7: List<List<Any?>> = listOf(
            SESSIONS[0] + listOf("Europe/Berlin", "meeting"),
            SESSIONS[1] + listOf("Europe/Berlin", "voice-note"),
            SESSIONS[2] + listOf(null, null)
        )
        val SEGMENTS: List<List<Any?>> = listOf(
            listOf("s1-seg1", "s1", "Morgen um halb 8 die Bank anrufen.", 1760000100000L, 1L),
            listOf("s1-seg2", "s1", "Remind me to send the deck on Friday.", 1760000200000L, 1L),
            listOf("s2-seg1", "s2", "Buy milk.", 1760100010000L, 1L),
            listOf("s2-seg2", "s2", "partial", 1760100020000L, 0L),
            listOf("s3-seg1", "s3", "Thanks for joining, it's a short one.", 1760200010000L, 1L)
        )
        val INSIGHTS: List<List<Any?>> = listOf(
            listOf(
                "s1-ins1", "s1", "Sync", "Bank call and deck.", "[\"Call the bank\",\"Send the deck\"]",
                1760000400000L, "[\"s1-seg1\",\"s1-seg2\"]"
            ),
            listOf("s2-ins1", "s2", null, "Milk.", null, 1760100030000L, "[\"s2-seg1\"]")
        )
        val TODOS: List<List<Any?>> = listOf(
            listOf(
                "t1", "s1", "Die Bank anrufen", "TASK", "OPEN", "CONFIRMED", "EXPLICIT", 1760077800000L, 1L,
                "morgen um halb 8", 1760077800000L, null, "[\"s1-seg1\"]", 1760000500000L, 1760000500000L, null
            ),
            listOf(
                "t2", null, "Buy milk", "TASK", "DONE", "CONFIRMED", "COMMITMENT", null, 0L,
                null, null, null, "[]", 1760100100000L, 1760100200000L, 1760100200000L
            )
        )
        val CATEGORIES: List<List<Any?>> = listOf(
            listOf("meeting", "Meeting", "SHORT_MEETING", 1L, 1759000000000L),
            listOf("voice-note", "Voice note", "SIMPLE_LISTENING", 0L, 1759000000000L)
        )
        /** Version 8's map of s1, parent first and in id order: a pinned theme with a summary, and its topic. */
        val MIND_NODES: List<List<Any?>> = listOf(
            listOf(
                "home", "THEME", "Home", "home", null, "The bank call and the heating.", 1760000800000L, 0L, 1L,
                1760000700000L, 1760000800000L
            ),
            listOf("repair", "TOPIC", "Heating repair", "heating repair", "home", null, null, 0L, 0L, 1760000700000L, 1760000700000L)
        )
        val MIND_LINKS: List<List<Any?>> = listOf(
            listOf("repair", "s1", "[\"Fixed this month\"]", "[\"s1-seg1\"]", 1760000700000L)
        )
    }
}
