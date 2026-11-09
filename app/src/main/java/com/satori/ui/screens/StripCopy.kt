package com.satori.ui.screens

import androidx.annotation.StringRes
import com.satori.data.config.Gemma4E2bModelConfig
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.NudgeId
import com.satori.presentation.firstrun.StripHold
import com.satori.presentation.firstrun.StripState
import com.satori.ui.R

/** What one of the strip's pills asks for. */
sealed interface StripTap {
    /** Try now, or Try again, on the download of [part]. */
    data class Retry(val part: FirstRunPart) : StripTap

    /** Use mobile data for the download of [part], which waits for Wi-Fi. */
    data class UseMobileData(val part: FirstRunPart) : StripTap

    /** Continue on a download held by Getting ready's Pause. */
    data object Resume : StripTap

    /** Android's storage settings, to make room. */
    data object OpenStorageSettings : StripTap

    /** Try again for the Malayalam the first run dropped. */
    data object RetryMalayalam : StripTap

    /** Open: the notes of [sessionId], the first ones. */
    data class OpenFirstNotes(val sessionId: String) : StripTap

    /** An offer answered: taken ([accepted]) or Not now. */
    data class Answer(val id: NudgeId, val accepted: Boolean) : StripTap
}

/**
 * One of the strip's pills: an outline pill, or a quiet one for a way that only tries sooner
 * (Try now, Continue), as Getting ready has them.
 */
data class StripPill(val label: CopyRes, val tap: StripTap, val quiet: Boolean = false)

/**
 * One line of the strip, in ink when it asks for him ([ink]), else in ash.
 */
data class StripLine(val text: CopyRes, val ink: Boolean = false)

/**
 * The words of the status strip in one of its states, slot by slot from the top (design 3.8, 3.9
 * and 4.1), so that the strip says exactly these and StripCopyTest counts what it says.
 *
 * @property title The download's part, or the offer's title, in ink; null for a strip of one
 *   sentence
 * @property percent The download's percent, at the title's right end, with [fraction] as its dots;
 *   null without a bar
 * @property lines Under that: the time left or why the download holds, then that hold's sentence;
 *   or the strip's one sentence
 * @property pills At most two, side by side under the lines
 */
data class StripCopy(
    val title: CopyRes? = null,
    val percent: Int? = null,
    val fraction: Float? = null,
    val lines: List<StripLine> = emptyList(),
    val pills: List<StripPill> = emptyList()
)

/**
 * What [strip] says; null for a nudge that is never one on the strip (the first notes' own
 * confirmation is [StripState.FirstNotes], and the retention offer isn't made). [size] writes a
 * size in bytes ([modelSizeLabel] in the app's locale).
 */
fun stripCopy(strip: StripState, size: (Long) -> String): StripCopy? = when (strip) {
    is StripState.Download -> downloadCopy(strip, size)
    StripState.MalayalamFailed -> StripCopy(
        lines = listOf(StripLine(CopyRes(R.string.g_strip_malayalam_failed), ink = true)),
        pills = listOf(StripPill(CopyRes(R.string.g_onb_retry), StripTap.RetryMalayalam))
    )
    is StripState.FirstNotes -> StripCopy(
        lines = listOf(StripLine(CopyRes(R.string.g_strip_first_notes), ink = true)),
        pills = listOf(StripPill(CopyRes(R.string.g_strip_open), StripTap.OpenFirstNotes(strip.sessionId)))
    )
    is StripState.Nudge -> nudgeCopy(strip, size)
}

/** The bigger AI model's size, from the registry, as its offer says it: "3.3 GB". */
internal val biggerModelBytes: Long = Gemma4E2bModelConfig.INSTANCE.llmSpec.bytes ?: 0L

/**
 * An offer's words. The ping for notes that no notification could tell of says why first, in
 * ash, and then asks, in ink (design R10: one plain sentence on why, then the one window).
 */
private fun nudgeCopy(strip: StripState.Nudge, size: (Long) -> String): StripCopy? = when (val id = strip.id) {
    NudgeId.NOTIFY_PING -> StripCopy(
        lines = listOfNotNull(
            StripLine(CopyRes(R.string.g_nudge_ping_missed)).takeIf { strip.missedNotes },
            StripLine(CopyRes(R.string.g_nudge_ping), ink = true)
        ),
        pills = answers(id, accept = R.string.g_todo_notify_allow)
    )
    NudgeId.E2B_OFFER -> StripCopy(
        title = CopyRes(R.string.g_nudge_e2b_title),
        lines = listOf(StripLine(CopyRes(R.string.g_nudge_e2b_body, listOf(size(biggerModelBytes))))),
        pills = answers(id, accept = R.string.g_nudge_get_it)
    )
    NudgeId.RETENTION, NudgeId.FIRST_NOTES -> null
}

/** A plain yes and no, the same weight each (design R20). */
private fun answers(id: NudgeId, @StringRes accept: Int): List<StripPill> = listOf(
    StripPill(CopyRes(accept), StripTap.Answer(id, accepted = true)),
    StripPill(CopyRes(R.string.g_todo_notify_later), StripTap.Answer(id, accepted = false))
)

/**
 * The AI model, the bigger one or Malayalam, its bar, and the line under it: the time left while
 * it runs, else what holds it in Getting ready's words, with the same action.
 */
private fun downloadCopy(strip: StripState.Download, size: (Long) -> String): StripCopy {
    val hold = strip.hold
    return StripCopy(
        title = CopyRes(partLabel(strip.part)),
        percent = strip.percent,
        fraction = strip.fraction,
        lines = if (hold == null) listOfNotNull(runningLine(strip)) else holdLines(hold, strip, size),
        pills = if (hold == null) emptyList() else listOfNotNull(holdPill(hold, strip.part))
    )
}

/** The files being checked, else the time left, else where a resumed download went on from. */
private fun runningLine(strip: StripState.Download): StripLine? {
    val minutes = strip.minutesLeft
    val resumed = strip.resumedFromBytes
    val text = when {
        strip.checking -> CopyRes(R.string.g_fr_checking)
        minutes != null && minutes <= 1 -> CopyRes(R.string.g_fr_time_under)
        minutes != null -> CopyRes(R.string.g_fr_time_left, listOf(minutes))
        resumed != null -> CopyRes(R.string.g_onb_resuming, listOf((resumed / BYTES_PER_MB).toInt()))
        else -> return null
    }
    return StripLine(text)
}

/** What holds the download: its title, in ink when only he can let it go on, then its one sentence. */
private fun holdLines(hold: StripHold, strip: StripState.Download, size: (Long) -> String): List<StripLine> {
    val title = StripLine(CopyRes(holdTitle(hold)), ink = hold.needsHim)
    val body = when (hold) {
        StripHold.RECORDING, StripHold.DAMAGED, StripHold.PAUSED -> null
        StripHold.NO_CONNECTION -> CopyRes(R.string.g_fr_err_conn_body)
        StripHold.WAITING_FOR_WIFI -> CopyRes(R.string.g_fr_err_wifi_body, listOf(size(strip.bytesLeft)))
        StripHold.BLOCKED -> CopyRes(R.string.g_fr_err_blocked_body)
        StripHold.NOT_ENOUGH_SPACE -> strip.space?.let { CopyRes(R.string.g_fr_space_body, listOf(size(it.neededBytes), size(it.freeBytes))) }
        StripHold.SERVER -> CopyRes(R.string.g_fr_err_server_body)
        StripHold.DAMAGED_ONCE -> CopyRes(R.string.g_fr_err_damaged_body)
    }
    return listOfNotNull(title, body?.let(::StripLine))
}

@StringRes
private fun holdTitle(hold: StripHold): Int = when (hold) {
    StripHold.RECORDING -> R.string.g_strip_paused_recording
    StripHold.NO_CONNECTION -> R.string.g_fr_err_conn_title
    StripHold.WAITING_FOR_WIFI -> R.string.g_fr_err_wifi_title
    StripHold.BLOCKED -> R.string.g_fr_err_blocked_title
    StripHold.NOT_ENOUGH_SPACE -> R.string.g_fr_space_title
    StripHold.SERVER -> R.string.g_fr_err_server_title
    StripHold.DAMAGED_ONCE, StripHold.DAMAGED -> R.string.g_fr_err_damaged_title
    StripHold.PAUSED -> R.string.g_fr_paused
}

/** The hold's one action, as Getting ready has it; none where it goes on by itself. */
private fun holdPill(hold: StripHold, part: FirstRunPart): StripPill? = when (hold) {
    StripHold.NO_CONNECTION -> StripPill(CopyRes(R.string.g_fr_try_now), StripTap.Retry(part), quiet = true)
    StripHold.WAITING_FOR_WIFI -> StripPill(CopyRes(R.string.g_fr_use_mobile), StripTap.UseMobileData(part))
    StripHold.BLOCKED, StripHold.DAMAGED -> StripPill(CopyRes(R.string.g_onb_retry), StripTap.Retry(part))
    StripHold.NOT_ENOUGH_SPACE -> StripPill(CopyRes(R.string.g_fr_space_action), StripTap.OpenStorageSettings)
    StripHold.PAUSED -> StripPill(CopyRes(R.string.g_fr_resume), StripTap.Resume, quiet = true)
    StripHold.RECORDING, StripHold.SERVER, StripHold.DAMAGED_ONCE -> null
}

/** Megabytes as the download screens count them (10^6 bytes). */
private const val BYTES_PER_MB = 1_000_000L
