package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuickKeyAdbSetupTest {

    private val commands = QuickKeyAdbSetup.commands(
        applicationId = "com.satori",
        activityClass = "com.satori.quickkey.QuickKeyActivity",
        action = "com.satori.action.QUICK_KEY"
    )

    @Test
    fun `the commands are the ones in the quick key document`() {
        assertEquals(documentedCommands(), commands)
    }

    @Test
    fun `the text is plain ASCII, so it pastes into a terminal as it is`() {
        assertTrue(commands.all { it.code in 0x20..0x7E || it == '\n' })
    }

    @Test
    fun `the intent names the action and the component`() {
        val other = QuickKeyAdbSetup.commands("org.example.app", "org.example.app.Quick", "org.example.QUICK")

        assertTrue("action=org.example.QUICK;" in other)
        assertTrue("component=org.example.app/org.example.app.Quick;end" in other)
    }

    @Test
    fun `there is one command per line`() {
        assertEquals(3, commands.lines().filter { it.isNotBlank() }.size)
        assertTrue(commands.lines().filter { it.isNotBlank() }.all { it.startsWith("adb shell ") })
    }

    /**
     * The lines of the first code block under "Bind it with adb" in the document, read from the
     * file so the document and the generated text cannot drift apart. Gradle runs a module's tests
     * in the module's directory. The "# check" after the last line is the document's remark, not
     * part of the command.
     */
    private fun documentedCommands(): String {
        val document = File(DOCUMENT).readText()
        val block = document.substringAfter(HEADING, missingDelimiterValue = "")
            .substringAfter("```sh\n", missingDelimiterValue = "")
            .substringBefore("```")
        check(block.isNotBlank()) { "$DOCUMENT has no code block under \"$HEADING\"" }
        return block.lines()
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n", postfix = "\n") { it.replace(TRAILING_REMARK, "") }
    }

    private companion object {
        const val DOCUMENT = "../docs/QUICK_KEY.md"
        const val HEADING = "## Bind it with adb"
        val TRAILING_REMARK = Regex("\\s+# .*$")
    }
}
