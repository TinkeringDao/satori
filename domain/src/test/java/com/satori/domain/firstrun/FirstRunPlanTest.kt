package com.satori.domain.firstrun

import com.satori.domain.firstrun.FirstRunPart.AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.BIGGER_AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.MALAYALAM
import com.satori.domain.firstrun.FirstRunPart.READER
import com.satori.domain.firstrun.FirstRunPart.SPEECH
import com.satori.domain.firstrun.FirstRunPlan.Status
import com.satori.domain.model.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunPlanTest {

    // The registry's bytes: Parakeet v3's four files, the Malayalam model's two, Gemma 3 1B Q8_0.
    private val speech = 670_478_772L
    private val malayalam = 137_566_803L
    private val aiModel = 1_069_306_368L
    private val plan = FirstRunPlan.initial(speech, malayalam, aiModel)

    @Test
    fun `speech comes first, then Malayalam, then the AI model`() {
        assertEquals(listOf(SPEECH, MALAYALAM, AI_MODEL), plan.items.map { it.part })
        assertEquals(listOf(SPEECH, MALAYALAM), plan.setup.map { it.part })
    }

    @Test
    fun `get ready's button counts speech and Malayalam, 808 MB`() {
        assertEquals(808_045_575L, plan.setupBytes)
        assertEquals(speech, FirstRunPlan.initial(speech, null, aiModel).setupBytes)
    }

    @Test
    fun `without the switch or the AI notes the parts are left out`() {
        assertEquals(listOf(SPEECH), FirstRunPlan.initial(speech, malayalamBytes = null, aiModelBytes = null).items.map { it.part })
    }

    @Test
    fun `the AI model waits for the first recording`() {
        val setUp = plan.done(SPEECH).done(MALAYALAM)

        assertEquals(SPEECH, plan.next(firstRecordingSaved = false)?.part)
        assertEquals(MALAYALAM, plan.done(SPEECH).next(firstRecordingSaved = false)?.part)
        assertNull(setUp.next(firstRecordingSaved = false))
        assertEquals(AI_MODEL, setUp.next(firstRecordingSaved = true)?.part)
        assertNull(setUp.done(AI_MODEL).next(firstRecordingSaved = true))
    }

    @Test
    fun `the setup is done when speech and Malayalam are on the phone, or Malayalam was dropped`() {
        assertFalse(plan.setupDone)
        assertFalse(plan.done(SPEECH).setupDone)
        assertTrue(plan.done(SPEECH).done(MALAYALAM).setupDone)

        val dropped = plan.done(SPEECH)
            .failed(MALAYALAM, DownloadFailure.OTHER)
            .failed(MALAYALAM, DownloadFailure.OTHER)
        assertEquals(Status.DROPPED, dropped.item(MALAYALAM)?.status)
        assertTrue(dropped.setupDone)
    }

    @Test
    fun `Malayalam is dropped after two server errors or two damaged files, not after one`() {
        assertEquals(Status.PENDING, plan.failed(MALAYALAM, DownloadFailure.SERVER).item(MALAYALAM)?.status)
        assertEquals(
            Status.DROPPED,
            plan.failed(MALAYALAM, DownloadFailure.SERVER).failed(MALAYALAM, DownloadFailure.SERVER).item(MALAYALAM)?.status
        )
        assertEquals(
            Status.DROPPED,
            plan.failed(MALAYALAM, DownloadFailure.OTHER).failed(MALAYALAM, DownloadFailure.OTHER).item(MALAYALAM)?.status
        )
        // One of each is no run of either.
        assertEquals(
            Status.PENDING,
            plan.failed(MALAYALAM, DownloadFailure.SERVER).failed(MALAYALAM, DownloadFailure.OTHER).item(MALAYALAM)?.status
        )
    }

    @Test
    fun `the network and a full disk are never held against a part`() {
        var failed = plan
        repeat(5) {
            for (failure in listOf(DownloadFailure.NO_CONNECTION, DownloadFailure.BLOCKED, DownloadFailure.NOT_ENOUGH_STORAGE)) {
                failed = failed.failed(MALAYALAM, failure).failed(SPEECH, failure)
            }
        }
        assertEquals(plan, failed)
    }

    @Test
    fun `speech is never dropped, after two damaged files it waits for Try again`() {
        val once = plan.failed(SPEECH, DownloadFailure.OTHER)
        val twice = once.failed(SPEECH, DownloadFailure.OTHER)

        assertFalse(once.item(SPEECH)!!.waitsForTap)
        assertEquals(Status.PENDING, twice.item(SPEECH)?.status)
        assertTrue(twice.item(SPEECH)!!.waitsForTap)
        // In order: the queue holds on it rather than skip to Malayalam.
        assertEquals(SPEECH, twice.next(firstRecordingSaved = false)?.part)
        assertFalse(twice.retried(SPEECH).item(SPEECH)!!.waitsForTap)
    }

    @Test
    fun `server errors are tried again by themselves, however many`() {
        var failed = plan
        repeat(5) { failed = failed.failed(SPEECH, DownloadFailure.SERVER) }

        assertEquals(Status.PENDING, failed.item(SPEECH)?.status)
        assertFalse(failed.item(SPEECH)!!.waitsForTap)
    }

    @Test
    fun `try again plans a dropped Malayalam anew`() {
        val dropped = plan.done(SPEECH).failed(MALAYALAM, DownloadFailure.SERVER).failed(MALAYALAM, DownloadFailure.SERVER)
        val again = dropped.retried(MALAYALAM)

        assertEquals(Status.PENDING, again.item(MALAYALAM)?.status)
        assertEquals(0, again.item(MALAYALAM)?.serverErrors)
        assertFalse(again.setupDone)
        assertTrue(again.pending(MALAYALAM))
        assertFalse(dropped.pending(MALAYALAM))
    }

    @Test
    fun `a part on the phone forgets its failures`() {
        val done = plan.failed(SPEECH, DownloadFailure.OTHER).done(SPEECH)

        assertEquals(Status.DONE, done.item(SPEECH)?.status)
        assertEquals(0, done.item(SPEECH)?.damaged)
    }

    @Test
    fun `the AI model is pending until it is on the phone`() {
        assertTrue(plan.pending(AI_MODEL))
        assertFalse(plan.done(AI_MODEL).pending(AI_MODEL))
        assertFalse(FirstRunPlan.initial(speech, malayalam, aiModelBytes = null).pending(AI_MODEL))
    }

    @Test
    fun `the bigger model joins the end once, when its offer is taken`() {
        val bigger = plan.withBiggerModel(3_349_516_256L)

        assertEquals(listOf(SPEECH, MALAYALAM, AI_MODEL, BIGGER_AI_MODEL), bigger.items.map { it.part })
        assertEquals(bigger, bigger.withBiggerModel(3_349_516_256L))
        assertEquals(plan.setupBytes, bigger.setupBytes)
    }

    // ── The reader (0.8.0, design R7) ────────────────────────────────────────

    // The int8 build's eleven files, from the registry.
    private val reader = 241_989_173L

    @Test
    fun `with Malayalam and AI notes the reader comes after the AI model, after the first recording, and not in the setup`() {
        val withReader = FirstRunPlan.initial(speech, malayalam, aiModel, reader)

        assertEquals(listOf(SPEECH, MALAYALAM, AI_MODEL, READER), withReader.items.map { it.part })
        assertEquals(plan.setupBytes, withReader.setupBytes)
        assertNull(withReader.done(SPEECH).done(MALAYALAM).next(firstRecordingSaved = false))
        assertEquals(READER, withReader.done(SPEECH).done(MALAYALAM).done(AI_MODEL).next(firstRecordingSaved = true)?.part)
        // Without its bytes, as without Malayalam or AI notes, or with the reader on the phone: none.
        assertNull(plan.item(READER))
    }

    @Test
    fun `a reader that fails twice is dropped alone, as Malayalam is, and Try again plans it anew`() {
        val withReader = FirstRunPlan.initial(speech, malayalam, aiModel, reader).done(SPEECH).done(MALAYALAM).done(AI_MODEL)

        val once = withReader.failed(READER, DownloadFailure.OTHER)
        assertEquals(Status.PENDING, once.item(READER)?.status)
        val dropped = once.failed(READER, DownloadFailure.OTHER)
        assertEquals(Status.DROPPED, dropped.item(READER)?.status)
        assertEquals(Status.DONE, dropped.item(MALAYALAM)?.status)
        assertFalse(dropped.pending(READER))
        assertNull(dropped.next(firstRecordingSaved = true))
        assertEquals(Status.PENDING, dropped.retried(READER).item(READER)?.status)
        assertEquals(Status.DROPPED, withReader.failed(READER, DownloadFailure.SERVER).failed(READER, DownloadFailure.SERVER).item(READER)?.status)
    }

    @Test
    fun `an install without the reader gets it at the end, one that had it done plans it again, and a pending or dropped one stays`() {
        val done = plan.done(SPEECH).done(MALAYALAM).done(AI_MODEL)

        val appended = done.withReader(reader)
        assertEquals(listOf(SPEECH, MALAYALAM, AI_MODEL, READER), appended.items.map { it.part })
        assertEquals(FirstRunPlan.Item(READER, reader), appended.item(READER))
        assertEquals(appended, appended.withReader(reader))

        // Its files went since it was done: planned again, its failures forgotten.
        val again = appended.failed(READER, DownloadFailure.OTHER).done(READER).withReader(reader)
        assertEquals(FirstRunPlan.Item(READER, reader), again.item(READER))

        val dropped = appended.failed(READER, DownloadFailure.OTHER).failed(READER, DownloadFailure.OTHER)
        assertEquals(dropped, dropped.withReader(reader))
        // An empty plan, an install from before the first run's plans, gets it too.
        assertEquals(listOf(READER), FirstRunPlan(emptyList()).withReader(reader).items.map { it.part })
    }

    @Test
    fun `a download needs its bytes and 300 MB more free`() {
        // Get ready on the owner's phone: 808 MB, so 1.1 GB free.
        assertEquals(1_108_045_575L, FirstRunPlan.neededSpace(plan.setupBytes))
        assertTrue(FirstRunPlan.fits(1_108_045_575L, plan.setupBytes))
        assertFalse(FirstRunPlan.fits(600_000_000L, plan.setupBytes))
    }
}
