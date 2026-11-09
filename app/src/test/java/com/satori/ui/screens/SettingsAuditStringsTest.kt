package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of the settings audit's fixes (SETTINGS_AUDIT_RESULT.md, 2026-10-15), in English and
 * German: each says what is true now, and the German addresses nobody (GermanAddressTest).
 */
class SettingsAuditStringsTest {

    private val en = StringsFiles.all("values")
    private val de = StringsFiles.all("values-de")

    private fun en(key: String) = en.getValue(key)
    private fun de(key: String) = de.getValue(key)

    @Test
    fun `AI for finished recordings says what on, off and short memory do`() {
        assertEquals(
            "Gemma 4 E2B writes the notes, name and to-dos after Stop: better, but slower. Off: the model chosen above does it. " +
                "When memory is short, that model does it anyway, or the work waits until a recording ends.",
            en("strings_grille_settings.xml/g_settings_finishing_model_desc")
        )
        assertTrue(de("strings_grille_settings.xml/g_settings_finishing_model_desc").startsWith("Gemma 4 E2B schreibt nach dem Stoppen"))
    }

    @Test
    fun `the AI model line says which model writes while recording and that E2B can't be picked`() {
        assertEquals(
            "The model picked here writes the notes while recording. Gemma 4 E2B can't be picked: it works after Stop, with the switch below.",
            en("strings_grille_settings.xml/g_settings_ai_model_help")
        )
        assertEquals(
            "A model for live notes isn't on this phone yet; Gemma 4 E2B needs one.",
            en("strings_grille_settings.xml/g_settings_ai_notes_missing_e2b")
        )
        assertEquals("Noch kein Modell für Live-Notizen auf diesem Telefon; Gemma 4 E2B braucht eines.", de("strings_grille_settings.xml/g_settings_ai_notes_missing_e2b"))
        // One model downloads at a time: "heruntergeladen", as in English, not "geladen".
        assertTrue("heruntergeladen" in de("strings_grille_settings.xml/g_settings_ai_model_busy"))
    }

    @Test
    fun `the Efficient model's line claims no accuracy nothing measured`() {
        // The stash's "a little less accurate" had no source (the audit's section 5): no comparison
        // of the Efficient model's notes exists.
        assertEquals("Gemma 3 1B. Easier on memory and battery. About %1\$s.", en("strings_grille_settings.xml/g_settings_model_iq4_desc"))
        assertEquals("Gemma 3 1B. Schont Arbeitsspeicher und Akku. Etwa %1\$s.", de("strings_grille_settings.xml/g_settings_model_iq4_desc"))
    }

    @Test
    fun `voice detection and the words to recognize say where they don't apply`() {
        assertTrue("Transcribe again uses its own values" in en("strings_grille_settings.xml/g_settings_vad_desc"))
        assertTrue("„Neu transkribieren“ nutzt eigene Werte" in de("strings_grille_settings.xml/g_settings_vad_desc"))
        assertTrue(en("strings_grille_speech.xml/g_speech_words_help").endsWith("The other speech models do not, so a better transcript after Stop does not use them."))
        assertTrue("Die anderen Spracherkennungsmodelle tun das nicht" in de("strings_grille_speech.xml/g_speech_words_help"))
        assertEquals("Shorter", en("strings_grille_settings.xml/g_settings_vad_shorter"))
        assertEquals("Länger", de("strings_grille_settings.xml/g_settings_vad_longer"))
    }

    @Test
    fun `the better transcript after Stop says it is for a language the model knows`() {
        for (quantity in listOf("one", "other")) {
            assertTrue("Only for recordings in a language the model knows." in en("strings_grille_speech.xml/g_speech_after_stop_help:$quantity"))
            assertTrue("Nur für Aufnahmen in einer Sprache, die das Modell kennt." in de("strings_grille_speech.xml/g_speech_after_stop_help:$quantity"))
        }
        // Parakeet v2 does English only: not "each recording".
        assertEquals("Transcribes recordings again after Stop, for fewer errors. The notes wait for it.", en("strings_grille_speech.xml/g_speech_after_stop_desc"))
    }

    @Test
    fun `Parakeet v3 is offered again only as a repair, and delete questions say what stops`() {
        assertEquals("Damaged", en("strings_grille_settings.xml/g_settings_speech_damaged"))
        assertEquals("Beschädigt", de("strings_grille_settings.xml/g_settings_speech_damaged"))
        assertEquals("Use this if recording stops working. It needs an internet connection.", en("strings_grille_settings.xml/g_settings_speech_again_body"))
        assertFalse(en.containsKey("strings_grille_speech.xml/g_speech_delete_live_body"))
        assertEquals(
            "Transcripts it made stay. Until it is back, the power key and Record as record in English or German.",
            en("strings_grille_speech.xml/g_speech_delete_ml_body")
        )
        assertEquals(
            "Notes you already have stay. Until a model is back, new recordings get no notes.",
            en("strings_grille_settings.xml/g_settings_model_delete_last_body")
        )
        assertTrue(de("strings_grille_settings.xml/g_settings_model_delete_last_body").endsWith("entstehen zu neuen Aufnahmen keine Notizen."))
    }

    @Test
    fun `the microphone sheet says when a choice applies`() {
        assertEquals("Applies from the next recording.", en("strings_grille_speech.xml/g_settings_mic_input_help"))
        assertEquals("Gilt ab der nächsten Aufnahme.", de("strings_grille_speech.xml/g_settings_mic_input_help"))
    }

    @Test
    fun `the categories say every recording gets to-dos`() {
        assertEquals("Short notes and to-dos", en("strings_grille.xml/g_mode_note_desc"))
        assertEquals("Decisions, facts and to-dos", en("strings_grille.xml/g_mode_meeting_desc"))
        assertEquals("Kurze Notizen und Aufgaben", de("strings_grille.xml/g_mode_note_desc"))
        assertEquals("Entscheidungen, Fakten und Aufgaben", de("strings_grille.xml/g_mode_meeting_desc"))
    }

    @Test
    fun `Android's notification page names the channels as the app speaks`() {
        assertEquals("Recording", en("strings.xml/notification_channel_recording_name"))
        assertEquals("Finishing recordings", en("strings.xml/notification_channel_llm_processing_name"))
        // The download service's channel is named with its notification's words (strings_grille_download.xml).
        assertEquals("Downloads", en("strings_grille_download.xml/g_dl_channel"))
        assertEquals("Shows model downloads", en("strings_grille_download.xml/g_dl_channel_desc"))
        assertEquals("Zeigt Modell-Downloads", de("strings_grille_download.xml/g_dl_channel_desc"))
        assertEquals("Aufnahme", de("strings.xml/notification_channel_recording_name"))
        assertEquals("Aufnahmen abschließen", de("strings.xml/notification_channel_llm_processing_name"))
        for (texts in listOf(en, de)) {
            val channels = texts.filterKeys {
                it.startsWith("strings.xml/notification_channel_") || it.startsWith("strings_grille_download.xml/g_dl_channel")
            }.values
            assertFalse("$channels", channels.any { "AI Processing" in it || "Session" in it || "analyz" in it || "Sitzung" in it })
        }
    }

    @Test
    fun `Save the place says the switch is on while no place is saved, and names Android's settings`() {
        assertTrue(en("strings_grille_place.xml/g_place_location_off").startsWith("On, but location is off"))
        assertTrue(de("strings_grille_place.xml/g_place_location_off").startsWith("An, aber der Standort ist"))
        assertEquals("Open Android settings", en("strings_grille_place.xml/g_place_open_android_settings"))
        assertEquals("Android-Einstellungen öffnen", de("strings_grille_place.xml/g_place_open_android_settings"))
    }

    @Test
    fun `the screen kept on, the AI model's screen and Translate say what is true`() {
        assertTrue("While the phone is locked the screen times out as usual." in en("strings_grille_settings.xml/g_settings_keep_screen_on_desc"))
        assertTrue("Bei gesperrtem Telefon geht der Bildschirm wie gewohnt aus." in de("strings_grille_settings.xml/g_settings_keep_screen_on_desc"))
        assertEquals("Transcription works without it.", en("strings_grille_settings.xml/g_settings_llm_works_without"))
        assertEquals("Die Transkription funktioniert auch ohne.", de("strings_grille_settings.xml/g_settings_llm_works_without"))
        assertTrue(en("strings_grille_settings.xml/g_settings_translate_into_desc").endsWith("Translate reads English or German speech."))
        assertTrue(de("strings_grille_settings.xml/g_settings_translate_into_desc").endsWith("„Übersetzen“ versteht Englisch und Deutsch."))
        // The recorder no longer promises each sentence at once: translations come at the interval set.
        assertEquals("Finished sentences are translated here, a few at a time.", en("strings_grille_recorder.xml/g_rec_translation_live"))
        // The German prompt translates "auf Englisch", with the name in German (getTranslationTargetName).
        assertTrue("den folgenden Text auf {target_language} übersetzen" in de("strings_grille_ai.xml/prompt_translation"))
    }

    @Test
    fun `the Power key row set up says nothing about a setup`() {
        val setupWords = listOf("setup", "set up", "adb", "computer", "Einrichtung", "eingerichtet", "Computer")
        for (key in listOf("strings_grille_quickkey.xml/g_quickkey_record_as", "strings_grille_quickkey.xml/g_quickkey_record_as_in")) {
            for (text in listOf(en(key), de(key))) {
                assertFalse(text, setupWords.any { it in text })
            }
        }
        assertEquals("Two presses start a recording as %1\$s", en("strings_grille_quickkey.xml/g_quickkey_record_as"))
        assertEquals("Not set up yet", en("strings_grille_quickkey.xml/g_quickkey_row_not_set_up"))
        assertEquals("Opens another app now", en("strings_grille_quickkey.xml/g_quickkey_row_other_app"))
        assertEquals("Öffnet gerade eine andere App", de("strings_grille_quickkey.xml/g_quickkey_row_other_app"))
        assertEquals("Record as %1\$s", en("strings_grille_quickkey.xml/g_quickkey_row_unknown"))
    }

    @Test
    fun `the strings nothing used are gone`() {
        for (texts in listOf(en, de)) {
            for (name in listOf("g_field_target", "g_timing_live", "g_timing_after")) {
                assertFalse(name, texts.containsKey("strings_grille.xml/$name"))
            }
        }
    }

    @Test
    fun `the Malayalam link of the language row says where it goes`() {
        assertEquals("Download Malayalam in Advanced settings", en("strings_grille_language.xml/g_language_get_ml"))
    }
}
