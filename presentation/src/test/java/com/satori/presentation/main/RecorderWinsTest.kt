package com.satori.presentation.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Work a screen started gives way to a recorder that leaves IDLE, and the stop is told once. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderWinsTest {

    private val recorders = ActiveRecorder()
    private val recorder = Any()
    private val events = mutableListOf<String>()

    // The view model whose work a recorder stopped, and another one of the same screen.
    private val ran = Any()
    private val next = Any()

    @Test
    fun `work runs to its end while no recorder captures, also beside an open recorder that stays idle`() = runTest {
        val alone = recorders.current.runUntilCapture(onStopped = { events += "told" }) { events += "alone" }
        recorders.open(recorder, "r", ActiveRecorder.Activity.IDLE)
        val besideIdle = recorders.current.runUntilCapture(onStopped = { events += "told" }) {
            delay(60_000)
            events += "beside an idle recorder"
        }

        assertTrue(alone)
        assertTrue(besideIdle)
        assertEquals(listOf("alone", "beside an idle recorder"), events)
    }

    @Test
    fun `a recorder that leaves IDLE mid-run stops the work, and the stop is told once the work has ended`() = runTest {
        recorders.open(recorder, "r", ActiveRecorder.Activity.IDLE)
        val began = CompletableDeferred<Unit>()
        val run = async {
            recorders.current.runUntilCapture(onStopped = { events += "told" }) {
                try {
                    began.complete(Unit)
                    delay(60_000)
                    events += "stored"
                } finally {
                    events += "ended"
                }
            }
        }
        began.await()

        recorders.report(recorder, "r", ActiveRecorder.Activity.STARTING)

        assertFalse(run.await())
        assertEquals(listOf("ended", "told"), events)
    }

    @Test
    fun `starting, recording and saving each stop it`() = runTest {
        for (activity in listOf(ActiveRecorder.Activity.STARTING, ActiveRecorder.Activity.RECORDING, ActiveRecorder.Activity.SAVING)) {
            val fresh = ActiveRecorder()
            fresh.open(recorder, "r", ActiveRecorder.Activity.IDLE)
            var told = 0
            val run = async { fresh.current.runUntilCapture(onStopped = { told++ }) { awaitCancellation() } }
            runCurrent()

            fresh.report(recorder, "r", activity)

            assertFalse(activity.name, run.await())
            assertEquals(activity.name, 1, told)
        }
    }

    @Test
    fun `work that ends as a recorder leaves IDLE isn't stopped, and nothing is told`() = runTest {
        // Its last step lets a recorder start, before the watcher has looked: as a store that ends just then.
        val ran = recorders.current.runUntilCapture(onStopped = { events += "told" }) {
            events += "done"
            recorders.expect("r")
        }

        assertTrue(ran)
        assertEquals(listOf("done"), events)
    }

    @Test
    fun `with a recorder capturing already the work doesn't begin, and the stop is told`() = runTest {
        // A quick-key press: the recorder counts as starting before it has opened.
        recorders.expect("r")
        var began = false

        val ran = recorders.current.runUntilCapture(onStopped = { events += "told" }) { began = true }

        assertFalse(ran)
        assertFalse(began)
        assertEquals(listOf("told"), events)
    }

    @Test
    fun `a caller cancelled while the stopped work winds down still tells the stop, after the work has ended`() = runTest {
        val began = CompletableDeferred<Unit>()
        val windingDown = CompletableDeferred<Unit>()
        val caller = launch {
            recorders.current.runUntilCapture(onStopped = { events += "told" }) {
                try {
                    began.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { windingDown.await() }
                    events += "ended"
                }
            }
        }
        began.await()
        recorders.expect("r")
        runCurrent()

        // The quick-key start closes the screen that runs the work, which ends its coroutine.
        caller.cancel()
        windingDown.complete(Unit)
        caller.join()

        assertEquals(listOf("ended", "told"), events)
    }

    @Test
    fun `a store the work began under NonCancellable runs to its end before the stop is told`() = runTest {
        val storing = CompletableDeferred<Unit>()
        val store = CompletableDeferred<Unit>()
        val run = async {
            recorders.current.runUntilCapture(onStopped = { events += "told" }) {
                withContext(NonCancellable) {
                    storing.complete(Unit)
                    store.await()
                    events += "stored"
                }
                delay(1_000)
            }
        }
        storing.await()

        recorders.expect("r")
        runCurrent()
        store.complete(Unit)

        assertFalse(run.await())
        assertEquals(listOf("stored", "told"), events)
    }

    @Test
    fun `a caller cancelled with no recorder, as when the page is left, tells nothing`() = runTest {
        val began = CompletableDeferred<Unit>()
        val caller = launch {
            recorders.current.runUntilCapture(onStopped = { events += "told" }) {
                began.complete(Unit)
                awaitCancellation()
            }
        }
        began.await()

        caller.cancelAndJoin()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a failure of the work goes on to the caller, and no stop is told`() = runTest {
        try {
            recorders.current.runUntilCapture(onStopped = { events += "told" }) { error("the model failed") }
            fail("The failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("the model failed", e.message)
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a stopped work is told once, to its own screen, among the kinds that screen shows`() {
        val stopped = StoppedWork()
        stopped.post(StoppedWork.Kind.REWRITE_NOTES, "s1", ran)
        stopped.post(StoppedWork.Kind.REWRITE_NOTES, "s1", ran)
        stopped.post(StoppedWork.Kind.TRANSCRIBE_AGAIN, "s1", ran)

        val page = setOf(StoppedWork.Kind.REWRITE_NOTES, StoppedWork.Kind.FIND_TODOS)
        assertEquals(emptySet<StoppedWork.Kind>(), stopped.take("s2", page, next))
        assertEquals(setOf(StoppedWork.Kind.REWRITE_NOTES), stopped.take("s1", page, next))
        assertEquals(emptySet<StoppedWork.Kind>(), stopped.take("s1", page, next))
        assertEquals(setOf(StoppedWork.Kind.TRANSCRIBE_AGAIN), stopped.take("s1", setOf(StoppedWork.Kind.TRANSCRIBE_AGAIN), next))
    }

    @Test
    fun `screens are told apart by value`() {
        data class View(val id: String)
        val stopped = StoppedWork()
        stopped.post(StoppedWork.Kind.MAP, View("theme"), ran)

        assertEquals(emptySet<StoppedWork.Kind>(), stopped.take(View("other"), setOf(StoppedWork.Kind.MAP), next))
        assertEquals(setOf(StoppedWork.Kind.MAP), stopped.take(View("theme"), setOf(StoppedWork.Kind.MAP), next))
    }

    @Test
    fun `the view model whose work was stopped never takes the stop, whose screen is closing`() {
        val stopped = StoppedWork()
        stopped.post(StoppedWork.Kind.FIND_TODOS, "s1", ran)

        assertEquals(emptySet<StoppedWork.Kind>(), stopped.take("s1", setOf(StoppedWork.Kind.FIND_TODOS), ran))
        assertEquals(setOf(StoppedWork.Kind.FIND_TODOS), stopped.take("s1", setOf(StoppedWork.Kind.FIND_TODOS), next))
    }

    @Test
    fun `a screen open again as the stopped work ends is told then, and one opened later no more`() = runTest {
        val stopped = StoppedWork()
        val page = setOf(StoppedWork.Kind.REWRITE_NOTES, StoppedWork.Kind.FIND_TODOS)
        val closing = mutableListOf<StoppedWork.Kind>()
        val reopened = mutableListOf<StoppedWork.Kind>()
        val openedLater = mutableListOf<StoppedWork.Kind>()
        // The page whose work stopped is closing, and the owner has opened it again meanwhile.
        backgroundScope.launch { stopped.stopsOn("s1", page, ran).collect { closing += it } }
        backgroundScope.launch { stopped.stopsOn("s1", page, next).collect { reopened += it } }
        runCurrent()
        assertTrue(reopened.isEmpty())

        // The stopped work has wound down, which can take a model load after the stop.
        stopped.post(StoppedWork.Kind.REWRITE_NOTES, "s1", ran)
        runCurrent()
        backgroundScope.launch { stopped.stopsOn("s1", page, Any()).collect { openedLater += it } }
        runCurrent()

        assertEquals(listOf(StoppedWork.Kind.REWRITE_NOTES), reopened)
        assertTrue(closing.isEmpty())
        assertTrue(openedLater.isEmpty())
    }
}
