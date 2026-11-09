package com.satori.ui.screens

import com.satori.domain.model.InsightStrategy
import com.satori.domain.settings.QuickKeyBinding
import com.satori.presentation.settings.DeleteConsequence
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the settings audit's fixes (SETTINGS_AUDIT_RESULT.md, 2026-10-15) that the
 * screens draw: what the Power key row says, when the screen stays on while recording, when the
 * notes interval shows, when a category's name takes focus, the value's share of a row and the
 * chevron of a link row, what a delete question says, and the last line of the AI model's screen.
 */
class SettingsAuditRulesTest {

    @Test
    fun `the Power key row says what two presses do only once the key reaches Satori`() {
        assertEquals(R.string.g_quickkey_record_as, quickKeyRowLine(QuickKeyBinding.SET_UP, malayalam = false))
        assertEquals(R.string.g_quickkey_record_as_in, quickKeyRowLine(QuickKeyBinding.SET_UP, malayalam = true))
        assertEquals(R.string.g_quickkey_row_not_set_up, quickKeyRowLine(QuickKeyBinding.NOT_SET_UP, malayalam = true))
        assertEquals(R.string.g_quickkey_row_other_app, quickKeyRowLine(QuickKeyBinding.OTHER_APP, malayalam = false))
        assertEquals(R.string.g_quickkey_row_unknown, quickKeyRowLine(QuickKeyBinding.UNKNOWN, malayalam = false))
        assertEquals(R.string.g_quickkey_row_unknown_in, quickKeyRowLine(QuickKeyBinding.UNKNOWN, malayalam = true))
    }

    @Test
    fun `the screen stays on while recording only with the phone unlocked`() {
        assertFalse(RecordingScreenOn.KEEPS_ON_OVER_LOCK_SCREEN)
        assertTrue(RecordingScreenOn.keeps(recording = true, setting = true, locked = false))
        // A power-key recording over the lock screen, in a pocket: the screen times out as usual.
        assertFalse(RecordingScreenOn.keeps(recording = true, setting = true, locked = true))
        assertFalse(RecordingScreenOn.keeps(recording = true, setting = false, locked = false))
        assertFalse(RecordingScreenOn.keeps(recording = false, setting = true, locked = false))
    }

    @Test
    fun `the notes interval shows only while notes are written while recording`() {
        assertTrue(showsInterval(InsightStrategy.REAL_TIME))
        assertFalse(showsInterval(InsightStrategy.END_OF_SESSION))
    }

    @Test
    fun `a category's name takes focus at once on the inner screen, not on the cover`() {
        // The inner screen: 2640 px at 420 dpi. The cover: 1272 px at 306 dpi, 923 px above the cameras.
        assertTrue(focusesNameAtOnce(1005))
        assertFalse(focusesNameAtOnce(665))
        assertFalse(focusesNameAtOnce(483))
        assertTrue(focusesNameAtOnce(NAME_FOCUS_MIN_HEIGHT_DP))
    }

    @Test
    fun `a value takes at most two fifths of its row`() {
        assertEquals(0.4f, VALUE_SHARE)
        // 411 dp at 2.625 px a dp, the inner screen less its margins.
        assertEquals(400, valueWidthCap(1000))
        assertEquals(0, valueWidthCap(0))
    }

    @Test
    fun `a link row without a value ends in a chevron`() {
        assertTrue(linkRowHasChevron(null))
        assertFalse(linkRowHasChevron("Auto"))
    }

    @Test
    fun `a delete question says what stops working`() {
        assertEquals(R.string.g_settings_model_delete_last_body, deleteBody(DeleteConsequence.NO_NOTES))
        assertEquals(R.string.g_settings_model_delete_body, deleteBody(DeleteConsequence.NONE))
        assertEquals(R.string.g_speech_delete_ml_body, speechDeleteBody(DeleteConsequence.MALAYALAM_IN_ENGLISH_OR_GERMAN))
        assertEquals(R.string.g_speech_delete_body, speechDeleteBody(DeleteConsequence.NONE))
    }

    @Test
    fun `the AI model's screen opened from Settings doesn't send the owner to Settings`() {
        assertEquals(R.string.g_settings_llm_works_without, llmLaterLine(isStandalone = true))
        assertEquals(R.string.g_onb_llm_later, llmLaterLine(isStandalone = false))
    }
}
