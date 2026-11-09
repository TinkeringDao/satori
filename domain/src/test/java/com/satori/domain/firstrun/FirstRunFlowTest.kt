package com.satori.domain.firstrun

import com.satori.domain.firstrun.FirstRunEvent.DOWNLOAD_TAPPED
import com.satori.domain.firstrun.FirstRunEvent.RECORDING_SAVED
import com.satori.domain.firstrun.FirstRunEvent.SETUP_DONE
import com.satori.domain.firstrun.FirstRunEvent.SPEECH_GONE
import com.satori.domain.firstrun.FirstRunStage.DONE
import com.satori.domain.firstrun.FirstRunStage.DOWNLOADING
import com.satori.domain.firstrun.FirstRunStage.GET_READY
import com.satori.domain.firstrun.FirstRunStage.READY
import com.satori.domain.firstrun.SpeechFiles.CHECKED
import com.satori.domain.firstrun.SpeechFiles.MISSING
import com.satori.domain.firstrun.SpeechFiles.PRESENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunFlowTest {

    @Test
    fun `every stage and event gives the stage of the design's table`() {
        // Rows: the stage; columns: download tapped, setup done, recording saved, speech gone.
        val table = mapOf(
            GET_READY to listOf(DOWNLOADING, READY, GET_READY, GET_READY),
            DOWNLOADING to listOf(DOWNLOADING, READY, DOWNLOADING, DOWNLOADING),
            READY to listOf(READY, READY, DONE, GET_READY),
            DONE to listOf(DONE, DONE, DONE, GET_READY)
        )
        val events = listOf(DOWNLOAD_TAPPED, SETUP_DONE, RECORDING_SAVED, SPEECH_GONE)

        for ((stage, expected) in table) {
            events.zip(expected).forEach { (event, next) ->
                assertEquals("$stage + $event", next, FirstRunFlow.next(stage, event))
            }
        }
        assertEquals(FirstRunStage.entries.toSet(), table.keys)
    }

    @Test
    fun `the stage only moves forward, but for the speech files going`() {
        for (stage in FirstRunStage.entries) {
            for (event in FirstRunEvent.entries - SPEECH_GONE) {
                assertTrue("$stage + $event", FirstRunFlow.next(stage, event) >= stage)
            }
        }
    }

    @Test
    fun `the stage to open at follows the files on disk`() {
        // stored, speech recognition's files, setup done, expected
        val rows = listOf(
            Row(GET_READY, MISSING, setupDone = false, opens = GET_READY),
            // Put there by hand on a fresh install: the first screen is Ready.
            Row(GET_READY, CHECKED, setupDone = false, opens = READY),
            Row(GET_READY, CHECKED, setupDone = true, opens = READY),
            // The download goes on where it was.
            Row(DOWNLOADING, MISSING, setupDone = false, opens = DOWNLOADING),
            // Speech is in, Malayalam still on its way.
            Row(DOWNLOADING, CHECKED, setupDone = false, opens = DOWNLOADING),
            // The last file was checked as the app died.
            Row(DOWNLOADING, CHECKED, setupDone = true, opens = READY),
            Row(READY, CHECKED, setupDone = true, opens = READY),
            Row(DONE, CHECKED, setupDone = true, opens = DONE),
            // Files gone after the setup: it starts over, as the earlier onboarding did.
            Row(READY, MISSING, setupDone = false, opens = GET_READY),
            Row(DONE, MISSING, setupDone = false, opens = GET_READY)
        )
        for (row in rows) {
            assertEquals(row.toString(), row.opens, FirstRunFlow.atLaunch(row.stored, row.speech, row.setupDone))
        }
    }

    @Test
    fun `files that are there but not checked change nothing`() {
        // Not proof of a setup done by hand, and no reason to start one over.
        for (stage in FirstRunStage.entries) {
            for (setupDone in listOf(false, true)) {
                assertEquals("$stage, setup done $setupDone", stage, FirstRunFlow.atLaunch(stage, PRESENT, setupDone))
            }
        }
    }

    @Test
    fun `get ready and getting ready are the two setup screens, and ready is the second one's end`() {
        assertEquals(FirstRunScreen.GET_READY, FirstRunFlow.screen(GET_READY, lookedAround = false))
        assertEquals(FirstRunScreen.GETTING_READY, FirstRunFlow.screen(DOWNLOADING, lookedAround = false))
        assertEquals(FirstRunScreen.GETTING_READY, FirstRunFlow.screen(READY, lookedAround = false))
        assertEquals(FirstRunScreen.HOME, FirstRunFlow.screen(READY, lookedAround = true))
        assertEquals(FirstRunScreen.HOME, FirstRunFlow.screen(DONE, lookedAround = false))
    }

    @Test
    fun `three dots for three steps, and none at home`() {
        assertEquals(listOf(0, 1, 2), listOf(GET_READY, DOWNLOADING, READY).map(FirstRunFlow::pageIndex))
        assertEquals(FirstRunFlow.PAGE_COUNT, 3)
        assertEquals(FirstRunFlow.NO_PAGE, FirstRunFlow.pageIndex(DONE))
    }

    @Test
    fun `the quick key records from ready on, with the microphone allowed`() {
        assertFalse(FirstRunFlow.quickKeyReady(GET_READY, microphoneAllowed = true))
        assertFalse(FirstRunFlow.quickKeyReady(DOWNLOADING, microphoneAllowed = true))
        assertFalse(FirstRunFlow.quickKeyReady(READY, microphoneAllowed = false))
        assertTrue(FirstRunFlow.quickKeyReady(READY, microphoneAllowed = true))
        assertTrue(FirstRunFlow.quickKeyReady(DONE, microphoneAllowed = true))
        assertFalse(FirstRunFlow.quickKeyReady(DONE, microphoneAllowed = false))
    }

    @Test
    fun `the earlier onboarding counts as complete from ready on`() {
        assertEquals(
            listOf(false, false, true, true),
            FirstRunStage.entries.map(FirstRunFlow::onboardingComplete)
        )
    }

    @Test
    fun `the next recording is coached as the first until one is saved`() {
        assertEquals(
            listOf(true, true, true, false),
            FirstRunStage.entries.map(FirstRunFlow::firstRecordingAhead)
        )
    }

    @Test
    fun `a stored stage reads back, and an install without one by its onboarding flag`() {
        for (stage in FirstRunStage.entries) {
            assertEquals(stage, FirstRunStage.stored(stage.name, onboardingComplete = false))
            assertEquals(stage, FirstRunStage.stored(stage.name, onboardingComplete = true))
        }
        assertEquals(DONE, FirstRunStage.stored(null, onboardingComplete = true))
        assertEquals(GET_READY, FirstRunStage.stored(null, onboardingComplete = false))
        assertEquals(GET_READY, FirstRunStage.stored("WELCOME", onboardingComplete = false))
    }

    private data class Row(val stored: FirstRunStage, val speech: SpeechFiles, val setupDone: Boolean, val opens: FirstRunStage)
}
