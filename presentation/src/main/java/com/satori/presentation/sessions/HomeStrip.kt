package com.satori.presentation.sessions

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.NudgeFacts
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.NudgeLedger
import com.satori.domain.firstrun.NudgeRules
import com.satori.domain.model.SessionListItem
import com.satori.domain.usecase.todo.ReminderPrompt
import com.satori.domain.usecase.todo.ReminderPrompts
import com.satori.presentation.firstrun.StripState

/**
 * The recordings as home's strip counts them, for its offers and its confirmation.
 *
 * @property withWords Saved recordings with words in them; the one being made doesn't count
 * @property withNotes The saved recordings whose notes are written and whose finishing is done,
 *   oldest first: the first of them has the first notes
 * @property lastEndedAtMs When the newest saved recording ended (epoch ms), its start plus its
 *   length: a Stop the strip didn't see, as home was not open, still keeps it quiet for a minute
 */
data class StripRecordings(
    val withWords: Int = 0,
    val withNotes: List<String> = emptyList(),
    val lastEndedAtMs: Long = 0L
) {
    companion object {
        /**
         * [items], every recording, counted without the one being made ([capturingId]); one being
         * finished ([finishing]) has words, but not yet all its notes.
         */
        fun of(items: List<SessionListItem>, capturingId: String?, finishing: Set<String>): StripRecordings {
            val saved = items.filter { it.session.id != capturingId }
            return StripRecordings(
                withWords = saved.count { it.hasTranscript },
                withNotes = saved.filter { it.hasNotes && it.session.id !in finishing }
                    .sortedBy { it.session.createdAt }
                    .map { it.session.id },
                lastEndedAtMs = saved.maxOfOrNull { it.session.createdAt + it.session.durationMs } ?: 0L
            )
        }
    }
}

/**
 * Which strip home shows, and when to look again, from all it reads: the first run's snapshot (the
 * download after the setup, and Malayalam dropped from it), the nudge ledger, the recordings, the
 * phone, whether a recorder is live and when a recording last stopped. Pure, so every state of the
 * strip is tested without a screen.
 *
 * Notes that were ready while the app was out of sight, with notifications not allowed, make the
 * ping due at once ([PhoneFacts.notesMissed]): an owner who records by the quick key over the lock
 * screen next sees Satori on home, unlocked, and that look asks, before the first notes'
 * confirmation unless it is on show already. The ask still waits out a live recording and the
 * minute after a Stop, and shows only on home, where it counts as shown once seen unlocked.
 */
object HomeStrip {

    /**
     * @property strip The strip to show, or null for none
     * @property nextChangeAtMs When the strip may change with nothing else changing (epoch ms): a
     *   minute after a Stop, or when a nudge on show goes; null when nothing is due
     * @property quiet A recorder is live or a recording stopped less than
     *   [NudgeRules.QUIET_AFTER_STOP_MS] ago: no offer shows, and the to-do reminders' sheet waits too
     */
    data class Decision(val strip: StripState?, val nextChangeAtMs: Long?, val quiet: Boolean = false)

    /**
     * @param malayalamOnPhone The Malayalam speech model is on the phone, so a Malayalam the first
     *   run dropped and then got from Settings is no longer offered
     * @param recordingLive A recorder is starting, recording or saving
     * @param lastStopAtMs When home saw a recorder stop (epoch ms); 0 when it saw none
     * @param remindersPending An open to-do has a reminder still to come, so the to-do reminders'
     *   sheet asks for notifications unless they are on or were asked for ([ReminderPrompts]); the
     *   ping doesn't ask beside it
     * @param e2bOn The bigger AI model's build flag ([NudgeRules.E2B_OFFER_ON]), given for the tests
     */
    fun decide(
        snapshot: FirstRunSnapshot,
        ledger: NudgeLedger,
        recordings: StripRecordings,
        phone: PhoneFacts,
        aiNotesOn: Boolean,
        malayalamOnPhone: Boolean,
        recordingLive: Boolean,
        lastStopAtMs: Long,
        nowMs: Long,
        remindersPending: Boolean = false,
        e2bOn: Boolean = NudgeRules.E2B_OFFER_ON
    ): Decision {
        val quietUntil = maxOf(lastStopAtMs, recordings.lastEndedAtMs) + NudgeRules.QUIET_AFTER_STOP_MS
        val reminderAsk = ReminderPrompts.decide(
            hasReminders = remindersPending,
            notificationsOn = phone.notificationsAllowed,
            canAsk = phone.notificationsAskable,
            alreadyAsked = phone.notificationsAsked
        ) == ReminderPrompt.Ask
        val facts = NudgeFacts(
            recordingsWithWords = recordings.withWords,
            recordingsWithNotes = recordings.withNotes.size,
            recordingLive = recordingLive,
            justStopped = nowMs < quietUntil,
            notificationsAllowed = phone.notificationsAllowed,
            notificationsAskable = phone.notificationsAskable,
            notificationsAsked = phone.notificationsAsked,
            reminderAskDue = reminderAsk,
            notesMissed = phone.notesMissed > 0,
            biggerModelHere = phone.biggerModelOnPhone || snapshot.plan.item(FirstRunPart.BIGGER_AI_MODEL) != null,
            aiNotesOn = aiNotesOn,
            unmetered = snapshot.network.connected && !snapshot.network.metered,
            charging = phone.charging,
            freeBytes = phone.freeBytes
        )
        val malayalamFailed = malayalamDropped(snapshot, ledger, malayalamOnPhone) || readerDropped(snapshot, ledger, aiNotesOn)
        val firstNotes = recordings.withNotes.firstOrNull()
            ?.takeIf { NudgeRules.firstNotes(facts, ledger, nowMs) }
            ?.let(StripState::FirstNotes)
        val nudge = NudgeRules.offer(facts, ledger, nowMs, e2bOn)?.let { id ->
            StripState.Nudge(id, missedNotes = id == NudgeId.NOTIFY_PING && facts.notesMissed)
        }
        val strip = StripState.first(
            download = StripState.download(snapshot.later, snapshot.plan, snapshot.space),
            malayalamFailed = malayalamFailed,
            firstNotes = firstNotes,
            nudge = nudge,
            // Notes no notification could tell of: the first look after them asks, also before a
            // first notes' confirmation not yet on show; one on show keeps its place.
            nudgeFirst = nudge?.missedNotes == true && !ledger.onShow(NudgeId.FIRST_NOTES, nowMs)
        )
        val nextChange = listOfNotNull(quietUntil, ledger.nextFadeAtMs).filter { it > nowMs }.minOrNull()
        return Decision(strip, nextChange, quiet = facts.recordingLive || facts.justStopped)
    }

    /**
     * Malayalam was dropped by the first run, the strip's one Try again for it is unused, and the
     * model didn't come another way (Settings): [StripState.MalayalamFailed] offers it.
     */
    fun malayalamDropped(snapshot: FirstRunSnapshot, ledger: NudgeLedger, malayalamOnPhone: Boolean): Boolean =
        snapshot.plan.item(FirstRunPart.MALAYALAM)?.status == FirstRunPlan.Status.DROPPED && !ledger.malayalamRetried && !malayalamOnPhone

    /**
     * The reader was dropped by the first run (0.8.0, design R7), the strip's one Try again for it is
     * unused, and AI notes, which alone use it, are on: the same strip as for Malayalam offers it, in
     * the same words, as no string names the reader.
     */
    fun readerDropped(snapshot: FirstRunSnapshot, ledger: NudgeLedger, aiNotesOn: Boolean): Boolean =
        snapshot.plan.item(FirstRunPart.READER)?.status == FirstRunPlan.Status.DROPPED && !ledger.readerRetried && aiNotesOn
}
