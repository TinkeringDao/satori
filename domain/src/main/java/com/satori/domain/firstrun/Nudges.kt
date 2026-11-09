package com.satori.domain.firstrun

/**
 * The status strip's one-time offers, and the one confirmation the nudge ledger keeps. Each is
 * shown at most once, ever; one at a time; never while a recording runs, within a minute of a
 * Stop, on the recorder or on a setup screen ([NudgeRules]). The ids are what the field log names.
 */
enum class NudgeId {
    /**
     * A ping when notes are ready, if notifications were never asked for: after the second
     * recording with words, or once notes were ready that no notification could tell of.
     */
    NOTIFY_PING,

    /** The bigger AI model, after the third recording with notes; behind a build flag until its memory runs pass. */
    E2B_OFFER,

    /**
     * Compress older audio, once the audio reaches 2 GB. Optional, and not offered in 0.7.0:
     * nothing makes it due ([NudgeRules.offer]).
     */
    RETENTION,

    /** "Your first notes are ready": a confirmation, not a request; it goes when a recording is opened or after a day. */
    FIRST_NOTES
}

/**
 * Where a nudge stands in the ledger ([NudgeLedger]). Once it has reached the screen it is shown
 * that one time: it stays until it is answered or goes, and never comes back, whatever was done
 * with it.
 */
enum class NudgeState {
    NEVER_SHOWN,

    /** It reached the screen, and is on show until it is answered or goes. */
    SHOWN,

    /** Its offer was taken. */
    ACCEPTED,

    /** Not now. */
    DECLINED,

    /**
     * It went without an answer: left alone for a day ([NudgeLedger.SHOWN_FOR_MS]), or, for the
     * first notes' confirmation, once a recording was opened.
     */
    GONE
}
