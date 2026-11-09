package com.satori.domain.firstrun

/**
 * Where the first run of a fresh install stands. It is stored before the screen changes and read
 * synchronously as the app starts, since the quick key asks at once whether the app is set up. It
 * only moves forward ([FirstRunFlow.next]), except for the two jumps that follow the speech files
 * on disk ([FirstRunFlow.atLaunch]).
 */
enum class FirstRunStage {
    /** Nothing agreed to yet: the first screen is Get ready. */
    GET_READY,

    /** Download was tapped and speech recognition is on its way: the screen is Getting ready. */
    DOWNLOADING,

    /**
     * Speech recognition is on the phone and checked, and Malayalam too unless it was not wanted
     * or was dropped: Getting ready shows Ready, until the first recording is saved or Look
     * around first is tapped.
     */
    READY,

    /** The first recording is saved: home, with the AI model downloading after it. */
    DONE;

    companion object {
        /**
         * The stage stored under [name], the name of a stage. An install that stored no stage, or
         * one this version doesn't know, is at [DONE] when it finished the earlier onboarding
         * ([onboardingComplete]) and at [GET_READY] otherwise.
         */
        fun stored(name: String?, onboardingComplete: Boolean): FirstRunStage =
            entries.firstOrNull { it.name == name } ?: if (onboardingComplete) DONE else GET_READY
    }
}
