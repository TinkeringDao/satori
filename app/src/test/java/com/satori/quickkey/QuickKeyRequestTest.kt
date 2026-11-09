package com.satori.quickkey

import com.satori.domain.model.RecordingLanguage
import com.satori.quickkey.QuickKeyRequest.Action
import com.satori.quickkey.QuickKeyRequest.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The press table in QuickKeyRequest's class comment. */
class QuickKeyRequestTest {

    private val toggle = QuickKeyRequest(categoryId = "voice-note", stopIfRecording = true)
    private val startOnly = QuickKeyRequest(categoryId = "voice-note", stopIfRecording = false)

    @Test
    fun `the press table, row by row`() {
        val table = mapOf(
            RecorderState.NOT_SET_UP to (Action.SHOW to Action.SHOW),
            RecorderState.IDLE to (Action.START to Action.START),
            RecorderState.STARTING to (Action.CANCEL_START to Action.SHOW),
            RecorderState.RECORDING to (Action.STOP to Action.SHOW),
            RecorderState.SAVING to (Action.START to Action.START)
        )
        assertEquals(RecorderState.entries.toSet(), table.keys)
        for ((state, actions) in table) {
            assertEquals("$state, stopIfRecording=true", actions.first, toggle.actionFor(state))
            assertEquals("$state, stopIfRecording=false", actions.second, startOnly.actionFor(state))
        }
    }

    @Test
    fun `with the stop switch on, a press starts and the next one stops`() {
        assertEquals(Action.START, toggle.actionFor(RecorderState.IDLE))
        assertEquals(Action.STOP, toggle.actionFor(RecorderState.RECORDING))
    }

    @Test
    fun `a stop press while the recorder gets ready cancels the start`() {
        assertEquals(Action.CANCEL_START, toggle.actionFor(RecorderState.STARTING))
    }

    @Test
    fun `with the stop switch off, a press never stops or cancels`() {
        assertEquals(Action.START, startOnly.actionFor(RecorderState.IDLE))
        assertEquals(Action.SHOW, startOnly.actionFor(RecorderState.STARTING))
        assertEquals(Action.SHOW, startOnly.actionFor(RecorderState.RECORDING))
    }

    @Test
    fun `a press while the last recording is saved records again, either way`() {
        for (request in listOf(toggle, startOnly)) {
            assertEquals(Action.START, request.actionFor(RecorderState.SAVING))
        }
    }

    @Test
    fun `before setup a press only shows Satori`() {
        for (request in listOf(toggle, startOnly)) {
            assertEquals(Action.SHOW, request.actionFor(RecorderState.NOT_SET_UP))
        }
    }

    @Test
    fun `no intent is no press`() {
        assertNull(QuickKeyRequest.from(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a request never has a blank category`() {
        QuickKeyRequest(categoryId = " ", stopIfRecording = true)
    }

    @Test
    fun `a press records in English or German unless the key is set to Malayalam`() {
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, toggle.language)
        val malayalam = QuickKeyRequest(categoryId = "meeting", stopIfRecording = true, language = RecordingLanguage.MALAYALAM)
        assertEquals(RecordingLanguage.MALAYALAM, malayalam.language)
        // The language goes with the press as its code, and none for English or German, which an
        // intent without the extra reads as.
        assertEquals("ml", malayalam.language.code)
        assertNull(toggle.language.code)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, RecordingLanguage.fromCode(null))
    }
}
