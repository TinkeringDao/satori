package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A pill that says more to TalkBack than its text ("Delete Standard") passes it as PillButton's
 * contentDescription: one added to the pill's modifier is never spoken, because the pill merges its
 * text and clears it. Advanced settings' AI model pills did, so TalkBack said "Delete" under each
 * model. Gradle runs the tests in the module's folder.
 */
class PillDescriptionsTest {

    @Test
    fun `no pill takes its description through its modifier`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)

        val described = Regex("semantics\\s*\\{[^}]*contentDescription\\s*=")
        val wrong = sources.flatMap { file ->
            val text = file.readText()
            pillCalls(text).filter { (_, call) -> described.containsMatchIn(call) }
                .map { (at, _) -> "${file.name}:${text.take(at).count { it == '\n' } + 1}" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the scan finds a pill described through its modifier`() {
        val text = """
            PillButton(
                text = "Delete",
                onClick = {},
                modifier = Modifier.semantics { contentDescription = description }
            )
            PillButton(text = "Keep", onClick = { keep() }, contentDescription = keep)
        """.trimIndent()

        val calls = pillCalls(text)

        assertEquals(2, calls.size)
        assertTrue(Regex("semantics\\s*\\{[^}]*contentDescription\\s*=").containsMatchIn(calls[0].second))
        assertTrue("semantics" !in calls[1].second)
    }

    /** Each call of PillButton in [text], from its name to its closing bracket, with where it starts. */
    private fun pillCalls(text: String): List<Pair<Int, String>> =
        Regex("(?<![\\w.])PillButton\\(").findAll(text)
            .filter { match -> !text.substring(maxOf(0, match.range.first - 4), match.range.first).endsWith("fun ") }
            .map { match ->
                var depth = 0
                var end = match.range.last
                for (i in match.range.last until text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
                match.range.first to text.substring(match.range.first, end + 1)
            }
            .toList()
}
