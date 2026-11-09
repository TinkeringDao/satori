package com.satori.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A recording detects speech with the values of Advanced settings, Transcribe again with fixed
 * ones. That a value an earlier version stored at its old default reads as the new default is
 * the settings' rule (SettingsRepositoryImplTest); the live profile takes what they read.
 */
class VadParametersTest {

    @Test
    fun `a recording uses the voice detection settings`() {
        val settings = AppSettings(vadThreshold = 0.65F, vadMinSilenceDuration = 1.2F, vadMaxSpeechDuration = 12.0F)

        assertEquals(VadParameters(VadProfile.LIVE, 0.65F, 1.2F, 12.0F), VadParameters.of(VadProfile.LIVE, settings))
    }

    @Test
    fun `a recording with the settings untouched uses today's defaults`() {
        assertEquals(VadParameters(VadProfile.LIVE, 0.4F, 0.5F, 20.0F), VadParameters.of(VadProfile.LIVE, AppSettings()))
    }

    @Test
    fun `Transcribe again uses its own values, whatever the settings are`() {
        val expected = VadParameters(VadProfile.FILE, threshold = 0.3F, minSilenceDuration = 0.7F, maxSpeechDuration = 20.0F)

        assertEquals(expected, VadParameters.of(VadProfile.FILE, AppSettings()))
        assertEquals(
            expected,
            VadParameters.of(VadProfile.FILE, AppSettings(vadThreshold = 0.9F, vadMinSilenceDuration = 0.1F, vadMaxSpeechDuration = 3.0F))
        )
        assertEquals(expected, VadParameters.FILE)
    }

    @Test
    fun `each profile says which it is`() {
        VadProfile.entries.forEach { profile -> assertEquals(profile, VadParameters.of(profile, AppSettings()).profile) }
    }
}
