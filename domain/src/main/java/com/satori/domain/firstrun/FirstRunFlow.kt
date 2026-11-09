package com.satori.domain.firstrun

import com.satori.domain.firstrun.FirstRunStage.DONE
import com.satori.domain.firstrun.FirstRunStage.DOWNLOADING
import com.satori.domain.firstrun.FirstRunStage.GET_READY
import com.satori.domain.firstrun.FirstRunStage.READY

/** The screen the first run shows. */
enum class FirstRunScreen {
    /** Get ready: what Satori does, what it downloads, and the one button that agrees to it. */
    GET_READY,

    /** Getting ready, the one wait, which turns into Ready when the files are checked. */
    GETTING_READY,

    /** The first run is over, or was left with Look around first: home. */
    HOME
}

/** What the app finds of speech recognition's files as it starts ([FirstRunFlow.atLaunch]). */
enum class SpeechFiles {
    /** A file is missing or empty: whatever was there is gone. */
    MISSING,

    /**
     * Every file is there, but they haven't been checked yet, or one fails the check (a damaged
     * file, or one the check couldn't read).
     */
    PRESENT,

    /** Every file is there and passes its check: downloaded, or put there by hand. */
    CHECKED
}

/** What moves the stage on ([FirstRunFlow.next]). */
enum class FirstRunEvent {
    /** Download was tapped on Get ready, or Wait for Wi-Fi on mobile data: the consent. */
    DOWNLOAD_TAPPED,

    /**
     * Speech recognition is on the phone and checked, and Malayalam too unless it was not wanted
     * or was dropped: downloaded, or put there by hand.
     */
    SETUP_DONE,

    /** A recording was saved with words in it; one deleted because nothing was said doesn't count. */
    RECORDING_SAVED,

    /** The speech recognition files are no longer on the phone. */
    SPEECH_GONE
}

/**
 * The rules of the first run's stages, pure so that every pair is tested in a table: which stage
 * follows which, where the app opens, which page dot is lit, and when the quick key may record.
 */
object FirstRunFlow {

    /** Page dots: Get ready, Getting ready, and the first recording, which Ready leads to. */
    const val PAGE_COUNT = 3

    /** [pageIndex] of the stage that shows no dots. */
    const val NO_PAGE = -1

    /**
     * How long the primary action of a screen that changed by itself ignores taps (ms), so a tap
     * meant for the screen before it does nothing. A target of the design, not a measured number.
     */
    const val TAP_SHIELD_MS = 700L

    /**
     * The stage after [event] at [stage]. It only moves forward, but for [FirstRunEvent.SPEECH_GONE],
     * which starts over from READY or DONE; an event that doesn't apply to [stage] changes nothing.
     */
    fun next(stage: FirstRunStage, event: FirstRunEvent): FirstRunStage = when (event) {
        FirstRunEvent.DOWNLOAD_TAPPED -> if (stage == GET_READY) DOWNLOADING else stage
        FirstRunEvent.SETUP_DONE -> if (stage == GET_READY || stage == DOWNLOADING) READY else stage
        FirstRunEvent.RECORDING_SAVED -> if (stage == READY) DONE else stage
        FirstRunEvent.SPEECH_GONE -> if (stage == READY || stage == DONE) GET_READY else stage
    }

    /**
     * The stage to open at, from the [stored] one and the files on disk. Speech recognition that is
     * there and checked on a fresh install was put there by hand, so the first screen is Ready. A
     * download whose last file was checked as the app died ([setupDone]) is Ready too. Speech files
     * gone after the setup start it over, as the earlier onboarding did. Files that are there but
     * not checked change nothing: one the check couldn't read is no reason to download 670 MB
     * again, nor proof that the setup is done; the recorder says when a model is damaged.
     *
     * @param speech What is on the phone of speech recognition's files
     * @param setupDone Whether every part of the setup is there or dropped ([FirstRunPlan.setupDone])
     */
    fun atLaunch(stored: FirstRunStage, speech: SpeechFiles, setupDone: Boolean): FirstRunStage = when (speech) {
        SpeechFiles.MISSING -> next(stored, FirstRunEvent.SPEECH_GONE)
        SpeechFiles.PRESENT -> stored
        SpeechFiles.CHECKED -> if (stored == GET_READY || setupDone) next(stored, FirstRunEvent.SETUP_DONE) else stored
    }

    /** The screen for [stage]; at READY, home once Look around first was tapped ([lookedAround]). */
    fun screen(stage: FirstRunStage, lookedAround: Boolean): FirstRunScreen = when (stage) {
        GET_READY -> FirstRunScreen.GET_READY
        DOWNLOADING -> FirstRunScreen.GETTING_READY
        READY -> if (lookedAround) FirstRunScreen.HOME else FirstRunScreen.GETTING_READY
        DONE -> FirstRunScreen.HOME
    }

    /** The lit page dot at [stage], zero-based, or [NO_PAGE]. */
    fun pageIndex(stage: FirstRunStage): Int = when (stage) {
        GET_READY -> 0
        DOWNLOADING -> 1
        READY -> 2
        DONE -> NO_PAGE
    }

    /**
     * Whether a press of the quick key may start a recording: the speech files are there (READY
     * or later) and the microphone is allowed. Before that a press only opens the app.
     */
    fun quickKeyReady(stage: FirstRunStage, microphoneAllowed: Boolean): Boolean =
        stage >= READY && microphoneAllowed

    /** The earlier onboarding's `onboarding_complete`, still stored for what reads it: true from READY on. */
    fun onboardingComplete(stage: FirstRunStage): Boolean = stage >= READY

    /** Whether no recording has been saved yet, so the next one is coached as the first. */
    fun firstRecordingAhead(stage: FirstRunStage): Boolean = stage != DONE
}
