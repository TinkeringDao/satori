package com.satori.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingHolderSetTest {

    /** The service's commands as sent; a START is refused while [refuse] is set. */
    private class FakeCommands : ProcessingHolderSet.Commands {
        val sent = mutableListOf<String>()
        var refuse = false

        override fun start(holder: String, generation: Long): Boolean {
            sent += "START $generation"
            return !refuse
        }

        override fun stop(generation: Long) {
            sent += "STOP $generation"
        }
    }

    private val commands = FakeCommands()
    private val holders = ProcessingHolderSet(commands)

    @Test
    fun `two holders start the service once`() {
        assertTrue(holders.take("finish:a"))
        assertTrue(holders.take("map"))

        assertEquals(listOf("START 1"), commands.sent)
    }

    @Test
    fun `the first release doesn't stop the service, the last one does`() {
        holders.take("finish:a")
        holders.take("rewrite:b")

        holders.release("finish:a")
        assertEquals(listOf("START 1"), commands.sent)
        holders.release("rewrite:b")
        assertEquals(listOf("START 1", "STOP 1"), commands.sent)
    }

    @Test
    fun `the holders now follow each take and release, for Models and storage`() {
        assertEquals(emptySet<String>(), holders.held.value)
        holders.take("finish:a")
        holders.take("transcribe:b")
        assertEquals(setOf("finish:a", "transcribe:b"), holders.held.value)

        holders.release("finish:a")
        assertEquals(setOf("transcribe:b"), holders.held.value)
        holders.release("finish:z")
        assertEquals(setOf("transcribe:b"), holders.held.value)
        holders.release("transcribe:b")
        assertEquals(emptySet<String>(), holders.held.value)
    }

    @Test
    fun `a holder that doesn't hold the service is ignored`() {
        holders.take("finish:a")

        holders.release("finish:b")
        holders.release("finish:a")
        holders.release("finish:a")

        assertEquals(listOf("START 1", "STOP 1"), commands.sent)
    }

    @Test
    fun `the same holder taken twice holds the service once`() {
        holders.take("finish:a")
        holders.take("finish:a")
        holders.release("finish:a")

        assertEquals(listOf("START 1", "STOP 1"), commands.sent)
    }

    @Test
    fun `a holder after the last release starts again, and the stop before it is stale`() {
        holders.take("rewrite:a")
        holders.release("rewrite:a")
        holders.take("finish:b")

        assertEquals(listOf("START 1", "STOP 1", "START 2"), commands.sent)
        assertFalse(holders.stopHolds(1))
        holders.release("finish:b")
        assertEquals("STOP 2", commands.sent.last())
        assertTrue(holders.stopHolds(2))
    }

    @Test
    fun `after a refused start the next holder asks again`() {
        commands.refuse = true
        assertFalse(holders.take("finish:a"))

        commands.refuse = false
        assertTrue(holders.take("finish:b"))
        assertEquals(listOf("START 1", "START 2"), commands.sent)
    }

    @Test
    fun `after the service lost the foreground the next holder asks again`() {
        // Its time for the day was used up, or startForeground was refused as it started.
        holders.take("finish:a")
        holders.foreground()
        holders.leftForeground(1)

        assertTrue(holders.take("finish:b"))
        assertEquals(listOf("START 1", "START 2"), commands.sent)
    }

    @Test
    fun `the loss of an older start leaves the newer one running`() {
        // The service left the foreground for the STOP of 1 while the START of 2 was on its way.
        holders.take("rewrite:a")
        holders.release("rewrite:a")
        holders.take("finish:b")

        holders.leftForeground(1)

        assertTrue(holders.take("map"))
        assertEquals(listOf("START 1", "STOP 1", "START 2"), commands.sent)
    }

    @Test
    fun `the stop after a refused start still stops the service`() {
        // The service may still run for an earlier start whose stop it found stale.
        holders.take("rewrite:a")
        holders.release("rewrite:a")
        commands.refuse = true
        holders.take("finish:b")

        holders.release("finish:b")

        assertEquals(listOf("START 1", "STOP 1", "START 2", "STOP 2"), commands.sent)
        assertTrue(holders.stopHolds(2))
    }

    @Test
    fun `the service protects once it reports the foreground, not when its start is sent`() {
        holders.take("finish:a")
        assertFalse(holders.protected.value)

        holders.foreground()
        assertTrue(holders.protected.value)
    }

    @Test
    fun `a refused start doesn't protect`() {
        commands.refuse = true
        holders.take("finish:a")

        assertFalse(holders.protected.value)
    }

    @Test
    fun `the foreground protects nothing without a holder`() {
        // The START arrives after the holder released the service: its STOP is on its way.
        holders.take("finish:a")
        holders.release("finish:a")

        holders.foreground()

        assertFalse(holders.protected.value)
    }

    @Test
    fun `the last release ends the protection, an earlier one doesn't`() {
        holders.take("finish:a")
        holders.take("map")
        holders.foreground()

        holders.release("finish:a")
        assertTrue(holders.protected.value)
        holders.release("map")
        assertFalse(holders.protected.value)
    }

    @Test
    fun `losing the foreground ends the protection`() {
        holders.take("finish:a")
        holders.foreground()

        holders.leftForeground(1)

        assertFalse(holders.protected.value)
    }
}
