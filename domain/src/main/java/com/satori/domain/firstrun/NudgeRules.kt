package com.satori.domain.firstrun

/**
 * What decides whether one of home's offers is due, all read just now: the recordings saved,
 * whether one is being made or was just stopped, and the phone's facts each offer asks for.
 *
 * @property recordingsWithWords Recordings saved with words in them; the one being made doesn't count
 * @property recordingsWithNotes Recordings saved whose notes are written and whose finishing is done
 * @property recordingLive A recorder is starting, recording or saving
 * @property justStopped A recording was stopped less than [NudgeRules.QUIET_AFTER_STOP_MS] ago
 * @property notificationsAllowed The app may post notifications now
 * @property notificationsAskable Android's notification window can ask: Android 13 or later, and
 *   the permission isn't granted
 * @property notificationsAsked Android's notification window was asked for before, by the to-do
 *   reminders' sheet or by the ping offer: the one flag both share
 * @property reminderAskDue The to-do reminders' sheet asks for notifications now: an open to-do has
 *   a reminder, and the window was never asked for. One ask at a time on home: the ping waits for
 *   it, and once the sheet is answered the window counts as asked, so the ping has nothing left
 * @property notesMissed A recording's notes were ready while the app was out of sight, and "Your
 *   notes are ready" could not be posted, as notifications aren't allowed: the moment the ping was
 *   needed (design R10), also for an owner who records by the quick key and seldom sees home
 * @property biggerModelHere Gemma 4 E2B is on the phone or on its way: planned, or partly downloaded
 * @property aiNotesOn AI notes are switched on
 * @property unmetered There is a network, and data on it costs nothing: not mobile data, not a hotspot
 * @property charging The phone is charging
 * @property freeBytes The free space where the models go
 */
data class NudgeFacts(
    val recordingsWithWords: Int = 0,
    val recordingsWithNotes: Int = 0,
    val recordingLive: Boolean = false,
    val justStopped: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val notificationsAskable: Boolean = false,
    val notificationsAsked: Boolean = false,
    val reminderAskDue: Boolean = false,
    val notesMissed: Boolean = false,
    val biggerModelHere: Boolean = false,
    val aiNotesOn: Boolean = false,
    val unmetered: Boolean = false,
    val charging: Boolean = false,
    val freeBytes: Long = 0L
)

/**
 * When home's one-time offers are made (design 4.1), and the first notes' confirmation shown. At
 * most one at a time, and none while a recording is made, within [QUIET_AFTER_STOP_MS] of a Stop,
 * on the recorder or on a setup screen: the strip that shows them is home's alone. An offer on show
 * keeps its place until it is answered or goes ([NudgeLedger]); a new one waits its turn.
 */
object NudgeRules {

    /**
     * The build flag of the bigger AI model's offer, off until Gemma 4 E2B's memory runs on the
     * phone have passed (the owner's decision of 2026-10-15). Turning the offer on is this line.
     */
    const val E2B_OFFER_ON = false

    /**
     * The ping is offered once this many recordings with words are saved, or sooner, once notes
     * were ready that no notification could tell of ([NudgeFacts.notesMissed]).
     */
    const val PING_AFTER_RECORDINGS = 2

    /** The bigger AI model is offered once this many recordings have their notes. */
    const val E2B_AFTER_RECORDINGS = 3

    /** The free space the bigger AI model's offer asks for: its 3.3 GB and room besides. */
    const val E2B_FREE_BYTES = 5_000_000_000L

    /** How long after a Stop no offer and no confirmation appears (ms). */
    const val QUIET_AFTER_STOP_MS = 60_000L

    /** The offers, in the order they are made when two are due together. */
    private val OFFERS = listOf(NudgeId.NOTIFY_PING, NudgeId.E2B_OFFER)

    /**
     * The offer to show at [nowMs], or null: the one on show, if it still has something to offer,
     * else the first that is due and was never shown. [e2bOn] is [E2B_OFFER_ON], given here for
     * the tests.
     */
    fun offer(facts: NudgeFacts, ledger: NudgeLedger, nowMs: Long, e2bOn: Boolean = E2B_OFFER_ON): NudgeId? {
        if (quiet(facts)) return null
        return OFFERS.firstOrNull { ledger.onShow(it, nowMs) && stillOffers(it, facts, e2bOn) }
            ?: OFFERS.firstOrNull { ledger.state(it) == NudgeState.NEVER_SHOWN && due(it, facts, e2bOn) }
    }

    /**
     * Whether "Your first notes are ready" shows at [nowMs]: a recording has its notes, the
     * confirmation never showed or is on show still, and no recording is made or was just stopped.
     */
    fun firstNotes(facts: NudgeFacts, ledger: NudgeLedger, nowMs: Long): Boolean =
        !quiet(facts) && facts.recordingsWithNotes >= 1 && ledger.open(NudgeId.FIRST_NOTES, nowMs)

    /**
     * Whether [id] is due to be offered for the first time:
     * - the ping after the second recording with words, or as soon as notes were ready that no
     *   notification could tell of ([NudgeFacts.notesMissed]), while notifications aren't allowed
     *   and Android's window was never asked for, by this or the to-do reminders' sheet, and AI
     *   notes are on, as without them no notes are ever ready to ping for;
     * - the bigger AI model after the third recording with notes, while it is neither on the phone
     *   nor on its way, AI notes are on, the network costs nothing, the phone charges (the owner's
     *   decision of 2026-10-15: "on Wi-Fi, while charging"), 5 GB are free, and its build flag
     *   ([e2bOn]) is on.
     * Neither while a recording is made or just after one ([offer] looks first).
     */
    fun due(id: NudgeId, facts: NudgeFacts, e2bOn: Boolean = E2B_OFFER_ON): Boolean = when (id) {
        NudgeId.NOTIFY_PING -> (facts.recordingsWithWords >= PING_AFTER_RECORDINGS || facts.notesMissed) && stillOffers(id, facts, e2bOn)
        NudgeId.E2B_OFFER -> facts.recordingsWithNotes >= E2B_AFTER_RECORDINGS && stillOffers(id, facts, e2bOn) &&
            facts.unmetered && facts.charging &&
            facts.freeBytes >= E2B_FREE_BYTES
        NudgeId.RETENTION, NudgeId.FIRST_NOTES -> false
    }

    /**
     * Whether [id] still has something to offer, on show or not: notifications that Android can
     * still be asked for, and were not asked for elsewhere meanwhile nor are being asked for by the
     * to-do reminders' sheet, for notes that AI notes write;
     * a bigger AI model not on the phone, for AI notes that are on. What only picks the moment of
     * the first showing (the network, the battery, the space) doesn't take one on show away.
     */
    private fun stillOffers(id: NudgeId, facts: NudgeFacts, e2bOn: Boolean): Boolean = when (id) {
        NudgeId.NOTIFY_PING -> facts.aiNotesOn && !facts.notificationsAllowed && facts.notificationsAskable &&
            !facts.notificationsAsked && !facts.reminderAskDue
        NudgeId.E2B_OFFER -> e2bOn && !facts.biggerModelHere && facts.aiNotesOn
        NudgeId.RETENTION, NudgeId.FIRST_NOTES -> false
    }

    private fun quiet(facts: NudgeFacts): Boolean = facts.recordingLive || facts.justStopped
}
