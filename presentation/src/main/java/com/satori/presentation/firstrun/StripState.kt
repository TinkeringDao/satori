package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason

/**
 * Home's status strip (design 4.1): one row at the top of home's list, the one place for the
 * downloads after the setup, the first notes' confirmation and the nudges. It sits above the
 * list, so it stays in view while the list scrolls. It never shows on the recorder or a setup
 * screen, and only one thing shows at a time ([first]).
 */
sealed interface StripState {

    /**
     * What the strip shows apart from its numbers: when this changes by itself, the strip's pills
     * ignore taps for a moment ([StripTapShield]), while a bar that moves on changes nothing.
     */
    val face: Any get() = this

    /**
     * A download after the setup: the AI model, the bigger AI model, or Malayalam tried again,
     * with its percent, dots and time left, or why it doesn't move ([hold]).
     *
     * @property minutesLeft Rounded up, so 1 is the last minute; null while it isn't shown
     * @property hold Why it doesn't move, and so the strip's one action; null while it runs
     * @property checking A file is in and its check holds the percent for some seconds: "Checking
     *   the files", as on Getting ready
     * @property resumedFromBytes The bytes it went on from after the app was closed, said until the
     *   time left shows; null otherwise
     * @property space What it needs and what is free, while it is held for want of space
     */
    data class Download(
        val part: FirstRunPart,
        val bytesDone: Long,
        val bytesTotal: Long,
        val minutesLeft: Int? = null,
        val hold: StripHold? = null,
        val checking: Boolean = false,
        val resumedFromBytes: Long? = null,
        val space: SpaceShortfall? = null
    ) : StripState {
        val percent: Int get() = if (bytesTotal <= 0L) 0 else (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)
        val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)

        /** What is still to come, for "848 MB left" while it waits for Wi-Fi. */
        val bytesLeft: Long get() = (bytesTotal - bytesDone).coerceAtLeast(0L)

        override val face: Any get() = part to hold
    }

    /**
     * Malayalam was dropped after it failed: Try again, offered once. The reader dropped after it
     * failed (0.8.0) shows this strip too, in its words, and its Try again plans whichever of the
     * two was dropped.
     */
    data object MalayalamFailed : StripState

    /** "Your first notes are ready", with Open, which goes to the notes of [sessionId]. */
    data class FirstNotes(val sessionId: String) : StripState

    /**
     * A one-time offer, [NudgeId.NOTIFY_PING] or [NudgeId.E2B_OFFER]. The first notes'
     * confirmation is [FirstNotes], and the retention offer isn't made in 0.7.0.
     *
     * @property missedNotes The ping is offered as notes were ready that no notification could tell
     *   of, as notifications aren't allowed; it says so first
     */
    data class Nudge(val id: NudgeId, val missedNotes: Boolean = false) : StripState

    companion object {
        /**
         * The one strip to show, in the design's order: what needs him (a download held for him,
         * a failed Malayalam), then a download under way, then the confirmation, then a nudge. A
         * nudge that [nudgeFirst] puts ahead comes before the confirmation: the ping for notes
         * that no notification could tell of, at the first look after them.
         */
        fun first(
            download: Download?,
            malayalamFailed: Boolean,
            firstNotes: FirstNotes?,
            nudge: Nudge?,
            nudgeFirst: Boolean = false
        ): StripState? = when {
            download?.hold?.needsHim == true -> download
            malayalamFailed -> MalayalamFailed
            download != null -> download
            nudgeFirst && nudge != null -> nudge
            firstNotes != null -> firstNotes
            else -> nudge
        }

        /**
         * The strip of the download after the setup ([later], the coordinator's), or null when
         * there is none or it is done. Held, it says why, as Getting ready's state of the same name
         * does; [plan] tells a file damaged twice, which waits for Try again, from one that is
         * fetched again by itself; [space] gives the numbers of a download held for want of room.
         */
        fun download(later: FirstRunProgress?, plan: FirstRunPlan, space: SpaceShortfall?): Download? {
            val progress = later ?: return null
            val state = progress.state
            if (state is DownloadState.Completed) return null
            val running = state is DownloadState.Downloading || state is DownloadState.Verifying
            val hold = when {
                running -> null
                plan.item(progress.part)?.waitsForTap == true -> StripHold.DAMAGED
                else -> holdOf(state)
            }
            return Download(
                part = progress.part,
                bytesDone = progress.bytesDone,
                bytesTotal = progress.bytesTotal,
                minutesLeft = progress.minutesLeft.takeIf { hold == null },
                hold = hold,
                checking = state is DownloadState.Verifying,
                resumedFromBytes = progress.resumedFromBytes.takeIf { hold == null },
                space = space.takeIf { hold == StripHold.NOT_ENOUGH_SPACE }
            )
        }

        /** Why a download that doesn't run is held; null for one waiting to begin. */
        private fun holdOf(state: DownloadState): StripHold? = when (state) {
            is DownloadState.Paused -> when (state.reason) {
                PauseReason.USER -> StripHold.PAUSED
                PauseReason.RECORDING -> StripHold.RECORDING
                PauseReason.NO_NETWORK -> StripHold.NO_CONNECTION
                PauseReason.WIFI_ONLY -> StripHold.WAITING_FOR_WIFI
                PauseReason.BLOCKED -> StripHold.BLOCKED
                PauseReason.NOT_ENOUGH_STORAGE -> StripHold.NOT_ENOUGH_SPACE
                PauseReason.SERVER -> StripHold.SERVER
            }
            is DownloadState.Error -> when (state.reason) {
                DownloadFailure.NO_CONNECTION -> StripHold.NO_CONNECTION
                DownloadFailure.NOT_ENOUGH_STORAGE -> StripHold.NOT_ENOUGH_SPACE
                DownloadFailure.SERVER -> StripHold.SERVER
                DownloadFailure.BLOCKED -> StripHold.BLOCKED
                // The plan counts any other failure as a damaged file, fetched again by itself.
                DownloadFailure.OTHER -> StripHold.DAMAGED_ONCE
            }
            DownloadState.Idle, is DownloadState.Downloading, is DownloadState.Verifying, DownloadState.Completed -> null
        }
    }
}

/**
 * Why the strip's download doesn't move. Each says the words of Getting ready's state of the same
 * name, with the same action.
 *
 * @property needsHim Only he can let it go on, so it comes first ([StripState.first])
 */
enum class StripHold(val needsHim: Boolean) {
    /** "Paused while recording": it goes on a few seconds after Stop. */
    RECORDING(needsHim = false),

    /** No connection: it goes on by itself; Try now tries at once. */
    NO_CONNECTION(needsHim = false),

    /** Waiting for Wi-Fi: Use mobile data, an outline pill. */
    WAITING_FOR_WIFI(needsHim = true),

    /** Satori can't reach the internet: Try again. */
    BLOCKED(needsHim = true),

    /** Not enough space: Open storage settings; it goes on once there is room. */
    NOT_ENOUGH_SPACE(needsHim = true),

    /** The server isn't answering: Satori tries again in a few minutes. */
    SERVER(needsHim = false),

    /** It arrived damaged once: Satori fetches it again by itself. */
    DAMAGED_ONCE(needsHim = false),

    /** It arrived damaged twice: Try again. */
    DAMAGED(needsHim = true),

    /** Pause was tapped on Getting ready and holds it too: Continue. */
    PAUSED(needsHim = true)
}

/**
 * The strip's pills ignore taps for [FirstRunFlow.TAP_SHIELD_MS] after the strip changed by itself
 * ([StripState.face]), so a tap meant for what was there lands on nothing (design R8): a strip that
 * appears above the list moves the list down. The strip home opens with is no change, and the bar
 * moving on is none either.
 */
class StripTapShield(private val shieldMs: Long = FirstRunFlow.TAP_SHIELD_MS) {

    private var seen = false
    private var face: Any? = null
    private var shieldUntilMs = 0L

    /** The strip shows [face] (null for none) from [nowMs]. */
    fun saw(face: Any?, nowMs: Long) {
        if (seen && face != this.face) shieldUntilMs = nowMs + shieldMs
        seen = true
        this.face = face
    }

    /** Whether a tap on a pill at [nowMs] counts. */
    fun accepts(nowMs: Long): Boolean = nowMs >= shieldUntilMs
}
