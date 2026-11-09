package com.satori.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The statuses of reader_status.h become their exceptions, and the options keep reader_jni.cpp's layout. */
class ReaderStatusTest {

    @Test
    fun eachStatusBecomesItsException() {
        val expected = mapOf(
            ReaderStatus.CANCELLED to ReaderException.Cancelled::class,
            ReaderStatus.BAD_INPUT to ReaderException.BadInput::class,
            ReaderStatus.BAD_MODEL to ReaderException.BadModel::class,
            ReaderStatus.LOAD_FAILED to ReaderException.LoadFailed::class,
            ReaderStatus.RUN_FAILED to ReaderException.RunFailed::class,
            ReaderStatus.OUT_OF_MEMORY to ReaderException.OutOfMemory::class,
            ReaderStatus.NOT_LOADED to ReaderException.NotLoaded::class,
            ReaderStatus.BUSY to ReaderException.Busy::class,
            ReaderStatus.INTERNAL to ReaderException.Internal::class,
        )
        for ((status, type) in expected) {
            val exception = ReaderStatus.exception(status, "message $status")
            assertEquals(type, exception::class)
            assertEquals(status, exception.status)
            assertEquals("message $status", exception.message)
        }
    }

    @Test
    fun theNumbersAreTheNativeOnes() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(
                ReaderStatus.OK, ReaderStatus.CANCELLED, ReaderStatus.BAD_INPUT, ReaderStatus.BAD_MODEL,
                ReaderStatus.LOAD_FAILED, ReaderStatus.RUN_FAILED, ReaderStatus.OUT_OF_MEMORY,
                ReaderStatus.NOT_LOADED, ReaderStatus.BUSY, ReaderStatus.INTERNAL,
            ),
        )
    }

    @Test
    fun anUnknownStatusIsInternalAndNamed() {
        val exception = ReaderStatus.exception(42, "odd")
        assertTrue(exception is ReaderException.Internal)
        assertEquals("status 42: odd", exception.message)
    }

    @Test
    fun okThrowsNothingAndReadsNoMessage() {
        ReaderStatus.check(ReaderStatus.OK) { throw AssertionError("message read for OK") }
    }

    @Test
    fun aFailureThrowsWithItsMessage() {
        try {
            ReaderStatus.check(ReaderStatus.BAD_MODEL) { "decoder_model: no float logits" }
            throw AssertionError("no exception")
        } catch (e: ReaderException.BadModel) {
            assertEquals("decoder_model: no float logits", e.message)
        }
    }

    @Test
    fun optionsKeepTheJniLayout() {
        val defaults = ReaderOptions().toIntArray()
        assertArrayEquals(intArrayOf(4, 1, 0, 1, 1, 1, 256, 256, 122_706, 2, 2, 1), defaults)
        val arm = ReaderOptions(threads = 2, prepackDecoder = true, cpuArena = false, padId = -1).toIntArray()
        assertArrayEquals(intArrayOf(2, 1, 1, 1, 0, 1, 256, 256, 122_706, 2, 2, -1), arm)
    }

    @Test
    fun theOutputCapIsD15s() {
        assertEquals(10, ReaderEngine.maxNewTokens(0))
        assertEquals(12, ReaderEngine.maxNewTokens(1))
        assertEquals(80, ReaderEngine.maxNewTokens(35))
        assertEquals(255, ReaderEngine.maxNewTokens(ReaderEngine.MAX_SOURCE_PIECES))
        assertEquals(255, ReaderEngine.maxNewTokens(123))
        assertEquals(254, ReaderEngine.maxNewTokens(122))
        assertEquals(10, ReaderEngine.maxNewTokens(-5))
    }
}
