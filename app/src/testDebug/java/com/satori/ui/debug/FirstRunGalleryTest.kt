package com.satori.ui.debug

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.NudgeId
import com.satori.presentation.firstrun.FirstRunFooter
import com.satori.presentation.firstrun.FirstRunProblem
import com.satori.presentation.firstrun.FirstRunQuiet
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.firstrun.StripHold
import com.satori.presentation.firstrun.StripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery has every first-run state of the design's list (5.9), and each shows what its name says. */
class FirstRunGalleryTest {

    /** ONBOARDING_DESIGN_ADHD.md 5.9, in its order, and 0.7.2's ping for notes no notification could tell of. */
    private val designed = listOf(
        "firstrun_s1_wifi", "firstrun_s1_mobile", "firstrun_s1_space", "firstrun_s2_mic", "firstrun_s2_mic_ok",
        "firstrun_s2_checking", "firstrun_s2_paused", "firstrun_s2_noconn", "firstrun_s2_wifi", "firstrun_s2_blocked",
        "firstrun_s2_server", "firstrun_s2_damaged", "firstrun_s2_mic_refused", "firstrun_s2_mic_settings",
        "firstrun_s3_ready", "firstrun_s3_ready_mic", "firstrun_s4_coach", "firstrun_home_waits", "firstrun_home_paused",
        "firstrun_home_short", "firstrun_home_first_notes", "firstrun_nudge_ping", "firstrun_nudge_e2b",
        "firstrun_nudge_ping_missed"
    )

    private fun setup(name: String) = checkNotNull(FirstRunGallery.setup[name]) { name }
    private fun home(name: String) = checkNotNull(FirstRunGallery.home(name) { it }) { name }

    @Test
    fun `every designed state is in the gallery, and nothing else is called firstrun`() {
        val recorder = GalleryStates.byName.keys.filter { it.startsWith("firstrun_") }
        assertEquals(designed.toSet(), (FirstRunGallery.names + recorder).toSet())
        assertEquals(designed.size, FirstRunGallery.names.size + recorder.size)
        assertTrue(FirstRunGallery.names.all { FirstRunGallery.setup[it] != null || FirstRunGallery.home(it) { id -> id } != null })
    }

    @Test
    fun `get ready on Wi-Fi, on mobile data and short of space`() {
        assertEquals(FirstRunScreen.GET_READY, setup("firstrun_s1_wifi").screen)
        assertEquals(FirstRunFooter.DOWNLOAD, setup("firstrun_s1_wifi").footer)
        assertEquals(FirstRunQuiet.WAIT_FOR_WIFI, setup("firstrun_s1_mobile").quiet)
        assertEquals(FirstRunFooter.OPEN_STORAGE_SETTINGS, setup("firstrun_s1_space").footer)
        assertEquals(808_045_575L, setup("firstrun_s1_wifi").downloadBytes)
    }

    @Test
    fun `getting ready in each of its states`() {
        assertEquals(FirstRunFooter.ALLOW_MICROPHONE, setup("firstrun_s2_mic").footer)
        assertEquals(34, setup("firstrun_s2_mic").progress?.percent)
        assertEquals(FirstRunFooter.RECORD, setup("firstrun_s2_mic_ok").footer)
        assertFalse(setup("firstrun_s2_mic_ok").primaryEnabled)
        assertEquals(true, setup("firstrun_s2_checking").progress?.checking)
        assertEquals(81, setup("firstrun_s2_checking").progress?.percent)
        assertTrue(setup("firstrun_s2_paused").paused)
        assertEquals(FirstRunQuiet.TRY_NOW, setup("firstrun_s2_noconn").quiet)
        assertTrue(setup("firstrun_s2_wifi").offersMobileData)
        assertEquals(428L, setup("firstrun_s2_wifi").progress?.bytesLeft?.div(1_000_000))
        assertEquals(FirstRunFooter.TRY_AGAIN, setup("firstrun_s2_blocked").footer)
        assertEquals(FirstRunProblem.ServerNotAnswering, setup("firstrun_s2_server").shownProblem)
        assertEquals(FirstRunProblem.Damaged(again = false), setup("firstrun_s2_damaged").shownProblem)
        assertEquals(MicrophoneState.REFUSED_ONCE, setup("firstrun_s2_mic_refused").microphone)
        assertEquals(FirstRunFooter.OPEN_APP_SETTINGS, setup("firstrun_s2_mic_settings").footer)
    }

    @Test
    fun `ready, with the microphone and without it`() {
        assertEquals(FirstRunFooter.RECORD, setup("firstrun_s3_ready").footer)
        assertTrue(setup("firstrun_s3_ready").primaryEnabled)
        assertEquals(FirstRunQuiet.LOOK_AROUND, setup("firstrun_s3_ready").quiet)
        assertEquals(FirstRunFooter.ALLOW_MICROPHONE, setup("firstrun_s3_ready_mic").footer)
    }

    @Test
    fun `each setup state has only the controls the design allows, and a way on`() {
        for ((name, state) in FirstRunGallery.setup) {
            val allowed = when (state.screen) {
                FirstRunScreen.GET_READY -> setOf(null, FirstRunQuiet.WAIT_FOR_WIFI)
                else -> if (state.ready) {
                    setOf(FirstRunQuiet.LOOK_AROUND)
                } else {
                    setOf(null, FirstRunQuiet.PAUSE, FirstRunQuiet.CONTINUE, FirstRunQuiet.TRY_NOW)
                }
            }
            assertTrue("$name: ${state.quiet}", state.quiet in allowed)
            assertTrue(name, !state.offersMobileData || state.shownProblem == FirstRunProblem.WaitingForWifi)
            // A switched-off Record goes on by itself once the files are checked.
            assertTrue(name, state.primaryEnabled || state.quiet != null || state.footer == FirstRunFooter.RECORD)
        }
    }

    @Test
    fun `home's states show the strip, and the row that waits for the AI model or is too short`() {
        assertEquals(FinishingStatus.Phase.WAITS_FOR_MODEL, home("firstrun_home_waits").finishing["first"]?.phase)
        assertNull((home("firstrun_home_waits").strip as StripState.Download).hold)
        assertEquals(StripHold.RECORDING, (home("firstrun_home_paused").strip as StripState.Download).hold)
        assertEquals(true, home("firstrun_home_short").finishing["first"]?.noNotes)
        assertEquals(StripState.FirstNotes("first"), home("firstrun_home_first_notes").strip)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), home("firstrun_nudge_ping").strip)
        assertEquals(2, home("firstrun_nudge_ping").recordings.size)
        // One recording is enough once its notes were ready that no notification could tell of.
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true), home("firstrun_nudge_ping_missed").strip)
        assertEquals(1, home("firstrun_nudge_ping_missed").recordings.size)
        assertEquals(StripState.Nudge(NudgeId.E2B_OFFER), home("firstrun_nudge_e2b").strip)
        assertEquals(3, home("firstrun_nudge_e2b").recordings.size)
        assertEquals(23, (home("firstrun_home_waits").strip as StripState.Download).percent)
    }

    @Test
    fun `the first recording is coached, with the AI model still to come`() {
        val coach = checkNotNull(GalleryStates.find("firstrun_s4_coach"))
        assertTrue(coach.isRecording)
        assertTrue(coach.firstRecordingCoach)
        assertTrue(coach.aiModelPending)
        assertTrue(coach.allSegments.sumOf { it.text.split(' ').size } < 20)
    }
}
