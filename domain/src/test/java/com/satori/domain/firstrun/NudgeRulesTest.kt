package com.satori.domain.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When home's offers are made (design 5.6, F2 to F4), and the first notes' confirmation. */
class NudgeRulesTest {

    private val now = 10_000_000L
    private val empty = NudgeLedger()

    /** After the second recording with words, notifications never asked for, AI notes on. */
    private val pingDue = NudgeFacts(recordingsWithWords = 2, notificationsAskable = true, aiNotesOn = true)

    /** After the third recording with notes, on Wi-Fi, charging, with room: everything the bigger model's offer asks. */
    private val e2bDue = NudgeFacts(
        recordingsWithWords = 3,
        recordingsWithNotes = 3,
        notificationsAllowed = true,
        aiNotesOn = true,
        unmetered = true,
        charging = true,
        freeBytes = 6_000_000_000L
    )

    @Test
    fun `the ping comes after the second recording with words, not the first`() {
        assertNull(NudgeRules.offer(pingDue.copy(recordingsWithWords = 1), empty, now))
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(pingDue, empty, now))
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(pingDue.copy(recordingsWithWords = 3), empty, now))
    }

    @Test
    fun `notes that no notification could tell of make the ping due from the first recording, or none`() {
        val missed = pingDue.copy(recordingsWithWords = 1, notesMissed = true)
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(missed, empty, now))
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(missed.copy(recordingsWithWords = 0), empty, now))
        // Without a miss one recording is still not enough.
        assertNull(NudgeRules.offer(missed.copy(notesMissed = false), empty, now))
    }

    @Test
    fun `a ping due by missed notes is still held by every other condition, one at a time`() {
        val missed = pingDue.copy(recordingsWithWords = 1, notesMissed = true)
        val oneFalse = mapOf(
            "notifications allowed" to missed.copy(notificationsAllowed = true),
            "Android can't ask" to missed.copy(notificationsAskable = false),
            "asked before" to missed.copy(notificationsAsked = true),
            "the to-do sheet asks" to missed.copy(reminderAskDue = true),
            "AI notes off" to missed.copy(aiNotesOn = false),
            "a recording live" to missed.copy(recordingLive = true),
            "just stopped" to missed.copy(justStopped = true)
        )
        for ((condition, facts) in oneFalse) {
            assertNull(condition, NudgeRules.offer(facts, empty, now))
        }
        // Once only, as any offer.
        assertNull(NudgeRules.offer(missed, empty.answered(NudgeId.NOTIFY_PING, accepted = false, atMs = now), now + 1))
        assertNull(NudgeRules.offer(missed, empty.shown(NudgeId.NOTIFY_PING, atMs = now).faded(now + NudgeLedger.SHOWN_FOR_MS), now + NudgeLedger.SHOWN_FOR_MS))
    }

    @Test
    fun `the ping isn't offered when notifications are allowed, Android can't ask, or the to-do sheet asked`() {
        assertNull(NudgeRules.offer(pingDue.copy(notificationsAllowed = true), empty, now))
        assertNull(NudgeRules.offer(pingDue.copy(notificationsAskable = false), empty, now))
        assertNull(NudgeRules.offer(pingDue.copy(notificationsAsked = true), empty, now))
    }

    @Test
    fun `without AI notes no notes are ever ready, so the ping isn't offered`() {
        assertNull(NudgeRules.offer(pingDue.copy(aiNotesOn = false), empty, now))
        val shown = empty.shown(NudgeId.NOTIFY_PING, atMs = now)
        assertNull(NudgeRules.offer(pingDue.copy(aiNotesOn = false), shown, now + 1))
    }

    @Test
    fun `Not now means never again, and so does a ping left alone for a day`() {
        val declined = empty.answered(NudgeId.NOTIFY_PING, accepted = false, atMs = now)
        assertNull(NudgeRules.offer(pingDue, declined, now + 1))

        val shown = empty.shown(NudgeId.NOTIFY_PING, atMs = now)
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(pingDue, shown, now + NudgeLedger.SHOWN_FOR_MS - 1))
        assertNull(NudgeRules.offer(pingDue, shown, now + NudgeLedger.SHOWN_FOR_MS))
    }

    @Test
    fun `the bigger model is offered only when all eight of its conditions hold`() {
        assertEquals(NudgeId.E2B_OFFER, NudgeRules.offer(e2bDue, empty, now, e2bOn = true))
        val oneFalse = mapOf(
            "the third recording with notes" to e2bDue.copy(recordingsWithNotes = 2),
            "not on the phone" to e2bDue.copy(biggerModelHere = true),
            "AI notes on" to e2bDue.copy(aiNotesOn = false),
            "a network that costs nothing" to e2bDue.copy(unmetered = false),
            "charging" to e2bDue.copy(charging = false),
            "5 GB free" to e2bDue.copy(freeBytes = 4_999_999_999L),
            "no recording live" to e2bDue.copy(recordingLive = true)
        )
        for ((condition, facts) in oneFalse) {
            assertNull(condition, NudgeRules.offer(facts, empty, now, e2bOn = true))
        }
        // The eighth: the build flag, off until the memory runs pass.
        assertNull("the build flag", NudgeRules.offer(e2bDue, empty, now, e2bOn = false))
    }

    @Test
    fun `a full battery without a charger is not enough, as the owner decided`() {
        assertNull(NudgeRules.offer(e2bDue.copy(charging = false), empty, now, e2bOn = true))
    }

    @Test
    fun `the build flag is off, so the bigger model is never offered in this build`() {
        assertFalse(NudgeRules.E2B_OFFER_ON)
        assertNull(NudgeRules.offer(e2bDue, empty, now))
    }

    @Test
    fun `the bigger model is offered once`() {
        val shown = empty.shown(NudgeId.E2B_OFFER, atMs = now)
        assertEquals(NudgeId.E2B_OFFER, NudgeRules.offer(e2bDue, shown, now + 1, e2bOn = true))
        for (answered in listOf(shown.answered(NudgeId.E2B_OFFER, accepted = true, atMs = now), shown.faded(now + NudgeLedger.SHOWN_FOR_MS))) {
            assertNull(NudgeRules.offer(e2bDue, answered, now + NudgeLedger.SHOWN_FOR_MS, e2bOn = true))
        }
    }

    @Test
    fun `an offer on show stays when the moment it was picked for passes, and goes when nothing is left to offer`() {
        val shown = empty.shown(NudgeId.E2B_OFFER, atMs = now)
        val unplugged = e2bDue.copy(unmetered = false, charging = false, freeBytes = 0L)
        assertEquals(NudgeId.E2B_OFFER, NudgeRules.offer(unplugged, shown, now + 1, e2bOn = true))
        assertNull(NudgeRules.offer(e2bDue.copy(biggerModelHere = true), shown, now + 1, e2bOn = true))

        val pingShown = empty.shown(NudgeId.NOTIFY_PING, atMs = now)
        assertNull(NudgeRules.offer(pingDue.copy(notificationsAllowed = true), pingShown, now + 1))
    }

    @Test
    fun `two offers due together show one at a time, the ping first, and the one on show keeps its place`() {
        val both = e2bDue.copy(notificationsAllowed = false, notificationsAskable = true)
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(both, empty, now, e2bOn = true))

        val pingShown = empty.shown(NudgeId.NOTIFY_PING, atMs = now)
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(both, pingShown, now + 1, e2bOn = true))

        val pingAnswered = pingShown.answered(NudgeId.NOTIFY_PING, accepted = false, atMs = now + 1)
        assertEquals(NudgeId.E2B_OFFER, NudgeRules.offer(both, pingAnswered, now + 2, e2bOn = true))

        // The bigger model on show stays ahead of a ping that became due after it.
        val e2bShown = empty.shown(NudgeId.E2B_OFFER, atMs = now)
        assertEquals(NudgeId.E2B_OFFER, NudgeRules.offer(both, e2bShown, now + 1, e2bOn = true))
    }

    @Test
    fun `nothing is offered or confirmed while a recording is made or within a minute of its Stop`() {
        val noted = pingDue.copy(recordingsWithNotes = 1)
        for (quiet in listOf(noted.copy(recordingLive = true), noted.copy(justStopped = true))) {
            assertNull(NudgeRules.offer(quiet, empty, now))
            assertFalse(NudgeRules.firstNotes(quiet, empty, now))
        }
        // An offer on show waits too, and is back afterwards.
        val shown = empty.shown(NudgeId.NOTIFY_PING, atMs = now)
        assertNull(NudgeRules.offer(pingDue.copy(justStopped = true), shown, now + 1))
        assertEquals(NudgeId.NOTIFY_PING, NudgeRules.offer(pingDue, shown, now + 1))
    }

    @Test
    fun `the first notes are confirmed once the first recording has its notes, until a day has passed or a recording was opened`() {
        val facts = NudgeFacts(recordingsWithWords = 1, recordingsWithNotes = 1)
        assertFalse(NudgeRules.firstNotes(facts.copy(recordingsWithNotes = 0), empty, now))
        assertTrue(NudgeRules.firstNotes(facts, empty, now))

        val shown = empty.shown(NudgeId.FIRST_NOTES, atMs = now)
        assertTrue(NudgeRules.firstNotes(facts, shown, now + NudgeLedger.SHOWN_FOR_MS - 1))
        assertFalse(NudgeRules.firstNotes(facts, shown, now + NudgeLedger.SHOWN_FOR_MS))
        assertFalse(NudgeRules.firstNotes(facts, shown.gone(NudgeId.FIRST_NOTES), now + 1))
        assertFalse(NudgeRules.firstNotes(facts, shown.answered(NudgeId.FIRST_NOTES, accepted = true, atMs = now), now + 1))
    }

    @Test
    fun `the optional retention offer and the confirmation are never offers`() {
        val all = e2bDue.copy(notificationsAllowed = false, notificationsAskable = true)
        for (id in listOf(NudgeId.RETENTION, NudgeId.FIRST_NOTES)) {
            assertFalse(NudgeRules.due(id, all, e2bOn = true))
        }
    }
}
