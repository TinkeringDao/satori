package com.satori.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The German texts address nobody: the neutral infinitive, no du and no Sie (ROUND3_SPEC.md
 * section 2). Every string and plural form of every German strings file is read, but the model
 * prompts (prompt_*), which talk to the AI. A capital Sie or Ihr form that starts a sentence reads
 * as the formal "you" even where it means "they" ("Sie sind älter als 30 Tage", of recordings),
 * so none may start one; any form of du is the informal "you" wherever it stands.
 */
class GermanAddressTest {

    private val formalAtStart = Regex("(?:^|[.!?:;]\\s+|[„\"(]\\s*)(Sie|Ihr|Ihre|Ihren|Ihrem|Ihrer|Ihres|Ihnen)\\b")
    private val informal = Regex(
        "\\b(du|Du|dich|Dich|dir|Dir|dein|Dein|deine|Deine|deinen|Deinen|deinem|Deinem|deiner|Deiner|deines|Deines)\\b"
    )

    private fun germanTexts(): Map<String, String> =
        StringsFiles.all("values-de").filterKeys { !it.substringAfter('/').startsWith("prompt_") }

    @Test
    fun `no German text starts a sentence with Sie or Ihr`() {
        val found = germanTexts().filterValues { formalAtStart.containsMatchIn(it) }
        assertTrue("$found", found.isEmpty())
    }

    @Test
    fun `no German text uses du`() {
        val found = germanTexts().filterValues { informal.containsMatchIn(it) }
        assertTrue("$found", found.isEmpty())
    }

    @Test
    fun `the checks find what they are for, and leave a small sie in a sentence alone`() {
        assertTrue(formalAtStart.containsMatchIn("Sie sind älter als 30 Tage."))
        assertTrue(formalAtStart.containsMatchIn("Gelöscht. Ihre Aufnahmen bleiben."))
        assertTrue(informal.containsMatchIn("Tippe, wenn du fertig bist."))
        assertFalse(formalAtStart.containsMatchIn("Transkripte bleiben, aber sie lassen sich nicht abspielen."))
        assertFalse(formalAtStart.containsMatchIn("Aufnahmen dieser Kategorie behalten ihre Notizen."))
        assertFalse(informal.containsMatchIn("Durch den Dunst"))
    }

    @Test
    fun `the strings that read as Sie say it without a pronoun now`() {
        val texts = StringsFiles.all("values-de")
        for (key in listOf(
            "strings_grille_retention.xml/g_audio_delete_now_body:one",
            "strings_grille_retention.xml/g_audio_delete_now_body:other",
            "strings_grille_categories.xml/g_cat_delete_body",
            "strings_grille_home.xml/g_todo_delete_body"
        )) {
            val text = texts.getValue(key)
            assertFalse("$key: $text", formalAtStart.containsMatchIn(text))
        }
        // The English audio question follows the days only, so one recording is no "they".
        val english = StringsFiles.all("values")
        assertTrue(english.getValue("strings_grille_retention.xml/g_audio_delete_now_body:one").startsWith("Audio older than %1\$d day "))
    }
}
