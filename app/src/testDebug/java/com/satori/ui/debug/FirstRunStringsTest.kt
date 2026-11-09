package com.satori.ui.debug

import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.ui.R
import com.satori.ui.screens.CopyRes
import com.satori.ui.screens.FirstRunCopy
import com.satori.ui.screens.firstRunCopy
import com.satori.ui.screens.modelSizeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The first run's words against the design's limits (ONBOARDING_DESIGN_ADHD.md 5.1): per setup
 * state of the gallery, at most 30 words of text besides the title and the controls (36 in
 * German), titles of at most 4 words, controls of at most 3 words and 22 characters (A4);
 * sentences of at most 12 words (14 in German, A5); no jargon and no blame (A6); German without du
 * or Sie, and the same keys in both languages (A7); and nothing of what the first run leaves out
 * (A13). A state's words are what [firstRunCopy] gives it, which the screens draw.
 */
class FirstRunStringsTest {

    private val firstRun = "strings_grille_firstrun.xml"
    private val strip = "strings_grille_strip.xml"

    private enum class Language(val folder: String, val locale: Locale, val bodyWords: Int, val sentenceWords: Int) {
        EN("values", Locale.ENGLISH, bodyWords = 30, sentenceWords = 12),
        DE("values-de", Locale.GERMAN, bodyWords = 36, sentenceWords = 14)
    }

    @Test
    fun `every setup state has at most 30 words besides its title and controls, 36 in German`() {
        for (language in Language.entries) {
            for ((name, state) in FirstRunGallery.setup) {
                val words = body(copy(state, language)).sumOf { words(text(it, language)).size }
                assertTrue("$name $language: $words words", words <= language.bodyWords)
            }
        }
    }

    @Test
    fun `titles have at most 4 words`() {
        for (language in Language.entries) {
            for ((name, state) in FirstRunGallery.setup) {
                val title = copy(state, language).title?.let { text(it, language) } ?: continue
                assertTrue("$name $language: \"$title\"", words(title).size <= 4)
            }
        }
    }

    @Test
    fun `controls have at most 3 words and 22 characters`() {
        for (language in Language.entries) {
            for ((name, state) in FirstRunGallery.setup) {
                val copy = copy(state, language)
                for (control in listOfNotNull(copy.primary, copy.quiet, copy.outline)) {
                    val label = text(control, language)
                    assertTrue("$name $language: \"$label\"", label.split(' ', '\u00A0').size <= 3 && label.length <= 22)
                }
            }
        }
    }

    @Test
    fun `every sentence has at most 12 words, 14 in German`() {
        for (language in Language.entries) {
            for (file in listOf(firstRun, strip)) {
                for ((name, text) in strings(language.folder, file)) {
                    for (sentence in text.split(SENTENCE_END)) {
                        val count = sentence.split(WHITESPACE).count { it.isNotBlank() }
                        assertTrue("$name $language: \"$sentence\"", count <= language.sentenceWords)
                    }
                }
            }
        }
    }

    @Test
    fun `no jargon and no blame`() {
        val jargon = setOf(
            "int8", "gguf", "llm", "token", "tokens", "vad", "rtf", "inference", "checksum", "sha", "weights",
            "gemma", "parakeet", "indicconformer", "ai4bharat", "stt", "adb", "computer"
        )
        val blame = listOf("forgot", "missed", "you failed", "your fault", "should have", "vergessen", "versäumt", "verpasst")
        for (language in Language.entries) {
            for (file in listOf(firstRun, strip)) {
                for ((name, text) in strings(language.folder, file)) {
                    val tokens = text.split(NOT_A_WORD).filter { it.isNotEmpty() }
                    assertTrue("$name: jargon in \"$text\"", tokens.none { it.lowercase() in jargon || it.lowercase().startsWith("quantiz") })
                    // Malayalam is named in full, never as ML.
                    assertTrue("$name: \"ML\" in \"$text\"", "ML" !in tokens)
                    assertTrue("$name: blame in \"$text\"", blame.none { text.lowercase().contains(it) })
                }
            }
        }
    }

    @Test
    fun `German says it without du or Sie, and both languages have the same keys`() {
        val addressed = setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre")
        for (file in listOf(firstRun, strip)) {
            for ((name, text) in strings(Language.DE.folder, file)) {
                assertTrue("$name: \"$text\"", text.split(NOT_A_WORD).none { it in addressed })
            }
            assertEquals(file, strings(Language.EN.folder, file).keys, strings(Language.DE.folder, file).keys)
        }
    }

    @Test
    fun `the setup says nothing of what it leaves for later`() {
        val leftOut = listOf(
            "E2B", "3.3", "3,3", "bigger", "größer", "power key", "Ein-/Aus-Taste", "adb", "computer",
            "notes language", "Sprache der Notizen"
        )
        for (language in Language.entries) {
            for ((name, text) in strings(language.folder, firstRun)) {
                assertTrue("$name: \"$text\"", leftOut.none { text.contains(it, ignoreCase = true) })
                for (sentence in text.split(SENTENCE_END)) {
                    val both = sentence.contains("Malayalam") && (sentence.contains("English") || sentence.contains("Englisch"))
                    assertTrue("$name: \"$sentence\"", !both)
                }
            }
        }
    }

    private fun copy(state: FirstRunUiState, language: Language): FirstRunCopy =
        firstRunCopy(state) { modelSizeLabel(it, language.locale) }

    /** Every line of the content but the title and the controls, and the lines over the primary. */
    private fun body(copy: FirstRunCopy): List<CopyRes> =
        listOfNotNull(copy.intro, copy.body, copy.network, copy.part, copy.status) +
            copy.rows.flatMap { listOfNotNull(it.label, it.caption) } + copy.notes + copy.caption

    /** The words a reader reads in [text]: not the numbers, the units or the marks between them. */
    private fun words(text: String): List<String> = text.split(WHITESPACE)
        .map { it.trim { char -> !char.isLetterOrDigit() && char != '%' && char != '-' } }
        .filter { it.isNotEmpty() && !it.matches(NUMBER) && it !in UNITS }

    /** The text [res] says in [language], formatted with its values. */
    private fun text(res: CopyRes, language: Language): String {
        val name = resourceNames.getValue(res.id)
        val text = allStrings(language.folder).getValue(name)
        return if (res.args.isEmpty()) text else String.format(language.locale, text, *res.args.toTypedArray())
    }

    private val resourceNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val stringsByFolder = mutableMapOf<String, Map<String, String>>()

    /** Every string of the app in res/[folder], by name. */
    private fun allStrings(folder: String): Map<String, String> = stringsByFolder.getOrPut(folder) {
        File("src/main/res/$folder").listFiles { file -> file.name.startsWith("strings") }.orEmpty()
            .flatMap { strings(folder, it.name).entries }
            .associate { it.key to it.value }
    }

    /**
     * The strings of res/[folder]/[file] by name, with their escapes read as aapt2 reads them.
     * Gradle runs the tests in the module's folder.
     */
    private fun strings(folder: String, file: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate {
            it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\u00A0", "\u00A0")
        }
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\u00A0]+")
        val SENTENCE_END = Regex("(?<=[.!?])\\s+")
        val NOT_A_WORD = Regex("[^\\p{L}\\p{N}-]+")
        val NUMBER = Regex("[0-9][0-9.,]*%?")
        val UNITS = setOf("MB", "GB", "KB", "%", "min", "Min")
    }
}
