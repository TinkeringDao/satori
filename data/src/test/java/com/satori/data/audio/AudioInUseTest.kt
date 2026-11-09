package com.satori.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInUseTest {

    private val usage = AudioInUse()

    @Test
    fun `audio is in use from its first reader's begin until its last reader's end`() {
        usage.begin("rec-1")
        usage.begin("rec-1")
        usage.begin("rec-2")

        usage.end("rec-1")
        assertEquals(setOf("rec-1", "rec-2"), usage.inUse())

        usage.end("rec-1")
        assertEquals(setOf("rec-2"), usage.inUse())

        usage.end("rec-2")
        assertEquals(emptySet<String>(), usage.inUse())
    }

    @Test
    fun `an end without a begin changes nothing, and a later begin still counts`() {
        usage.end("rec-1")
        usage.begin("rec-1")

        assertEquals(setOf("rec-1"), usage.inUse())
    }

    @Test
    fun `what is in use is read as it is then`() {
        usage.begin("rec-1")
        val before = usage.inUse()

        usage.end("rec-1")

        assertEquals(setOf("rec-1"), before)
        assertEquals(emptySet<String>(), usage.inUse())
    }
}
