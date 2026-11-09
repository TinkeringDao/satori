package com.satori.ui.screens

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.presentation.firstrun.StripHold
import com.satori.presentation.firstrun.StripState
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What home's status strip says in each of its states (design 3.8, 3.9 and 4.1), slot by slot, and
 * that every state keeps to the first run's limits (design 5.1): at most 30 words besides the title
 * and the pills (36 in German), titles of at most 4 words, pills of at most 3 words and 22
 * characters (A4).
 */
class StripCopyTest {

    private val aiBytes = 1_069_306_368L
    private val running = StripState.Download(FirstRunPart.AI_MODEL, bytesDone = 246_000_000L, bytesTotal = aiBytes, minutesLeft = 3)
    private val space = SpaceShortfall(neededBytes = 1_369_306_368L, freeBytes = 900_000_000L)

    private fun copy(strip: StripState, locale: Locale = Locale.ENGLISH): StripCopy =
        checkNotNull(stripCopy(strip) { modelSizeLabel(it, locale) }) { "$strip" }

    /** Every state the strip can be in: each download, held for each reason, and the rest. */
    private val states: List<StripState> =
        FirstRunPart.entries.filterNot { it == FirstRunPart.SPEECH }.map { running.copy(part = it) } +
            listOf(running.copy(minutesLeft = 1), running.copy(minutesLeft = null, checking = true), running.copy(minutesLeft = null, resumedFromBytes = 412_000_000L)) +
            StripHold.entries.map { hold ->
                running.copy(minutesLeft = null, hold = hold, space = space.takeIf { hold == StripHold.NOT_ENOUGH_SPACE })
            } +
            listOf(
                StripState.MalayalamFailed, StripState.FirstNotes("first"), StripState.Nudge(NudgeId.NOTIFY_PING),
                StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true), StripState.Nudge(NudgeId.E2B_OFFER)
            )

    @Test
    fun `the AI model downloading says its name, its percent and the time left`() {
        val copy = copy(running)
        assertEquals(CopyRes(R.string.g_strip_ai_model), copy.title)
        assertEquals(23, copy.percent)
        assertEquals(listOf(StripLine(CopyRes(R.string.g_fr_time_left, listOf(3)))), copy.lines)
        assertEquals(emptyList<StripPill>(), copy.pills)
    }

    @Test
    fun `the last minute, a file being checked and a resume say so, as on Getting ready`() {
        assertEquals(CopyRes(R.string.g_fr_time_under), copy(running.copy(minutesLeft = 1)).lines.single().text)
        assertEquals(CopyRes(R.string.g_fr_checking), copy(running.copy(checking = true)).lines.single().text)
        assertEquals(CopyRes(R.string.g_onb_resuming, listOf(412)), copy(running.copy(minutesLeft = null, resumedFromBytes = 412_000_000L)).lines.single().text)
        assertEquals(emptyList<StripLine>(), copy(running.copy(minutesLeft = null)).lines)
    }

    @Test
    fun `while a recording runs it says it is paused, with nothing to tap`() {
        val copy = copy(running.copy(minutesLeft = null, hold = StripHold.RECORDING))
        assertEquals(listOf(StripLine(CopyRes(R.string.g_strip_paused_recording))), copy.lines)
        assertEquals(emptyList<StripPill>(), copy.pills)
    }

    @Test
    fun `each hold says Getting ready's words, with its one action`() {
        fun held(hold: StripHold) = copy(running.copy(minutesLeft = null, hold = hold, space = space.takeIf { hold == StripHold.NOT_ENOUGH_SPACE }))

        val wifi = held(StripHold.WAITING_FOR_WIFI)
        assertEquals(
            listOf(StripLine(CopyRes(R.string.g_fr_err_wifi_title), ink = true), StripLine(CopyRes(R.string.g_fr_err_wifi_body, listOf("823\u00A0MB")))),
            wifi.lines
        )
        assertEquals(listOf(StripPill(CopyRes(R.string.g_fr_use_mobile), StripTap.UseMobileData(FirstRunPart.AI_MODEL))), wifi.pills)

        assertEquals(StripPill(CopyRes(R.string.g_fr_try_now), StripTap.Retry(FirstRunPart.AI_MODEL), quiet = true), held(StripHold.NO_CONNECTION).pills.single())
        assertEquals(StripTap.Retry(FirstRunPart.AI_MODEL), held(StripHold.BLOCKED).pills.single().tap)
        assertEquals(CopyRes(R.string.g_fr_err_blocked_body), held(StripHold.BLOCKED).lines[1].text)
        assertEquals(StripTap.Retry(FirstRunPart.AI_MODEL), held(StripHold.DAMAGED).pills.single().tap)
        assertEquals(CopyRes(R.string.g_fr_space_body, listOf("1.4\u00A0GB", "900\u00A0MB")), held(StripHold.NOT_ENOUGH_SPACE).lines[1].text)
        assertEquals(StripTap.OpenStorageSettings, held(StripHold.NOT_ENOUGH_SPACE).pills.single().tap)
        assertEquals(StripTap.Resume, held(StripHold.PAUSED).pills.single().tap)
        for (byItself in listOf(StripHold.SERVER, StripHold.DAMAGED_ONCE, StripHold.RECORDING)) {
            assertEquals("$byItself", emptyList<StripPill>(), held(byItself).pills)
        }
        // What only he can let go on is said in ink; what goes on by itself in ash.
        for (hold in StripHold.entries) assertEquals("$hold", hold.needsHim, held(hold).lines.first().ink)
    }

    @Test
    fun `the bigger AI model and Malayalam tried again are named as such`() {
        assertEquals(CopyRes(R.string.g_strip_bigger_model), copy(running.copy(part = FirstRunPart.BIGGER_AI_MODEL)).title)
        assertEquals(CopyRes(R.string.g_fr_part_malayalam), copy(running.copy(part = FirstRunPart.MALAYALAM)).title)
    }

    @Test
    fun `Malayalam dropped, the first notes and the ping each say one sentence, with their pills`() {
        val malayalam = copy(StripState.MalayalamFailed)
        assertNull(malayalam.title)
        assertEquals(listOf(StripLine(CopyRes(R.string.g_strip_malayalam_failed), ink = true)), malayalam.lines)
        assertEquals(listOf(StripPill(CopyRes(R.string.g_onb_retry), StripTap.RetryMalayalam)), malayalam.pills)

        val notes = copy(StripState.FirstNotes("first"))
        assertEquals(listOf(StripLine(CopyRes(R.string.g_strip_first_notes), ink = true)), notes.lines)
        assertEquals(listOf(StripPill(CopyRes(R.string.g_strip_open), StripTap.OpenFirstNotes("first"))), notes.pills)

        val ping = copy(StripState.Nudge(NudgeId.NOTIFY_PING))
        assertEquals(listOf(StripLine(CopyRes(R.string.g_nudge_ping), ink = true)), ping.lines)
        assertEquals(
            listOf(
                StripPill(CopyRes(R.string.g_todo_notify_allow), StripTap.Answer(NudgeId.NOTIFY_PING, accepted = true)),
                StripPill(CopyRes(R.string.g_todo_notify_later), StripTap.Answer(NudgeId.NOTIFY_PING, accepted = false))
            ),
            ping.pills
        )
    }

    @Test
    fun `the ping for notes no notification could tell of says why in ash, then asks in ink, with the same pills`() {
        val missed = copy(StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true))
        assertNull(missed.title)
        assertEquals(
            listOf(StripLine(CopyRes(R.string.g_nudge_ping_missed)), StripLine(CopyRes(R.string.g_nudge_ping), ink = true)),
            missed.lines
        )
        assertEquals(copy(StripState.Nudge(NudgeId.NOTIFY_PING)).pills, missed.pills)
        assertEquals(
            "Your notes were ready, but no ping came. Satori isn't allowed to show notifications.",
            text(CopyRes(R.string.g_nudge_ping_missed), Language.EN)
        )
        assertEquals(
            "Die Notizen waren fertig, aber es kam kein Hinweis. Satori darf keine Benachrichtigungen anzeigen.",
            text(CopyRes(R.string.g_nudge_ping_missed), Language.DE)
        )
    }

    @Test
    fun `the bigger model's offer says its size from the registry, 3,3 GB in German`() {
        val offer = copy(StripState.Nudge(NudgeId.E2B_OFFER))
        assertEquals(CopyRes(R.string.g_nudge_e2b_title), offer.title)
        assertEquals(CopyRes(R.string.g_nudge_e2b_body, listOf("3.3\u00A0GB")), offer.lines.single().text)
        assertEquals(CopyRes(R.string.g_nudge_e2b_body, listOf("3,3\u00A0GB")), copy(StripState.Nudge(NudgeId.E2B_OFFER), Locale.GERMAN).lines.single().text)
        assertEquals(listOf(true, false), offer.pills.map { (it.tap as StripTap.Answer).accepted })
    }

    @Test
    fun `what is never a strip of its own draws nothing`() {
        for (id in listOf(NudgeId.FIRST_NOTES, NudgeId.RETENTION)) {
            assertNull(stripCopy(StripState.Nudge(id)) { "" })
        }
    }

    @Test
    fun `every state has at most 30 words besides its title and pills, 36 in German`() {
        for (language in Language.entries) {
            for (strip in states) {
                val copy = copy(strip, language.locale)
                val words = copy.lines.sumOf { words(text(it.text, language)).size }
                assertTrue("$strip $language: $words words", words <= language.bodyWords)
            }
        }
    }

    @Test
    fun `titles, a hold's among them, have at most 4 words`() {
        for (language in Language.entries) {
            for (strip in states) {
                val copy = copy(strip, language.locale)
                val holdTitle = copy.lines.firstOrNull().takeIf { (strip as? StripState.Download)?.hold != null }?.text
                for (title in listOfNotNull(copy.title, holdTitle)) {
                    val said = text(title, language)
                    assertTrue("$strip $language: \"$said\"", words(said).size <= 4)
                }
            }
        }
    }

    @Test
    fun `pills have at most 3 words and 22 characters`() {
        for (language in Language.entries) {
            for (strip in states) {
                for (pill in copy(strip, language.locale).pills) {
                    val label = text(pill.label, language)
                    assertTrue("$strip $language: \"$label\"", label.split(' ', '\u00A0').size <= 3 && label.length <= 22)
                }
            }
        }
    }

    private enum class Language(val folder: String, val locale: Locale, val bodyWords: Int) {
        EN("values", Locale.ENGLISH, bodyWords = 30),
        DE("values-de", Locale.GERMAN, bodyWords = 36)
    }

    /** The words a reader reads in [text]: not the numbers, the units or the marks between them. */
    private fun words(text: String): List<String> = text.split(WHITESPACE)
        .map { it.trim { char -> !char.isLetterOrDigit() && char != '%' && char != '-' } }
        .filter { it.isNotEmpty() && !it.matches(NUMBER) && it !in UNITS }

    /** The text [res] says in [language], formatted with its values. */
    private fun text(res: CopyRes, language: Language): String {
        val name = resourceNames.getValue(res.id)
        val text = strings(language.folder).getValue(name)
        return if (res.args.isEmpty()) text else String.format(language.locale, text, *res.args.toTypedArray())
    }

    private val resourceNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val stringsByFolder = mutableMapOf<String, Map<String, String>>()

    /** Every string of the app in res/[folder], by name, with its escapes read as aapt2 reads them. Gradle runs the tests in the module's folder. */
    private fun strings(folder: String): Map<String, String> = stringsByFolder.getOrPut(folder) {
        File("src/main/res/$folder").listFiles { file -> file.name.startsWith("strings") }.orEmpty().flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) as Element }.map {
                it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\u00A0", "\u00A0")
            }
        }.toMap()
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\u00A0]+")
        val NUMBER = Regex("[0-9][0-9.,]*%?")
        val UNITS = setOf("MB", "GB", "KB", "%", "min", "Min")
    }
}
