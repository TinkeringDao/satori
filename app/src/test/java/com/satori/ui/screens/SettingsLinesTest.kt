package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every row of Settings and Advanced settings says what it changes, and every choice of their
 * sheets what picking it means (the owner, 2026-10-14): the lines exist in English and German,
 * German in the neutral infinitive style without du or Sie, both without the words only a
 * developer knows, and with the same placeholders.
 */
class SettingsLinesTest {

    /** The line under each row of Settings, by its strings file. */
    private val settingsRows = mapOf(
        "strings_grille_settings.xml" to listOf(
            "g_settings_ai_notes_desc",
            "g_settings_notes_language_desc",
            "g_settings_categories_desc",
            "g_settings_theme_desc",
            "g_settings_advanced_desc",
            "g_settings_vibrate_desc",
            "g_settings_keep_screen_on_desc",
            "g_settings_licences_desc"
        ),
        "strings_grille_quickkey.xml" to listOf("g_quickkey_record_as", "g_quickkey_record_as_in"),
        "strings_grille_audio.xml" to listOf("g_audio_keep_desc"),
        "strings_grille_retention.xml" to listOf("g_audio_compress_desc", "g_audio_delete_older_desc"),
        "strings_grille_place.xml" to listOf("g_place_save_desc")
    )

    /** The line under each row of Advanced settings, by its strings file. */
    private val advancedRows = mapOf(
        "strings_grille_settings.xml" to listOf(
            "g_settings_model_q8_desc",
            "g_settings_model_iq4_desc",
            "g_settings_model_qwen_desc",
            "g_settings_model_e2b_desc",
            "g_settings_finishing_model_desc",
            "g_settings_interval_desc",
            "g_settings_interval_translate_desc",
            "g_settings_strategy_desc",
            "g_settings_strategy_translate_desc",
            "g_settings_translate_into_desc",
            "g_settings_vad_silence_desc",
            "g_settings_vad_speech_desc",
            "g_settings_vad_threshold_desc"
        ),
        "strings_grille_speech.xml" to listOf(
            "g_speech_v3_desc",
            "g_speech_v2_desc",
            "g_speech_cohere_desc",
            "g_speech_ml_desc",
            "g_speech_after_stop_desc",
            "g_speech_words_desc",
            "g_settings_mic_input_desc"
        )
    )

    /** The line under each choice of the sheets, by its strings file. */
    private val choiceLines = mapOf(
        "strings_grille_settings.xml" to listOf(
            "g_settings_notes_language_auto_desc",
            "g_settings_notes_language_en_desc",
            "g_settings_notes_language_de_desc",
            "g_settings_theme_system_desc",
            "g_settings_theme_light_desc",
            "g_settings_theme_dark_desc"
        ),
        "strings_grille_speech.xml" to listOf(
            "g_speech_after_stop_off_desc",
            "g_speech_after_stop_v2_desc",
            "g_speech_after_stop_cohere_desc",
            "g_settings_mic_usual_desc",
            "g_settings_mic_recognition_desc",
            "g_settings_mic_calls_desc"
        ),
        "strings_grille_retention.xml" to listOf("g_audio_compress_never_desc", "g_audio_delete_never_desc"),
        "strings_grille_quickkey.xml" to listOf("g_quickkey_stop_desc", "g_quickkey_language_desc")
    )

    /** The lines of the audio ages, one per number of days. */
    private val choicePlurals = mapOf(
        "strings_grille_retention.xml" to listOf("g_audio_compress_after_desc", "g_audio_delete_after_desc")
    )

    private val all: Map<String, List<String>> =
        (settingsRows.keys + advancedRows.keys + choiceLines.keys).associateWith { file ->
            settingsRows[file].orEmpty() + advancedRows[file].orEmpty() + choiceLines[file].orEmpty()
        }

    /** The files of the two screens and their sheets, whose every text the owner reads. */
    private val screenFiles = listOf(
        "strings_grille_settings.xml",
        "strings_grille_speech.xml",
        "strings_grille_retention.xml",
        "strings_grille_quickkey.xml",
        "strings_grille_audio.xml",
        "strings_grille_place.xml"
    )

    @Test
    fun `every row and every choice has its line, in English and in German`() {
        for ((file, names) in all) {
            for (name in names) {
                assertTrue("$file $name", text("values", file, name).isNotBlank())
                // A name only in English would show English on a German phone.
                assertTrue("$file $name has no German", text("values-de", file, name).isNotBlank())
            }
        }
        for ((file, names) in choicePlurals) {
            for (name in names) {
                for (folder in listOf("values", "values-de")) {
                    val forms = plural(folder, file, name)
                    assertEquals("$folder $name", setOf("one", "other"), forms.keys)
                    forms.forEach { (quantity, form) -> assertTrue("$folder $name $quantity", "%d" in form) }
                }
            }
        }
    }

    @Test
    fun `the microphone inputs say what picking each means, in the owner's words`() {
        assertEquals("The phone's usual input", text("values", "strings_grille_speech.xml", "g_settings_mic_usual"))
        assertEquals("Recommended: works best on this phone", text("values", "strings_grille_speech.xml", "g_settings_mic_usual_desc"))
        // Offered only if MicSource.OFFERS_SPEECH_RECOGNITION_INPUT says so; then it says what it costs.
        assertEquals("Much quieter on this phone: most words can be lost.", text("values", "strings_grille_speech.xml", "g_settings_mic_recognition_desc"))
        assertEquals("Applies from the next recording.", text("values", "strings_grille_speech.xml", "g_settings_mic_input_help"))
        assertEquals("Gilt ab der nächsten Aufnahme.", text("values-de", "strings_grille_speech.xml", "g_settings_mic_input_help"))
        assertEquals(
            "As in calls: can help in loud places, can cost words in quiet ones",
            text("values", "strings_grille_speech.xml", "g_settings_mic_calls_desc")
        )
        assertEquals("Empfohlen: funktioniert auf diesem Telefon am besten", text("values-de", "strings_grille_speech.xml", "g_settings_mic_usual_desc"))
    }

    @Test
    fun `the lines say what a choice costs where it costs something`() {
        val costs = listOf(
            Triple("strings_grille_settings.xml", "g_settings_ai_notes_desc", "battery and memory"),
            Triple("strings_grille_settings.xml", "g_settings_keep_screen_on_desc", "battery"),
            Triple("strings_grille_settings.xml", "g_settings_interval_desc", "battery"),
            Triple("strings_grille_audio.xml", "g_audio_keep_desc", "2 MB a minute"),
            Triple("strings_grille_speech.xml", "g_speech_cohere_desc", "memory"),
            Triple("strings_grille_place.xml", "g_place_save_desc", "battery")
        )
        for ((file, name, cost) in costs) {
            val line = text("values", file, name)
            assertTrue("$name: $line", cost in line)
        }
    }

    @Test
    fun `German says it without du or Sie`() {
        val informal = setOf("du", "Du", "dich", "Dich", "dein", "Dein", "deine", "Deine", "dir", "Dir")
        val formal = setOf("Sie", "Ihnen", "Ihr", "Ihre", "Ihren", "Ihrem", "Ihrer")
        val lines = all.flatMap { (file, names) -> names.map { name -> file to text("values-de", file, name) } } +
            choicePlurals.flatMap { (file, names) -> names.flatMap { name -> plural("values-de", file, name).values.map { file to it } } }
        for ((file, line) in lines) {
            val words = line.split(Regex("[^\\p{L}]+"))
            assertFalse("$file: $line", words.any { it in informal || it in formal })
        }
        // The other texts of the screens: "Sie" is also "they" there ("Sie sind älter als ...", of
        // recordings), so only the informal forms can be told apart by the word alone.
        for (file in screenFiles) {
            for ((name, text) in texts("values-de", file)) {
                assertFalse("$file $name: $text", text.split(Regex("[^\\p{L}]+")).any { it in informal })
            }
        }
    }

    @Test
    fun `no row or choice uses a word only a developer knows`() {
        val jargon = Regex("\\b(VAD|RTF|int8|tokens?|GGUF|ONNX|sherpa|LLM|CPU|RAM|Q8_0|IQ4_NL|Q4_0)\\b", RegexOption.IGNORE_CASE)
        for (folder in listOf("values", "values-de")) {
            for (file in screenFiles) {
                for ((name, text) in texts(folder, file)) {
                    assertFalse("$folder/$file $name: $text", jargon.containsMatchIn(text))
                }
            }
        }
    }

    /**
     * A Malayalam recording's notes are English, and the app doesn't say so anywhere (the owner,
     * 2026-10-15): no line of the screens names Malayalam together with English or German in a
     * text about notes, and the power key's language line says only what the key records in.
     */
    @Test
    fun `no line says what language a Malayalam recording's notes are in`() {
        val notes = Regex("\\b(notes?|Notizen)\\b", RegexOption.IGNORE_CASE)
        val englishOrGerman = Regex("English|German|Englisch|Deutsch|englisch|deutsch")
        for (folder in listOf("values", "values-de")) {
            for (file in screenFiles) {
                for ((name, text) in texts(folder, file)) {
                    val says = "Malayalam" in text && notes.containsMatchIn(text) && englishOrGerman.containsMatchIn(text)
                    assertFalse("$folder/$file $name: $text", says)
                }
            }
        }
        assertEquals("The language the power key records in.", text("values", "strings_grille_quickkey.xml", "g_quickkey_language_desc"))
        assertEquals("Die Sprache, in der die Ein-/Aus-Taste aufnimmt.", text("values-de", "strings_grille_quickkey.xml", "g_quickkey_language_desc"))
        for (folder in listOf("values", "values-de")) {
            assertFalse(folder, "Malayalam" in text(folder, "strings_grille_settings.xml", "g_settings_notes_language_help"))
        }
    }

    @Test
    fun `German keeps the placeholders of English`() {
        for ((file, names) in all) {
            for (name in names) {
                assertEquals("$file $name", placeholders(text("values", file, name)), placeholders(text("values-de", file, name)))
            }
        }
    }

    private fun placeholders(text: String): List<String> = Regex("%[0-9]+\\$[a-z]|%[a-z]").findAll(text).map { it.value }.sorted().toList()

    private fun elements(folder: String, file: String, tag: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Every string and plural form of [file] in [folder], by name, as aapt2 reads them. */
    private fun texts(folder: String, file: String): Map<String, String> =
        elements(folder, file, "string").associate { it.getAttribute("name") to read(it.textContent) } +
            elements(folder, file, "plurals").flatMap { plurals ->
                val items = plurals.getElementsByTagName("item")
                (0 until items.length).map { i ->
                    val item = items.item(i) as Element
                    "${plurals.getAttribute("name")}:${item.getAttribute("quantity")}" to read(item.textContent)
                }
            }

    /**
     * The text of the string [name] in the app's res/[folder]/[file], with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun text(folder: String, file: String, name: String): String =
        read(elements(folder, file, "string").single { it.getAttribute("name") == name }.textContent)

    /** The forms of the plural [name], by quantity. */
    private fun plural(folder: String, file: String, name: String): Map<String, String> {
        val plurals = elements(folder, file, "plurals").single { it.getAttribute("name") == name }
        val items = plurals.getElementsByTagName("item")
        return (0 until items.length).associate { i ->
            val item = items.item(i) as Element
            item.getAttribute("quantity") to read(item.textContent)
        }
    }

    // A no-break space reads as a space here, so a test can say "2 MB".
    private fun read(raw: String): String = raw.replace("\\'", "'").replace("\\u00A0", " ")
}
