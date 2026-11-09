package com.satori.data.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SQL of the migrations against the schemas Room exported (schemas/N.json, kept in git):
 * replayed on schema N, a migration's statements give schema N + 1 exactly, table by table and
 * index by index, in Room's own createSql. So a migrated database matches a fresh one, which the
 * instrumented MigrationTest checks on a device; this runs with every JVM test run.
 *
 * The replay knows only what the migrations use: CREATE TABLE and CREATE INDEX, which must be
 * Room's createSql, and ALTER TABLE ... ADD COLUMN, whose column comes after the existing ones,
 * as Room places the column of an entity field added last.
 */
class MigrationSqlTest {

    @Test
    fun `12 to 13 adds what tells the owner's edits and the backfill's columns as version 13 has them`() {
        assertEquals(schema(13), schema(12).after(AppDatabase.MIGRATION_12_13_STATEMENTS))
    }

    @Test
    fun `11 to 12 creates the lines' readings as version 12 has them`() {
        assertEquals(schema(12), schema(11).after(AppDatabase.MIGRATION_11_12_STATEMENTS))
    }

    @Test
    fun `10 to 11 adds the recording's language as version 11 has it`() {
        assertEquals(schema(11), schema(10).after(AppDatabase.MIGRATION_10_11_STATEMENTS))
    }

    @Test
    fun `9 to 10 creates the finishing queue and the mind map's skips as version 10 has them`() {
        assertEquals(schema(10), schema(9).after(AppDatabase.MIGRATION_9_10_STATEMENTS))
    }

    @Test
    fun `8 to 9 adds the place and the transcript model as version 9 has them`() {
        assertEquals(schema(9), schema(8).after(AppDatabase.MIGRATION_8_9_STATEMENTS))
    }

    @Test
    fun `7 to 8 creates the mind map as version 8 has it`() {
        assertEquals(schema(8), schema(7).after(AppDatabase.MIGRATION_7_8_STATEMENTS))
    }

    @Test
    fun `6 to 7 adds the to-dos, the categories and the session columns as version 7 has them`() {
        assertEquals(schema(7), schema(6).after(AppDatabase.MIGRATION_6_7_STATEMENTS))
    }

    @Test
    fun `the app's database builder gets every migration, one version at a time`() {
        assertEquals((1..12).map { it to it + 1 }, AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    /** A schema's tables, by name, and its indices, each as the SQL that creates it. */
    private data class Schema(val tables: Map<String, String>, val indices: Set<String>) {

        /** This schema after [statements], run in order. */
        fun after(statements: List<String>): Schema {
            val tables = tables.toMutableMap()
            val indices = indices.toMutableSet()
            statements.forEach { sql ->
                val table = CREATE_TABLE.find(sql)
                val column = ADD_COLUMN.find(sql)
                when {
                    table != null -> tables[table.groupValues[1]] = sql
                    CREATE_INDEX.containsMatchIn(sql) -> indices += sql
                    column != null -> {
                        val (name, definition) = column.destructured
                        val create = tables[name] ?: error("A column added to a table the schema doesn't have: $sql")
                        tables[name] = create.replace(TABLE_CONSTRAINTS, ", $definition$TABLE_CONSTRAINTS")
                    }
                    else -> error("A statement the replay doesn't know: $sql")
                }
            }
            return Schema(tables, indices)
        }
    }

    /** The tables and indices of schemas/[version].json, with their names put in. */
    private fun schema(version: Int): Schema {
        val file = File(SCHEMAS, "$version.json")
        assertTrue("No exported schema at ${file.absolutePath}", file.isFile)
        val database = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        val tables = mutableMapOf<String, String>()
        val indices = mutableSetOf<String>()
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val name = entity.text("tableName")
            tables[name] = entity.text("createSql").named(name)
            entity["indices"]?.jsonArray?.forEach { index -> indices += index.jsonObject.text("createSql").named(name) }
        }
        return Schema(tables, indices)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** Room's createSql with the table's name in place of its placeholder. */
    private fun String.named(table: String): String = replace("\${TABLE_NAME}", table)

    private companion object {
        /** Where Room exports the schemas (data/build.gradle.kts); Gradle runs the tests in the module's folder. */
        val SCHEMAS = File("schemas/com.satori.data.database.AppDatabase")

        val CREATE_TABLE = Regex("^CREATE TABLE IF NOT EXISTS `([^`]+)` \\(")
        val CREATE_INDEX = Regex("^CREATE (UNIQUE )?INDEX IF NOT EXISTS `[^`]+` ON `[^`]+` \\(")
        val ADD_COLUMN = Regex("^ALTER TABLE `([^`]+)` ADD COLUMN (`[^`]+` .+)$")

        /** Where Room's createSql ends the columns: every table here has a PRIMARY KEY clause. */
        const val TABLE_CONSTRAINTS = ", PRIMARY KEY("
    }
}
