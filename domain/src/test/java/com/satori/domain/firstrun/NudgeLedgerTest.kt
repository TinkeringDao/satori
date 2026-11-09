package com.satori.domain.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nudge ledger: each nudge shown once, ever, on show until answered or a day old, and the same after a restart. */
class NudgeLedgerTest {

    private val day = NudgeLedger.SHOWN_FOR_MS
    private val ping = NudgeId.NOTIFY_PING

    /** The ledger as a restart reads it back: stored, then read. */
    private fun NudgeLedger.restarted(): NudgeLedger = NudgeLedger.read(stored())

    @Test
    fun `a nudge never shown may show, and once shown it stays on show for a day, then never again`() {
        val fresh = NudgeLedger()
        assertTrue(fresh.open(ping, nowMs = 0L))
        assertFalse(fresh.onShow(ping, nowMs = 0L))

        val shown = fresh.shown(ping, atMs = 1_000L)
        assertEquals(NudgeState.SHOWN, shown.state(ping))
        assertTrue(shown.onShow(ping, nowMs = 1_000L + day - 1))
        assertFalse(shown.open(ping, nowMs = 1_000L + day))

        val faded = shown.faded(nowMs = 1_000L + day)
        assertEquals(NudgeState.GONE, faded.state(ping))
        assertFalse(faded.open(ping, nowMs = 1_000L + day))
        // Gone is gone: it isn't shown again, whatever comes.
        assertEquals(faded, faded.shown(ping, atMs = 5 * day))
    }

    @Test
    fun `showing it again keeps the first time, so a second look doesn't make it last longer`() {
        val shown = NudgeLedger().shown(ping, atMs = 1_000L)
        assertEquals(shown, shown.shown(ping, atMs = 50_000L))
        assertEquals(1_000L + day, shown.nextFadeAtMs)
    }

    @Test
    fun `an answer ends it, and a tap before it counted as seen counts as its showing`() {
        val accepted = NudgeLedger().answered(ping, accepted = true, atMs = 7L)
        assertEquals(NudgeState.ACCEPTED, accepted.state(ping))
        assertEquals(NudgeEntry(NudgeState.ACCEPTED, shownAtMs = 7L), accepted.entries[ping])

        val declined = NudgeLedger().shown(ping, atMs = 3L).answered(ping, accepted = false, atMs = 9L)
        assertEquals(NudgeEntry(NudgeState.DECLINED, shownAtMs = 3L), declined.entries[ping])
        assertFalse(declined.open(ping, nowMs = 10L))
        // An answer is final: neither a second answer nor a fade changes it.
        assertEquals(declined, declined.answered(ping, accepted = true, atMs = 11L))
        assertEquals(declined, declined.faded(nowMs = 10 * day))
        assertNull(declined.nextFadeAtMs)
    }

    @Test
    fun `the first notes' confirmation goes when a recording is opened, only once it is on show`() {
        val notes = NudgeId.FIRST_NOTES
        assertEquals(NudgeLedger(), NudgeLedger().gone(notes))

        val gone = NudgeLedger().shown(notes, atMs = 0L).gone(notes)
        assertEquals(NudgeState.GONE, gone.state(notes))
        assertFalse(gone.open(notes, nowMs = 1L))
    }

    @Test
    fun `a confirmation that waited unseen goes too when what it says was seen, and stays gone after a restart`() {
        val notes = NudgeId.FIRST_NOTES
        val gone = NudgeLedger().gone(notes, unseen = true)
        assertEquals(NudgeEntry(NudgeState.GONE, shownAtMs = null), gone.entries[notes])
        assertFalse(gone.restarted().open(notes, nowMs = 1L))
        assertEquals(listOf(notes to NudgeState.GONE), NudgeLedger().changes(gone))
        // On show it goes as before; answered, it stays answered.
        assertEquals(NudgeState.GONE, NudgeLedger().shown(notes, atMs = 0L).gone(notes, unseen = true).state(notes))
        val opened = NudgeLedger().answered(notes, accepted = true, atMs = 0L)
        assertEquals(opened, opened.gone(notes, unseen = true))
    }

    @Test
    fun `a restart neither loses nor repeats a nudge`() {
        val ledger = NudgeLedger()
            .shown(ping, atMs = 1_000L)
            .answered(NudgeId.E2B_OFFER, accepted = false, atMs = 2_000L)
            .shown(NudgeId.FIRST_NOTES, atMs = 3_000L)
            .gone(NudgeId.FIRST_NOTES)
            .malayalamRetryUsed()

        val restarted = ledger.restarted()
        assertEquals(ledger, restarted)
        assertTrue(restarted.onShow(ping, nowMs = 2_000L))
        assertEquals(restarted, restarted.restarted().shown(ping, atMs = 9_000L))
        assertTrue(restarted.malayalamRetried)
        assertFalse(restarted.readerRetried)
    }

    @Test
    fun `the strip's one Try again for a dropped reader is kept apart from Malayalam's, across a restart`() {
        val ledger = NudgeLedger().readerRetryUsed()

        assertTrue(ledger.readerRetried)
        assertFalse(ledger.malayalamRetried)
        assertEquals(ledger, ledger.restarted())
        assertEquals(true, ledger.stored()[NudgeLedger.READER_RETRIED])
    }

    @Test
    fun `three restarts while a nudge is ignored show it once in all, and it goes after its day`() {
        var ledger = NudgeLedger()
        var showings = 0
        for (start in listOf(0L, day / 3, day / 2, day - 1, day + 1)) {
            ledger = ledger.restarted().faded(nowMs = start)
            if (ledger.open(ping, start)) {
                val before = ledger
                ledger = ledger.shown(ping, atMs = start)
                showings += before.changes(ledger).count { it == (ping to NudgeState.SHOWN) }
            }
        }
        assertEquals(1, showings)
        assertEquals(NudgeState.GONE, ledger.state(ping))
    }

    @Test
    fun `what a later version stored and this one doesn't know reads as never shown`() {
        val read = NudgeLedger.read(mapOf("NOTIFY_PING" to "SNOOZED", "SOME_NEW_NUDGE" to "SHOWN", "E2B_OFFER" to "DECLINED"))
        assertEquals(NudgeState.NEVER_SHOWN, read.state(ping))
        assertEquals(NudgeState.DECLINED, read.state(NudgeId.E2B_OFFER))
        assertFalse(read.malayalamRetried)
        assertFalse(read.readerRetried)
    }

    @Test
    fun `a nudge on show without the time it was shown goes, rather than staying for ever`() {
        val read = NudgeLedger.read(mapOf("NOTIFY_PING" to "SHOWN"))
        assertFalse(read.onShow(ping, nowMs = 0L))
        assertEquals(NudgeState.GONE, read.faded(nowMs = 0L).state(ping))
    }

    @Test
    fun `the field log gets each change once, in the ids' order`() {
        val before = NudgeLedger().shown(NudgeId.FIRST_NOTES, atMs = 0L)
        val after = before.shown(ping, atMs = 5L).gone(NudgeId.FIRST_NOTES).shown(ping, atMs = 6L)
        assertEquals(listOf(ping to NudgeState.SHOWN, NudgeId.FIRST_NOTES to NudgeState.GONE), before.changes(after))
        assertEquals(emptyList<Pair<NudgeId, NudgeState>>(), after.changes(after))
    }
}
