package com.satori.domain.firstrun

import com.satori.domain.model.DownloadState
import kotlinx.coroutines.flow.StateFlow

/**
 * The first run's one writer: an app-wide singleton, started as the app starts, that keeps the
 * stage and the plan, runs the downloads through the queue, resumes them at every start, starts
 * the AI model after the first recording is saved, and follows the model folder while a download
 * can't go on, so files put there by hand count as downloaded. Screens and the recorder only read
 * [state] and ask for changes through the functions here; none of them writes the first run's
 * state itself.
 */
interface FirstRunCoordinator {

    /** The first run as it stands. Its first value holds the stored stage, read synchronously. */
    val state: StateFlow<FirstRunSnapshot>

    /**
     * Called at every start of the app's screen (MainActivity's onStart): reads the files on disk
     * ([FirstRunFlow.atLaunch]) and resumes the download that was under way. Not in a process
     * Android starts for something else: a reboot is picked up here, at the next open, as Android
     * doesn't let a boot receiver start a data sync service.
     */
    fun start()

    /**
     * Get ready's consent: download the setup, with Malayalam or without ([malayalam], the switch).
     * [useMobileData] is true for Download tapped on mobile data, whose button showed the size;
     * false on Wi-Fi and for Wait for Wi-Fi.
     */
    fun download(malayalam: Boolean, useMobileData: Boolean)

    /** Getting ready's Pause: the setup's download stops within 2 s, and stays paused after a restart. */
    fun pause()

    /** Getting ready's Continue. */
    fun resume()

    /** Try now, or Try again, for [part]: also a dropped Malayalam, once, from the status strip. */
    fun retry(part: FirstRunPart)

    /** Use mobile data for [part], which waits for Wi-Fi: the only way a download spends mobile data later. */
    fun useMobileData(part: FirstRunPart)

    /** Ready's Look around first: home, with the stage left at READY until the first recording is saved. */
    fun lookAround()

    /**
     * A recording was saved with words in it, not deleted for having nothing said: the first one
     * ends the first run ([FirstRunEvent.RECORDING_SAVED]) and lets the AI model download.
     */
    fun recordingSaved()

    /** The bigger AI model's offer was taken: it downloads, on Wi-Fi, after what is ahead of it. */
    fun getBiggerModel()
}

/**
 * The first run as the coordinator holds it, for every screen that shows a part of it: Get ready
 * and Getting ready, the recorder's first recording, home's status strip, and the finisher, which
 * holds a recording for the AI model while it downloads.
 *
 * @property stage Where the first run stands
 * @property lookedAround Look around first was tapped at READY: home shows, though the stage stays
 *   READY until the first recording is saved
 * @property malayalamWanted Get ready's switch: on by default, stored with the consent
 * @property plan What downloads, in order, and how far each part has come
 * @property setup The setup's downloads as Getting ready shows them, one bar over all of them;
 *   null before Download is tapped
 * @property later The download after the setup that runs or waits, for the status strip: the AI
 *   model, the bigger AI model, the reader, or a Malayalam tried again; null when there is none
 * @property network The default network now
 * @property space Set while a download can't start or go on for want of free space
 * @property readerFound How often in this process the reader's files were found whole and with
 *   their pinned SHA-256 (0.8.0, R7): at its download's end, or on the phone. It tells the reader's
 *   availability changed where its part's status may not show it: a part done whose record went is
 *   planned again and found again at once, done before and after.
 */
data class FirstRunSnapshot(
    val stage: FirstRunStage = FirstRunStage.GET_READY,
    val lookedAround: Boolean = false,
    val malayalamWanted: Boolean = true,
    val plan: FirstRunPlan = FirstRunPlan(emptyList()),
    val setup: FirstRunProgress? = null,
    val later: FirstRunProgress? = null,
    val network: NetworkFacts = NetworkFacts.NONE,
    val space: SpaceShortfall? = null,
    val readerFound: Int = 0
) {
    /** The screen the app opens on ([FirstRunFlow.screen]). */
    val screen: FirstRunScreen get() = FirstRunFlow.screen(stage, lookedAround)

    /**
     * Whether a recording saved now waits for the AI model, held as
     * [com.satori.domain.finish.FinishingStatus.Phase.WAITS_FOR_MODEL], instead of being finished
     * without notes: the AI model is planned and not on the phone yet. Whoever holds a recording
     * asks too whether AI notes are on.
     */
    val aiModelPending: Boolean get() = plan.pending(FirstRunPart.AI_MODEL)

    /**
     * Whether the reader is planned and not on the phone yet (0.8.0, design R7): neither dropped
     * nor done. A Malayalam recording saved meanwhile waits for it, with AI notes on
     * ([com.satori.domain.reading.ReaderStatus.ON_ITS_WAY]), instead of being finished without its
     * AI steps.
     */
    val readerPending: Boolean get() = plan.pending(FirstRunPart.READER)

    /** Whether no recording was saved yet, so the recorder coaches the next one as the first. */
    val firstRecordingAhead: Boolean get() = FirstRunFlow.firstRecordingAhead(stage)
}

/**
 * A download of the first run as the screens show it: one bar over [bytesTotal], the part under
 * way named beside the bytes.
 *
 * @property part The part downloading now, or the one that is held
 * @property state The state of [part]'s download: running, checked, held and why, or failed
 * @property bytesDone The bytes on the phone of everything the bar counts
 * @property bytesTotal What the bar counts: the setup's parts together on Getting ready, one part
 *   in the status strip
 * @property minutesLeft The time left, rounded up to the minute, so 1 is the last minute; null
 *   while it isn't shown: before 10 s and 2 % of the bytes, and while no bytes arrive
 * @property resumedFromBytes The bytes a download that went on after the app was closed went on
 *   from, until the time left shows; null otherwise
 */
data class FirstRunProgress(
    val part: FirstRunPart,
    val state: DownloadState,
    val bytesDone: Long,
    val bytesTotal: Long,
    val minutesLeft: Int? = null,
    val resumedFromBytes: Long? = null
)
