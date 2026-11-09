package com.satori.feature.stt.datasource

import android.content.pm.ApplicationInfo
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.LoadSupersededException
import com.satori.data.monitor.MemoryReading
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.data.monitor.ReleasePurge
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The live speech model's recognizer: loaded once when asked for, freed by a release (off the
 * caller's thread, once no decode runs), and whether it is in memory, which the finisher of
 * recordings waits on before it loads Gemma 4 E2B; another language's model takes its place, the
 * one freed before the other loads, and a capture decodes with its own model only; a load stopped
 * as its model is no longer wanted leaves none in memory; and its memory lines in a debug build.
 */
class LiveRecognizerTest {

    /** A recognizer, told apart by the load that made it, of the model it was loaded for. */
    private class Model(val load: Int, val of: String = PARAKEET)

    private companion object {
        const val PARAKEET = "parakeet"
        const val MALAYALAM = "malayalam"
    }

    // What happened, in order: each load and each free, with the model.
    private val events = CopyOnWriteArrayList<String>()
    private val loads = CopyOnWriteArrayList<Model>()
    private val freed = CopyOnWriteArrayList<Model>()
    private val live = LiveRecognizer<String, Model>(
        load = { model, _ ->
            events += "load $model"
            Model(loads.size + 1, model).also { loads += it }
        },
        free = { model, recognizer ->
            events += "free $model"
            freed += recognizer
        }
    )

    @Test
    fun `nothing is in memory until a recognizer is asked for, which loads it once`() {
        assertFalse(live.loaded.value)
        assertNull(live.loadedOrNull())
        assertNull(live.model)
        assertTrue(loads.isEmpty())

        val first = live.get(PARAKEET)

        assertTrue(live.loaded.value)
        assertSame(first, live.get(PARAKEET))
        assertSame(first, live.loadedOrNull())
        assertEquals(PARAKEET, live.model)
        assertEquals(1, loads.size)
    }

    @Test
    fun `a release frees it and says it is out, and the next one asked for is loaded anew`() {
        val first = live.get(PARAKEET)

        live.release()

        assertEquals(listOf(first), freed)
        assertFalse(live.loaded.value)
        assertNull(live.loadedOrNull())
        assertNull(live.model)
        val second = live.get(PARAKEET)
        assertNotSame(first, second)
        assertTrue(live.loaded.value)
        assertEquals(2, loads.size)
    }

    @Test
    fun `a release with none in memory frees nothing`() {
        live.release()
        live.get(PARAKEET)
        live.release()
        live.release()

        assertEquals(1, freed.size)
        assertFalse(live.loaded.value)
    }

    @Test
    fun `another model asked for frees the one in memory before it loads, so the two are never in memory together`() {
        val parakeet = live.get(PARAKEET)

        val malayalam = live.get(MALAYALAM)

        assertEquals(listOf("load $PARAKEET", "free $PARAKEET", "load $MALAYALAM"), events.toList())
        assertEquals(listOf(parakeet), freed)
        assertEquals(MALAYALAM, malayalam.of)
        assertSame(malayalam, live.loadedOrNull())
        assertEquals(MALAYALAM, live.model)
        assertTrue(live.loaded.value)
        // And back: a switch to English or German while recording.
        live.get(PARAKEET)
        assertEquals(listOf("free $MALAYALAM", "load $PARAKEET"), events.drop(3))
        assertEquals(PARAKEET, live.model)
    }

    @Test
    fun `a capture asks for its own model's recognizer only, and one cut short by a newer capture in another language gets none`() {
        val parakeet = live.get(PARAKEET)
        assertSame(parakeet, live.loadedOrNull(PARAKEET))
        assertNull(live.loadedOrNull(MALAYALAM))

        // The newer capture records in Malayalam: the older one's last words find no recognizer of theirs.
        val malayalam = live.get(MALAYALAM)

        assertNull(live.loadedOrNull(PARAKEET))
        assertSame(malayalam, live.loadedOrNull(MALAYALAM))
        assertSame("any model's, unasked", malayalam, live.loadedOrNull())
        live.release()
        assertNull(live.loadedOrNull(MALAYALAM))
    }

    @Test
    fun `a load of another model that fails leaves none in memory, and the one before can be loaded again`() {
        val failing = LiveRecognizer<String, Model>(
            load = { model, _ ->
                events += "load $model"
                if (model == MALAYALAM) throw IllegalStateException("Speech model malayalam not downloaded")
                Model(loads.size + 1, model).also { loads += it }
            },
            free = { model, recognizer ->
                events += "free $model"
                freed += recognizer
            }
        )
        failing.get(PARAKEET)

        val failure = runCatching { failing.get(MALAYALAM) }.exceptionOrNull()

        assertTrue("failed with $failure", failure is IllegalStateException)
        assertNull(failing.loadedOrNull())
        assertNull(failing.model)
        assertFalse(failing.loaded.value)
        val again = failing.get(PARAKEET)
        assertEquals(PARAKEET, again.of)
        assertTrue(failing.loaded.value)
        assertEquals(listOf("load $PARAKEET", "free $PARAKEET", "load $MALAYALAM", "load $PARAKEET"), events.toList())
    }

    @Test
    fun `two that ask while none is in memory get the same one, loaded once`() {
        val loading = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val slow = LiveRecognizer<String, Model>(
            load = { _, _ ->
                loading.countDown()
                check(goOn.await(5, TimeUnit.SECONDS)) { "the load was never let go on" }
                Model(loads.size + 1).also { loads += it }
            },
            free = { _, _ -> }
        )
        var first: Model? = null
        var second: Model? = null

        val one = thread { first = slow.get(PARAKEET) }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val other = thread { second = slow.get(PARAKEET) }
        // Time for the second to reach the load while the first one's still runs.
        Thread.sleep(100)
        goOn.countDown()
        one.join(5_000)
        other.join(5_000)

        assertEquals(1, loads.size)
        assertSame(first, second)
        assertTrue(slow.loaded.value)
    }

    @Test
    fun `Stop's release frees the recognizer off the caller's thread, once the decode under way is done`() = runBlocking {
        val freedOn = CopyOnWriteArrayList<Thread>()
        val stopping = LiveRecognizer<String, Model>(load = { _, _ -> Model(1) }, free = { _, _ -> freedOn += Thread.currentThread() })
        stopping.get(PARAKEET)
        val decodeLock = Mutex()
        decodeLock.lock()

        val release = async { stopping.releaseWhenIdle(decodeLock) }
        delay(200)
        // A decode still holds the lock: the recognizer it reads stays.
        assertTrue(freedOn.isEmpty())
        assertTrue(stopping.loaded.value)
        decodeLock.unlock()
        withTimeout(5_000) { release.await() }

        assertEquals(1, freedOn.size)
        // The caller here stands for the main thread, where Stop runs.
        assertNotSame(Thread.currentThread(), freedOn.single())
        assertFalse(stopping.loaded.value)
    }

    @Test
    fun `each live speech model's load and release log their memory lines under its id, a switch of the language both`() {
        val lines = mutableListOf<String>()
        val reading = MemoryReading(memAvailableMb = 3270, rssAnonMb = 200, rssFileMb = 90, psiSomeAvg10 = null, psiFullAvg10 = null)
        val memory = ModelMemoryLines.forFlags(
            appFlags = ApplicationInfo.FLAG_DEBUGGABLE,
            log = { lines += it },
            read = { reading },
            clock = { 0L },
            later = { _, _ -> },
            every = { _, _ -> AutoCloseable {} }
        )
        val measured = liveSpeechRecognizer(memory, load = { model, _ -> Model(1, model.id) }, free = { freed += it })

        measured.get(SpeechModelId.PARAKEET_V3)
        // A switch to Malayalam while recording, then Stop.
        measured.get(SpeechModelId.INDICCONFORMER_ML)
        measured.release()

        assertEquals(
            listOf(
                "Memory: load of parakeet-tdt-0.6b-v3-int8 took 0 ms;",
                "Memory: release of parakeet-tdt-0.6b-v3-int8 took 0 ms;",
                "Memory: load of indicconformer-ml-int8 took 0 ms;",
                "Memory: release of indicconformer-ml-int8 took 0 ms;"
            ),
            lines.map { it.substringBefore(" MemAvailable") }
        )
        assertEquals(listOf("parakeet-tdt-0.6b-v3-int8", "indicconformer-ml-int8"), freed.map { it.of })
        // A load's line has its peak RssAnon, a release's none.
        assertTrue(lines[0], lines[0].contains("RssAnon 200 -> 200 MB (peak 200 MB), RssFile"))
        assertTrue(lines[1], lines[1].contains("RssAnon 200 -> 200 MB, RssFile"))
    }

    // ─── The purge after a release (0.7.2) ───────────────────────────────────────

    /** A debug build's purge whose heap notes each purge in [events], after [whilePurging]. */
    private fun purging(property: String = "", whilePurging: () -> Unit = {}, purge: Purge = Purge.ALL) = ReleasePurge(
        debuggable = true,
        read = { property },
        warn = { events += "W $it" },
        heap = NativeHeap {
            whilePurging()
            events += "purge"
            purge
        }
    )

    private fun memoryLines(lines: MutableList<String>, flags: Int = ApplicationInfo.FLAG_DEBUGGABLE) = ModelMemoryLines.forFlags(
        appFlags = flags,
        log = { lines += it },
        read = { MemoryReading(memAvailableMb = 2269, rssAnonMb = 880, rssFileMb = 138, psiSomeAvg10 = null, psiFullAvg10 = null) },
        clock = { 0L },
        later = { _, _ -> },
        every = { _, _ -> AutoCloseable {} },
        rssAnonMb = { 658L }
    )

    @Test
    fun `a release frees, then purges the native heap, and only then is the recognizer out of memory`() {
        lateinit var measured: LiveRecognizer<SpeechModelId, Model>
        val purge = purging(whilePurging = { events += "loaded ${measured.loaded.value} while purging" })
        measured = liveSpeechRecognizer(
            memoryLines(mutableListOf()),
            load = { model, _ -> Model(1, model.id) },
            free = { events += "free ${it.of}" },
            purge = purge
        )
        measured.get(SpeechModelId.PARAKEET_V3)

        measured.release()

        assertEquals(listOf("free parakeet-tdt-0.6b-v3-int8", "loaded true while purging", "purge"), events.toList())
        assertFalse(measured.loaded.value)
    }

    @Test
    fun `a purge that takes its time keeps the recognizer in memory until it returns, so the finisher waits for it`() {
        val purgeStarted = CountDownLatch(1)
        val purgeMayEnd = CountDownLatch(1)
        val measured = liveSpeechRecognizer(
            memoryLines(mutableListOf()),
            load = { model, _ -> Model(1, model.id) },
            free = {},
            purge = purging(whilePurging = {
                purgeStarted.countDown()
                check(purgeMayEnd.await(5, TimeUnit.SECONDS)) { "the purge was never let end" }
            })
        )
        measured.get(SpeechModelId.PARAKEET_V3)

        val releasing = thread { measured.release() }
        assertTrue(purgeStarted.await(5, TimeUnit.SECONDS))
        assertTrue(measured.loaded.value)
        purgeMayEnd.countDown()
        releasing.join(5_000)

        assertFalse(measured.loaded.value)
    }

    @Test
    fun `a switch of the language frees the one model, purges, then loads the other`() {
        val measured = liveSpeechRecognizer(
            memoryLines(mutableListOf()),
            load = { model, _ -> Model(1, model.id).also { events += "load ${model.id}" } },
            free = { events += "free ${it.of}" },
            purge = purging()
        )
        measured.get(SpeechModelId.PARAKEET_V3)

        measured.get(SpeechModelId.INDICCONFORMER_ML)

        assertEquals(
            listOf("load parakeet-tdt-0.6b-v3-int8", "free parakeet-tdt-0.6b-v3-int8", "purge", "load indicconformer-ml-int8"),
            events.toList()
        )
    }

    @Test
    fun `the release line has RssAnon before, after the free and after the purge, or says the purge was off`() {
        val lines = mutableListOf<String>()
        val on = liveSpeechRecognizer(memoryLines(lines), load = { model, _ -> Model(1, model.id) }, free = {}, purge = purging())
        on.get(SpeechModelId.PARAKEET_V3)
        on.release()
        val off = liveSpeechRecognizer(memoryLines(lines), load = { model, _ -> Model(1, model.id) }, free = {}, purge = purging(property = "off"))
        off.get(SpeechModelId.PARAKEET_V3)
        off.release()

        assertTrue(lines[1], lines[1].contains("; RssAnon 880 -> 658 MB freed -> 880 MB purged (M_PURGE_ALL, 0 ms), RssFile"))
        assertTrue(lines[3], lines[3].contains("; RssAnon 880 -> 880 MB freed, not purged (debug.satori.mem.purge=off), RssFile"))
        // Off, nothing was purged.
        assertEquals(1, events.count { it == "purge" })
    }

    @Test
    fun `a purge that gives nothing back warns once for each release`() {
        val measured = liveSpeechRecognizer(memoryLines(mutableListOf()), load = { model, _ -> Model(1, model.id) }, free = {}, purge = purging(purge = Purge.NONE))

        measured.get(SpeechModelId.PARAKEET_V3)
        measured.release()
        measured.get(SpeechModelId.PARAKEET_V3)
        measured.release()

        assertEquals(
            List(2) { "W Native heap not purged after the release of parakeet-tdt-0.6b-v3-int8: mallopt knows neither M_PURGE_ALL nor M_PURGE" },
            events.filter { it.startsWith("W ") }
        )
    }

    @Test
    fun `a release build frees and purges and logs no memory line`() {
        val lines = mutableListOf<String>()
        val release = ReleasePurge(false, { throw AssertionError("a release build read $it") }, { events += "W $it" }, NativeHeap {
            events += "purge"
            Purge.ALL
        })
        val measured = liveSpeechRecognizer(
            memoryLines(lines, flags = ApplicationInfo.FLAG_HAS_CODE),
            load = { model, _ -> Model(1, model.id) },
            free = { events += "free" },
            purge = release
        )

        measured.get(SpeechModelId.PARAKEET_V3)
        measured.release()

        assertEquals(listOf("free", "purge"), events.toList())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a whole-file run's own recognizer is released the same way, freed then purged`() {
        val lines = mutableListOf<String>()

        speechModelReleased(memoryLines(lines), purging(), SpeechModelId.COHERE_TRANSCRIBE) { events += "free" }

        assertEquals(listOf("free", "purge"), events.toList())
        assertTrue(lines.single(), lines.single().startsWith("Memory: release of ${SpeechModelId.COHERE_TRANSCRIBE.id} took 0 ms;"))
        assertTrue(lines.single(), lines.single().contains("MB freed -> 880 MB purged (M_PURGE_ALL, 0 ms)"))
    }

    @Test
    fun `a load stopped as its model is no longer wanted leaves none in memory, and its memory line says it stopped`() {
        val lines = mutableListOf<String>()
        val reading = MemoryReading(memAvailableMb = 3164, rssAnonMb = 57, rssFileMb = 148, psiSomeAvg10 = null, psiFullAvg10 = null)
        val memory = ModelMemoryLines.forFlags(
            appFlags = ApplicationInfo.FLAG_DEBUGGABLE,
            log = { lines += it },
            read = { reading },
            clock = { 0L },
            later = { _, _ -> },
            every = { _, _ -> AutoCloseable {} }
        )
        val measured = liveSpeechRecognizer(
            memory,
            load = { model, stop ->
                stop()?.let { throw LoadSupersededException("${model.id} is no longer wanted", it) }
                Model(1, model.id)
            },
            free = { freed += it }
        )
        measured.get(SpeechModelId.INDICCONFORMER_ML)

        val failure = runCatching { measured.get(SpeechModelId.PARAKEET_V3) { LoadStop.SWITCHED } }.exceptionOrNull()

        assertTrue("failed with $failure", failure is LoadSupersededException)
        assertNull(measured.model)
        assertFalse(measured.loaded.value)
        assertEquals(
            listOf(
                "Memory: load of indicconformer-ml-int8 took 0 ms;",
                "Memory: release of indicconformer-ml-int8 took 0 ms;",
                "Memory: load of parakeet-tdt-0.6b-v3-int8 stopped after 0 ms;"
            ),
            lines.map { it.substringBefore(" MemAvailable") }
        )
        // The model wanted next loads as any would.
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, measured.get(SpeechModelId.INDICCONFORMER_ML).of)
    }
}
