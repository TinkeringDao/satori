package com.satori.presentation.settings

import com.satori.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Reset voice detection" and the check that hides it use the view model's defaults; they must be
 * the settings' own, or a reset would store other values than a fresh install starts with.
 */
class VoiceDetectionDefaultsTest {

    @Test
    fun `the reset values are the settings' defaults`() {
        val defaults = AppSettings()

        assertEquals(defaults.vadThreshold, AdvancedSettingsViewModel.VAD_THRESHOLD_DEFAULT)
        assertEquals(defaults.vadMinSilenceDuration, AdvancedSettingsViewModel.MIN_SILENCE_DURATION_DEFAULT)
        assertEquals(defaults.vadMaxSpeechDuration, AdvancedSettingsViewModel.MAX_SPEECH_DURATION_DEFAULT)
    }
}
