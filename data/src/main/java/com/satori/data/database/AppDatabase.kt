package com.satori.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.satori.data.database.dao.CategoryDao
import com.satori.data.database.dao.FinishingJobDao
import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.SearchDao
import com.satori.data.database.dao.SegmentReadingDao
import com.satori.data.database.dao.TodoDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.data.database.entity.CategoryEntity
import com.satori.data.database.entity.FinishingJobEntity
import com.satori.data.database.entity.KeptBeforeRedoEntity
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.MindLinkEntity
import com.satori.data.database.entity.MindMapSkipEntity
import com.satori.data.database.entity.MindNodeEntity
import com.satori.data.database.entity.NoteKeptBeforeRedoEntity
import com.satori.data.database.entity.SegmentReadingEntity
import com.satori.data.database.entity.TodoEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity

/**
 * Room database for the application.
 *
 * Manages persistent storage of transcription sessions, segments, LLM insights, todos,
 * recording categories, the mind map and the queue of recordings to be finished.
 *
 * Database version 1:
 * - Initial schema with sessions, segments, and insights tables
 * - Foreign key relationships with CASCADE delete
 * - Indices on session_id columns for query performance
 *
 * Database version 2:
 * - Added recording_type column to transcription_sessions table
 * - Defaults to "SHORT" for backward compatibility
 *
 * Database version 3:
 * - Added mode, input_language, output_language to transcription_sessions
 * - Migrated recording_type to mode
 *
 * Database version 4:
 * - Added title and tasks columns to llm_insights table
 * - Support for structured AI output with titles and extracted tasks
 *
 * Database version 5:
 * - Added duration_ms column to transcription_sessions table
 * - Stores total recording duration in milliseconds (0 for pre-existing sessions)
 *
 * Database version 6:
 * - Added insight_strategy column (REAL_TIME/END_OF_SESSION) to transcription_sessions
 * - Added topic column (nullable text) to transcription_sessions
 *
 * Database version 7:
 * - Added the todos table (session_id references transcription_sessions ON DELETE SET NULL;
 *   indexed on status, due_at, remind_at and session_id; reminder_shown_at records when each
 *   reminder was shown)
 * - Added the categories table
 * - Added zone_id and category_id (nullable text, indexed) to transcription_sessions;
 *   category_id has no foreign key, see TranscriptionSessionEntity.categoryId
 *
 * Database version 8:
 * - Added the mind map: mind_nodes (themes and topics; a topic's parent_id references its theme
 *   ON DELETE CASCADE; indexed on parent_id and match_key) and mind_links (one row per topic and
 *   recording, both ends ON DELETE CASCADE; primary key node_id, session_id; indexed on
 *   session_id). Additive: no existing table changes.
 *
 * Database version 9:
 * - Added to transcription_sessions where a recording started, latitude, longitude and
 *   location_accuracy_m (REAL) and place_name (TEXT), and transcript_model (TEXT), the id of the
 *   speech model that made its transcript. All nullable, existing rows keep NULL. Additive.
 *
 * Database version 10:
 * - Added finishing_jobs, the queue of recordings to be finished in the background: one row per
 *   recording (primary key session_id, ON DELETE CASCADE), from its capture's start until it is
 *   finished, with its state, the step that began last and how often, its owner process, and its
 *   place in the queue; indexed on state and queued_at. No text.
 * - Added mind_map_skips: a recording the AI read for the mind map without finding a topic, with
 *   the length of its complete transcript then (primary key session_id, ON DELETE CASCADE). No
 *   text. Both additive and empty after the migration.
 *
 * Database version 11:
 * - Added language (TEXT, nullable) to transcription_sessions: the language a recording is made
 *   in, null for English or German and "ml" for Malayalam (RecordingLanguage.code). Existing rows
 *   keep NULL, which is what they were recorded in. Additive.
 *
 * Database version 12:
 * - Added segment_readings, the English readings of transcript lines (0.8.0): one row per line
 *   (primary key segment_id, references transcription_segments ON DELETE CASCADE, so a replaced
 *   transcript and a deleted recording take theirs with them), with the line's recording (indexed),
 *   the SHA-256 of the text it read, the reading, the reader's model, its score, its window and when
 *   it was read. Additive and empty after the migration: the Reading step reads the lines.
 *
 * Database version 13 (0.8.0, design R9, the backfill of readings):
 * - Added to transcription_sessions name_by_owner (INTEGER NOT NULL DEFAULT 0), 1 while the name is
 *   one the owner typed (Rename), backfill_failures (INTEGER NOT NULL DEFAULT 0), the failures
 *   counted against its backfill: finishings that ended with lines still never read, and redo_due
 *   (INTEGER NOT NULL DEFAULT 0), 1 while the backfill is to make its AI steps again over its
 *   readings.
 * - Added to llm_insights edited_at (INTEGER, nullable): when the owner last saved the note in the
 *   note editor.
 * - Added to finishing_jobs redo (INTEGER NOT NULL DEFAULT 0): a job that makes a finished
 *   recording's AI steps again from its Reading step.
 * - Added kept_before_redo and notes_kept_before_redo: a recording's name and notes as they were
 *   before a finishing made again first replaced any of them, kept once (primary key session_id,
 *   and the notes' own ids indexed on session_id; both ON DELETE CASCADE with the recording).
 * - Additive, but for one value: every Malayalam recording of before (language or input_language
 *   'ml') gets redo_due 1, as an older version's AI model read its lines as they were said, or none
 *   did. Existing rows get 0 and NULL otherwise: before version 13 no column recorded who typed a
 *   name or changed a note, so those of before count as the app's, and are kept before they are
 *   replaced.
 */
@Database(
    entities = [
        TranscriptionSessionEntity::class,
        TranscriptionSegmentEntity::class,
        LlmInsightEntity::class,
        TodoEntity::class,
        CategoryEntity::class,
        MindNodeEntity::class,
        MindLinkEntity::class,
        FinishingJobEntity::class,
        MindMapSkipEntity::class,
        SegmentReadingEntity::class,
        KeptBeforeRedoEntity::class,
        NoteKeptBeforeRedoEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transcriptionSessionDao(): TranscriptionSessionDao
    abstract fun transcriptionSegmentDao(): TranscriptionSegmentDao
    abstract fun llmInsightDao(): LlmInsightDao
    abstract fun searchDao(): SearchDao
    abstract fun todoDao(): TodoDao
    abstract fun categoryDao(): CategoryDao
    abstract fun mindMapDao(): MindMapDao
    abstract fun finishingJobDao(): FinishingJobDao
    abstract fun segmentReadingDao(): SegmentReadingDao

    companion object {
        const val DATABASE_NAME = "satori.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE transcription_sessions ADD COLUMN recording_type TEXT NOT NULL DEFAULT 'SHORT'"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns
                database.execSQL("ALTER TABLE transcription_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'SIMPLE_LISTENING'")
                database.execSQL("ALTER TABLE transcription_sessions ADD COLUMN input_language TEXT NOT NULL DEFAULT 'it'")
                database.execSQL("ALTER TABLE transcription_sessions ADD COLUMN output_language TEXT")

                // Migrate existing data (recording_type -> mode)
                // Note: recording_type column remains but is unused by the Entity
                database.execSQL("""
                    UPDATE transcription_sessions
                    SET mode = CASE
                        WHEN recording_type = 'SHORT' THEN 'SHORT_MEETING'
                        WHEN recording_type = 'LONG' THEN 'LONG_MEETING'
                        ELSE 'SIMPLE_LISTENING'
                    END
                """)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add title and tasks columns to llm_insights
                database.execSQL("ALTER TABLE llm_insights ADD COLUMN title TEXT")
                database.execSQL("ALTER TABLE llm_insights ADD COLUMN tasks TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add duration_ms column to transcription_sessions; 0 = unknown for existing sessions
                database.execSQL(
                    "ALTER TABLE transcription_sessions ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add insight_strategy column; existing sessions default to REAL_TIME (legacy behavior)
                database.execSQL(
                    "ALTER TABLE transcription_sessions ADD COLUMN insight_strategy TEXT NOT NULL DEFAULT 'REAL_TIME'"
                )
                // Add topic column; existing sessions have no topic
                database.execSQL(
                    "ALTER TABLE transcription_sessions ADD COLUMN topic TEXT"
                )
            }
        }

        /**
         * The SQL of [MIGRATION_6_7], in order. The table and index statements are Room's own
         * createSql from schemas/7.json, so a migrated database matches a fresh one.
         *
         * Existing sessions get zone_id and category_id NULL: their zone wasn't recorded, and
         * the starter categories are seeded at runtime (with names in the app language) by
         * CategoryRepository.ensureStarterCategories, not here.
         */
        internal val MIGRATION_6_7_STATEMENTS = listOf(
            "ALTER TABLE `transcription_sessions` ADD COLUMN `zone_id` TEXT",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `category_id` TEXT",
            "CREATE INDEX IF NOT EXISTS `index_transcription_sessions_category_id` " +
                "ON `transcription_sessions` (`category_id`)",
            "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `todos` (`id` TEXT NOT NULL, `session_id` TEXT, " +
                "`text` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`certainty` TEXT NOT NULL, `capture` TEXT NOT NULL, `due_at` INTEGER, " +
                "`due_has_time` INTEGER NOT NULL, `time_phrase` TEXT, `remind_at` INTEGER, " +
                "`reminder_shown_at` INTEGER, `source_segment_ids` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "`completed_at` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_todos_status` ON `todos` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_todos_due_at` ON `todos` (`due_at`)",
            "CREATE INDEX IF NOT EXISTS `index_todos_remind_at` ON `todos` (`remind_at`)",
            "CREATE INDEX IF NOT EXISTS `index_todos_session_id` ON `todos` (`session_id`)"
        )

        /** Adds todos and categories, and zone_id and category_id on sessions. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_6_7_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_7_8], in order: Room's own createSql from schemas/8.json, so a
         * migrated database matches a fresh one. mind_nodes comes first because mind_links
         * references it. Both tables start empty; recordings made before are mapped when the
         * user asks for it on the map screen ("Map N older recordings").
         */
        internal val MIGRATION_7_8_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `mind_nodes` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, `match_key` TEXT NOT NULL, `parent_id` TEXT, `summary` TEXT, " +
                "`summary_updated_at` INTEGER, `summary_stale` INTEGER NOT NULL DEFAULT 0, " +
                "`pinned` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`parent_id`) REFERENCES `mind_nodes`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_mind_nodes_parent_id` ON `mind_nodes` (`parent_id`)",
            "CREATE INDEX IF NOT EXISTS `index_mind_nodes_match_key` ON `mind_nodes` (`match_key`)",
            "CREATE TABLE IF NOT EXISTS `mind_links` (`node_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                "`points` TEXT NOT NULL, `segment_ids` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`node_id`, `session_id`), " +
                "FOREIGN KEY(`node_id`) REFERENCES `mind_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_mind_links_session_id` ON `mind_links` (`session_id`)"
        )

        /** Adds the mind map's tables, mind_nodes and mind_links. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_7_8_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_8_9], in order: each column as Room's createSql in schemas/9.json
         * defines it, so a migrated database matches a fresh one. No defaults: a recording made
         * before has no place, and its transcript model stays NULL, which reads as Parakeet v3,
         * the only speech model before version 9.
         */
        internal val MIGRATION_8_9_STATEMENTS = listOf(
            "ALTER TABLE `transcription_sessions` ADD COLUMN `latitude` REAL",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `longitude` REAL",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `location_accuracy_m` REAL",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `place_name` TEXT",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `transcript_model` TEXT"
        )

        /** Adds where a recording started and which speech model made its transcript. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_8_9_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_9_10], in order: Room's own createSql from schemas/10.json, so a
         * migrated database matches a fresh one. Both tables start empty: a recording made
         * before was finished by the recorder that made it, and the map offers the older
         * recordings without topics once more, then no longer.
         */
        internal val MIGRATION_9_10_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `finishing_jobs` (`session_id` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                "`next_step` TEXT, `attempts` INTEGER NOT NULL, `owner` TEXT NOT NULL, " +
                "`recognizer_failed` INTEGER NOT NULL, `recovered` INTEGER NOT NULL, " +
                "`waits_for_recording` INTEGER NOT NULL, `queued_at` INTEGER, `created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`session_id`), FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_finishing_jobs_state_queued_at` ON `finishing_jobs` (`state`, `queued_at`)",
            "CREATE TABLE IF NOT EXISTS `mind_map_skips` (`session_id` TEXT NOT NULL, `chars` INTEGER NOT NULL, " +
                "`read_at` INTEGER NOT NULL, PRIMARY KEY(`session_id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )

        /** Adds the finishing queue, finishing_jobs, and the mind map's skips, mind_map_skips. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_9_10_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_10_11]: the column as Room's createSql in schemas/11.json defines
         * it, so a migrated database matches a fresh one. No default: a recording made before is
         * English or German, which NULL stands for.
         */
        internal val MIGRATION_10_11_STATEMENTS = listOf(
            "ALTER TABLE `transcription_sessions` ADD COLUMN `language` TEXT"
        )

        /** Adds the language a recording is made in. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_10_11_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_11_12], in order: Room's own createSql from schemas/12.json, so a
         * migrated database matches a fresh one. The table starts empty: no line was read before,
         * and a Malayalam recording made before gets its readings when the reader reads it.
         */
        internal val MIGRATION_11_12_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `segment_readings` (`segment_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                "`source_sha256` TEXT NOT NULL, `text` TEXT NOT NULL, `model` TEXT NOT NULL, `score` REAL, " +
                "`window_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`segment_id`), " +
                "FOREIGN KEY(`segment_id`) REFERENCES `transcription_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_segment_readings_session_id` ON `segment_readings` (`session_id`)"
        )

        /** Adds the readings of transcript lines, segment_readings. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_11_12_STATEMENTS.forEach { database.execSQL(it) }
            }
        }

        /**
         * The SQL of [MIGRATION_12_13] that changes the schema: the five columns as Room's createSql
         * in schemas/13.json defines them, each at its table's end, and the two tables of what a
         * finishing made again keeps, with their index, in Room's own createSql. Existing rows get
         * the defaults: no name typed by the owner, no note he changed, no backfill failed, no redo
         * due, no job a redo; before version 13 nothing recorded the first two, so names and notes of
         * before count as the app's.
         */
        internal val MIGRATION_12_13_STATEMENTS = listOf(
            "ALTER TABLE `transcription_sessions` ADD COLUMN `name_by_owner` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `backfill_failures` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `transcription_sessions` ADD COLUMN `redo_due` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `llm_insights` ADD COLUMN `edited_at` INTEGER",
            "ALTER TABLE `finishing_jobs` ADD COLUMN `redo` INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE IF NOT EXISTS `kept_before_redo` (`session_id` TEXT NOT NULL, `name` TEXT, " +
                "`name_by_owner` INTEGER NOT NULL, `insight_strategy` TEXT NOT NULL, `kept_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`session_id`), FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `notes_kept_before_redo` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                "`title` TEXT, `content` TEXT NOT NULL, `tasks` TEXT, `timestamp` INTEGER NOT NULL, " +
                "`source_segment_ids` TEXT NOT NULL, `edited_at` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `transcription_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_notes_kept_before_redo_session_id` ON `notes_kept_before_redo` (`session_id`)"
        )

        /**
         * The one value [MIGRATION_12_13] sets, after its schema's statements: every Malayalam
         * recording of before is due to be made again over its readings (redo_due), as an older
         * version's AI model read its lines as they were said, or none did: the backfill of readings
         * picks it up once the reader is on the phone (design R9, the owner's O8).
         */
        internal val MIGRATION_12_13_DATA = listOf(
            "UPDATE `transcription_sessions` SET `redo_due` = 1 WHERE `language` = 'ml' OR `input_language` = 'ml'"
        )

        /** Adds what tells the owner's edits from the app's output, and the backfill's own columns and tables. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                (MIGRATION_12_13_STATEMENTS + MIGRATION_12_13_DATA).forEach { database.execSQL(it) }
            }
        }

        /**
         * Every migration, oldest first: what the app's database builder registers, so a file of
         * any earlier version opens. The migration tests open files with the same list.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13
        )
    }
}
