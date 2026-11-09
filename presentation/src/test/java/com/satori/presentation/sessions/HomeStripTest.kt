package com.satori.presentation.sessions

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.NudgeLedger
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.TranscriptionSession
import com.satori.presentation.firstrun.StripHold
import com.satori.presentation.firstrun.StripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which strip home shows, from the first run, the ledger, the recordings and the phone (design 3.8, 3.9, 4.1). */
class HomeStripTest {

    private val now = 1_800_000_000_000L
    private val hourAgo = now - 3_600_000L
    private val aiBytes = 1_069_306_368L

    private val done = FirstRunSnapshot(stage = FirstRunStage.DONE, network = NetworkFacts.WIFI)
    private val aiPlanned = FirstRunPlan.initial(speechBytes = 670_478_772L, malayalamBytes = 137_566_803L, aiModelBytes = aiBytes)
        .done(FirstRunPart.SPEECH).done(FirstRunPart.MALAYALAM)
    private val downloading = done.copy(
        plan = aiPlanned,
        later = FirstRunProgress(FirstRunPart.AI_MODEL, DownloadState.Downloading(DownloadProgress(246_000_000L, aiBytes, 23)), 246_000_000L, aiBytes, minutesLeft = 3)
    )

    /** Notifications off and never asked: the ping can be offered. */
    private val phone = PhoneFacts(notificationsAskable = true, charging = true, freeBytes = 9_000_000_000L)

    private fun recording(id: String, createdAt: Long = hourAgo, words: Boolean = true, notes: Boolean = false, lengthMs: Long = 60_000L) =
        SessionListItem(
            session = TranscriptionSession(id = id, name = null, createdAt = createdAt, lastModifiedAt = createdAt, durationMs = lengthMs),
            hasTranscript = words,
            hasNotes = notes
        )

    private fun decide(
        snapshot: FirstRunSnapshot = done,
        ledger: NudgeLedger = NudgeLedger(),
        items: List<SessionListItem> = emptyList(),
        capturing: String? = null,
        finishing: Set<String> = emptySet(),
        phone: PhoneFacts = this.phone,
        live: Boolean = false,
        lastStopAt: Long = 0L,
        at: Long = now,
        e2bOn: Boolean = false,
        malayalamOnPhone: Boolean = false,
        reminders: Boolean = false,
        aiNotesOn: Boolean = true
    ): HomeStrip.Decision = HomeStrip.decide(
        snapshot = snapshot,
        ledger = ledger,
        recordings = StripRecordings.of(items, capturing, finishing),
        phone = phone,
        aiNotesOn = aiNotesOn,
        malayalamOnPhone = malayalamOnPhone,
        recordingLive = live,
        lastStopAtMs = lastStopAt,
        nowMs = at,
        remindersPending = reminders,
        e2bOn = e2bOn
    )

    @Test
    fun `after the first Stop the AI model's download shows, its row waiting for it`() {
        val strip = decide(downloading, items = listOf(recording("first")), finishing = setOf("first")).strip
        assertEquals(StripState.Download(FirstRunPart.AI_MODEL, 246_000_000L, aiBytes, minutesLeft = 3), strip)
    }

    @Test
    fun `a second recording pauses it, and the strip says so, also while recording`() {
        val paused = downloading.copy(later = downloading.later?.copy(state = DownloadState.Paused(PauseReason.RECORDING), minutesLeft = null))
        val strip = decide(paused, items = listOf(recording("second", createdAt = now - 1_000L), recording("first")), capturing = "second", live = true).strip
        assertEquals(StripHold.RECORDING, (strip as StripState.Download).hold)
    }

    @Test
    fun `the first notes are confirmed once written, but not within a minute of a Stop`() {
        val noted = listOf(recording("first", notes = true))
        assertEquals(StripState.FirstNotes("first"), decide(items = noted).strip)

        val decision = decide(items = noted, lastStopAt = now - 30_000L)
        assertNull(decision.strip)
        assertEquals(now + 30_000L, decision.nextChangeAtMs)
        assertEquals(StripState.FirstNotes("first"), decide(items = noted, lastStopAt = now - 30_000L, at = now + 30_000L).strip)
    }

    @Test
    fun `notes still being finished, or of the recording being made, are not the first notes yet`() {
        val noted = listOf(recording("first", notes = true))
        assertNull(decide(items = noted, finishing = setOf("first")).strip)
        assertNull(decide(items = noted, capturing = "first", live = true).strip)
    }

    @Test
    fun `the first notes are those of the oldest recording that has some`() {
        val items = listOf(recording("third", createdAt = now - 600_000L, notes = true), recording("second", createdAt = now - 900_000L, notes = true), recording("first", words = false))
        assertEquals(StripState.FirstNotes("second"), decide(items = items, ledger = NudgeLedger().answered(NudgeId.NOTIFY_PING, false, 0L)).strip)
    }

    @Test
    fun `a Stop home didn't see keeps it quiet from the recording's end`() {
        // Started 70 s ago, 30 s long: it stopped 40 s ago.
        val items = listOf(recording("first", createdAt = now - 70_000L, notes = true, lengthMs = 30_000L))
        val decision = decide(items = items)
        assertNull(decision.strip)
        assertEquals(now + 20_000L, decision.nextChangeAtMs)
    }

    @Test
    fun `the ping comes after the second recording with words, once the first notes were confirmed`() {
        val two = listOf(recording("second"), recording("first", notes = true))
        val confirmed = NudgeLedger().answered(NudgeId.FIRST_NOTES, accepted = true, atMs = hourAgo)
        assertEquals(StripState.FirstNotes("first"), decide(items = two).strip)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), decide(items = two, ledger = confirmed).strip)
        assertNull(decide(items = two.take(1)).strip)
    }

    @Test
    fun `the ping waits while the to-do reminders' sheet asks, which leaves it nothing once answered`() {
        val two = listOf(recording("second"), recording("first", notes = true))
        val confirmed = NudgeLedger().answered(NudgeId.FIRST_NOTES, accepted = true, atMs = hourAgo)
        // An open to-do with a reminder: the sheet asks, so the ping doesn't, on show or not.
        assertNull(decide(items = two, ledger = confirmed, reminders = true).strip)
        val onShow = confirmed.shown(NudgeId.NOTIFY_PING, now - 60_000L)
        assertNull(decide(items = two, ledger = onShow, reminders = true).strip)
        // The sheet answered: the window counts as asked, and the ping has nothing left to offer.
        assertNull(decide(items = two, ledger = onShow, phone = phone.copy(notificationsAsked = true)).strip)
        // Without reminders the ping is offered as before.
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), decide(items = two, ledger = onShow).strip)
    }

    @Test
    fun `home is quiet for asks while a recorder is live and for a minute after a Stop`() {
        assertEquals(true, decide(live = true).quiet)
        assertEquals(true, decide(lastStopAt = now - 59_000L).quiet)
        assertEquals(false, decide(lastStopAt = now - 61_000L).quiet)
    }

    @Test
    fun `the bigger model's offer stays off with its build flag, and comes when it is on and all holds`() {
        val three = listOf(recording("c", notes = true), recording("b", notes = true), recording("a", notes = true))
        val allowed = phone.copy(notificationsAllowed = true)
        val confirmed = NudgeLedger().answered(NudgeId.FIRST_NOTES, accepted = true, atMs = hourAgo)
        assertNull(decide(items = three, ledger = confirmed, phone = allowed).strip)
        assertEquals(StripState.Nudge(NudgeId.E2B_OFFER), decide(items = three, ledger = confirmed, phone = allowed, e2bOn = true).strip)
        // Planned already, as its offer was taken, or on mobile data: no offer.
        val planned = done.copy(plan = aiPlanned.withBiggerModel(3_349_516_256L))
        assertNull(decide(planned, ledger = confirmed, items = three, phone = allowed, e2bOn = true).strip)
        assertNull(decide(done.copy(network = NetworkFacts.MOBILE), ledger = confirmed, items = three, phone = allowed, e2bOn = true).strip)
    }

    @Test
    fun `what needs him comes before the confirmation and the offers`() {
        val waitsForWifi = downloading.copy(later = downloading.later?.copy(state = DownloadState.Paused(PauseReason.WIFI_ONLY), minutesLeft = null))
        val noted = listOf(recording("second"), recording("first", notes = true))
        assertEquals(StripHold.WAITING_FOR_WIFI, (decide(waitsForWifi, items = noted).strip as StripState.Download).hold)
    }

    @Test
    fun `a dropped Malayalam is offered again once, and not once it is on the phone`() {
        val dropped = done.copy(
            plan = aiPlanned.copy(items = aiPlanned.items.map { if (it.part == FirstRunPart.MALAYALAM) it.copy(status = FirstRunPlan.Status.PENDING) else it })
                .failed(FirstRunPart.MALAYALAM, DownloadFailure.SERVER).failed(FirstRunPart.MALAYALAM, DownloadFailure.SERVER)
        )
        assertEquals(FirstRunPlan.Status.DROPPED, dropped.plan.item(FirstRunPart.MALAYALAM)?.status)
        assertEquals(StripState.MalayalamFailed, decide(dropped).strip)
        assertNull(decide(dropped, ledger = NudgeLedger().malayalamRetryUsed()).strip)
        assertNull(decide(dropped, malayalamOnPhone = true).strip)
    }

    @Test
    fun `a dropped reader shows the same strip once, with AI notes on, apart from Malayalam's own Try again`() {
        val dropped = done.copy(
            plan = aiPlanned.done(FirstRunPart.AI_MODEL).withReader(241_989_173L)
                .failed(FirstRunPart.READER, DownloadFailure.OTHER).failed(FirstRunPart.READER, DownloadFailure.OTHER)
        )
        assertEquals(FirstRunPlan.Status.DROPPED, dropped.plan.item(FirstRunPart.READER)?.status)
        assertEquals(StripState.MalayalamFailed, decide(dropped, malayalamOnPhone = true).strip)
        // Its own Try again, not Malayalam's, used up.
        assertEquals(StripState.MalayalamFailed, decide(dropped, ledger = NudgeLedger().malayalamRetryUsed(), malayalamOnPhone = true).strip)
        assertNull(decide(dropped, ledger = NudgeLedger().readerRetryUsed(), malayalamOnPhone = true).strip)
        // Only AI notes use it.
        assertNull(decide(dropped, malayalamOnPhone = true, aiNotesOn = false).strip)
    }

    @Test
    fun `an offer on show is next looked at when it goes, a day after it was shown`() {
        val two = listOf(recording("second"), recording("first"))
        val shown = NudgeLedger().shown(NudgeId.NOTIFY_PING, atMs = hourAgo)
        val decision = decide(items = two, ledger = shown)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), decision.strip)
        assertEquals(hourAgo + NudgeLedger.SHOWN_FOR_MS, decision.nextChangeAtMs)
        assertNull(decide(items = two, ledger = shown, at = hourAgo + NudgeLedger.SHOWN_FOR_MS).strip)
    }

    @Test
    fun `nothing to show and nothing due is no strip, and no wake`() {
        assertEquals(HomeStrip.Decision(null, null), decide())
    }

    // A quick-key owner's first day, as offsets (s) from the first recording's Stop: the notes are
    // ready at +126 s while Satori is out of sight, with notifications not allowed; he looks at home
    // at +204 s, a few times around +1600 s, opens the recording at +6872 s, and is back at +6892 s.
    private val stop = now - 6_000_000L
    private fun at(s: Long) = stop + s * 1_000L
    private val firstOnly = listOf(recording("first", createdAt = at(-46), notes = true, lengthMs = 46_000L))
    private val missedOnce = phone.copy(notesMissed = 1)
    private val ping = StripState.Nudge(NudgeId.NOTIFY_PING)
    private val missedPing = StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true)

    @Test
    fun `notes no notification could tell of make the ping due at the first look, ahead of the first notes' confirmation`() {
        // Before 0.7.2 that look got the confirmation, and one recording never made the ping due.
        assertEquals(StripState.FirstNotes("first"), decide(items = firstOnly, at = at(204)).strip)
        assertEquals(missedPing, decide(items = firstOnly, phone = missedOnce, at = at(204)).strip)

        // Once the ping is answered, either way, the confirmation follows.
        for (accepted in listOf(true, false)) {
            val answered = NudgeLedger().shown(NudgeId.NOTIFY_PING, at(204)).answered(NudgeId.NOTIFY_PING, accepted, at(210))
            val asked = if (accepted) missedOnce.copy(notificationsAsked = true) else missedOnce
            assertEquals("$accepted", StripState.FirstNotes("first"), decide(items = firstOnly, ledger = answered, phone = asked, at = at(220)).strip)
        }
    }

    @Test
    fun `a first notes' confirmation already on show keeps its place over a later miss, and the ping comes after it`() {
        val confirming = NudgeLedger().shown(NudgeId.FIRST_NOTES, at(204))
        for (visit in listOf(1_548L, 1_805L, 2_202L, 6_867L)) {
            assertEquals("$visit", StripState.FirstNotes("first"), decide(items = firstOnly, ledger = confirming, phone = missedOnce, at = at(visit)).strip)
        }
        // The recording opened at +6872 s: back on home the ping asks, where before nothing came.
        val opened = confirming.gone(NudgeId.FIRST_NOTES)
        assertNull(decide(items = firstOnly, ledger = opened, at = at(6_892)).strip)
        assertEquals(missedPing, decide(items = firstOnly, ledger = opened, phone = missedOnce, at = at(6_892)).strip)
    }

    @Test
    fun `the missed ping waits out a live recording and the minute after a Stop, and asks once it is over`() {
        val second = recording("second", createdAt = at(92_008), notes = true, lengthMs = 30_000L)
        val both = listOf(second) + firstOnly
        val done = NudgeLedger().shown(NudgeId.FIRST_NOTES, at(204)).gone(NudgeId.FIRST_NOTES)
        assertNull(decide(items = both, ledger = done, phone = missedOnce, live = true, at = at(92_020)).strip)
        val justStopped = decide(items = both, ledger = done, phone = missedOnce, at = at(92_060))
        assertNull(justStopped.strip)
        assertEquals(true, justStopped.quiet)
        assertEquals(at(92_098), justStopped.nextChangeAtMs)
        assertEquals(missedPing, decide(items = both, ledger = done, phone = missedOnce, at = at(92_099)).strip)
    }

    @Test
    fun `two recordings without a miss give the ping of before, one line, after the confirmation`() {
        val two = listOf(recording("second"), recording("first", notes = true))
        val confirmed = NudgeLedger().answered(NudgeId.FIRST_NOTES, accepted = true, atMs = hourAgo)
        assertEquals(StripState.FirstNotes("first"), decide(items = two).strip)
        assertEquals(ping, decide(items = two, ledger = confirmed).strip)
        // A miss later on says so on the ping on show.
        val onShow = confirmed.shown(NudgeId.NOTIFY_PING, now - 60_000L)
        assertEquals(missedPing, decide(items = two, ledger = onShow, phone = phone.copy(notesMissed = 1)).strip)
    }

    @Test
    fun `a miss makes no ping where Android can't ask, it asked before, or notifications came on`() {
        for (facts in listOf(missedOnce.copy(notificationsAskable = false), missedOnce.copy(notificationsAsked = true), missedOnce.copy(notificationsAllowed = true))) {
            assertEquals("$facts", StripState.FirstNotes("first"), decide(items = firstOnly, phone = facts, at = at(204)).strip)
        }
    }
}
