package com.satori.ui.screens

import androidx.annotation.StringRes
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.presentation.firstrun.FirstRunFooter
import com.satori.presentation.firstrun.FirstRunProblem
import com.satori.presentation.firstrun.FirstRunQuiet
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.SetupProgress
import com.satori.ui.R
import com.satori.ui.components.microphoneAskCopy

/** A string resource and the values it is formatted with: what a line says, without a Context. */
data class CopyRes(@StringRes val id: Int, val args: List<Any> = emptyList())

/**
 * One of Get ready's rows: what downloads, with its size beside it.
 *
 * @property switchedOn The state of the row's switch, Malayalam's; null for a row without one
 * @property caption The line under it: when it downloads
 */
data class PartRow(val label: CopyRes, val bytes: Long, val switchedOn: Boolean? = null, val caption: CopyRes? = null)

/**
 * The words of one state of the first run, slot by slot from the top (design 3.4-3.6, the copy of
 * 7.3), so that the screens say exactly these and FirstRunStringsTest counts what they say. The
 * numbers are the state's: the percent, the dots, "276 / 808 MB" (g_onb_megabytes) and the rows'
 * sizes. Where a slot sits is the screens' to lay out, after the mockups.
 *
 * @property intro Get ready's sentence under the wordmark
 * @property title The heading; on Get ready only "Not enough space", under the intro
 * @property body The one sentence under the title: a problem's, Ready's, the space wanted
 * @property rows Get ready's rows
 * @property network Get ready's line about the network
 * @property part The part under way, beside the bytes of the bar
 * @property status The line under it: the time left, the files being checked, or where a download
 *   resumed; [quiet] sits at its right end
 * @property quiet The one quiet control ([FirstRunUiState.quiet])
 * @property outline Use mobile data, an outline pill under the bar ([FirstRunUiState.offersMobileData])
 * @property notes The lines under that: the microphone allowed, that the app may be left
 * @property caption The one or two lines docked right over the primary
 * @property primary The primary pill ([FirstRunUiState.footer]), off while
 *   [FirstRunUiState.primaryEnabled] is false
 */
data class FirstRunCopy(
    val intro: CopyRes? = null,
    val title: CopyRes? = null,
    val body: CopyRes? = null,
    val rows: List<PartRow> = emptyList(),
    val network: CopyRes? = null,
    val part: CopyRes? = null,
    val status: CopyRes? = null,
    val quiet: CopyRes? = null,
    val outline: CopyRes? = null,
    val notes: List<CopyRes> = emptyList(),
    val caption: List<CopyRes> = emptyList(),
    val primary: CopyRes
)

/** The test tags of the first run, for the emulator's checks; the screens' roots set testTagsAsResourceId. */
object FirstRunTags {
    const val GET_READY = "fr_screen_1"
    const val GETTING_READY = "fr_screen_2"
    const val PRIMARY = "fr_primary"
    const val QUIET = "fr_quiet"
    const val OUTLINE = "fr_outline"
    const val MALAYALAM = "fr_malayalam"
    const val STRIP = "fr_strip"

    /** The root tag of [screen]; Ready is Getting ready's end, so it has that one. */
    fun root(screen: FirstRunScreen): String = if (screen == FirstRunScreen.GET_READY) GET_READY else GETTING_READY
}

/**
 * What [state] says. [size] writes a size in bytes ([modelSizeLabel] in the app's locale).
 */
fun firstRunCopy(state: FirstRunUiState, size: (Long) -> String): FirstRunCopy =
    if (state.screen == FirstRunScreen.GET_READY) getReadyCopy(state, size) else gettingReadyCopy(state, size)

/** Get ready: on Wi-Fi, on mobile data, or short of space, where only the numbers are said. */
private fun getReadyCopy(state: FirstRunUiState, size: (Long) -> String): FirstRunCopy {
    val space = (state.shownProblem as? FirstRunProblem.NotEnoughSpace)?.space
    return FirstRunCopy(
        intro = CopyRes(R.string.g_fr_intro),
        title = space?.let { CopyRes(R.string.g_fr_space_title) },
        body = space?.let { CopyRes(R.string.g_fr_space_body, listOf(size(it.neededBytes), size(it.freeBytes))) },
        rows = if (space != null) emptyList() else partRows(state),
        network = when {
            space != null -> null
            state.onMobileData -> CopyRes(R.string.g_fr_mobile_now)
            else -> CopyRes(R.string.g_fr_wifi_only)
        },
        quiet = state.quiet?.let(::quietCopy),
        primary = primaryCopy(state, size)
    )
}

/**
 * Speech recognition, Malayalam with its switch, and AI notes when they download after the first
 * recording: the AI model, and with Malayalam's switch on the reader too (0.8.0), which only AI notes
 * use, so the row's size follows the switch. A part with nothing to download (0 bytes: on the phone
 * already, or not offered) has no row, so the switch only shows where it changes what downloads.
 */
private fun partRows(state: FirstRunUiState): List<PartRow> {
    val aiNotesBytes = state.sizes.aiNotesBytes(state.malayalam)
    return listOfNotNull(
        PartRow(CopyRes(R.string.g_fr_part_speech), state.sizes.speechBytes),
        PartRow(CopyRes(R.string.g_fr_part_malayalam), state.sizes.malayalamBytes, switchedOn = state.malayalam)
            .takeIf { state.sizes.malayalamBytes > 0L },
        PartRow(
            CopyRes(R.string.g_fr_part_notes),
            aiNotesBytes,
            caption = CopyRes(R.string.g_fr_part_notes_when)
        ).takeIf { aiNotesBytes > 0L }
    )
}

/** Getting ready, through its problems, to Ready. */
private fun gettingReadyCopy(state: FirstRunUiState, size: (Long) -> String): FirstRunCopy {
    val problem = state.shownProblem
    val progress = state.progress
    return FirstRunCopy(
        title = CopyRes(
            when {
                state.ready -> R.string.g_fr_ready_title
                state.paused -> R.string.g_fr_paused
                problem != null -> problemTitle(problem)
                else -> R.string.g_fr_getting_ready
            }
        ),
        body = when {
            state.ready -> CopyRes(R.string.g_fr_ready_body)
            problem != null -> problemBody(problem, state, size)
            else -> null
        },
        part = progress?.takeUnless { state.ready }?.let { CopyRes(partLabel(it.part)) },
        status = progress?.takeUnless { state.ready || state.paused || problem != null }?.let(::statusCopy),
        quiet = state.quiet?.let(::quietCopy),
        outline = CopyRes(R.string.g_fr_use_mobile).takeIf { state.offersMobileData },
        notes = listOfNotNull(
            CopyRes(R.string.g_fr_mic_ok).takeIf { state.showsMicrophoneAllowed },
            CopyRes(R.string.g_fr_leave).takeIf { state.showsLeaveLine }
        ),
        caption = captionCopy(state),
        primary = primaryCopy(state, size)
    )
}

@StringRes
private fun problemTitle(problem: FirstRunProblem): Int = when (problem) {
    FirstRunProblem.NoConnection -> R.string.g_fr_err_conn_title
    FirstRunProblem.WaitingForWifi -> R.string.g_fr_err_wifi_title
    FirstRunProblem.Blocked -> R.string.g_fr_err_blocked_title
    FirstRunProblem.ServerNotAnswering -> R.string.g_fr_err_server_title
    is FirstRunProblem.Damaged -> R.string.g_fr_err_damaged_title
    is FirstRunProblem.NotEnoughSpace -> R.string.g_fr_space_title
}

/** A problem's one sentence; a file damaged twice has none: it waits for Try again, which the footer says. */
private fun problemBody(problem: FirstRunProblem, state: FirstRunUiState, size: (Long) -> String): CopyRes? = when (problem) {
    FirstRunProblem.NoConnection -> CopyRes(R.string.g_fr_err_conn_body)
    FirstRunProblem.WaitingForWifi ->
        CopyRes(R.string.g_fr_err_wifi_body, listOf(size(state.progress?.bytesLeft ?: state.downloadBytes)))
    FirstRunProblem.Blocked -> CopyRes(R.string.g_fr_err_blocked_body)
    FirstRunProblem.ServerNotAnswering -> CopyRes(R.string.g_fr_err_server_body)
    is FirstRunProblem.Damaged -> if (problem.again) null else CopyRes(R.string.g_fr_err_damaged_body)
    is FirstRunProblem.NotEnoughSpace ->
        CopyRes(R.string.g_fr_space_body, listOf(size(problem.space.neededBytes), size(problem.space.freeBytes)))
}

/**
 * The name of [part], beside its bytes on Getting ready and in what TalkBack hears of its bar. The
 * reader goes by the AI model's name (0.8.0, design R7): it serves the AI notes alone, and no string
 * names it.
 */
@StringRes
internal fun partLabel(part: FirstRunPart): Int = when (part) {
    FirstRunPart.SPEECH -> R.string.g_fr_part_speech
    FirstRunPart.MALAYALAM -> R.string.g_fr_part_malayalam
    FirstRunPart.AI_MODEL, FirstRunPart.READER -> R.string.g_strip_ai_model
    FirstRunPart.BIGGER_AI_MODEL -> R.string.g_strip_bigger_model
}

/** The files being checked, else the time left, else where a resumed download went on from. */
private fun statusCopy(progress: SetupProgress): CopyRes? {
    val minutes = progress.minutesLeft
    val resumed = progress.resumedFromBytes
    return when {
        progress.checking -> CopyRes(R.string.g_fr_checking)
        minutes != null && minutes <= 1 -> CopyRes(R.string.g_fr_time_under)
        minutes != null -> CopyRes(R.string.g_fr_time_left, listOf(minutes))
        resumed != null -> CopyRes(R.string.g_onb_resuming, listOf((resumed / BYTES_PER_MB).toInt()))
        else -> null
    }
}

private fun quietCopy(quiet: FirstRunQuiet): CopyRes = CopyRes(
    when (quiet) {
        FirstRunQuiet.WAIT_FOR_WIFI -> R.string.g_fr_wait_wifi
        FirstRunQuiet.PAUSE -> R.string.g_fr_pause
        FirstRunQuiet.CONTINUE -> R.string.g_fr_resume
        FirstRunQuiet.TRY_NOW -> R.string.g_fr_try_now
        FirstRunQuiet.LOOK_AROUND -> R.string.g_fr_ready_look
    }
)

/**
 * Why the microphone is asked for and which option to pick, after a refusal where it can still be
 * allowed ([microphoneAskCopy], the words the recorder's ask says too), or why Record is off;
 * nothing over the other pills.
 */
private fun captionCopy(state: FirstRunUiState): List<CopyRes> = when (state.footer) {
    FirstRunFooter.ALLOW_MICROPHONE, FirstRunFooter.OPEN_APP_SETTINGS -> microphoneAskCopy(state.microphone).caption
    FirstRunFooter.RECORD -> if (state.primaryEnabled) emptyList() else listOf(CopyRes(R.string.g_fr_record_waits))
    FirstRunFooter.DOWNLOAD, FirstRunFooter.OPEN_STORAGE_SETTINGS, FirstRunFooter.TRY_AGAIN -> emptyList()
}

private fun primaryCopy(state: FirstRunUiState, size: (Long) -> String): CopyRes = when (state.footer) {
    FirstRunFooter.DOWNLOAD -> CopyRes(R.string.g_fr_download, listOf(size(state.downloadBytes)))
    FirstRunFooter.OPEN_STORAGE_SETTINGS -> CopyRes(R.string.g_fr_space_action)
    FirstRunFooter.TRY_AGAIN -> CopyRes(R.string.g_onb_retry)
    FirstRunFooter.ALLOW_MICROPHONE, FirstRunFooter.OPEN_APP_SETTINGS -> microphoneAskCopy(state.microphone).primary
    FirstRunFooter.RECORD -> CopyRes(R.string.g_fr_record)
}

/** Megabytes as the download screens count them (10^6 bytes). */
private const val BYTES_PER_MB = 1_000_000L
