package com.satori.feature.stt.datasource

import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.LoadSupersededException
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One capture's speech model: the live model of the recording's language, loaded as the capture
 * begins, in place of one whose files were still read in when the language was switched; changed
 * between utterances once the language is switched, inside the decode lock, only by the newest
 * capture; a model that doesn't load leaves the one before in, tells the recorder, and isn't tried
 * again until the language has asked for another one.
 */
class CaptureSpeechModelTest {

    // A load that never ends, as when a capture's cancel no longer stops it, fails its test here
    // instead of hanging the build (review of 0.7.1).
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    /** A recognizer of [model]. */
    private class Recognizer(val model: SpeechModelId)

    /** The recording's language as its recorder holds it, with the refusals it was told of. */
    private class Language(@Volatile override var current: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN) : CaptureLanguage {
        val refusals: MutableList<Pair<RecordingLanguage, RecordingLanguage>> = Collections.synchronizedList(mutableListOf())

        override fun refused(wanted: RecordingLanguage, instead: RecordingLanguage) {
            refusals += wanted to instead
        }
    }

    // What happened to the shared recognizer, in order.
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // The models whose load fails.
    private val failing: MutableSet<SpeechModelId> = Collections.synchronizedSet(mutableSetOf())

    private val shared = LiveRecognizer<SpeechModelId, Recognizer>(
        load = { model, _ ->
            events += "load ${model.id}"
            if (model in failing) throw IllegalStateException("${model.id} didn't load")
            Recognizer(model)
        },
        free = { model, _ -> events += "free ${model.id}" }
    )
    private val decodeLock = Mutex()
    private val language = Language()

    @Volatile
    private var newest = true

    private fun captureModel() = CaptureSpeechModel(
        shared, language, decodeLock, isNewest = { newest }, io = Dispatchers.Default, warn = { message, _ -> events += "warn $message" }
    )

    @Test
    fun `a capture loads the model of the recording's language, freeing another language's first`() = runBlocking {
        shared.get(SpeechModelId.INDICCONFORMER_ML)
        events.clear()
        val model = captureModel()
        assertEquals(SpeechModelId.PARAKEET_V3, model.model())

        val recognizer = model.load()

        assertEquals(SpeechModelId.PARAKEET_V3, recognizer.model)
        assertEquals(listOf("free indicconformer-ml-int8", "load parakeet-tdt-0.6b-v3-int8"), events.toList())
        assertEquals(SpeechModelId.PARAKEET_V3, model.model())
        assertNull(model.switchDue())
    }

    @Test
    fun `a Malayalam recording loads the Malayalam model, and one in memory already is kept`() = runBlocking {
        language.current = RecordingLanguage.MALAYALAM
        shared.get(SpeechModelId.INDICCONFORMER_ML)
        events.clear()

        val recognizer = captureModel().load()

        assertEquals(SpeechModelId.INDICCONFORMER_ML, recognizer.model)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a switch of the language makes the change due, which frees the model before and loads the new one`() = runBlocking {
        val model = captureModel()
        assertNull("nothing to change before the capture's own load", model.switchDue())
        model.load()
        events.clear()

        language.current = RecordingLanguage.MALAYALAM

        assertEquals(SpeechModelId.INDICCONFORMER_ML, model.switchDue())
        assertTrue(model.switchTo(SpeechModelId.INDICCONFORMER_ML))
        assertEquals(listOf("free parakeet-tdt-0.6b-v3-int8", "load indicconformer-ml-int8"), events.toList())
        assertEquals(SpeechModelId.INDICCONFORMER_ML, model.model())
        assertEquals(SpeechModelId.INDICCONFORMER_ML, shared.model)
        assertNull(model.switchDue())
        assertTrue(language.refusals.isEmpty())
        // And back to English or German.
        language.current = RecordingLanguage.ENGLISH_OR_GERMAN
        assertEquals(SpeechModelId.PARAKEET_V3, model.switchDue())
        assertTrue(model.switchTo(SpeechModelId.PARAKEET_V3))
        assertEquals(SpeechModelId.PARAKEET_V3, shared.model)
    }

    @Test
    fun `a capture that a newer one cut short leaves the model to that one`() = runBlocking {
        val model = captureModel()
        model.load()

        newest = false
        language.current = RecordingLanguage.MALAYALAM

        assertNull(model.switchDue())
    }

    @Test
    fun `a model that doesn't load leaves the one before in, tells the recorder, and isn't tried again until the language changes`() = runBlocking {
        failing += SpeechModelId.INDICCONFORMER_ML
        val model = captureModel()
        model.load()
        events.clear()
        language.current = RecordingLanguage.MALAYALAM

        assertFalse(model.switchTo(SpeechModelId.INDICCONFORMER_ML))

        assertEquals(
            listOf("free parakeet-tdt-0.6b-v3-int8", "load indicconformer-ml-int8"),
            events.filterNot { it.startsWith("warn") }.take(2)
        )
        assertEquals("load parakeet-tdt-0.6b-v3-int8", events.last())
        assertTrue(events.any { it.startsWith("warn Live speech model indicconformer-ml-int8 failed to load (IllegalStateException)") })
        assertEquals(SpeechModelId.PARAKEET_V3, model.model())
        assertEquals(SpeechModelId.PARAKEET_V3, shared.model)
        assertEquals(listOf(RecordingLanguage.MALAYALAM to RecordingLanguage.ENGLISH_OR_GERMAN), language.refusals.toList())
        // Not again while the recording is still in Malayalam: not at every read.
        assertNull(model.switchDue())
        // The recorder takes the switch back; picked again later, it is tried once more.
        language.current = RecordingLanguage.ENGLISH_OR_GERMAN
        assertNull(model.switchDue())
        language.current = RecordingLanguage.MALAYALAM
        assertEquals(SpeechModelId.INDICCONFORMER_ML, model.switchDue())
    }

    @Test
    fun `when the model before doesn't load again either, the change fails and none is in memory`() = runBlocking {
        val model = captureModel()
        model.load()
        failing += setOf(SpeechModelId.INDICCONFORMER_ML, SpeechModelId.PARAKEET_V3)
        language.current = RecordingLanguage.MALAYALAM

        val failure = runCatching { model.switchTo(SpeechModelId.INDICCONFORMER_ML) }.exceptionOrNull()

        assertTrue("failed with $failure", failure is IllegalStateException)
        assertNull(shared.model)
        assertFalse(shared.loaded.value)
    }

    @Test
    fun `the change holds the decode lock from the release to the end of the load, so a waiting decode gets the new model`() = runBlocking {
        val loading = CompletableDeferred<Unit>()
        val letLoad = CompletableDeferred<Unit>()
        val slow = LiveRecognizer<SpeechModelId, Recognizer>(
            load = { model, _ ->
                if (model == SpeechModelId.INDICCONFORMER_ML) {
                    loading.complete(Unit)
                    runBlocking { withTimeout(5_000) { letLoad.await() } }
                }
                Recognizer(model)
            },
            free = { _, _ -> }
        )
        val model = CaptureSpeechModel(slow, language, decodeLock, isNewest = { true }, io = Dispatchers.Default, warn = { _, _ -> })
        model.load()
        language.current = RecordingLanguage.MALAYALAM

        val switching = async(Dispatchers.Default) { model.switchTo(SpeechModelId.INDICCONFORMER_ML) }
        withTimeout(5_000) { loading.await() }
        // A decode comes while the new model loads: it waits for the lock, and doesn't find the old one gone.
        val decode = async(Dispatchers.Default) { decodeLock.withLock { slow.loadedOrNull()?.model } }
        delay(100)
        assertFalse(decode.isCompleted)
        letLoad.complete(Unit)

        assertTrue(withTimeout(5_000) { switching.await() })
        assertEquals(SpeechModelId.INDICCONFORMER_ML, withTimeout(5_000) { decode.await() })
    }

    /**
     * A live recognizer whose loads read their files in first, as ModelLoadPacing's pre-read does:
     * Parakeet's until [letPreReadEnd], polling the load's check as the pre-read does at each piece,
     * and stopping when it says the model is no longer wanted; any other model's at once.
     */
    private inner class PreReading {
        val preReading = CountDownLatch(1)
        val letPreReadEnd = CountDownLatch(1)

        val recognizer = LiveRecognizer<SpeechModelId, Recognizer>(
            load = { model, stop ->
                events += "pre-read ${model.id}"
                if (model == SpeechModelId.PARAKEET_V3) {
                    preReading.countDown()
                    while (!letPreReadEnd.await(5, TimeUnit.MILLISECONDS)) {
                        stop()?.let { why ->
                            events += "stopped ${model.id}, ${why.name}"
                            throw LoadSupersededException("${model.id} is no longer wanted", why)
                        }
                    }
                }
                stop()?.let { throw LoadSupersededException("${model.id} is no longer wanted", it) }
                events += "load ${model.id}"
                if (model in failing) throw IllegalStateException("${model.id} didn't load")
                Recognizer(model)
            },
            free = { model, _ -> events += "free ${model.id}" }
        )

        /** Whether Stop came, in a recording that keeps its audio. */
        @Volatile var stoppedWithAudio = false

        fun captureModel() = CaptureSpeechModel(
            recognizer, language, decodeLock, isNewest = { true }, io = Dispatchers.Default,
            warn = { message, _ -> events += "warn $message" }, log = { events += "log $it" },
            stopsAtStop = { stoppedWithAudio }
        )
    }

    @Test
    fun `a switch while the model's files are read in stops that load, and the new language's model loads in its place`() = runBlocking {
        val loads = PreReading()
        val model = loads.captureModel()

        val loading = async(Dispatchers.Default) { model.load() }
        assertTrue(loads.preReading.await(5, TimeUnit.SECONDS))
        language.current = RecordingLanguage.MALAYALAM
        val recognizer = withTimeout(5_000) { loading.await() }

        assertEquals(SpeechModelId.INDICCONFORMER_ML, recognizer.model)
        // Parakeet's files were read in part, and Parakeet itself never loaded, so never took its memory.
        assertEquals(
            listOf(
                "pre-read parakeet-tdt-0.6b-v3-int8",
                "stopped parakeet-tdt-0.6b-v3-int8, SWITCHED",
                "log Live speech model parakeet-tdt-0.6b-v3-int8 not loaded, as the recording switched to indicconformer-ml-int8 first",
                "pre-read indicconformer-ml-int8",
                "load indicconformer-ml-int8"
            ),
            events.toList()
        )
        assertEquals(SpeechModelId.INDICCONFORMER_ML, model.model())
        assertEquals(SpeechModelId.INDICCONFORMER_ML, loads.recognizer.model)
        assertNull(model.switchDue())
        assertTrue(language.refusals.isEmpty())
    }

    @Test
    fun `a model switched to while the first one's files were read in that doesn't load leaves the capture on the first one, told to the recorder`() = runBlocking {
        failing += SpeechModelId.INDICCONFORMER_ML
        val loads = PreReading()
        val model = loads.captureModel()

        val loading = async(Dispatchers.Default) { model.load() }
        assertTrue(loads.preReading.await(5, TimeUnit.SECONDS))
        language.current = RecordingLanguage.MALAYALAM
        // Parakeet's files are read in in full the second time.
        loads.letPreReadEnd.countDown()
        val recognizer = withTimeout(5_000) { loading.await() }

        // The recording goes on, in English or German, as after a switch whose model didn't load.
        assertEquals(SpeechModelId.PARAKEET_V3, recognizer.model)
        assertEquals(SpeechModelId.PARAKEET_V3, model.model())
        assertEquals(SpeechModelId.PARAKEET_V3, loads.recognizer.model)
        assertEquals(listOf(RecordingLanguage.MALAYALAM to RecordingLanguage.ENGLISH_OR_GERMAN), language.refusals.toList())
        assertTrue(events.toString(), events.any { it.startsWith("warn Live speech model indicconformer-ml-int8 failed to load (IllegalStateException)") })
        assertEquals("load parakeet-tdt-0.6b-v3-int8", events.last())
        // Not tried again while the recording is still in Malayalam.
        assertNull(model.switchDue())
    }

    @Test
    fun `a model whose files are read in when nothing is switched loads as before`() = runBlocking {
        val loads = PreReading()
        val model = loads.captureModel()

        val loading = async(Dispatchers.Default) { model.load() }
        assertTrue(loads.preReading.await(5, TimeUnit.SECONDS))
        loads.letPreReadEnd.countDown()

        assertEquals(SpeechModelId.PARAKEET_V3, withTimeout(5_000) { loading.await() }.model)
        assertEquals(listOf("pre-read parakeet-tdt-0.6b-v3-int8", "load parakeet-tdt-0.6b-v3-int8"), events.toList())
    }

    @Test
    fun `a capture cancelled while its model's files are read in stops the load, and nothing is in memory`() = runBlocking {
        val loads = PreReading()
        val model = loads.captureModel()

        val loading = async(Dispatchers.Default) { model.load() }
        assertTrue(loads.preReading.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { loading.cancelAndJoin() }

        assertTrue(runCatching { loading.await() }.exceptionOrNull() is CancellationException)
        // Stopped as the capture ended, which the field log then says, not as a switch.
        assertEquals(listOf("pre-read parakeet-tdt-0.6b-v3-int8", "stopped parakeet-tdt-0.6b-v3-int8, ENDED"), events.toList())
        assertNull(loads.recognizer.model)
        assertFalse(loads.recognizer.loaded.value)
    }

    @Test
    fun `Stop while the model's files are read in, in a recording that keeps its audio, stops the load and loads nothing`() = runBlocking {
        val loads = PreReading()
        val model = loads.captureModel()

        // Its failure kept in the result, so it fails no parent of the test.
        val loading = async(Dispatchers.Default) { runCatching { model.load() } }
        assertTrue(loads.preReading.await(5, TimeUnit.SECONDS))
        loads.stoppedWithAudio = true
        // Also when the language was switched meanwhile: after Stop nothing loads.
        language.current = RecordingLanguage.MALAYALAM

        val failure = withTimeout(5_000) { loading.await() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is StoppedBeforeModelException)
        assertEquals(SpeechModelId.PARAKEET_V3, (failure as StoppedBeforeModelException).model)
        assertEquals(listOf("pre-read parakeet-tdt-0.6b-v3-int8", "stopped parakeet-tdt-0.6b-v3-int8, STOPPED"), events.toList())
        assertNull(loads.recognizer.model)
        assertFalse(loads.recognizer.loaded.value)
    }
}
