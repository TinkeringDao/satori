package com.satori.ui.screens

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.presentation.firstrun.FirstRunProblem
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.firstrun.SetupProgress
import com.satori.presentation.firstrun.SetupSizes
import com.satori.ui.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The design's limits on the first run's words (ONBOARDING_DESIGN_ADHD.md 5.1, A4) in every state
 * the screens can reach, not only the gallery's: each stage with each problem, each microphone
 * answer, and each line under the bar. At most 30 words besides the title and the controls (36 in
 * German), titles of at most 4 words, controls of at most 3 words and 22 characters. Words are
 * counted as FirstRunStringsTest counts them: not the numbers, the units or the marks between.
 */
class FirstRunCopyLimitsTest {

    private enum class Language(val folder: String, val locale: Locale, val bodyWords: Int) {
        EN("values", Locale.ENGLISH, bodyWords = 30),
        DE("values-de", Locale.GERMAN, bodyWords = 36)
    }

    private val mb = 1_000_000L

    @Test
    fun `every reachable state keeps to the word limits, in both languages`() {
        val states = reachable()
        for (language in Language.entries) {
            for (state in states) {
                val copy = firstRunCopy(state) { modelSizeLabel(it, language.locale) }
                val body = body(copy).sumOf { words(text(it, language)).size }
                assertTrue("$language $body words: $state", body <= language.bodyWords)
                copy.title?.let {
                    val title = text(it, language)
                    assertTrue("$language \"$title\": $state", words(title).size <= 4)
                }
                for (control in listOfNotNull(copy.primary, copy.quiet, copy.outline)) {
                    val label = text(control, language)
                    assertTrue("$language \"$label\": $state", label.split(' ', '\u00A0').size <= 3 && label.length <= 22)
                }
            }
        }
    }

    /** Get ready, Getting ready and Ready, in every combination the state allows. */
    private fun reachable(): List<FirstRunUiState> {
        val sizes = listOf(
            SetupSizes(670_478_772L, 137_566_803L, 1_069_306_368L),
            SetupSizes(670_478_772L, 0L, 0L)
        )
        val space = FirstRunProblem.NotEnoughSpace(SpaceShortfall(neededBytes = 1_108_045_575L, freeBytes = 600 * mb))
        val problems = listOf(
            null, FirstRunProblem.NoConnection, FirstRunProblem.WaitingForWifi, FirstRunProblem.Blocked,
            FirstRunProblem.ServerNotAnswering, FirstRunProblem.Damaged(again = false), FirstRunProblem.Damaged(again = true), space
        )
        val running = SetupProgress(FirstRunPart.SPEECH, bytesDone = 276 * mb, bytesTotal = 808_045_575L)
        val progresses = listOf(null) + listOf(FirstRunPart.SPEECH, FirstRunPart.MALAYALAM).flatMap { part ->
            listOf(null, 1, 2, 14).flatMap { minutes ->
                listOf(
                    running.copy(part = part, minutesLeft = minutes),
                    running.copy(part = part, minutesLeft = minutes, checking = true),
                    running.copy(part = part, minutesLeft = minutes, paused = true),
                    running.copy(part = part, minutesLeft = minutes, resumedFromBytes = 412 * mb)
                )
            }
        }
        val states = mutableListOf<FirstRunUiState>()
        for (size in sizes) {
            for (malayalam in listOf(true, false)) {
                for (mobile in listOf(false, true)) {
                    for (problem in listOf(null, space)) {
                        states += FirstRunUiState(sizes = size, malayalam = malayalam, onMobileData = mobile, problem = problem)
                    }
                }
            }
            for (stage in listOf(FirstRunStage.DOWNLOADING, FirstRunStage.READY)) {
                for (microphone in MicrophoneState.entries) {
                    for (problem in problems) {
                        for (progress in progresses) {
                            states += FirstRunUiState(stage = stage, sizes = size, progress = progress, problem = problem, microphone = microphone)
                        }
                    }
                }
            }
        }
        return states
    }

    /** Every line of the content but the title and the controls, and the lines over the primary. */
    private fun body(copy: FirstRunCopy): List<CopyRes> =
        listOfNotNull(copy.intro, copy.body, copy.network, copy.part, copy.status) +
            copy.rows.flatMap { listOfNotNull(it.label, it.caption) } + copy.notes + copy.caption

    private fun words(text: String): List<String> = text.split(WHITESPACE)
        .map { it.trim { char -> !char.isLetterOrDigit() && char != '%' && char != '-' } }
        .filter { it.isNotEmpty() && !it.matches(NUMBER) && it !in UNITS }

    private fun text(res: CopyRes, language: Language): String {
        val raw = strings(language.folder).getValue(names.getValue(res.id))
        return if (res.args.isEmpty()) raw else String.format(language.locale, raw, *res.args.toTypedArray())
    }

    private val names: Map<Int, String> by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }

    private val byFolder = mutableMapOf<String, Map<String, String>>()

    /** Every string of res/[folder], with its escapes read as aapt2 reads them; English where German has none. */
    private fun strings(folder: String): Map<String, String> = byFolder.getOrPut(folder) {
        val own = File("src/main/res/$folder").listFiles { file -> file.name.startsWith("strings") }.orEmpty()
            .flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
            .associate {
                it.getAttribute("name") to
                    it.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\u00A0", "\u00A0")
            }
        if (folder == "values") own else strings("values") + own
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\u00A0]+")
        val NUMBER = Regex("[0-9][0-9.,]*%?")
        val UNITS = setOf("MB", "GB", "KB", "%", "min", "Min")
    }
}
