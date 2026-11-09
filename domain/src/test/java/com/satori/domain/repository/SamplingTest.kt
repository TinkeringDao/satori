package com.satori.domain.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an AI call samples: [Sampling.DEFAULT] (0.7.2 keeps today's values for every call), unless
 * the coroutine that makes it carries an [AiSampling], as a debug build's rerun of a finishing step
 * does; and the field log's form of a sampling.
 */
class SamplingTest {

    @Test
    fun `the default is today's 0_3 after top-k 40, top-p 0_95 and min-p 0_05, and greedy differs only in its temperature`() {
        assertEquals(Sampling(temperature = 0.3f, topK = 40, topP = 0.95f, minP = 0.05f), Sampling.DEFAULT)
        assertEquals(Sampling.DEFAULT.copy(temperature = 0f), Sampling.GREEDY)
    }

    @Test
    fun `the field log names every value of a sampling, with a dot in every locale`() {
        assertEquals("temp 0.30 top-k 40 top-p 0.95 min-p 0.05", Sampling.DEFAULT.describe())
        assertEquals("temp 0.00 top-k 40 top-p 0.95 min-p 0.05", Sampling.GREEDY.describe())
        assertEquals("temp 1.00 top-k 64 top-p 0.95 min-p 0.00", Sampling(1f, topK = 64, minP = 0f).describe())
        assertEquals("AiSampling(temp 0.00 top-k 40 top-p 0.95 min-p 0.05)", AiSampling(Sampling.GREEDY).toString())
    }

    @Test
    fun `values outside the engine's ranges are refused, its bounds are not`() {
        for (make in listOf(
            { Sampling(-0.01f) }, { Sampling(2.01f) }, { Sampling(Float.NaN) },
            { Sampling(0.3f, topK = -1) }, { Sampling(0.3f, topK = 101) },
            { Sampling(0.3f, topP = 1.01f) }, { Sampling(0.3f, topP = -0.1f) },
            { Sampling(0.3f, minP = 1.5f) }, { Sampling(0.3f, minP = -0.01f) }
        )) {
            assertTrue(runCatching(make).exceptionOrNull() is IllegalArgumentException)
        }
        Sampling(0f, topK = 0, topP = 0f, minP = 0f)
        Sampling(2f, topK = 100, topP = 1f, minP = 1f)
    }

    @Test
    fun `a call samples at the default, but inside an AiSampling, its children's too, and at the default again after it`() = runBlocking {
        assertEquals(Sampling.DEFAULT, currentSampling())
        val rerun = Sampling(temperature = 0.7f)
        withContext(AiSampling(rerun)) {
            assertEquals(rerun, currentSampling())
            coroutineScope { assertEquals(rerun, currentSampling()) }
            assertEquals(rerun, async(Dispatchers.Default) { currentSampling() }.await())
            launch { assertEquals(rerun, currentSampling()) }.join()
            // An inner element wins for its own block.
            withContext(AiSampling(Sampling.GREEDY)) { assertEquals(Sampling.GREEDY, currentSampling()) }
            assertEquals(rerun, currentSampling())
        }
        assertEquals(Sampling.DEFAULT, currentSampling())
    }

    @Test
    fun `work handed to a scope of its own, as the finishing queue's, samples at the default`() = runBlocking {
        val queue = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queued = withContext(AiSampling(Sampling.GREEDY)) { queue.async { currentSampling() } }
            assertEquals(Sampling.DEFAULT, queued.await())
        } finally {
            queue.cancel()
        }
    }
}
