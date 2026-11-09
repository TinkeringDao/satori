package com.satori.data.firstrun

/**
 * Where the first run is stored: the SharedPreferences file in which the earlier onboarding kept
 * `onboarding_complete`, read synchronously as the app starts, since the quick key asks at once
 * whether the app is set up. The first run's store reads and writes these keys, the stage before
 * the screen changes; the debug tools write the stage for the tests.
 */
object FirstRunPrefs {

    const val FILE = "onboarding_prefs"

    /** The name of the [com.satori.domain.firstrun.FirstRunStage]. */
    const val STAGE = "stage"

    /** When the stage was last written (epoch ms), for the field log's time per stage. */
    const val STAGE_AT = "stage_at"

    /**
     * The plan ([com.satori.domain.firstrun.FirstRunPlan]): each part with its status and the
     * failures counted against it, as `PART:STATUS:damaged:serverErrors`, comma-separated. Absent
     * until Download was tapped, or the setup was found on the phone.
     */
    const val PLAN = "plan"

    /** The parts after the setup for which Use mobile data was tapped, by name. */
    const val MOBILE_OK_PARTS = "mobile_ok_parts"

    /**
     * The microphone as the first run last knew it, the name of a refusal (refused once, refused
     * for good); absent while it was never refused. The permission itself is read each time.
     */
    const val MICROPHONE = "microphone"

    /** True from READY on ([com.satori.domain.firstrun.FirstRunFlow.onboardingComplete]), for what still reads it. */
    const val ONBOARDING_COMPLETE = "onboarding_complete"

    /** Get ready's switch, stored with the consent. */
    const val MALAYALAM_WANTED = "malayalam_wanted"

    /** When Download was tapped (epoch ms). */
    const val CONSENT_AT = "consent_at"

    /** Download was tapped on mobile data, or Use mobile data for the setup: it may use mobile data. */
    const val MOBILE_OK_FOR_SPEECH = "mobile_ok_for_speech"

    /** Pause was tapped and not Continue: the setup stays paused after a restart. */
    const val USER_PAUSED = "user_paused"

    /** Look around first was tapped at READY. */
    const val LOOKED_AROUND = "looked_around"

    /** The first recording was saved. */
    const val FIRST_RECORDING_SAVED = "first_recording_saved"

    /** The AI model's download was started after the first recording. */
    const val AI_DOWNLOAD_STARTED = "ai_download_started"
}
