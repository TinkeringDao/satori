package com.satori.domain.settings

import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteIntervalsTest {

    @Test
    fun `each mode offers its own few intervals`() {
        assertEquals(listOf(30, 60, 120), NoteIntervals.optionsFor(RecordingMode.SIMPLE_LISTENING))
        assertEquals(listOf(30, 60, 120), NoteIntervals.optionsFor(RecordingMode.SHORT_MEETING))
        assertEquals(listOf(3, 5, 10), NoteIntervals.optionsFor(RecordingMode.LONG_MEETING))
        assertEquals(listOf(15, 30, 60), NoteIntervals.optionsFor(RecordingMode.REAL_TIME_TRANSLATION))
    }

    @Test
    fun `the defaults are among the options`() {
        val defaults = AppSettings()

        assertTrue(defaults.simpleListeningIntervalSeconds in NoteIntervals.optionsFor(RecordingMode.SIMPLE_LISTENING))
        assertTrue(defaults.shortMeetingIntervalSeconds in NoteIntervals.optionsFor(RecordingMode.SHORT_MEETING))
        assertTrue(defaults.longMeetingIntervalMinutes in NoteIntervals.optionsFor(RecordingMode.LONG_MEETING))
        assertTrue(defaults.translationIntervalSeconds in NoteIntervals.optionsFor(RecordingMode.REAL_TIME_TRANSLATION))
    }

    @Test
    fun `the analysis modes never offer less than 30 seconds`() {
        // Below 30 s the model gets too little text for a useful note (SettingsViewModel's old
        // MIN_INTERVAL_SECONDS_ANALYSIS).
        assertTrue(NoteIntervals.NOTES_SECONDS.all { it >= 30 })
    }

    @Test
    fun `a value that is an option is its own nearest`() {
        assertEquals(60, NoteIntervals.nearest(NoteIntervals.NOTES_SECONDS, 60))
    }

    @Test
    fun `a value from the old slider shows as the closest option`() {
        assertEquals(30, NoteIntervals.nearest(NoteIntervals.NOTES_SECONDS, 40))
        assertEquals(60, NoteIntervals.nearest(NoteIntervals.NOTES_SECONDS, 70))
        assertEquals(120, NoteIntervals.nearest(NoteIntervals.NOTES_SECONDS, 180))
        assertEquals(15, NoteIntervals.nearest(NoteIntervals.TRANSLATION_SECONDS, 10))
    }

    @Test
    fun `two equally close options give the shorter one`() {
        assertEquals(60, NoteIntervals.nearest(NoteIntervals.NOTES_SECONDS, 90))
    }

    @Test
    fun `no options leave the value alone`() {
        assertEquals(45, NoteIntervals.nearest(emptyList(), 45))
    }
}
