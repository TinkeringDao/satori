package com.satori.feature.stt.datasource

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A capture's microphone is closed exactly once, whichever order Stop and the capture loop come in. */
class CaptureTest {

    /** A microphone that counts how often it is closed. */
    private class Microphone {
        val closes = AtomicInteger()
    }

    private fun capture() = Capture<Microphone> { it.closes.incrementAndGet() }

    @Test
    fun `Stop while the microphone opens closes it as soon as it is there, and nothing reads it`() {
        val capture = capture()
        val microphone = Microphone()

        capture.stop()

        assertFalse("read from a stopped capture", capture.attach(microphone))
        assertEquals(1, microphone.closes.get())
        capture.end()
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `Stop while recording closes the microphone, and the end of the loop doesn't close it again`() {
        val capture = capture()
        val microphone = Microphone()
        assertTrue(capture.attach(microphone))

        capture.stop()
        capture.end()

        assertTrue(capture.isStopped)
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `a loop that ends by itself closes the microphone, and a later Stop doesn't close it again`() {
        val capture = capture()
        val microphone = Microphone()
        capture.attach(microphone)

        capture.end()
        assertFalse("ended without a Stop", capture.isStopped)
        assertEquals(1, microphone.closes.get())

        capture.stop()
        assertEquals(1, microphone.closes.get())
    }

    @Test
    fun `Stop before a microphone opened has nothing to close`() {
        val capture = capture()

        capture.stop()
        capture.end()

        assertTrue(capture.isStopped)
    }

    @Test
    fun `a capture a newer one interrupted closes its own microphone, never the newer one's`() {
        // The older one is still loading when a newer recording starts and stops it.
        val older = capture()
        older.stop()
        val newer = capture()
        val newerMicrophone = Microphone()
        assertTrue(newer.attach(newerMicrophone))

        // The older one's microphone opens after all.
        val olderMicrophone = Microphone()
        assertFalse(older.attach(olderMicrophone))
        assertEquals(1, olderMicrophone.closes.get())
        assertEquals("the newer one still records", 0, newerMicrophone.closes.get())

        newer.stop()
        assertEquals(1, newerMicrophone.closes.get())
        assertEquals(1, olderMicrophone.closes.get())
    }
}
