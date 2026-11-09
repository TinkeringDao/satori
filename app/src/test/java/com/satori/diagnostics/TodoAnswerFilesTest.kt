package com.satori.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TodoAnswerFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()

    private fun answers(dir: File = File(tmp.root, "debug/todo-answers")) = TodoAnswerFiles(dir) { message, _ -> warnings += message }

    @Test
    fun `each window's answer goes to its own file, and finding the to-dos again writes it anew`() {
        val dir = File(tmp.root, "debug/todo-answers")
        val log = answers(dir)

        log.todoAnswer("3f2a-11", 0, """{"items": []}""")
        log.todoAnswer("3f2a-11", 1, """{"items": [{"segment": "S2"}]}""")
        log.todoAnswer("3f2a-11", 0, """{"items": [{"segment": "S1"}]}""")

        assertEquals(setOf("3f2a-11-0.json", "3f2a-11-1.json"), dir.list()!!.toSet())
        assertEquals("""{"items": [{"segment": "S1"}]}""", File(dir, "3f2a-11-0.json").readText())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an id that is not a file name is skipped`() {
        val log = answers()

        log.todoAnswer("../outside", 0, "{}")
        log.todoAnswer("two words", 0, "{}")
        log.todoAnswer("3f2a-11", -1, "{}")

        assertFalse(File(tmp.root, "outside-0.json").exists())
        assertFalse(File(tmp.root, "debug/todo-answers").exists())
        assertEquals(3, warnings.size)
    }

    @Test
    fun `a deleted recording's answers go with it, and only its own`() {
        val dir = File(tmp.root, "debug/todo-answers")
        val log = answers(dir)
        log.todoAnswer("3f2a-11", 0, "{}")
        log.todoAnswer("3f2a-11", 1, "{}")
        // Ids that this one starts with, or that start with it.
        log.todoAnswer("3f2a", 0, "{}")
        log.todoAnswer("3f2a-11-7", 0, "{}")

        log.forget("3f2a-11")

        assertEquals(setOf("3f2a-0.json", "3f2a-11-7-0.json"), dir.list()!!.toSet())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `forgetting a recording without answers, or with an id that is not a file name, deletes nothing`() {
        val outside = tmp.newFile("x-0.json")

        answers(File(tmp.root, "debug/todo-answers")).forget("3f2a-11")
        answers(tmp.root).forget("../x")
        answers(tmp.root).forget("x/..")

        assertTrue(outside.exists())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a write that fails costs only that answer`() {
        // A file where the folder should be: nothing can be written into it.
        val blocked = tmp.newFile("blocked")

        answers(blocked).todoAnswer("3f2a-11", 0, "{}")
        answers(blocked).answer("3f2a-11", "notes-final-0", "{}")

        assertEquals(listOf("A raw to-do answer was not kept", "A raw answer was not kept"), warnings)
    }

    // ─── The notes' and the name's answers (0.7.2) ───────────────────────────────

    @Test
    fun `the notes' and the name's answers go beside the to-do answers, each call under its tag`() {
        val dir = File(tmp.root, "debug/todo-answers")
        val log = answers(dir)

        log.todoAnswer("3f2a-11", 0, """{"items": []}""")
        log.answer("3f2a-11", "notes-final-0", """{"title": "Garden", "points": []}""")
        log.answer("3f2a-11", "notes-final-1", """{"title": "Garden", "points": ["Plant the roses."]}""")
        log.answer("3f2a-11", "name-0", "Garden plans")
        log.answer("3f2a-11", "rerun-notes-2-part1-0", "{}")

        assertEquals(
            setOf("3f2a-11-0.json", "3f2a-11-notes-final-0.txt", "3f2a-11-notes-final-1.txt", "3f2a-11-name-0.txt", "3f2a-11-rerun-notes-2-part1-0.txt"),
            dir.list()!!.toSet()
        )
        assertEquals("Garden plans", File(dir, "3f2a-11-name-0.txt").readText())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a tag of no step, or an id that is not a file name, keeps no answer`() {
        val log = answers()

        log.answer("3f2a-11", "../outside", "{}")
        log.answer("3f2a-11", "todos-0", "{}")
        log.answer("3f2a-11", "Notes-0", "{}")
        log.answer("../3f2a", "notes-final-0", "{}")

        assertFalse(File(tmp.root, "debug/todo-answers").exists())
        assertEquals(4, warnings.size)
    }

    @Test
    fun `a step run again clears its own answers first, and only those of its recording`() {
        val dir = File(tmp.root, "debug/todo-answers")
        val log = answers(dir)
        for (tag in listOf("notes-part1-0", "notes-part2-0", "notes-part3-1", "notes-merge-0", "notes-final-0", "name-0", "name-1")) {
            log.answer("3f2a-11", tag, "{}")
        }
        log.answer("3f2a", "notes-part1-0", "{}")
        log.todoAnswer("3f2a-11", 0, "{}")

        // The final note's own, then a whole pass's (all the notes'), then the name's.
        log.clear("3f2a-11", "notes-final")
        assertFalse(File(dir, "3f2a-11-notes-final-0.txt").exists())
        assertTrue(File(dir, "3f2a-11-notes-part1-0.txt").exists())
        log.clear("3f2a-11", "notes")
        log.clear("3f2a-11", "name")

        assertEquals(setOf("3f2a-notes-part1-0.txt", "3f2a-11-0.json"), dir.list()!!.toSet())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a deleted recording's notes and name answers go with its to-do answers, and another's stay`() {
        val dir = File(tmp.root, "debug/todo-answers")
        val log = answers(dir)
        log.todoAnswer("3f2a-11", 0, "{}")
        log.answer("3f2a-11", "notes-final-0", "{}")
        log.answer("3f2a-11", "name-1", "{}")
        log.answer("3f2a-11", "rerun-todos-1-0", "{}")
        // Ids that this one starts with, or that start with it.
        log.answer("3f2a", "name-0", "{}")
        log.answer("3f2a-11-7", "notes-final-0", "{}")
        log.todoAnswer("3f2a-11-7", 0, "{}")

        log.forget("3f2a-11")

        assertEquals(setOf("3f2a-name-0.txt", "3f2a-11-7-notes-final-0.txt", "3f2a-11-7-0.json"), dir.list()!!.toSet())
        assertTrue(warnings.isEmpty())
    }
}
