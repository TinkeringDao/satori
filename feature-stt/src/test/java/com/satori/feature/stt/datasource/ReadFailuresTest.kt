package com.satori.feature.stt.datasource

import com.satori.domain.repository.MicrophoneLostException
import com.satori.feature.stt.datasource.SherpaOnnxDataSource.ReadFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadFailuresTest {

    @Test
    fun `warns at the first failed read, then once a second with the reads since the last warning`() {
        val failures = ReadFailures()

        assertEquals(1, failures.onFailure(nowMs = 10_000))
        for (t in 10_100L..10_900L step ReadFailures.RETRY_DELAY_MS) assertEquals(0, failures.onFailure(t))
        assertEquals(10, failures.onFailure(11_000))
    }

    @Test
    fun `ends the capture once reads have failed for five seconds in a row`() {
        val failures = ReadFailures()

        for (t in 0L until ReadFailures.GIVE_UP_AFTER_MS step ReadFailures.RETRY_DELAY_MS) {
            failures.onFailure(t)
            assertFalse("gave up at $t ms", failures.isSustained)
        }
        failures.onFailure(ReadFailures.GIVE_UP_AFTER_MS)

        assertTrue(failures.isSustained)
        assertEquals(ReadFailures.GIVE_UP_AFTER_MS, failures.failingForMs)
    }

    @Test
    fun `the capture ends as a lost microphone, which the recorder tells from a recognizer failure`() {
        val failures = ReadFailures()
        for (t in 0L until ReadFailures.GIVE_UP_AFTER_MS step ReadFailures.RETRY_DELAY_MS) {
            failures.onFailure(t)
            assertNull("gave up at $t ms", failures.lostMicrophone(ret = -6))
        }
        failures.onFailure(ReadFailures.GIVE_UP_AFTER_MS)

        val lost = failures.lostMicrophone(ret = -6)

        assertTrue(lost is MicrophoneLostException)
        assertEquals("No audio from the microphone for 5000 ms (read returned -6)", lost?.message)
    }

    @Test
    fun `a read with audio starts the run again`() {
        val failures = ReadFailures()
        failures.onFailure(0)
        failures.onFailure(ReadFailures.GIVE_UP_AFTER_MS - ReadFailures.RETRY_DELAY_MS)

        failures.onSuccess()
        assertEquals(0, failures.failingForMs)
        failures.onFailure(ReadFailures.GIVE_UP_AFTER_MS)
        assertFalse(failures.isSustained)
        failures.onFailure(2 * ReadFailures.GIVE_UP_AFTER_MS)
        assertTrue(failures.isSustained)
    }

    @Test
    fun `failed reads between good ones still warn at most once a second`() {
        val failures = ReadFailures()

        assertEquals(1, failures.onFailure(0))
        failures.onSuccess()
        assertEquals(0, failures.onFailure(500))
        failures.onSuccess()
        assertEquals(2, failures.onFailure(1_000))
    }
}
