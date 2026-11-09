package com.satori.domain.usecase.place

import com.satori.domain.place.LocationProvider
import com.satori.domain.usecase.place.TakeLocationFixUseCase.Step
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fix rules: a last satellite fix at most 2 minutes old is taken as it is; else a new one is
 * waited for, up to 30 s in the app (a tenth of a second here); else the newest last position of
 * any provider at most 10 minutes old, counted from the start of the recording, read both at the
 * start and after the wait.
 */
class TakeLocationFixUseCaseTest {

    private val source = FakeLocationSource()
    private val start = source.now
    private val take = TakeLocationFixUseCase(source, currentFixTimeoutMs = TIMEOUT_MS)

    private val home = fix(50.94, 6.96, secondsAgo = 0)

    @Test
    fun `the app waits half a minute for a new satellite fix`() {
        assertEquals(30_000L, TakeLocationFixUseCase.CURRENT_FIX_TIMEOUT_MS)
    }

    @Test
    fun `a last satellite fix under 2 minutes old is taken without asking for a new one`() = runBlocking {
        val recent = fix(50.94, 6.96, secondsAgo = 119)
        source.last[LocationProvider.GPS] = recent

        assertEquals(TakeLocationFixUseCase.Found(recent, Step.LAST_GPS), take(start))
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `a last satellite fix exactly 2 minutes old is still taken`() = runBlocking {
        val recent = fix(50.94, 6.96, secondsAgo = 120)
        source.last[LocationProvider.GPS] = recent

        assertEquals(TakeLocationFixUseCase.Found(recent, Step.LAST_GPS), take(start))
    }

    @Test
    fun `an older last satellite fix is not enough, and a new one is asked for`() = runBlocking {
        source.last[LocationProvider.GPS] = fix(50.94, 6.96, secondsAgo = 121)
        source.current = { home }

        assertEquals(TakeLocationFixUseCase.Found(home, Step.CURRENT_GPS), take(start))
        assertEquals(listOf(LocationProvider.GPS), source.asked)
    }

    @Test
    fun `a new fix that comes while the recording goes on is taken`() = runBlocking {
        source.current = {
            delay(TIMEOUT_MS / 4)
            home
        }

        assertEquals(TakeLocationFixUseCase.Found(home, Step.CURRENT_GPS), take(start))
    }

    @Test
    fun `without a new fix in time the newest last position under 10 minutes old is taken`() = runBlocking {
        val network = fix(50.95, 6.97, secondsAgo = 300, accuracy = 40F)
        val passive = fix(50.96, 6.98, secondsAgo = 200, accuracy = 12F)
        val oldGps = fix(50.97, 6.99, secondsAgo = 500)
        source.last[LocationProvider.NETWORK] = network
        source.last[LocationProvider.PASSIVE] = passive
        source.last[LocationProvider.GPS] = oldGps

        val started = System.nanoTime()
        val found = take(start)
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(TakeLocationFixUseCase.Found(passive, Step.LAST_KNOWN), found)
        assertTrue("waited $waitedMs ms", waitedMs >= TIMEOUT_MS)
    }

    @Test
    fun `a last satellite fix too old for the first step can still be the fallback`() = runBlocking {
        val oldGps = fix(50.97, 6.99, secondsAgo = 500)
        source.last[LocationProvider.GPS] = oldGps

        assertEquals(TakeLocationFixUseCase.Found(oldGps, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `a last position over 10 minutes old is not taken`() = runBlocking {
        source.last[LocationProvider.NETWORK] = fix(50.95, 6.97, secondsAgo = 601)

        assertNull(take(start))
    }

    @Test
    fun `a last position exactly 10 minutes old is still taken`() = runBlocking {
        val network = fix(50.95, 6.97, secondsAgo = 600, accuracy = 40F)
        source.last[LocationProvider.NETWORK] = network

        assertEquals(TakeLocationFixUseCase.Found(network, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `ages are counted from the start, not from the end of the wait`() = runBlocking {
        // 9 min 50 s before the start, so over 10 minutes old once the wait is over.
        val network = fix(50.95, 6.97, secondsAgo = 590, accuracy = 40F)
        source.last[LocationProvider.NETWORK] = network
        source.current = {
            source.now += 60 * SECOND_NANOS
            awaitCancellation()
        }

        assertEquals(TakeLocationFixUseCase.Found(network, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `positions read at the start still count when none can be read after the wait`() = runBlocking {
        // Android may refuse the last positions to an app that has left the screen meanwhile.
        val network = fix(50.95, 6.97, secondsAgo = 60, accuracy = 40F)
        source.last[LocationProvider.NETWORK] = network
        source.current = {
            source.last.clear()
            awaitCancellation()
        }

        assertEquals(TakeLocationFixUseCase.Found(network, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `a position another app got during the wait beats an older one read at the start`() = runBlocking {
        source.last[LocationProvider.NETWORK] = fix(50.95, 6.97, secondsAgo = 300, accuracy = 40F)
        val duringWait = fix(50.96, 6.98, secondsAgo = -10, accuracy = 15F)
        source.current = {
            source.last[LocationProvider.PASSIVE] = duringWait
            awaitCancellation()
        }

        assertEquals(TakeLocationFixUseCase.Found(duringWait, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `a provider that gives up at once leads straight to the last positions`() = runBlocking {
        val network = fix(50.95, 6.97, secondsAgo = 60, accuracy = 40F)
        source.last[LocationProvider.NETWORK] = network
        source.current = { null }

        val started = System.nanoTime()
        assertEquals(TakeLocationFixUseCase.Found(network, Step.LAST_KNOWN), take(start))
        assertTrue((System.nanoTime() - started) / 1_000_000 < TIMEOUT_MS)
    }

    @Test
    fun `a position another app got after the start counts as fresh`() = runBlocking {
        val afterStart = fix(50.95, 6.97, secondsAgo = -20)
        source.last[LocationProvider.PASSIVE] = afterStart

        assertEquals(TakeLocationFixUseCase.Found(afterStart, Step.LAST_KNOWN), take(start))
    }

    @Test
    fun `with nothing at all there is no fix`() = runBlocking {
        assertNull(take(start))
    }

    private companion object {
        const val TIMEOUT_MS = 100L
    }
}
