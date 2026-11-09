package com.satori.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Settings offers the usual input first and the one for calls, not the one for speech
 * recognition, which recorded the razr too quietly; a stored speech recognition input records
 * with the usual one.
 */
class MicSourceTest {

    @Test
    fun `Settings offers the usual input first, then the one for calls`() {
        assertFalse(MicSource.OFFERS_SPEECH_RECOGNITION_INPUT)
        assertEquals(listOf(MicSource.MIC, MicSource.VOICE_COMMUNICATION), MicSource.OFFERED)
    }

    @Test
    fun `a stored speech recognition input records with the usual one, the others as stored`() {
        assertEquals(MicSource.MIC, MicSource.offered(MicSource.VOICE_RECOGNITION))
        assertEquals(MicSource.MIC, MicSource.offered(MicSource.MIC))
        assertEquals(MicSource.VOICE_COMMUNICATION, MicSource.offered(MicSource.VOICE_COMMUNICATION))
    }
}
