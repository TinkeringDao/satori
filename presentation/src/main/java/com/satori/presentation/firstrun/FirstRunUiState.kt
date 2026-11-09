package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.SpaceShortfall

/**
 * What Get ready and Getting ready draw, Ready being Getting ready's end (design 3.4-3.6). The
 * first run's view model makes it from the coordinator's snapshot and the microphone permission;
 * the screens only draw it. Every choice of what they show is a property here, so it is tested
 * without a screen; the words of each state are FirstRunCopy's, in the app module.
 *
 * @property stage Where the first run stands; the screens draw GET_READY to READY
 * @property sizes The registry's bytes of what Get ready lists
 * @property malayalam Get ready's switch
 * @property onMobileData Android says the network is metered: Get ready says so and offers to
 *   wait for Wi-Fi
 * @property progress The setup's download, once Download was tapped
 * @property problem What keeps the download from going on; the screen keeps its layout, and the
 *   title and one sentence say it
 * @property microphone The microphone permission as last read: after Android's window, and on
 *   every return to the screen
 * @property tapsFromMs When the primary takes taps again after the screen changed by itself
 *   ([withTapShield]); 0 takes them at once
 */
data class FirstRunUiState(
    val stage: FirstRunStage = FirstRunStage.GET_READY,
    val sizes: SetupSizes = SetupSizes(),
    val malayalam: Boolean = true,
    val onMobileData: Boolean = false,
    val progress: SetupProgress? = null,
    val problem: FirstRunProblem? = null,
    val microphone: MicrophoneState = MicrophoneState.NOT_ALLOWED,
    val tapsFromMs: Long = 0L
) {
    /** Get ready, or Getting ready with Ready as its end. */
    val screen: FirstRunScreen
        get() = if (stage == FirstRunStage.GET_READY) FirstRunScreen.GET_READY else FirstRunScreen.GETTING_READY

    /** Speech recognition is on the phone: Getting ready shows Ready. */
    val ready: Boolean get() = stage >= FirstRunStage.READY

    /** The lit page dot ([FirstRunFlow.pageIndex]). */
    val page: Int get() = FirstRunFlow.pageIndex(stage)

    /** What Get ready's button downloads: speech recognition, and Malayalam while the switch is on. */
    val downloadBytes: Long get() = sizes.download(malayalam)

    /** Pause was tapped and the setup waits for Continue. */
    val paused: Boolean get() = !ready && progress?.paused == true

    /** The problem the screen says: none at Ready, and none while paused, as nothing is tried then. */
    val shownProblem: FirstRunProblem? get() = problem.takeUnless { ready || paused }

    /**
     * The one primary, docked at the bottom in the same place in every state. A problem that needs
     * him beats the microphone, and the microphone beats Record, which stays in its place, switched
     * off, until the files are checked ([primaryEnabled]).
     */
    val footer: FirstRunFooter
        get() {
            val problem = shownProblem
            return when {
                problem is FirstRunProblem.NotEnoughSpace -> FirstRunFooter.OPEN_STORAGE_SETTINGS
                stage == FirstRunStage.GET_READY -> FirstRunFooter.DOWNLOAD
                problem?.needsTap == true -> FirstRunFooter.TRY_AGAIN
                microphone == MicrophoneState.REFUSED_FOR_GOOD -> FirstRunFooter.OPEN_APP_SETTINGS
                microphone != MicrophoneState.ALLOWED -> FirstRunFooter.ALLOW_MICROPHONE
                else -> FirstRunFooter.RECORD
            }
        }

    /** Whether the primary can be tapped: Record only once the files are checked. */
    val primaryEnabled: Boolean get() = footer != FirstRunFooter.RECORD || ready

    /**
     * The one quiet control, a text link in the content: Wait for Wi-Fi on mobile data, Pause or
     * Continue while downloading, Try now without a connection, Look around first at Ready. None
     * while a file is checked, or while a problem other than no connection shows.
     */
    val quiet: FirstRunQuiet?
        get() = when {
            stage == FirstRunStage.GET_READY -> FirstRunQuiet.WAIT_FOR_WIFI.takeIf { onMobileData && shownProblem == null }
            ready -> FirstRunQuiet.LOOK_AROUND
            paused -> FirstRunQuiet.CONTINUE
            shownProblem == FirstRunProblem.NoConnection -> FirstRunQuiet.TRY_NOW
            shownProblem != null || progress == null || progress.checking -> null
            else -> FirstRunQuiet.PAUSE
        }

    /** Whether Use mobile data shows: an outline pill, never the primary, as it costs money. */
    val offersMobileData: Boolean get() = shownProblem == FirstRunProblem.WaitingForWifi

    /**
     * Whether "You can leave. Satori keeps going." is true now: while the download runs or retries
     * by itself, not while it is paused, waits for Wi-Fi or needs a tap.
     */
    val showsLeaveLine: Boolean
        get() = stage == FirstRunStage.DOWNLOADING && !paused &&
            shownProblem?.needsTap != true && shownProblem != FirstRunProblem.WaitingForWifi

    /** "Microphone allowed." on Getting ready, once it is; Ready says nothing of it. */
    val showsMicrophoneAllowed: Boolean
        get() = stage == FirstRunStage.DOWNLOADING && microphone == MicrophoneState.ALLOWED

    /**
     * Whether the screen stays on: while Getting ready is watched during the download, and only
     * then. Not while paused, nor while it waits for a tap.
     */
    val keepScreenOn: Boolean
        get() = stage == FirstRunStage.DOWNLOADING && !paused && shownProblem?.needsTap != true

    /** Whether a tap on the primary at [nowMs] counts ([withTapShield]). */
    fun acceptsTaps(nowMs: Long): Boolean = nowMs >= tapsFromMs

    /**
     * This state as it follows [previous] at [nowMs]. When the primary changed by itself, not
     * through a tap ([byTap]), it ignores taps for [FirstRunFlow.TAP_SHIELD_MS], so a tap meant for
     * the one before lands on nothing; the shield swallows the tap and doesn't change how the
     * button looks. Otherwise the shield stays as it was.
     */
    fun withTapShield(previous: FirstRunUiState?, nowMs: Long, byTap: Boolean): FirstRunUiState {
        val changed = previous != null && (previous.screen != screen || previous.footer != footer ||
            previous.primaryEnabled != primaryEnabled)
        val shieldFrom = if (changed && !byTap) nowMs + FirstRunFlow.TAP_SHIELD_MS else previous?.tapsFromMs ?: tapsFromMs
        return copy(tapsFromMs = shieldFrom)
    }
}

/**
 * The bytes of what Get ready lists, from the registry.
 *
 * @property malayalamBytes Malayalam, its switch's row; 0 when it is on the phone already, which
 *   leaves the row and its switch out
 * @property aiModelBytes The AI model that downloads after the first recording; 0 when none will
 *   (AI notes off, or the model is on the phone already), which leaves its row out
 * @property readerBytes The reader that downloads after the AI model when Malayalam is wanted
 *   (0.8.0); 0 when it won't (AI notes off, or it is on the phone already). It has no row of its
 *   own: the AI notes row counts it while Malayalam's switch is on ([aiNotesBytes])
 */
data class SetupSizes(
    val speechBytes: Long = 0L,
    val malayalamBytes: Long = 0L,
    val aiModelBytes: Long = 0L,
    val readerBytes: Long = 0L
) {
    /** What Get ready's button downloads, with Malayalam or without. */
    fun download(malayalam: Boolean): Long = speechBytes + if (malayalam) malayalamBytes else 0L

    /**
     * What the AI notes row says comes after the first recording: the AI model, and the reader with
     * Malayalam ([malayalam], the switch), which only AI notes use (design R7, no new words).
     */
    fun aiNotesBytes(malayalam: Boolean): Long = aiModelBytes + if (malayalam) readerBytes else 0L
}

/**
 * The setup's download as Getting ready shows it: one bar over speech recognition and Malayalam.
 *
 * @property part The part under way, named beside the bytes
 * @property bytesDone The bytes of the setup on the phone
 * @property bytesTotal The bytes of the setup ([SetupSizes.download])
 * @property minutesLeft The time left, rounded up, so 1 is the last minute; null while it isn't
 *   shown: before 10 s and 2 % of the bytes, and while no bytes arrive
 * @property checking A file is in and being checked, which holds the percent for some seconds
 * @property paused Pause was tapped
 * @property resumedFromBytes The bytes a download that went on after the app was closed went on
 *   from, said until the time left shows
 */
data class SetupProgress(
    val part: FirstRunPart,
    val bytesDone: Long,
    val bytesTotal: Long,
    val minutesLeft: Int? = null,
    val checking: Boolean = false,
    val paused: Boolean = false,
    val resumedFromBytes: Long? = null
) {
    /** 0-100, as the big number shows it. */
    val percent: Int get() = if (bytesTotal <= 0L) 0 else (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)

    /** 0-1, for the dots. */
    val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)

    /** What is still to come, for "428 MB left" while waiting for Wi-Fi. */
    val bytesLeft: Long get() = (bytesTotal - bytesDone).coerceAtLeast(0L)
}

/** What keeps the setup's download from going on. Each keeps the screen's layout and says itself in a title and one sentence. */
sealed interface FirstRunProblem {

    /** Whether only he can fix it: the footer then holds the fix, ahead of the microphone. */
    val needsTap: Boolean

    /** No connection (2c): Satori goes on by itself when it is back; Try now is the quiet control. */
    data object NoConnection : FirstRunProblem {
        override val needsTap: Boolean get() = false
    }

    /** Wi-Fi is gone and only mobile data is left (2f): it waits, and Use mobile data is offered. */
    data object WaitingForWifi : FirstRunProblem {
        override val needsTap: Boolean get() = false
    }

    /** The phone is online but Satori can't reach the internet (2d): Try again. */
    data object Blocked : FirstRunProblem {
        override val needsTap: Boolean get() = true
    }

    /** The server answers with errors: Satori tries again in a few minutes. */
    data object ServerNotAnswering : FirstRunProblem {
        override val needsTap: Boolean get() = false
    }

    /** A file arrived damaged: fetched again by itself; after the second time ([again]), Try again. */
    data class Damaged(val again: Boolean) : FirstRunProblem {
        override val needsTap: Boolean get() = again
    }

    /** Not enough free space (1c): Open storage settings; a download held for it goes on once there is room. */
    data class NotEnoughSpace(val space: SpaceShortfall) : FirstRunProblem {
        override val needsTap: Boolean get() = true
    }
}

/** The microphone permission as the first run knows it. */
enum class MicrophoneState {
    /** Not allowed, and Android's window can ask. */
    NOT_ALLOWED,

    ALLOWED,

    /** Refused once: Android can still ask, so the pill asks again. */
    REFUSED_ONCE,

    /** Refused twice: only the app's settings page can allow it now. */
    REFUSED_FOR_GOOD;

    companion object {
        /**
         * The state after Android's window answered at [previous]. A first refusal can always be
         * asked again; after that, Android says whether it would ask ([canAskAgain], its
         * shouldShowRequestPermissionRationale), which a dismissed window leaves false too.
         */
        fun answered(previous: MicrophoneState, granted: Boolean, canAskAgain: Boolean): MicrophoneState = when {
            granted -> ALLOWED
            previous == NOT_ALLOWED || previous == ALLOWED -> REFUSED_ONCE
            canAskAgain -> REFUSED_ONCE
            else -> REFUSED_FOR_GOOD
        }

        /**
         * The state when the permission is read again ([granted]) on a return to the screen, after
         * the app's settings page, say: allowed counts at once; a refusal stays what it was; an
         * allowance taken back, by "Only this time" or in Settings, is not allowed again.
         */
        fun reread(previous: MicrophoneState, granted: Boolean): MicrophoneState = when {
            granted -> ALLOWED
            previous == ALLOWED -> NOT_ALLOWED
            else -> previous
        }
    }
}

/** The first run's primary pill. */
enum class FirstRunFooter {
    /** Get ready: "Download 808 MB", the consent. */
    DOWNLOAD,

    /** Not enough space: Android's storage settings. */
    OPEN_STORAGE_SETTINGS,

    /** Blocked, or a file damaged twice. */
    TRY_AGAIN,

    /** Android's microphone window, after the caption says why and which option to pick. */
    ALLOW_MICROPHONE,

    /** The microphone was refused twice: the app's settings page. */
    OPEN_APP_SETTINGS,

    /** The first recording: off until the files are checked, as home's grille once they are. */
    RECORD
}

/** The first run's one quiet control. */
enum class FirstRunQuiet {
    WAIT_FOR_WIFI,
    PAUSE,
    CONTINUE,
    TRY_NOW,
    LOOK_AROUND
}
