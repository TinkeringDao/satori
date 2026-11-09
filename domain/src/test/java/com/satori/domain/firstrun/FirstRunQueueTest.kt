package com.satori.domain.firstrun

import com.satori.domain.firstrun.FirstRunPart.AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.BIGGER_AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.MALAYALAM
import com.satori.domain.firstrun.FirstRunPart.READER
import com.satori.domain.firstrun.FirstRunPart.SPEECH
import com.satori.domain.firstrun.FirstRunStage.DONE
import com.satori.domain.firstrun.FirstRunStage.DOWNLOADING
import com.satori.domain.firstrun.FirstRunStage.GET_READY
import com.satori.domain.firstrun.FirstRunStage.READY
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstRunQueueTest {

    private val plan = FirstRunPlan.initial(speechBytes = 670L, malayalamBytes = 138L, aiModelBytes = 1_069L)
    private val setupDone = plan.done(SPEECH).done(MALAYALAM)

    @Test
    fun `nothing downloads before Download is tapped`() {
        assertNull(FirstRunQueue.next(plan, GET_READY, firstRecordingSaved = false, aiNotesOn = true))
    }

    @Test
    fun `the setup downloads in order, speech recognition first`() {
        assertEquals(SPEECH, FirstRunQueue.next(plan, DOWNLOADING, false, true)?.part)
        assertEquals(MALAYALAM, FirstRunQueue.next(plan.done(SPEECH), DOWNLOADING, false, true)?.part)
    }

    @Test
    fun `the AI model waits for the first recording`() {
        assertNull(FirstRunQueue.next(setupDone, READY, firstRecordingSaved = false, aiNotesOn = true))
        assertEquals(AI_MODEL, FirstRunQueue.next(setupDone, DONE, firstRecordingSaved = true, aiNotesOn = true)?.part)
    }

    @Test
    fun `neither AI model downloads while AI notes are off`() {
        val bigger = setupDone.withBiggerModel(3_349L)
        assertNull(FirstRunQueue.next(bigger, DONE, firstRecordingSaved = true, aiNotesOn = false))
        // Speech recognition still does.
        assertEquals(SPEECH, FirstRunQueue.next(bigger.retriedSpeech(), DONE, firstRecordingSaved = true, aiNotesOn = false)?.part)
    }

    @Test
    fun `a dropped Malayalam doesn't hold up what comes after it`() {
        val dropped = plan.done(SPEECH)
            .failed(MALAYALAM, DownloadFailure.SERVER)
            .failed(MALAYALAM, DownloadFailure.SERVER)
        assertNull(FirstRunQueue.next(dropped, READY, firstRecordingSaved = false, aiNotesOn = true))
        assertEquals(AI_MODEL, FirstRunQueue.next(dropped, DONE, firstRecordingSaved = true, aiNotesOn = true)?.part)
    }

    @Test
    fun `a Malayalam tried again comes before the AI model, in the plan's order`() {
        val tried = plan.done(SPEECH)
            .failed(MALAYALAM, DownloadFailure.OTHER)
            .failed(MALAYALAM, DownloadFailure.OTHER)
            .retried(MALAYALAM)
        assertEquals(MALAYALAM, FirstRunQueue.next(tried, DONE, firstRecordingSaved = true, aiNotesOn = true)?.part)
    }

    @Test
    fun `the bigger AI model comes after the AI model`() {
        val bigger = setupDone.withBiggerModel(3_349L)
        assertEquals(AI_MODEL, FirstRunQueue.next(bigger, DONE, true, true)?.part)
        assertEquals(BIGGER_AI_MODEL, FirstRunQueue.next(bigger.done(AI_MODEL), DONE, true, true)?.part)
        assertNull(FirstRunQueue.next(bigger.done(AI_MODEL).done(BIGGER_AI_MODEL), DONE, true, true))
    }

    @Test
    fun `a part that waits for Try again holds the queue instead of being skipped`() {
        val damaged = plan.failed(SPEECH, DownloadFailure.OTHER).failed(SPEECH, DownloadFailure.OTHER)
        val item = FirstRunQueue.next(damaged, DOWNLOADING, false, true)

        assertEquals(SPEECH, item?.part)
        assertEquals(QueueStep.WaitsForTap(SPEECH), FirstRunQueue.step(item, QueueFacts()))
    }

    @Test
    fun `whether the part may go now`() {
        val speech = plan.item(SPEECH)
        val ai = setupDone.item(AI_MODEL)
        // part, facts, step
        val rows = listOf(
            Triple(speech, QueueFacts(), QueueStep.Run(SPEECH)),
            Triple(speech, QueueFacts(userPaused = true), QueueStep.Hold(SPEECH, PauseReason.USER)),
            // Pause is the setup's: the strip's downloads have none.
            Triple(ai, QueueFacts(userPaused = true), QueueStep.Run(AI_MODEL)),
            Triple(speech, QueueFacts(spaceShort = true), QueueStep.Hold(SPEECH, PauseReason.NOT_ENOUGH_STORAGE)),
            Triple(speech, QueueFacts(blocked = true), QueueStep.Hold(SPEECH, PauseReason.BLOCKED)),
            Triple(speech, QueueFacts(network = NetworkFacts.NONE), QueueStep.Hold(SPEECH, PauseReason.NO_NETWORK)),
            Triple(speech, QueueFacts(network = NetworkFacts.MOBILE), QueueStep.Hold(SPEECH, PauseReason.WIFI_ONLY)),
            Triple(speech, QueueFacts(network = NetworkFacts.MOBILE, mobileDataAllowed = true), QueueStep.Run(SPEECH)),
            Triple(speech, QueueFacts(retryWait = PauseReason.NO_NETWORK), QueueStep.Hold(SPEECH, PauseReason.NO_NETWORK)),
            Triple(speech, QueueFacts(retryWait = PauseReason.SERVER), QueueStep.Hold(SPEECH, PauseReason.SERVER)),
            Triple(null, QueueFacts(), QueueStep.Idle)
        )
        for ((item, facts, step) in rows) {
            assertEquals("${item?.part}, $facts", step, FirstRunQueue.step(item, facts))
        }
    }

    @Test
    fun `what only he can change comes before the network, and the network before a retry that is due`() {
        val speech = plan.item(SPEECH)
        val everything = QueueFacts(
            userPaused = true, network = NetworkFacts.NONE, spaceShort = true, blocked = true, retryWait = PauseReason.SERVER
        )

        assertEquals(QueueStep.Hold(SPEECH, PauseReason.USER), FirstRunQueue.step(speech, everything))
        assertEquals(QueueStep.Hold(SPEECH, PauseReason.NOT_ENOUGH_STORAGE), FirstRunQueue.step(speech, everything.copy(userPaused = false)))
        assertEquals(
            QueueStep.Hold(SPEECH, PauseReason.BLOCKED),
            FirstRunQueue.step(speech, everything.copy(userPaused = false, spaceShort = false))
        )
        assertEquals(
            QueueStep.Hold(SPEECH, PauseReason.NO_NETWORK),
            FirstRunQueue.step(speech, everything.copy(userPaused = false, spaceShort = false, blocked = false))
        )
        assertEquals(
            QueueStep.Hold(SPEECH, PauseReason.SERVER),
            FirstRunQueue.step(speech, everything.copy(userPaused = false, spaceShort = false, blocked = false, network = NetworkFacts.WIFI))
        )
    }

    @Test
    fun `pause holds the setup even while a part waits for Try again`() {
        val damaged = plan.failed(SPEECH, DownloadFailure.OTHER).failed(SPEECH, DownloadFailure.OTHER)

        assertEquals(QueueStep.Hold(SPEECH, PauseReason.USER), FirstRunQueue.step(damaged.item(SPEECH), QueueFacts(userPaused = true)))
    }

    @Test
    fun `only the AI models are AI models`() {
        assertEquals(setOf(AI_MODEL, BIGGER_AI_MODEL), FirstRunPart.entries.filter { it.aiModel }.toSet())
    }

    @Test
    fun `the reader is for AI notes alone, droppable, after the first recording`() {
        assertEquals(setOf(AI_MODEL, BIGGER_AI_MODEL, READER), FirstRunPart.entries.filter { it.forAiNotes }.toSet())
        assertEquals(false, READER.beforeFirstRecording)
        assertEquals(true, READER.droppable)
    }

    @Test
    fun `the reader comes after the AI model, and not while AI notes are off`() {
        val withReader = FirstRunPlan.initial(670L, 138L, 1_069L, readerBytes = 242L).done(SPEECH).done(MALAYALAM)

        assertNull(FirstRunQueue.next(withReader, READY, firstRecordingSaved = false, aiNotesOn = true))
        assertEquals(AI_MODEL, FirstRunQueue.next(withReader, DONE, firstRecordingSaved = true, aiNotesOn = true)?.part)
        assertEquals(READER, FirstRunQueue.next(withReader.done(AI_MODEL), DONE, firstRecordingSaved = true, aiNotesOn = true)?.part)
        assertNull(FirstRunQueue.next(withReader.done(AI_MODEL), DONE, firstRecordingSaved = true, aiNotesOn = false))
        assertNull(FirstRunQueue.next(withReader.done(AI_MODEL).done(READER), DONE, firstRecordingSaved = true, aiNotesOn = true))
    }

    /** Speech recognition planned again, as after its files went. */
    private fun FirstRunPlan.retriedSpeech(): FirstRunPlan =
        copy(items = items.map { if (it.part == SPEECH) it.copy(status = FirstRunPlan.Status.PENDING) else it })
}
